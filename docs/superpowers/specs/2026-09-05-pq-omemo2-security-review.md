# Security review: Monocles Chat "PQ-OMEMO2" (`urn:monocles:omemo-pq:1`)

**Scope:** the novel protocol glue monocles added on top of already-audited primitives
(ML-KEM-1024/FIPS 203 and ML-DSA-87/FIPS 204 via Cryspen's `libcrux`, and Signal's own
SPQR ratchet crate) — the bundle-signing transcript, KEM/identity binding, replay
defenses, continuous ratchet wiring, algorithm-confusion guards, and MUC handling.
Reviewed directly against source at `/work/monocles_chat_explore` (app, Java) and its
`deps/libsignal` submodule (`pq-omemo-2` fork, Rust), by two independent deep reads
covering 8 attack-surface areas.

## Summary verdict

**7 of 8 areas reviewed are SAFE, with real, specific evidence** (not just "looks
reasonable" — exact file/line citations and, where relevant, distinct code paths were
traced end to end). **One area has a genuine, exploitable vulnerability** that
undermines the entire point of the hybrid PQ scheme. The underlying cryptography here
is substantially better engineered than a "one-man unaudited side project" label
suggests — SPQR (Signal's actual production post-quantum ratchet) is wired in
correctly and mandatorily, replay defenses correctly implement the real libsignal
`KyberPreKeyStore` trait contract, and multiple independent fail-closed checks exist
for stanza-level and bundle-level downgrade attempts. But the one finding is serious
enough that this code cannot be adopted as-is without fixing it first.

## Findings

| # | Area | Verdict | Evidence |
|---|---|---|---|
| 1 | Bundle-signature transcript completeness (no "Frankenstein bundle" mix-and-match) | **SAFE** | `bundle.rs:118-145`, `:83-110`; dual verification `session.rs:201-243`; explicit unit tests `bundle.rs:521-577` |
| 2 | Downgrade resistance (PQ-mandatory at first contact; Kyber-Round-3 vs ML-KEM-1024 tag confusion) | **SAFE** (first contact) | `session.rs:107-139` (KEM mandatory in every message, unconditional); `AxolotlService.java:2118-2172` (mandatory pq-ik, fail-closed); tag rejection at 3 independent sites (`IqParser.java:649,748,821-848` + `CryptoHelper.java`) |
| 3 | KCI/UKS resistance | Base property **SAFE**; **VULNERABLE** in re-pinning logic | See "The vulnerability" below |
| 4 | Fingerprint-binding correctness | **SAFE** (inherits #3's risk) | `CryptoHelper.java:106-115` — SHA3-512 over length-prefixed classical+PQ keys, no ambiguity |
| 5 | One-time/last-resort prekey replay protection | **SAFE** | One-time keys deleted after single use (`session_management.rs:265-278`); last-resort key reuse guarded by a `(kemId,spkId,baseKey)` tuple, fails closed via `ReusedBaseKeyException` |
| 6 | Continuous PQ ratchet ("SPQR") | **SAFE — real, not aspirational** | Signal's own `spqr` crate (`SparsePostQuantumRatchet` v1.5.1), invoked on *every* encrypt/decrypt in `triple_ratchet.rs`, mandatory (`min_version: V1`); classical-only code path is `#![cfg(test)]`, unreachable in production |
| 7 | KEM algorithm/version confusion beyond bundle parsing | **SAFE**, one noted single-choke-point caveat | Every peer-controlled KEM/identity key ingestion point traced to `IqParser`'s checks; low-level Rust `decapsulate()` independently hard-checks type equality. Caveat: enforcement lives at the Java boundary, not the Rust type system — a future second bundle-ingestion path could reopen it, though none exists today. |
| 8 | MUC edge cases (anonymous rooms, mixed OMEMO2+legacy stanzas) | **SAFE** | Both cases terminate with an unconditional `return` before any decryption/degraded processing |

## The vulnerability (Finding 3)

**`AxolotlService.java:2126-2168`, in `buildSessionFromOmemo2PEP`.**

When a peer's PQ identity (`pq_ik`) changes for an already-known classical identity,
the code normally refuses the new bundle ("possible downgrade/MITM"). But it makes an
exception: if the peer's **classical** Ed25519 fingerprint is already user-verified, it
accepts the new `pq_ik` and silently re-pins it — reasoning that "an attacker cannot
MITM the session because they lack the classical private key."

That reasoning is only true if Ed25519 is unbroken. **The entire purpose of the hybrid
ML-DSA-87 layer is to remain secure even when classical crypto is broken** (stated
explicitly in the code's own `pqid.rs`/`bundle.rs` comments). An attacker who has
recovered a victim's classical private key — exactly the threat model this feature
exists for — can:

1. Generate their own fresh ML-DSA-87 keypair and new prekey material.
2. Forge valid Ed25519 signatures over it using the stolen classical key (passes the
   classical checks, since those only need the classical key).
3. Self-sign a fully consistent bundle with their own new ML-DSA-87 key (passes the PQ
   check trivially — nothing requires the *original* PQ private key).
4. Publish it. Because the "verified" trust flag is **sticky and never expires**
   (`FingerprintStatus.java:104-106` — a boolean, no timestamp/freshness component) and
   the classical key bytes are unchanged, the app accepts and re-pins the attacker's PQ
   identity — silently, with only a developer log line, no user-facing prompt.

Result: a full, silent downgrade of the hybrid identity to classical-only trust,
triggered purely by classical-key compromise, for any contact whose fingerprint was
verified in the past. This also breaks unknown-key-share resistance — both parties
believe they still have PQ-hardened trust with each other while actually talking
through the attacker.

**Scope of the bug:** limited to this one function. The separate reconciliation path
(`reconcileOmemo2PqPinFromBundle`) is safe — it only fills an *empty* pin, never
overwrites an existing one.

**Recommended fix** (for whichever fork ships first — ours or, ideally, upstream too):
remove the "already classically verified" exception entirely. A PQ-identity change for
an already-pinned contact should always require explicit, fresh, out-of-band
re-verification (of the PQ fingerprint specifically, not just reusing a stale classical
one) — never a silent auto-accept.

## What this means for sub-project B

- This is not a reason to abandon monocles' code — the surrounding engineering
  (SPQR wiring, replay defenses, bundle-transcript binding, MUC fail-closed behavior)
  is genuinely solid, and re-implementing an equivalent from scratch would introduce
  more risk, not less.
- It is a reason this fork **cannot** simply adopt `urn:monocles:omemo-pq:1` unmodified
  and call the review satisfied. Fixing Finding 3 (removing the sticky-verified
  downgrade exception) needs to be part of this fork's own required scope, not
  optional hardening.
- **Responsible disclosure**: this vulnerability affects monocles chat's real,
  existing users today, not just a hypothetical future fork. This should be reported
  to the maintainer (Arne-Brün Vogelsang) before or alongside any public fork work
  that references it, rather than only fixed silently in our own downstream copy.
