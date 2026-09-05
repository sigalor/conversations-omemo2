# OMEMO2+PQXDH-Mandatory Fork Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn this Monocles Chat fork into an OMEMO2(PQ)-only client — legacy OMEMO1/plaintext/OTR/PGP removed entirely, compose/send proactively blocked (not just reactively failed) for any contact or MUC that doesn't support PQ-OMEMO2, and the disclosed PQ-identity re-pinning vulnerability fixed.

**Architecture:** Force `Config.ENCRYPTION_MASK = OMEMO` (an existing, already-built lever) to make PQ-OMEMO2 the only mode; mechanically strip the parallel legacy (`isOmemo2==false`) code paths out of `AxolotlService`/`SQLiteAxolotlStore`/`DatabaseBackend` and ~10 UI files; add new proactive capability-checking logic at conversation-open time (1:1 and MUC) that disables compose when a peer/occupant lacks support; fix the vulnerable PQ-identity re-pinning exception.

**Tech Stack:** Java/Kotlin (Android), Gradle 9.4.1 (JDK 21), the `android-dev` workspace-platform template (already verified end-to-end: builds this exact project, 36/38 existing unit tests pass).

**Spec:** `docs/superpowers/specs/2026-09-05-omemo2-pqxdh-mandatory-fork-design.md` (and its companion `docs/superpowers/specs/2026-09-05-pq-omemo2-security-review.md`)

## Global Constraints

