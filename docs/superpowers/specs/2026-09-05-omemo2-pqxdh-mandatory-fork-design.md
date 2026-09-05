# OMEMO2+PQXDH-mandatory Conversations fork — design spec

## Problem

Build a fork of Conversations (via Monocles Chat, which already has a working,
well-engineered — though not perfect — post-quantum OMEMO implementation) that: (a)
only supports PQ-OMEMO2 encryption, with legacy OMEMO1/plaintext/OTR/PGP removed
entirely, and (b) refuses to let the user compose/send a message to any contact or
group chat that doesn't support PQ-OMEMO2, proactively (compose disabled), not just
reactively (send attempted then fails).

## Note: the fix is scoped to this fork only

The security review (see `pq-omemo2-security-review.md`) found a real, exploitable
vulnerability in Monocles Chat's current code: `AxolotlService.java`'s
`buildSessionFromOmemo2PEP` silently accepts and re-pins an attacker's substituted PQ
identity when the peer's classical fingerprint is already user-verified — defeating
the entire purpose of the hybrid PQ layer (staying secure when classical crypto is
broken). This is fixed in this fork (see section 4) as required scope, not optional
hardening. No upstream report is being filed — this fork's own commit history fixing
the issue will be publicly readable once the repo is public, which functionally
discloses it passively (a diff removing the vulnerable exception is close to
self-explanatory), just without a coordinated heads-up to Monocles first. That
tradeoff is accepted deliberately, not overlooked.

## Goal

A working, publicly-forkable Android XMPP client where:
1. PQ-OMEMO2 (`urn:monocles:omemo-pq:1`) is the only encryption mode — no plaintext,
   OTR, PGP, or legacy OMEMO1.
2. Compose/send is disabled (not just later-failing) for any 1:1 contact or group chat
   where the peer/any current occupant doesn't support PQ-OMEMO2 — hard block, no
   override, matching the "only able to write to addresses that support it" goal.
3. The disclosed re-pinning vulnerability is fixed (mandatory fresh re-verification on
   any PQ-identity change, no sticky-classical-trust exception).
4. Fully GPLv3/FOSS, published at the GitHub location provisioned by the `android-dev`
   workspace template's `forkRepo` parameter.

## Architecture

### 1. Remove legacy OMEMO1

- Delete `src/main/java/eu/siacs/conversations/crypto/axolotl/legacy/` entirely
  (`LegacyAxolotlBackend.java`, `LegacySignalProtocolStore.java`).
- Remove the `org.whispersystems:signal-protocol-java:2.6.2` Gradle dependency
  (`build.gradle:71`).
- `AxolotlService.java` (4659 lines): methodical pass removing every `isOmemo2==false`
  branch from the dozens of flag-branched methods (`deviceListStatus`,
  `hasErrorFetchingDeviceList`, `getDeviceIdsForStack`, `registerDevices`,
  `findDevicesWithoutSession`, `reportBrokenSessionException`, etc.) — this is the bulk
  of the mechanical work, not a rewrite (the flag pattern makes each branch locally
  removable).
- `SQLiteAxolotlStore.java`: remove legacy-stack methods/branches.
- `DatabaseBackend.java`: drop the legacy `sessions`/`prekeys`/`signed_prekeys` tables
  via a migration. The `identities` table is **shared** between stacks (classical
  Curve25519/Ed25519 keys + trust) — cannot be dropped; migration must confirm no
  session in either stack still references a row before any cleanup, per the existing
  code's own documented invariant.
- UI/settings cleanup: `ConversationMenuConfigurator.java` (remove legacy menu item,
  `globalLegacy` checks), `OmemoActivity.java` (remove the `legacy` boolean parameter
  and its fingerprint-format branch), `TrustKeysActivity.java`/`TrustKeys.java` (remove
  `legacyIntentFor`), `AppSettings.java`/`SecuritySettingsFragment.java` (remove
  `LEGACY_OMEMO_ENABLED`/`OMEMO_DEFAULT_LEGACY` preference wiring), delete
  `OmemoDefaultStackNotice.java` entirely (no more stack choice to prompt for),
  `EditAccountActivity.java`, `ExportBackupWorker.java`/`ImportBackupWorker.java` (only
  PQ-OMEMO2 keys to back up/restore now), `XmppUri.java` (remove the legacy fingerprint
  URI format).

### 2. Force mandatory PQ-OMEMO2

- `Config.java`: `ENCRYPTION_MASK = OMEMO` (drop `UNENCRYPTED | OPENPGP | OTR`). This
  single change cascades correctly through the existing `omemoOnly()` →
  `OmemoSetting.load()` chain, which was already built by upstream specifically for
  this purpose (`omemoOnly()` forces `always = true; encryption =
  ENCRYPTION_AXOLOTL_OMEMO2`, bypassing all user preference).
- `defaults.xml`: with legacy code removed, `legacy_omemo_enabled`/
  `omemo_default_legacy`/`allow_unencrypted` become dead preferences rather than
  needing new default values — remove them along with their settings-screen entries
  rather than just flipping defaults.

### 3. Proactive capability gating (the real new logic)

Today's behavior is reactive only: `ConversationFragment.sendMessage()` builds a
message with `conversation.getNextEncryption()` and calls `dispatchMessage()`; the only
existing pre-send gate is `trustKeysIfNeeded` (trust/keys, not peer capability). A
message to an incapable peer currently fails only after `AxolotlService` attempts (and
fails) to fetch a device list/bundle — safe (fails closed, no plaintext leak), but not
what "only able to write to addresses that support it" means: the user should not be
able to attempt composing in the first place.

New design:
- On conversation open (1:1) or occupant-list change (MUC), check PQ-OMEMO2 support:
  reuse the existing `fetchOmemo2DeviceIds`/bundle-fetch mechanism already in
  `AxolotlService` (empty result = unsupported, already correctly fail-closed) rather
  than inventing a new detection path — the gap isn't detection, it's *when* detection
  happens and *what the UI does* with a negative result.
  - Note: `AbstractGenerator.getFeatures()` already advertises `Namespace.OMEMO2 +
    ":pqxdh"`/`":spqr"` disco#info features, but nothing in the codebase consumes them
    from a peer (confirmed: zero matches outside that one file). A disco#info-based
    precheck could short-circuit the slower PEP device-list fetch for peers that don't
    even advertise the feature at all, as a fast-path optimization — worth adding
    alongside the PEP fetch, not instead of it, since disco#info absence doesn't
    guarantee PEP absence and vice versa isn't verified by this codebase's own
    conventions elsewhere.