- Build/test command (verified working): `./gradlew testMonocleschatFreeDebugUnitTest` from the repo root, inside an `android-dev` workspace (or the equivalent local Docker environment: `android-dev-local` image, built from `workspace-platform`'s `android-dev/Dockerfile`).
- No mocking framework in this project (confirmed: `XmppAxolotlSessionCandidateCapTest.java`'s own doc comment). Tests that need a live `SQLiteAxolotlStore`/`SessionCipher`/Android `Context` cannot run under plain JUnit — extract pure logic into static, parameter-only methods when a piece of logic needs unit-test coverage (the existing `XmppAxolotlSession.capCandidates` is the established precedent for this pattern).
- Existing OMEMO test files to follow as style precedent: `src/test/java/eu/siacs/conversations/crypto/axolotl/{XmppOmemo2MessageTest,XmppAxolotlSessionCandidateCapTest,XmppAxolotlMessageParseTest}.java`, `src/test/java/eu/siacs/conversations/parser/IqParserOmemoTest.java`.
- Every task's "run the tests" step means the full command above, not a narrower `--tests` filter, unless the step explicitly says otherwise — regressions in unrelated tests count as this task's own failure.
- Commit after every task (small, working increments) — this is a large refactor; big-bang single-commit changes make review and bisection much harder.

---

## File Structure

- Modify: `src/main/java/eu/siacs/conversations/crypto/axolotl/AxolotlService.java` (~4600 lines) — strip legacy branches, fix the vulnerability.
- Modify: `src/main/java/eu/siacs/conversations/crypto/axolotl/SQLiteAxolotlStore.java` — strip legacy branches.
- Delete: `src/main/java/eu/siacs/conversations/crypto/axolotl/legacy/` (`LegacyAxolotlBackend.java`, `LegacySignalProtocolStore.java`).
- Modify: `src/main/java/eu/siacs/conversations/persistance/DatabaseBackend.java` — drop legacy tables, migrate the shared `identities` table.
- Modify: `build.gradle` — remove the `org.whispersystems:signal-protocol-java` dependency.
- Modify: `src/main/java/eu/siacs/conversations/Config.java` — `ENCRYPTION_MASK = OMEMO`.
- Modify: `src/main/res/values/defaults.xml` — drop the now-dead `legacy_omemo_enabled`/`omemo_default_legacy` entries.
- Delete: `src/main/java/eu/siacs/conversations/ui/util/OmemoDefaultStackNotice.java`.
- Modify (legacy-path removal, small/mechanical each): `ConversationMenuConfigurator.java`, `OmemoActivity.java`, `TrustKeysActivity.java`, `TrustKeys.java`, `AppSettings.java`, `SecuritySettingsFragment.java`, `EditAccountActivity.java`, `ExportBackupWorker.java`, `ImportBackupWorker.java`, `XmppUri.java`.
- New: capability-gating logic — a new small class, `src/main/java/eu/siacs/conversations/crypto/axolotl/Omemo2CapabilityChecker.java`, plus call sites in `ConversationFragment.java` (compose UI) and `MucOptions.java`-consuming code (occupant re-check).
- Modify: `src/main/java/eu/siacs/conversations/ui/ConversationFragment.java` — wire in compose-disable.
- Modify: `build.gradle`'s `monocleschat` product flavor — rebranding (`applicationId`, app name).

---

### Task 1: Fix the disclosed PQ-identity re-pinning vulnerability

**Files:**
- Modify: `src/main/java/eu/siacs/conversations/crypto/axolotl/AxolotlService.java:2126-2168` (`buildSessionFromOmemo2PEP`)
- Test: full suite (no isolated unit test possible — this method needs a live account/session; see Global Constraints)

**Interfaces:**
- No signature changes — this is a pure internal-behavior fix.

- [ ] **Step 1: Read the current exact code to confirm line numbers still match**

```bash
sed -n '2110,2170p' src/main/java/eu/siacs/conversations/crypto/axolotl/AxolotlService.java
```
Confirm the `pqChanged && !classicalVerified` / `else` structure described below is still there (a prior commit in this plan could have shifted line numbers slightly — match by the code shape, not the literal line numbers if they've drifted).

- [ ] **Step 2: Remove the "already classically verified" exception**

Replace:
```java
                        final FingerprintStatus classicalTrust =
                                getFingerprintTrust(address.getName(), ikFingerprint);
                        final boolean classicalVerified =
                                classicalTrust != null && classicalTrust.isVerified();
                        if (pqChanged && !classicalVerified) {
                            Log.e(Config.LOGTAG, getLogprefix(account) + "PQ identity for "
                                    + ikFingerprint + " CHANGED — refusing OMEMO2 session (possible downgrade/MITM)");
                            preKeyBundle = null;
                        } else {
                            if (pqChanged) {
                                Log.w(Config.LOGTAG, getLogprefix(account) + "PQ identity for "
                                        + ikFingerprint + " changed, but the classical fingerprint is"
                                        + " verified — accepting and re-pinning the new pq_ik");
                            }
                            // Recompute the KEM binding from the fetched bundle so
                            // process() can verify the v2 transcript: if any ML-KEM
                            // pre-key was substituted (the harvest-and-forge vector),
                            // the digest won't match the ML-DSA-87 signature.
                            final byte[] kemBinding =
                                    computeOmemo2KemBindingFromWire(bundle, kemPreKeys);
                            preKeyBundle = plainPreKeyBundle.withPqIdentity(
                                    peerPq.identityKey, peerPq.signature, kemBinding);
                        }
```

with:
```java
                        if (pqChanged) {
                            // A PQ-identity change for an already-pinned classical identity is
                            // ALWAYS refused, regardless of classical-fingerprint trust status.
                            // The entire point of the hybrid layer is to stay secure even when
                            // classical (Ed25519) crypto is broken -- an attacker who has
                            // recovered a peer's classical private key can forge a fully valid
                            // replacement bundle with their OWN new PQ identity, so trusting a
                            // stale classical "verified" flag here would silently downgrade the
                            // hybrid identity to classical-only trust. See
                            // docs/superpowers/specs/2026-09-05-pq-omemo2-security-review.md,
                            // Finding 3, for the full attack chain. A genuine re-key (e.g. a
                            // contact reinstalling and losing their identity) needs an explicit,
                            // deliberate re-verification UX -- not a silent policy exception.
                            Log.e(Config.LOGTAG, getLogprefix(account) + "PQ identity for "
                                    + ikFingerprint + " CHANGED — refusing OMEMO2 session (requires"
                                    + " explicit re-verification, never a silent accept)");
                            preKeyBundle = null;
                        } else {
                            // Recompute the KEM binding from the fetched bundle so
                            // process() can verify the v2 transcript: if any ML-KEM
                            // pre-key was substituted (the harvest-and-forge vector),
                            // the digest won't match the ML-DSA-87 signature.
                            final byte[] kemBinding =
                                    computeOmemo2KemBindingFromWire(bundle, kemPreKeys);
                            preKeyBundle = plainPreKeyBundle.withPqIdentity(
                                    peerPq.identityKey, peerPq.signature, kemBinding);
                        }
```

- [ ] **Step 3: Confirm no other reference to the removed `classicalTrust`/`classicalVerified` locals remains in this method**

```bash
grep -n "classicalTrust\|classicalVerified" src/main/java/eu/siacs/conversations/crypto/axolotl/AxolotlService.java
```
Expected: no matches (both locals were scoped to the deleted block). If `getFingerprintTrust`/`FingerprintStatus` are used elsewhere in the file (they almost certainly are, for the trust-menu UI), do not touch those — only this one call site changes.

- [ ] **Step 4: Build and run the full test suite**

```bash
./gradlew testMonocleschatFreeDebugUnitTest
```
Expected: same pass/fail counts as the pre-existing baseline (36/38, per this plan's Global Constraints) — this change touches no code any current test exercises directly, so zero change in results is the correct outcome, not a false negative.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/eu/siacs/conversations/crypto/axolotl/AxolotlService.java
git commit -m "Fix PQ-identity re-pinning vulnerability: never accept a changed PQ identity, even with verified classical trust"
```

---

### Task 2: Force mandatory PQ-OMEMO2 (remove plaintext/OTR/PGP)

**Files:**
- Modify: `src/main/java/eu/siacs/conversations/Config.java`
- Modify: `src/main/res/values/defaults.xml`
- Test: full suite

**Interfaces:**
- Consumes: nothing new.
- Produces: `Config.omemoOnly()` returns `true` app-wide (already-existing method, now actually triggered) — Task 9 (UI cleanup) and the capability-gating tasks assume this is `true`.

- [ ] **Step 1: Change the encryption mask**

In `src/main/java/eu/siacs/conversations/Config.java`, change:
```java
    private static final int ENCRYPTION_MASK = UNENCRYPTED | OPENPGP |  OTR | OMEMO;
```
to:
```java
    private static final int ENCRYPTION_MASK = OMEMO;
```

- [ ] **Step 2: Remove the now-dead legacy-stack-default preferences**

In `src/main/res/values/defaults.xml`, delete these two lines (legacy OMEMO1 no longer exists as of Task 5, so a "default to legacy" preference is meaningless):
```xml
    <bool name="legacy_omemo_enabled">true</bool>
    <bool name="omemo_default_legacy">true</bool>
```
(Leave this XML deletion staged for now even though the *code* reading these keys isn't removed until Task 9 — deleting the resource first and fixing remaining references second, rather than the other way around, means the build will loudly fail-to-resolve anywhere still reading them, giving Task 9 a mechanical, compiler-driven checklist instead of a manual grep.)

- [ ] **Step 3: Build (expect compile errors — this is intentional)**

```bash
./gradlew compileMonocleschatFreeDebugJavaWithJavac 2>&1 | grep -i "cannot find symbol\|does not exist" | head -30
```
This will list every place `R.bool.legacy_omemo_enabled`/`R.bool.omemo_default_legacy` (or `AppSettings.LEGACY_OMEMO_ENABLED`/`OMEMO_DEFAULT_LEGACY`, if those are string-key constants rather than direct resource references) is still read. **Do not fix these here** — this list is Task 9's actual scope; record it (e.g. paste into Task 9's own tracking) rather than fixing ad hoc now, so Task 9 isn't relying on a fresh re-grep that might miss something this compile pass already caught.

- [ ] **Step 4: Revert Step 2 temporarily if Task 9 hasn't landed yet in this session**

If executing this plan strictly in order, leave the `defaults.xml` deletion in place and proceed straight to Task 3 — Tasks 3 and 4 don't touch any of the newly-dead code paths, and Task 5's legacy removal will naturally delete the remaining readers before Task 9 needs to. If instead you want `./gradlew testMonocleschatFreeDebugUnitTest` to pass in the meantime (e.g. running tasks out of order), restore the two `defaults.xml` lines now and redo Step 2 as part of Task 9 instead.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/eu/siacs/conversations/Config.java src/main/res/values/defaults.xml
git commit -m "Force PQ-OMEMO2 as the only encryption mode (Config.ENCRYPTION_MASK = OMEMO)"
```

---

### Task 3: Delete the legacy-vs-PQ stack choice dialog

**Files:**
- Delete: `src/main/java/eu/siacs/conversations/ui/util/OmemoDefaultStackNotice.java`
- Modify: whatever entry activities call it (grep-discovered in Step 1)
- Test: full suite

- [ ] **Step 1: Find every call site**

```bash
grep -rn "OmemoDefaultStackNotice" src/main/java/
```

- [ ] **Step 2: Delete the file and every call site found in Step 1**

```bash
rm src/main/java/eu/siacs/conversations/ui/util/OmemoDefaultStackNotice.java
```
For each call site (e.g. in `StartConversationActivity.java` and any other entry activity Step 1 found), remove the call and any now-unused imports of `OmemoDefaultStackNotice`. There is no replacement dialog needed — with `Config.omemoOnly()` now `true` (Task 2), there is no "which stack" choice left to prompt for.

- [ ] **Step 3: Build and run the full test suite**

```bash
./gradlew testMonocleschatFreeDebugUnitTest
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Remove the legacy-vs-PQ-OMEMO2 stack choice dialog (no longer a real choice)"
```

---

### Task 4: Remove the legacy crypto backend package and its dependency

**Files:**
- Delete: `src/main/java/eu/siacs/conversations/crypto/axolotl/legacy/` (`LegacyAxolotlBackend.java`, `LegacySignalProtocolStore.java`)
- Modify: `build.gradle` (remove `org.whispersystems:signal-protocol-java` dependency)
- Modify: `src/main/java/eu/siacs/conversations/crypto/axolotl/AxolotlService.java` (remove the `getLegacyBackend()`/legacy-session-building call site shown in this plan's own research at line ~2032-2050, and any other `legacy/`-package import)
- Test: full suite

**Interfaces:**
- Produces: no more `org.whispersystems.libsignal.*` symbols anywhere in the codebase (verified in Step 4).

- [ ] **Step 1: Find every reference to the legacy package and its types**

```bash
grep -rln "crypto.axolotl.legacy\|LegacyAxolotlBackend\|LegacySignalProtocolStore\|org.whispersystems.libsignal" src/main/java/
```

- [ ] **Step 2: Delete the package**

```bash
rm -rf src/main/java/eu/siacs/conversations/crypto/axolotl/legacy/
```

- [ ] **Step 3: Remove every reference found in Step 1**

For `AxolotlService.java` specifically: the call site around line 2032-2050 (`getLegacyBackend()`, `buildLegacySessionFromPEP`) that this plan's own research already located — replace the entire "delegate to legacy" branch with either deletion of the calling method entirely (if `buildSessionFromPEP`, the non-OMEMO2 sibling of `buildSessionFromOmemo2PEP`, has no other callers once legacy device-list handling is gone — check with `grep -n "buildSessionFromPEP\b"` before deleting) or, if it's still reachable from a code path Task 5 hasn't reached yet, leave a `throw new UnsupportedOperationException("legacy OMEMO removed")` as a temporary marker to be deleted for real in Task 5. Prefer full deletion now if the call graph allows it — a temporary marker is a last resort, not the default.

- [ ] **Step 4: Remove the Gradle dependency**

In `build.gradle`, remove the line referencing `org.whispersystems:signal-protocol-java` (found via research at `build.gradle:71`; confirm the exact line with `grep -n "signal-protocol-java" build.gradle` since line numbers may have shifted since that research pass).

- [ ] **Step 5: Build and confirm zero remaining references**

```bash
grep -rn "org.whispersystems.libsignal" src/main/java/ ; echo "exit: $?"
./gradlew testMonocleschatFreeDebugUnitTest
```
Expected: grep exits 1 (no matches), full test suite passes with the same baseline as Task 1.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Remove the legacy OMEMO1 crypto backend and its old-libsignal dependency"
```

---

### Task 5: Strip legacy branches from `AxolotlService.java` and `SQLiteAxolotlStore.java`

This is the bulk of the mechanical work — dozens of `isOmemo2`-flag-branched methods, per this plan's own spec research. Framed as a bounded, verifiable procedure rather than pre-written diffs, since the flag-branch pattern is locally mechanical but each occurrence's exact current code needs to be read fresh (line numbers have already drifted once this session from Task 1-4's own edits).

**Files:**
- Modify: `src/main/java/eu/siacs/conversations/crypto/axolotl/AxolotlService.java`
- Modify: `src/main/java/eu/siacs/conversations/crypto/axolotl/SQLiteAxolotlStore.java`
- Test: full suite, run after every 3-5 methods fixed (not just once at the end — a 4600-line mechanical pass is exactly where a single mid-pass mistake hides easily until the very end otherwise)

- [ ] **Step 1: Enumerate every flag-branched method**

```bash
grep -n "isOmemo2" src/main/java/eu/siacs/conversations/crypto/axolotl/AxolotlService.java | grep -oE "^[0-9]+:.*\b[a-zA-Z_]+\(" | head -60
grep -n "isOmemo2" src/main/java/eu/siacs/conversations/crypto/axolotl/SQLiteAxolotlStore.java
```
Cross-reference against the specific method names already identified during design research: `deviceListStatus`, `hasErrorFetchingDeviceList`, `getDeviceIdsForStack`, `registerDevices`, `findDevicesWithoutSession`, `reportBrokenSessionException`, and others matching the same `final boolean isOmemo2` parameter pattern.

- [ ] **Step 2: For each method, read it fully, then delete the `isOmemo2 == false` branch**

For each method found in Step 1: read the whole method body (`sed -n '<start>,<end>p' AxolotlService.java`), identify the `if (isOmemo2) { ... } else { ... }` (or equivalent) structure, delete the `else` branch and un-wrap the `if` body (or, where the method's only purpose was branching, inline the OMEMO2-only body directly and drop the now-single-value `isOmemo2` parameter from the method signature and every call site — check call sites with `grep -n "\.methodName(" AxolotlService.java` before changing a signature). Do this in small batches (3-5 methods), rebuilding after each batch:
```bash
./gradlew testMonocleschatFreeDebugUnitTest
```
A batch that breaks the build gets fixed before moving to the next batch — never accumulate multiple batches of unverified changes.

- [ ] **Step 3: Repeat Step 2 for `SQLiteAxolotlStore.java`**

Same procedure — this file was already identified as having legacy-vs-OMEMO2 store methods (legacy used the now-deleted `LegacySignalProtocolStore`, so any method exclusively serving that store from Task 4 may already be dead code to delete outright rather than flag-branched code to simplify).

- [ ] **Step 4: Final full-file verification**

```bash
grep -n "isOmemo2" src/main/java/eu/siacs/conversations/crypto/axolotl/AxolotlService.java src/main/java/eu/siacs/conversations/crypto/axolotl/SQLiteAxolotlStore.java
```
Expected: zero remaining matches (every occurrence was either a branch that got simplified away, or a parameter that got dropped). If any remain, they're either a genuine remaining reason to distinguish (re-check against the spec before assuming it's an oversight) or a missed cleanup.

```bash
./gradlew testMonocleschatFreeDebugUnitTest
```

- [ ] **Step 5: Commit** (one commit per batch from Step 2 is fine and arguably better for reviewability than one giant commit here — use judgment based on how the work actually split)

```bash
git add -A
git commit -m "Strip legacy OMEMO1 branches from AxolotlService/SQLiteAxolotlStore"
```

---

### Task 6: Drop legacy database tables, migrate the shared `identities` table

**Files:**
- Modify: `src/main/java/eu/siacs/conversations/persistance/DatabaseBackend.java`
- Test: full suite (no live-DB test infra confirmed in this repo's `src/test/` — if none exists, this task's real verification is Step 4's live-app smoke test, not a JVM unit test)

**Interfaces:**
- Consumes: nothing from earlier tasks directly, but must run after Task 5 (no code should reference the legacy tables by then).

- [ ] **Step 1: Read the current schema and migration structure**

```bash
grep -n "CREATE_LEGACY_SESSIONS_STATEMENT\|CREATE TABLE.*sessions\|CREATE TABLE.*prekeys\|CREATE TABLE.*signed_prekeys\|onUpgrade\|DATABASE_VERSION" src/main/java/eu/siacs/conversations/persistance/DatabaseBackend.java | head -40
```
Identify: the current `DATABASE_VERSION` constant, the `onUpgrade` migration-dispatch method's structure (so a new migration step can be added following the existing pattern), and the exact legacy table names (`sessions`, `prekeys`, `signed_prekeys` per design research — confirm against the real, current names).

- [ ] **Step 2: Add a new migration step**

Following the existing `onUpgrade` pattern (read at least 2 prior migration steps to match the established style exactly before writing a new one — don't invent a different pattern), add a step that:
1. Drops the legacy `sessions`/`prekeys`/`signed_prekeys` tables outright (`DROP TABLE IF EXISTS`).
2. For the shared `identities` table: per the design research, this table is NOT dropped (it's shared with the OMEMO2 stack) — this migration step should be a no-op for it, but add an explicit comment stating that decision (so a future reader doesn't wonder why `identities` was skipped when its sibling tables were dropped).
3. Bump `DATABASE_VERSION`.

- [ ] **Step 3: Build**

```bash
./gradlew testMonocleschatFreeDebugUnitTest
```

- [ ] **Step 4: Live migration smoke test**

This needs an actual app install with pre-existing legacy data, which a JVM unit test can't provide. Using the `android-dev` workspace (or a connected device/emulator once available — see this plan's own Open Questions): install a pre-fork build of Monocles Chat (which has real legacy `sessions`/`prekeys` rows), then install this fork over it, and confirm the app starts without a crash and the migration ran (check `adb shell run-as <applicationId> sqlite3 <db-path> ".tables"` shows the legacy tables gone and `identities` still present).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/eu/siacs/conversations/persistance/DatabaseBackend.java
git commit -m "Drop legacy OMEMO1 database tables in a new migration step"
```

---

### Task 7: UI/settings cleanup pass

**Files:** `ConversationMenuConfigurator.java`, `OmemoActivity.java`, `TrustKeysActivity.java`, `TrustKeys.java`, `AppSettings.java`, `SecuritySettingsFragment.java`, `EditAccountActivity.java`, `ExportBackupWorker.java`, `ImportBackupWorker.java`, `XmppUri.java`

- [ ] **Step 1: Re-run the compile-error list from Task 2 Step 3 as the authoritative checklist**

```bash
./gradlew compileMonocleschatFreeDebugJavaWithJavac 2>&1 | grep -i "cannot find symbol\|does not exist"
```
This should now list every reference to the deleted `legacy_omemo_enabled`/`omemo_default_legacy` resources/preference keys, plus (if Task 4/5 already removed types these files reference) any other now-broken reference. Work through this list file by file rather than the file list above in isolation — the compiler-generated list is more reliable than a hand-maintained one.

- [ ] **Step 2: For each file, remove the legacy-specific branch/menu-item/preference**

Per design research: `ConversationMenuConfigurator.java` (remove the legacy menu item + `globalLegacy` checks — `alwaysOmemo` is now unconditionally true so the whole plaintext/PGP/OTR-hiding logic simplifies too), `OmemoActivity.java` (remove the `legacy` boolean parameter threaded through `addFingerprintRow` and its legacy fingerprint-format branch), `TrustKeysActivity.java`/`TrustKeys.java` (remove `legacyIntentFor`), `AppSettings.java`/`SecuritySettingsFragment.java` (remove `LEGACY_OMEMO_ENABLED`/`OMEMO_DEFAULT_LEGACY` preference wiring and their settings-screen entries), `EditAccountActivity.java`/`ExportBackupWorker.java`/`ImportBackupWorker.java` (remove legacy key backup/restore — only PQ-OMEMO2 keys exist now), `XmppUri.java` (remove the legacy fingerprint URI format).

Read each file's relevant section before editing (`grep -n "legacy\|Legacy" <file>` as a starting point per file) — don't guess at current line numbers or exact surrounding code.

- [ ] **Step 3: Build and run the full test suite**

```bash
./gradlew testMonocleschatFreeDebugUnitTest
```
Expect zero compile errors now (Step 1's list should be empty on re-run).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Remove legacy OMEMO1 UI and settings paths"
```

---

### Task 8: Proactive capability gating — 1:1 conversations

**Files:**
- Create: `src/main/java/eu/siacs/conversations/crypto/axolotl/Omemo2CapabilityChecker.java`
- Test: `src/test/java/eu/siacs/conversations/crypto/axolotl/Omemo2CapabilityCheckerTest.java`
- Modify: `src/main/java/eu/siacs/conversations/ui/ConversationFragment.java`

**Interfaces:**
- Produces: `Omemo2CapabilityChecker.CapabilityResult` — an enum `{SUPPORTED, UNSUPPORTED, CHECK_FAILED}` (three states so the UI in Task 8 can distinguish "will never work" from "transient error, retry"), and `void checkOneToOne(AxolotlService axolotlService, Jid peer, Consumer<CapabilityResult> callback)` wrapping the existing `fetchOmemo2DeviceIds` mechanism.
- Consumes: `AxolotlService.fetchOmemo2DeviceIds` (existing method, per design research — confirm its exact current signature with `grep -n "fetchOmemo2DeviceIds" AxolotlService.java` before wiring against it, since Task 5's refactor may have changed it).

- [ ] **Step 1: Write the failing test for the pure result-mapping logic**

```java
package eu.siacs.conversations.crypto.axolotl;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class Omemo2CapabilityCheckerTest {

    @Test
    public void emptyDeviceListMapsToUnsupported() {
        assertEquals(
                Omemo2CapabilityChecker.CapabilityResult.UNSUPPORTED,
                Omemo2CapabilityChecker.resultForDeviceIds(Collections.emptyList()));
    }

    @Test
    public void nonEmptyDeviceListMapsToSupported() {
        assertEquals(
                Omemo2CapabilityChecker.CapabilityResult.SUPPORTED,
                Omemo2CapabilityChecker.resultForDeviceIds(List.of(1, 2)));
    }

    @Test
    public void nullDeviceListMapsToCheckFailed() {
        assertEquals(
                Omemo2CapabilityChecker.CapabilityResult.CHECK_FAILED,
                Omemo2CapabilityChecker.resultForDeviceIds(null));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile (the class doesn't exist yet)**

```bash
./gradlew testMonocleschatFreeDebugUnitTest --tests "eu.siacs.conversations.crypto.axolotl.Omemo2CapabilityCheckerTest"
```
Expected: FAIL — `Omemo2CapabilityChecker` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package eu.siacs.conversations.crypto.axolotl;

import eu.siacs.conversations.xmpp.Jid;
import java.util.List;
import java.util.function.Consumer;

/**
 * Whether a 1:1 peer supports PQ-OMEMO2, checked proactively (at conversation-open time)
 * rather than only reactively (a send attempt failing). Reuses the existing PEP device-list
 * fetch mechanism in {@link AxolotlService} -- an empty result is the app's own established
 * convention for "peer doesn't support PQ-OMEMO2" (see AxolotlService.fetchOmemo2DeviceIds's
 * own "fail closed" comment); this class only changes WHEN that check runs and WHAT the UI
 * does with a negative result, not how support is detected.
 */
public class Omemo2CapabilityChecker {

    public enum CapabilityResult {
        SUPPORTED,
        UNSUPPORTED,
        CHECK_FAILED
    }

    static CapabilityResult resultForDeviceIds(final List<Integer> deviceIds) {
        if (deviceIds == null) {
            return CapabilityResult.CHECK_FAILED;
        }
        return deviceIds.isEmpty() ? CapabilityResult.UNSUPPORTED : CapabilityResult.SUPPORTED;
    }

    public static void checkOneToOne(
            final AxolotlService axolotlService,
            final Jid peer,
            final Consumer<CapabilityResult> callback) {
        axolotlService.fetchOmemo2DeviceIds(
                List.of(peer),
                deviceIdsByJid -> {
                    final List<Integer> deviceIds = deviceIdsByJid == null ? null : deviceIdsByJid.get(peer);
                    callback.accept(resultForDeviceIds(deviceIds));
                });
    }
}
```
(The exact `fetchOmemo2DeviceIds` callback shape — `OnMultipleDeviceIdFetched` per design research — needs matching against its real, current signature; adjust the lambda/interface implementation to match rather than assuming this exact shape compiles unmodified.)

- [ ] **Step 4: Run the test again and confirm it passes**

```bash
./gradlew testMonocleschatFreeDebugUnitTest --tests "eu.siacs.conversations.crypto.axolotl.Omemo2CapabilityCheckerTest"
```

- [ ] **Step 5: Wire into `ConversationFragment`**

In `ConversationFragment.java`, at the point the conversation is opened/displayed (read the existing `onStart`/`refresh`-style lifecycle methods to find the right hook — don't guess), call `Omemo2CapabilityChecker.checkOneToOne(...)` for 1:1 conversations and, on `UNSUPPORTED`, disable the compose EditText and send button with an inline explanation (a new string resource, e.g. `omemo2_required_unsupported_contact`); on `CHECK_FAILED`, show a retryable state distinct from `UNSUPPORTED` (per this plan's spec's own Error Handling section — a transient failure must not look identical to a permanent block).

- [ ] **Step 6: Full test suite + manual verification**

```bash
./gradlew testMonocleschatFreeDebugUnitTest
```
Manual: per the design spec's Testing section item 3, confirm compose is actually disabled (not merely fails-on-send) against a real contact with no published PQ-OMEMO2 bundle.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add proactive PQ-OMEMO2 capability gating for 1:1 conversations"
```

---

### Task 9: Proactive capability gating — MUC

**Files:**
- Modify: `src/main/java/eu/siacs/conversations/crypto/axolotl/Omemo2CapabilityChecker.java` (add a MUC variant)
- Test: extend `Omemo2CapabilityCheckerTest.java`
- Modify: `ConversationFragment.java` (occupant-list-change re-check)

**Interfaces:**
- Consumes: `Omemo2CapabilityChecker.CapabilityResult` (Task 8), `MucOptions.getMembers(false)` (existing method per design research — confirm current signature).
- Produces: `checkMuc(AxolotlService, Conversation, Consumer<CapabilityResult>)` — `UNSUPPORTED` if *any* occupant lacks support (all-or-nothing, per the design spec — no partial/selective encryption is possible in a shared room).

- [ ] **Step 1: Write the failing test for the all-or-nothing aggregation logic**

```java
    @Test
    public void allSupportedYieldsSupported() {
        assertEquals(
                Omemo2CapabilityChecker.CapabilityResult.SUPPORTED,
                Omemo2CapabilityChecker.aggregateMucResults(List.of(
                        Omemo2CapabilityChecker.CapabilityResult.SUPPORTED,
                        Omemo2CapabilityChecker.CapabilityResult.SUPPORTED)));
    }

    @Test
    public void anyUnsupportedYieldsUnsupported() {
        assertEquals(
                Omemo2CapabilityChecker.CapabilityResult.UNSUPPORTED,
                Omemo2CapabilityChecker.aggregateMucResults(List.of(
                        Omemo2CapabilityChecker.CapabilityResult.SUPPORTED,
                        Omemo2CapabilityChecker.CapabilityResult.UNSUPPORTED)));
    }

    @Test
    public void anyCheckFailedWithNoUnsupportedYieldsCheckFailed() {
        assertEquals(
                Omemo2CapabilityChecker.CapabilityResult.CHECK_FAILED,
                Omemo2CapabilityChecker.aggregateMucResults(List.of(
                        Omemo2CapabilityChecker.CapabilityResult.SUPPORTED,
                        Omemo2CapabilityChecker.CapabilityResult.CHECK_FAILED)));
    }
```
(Append these to `Omemo2CapabilityCheckerTest.java` from Task 8.)

- [ ] **Step 2: Run and confirm failure (method doesn't exist)**

```bash
./gradlew testMonocleschatFreeDebugUnitTest --tests "eu.siacs.conversations.crypto.axolotl.Omemo2CapabilityCheckerTest"
```

- [ ] **Step 3: Implement**

```java
    public static CapabilityResult aggregateMucResults(final List<CapabilityResult> results) {
        if (results.contains(CapabilityResult.UNSUPPORTED)) {
            return CapabilityResult.UNSUPPORTED; // all-or-nothing: any one occupant blocks the room
        }
        if (results.contains(CapabilityResult.CHECK_FAILED)) {
            return CapabilityResult.CHECK_FAILED;
        }
        return CapabilityResult.SUPPORTED;
    }

    public static void checkMuc(
            final AxolotlService axolotlService,
            final List<Jid> occupants,
            final Consumer<CapabilityResult> callback) {
        // Fan out one checkOneToOne-style fetch per occupant, collect into aggregateMucResults.
        // Exact concurrency/collection mechanism (a CountDownLatch-free async collector, matching
        // this codebase's existing callback style rather than introducing a new concurrency
        // primitive) is an implementation detail for whoever executes this step -- read how
        // AxolotlService's own existing multi-JID fetch (fetchOmemo2DeviceIds already accepts a
        // List<Jid>) handles this, since it may already do exactly this fan-out internally and
        // this method may just need to call it once with the full occupant list rather than
        // reimplementing fan-out here.
    }
```
(`checkMuc`'s body is intentionally left as a real, scoped implementation task rather than fabricated code — `fetchOmemo2DeviceIds` per Task 8's own note already accepts `List<Jid>`, so this likely reduces to one call rather than N, but that needs confirming against its actual current behavior, not assumed.)

- [ ] **Step 4: Run the aggregation tests, confirm they pass**

```bash
./gradlew testMonocleschatFreeDebugUnitTest --tests "eu.siacs.conversations.crypto.axolotl.Omemo2CapabilityCheckerTest"
```

- [ ] **Step 5: Wire into `ConversationFragment` for MUC, re-checking on occupant changes**

Call `checkMuc` when a MUC conversation opens and again on occupant join/leave (find the existing occupant-change notification hook — likely something `MucOptions`-observer-based, per design research's finding that `MucOptions` itself has no OMEMO2-specific code, so this is a new observer wired from `ConversationFragment` or wherever occupant-change events already surface elsewhere in the UI). Apply the same compose-disable UI as Task 8.

- [ ] **Step 6: Full test suite + manual verification**

```bash
./gradlew testMonocleschatFreeDebugUnitTest
```
Manual: create a MUC with a mix of PQ-OMEMO2-capable and incapable occupants, confirm compose is disabled; remove the incapable occupant, confirm compose re-enables.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add proactive PQ-OMEMO2 capability gating for MUC conversations"
```

---

### Task 10: Rebranding

**Files:**
- Modify: `build.gradle` (the `monocleschat` product flavor block)
- Modify: app icon/launcher resources (found via `grep -rn "ic_launcher" src/main/res/ | head` — exact resource set depends on current mipmap structure)

- [ ] **Step 1: Update the product flavor**

In `build.gradle`, change the `monocleschat` flavor's `applicationId`, `versionCode`/`versionName` (reset to `1`/`"1.0"` for a fresh fork rather than continuing Monocles' own numbering), and `appName` local variable to the fork's real name (placeholder used elsewhere in this project's infra: "conversations-omemo2" as the repo name — the *display* app name is a separate, real decision left to whoever runs this step, not fixed here).

- [ ] **Step 2: Replace launcher icon resources**

Identify the current icon resource set (`grep -rln "ic_launcher" src/main/res/`) and replace with new artwork — this step's actual asset creation is out of scope for this plan (design work, not code), but wiring in whatever replacement assets are provided follows the same resource-file paths already in use.

- [ ] **Step 3: Build**

```bash
./gradlew testMonocleschatFreeDebugUnitTest
```
(Task name itself may need updating if the flavor name changes as part of Step 1 — `monocleschat` as a flavor identifier vs. `appName`/`applicationId` as its configured values are independent; changing the flavor's *identifier* would cascade into every Gradle task name used throughout this whole plan. Recommend keeping the flavor identifier `monocleschat` unchanged and only changing `applicationId`/`appName`/`versionCode`/`versionName`, to avoid that cascade — flag this explicitly to the operator if renaming the flavor identifier itself is actually wanted.)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Rebrand: new applicationId/app name/icon for the OMEMO2+PQXDH fork"
```

---

### Task 11: Full end-to-end verification

**Files:** none (verification only)

- [ ] **Step 1: Full test suite one final time**

```bash
./gradlew testMonocleschatFreeDebugUnitTest
```
Expected: same or better pass count than the Task 1 baseline (36/38 — the 2 known failures need a host-native build variant, unrelated to this plan's changes).

- [ ] **Step 2: Two-account interop test**

Per the design spec's Testing section: two real XMPP accounts (a test server is fine), two built instances of this fork, confirm PQ-OMEMO2 messaging works end-to-end for both 1:1 and MUC.

- [ ] **Step 3: Negative-path + vulnerability-fix verification**

Confirm compose is disabled (not merely fails-on-send) against an OMEMO2-incapable contact/room (Tasks 8-9's own manual checks, re-run together for the full picture); reproduce the original vulnerability's attack scenario from the security review against the now-fixed code and confirm it's rejected (Task 1's fix).

- [ ] **Step 4: Legacy-removal verification**

Confirm zero remaining paths to plaintext/OTR/PGP/legacy-OMEMO1 messaging — attempt every settings/menu path that used to offer them and confirm none remain reachable, not just that they're hidden/discouraged.

---

## Open Questions / Explicitly Out of Scope

- Task 6's live migration smoke test and Task 11's two-account interop test both need either a connected Android device/emulator or a real second XMPP account+server — neither is set up yet in the `android-dev` workspace (its own design spec explicitly deferred emulator support as an open question). Resolve this before Task 6/11 are actually executed, not assumed away.
- Task 9's `checkMuc` fan-out mechanism is intentionally left as a scoped decision for its implementer (see Task 9 Step 3's own note) rather than fabricated here.
- Distribution (F-Droid/IzzyOnDroid/GitHub releases) — explicitly out of scope per the design spec.
- Whether to track Monocles' upstream commits going forward (they're actively developing, near-daily commits as of Aug 2026) is an open question for after this plan lands, not addressed here.