- Compose/send UI (`ConversationFragment`) disabled entirely (not grayed-out-but-typable
  — actually disabled) with a clear inline explanation ("this contact doesn't support
  the required encryption") when the check comes back negative. No "send anyway"
  escape hatch, matching the hard-block decision already made.
- MUC: check **every current occupant** (`MucOptions` membership list, the same
  device-fan-out set `AxolotlService.encryptOmemo2`/`buildOmemo2Header` already use for
  MUC — reuse, don't reinvent). Any one occupant lacking support blocks the whole room
  (no partial/selective encryption is possible in a shared-room context). Re-check on
  membership changes (join/leave), not just once at room-open.
- Anonymous MUCs remain out of scope for OMEMO2 at all (existing, correct fail-closed
  behavior per the security review's Finding 8) — compose is blocked there
  unconditionally, not conditionally on a capability check that can't even run without
  real JIDs.

### 4. Fix the disclosed vulnerability

`AxolotlService.java`, `buildSessionFromOmemo2PEP`: remove the
`pqChanged && classicalVerified` exception entirely. Any PQ-identity change for an
already-pinned contact is refused outright ("possible downgrade/MITM") regardless of
prior classical-fingerprint verification status — matching `reconcileOmemo2PqPinFromBundle`'s
existing, correct pin-fill-only behavior. A real UI flow for *intentional* re-keying
(e.g. a contact genuinely reinstalled the app and lost their identity) needs deliberate
new UX — an explicit, both-fingerprints-shown re-verification step — not a silent
policy exception. Scoping that new UX is left to the implementation plan.

### 5. Rebranding

New Android `applicationId`/app name/icon, distinguishing this fork from monocles chat
proper. Published at the GitHub location the `android-dev` workspace template's
`forkRepo` parameter already provisions for (`sigalor/conversations-omemo2` is the
placeholder used there — confirm/finalize the real name before Task 1 of the
implementation plan actually creates it).

## Data flow (compose-time gating, the new piece)

1. User opens a conversation (1:1) or a MUC's occupant list changes.
2. App checks PQ-OMEMO2 support: 1:1 via existing `fetchOmemo2DeviceIds`/bundle fetch
   against the peer; MUC via the same check against every current occupant.
3. If any check comes back negative (empty device list = unsupported, per existing
   fail-closed convention): compose/send UI disabled, inline explanation shown.
4. If all checks pass: compose/send enabled normally, using the existing (unmodified)
   PQ-OMEMO2 encrypt/send path.
5. Re-check triggers: MUC occupant join/leave; possibly a manual refresh action for 1:1
   (e.g. peer only just installed a supporting client) — exact re-check triggers are an
   implementation-plan detail, not fixed here beyond "not just once at first open."

## Error handling

- Peer/occupant capability check itself fails (network error, timeout) vs. genuinely
  unsupported (empty result): these must be distinguished in the UI — a transient
  failure should not look identical to "this contact will never support this," and
  should be retryable rather than a permanent-looking block.
- Fixed vulnerability's new refusal path (PQ-identity change rejected outright): must
  surface as a clear, actionable error to the user ("this contact's security keys
  changed unexpectedly — verify before continuing"), not a silent failure.

## Testing

1. Local build via the `android-dev` workspace template (already proven working).
2. Two-account interop testing: two real XMPP accounts (a test server is fine), two
   built instances of this fork — confirm messaging works end-to-end with PQ-OMEMO2 for
   both 1:1 and MUC.
3. Negative-path testing: confirm compose is actually disabled (not just
   fails-on-send) when messaging a contact running an OMEMO2-incapable client (e.g. an
   account with no PQ-OMEMO2 bundle published).
4. Vulnerability-fix verification: reproduce the original attack scenario from the
   security review against the fixed code and confirm it's rejected rather than
   silently accepted.
5. Legacy-removal verification: confirm the app has zero remaining paths to
   plaintext/OTR/PGP/legacy-OMEMO1 messaging (a deliberate attempt to send unencrypted,
   via any settings/menu path, should be impossible, not just discouraged).

## Rollout / sequencing

1. Create the fork repo (GitHub, per the `android-dev` template's `forkRepo`), commit
   this spec.
2. Legacy removal + mandatory-PQ-OMEMO2 forcing (sections 1-2) — the bulk of the
   mechanical work, independently testable (app builds and runs PQ-OMEMO2-only, even
   before proactive gating exists).
3. Vulnerability fix (section 4) — small, isolated, should land early since it's a
   real security fix, not dependent on sections 1-2/5.
4. Proactive capability gating (section 3) — the genuinely new feature work, built on
   top of 1-2 (needs the app to already be PQ-OMEMO2-only for "block if unsupported"
   to be the *only* path, rather than one of several).
5. Rebranding (section 5) — can happen anytime, lowest-risk, likely first for a clean
   initial commit history under the new name.

## Open questions / explicitly out of scope

- The exact re-verification UX for an intentional PQ-identity re-key (section 4) is
  left to the implementation plan.
- Distribution (F-Droid/IzzyOnDroid/GitHub releases) is explicitly out of scope for
  this spec — build-and-run correctness first, distribution is a follow-up project.
- Whether to track monocles' upstream commits going forward (they're actively
  developing this feature, near-daily commits as of Aug 2026) or fork-and-diverge is
  an open question for after the initial fork lands — this spec covers the first cut
  only.
