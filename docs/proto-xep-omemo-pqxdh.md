# Proto-XEP: OMEMO Post-Quantum Extended Diffie-Hellman (OMEMO-PQXDH)

**Title:** OMEMO Post-Quantum Extended Diffie-Hellman
**Version:** 0.0.6
**Status:** ProtoXEP
**Type:** Standards Track
**Author:** Arne-Brün Vogelsang
**Derived from:** XEP-0384 (OMEMO Encryption), version 0.9.1; XEP-0420 (Stanza Content Encryption)
**Namespace:** `urn:monocles:omemo-pq:1` (distinct from XEP-0384's `urn:xmpp:omemo:2`; see §1.2, §10)
**Date:** 2026-07-28

---

## Abstract

This document specifies a post-quantum profile of OMEMO Encryption, derived from
XEP-0384 version 0.9.1, that adds Post-Quantum Extended Diffie-Hellman (PQXDH)
using ML-KEM-1024 (CRYSTALS-Kyber, NIST FIPS 203). It extends the OMEMO2 bundle
format with signed and one-time KEM prekeys, making OMEMO session **initiation**
resistant to "harvest now, decrypt later" attacks by quantum-capable adversaries.
Because this profile is deliberately wire-incompatible with classical XEP-0384
v0.9 at both the handshake (mandatory PQXDH) and the payload layer (§5.4), it
lives under its **own namespace and PEP nodes** (`urn:monocles:omemo-pq:1`) so
that classical OMEMO2 clients and this stack coexist without interfering (§1.2).
Where the underlying libsignal provides it, the **ongoing** session is
additionally protected by Signal's Sparse Post-Quantum Ratchet (SPQR / "ML-KEM
Braid"), extending post-quantum security beyond the handshake to continuous
post-compromise healing (§4.8). The implementation is built on Signal's libsignal
library (≥ 0.94.1), which implements both PQXDH and SPQR natively.

---

## Status of this Document

This is a ProtoXEP — an informal proposal seeking community review before formal
submission to the XMPP Standards Foundation. The XML namespaces and element names
defined here are subject to change until the document reaches Experimental status.

The monocles chat Android and desktop clients ship a reference implementation against this
specification.

---

## 1. Introduction

### 1.1 Motivation

OMEMO (XEP-0384) provides end-to-end encryption for XMPP based on the Signal
Protocol (X3DH key agreement + Double Ratchet). While this provides strong
classical security, it does not protect against a "harvest now, decrypt later"
(HNDL) attack: an adversary who stores encrypted ciphertext today could
theoretically decrypt it in the future using a sufficiently large quantum computer.

PQXDH (Post-Quantum Extended Diffie-Hellman) was published by Signal in 2023 and
is standardised as part of NIST FIPS 203 (ML-KEM). It augments X3DH with an
additional key encapsulation mechanism (KEM) using ML-KEM-1024 (Kyber-1024),
binding the classical and post-quantum shared secrets with HKDF so that
compromise of either component alone is insufficient.

PQXDH protects only the *initial* key agreement. The *ongoing* Double Ratchet —
which provides forward secrecy and post-compromise security as the conversation
continues — remains classical unless it too is augmented. Signal's Sparse
Post-Quantum Ratchet (SPQR), a.k.a. the ML-KEM Braid ([MLKEMBRAID]), addresses
this by ratcheting an ML-KEM shared secret continuously alongside the classical
Double Ratchet. This proto-XEP describes how PQXDH is mapped onto OMEMO2 (§4.2–§4.5)
and how the libsignal-provided SPQR rides transparently inside the unchanged
OMEMO2 message format (§4.8), so that both the handshake and the ongoing session
gain post-quantum protection.

### 1.2 Relationship to XEP-0384

This proto-XEP does not replace XEP-0384. It is a *derived profile*: it reuses
the XEP-0384 v0.9 element shapes (device list, bundle, `<encrypted>` header/keys
structure) and the XEP-0420 SCE envelope, extends the bundle with post-quantum
key material, and hardens the payload encryption (§5.4).

**It is, however, deliberately wire-incompatible with classical XEP-0384 v0.9**
in three ways: session initiation is PQXDH-only (a classical X3DH
`PreKeySignalMessage` is rejected, §4.4), the hybrid post-quantum identity is
mandatory (§4.9), and the symmetric payload scheme differs (AES-256-GCM with
context binding, §5.4, instead of XEP-0384's EncryptThenMAC construction).
A classical OMEMO2 client can therefore never complete a session with this
profile — and vice versa.

For that reason this profile uses its **own namespace and PEP nodes**
(`urn:monocles:omemo-pq:1`, §4.2) rather than squatting `urn:xmpp:omemo:2`.
Sharing XEP-0384's namespace would cause real harm in mixed ecosystems: a
conformant classical client would discover the bundles, consume one-time
prekeys, build a session that can never work, and surface undecryptable
messages with no way to diagnose why. Under a distinct namespace the two
stacks are mutually invisible: classical clients never fetch these bundles,
and this profile never touches `urn:xmpp:omemo:2` nodes. A client MAY
implement both this profile and classical XEP-0384 side by side as separate
stacks (the presence of the `urn:monocles:omemo-pq:1:bundles` node signals
support for this profile).

Note that this document REQUIRES the SCE-based message shape from XEP-0384
v0.9.x. It is likewise incompatible with legacy XEP-0384 v0.3 (the pre-SCE
format): v0.3 bundles do not carry KEM prekeys and cannot produce a Kyber
signature, so sessions cannot be established under PQXDH. PQXDH-mandating
clients MUST refuse to fall back to v0.3.

A client MAY additionally implement legacy OMEMO (v0.3) as a **separate,
independently-selectable** stack so that it can still reach peers on older
clients. In that case the two stacks MUST be kept strictly separate:

- The legacy stack and the OMEMO2/PQXDH stack use different PEP nodes, different
  device-id lists, and different session stores.
- PQXDH session establishment MUST NOT reuse an existing legacy Signal session
  for a peer device, even when one is present; it MUST always build a fresh
  OMEMO2 session from the peer's OMEMO2 bundle. Conversely, a legacy send MUST
  NOT consume an OMEMO2 session.
- Routing on receive follows the container's namespace, not the availability of
  a session: a stanza carrying the legacy `<encrypted>` element MUST be
  processed with the legacy stack only, and an `urn:monocles:omemo-pq:1`
  container with the OMEMO2 stack only. This holds for **every** shape of
  message, including empty/key-transport ones. Handing a legacy container to the
  OMEMO2 session cipher lets anyone who can inject a stanza from the peer's JID
  (a malicious server) re-wrap a captured OMEMO2 key blob in a legacy container
  and drive the OMEMO2 ratchet with it — consuming a one-time prekey and turning
  the genuine message into a duplicate that is then dropped.
- In the reference implementation legacy OMEMO is disabled by default
  (`legacy_omemo_enabled = false`); when it is disabled, conversations whose
  stored encryption is legacy OMEMO are transparently upgraded to OMEMO2 for new
  outgoing messages. Per-chat fallback to legacy must additionally be enabled on
  each conversation. This guarantees a client never silently downgrades a
  post-quantum conversation to a classical-only one.

### 1.3 Relationship to Signal's PQXDH

Signal's PQXDH spec (https://signal.org/docs/specifications/pqxdh/) defines the
full cryptographic protocol. This document maps Signal's PQXDH to the XMPP/OMEMO2
bundle publication format, specifying the additional XML elements required and
their encoding.

---

## 2. Requirements

- MUST provide post-quantum security against a quantum-capable passive adversary
  (HNDL attack resistance)
- MUST preserve full forward secrecy of the Double Ratchet
- MUST NOT interfere with classical XEP-0384 deployments: because this profile
  cannot complete a session with a classical OMEMO2 client, it MUST use its own
  namespace and PEP nodes (§1.2, §4.2) so classical clients never fetch its
  bundles or consume its prekeys
- MUST keep the `<encrypted>` stanza *structure* of XEP-0384 v0.9 (header, keys,
  payload) so the SPQR/PQXDH blobs ride the existing `<key>` transport (§4.4.3)
- MUST use ML-KEM-1024 (CRYSTALS-Kyber-1024, NIST FIPS 203) as the KEM algorithm
- MUST sign all published KEM public keys with the sender's identity key
- MUST support one-time KEM prekeys for forward secrecy, with signed last-resort
  fallback
- MUST enforce XEP-0420 §4.5 SCE-envelope binding (`<from>` and `<to>` JID
  verification) on receive
- MUST verify the `<keys jid='…'>` block addressed to the receiver and ignore
  key wrappings in foreign blocks (XEP-0420 §4.5)
- SHOULD encrypt all per-conversation metadata — chat states, chat markers,
  delivery receipts, reactions, message corrections, ephemeral timers, live
  location, WebXDC payloads, file-transfer SIMS references — by placing them
  inside the SCE envelope, not the outer stanza
- MUST place XEP-0447 file descriptions inside the SCE envelope and MUST ignore
  any found on the outer stanza: the source URL carries the file's encryption
  key and the metadata describes the plaintext file (§4.6.8)
- MUST include a `<time>` element in the SCE envelope and MUST reject envelopes
  that omit it or whose stamp is outside the tolerated clock-skew window (§4.6.2)

---

## 3. Glossary

| Term | Definition |
|------|-----------|
| **X3DH** | Extended Triple Diffie-Hellman — the classical OMEMO key agreement |
| **PQXDH** | Post-Quantum X3DH — X3DH augmented with ML-KEM (initial handshake) |
| **SPQR / ML-KEM Braid** | Sparse Post-Quantum Ratchet — a Sparse Continuous Key Agreement that braids ML-KEM shared secrets into the ongoing Double Ratchet for post-quantum post-compromise security |
| **Epoch** | One SPQR round: a single ML-KEM shared secret, agreed by braiding chunked KEM material across multiple messages |
| **PCS** | Post-Compromise Security — recovery of confidentiality after a state compromise as the session continues |
| **ML-KEM-1024** | Module-Lattice-Based Key-Encapsulation Mechanism (NIST FIPS 203); parameter set at NIST security category 5 (comparable to AES-256 key search) |
| **KEM-SPK** | KEM Signed Pre-Key — a long-lived ML-KEM public key signed by the identity key |
| **KEM-PK** | KEM one-time Pre-Key — an ephemeral ML-KEM public key signed by the identity key |
| **IK** | Identity Key — the device's permanent Ed25519/Curve25519 key pair |
| **SPK** | (EC) Signed Pre-Key — the existing OMEMO2 Curve25519 signed prekey |
| **PK** | (EC) one-time Pre-Key — the existing OMEMO2 Curve25519 one-time prekeys |
| **Bundle** | The set of public keys published via PEP that remote devices use to initiate sessions |

---

## 4. Protocol

### 4.1 Overview

PQXDH-OMEMO extends the session initiation handshake as follows:

1. The sender's bundle now includes ML-KEM-1024 key material (`<kem-spk>`,
   `<kem-prekeys>`).
2. When Alice initiates a session with Bob's device, she picks one of Bob's
   one-time KEM prekeys (or the signed KEM prekey as last resort), performs
   KEM encapsulation, and passes the resulting ciphertext and key material to
   the Signal Protocol's `SessionBuilder.process()`.
3. The PQXDH binding is absorbed into the initial shared secret. In addition,
   when the underlying libsignal provides it (see §4.8), an ML-KEM **continuous**
   ratchet (SPQR / "ML-KEM Braid") runs alongside the classical Double Ratchet,
   extending post-quantum protection from the handshake to the ongoing session.
4. The encrypted message *structure* (header, keys, payload) is unchanged from
   XEP-0384 v0.9; the payload cipher is the hardened AES-256-GCM scheme of §5.4,
   and the `<encrypted>` element lives in this profile's namespace (§4.7).
5. The SCE envelope (XEP-0420) carries the body **and** all per-conversation
   metadata. The outer stanza is reduced to routing fields, the OMEMO fallback
   body, and a small set of server-readable hints (see §4.6).

### 4.2 PEP Nodes

| Purpose | PEP Node | Item ID |
|---------|----------|---------|
| Device list | `urn:monocles:omemo-pq:1:devices` | `current` |
| Bundle (per device) | `urn:monocles:omemo-pq:1:bundles` | Device ID (integer string) |

These mirror the node layout of XEP-0384 v0.9.1 under this profile's own
namespace (§1.2): classical `urn:xmpp:omemo:2` nodes are never read or written
by this profile, and the presence of the `…:bundles` node is itself the
capability signal that a device speaks OMEMO-PQXDH.

### 4.3 Extended Bundle Format

The bundle item (PEP node `urn:monocles:omemo-pq:1:bundles`, item id = device ID) is
extended with four new child elements inside `<bundle xmlns='urn:monocles:omemo-pq:1'>`:

```xml
<bundle xmlns='urn:monocles:omemo-pq:1'>

  <!-- EXISTING OMEMO2 ELEMENTS (unchanged) -->
  <spk id='42'>
    BASE64(Curve25519 signed prekey public key, 32 bytes)
  </spk>
  <spks>
    BASE64(Ed25519 signature of spk by IK, 64 bytes)
  </spks>
  <ik>
    BASE64(Curve25519/Ed25519 identity public key, 32 bytes)
  </ik>
  <prekeys>
    <pk id='100'>BASE64(EC one-time prekey public key)</pk>
    <pk id='101'>BASE64(EC one-time prekey public key)</pk>
    <!-- ... up to 100+ keys recommended ... -->
  </prekeys>

  <!-- NEW PQXDH ELEMENTS (this document) -->
  <!-- Signed KEM Prekey (last-resort / long-lived) -->
  <kem-spk id='1'>
    BASE64(ML-KEM-1024 public key, 1568 bytes → ~2092 base64 chars)
  </kem-spk>
  <kem-spks>
    BASE64(Ed25519 signature of kem-spk public key bytes by IK, 64 bytes)
  </kem-spks>

  <!-- One-time KEM Prekeys (ephemeral; deleted after single use) -->
  <kem-prekeys>
    <kem-pk id='200' sig='BASE64(Ed25519 sig of this key by IK)'>
      BASE64(ML-KEM-1024 public key, 1568 bytes)
    </kem-pk>
    <kem-pk id='201' sig='BASE64(Ed25519 sig of this key by IK)'>
      BASE64(ML-KEM-1024 public key, 1568 bytes)
    </kem-pk>
    <!-- ... up to 100+ one-time KEM prekeys recommended ... -->
  </kem-prekeys>

  <!-- HYBRID POST-QUANTUM IDENTITY (this document, §4.9) -->
  <!-- ML-DSA-87 identity public key -->
  <pq-ik type='ML-DSA-87'>
    BASE64(ML-DSA-87 public key, 2592 bytes)
  </pq-ik>
  <!-- ML-DSA-87 signature over the bundle identity transcript (§4.9.1) -->
  <pq-sig>
    BASE64(ML-DSA-87 signature, 4627 bytes)
  </pq-sig>

</bundle>
```

#### 4.3.1 Element Definitions

**`<kem-spk id='N'>KEY</kem-spk>`**

- `id`: Unsigned integer, unique identifier for this KEM signed prekey
- Content: Base64-encoded ML-KEM-1024 public key (1568 raw bytes)
- This key is long-lived (rotated on the same schedule as `<spk>`, typically
  every 7–90 days; see §4.5.1)
- MUST be signed by the device's identity key; see `<kem-spks>`
- Serves as "last resort" if all one-time KEM prekeys have been consumed

**`<kem-spks>SIG</kem-spks>`**

- Content: Base64-encoded Ed25519 signature (64 bytes) over the raw bytes of the
  `<kem-spk>` public key, produced with the identity key's private key
- Verifying clients MUST validate this signature; an invalid signature MUST cause
  session initiation to abort

**`<kem-pk id='N' sig='SIG'>KEY</kem-pk>`** (inside `<kem-prekeys>`)

- `id`: Unsigned integer, unique identifier for this one-time KEM prekey
- `sig`: Base64-encoded Ed25519 signature (64 bytes) over the raw bytes of this
  KEM public key, produced with the identity key's private key
- Content: Base64-encoded ML-KEM-1024 public key (1568 raw bytes)
- Each `<kem-pk>` MUST be used at most once (deleted by the owning device after
  the first session using it is established)
- Verifying clients MUST validate the `sig` attribute; an invalid signature MUST
  cause session initiation to abort

#### 4.3.2 Backward Compatibility

Because this profile lives under its own namespace and PEP nodes (§1.2, §4.2),
a client that does not implement it never encounters these bundles at all —
there is no mixed-parsing case, and classical `urn:xmpp:omemo:2` deployments
are unaffected by design.

Within the profile, unknown additional bundle child elements MUST be ignored
(forward compatibility for future revisions). A client receiving a bundle
without `<kem-spk>` MUST refuse to initiate a session with that device — a
bundle under this namespace that lacks KEM material is malformed, not a
classical peer. It MUST NOT fall back to classical-only session initiation.

### 4.4 Session Initiation (PQXDH Handshake)

#### 4.4.1 Sender Side (Alice)

1. Fetch Bob's bundle from `urn:monocles:omemo-pq:1:bundles`, item id = Bob's device ID.
2. Validate all signatures:
   - `<spks>` over `<spk>` bytes using Bob's `<ik>`
   - `<kem-spks>` over `<kem-spk>` bytes using Bob's `<ik>`
   - `sig` attribute of each `<kem-pk>` using Bob's `<ik>`
   - Any validation failure MUST abort session initiation.
3. Select one one-time EC prekey `<pk id='N'>` at random.
4. Select one one-time KEM prekey `<kem-pk id='M' sig='...'>` at random, if any
   are available. Otherwise use `<kem-spk>` as last resort.
5. Construct a `PreKeyBundle` containing:
   - Bob's registration ID and device ID
   - The chosen EC prekey (`pkId`, `pkPublic`)
   - Bob's EC signed prekey (`spkId`, `spkPublic`, `spksSignature`)
   - Bob's identity key (`ik`)
   - The chosen KEM key (`kemId`, `kemPublic`, `kemSignature`)
6. Call `SessionBuilder.process(preKeyBundle)`. The Signal library internally:
   - Verifies the KEM key signature
   - Performs X3DH with the EC keys
   - Encapsulates to the KEM public key (ML-KEM-1024 `Encaps`)
   - Binds the KEM shared secret into the X3DH master secret via HKDF
     (`masterSecret = HKDF(X3DH_secret || KEM_shared_secret, …)`); the concrete
     KDF parameters are libsignal's, see §5.3
   - Produces a `PreKeySignalMessage` embedding the KEM ciphertext (~1568 bytes)
7. Encrypt the message content using the session's message key.
8. Send the `<encrypted xmlns='urn:monocles:omemo-pq:1'>` stanza (same element
   structure as XEP-0384 v0.9).

#### 4.4.2 Receiver Side (Bob)

1. Receive a `<encrypted>` stanza containing a `<key kex='true'>` element for
   Bob's device ID.
2. Deserialise the `PreKeySignalMessage` from the `<key>` content.
3. The Signal library internally:
   - Looks up the referenced EC one-time prekey (deletes it after use)
   - Looks up the referenced KEM prekey (calls `markKyberPreKeyUsed()`)
   - Decapsulates the KEM ciphertext (ML-KEM-1024 `Decaps`)
   - Recomputes the combined master secret
   - Derives the Double Ratchet chain key
4. Decrypt the `<payload>` using the derived message key.
5. If the KEM prekey stock falls below the replenishment threshold, publish a
   refreshed bundle.

#### 4.4.3 KEM Ciphertext Transport

The KEM ciphertext (~1568 bytes for ML-KEM-1024) is embedded inside the
`PreKeySignalMessage` serialised binary format. This is opaque to XMPP and is
transmitted as the base64-encoded content of the `<key>` element, exactly as in
standard OMEMO2. No changes to the `<encrypted>` stanza are required.

### 4.5 Key Lifecycle

#### 4.5.1 KEM Prekey Rotation

| Key | Rotation Trigger | Action |
|-----|-----------------|--------|
| `<kem-spk>` | Periodic (≥7 days, ≤90 days; 30 days in the reference implementation), or on demand | Generate new KEM keypair; sign with IK; publish updated bundle. Between rotations the SAME kem-spk MUST be reused across republishes — regenerating it on every publish defeats the rotation schedule and grows the key store without bound |
| `<kem-pk>` (one-time) | After each session initiation consuming that key | Delete from store; when fewer than 50% of the published batch remain live (50 of 100 in the reference implementation), top the published set back up — retained unconsumed keys stay in the bundle, only the shortfall is freshly generated |
| `<spk>` (EC signed prekey) | Periodic, on the same schedule as `<kem-spk>` (30 days in the reference implementation), or when the published copy no longer matches the local one | Generate new EC keypair; sign with IK; publish updated bundle (the ML-DSA-87 `<pq-sig>` transcript covers the new `<spk>`, so it is re-signed as part of the publish) |

Superseded private KEM keys (no longer in the published bundle) MUST be kept
for a grace period so in-flight session initiations against a previously
fetched bundle still decrypt, and SHOULD then be deleted — the reference
implementation prunes unpublished KEM keys older than 90 days. Retaining them
forever needlessly grows the at-rest secret-key store, which matters for
device-seizure scenarios. Superseded EC signed prekeys are retained for the same
reason and MUST NOT be deleted at rotation time — a handshake already in flight
against the previously published bundle must still complete.

Rotating `<spk>` matters as much as rotating `<kem-spk>`: the two are the
classical and post-quantum halves of the same handshake, and a signed prekey
that stays published for years widens the window in which its compromise
unlocks every session established against it. Because §4.5.1 rotation is
purely local key management, a client may adopt it independently of its peers —
they simply refetch the bundle as they always do.

#### 4.5.2 Last-Resort Key Semantics

The `<kem-spk>` (signed KEM prekey) serves as a last-resort key. Unlike one-time
`<kem-pk>` keys, it is NOT deleted after use. However, implementations SHOULD
track the `(kemPreKeyId, signedPreKeyId, senderBaseKey)` tuple of each session
that used the last-resort key and MUST reject a duplicate tuple (replay attack
prevention). This corresponds to `KyberPreKeyStore.markKyberPreKeyUsed()`
throwing `ReusedBaseKeyException`.

#### 4.5.3 Pre-key Exhaustion

If Bob's published bundle contains `<kem-spk>` but no `<kem-prekeys>`, Alice
MUST use the `<kem-spk>` as last resort (not abort). If the bundle contains
neither `<kem-spk>` nor `<kem-prekeys>`, the peer does not support PQXDH and the
session MUST be rejected by PQXDH-requiring clients.

### 4.6 SCE Envelope Contents

The OMEMO2 message format (XEP-0384 §6) wraps the cleartext in an SCE envelope
(XEP-0420). The reference implementation places **all** per-conversation
information inside the envelope's `<content>` element, leaving the outer stanza
to carry only what the server must route on. Concretely:

**Inside the SCE envelope (`<envelope xmlns="urn:xmpp:sce:1">`):**

```xml
<envelope xmlns='urn:xmpp:sce:1'>
  <content>
    <body xmlns='jabber:client'>…cleartext body or file URL…</body>

    <!-- arbitrary application metadata, all encrypted -->
    <thread xmlns='jabber:client'>…</thread>
    <subject xmlns='jabber:client'>…</subject>
    <reply xmlns='urn:xmpp:reply:0' …/>
    <fallback xmlns='urn:xmpp:fallback:0' …/>
    <replace xmlns='urn:xmpp:message-correct:0' id='…'/>
    <reactions xmlns='urn:xmpp:reactions:0' id='…'>…</reactions>
    <displayed xmlns='urn:xmpp:chat-markers:0' id='…'/>
    <received  xmlns='urn:xmpp:receipts'      id='…'/>
    <active    xmlns='http://jabber.org/protocol/chatstates'/>
    <ephemeral xmlns='urn:xmpp:ephemeral:0' timer='…'/>
    <i-want-out xmlns='urn:xmpp:ephemeral:0'/>
    <live-location xmlns='urn:xmpp:live-location:0'>…</live-location>
    <reference xmlns='urn:xmpp:reference:0' type='data'>
      <media-sharing xmlns='urn:xmpp:sims:1'>…</media-sharing>
    </reference>
    <file-sharing xmlns='urn:xmpp:sfs:0' disposition='inline' id='…'>…</file-sharing>
    <x xmlns='jabber:x:oob'><url>…</url></x>
    <x xmlns='urn:xmpp:webxdc:0'>…</x>
    <data xmlns='urn:xmpp:bob' cid='…' type='…'>…</data>
    <html xmlns='http://jabber.org/protocol/xhtml-im'>…</html>

    <!-- OGP / RDF link-preview description (see §4.6.5) -->
    <Description xmlns='http://www.w3.org/1999/02/22-rdf-syntax-ns#' …>…</Description>
  </content>

  <rpad>…random content padding the envelope to the next 256-byte bucket (§4.6.3)…</rpad>
  <time stamp='2026-05-27T12:34:56Z'/>
  <from jid='alice@example.com'/>
  <to   jid='bob@example.com'/>   <!-- MUC: room bare JID -->
</envelope>
```

#### 4.6.0 SCE affix profile

XEP-0420 (v0.5.0) requires every encryption protocol using SCE to define its own
**affix profile**. This is the profile of this document:

| Affix | Sender | Receiver verification |
|-------|--------|-----------------------|
| `<rpad>` | REQUIRED (bucket padding, §4.6.3) | None; content MUST be ignored; longer-than-expected padding MUST NOT be rejected |
| `<time>` | REQUIRED | MUST be checked against the sending time derived from the stanza (§4.6.2); mismatch beyond the window aborts with a hard error |
| `<from>` | REQUIRED | MUST equal the originator's bare JID (§4.6.1); mismatch aborts with a hard error |
| `<to>` | REQUIRED | MUST equal the recipient per §4.6.1; missing or mismatching aborts with a hard error |

The reference implementation treats a missing `<from>`, `<to>`, or `<time>` as a
hard error: all four affixes are REQUIRED, and a receiver MUST reject an envelope
that omits any of them (§4.6.2 for the `<time>` rationale).

#### 4.6.1 `<to>` and `<from>` (XEP-0420 §4.5 binding)

The sender MUST emit `<from jid='SENDER_BARE_JID'/>` and `<to jid='RECIPIENT'/>`
inside the envelope.

- For 1:1 chats the recipient is the counterpart's bare JID.
- For MUC the recipient is the room's bare JID.

The receiver MUST verify both, against the following expected values:

- `<from>` MUST equal the bare JID of the originator (i.e. the sender derived
  from the outer stanza after carbon unwrap).
- `<to>` MUST equal the bare JID of the recipient as observed on the outer
  stanza, with one exception: in MUC the expected `<to>` is the room bare JID.
  For carbon-sent reflections the expected `<to>` is the original recipient
  (the counterpart), not the local account JID.

A mismatch MUST abort decryption with a hard error. This prevents an attacker
who can manipulate stanza addressing from re-routing a ciphertext addressed to
one recipient to another whose device key also happens to appear in the
`<header>`.

#### 4.6.2 `<time>` element and replay window

The sender MUST include a `<time stamp='…'/>` child in the envelope. The
stamp is an ISO-8601 UTC timestamp at second resolution.

Per XEP-0420 (v0.5.0), the receiver MUST check the stamp against **the sending
time derived from the stanza itself** — the XEP-0203/XEP-0313 delay or MAM
timestamp when present, or the receive time for live stanzas — NOT against the
local wall clock alone.

The reference for that comparison MUST NOT be a stamp the **sender** asserted
about their own stanza. A `<delay/>` is only meaningful here when an
intermediary added it: the receiver's own server (offline storage, MAM result,
carbon wrapper) or the room replaying groupchat history. A receiver that simply
takes the lowest `<delay/>` stamp on the stanza gives the check away, because
the sender can attach one that matches the stamp inside the envelope and walk
the reference back to the replayed ciphertext's own age. Concretely, for a live
stanza a receiver SHOULD ignore `<delay/>` elements whose `from` is the sender's
own JID, or that carry no `from` at all, and fall back to the receive time.
Groupchat history is the acknowledged exception: the room's replay `<delay/>`
carries the room's JID, which an occupant can forge — that stamp is honoured
anyway, because rejecting it would destroy legitimate backlog (see below), and
the ratchet-layer protections still apply.

The reference implementation rejects (hard error) when:

- the stamp lies more than the skew window **in the future** relative to the
  local clock (a future-dated stamp is always bogus), or
- the stamp differs from the stanza-derived sending time by more than the skew
  window (an envelope whose internal stamp disagrees with when the stanza was
  actually sent is a replayed old ciphertext presented as fresh, or a
  manipulated delay stamp).

The reference skew window is ±7 days, generous enough to absorb badly wrong
sender clocks. Comparing against the *stanza* time (rather than the local
clock) is what makes MAM catch-up of arbitrary age pass — a week-old archived
message carries a week-old delay stamp matching its week-old SCE stamp — which
matters because this check necessarily runs after decryption, when the Double
Ratchet has already advanced: a rejected envelope is irrecoverably destroyed,
so the check must never fire on legitimate history. Genuine replays are
additionally defeated at lower layers (duplicate-message detection,
one-time-prekey deletion, the §6.4 last-resort tuple tracker); the `<time>`
affix adds the cross-check that survives those layers being reset. Because the
affix's whole purpose is replay detection, `<time>` is REQUIRED: a receiver MUST
reject (hard error) an envelope that omits `<time>`, carries no `stamp`, or whose
stamp is unparseable — tolerating its absence would let an attacker simply strip
`<time>` to bypass this defence.
Note that the delay/MAM stamp is attested only by the receiver's server: an
adversary controlling that server can align it with a replayed stamp, so this
check is defence-in-depth against weaker adversaries, not a substitute for the
ratchet-layer replay protection.

#### 4.6.3 `<rpad>` bucket padding

The sender MUST include an `<rpad>` element with random content, sized so that
the serialized envelope lands on a **size-bucket boundary**: the reference
implementation pads the UTF-8 serialization of the envelope up to the next
multiple of 256 bytes (so the `<rpad>` content is 1–256 characters drawn from a
set that needs no XML escaping). The AES-GCM ciphertext length then reveals
only a coarse size class of the plaintext instead of its length. A small
fixed-range random pad (e.g. 1–200 bytes, as in earlier drafts and in common
XEP-0420 practice) is NOT sufficient — it still exposes the content length to
within the range. Receivers MUST ignore the `<rpad>` content entirely, MUST NOT
attempt to decode it (it need not be valid base64), and — per XEP-0420 — MUST
NOT reject longer-than-expected padding. The bucket scheme is this profile's
padding policy in the sense of XEP-0420's affix-profile requirement (§4.6.0);
XEP-0420's own two-step example scheme is superseded by it within this profile.

#### 4.6.4 `<keys jid='…'>` enforcement on receive

XEP-0420 / XEP-0384 require that the receiver consider only `<keys>` blocks
whose `jid` attribute matches the receiver's own bare JID. The receiver MUST
ignore wrapped keys appearing under any other JID block, even when one of them
contains the receiver's device id. This prevents a malicious sender from
stuffing a key for the receiver's device under another user's `<keys>` block
to confuse session routing or trick the receiver into using a session it did
not expect.

#### 4.6.5 OGP / RDF link-preview descriptions

When the sender's client generates an Open Graph Protocol (OGP) link preview for
a URL in the body, the resulting RDF description is carried inside the SCE
envelope as a `<Description>` element in the RDF namespace
(`http://www.w3.org/1999/02/22-rdf-syntax-ns#`), alongside the body. Because it
travels inside `<content>` it is encrypted and authenticated end-to-end like any
other metadata; the link target and the fetched preview text are never exposed on
the outer stanza. On receive, the decrypted `<Description>` is forwarded to the
rendering layer (the reference implementation surfaces it via
`Message.getLinkDescriptions()`). A receiver that does not understand the element
MUST ignore it.

#### 4.6.6 Metadata-only envelopes

An SCE envelope MAY contain no `<body>` at all — for example when the stanza
carries only a chat state, a chat marker, or a delivery receipt (see §4.7,
§6.13). Such a "metadata-only" message is still a fully-formed OMEMO2 stanza: the
envelope is encrypted, the `<from>`/`<to>` binding (§4.6.1) and `<time>` window
(§4.6.2) are enforced, and the metadata children are re-injected onto the
outer-stanza representation after decryption so the receiver's per-element
handlers process them uniformly. The receiver MUST NOT create a visible chat
message for an envelope whose `<content>` carries no body and no file
reference, **with one exception**: an envelope carrying both a `<subject>` and
a `<thread>` (and no body) is a subject-only content message and MUST be
rendered — mirroring the plaintext rule that a stanza with `<subject>` +
`<thread>` and no `<body>` is a message. Requiring BOTH elements keeps
session-setup blanks and metadata-only stanzas invisible.

For OMEMO2 conversations the `<subject>` MUST travel inside the SCE envelope,
never on the outer stanza; a receiver MUST ignore any plaintext `<subject>` on
the outer stanza of an OMEMO2 message (a malicious server could otherwise
inject or strip subjects). Receivers SHOULD match SCE content children by
local name where the namespace may be absent (an element emitted without an
explicit `xmlns` inherits the SCE envelope namespace when re-parsed).

#### 4.6.7 Server-processed elements (forbidden inside `<content>`)

Per XEP-0420 "Server-processed Elements", elements the server must be able to
read are forbidden inside the SCE `<content>` and stay on the outer stanza:

- XEP-0334 Message Processing Hints (`urn:xmpp:hints` — `<store>`,
  `<no-store>`, `<no-permanent-store>`, `<no-copy>`)
- XEP-0359 `<stanza-id>` / `<origin-id>` (`urn:xmpp:sid:0`)
- XEP-0033 Extended Stanza Addressing (`http://jabber.org/protocol/address`)
- XEP-0380 `<encryption>` (`urn:xmpp:eme:0`)

Senders MUST NOT place these inside `<content>`, and receivers MUST discard
them when found there. This is not merely conformance hygiene: the receiver's
handlers for these element types deliberately read them from the (unencrypted,
server-attested) outer stanza, so accepting a copy from inside the envelope
would let a sender smuggle *authenticated-looking* routing, archiving or
deduplication directives — e.g. a forged `<stanza-id>` to poison duplicate
suppression — past that design decision. The reference implementation strips
them during SCE parsing, before any content handler runs.

#### 4.6.8 File sharing (XEP-0447)

Shared files are described with XEP-0447 `<file-sharing>` elements (metadata per
XEP-0446 `<file>`, sources per XEP-0103 `<url-data>`) placed inside `<content>`.
A message MAY carry several of them, each with a distinct `id` attribute as
XEP-0447 requires; that is how several files are sent as a single message.

Both the sources and the metadata MUST be inside the envelope:

- The `<url-data target='…'>` of an encrypted upload is an `aesgcm:` URL whose
  fragment carries the file's AES-256-GCM key and IV (XEP-0454). On the outer
  stanza that would hand the server the file key, defeating the upload's
  encryption entirely.
- The `<file>` metadata describes the *plaintext* file. Its `<hash>` (XEP-0300
  over the cleartext bytes) is a stable fingerprint that would let the server —
  or anyone on path — recognise known files, and `<name>`, `<size>` and
  `<media-type>` leak the shape of what was sent.

Consequently a receiver MUST ignore any `<file-sharing>` or `<x
xmlns='jabber:x:oob'>` found on the **outer** stanza of an OMEMO2 message and use
only what the decrypted envelope carries — the same rule, and for the same
reason, as `<subject>` in §4.6.6: otherwise a malicious server could swap the
file of an authenticated message for one of its own.

It follows that this file-sharing profile MUST NOT be combined with body-only
encryption (legacy XEP-0384 v0.3, XEP-0027 PGP), which has no envelope to put the
element in. Implementations that support both MUST fall back to a URL in the
encrypted body for those conversations.

**Fallback for receivers without XEP-0447.** Senders SHOULD additionally place
every file's URL in the envelope's `<body>`, put an `<x xmlns='jabber:x:oob'>`
for the first file beside it, and mark each URL span with XEP-0428 `<fallback>`
elements — one `for='jabber:x:oob'` and one `for='urn:xmpp:sfs:0'`. A single
file with no caption keeps `<body>` = URL with no OOB or fallback element, which
is what pre-0447 implementations of this profile already emit.

Receivers that strip fallback spans MUST drop each span **once** even when it is
marked for several namespaces; deleting the same range once per marker corrupts
the text that follows it.

#### 4.6.9 Sender-side: the header MUST reach at least one recipient device

A sender MUST NOT emit an `<encrypted>` element whose `<header>` carries no
wrapped key for any device of the intended recipient(s). Two exceptions, and
only these: a note-to-self conversation in which this stack knows no other own
device, and a group chat with no other occupants — in both cases there is
genuinely nobody to wrap for, and the envelope is stored locally.

This is a real failure mode rather than a theoretical one, because per-device
wrapping is trust-filtered: a device whose key is untrusted, or whose session
has been marked inactive (§4.5, device removed from the peer's list), is
skipped. When *every* device of the peer is skipped, a naive implementation
still produces a well-formed stanza — wrapped for the sender's own devices
only — and reports it as sent, while the recipient sees nothing but a
"not encrypted for this device" placeholder. Implementations MUST therefore
count the keys they actually attached, not the sessions they intended to use,
and fail the send (surfacing the failure to the user) when none was attached.

### 4.7 Outer-Stanza Minimisation

The outer stanza carries only what the server must read in cleartext:

- `from`, `to`, `type`, `id`, `origin-id` (routing)
- `<encrypted xmlns='urn:monocles:omemo-pq:1'>` (header + payload)
- `<encryption name='PQ-OMEMO2' namespace='urn:monocles:omemo-pq:1'>` (EME hint)
- `<store>` / `<no-store>` / `<no-permanent-store>` hints (archive guidance)
- `<markable>` (chat-marker request hint)
- `<request xmlns='urn:xmpp:receipts'/>` (receipt request hint)
- A fixed OMEMO fallback body for clients that don't speak OMEMO2

Everything else — including chat states, chat markers, delivery receipts,
reactions, message corrections, ephemeral timers, file-transfer SIMS
references, WebXDC payloads, live-location updates, OGP/RDF link-preview
descriptions, etc. — is placed inside the SCE envelope. Senders MUST NOT also
emit those elements on the outer stanza when sending an OMEMO2 message, since
duplication would leak the very metadata the encryption is meant to protect.

Receivers re-inject the relevant SCE child elements onto the outer-stanza
representation after decryption so that existing per-element handlers (chat
state, markers, receipts, reactions, …) can process them uniformly.

### 4.8 Post-Quantum Ratchet (SPQR / ML-KEM Braid)

PQXDH (§4.4) makes the **session-initiation** post-quantum secure. On its own it
does not make the *ongoing* Double Ratchet post-quantum: the per-message
ping-pong key agreement that provides forward secrecy and post-compromise
security (PCS) after the handshake is still classical (X25519). An adversary who
compromises ratchet state would, absent further measures, retain the ability to
decrypt subsequent messages until the next classical DH ratchet step — which a
quantum adversary could defeat.

To close this gap, the reference implementation's libsignal (≥ 0.94.1) runs
Signal's **Sparse Post-Quantum Ratchet (SPQR)**, also called the **ML-KEM Braid**
(see [MLKEMBRAID]), *in addition to* the classical Double Ratchet. SPQR is a
Sparse Continuous Key Agreement: it produces a fresh ML-KEM shared secret per
"epoch" and mixes it into the session secret, so post-compromise healing is
post-quantum, not merely classical. Because ML-KEM keys and ciphertexts are large,
SPQR splits each `KeyGen`/`Encaps`/reconciliation message into erasure-coded
chunks and "braids" those chunks across many ordinary messages, advancing one
epoch over a number of exchanges rather than one round-trip.

This document does **not** define SPQR on the wire. Like the PQXDH key schedule
(§5.3), SPQR is provided by libsignal and is **inherited, not specified** here:

- **Transport.** SPQR braid chunks travel inside the serialised
  `PreKeySignalMessage` / `SignalMessage` produced by libsignal. OMEMO2 carries
  that serialised blob verbatim as the base64 content of the `<key>` element
  (§4.4.3). Implementations MUST transmit the libsignal message bytes unmodified
  and MUST NOT parse, truncate, reorder, or re-wrap them; doing so would corrupt
  the braid. No new OMEMO2 XML is required and the `<encrypted>` stanza format is
  unchanged.
- **Seeding.** The PQXDH handshake (§5.3) yields an additional output that seeds
  the SPQR authentication chain, binding the continuous ratchet to the
  authenticated initial handshake.
- **Out-of-order / dropped messages.** SPQR's chunking and bounded out-of-order
  key windows tolerate the loss and reordering normal for store-and-forward XMPP
  delivery (e.g. MAM catch-up); no OMEMO2-level support is needed.
- **Version requirement.** In the reference libsignal, SPQR is mandatory for
  newly-established sessions (its minimum version is enforced). Consequently
  **both** peers MUST run a SPQR-capable libsignal to establish a PQ-OMEMO2
  session with continuous PQ protection. A peer whose OMEMO2 stack predates SPQR
  may be unable to complete the session; see §6.14.

A from-scratch implementation that does not build on libsignal MUST reproduce
libsignal's SPQR parameters exactly to interoperate (the braid parameters cannot
be derived from the OMEMO2 wire format), which is a further reason building on
libsignal is RECOMMENDED (§7.1).

### 4.9 Hybrid Post-Quantum Identity Authentication

PQXDH (§4.4) and SPQR (§4.8) make the session *confidentiality* and post-compromise
security post-quantum. The *authentication* of the handshake, however — the
signatures over the published pre-keys, by which a receiver knows a bundle really
belongs to the identity it claims — is the classical Ed25519 identity key (§6.2).
A future adversary with a cryptographically-relevant quantum computer could forge
those Ed25519 signatures and so actively machine-in-the-middle session
establishment, defeating the post-quantum confidentiality by a "harvest-and-forge"
attack. The verified fingerprint, committing only to the classical key, would not
detect the substituted keys.

To close this gap the device carries a second, **post-quantum identity key** —
ML-DSA-87 (FIPS 204, NIST category 5, matching ML-KEM-1024) — alongside the
classical one. Together they form the device's **hybrid identity**. The two keys
are published and verified together, so forging a bundle requires breaking **both**
Ed25519 and ML-DSA-87.

#### 4.9.1 Bundle elements and transcript

The bundle (§4.3) gains two elements:

- `<pq-ik type='ML-DSA-87'>BASE64(public key)</pq-ik>` — the ML-DSA-87 identity
  public key (2592 bytes). The `type` attribute is reserved for future agility;
  absent or unknown values default to `ML-DSA-87`.
- `<pq-sig>BASE64(signature)</pq-sig>` — an ML-DSA-87 signature (4627 bytes) over
  the **identity transcript**, produced with the ML-DSA-87 private key under the
  FIPS-204 signing context `"monocles:omemo2:pqid:v1"`.

The identity transcript binds the post-quantum identity to the classical identity,
the EC signed pre-key, and — via a **KEM binding digest** — every ML-KEM pre-key in
the bundle. It is the concatenation, in order:

```
"monocles:omemo2:pq-bundle:v2"
  || u32_be(len(IK))        || IK            (classical identity public key)
  || u32_be(len(PQ-IK))     || PQ-IK         (ML-DSA-87 public key)
  || u32_be(signedPreKeyId)
  || u32_be(len(SPK))       || SPK           (EC signed pre-key public key)
  || u32_be(len(KEM-BINDING)) || KEM-BINDING (32-byte digest, below)
```

`IK` and `SPK` are the libsignal public-key serialization (the 33-byte type-prefixed
form: a `0x05` DJB-type byte followed by the 32-byte Curve25519/Ed25519 key). `PQ-IK`
is the raw ML-DSA-87 verification key (2592 bytes). `signedPreKeyId` is the `<spk>`
`id`. All multi-byte integers are big-endian. The signature itself is produced with
ML-DSA-87 under the FIPS-204 signing context string `"monocles:omemo2:pqid:v1"`.

**KEM binding.** The one-time EC pre-key is still omitted (a served bundle carries
only the per-recipient EC selection, and it contributes only classical forward
secrecy). The ML-KEM pre-keys, however, MUST be bound: the entire post-quantum
*confidentiality* rests on the ML-KEM shared secret, so if the KEM pre-key were
authenticated by the Ed25519 `<kem-spks>`/`sig` alone, the quantum adversary this
whole section exists to counter could forge that Ed25519 signature, substitute a KEM
public key it controls, and defeat the post-quantum confidentiality despite the
hybrid identity. `KEM-BINDING` closes that: it is a 32-byte SHA-256 digest binding
the signed (`<kem-spk>`) key directly and all one-time (`<kem-pk>`) keys through a
manifest hash:

```
KEM-MANIFEST =                    (32 bytes)
    if the bundle has no <kem-pk>:  32 zero bytes
    else: SHA-256( for each <kem-pk>, in bundle document order:
              u32_be(id) || u32_be(len(pub)) || pub )      (pub = raw ML-KEM-1024 key)

KEM-BINDING = SHA-256(            (32 bytes)
      "monocles:omemo2:kem-binding:v1"
   || u32_be(kemSpkId) || u32_be(len(KEM-SPK)) || KEM-SPK   (KEM-SPK = <kem-spk> raw key)
   || u32_be(len(KEM-MANIFEST)) || KEM-MANIFEST )
```

`KEM-SPK` and each `<kem-pk>` `pub` are the raw ML-KEM-1024 public-key bytes exactly
as base64-encoded in the bundle (the libsignal `serialize()` form). The initiator
recomputes `KEM-BINDING` from the bundle it fetched; because one signature commits to
the whole published KEM set, a substituted last-resort key **or any** one-time key
changes the digest and the `<pq-sig>` check fails — regardless of which KEM pre-key
the initiator later selects, since that key necessarily came from the same
authenticated set. An attacker who cannot forge `<pq-sig>` can therefore substitute
neither the classical identity, the signed pre-key, nor any ML-KEM pre-key under a
pinned PQ identity.

**Test vector.** With `kemSpkId = 1`, `KEM-SPK = 0xAA×4`, and two `<kem-pk>`
`(id=2, pub=0xBB×3)`, `(id=3, pub=0xCC×5)`, `KEM-BINDING` is:

```
a2eb025c00c1f1ed7726d0cb96c0148621a6de11b6061724e0a9f2ac48bf712b
```

(Implementations MUST reproduce this exactly; it is asserted by the reference
Rust `pq_kem_binding` and the Java `PqBundle.kemBinding`.)

#### 4.9.2 Receiver processing

On receiving a bundle the initiator MUST, in addition to the §4.4.1 checks:

1. Recompute the §4.9.1 transcript from the fetched bundle — including the
   `KEM-BINDING` digest over the fetched `<kem-spk>` and **all** fetched `<kem-pk>`
   keys — and verify `<pq-sig>` against `<pq-ik>` over it. Any failure MUST abort
   session establishment. (In the reference implementation the receiver recomputes
   `KEM-BINDING` and hands it to `SessionBuilder.process()`, which builds the
   transcript from the bundle's own identity/signed-pre-key fields and performs the
   ML-DSA-87 check internally, so it cannot be bypassed at the application layer.)
2. Pin `<pq-ik>` to the peer's classical identity-key fingerprint on first contact
   (TOFU). A subsequent bundle presenting a *different* `<pq-ik>` for a known
   classical identity is treated as an identity change and the session refused —
   this prevents a later silent swap of the post-quantum key — **except** when that
   classical fingerprint is already user-verified. A user-verified classical identity
   is authenticated out of band, so an attacker cannot complete the handshake (they
   lack the classical private key) regardless of `<pq-ik>`; in that case the receiver
   MAY accept the new `<pq-ik>` and re-pin it. This removes a first-contact
   pin-poisoning denial-of-service (an active attacker pinning a bogus `<pq-ik>` would
   otherwise make the genuine bundle un-usable) while keeping the strict refusal for
   unverified contacts, where the pin is the only post-quantum protection. A receiver
   MAY instead surface the change to the user for explicit re-verification.

#### 4.9.3 Hybrid fingerprint

The fingerprint a user verifies out-of-band MUST commit to both identity keys:

```
hybrid-fingerprint = SHA-256( "monocles:omemo2:ik:v1" || IK || PQ-IK )
```

Committing to `PQ-IK` is what makes manual verification authenticate the
post-quantum key; a fingerprint over the classical key alone would let a quantum
adversary present its own `PQ-IK`.

#### 4.9.4 Mandatory, never downgraded

In the reference implementation the hybrid identity is **mandatory**: a device
always publishes `<pq-ik>`/`<pq-sig>`, and a peer that fetches an OMEMO2 bundle
lacking a valid post-quantum identity refuses to build the session rather than
falling back to classical-only authentication. (This is distinct from the legacy
v0.3 stack of §1.2, which is a separate, explicitly user-selected fallback and
never a silent downgrade of a post-quantum conversation.) The legacy v0.3 bundle
never carries `<pq-ik>`.

#### 4.9.5 Direction of authentication

The hybrid signature authenticates a *bundle*, so it is checked by the party that
*initiates* a session — the one that fetches the peer's bundle and runs §4.9.2. The
party that *receives* an initial PreKey message does not re-fetch a bundle, and so
does not verify the sender's `<pq-ik>` at decrypt time; it authenticates and pins the
sender's post-quantum identity when it later builds its own outbound session to that
peer (fetching their bundle, §4.9.2). This is the same asymmetry as classical OMEMO,
where an inbound PreKey message is decrypted before the recipient makes its own trust
decision about the sender. Consequently a conversation's post-quantum *authentication*
is mutual once both directions have established a session; a single inbound first
message is processed under the classical + PQXDH guarantees of the initiator's chosen
keys before the recipient has pinned the initiator's `<pq-ik>`. The transcript binding
(§4.9.1) ensures the keys actually used in that first message were authorised by the
post-quantum identity the recipient will pin.

### 4.10 Empty Messages (Session Healing and Heartbeats)

Some OMEMO2 messages carry no user content: they exist only to (re)establish or
advance the Double Ratchet. Two cases arise in the reference implementation:

- **Session heal.** When a receiver cannot decrypt an inbound message (for example a
  stale session after one side reset its local state), it re-fetches the sender's
  bundle, builds a fresh outbound session, and sends an empty message so the peer
  adopts the new session and its *next* message decrypts — without waiting for the
  user to send anything. At the libsignal level the first such message is a
  `PreKeySignalMessage` (`<key kex='true'>`).
- **Heartbeat (XEP-0384 business rules).** When a receiver processes the *first*
  message for a given ratchet key whose Double Ratchet counter has reached **53**, it
  MUST reply with an empty message. This forces a DH-ratchet step on the peer, so a
  long *one-directional* conversation still advances the ratchet — bounding
  skipped-message-key storage and restoring post-compromise security (the classical DH
  ratchet and the SPQR braid of §4.8 only step when the conversation changes
  direction; see §6.16). An implementation SHOULD send at most one heartbeat per
  receiving ratchet key, and SHOULD only send to a device it already trusts and
  encrypts to.

This document does not change the XEP-0384 heartbeat trigger; it only pins down the
on-the-wire representation of the empty message so the two behaviours interoperate.

#### 4.10.1 Wire format

An empty message MUST be a normal OMEMO2 `<encrypted>` stanza carrying **both** a
`<header>` and a `<payload>`, where the payload is an encrypted **empty SCE envelope**
— an `<envelope>` whose `<content>` has no children, but which still carries `<rpad>`,
`<time>`, and the §4.6.1 `<from>`/`<to>` binding. It is, in effect, a metadata-only
message (§4.6.6) with no metadata either.

Implementations MUST NOT represent an empty message as a header-only `<encrypted>`
with the `<payload>` omitted. Although such a "key-transport" shape appears in some
OMEMO tooling, a receiver is permitted to dispatch only stanzas that carry a
`<payload>` — the Android reference client does exactly this, dropping a payload-less
`<encrypted>` before decryption. A header-only empty message would therefore be
silently ignored by such a peer, and the heal or heartbeat would have no effect. A
receiver MAY accept a payload-less `<encrypted>` for robustness, but a sender MUST NOT
rely on it.

#### 4.10.2 Receiver behaviour

A receiver decrypts an empty message exactly like any other (§4.4.2) — which
establishes/advances the session — then finds an empty `<content>` and so MUST NOT
create a visible chat message (§4.6.6). The §4.6.1 `<from>`/`<to>` binding and the
§4.6.2 `<time>` window are still enforced. Empty messages SHOULD carry a `<no-store>`
hint (XEP-0334) so they are not archived.

---

## 5. Algorithm Specification

### 5.1 ML-KEM-1024 Parameters

| Parameter | Value |
|-----------|-------|
| Standard | NIST FIPS 203 (ML-KEM.KeyGen/Encaps/Decaps, Algorithms 19–21) |
| Security level | NIST category 5 (comparable to AES-256 key search) |
| Public key size | 1568 bytes |
| Ciphertext size | 1568 bytes |
| Shared secret size | 32 bytes |

### 5.2 Signature Algorithm

KEM public keys are signed using the same algorithm as EC signed prekeys:
Ed25519 (or the identity key's native signing algorithm if the client uses
a Curve25519-based identity key with XEdDSA). Implementations MUST use
`identityKeyPair.getPrivateKey().calculateSignature(kemPublicKey.serialize())`.

### 5.3 PQXDH Key Derivation

**The key agreement is performed entirely inside libsignal's native PQXDH
implementation.** This document does NOT define its own PQXDH key schedule.
Implementations compatible with this proto-XEP construct a libsignal
`PreKeyBundle` carrying both the EC keys and the chosen ML-KEM-1024 prekey, then
call `SessionBuilder.process(bundle)` to initiate (and `SessionCipher.decrypt(…)`
to respond). All of the following are carried out by libsignal, not by the
OMEMO2 layer:

- the ML-KEM-1024 encapsulation/decapsulation (`(CT, SS) = ENCAPS(PQPKB)` /
  `SS = DECAPS(CT)`);
- the Diffie-Hellman computations and their concatenation;
- the binding of the KEM shared secret into the session secret;
- the associated-data construction and re-encapsulation defence.

Conceptually this matches the Signal PQXDH specification
(https://signal.org/docs/specifications/pqxdh/): with `DH1..DH4` the X3DH
Diffie-Hellman outputs and `SS` the ML-KEM-1024 shared secret,

```
SK = KDF( DH1 || DH2 || DH3 || [DH4 ||] SS )
AD = Encode(IKa) || Encode(IKb)   (+ Encode(PQPKB) for re-encapsulation defence)
```

with `DH4` present when a one-time EC prekey is used. The Double Ratchet root and
chain keys are then derived from `SK` as in standard OMEMO2. Because `SS` is
folded into `SK`, an attacker must break **both** the classical DH and the
post-quantum KEM to recover `SK`.

> **Concrete parameters are libsignal's, not this document's.** The exact KDF
> hash, the HKDF `info`/protocol label, the salt, the curve, and the transcript
> encoding are those compiled into the libsignal release in use (libsignal
> ≥ 0.94.1 at the time of writing) — *not* a value chosen by this proto-XEP. In
> particular this document deliberately does **not** mandate the literal
> `info` string from the Signal PQXDH example
> (`"…_CURVE25519_SHA-512_CRYSTALS-KYBER-1024_"`), nor the legacy
> `"WhisperText"` label; either could change with a libsignal version and the
> wire format would be unaffected. Consequently, **interoperability under this
> proto-XEP is defined against other libsignal-based OMEMO2 implementations**
> that share the same PQXDH parameterisation. A from-scratch reimplementation
> MUST reproduce libsignal's PQXDH parameters exactly (it cannot derive them
> from the bundle XML alone), which is why building on libsignal is the
> RECOMMENDED approach (see §7.1).

### 5.4 Symmetric Encryption (Payload)

The symmetric encryption for the OMEMO2 payload MUST use **AES-256-GCM** (NIST SP
800-38D). The encryption key and IV are derived from the 32-byte OMEMO Message
Key (`MK`, produced by the Double Ratchet) using HKDF-SHA-256:

- **Salt**: The cryptographically-bound context string defined in §5.4.2.
- **Info**: `"OMEMO Payload"` (UTF-8).
- **Derived Length**: 44 bytes.
  - `derived[0..31]`  → 32-byte **AES-256 Key**.
  - `derived[32..43]` → 12-byte **IV** (nonce).

The encrypted payload consists of the GCM ciphertext followed by the 16-byte
authentication tag.

#### 5.4.2 Context Binding (Salt and AAD)

To cryptographically bind the ciphertext to the message context and prevent
ciphertext-stealing, re-routing, or device-transpose attacks at the symmetric
layer, the sender MUST provide a context-binding string as both the **HKDF salt**
and the **GCM Additional Authenticated Data (AAD)**.

The binding string is the concatenation of a domain-separation prefix, the
sender's bare JID, the recipient's bare JID, and the source device ID, separated
by null bytes:

```
Binding = "OMEMO2" || 0x00 || SENDER_BARE_JID || 0x00 || RECIPIENT_BARE_JID || 0x00 || u32_be(SOURCE_DEVICE_ID)
```

The receiver MUST recompute the same binding using the expected from/to JIDs (as
verified per §4.6.1) and the observed `sid` from the header, and provide it to
both the HKDF and the decryption operation. Decryption MUST fail if the
authentication tag is invalid.

### 5.5 Key Commitment

AES-256-GCM is **not** a committing AEAD: given a ciphertext it is possible to
construct a *second* key under which that same ciphertext decrypts to a different,
valid plaintext (the "invisible salamander"). The §5.4.2 context binding does not
remove this — associated data binds a message *under a fixed key*, it does not bind
the *key*. In a multi-device / group setting a malicious but authenticated sender
could therefore wrap **different** message keys to different recipient devices and
craft a single `<payload>` that each opens to different content (sender
equivocation).

To make the payload key-committing, the sender MUST publish a single **key
commitment** to the message key, shared by all recipients, computed with
HKDF-SHA-256:

- **IKM**: the 32-byte OMEMO Message Key `MK` (the same value wrapped per device).
- **Salt**: the §5.4.2 context-binding string.
- **Info**: `"monocles:omemo2:key-commitment:v1"` (UTF-8).
- **Output**: 32 bytes.

```
Commit = HKDF-SHA-256(IKM = MK, salt = Binding, info = "monocles:omemo2:key-commitment:v1", L = 32)
```

The commitment is carried in a single `<commit>` child of `<encrypted>` (base64),
a sibling of `<payload>`, present whenever a `<payload>` is present:

```xml
<encrypted xmlns='urn:monocles:omemo-pq:1'>
  <header sid='...'> ... </header>
  <payload>BASE64(GCM ciphertext || tag)</payload>
  <commit>BASE64(32-byte commitment)</commit>
</encrypted>
```

A receiver processing a `<payload>` MUST, **before** decrypting, unwrap its message
key, recompute `Commit` from that key and the recomputed binding, and compare it in
constant time against the `<commit>` value. The receiver MUST reject the message
(and MUST NOT attempt decryption) if `<commit>` is absent, malformed, or does not
match. Because `Commit` is a one-way, collision-resistant function of `MK`, a single
published value can match at most one message key: honest recipients (who all
receive the same `MK`) all verify successfully, whereas an equivocating sender who
wrapped a different key to a given device produces a commitment that device rejects.
This closes both the invisible-salamander collision and sender equivocation.

`Commit` uses a distinct HKDF `info` from the payload key/IV (§5.4), so the two
outputs are independent: publishing `Commit` reveals nothing about the AES key, the
IV, or `MK`. The commitment is **not** additionally folded into the GCM AAD — key
commitment is provided by the explicit single-shared-value check, not by associated
data. A key-transport / empty-payload `<encrypted>` (§4.10 permits a receiver to
accept one) carries no `<commit>`.

---

## 6. Security Considerations

### 6.1 Post-Quantum Security

ML-KEM-1024 targets NIST security category 5 (at least as hard to break as
AES-256 key search). Combined with X3DH's classical ~128-bit security (X25519),
the hybrid construction is secure as long as at least one component is unbroken.

### 6.2 Identity Key Binding

All KEM public keys MUST be signed by the device's identity key. A client MUST
verify these signatures before initiating a session. Without this check, an active
attacker could substitute their own KEM public key in the bundle, causing the
sender to derive a shared secret the attacker knows (KEM-PK substitution attack).

### 6.3 One-Time Key Forward Secrecy

Each one-time KEM prekey (`<kem-pk>`) is deleted after first use. This means
that compromise of the device's long-term KEM-SPK private key after the fact does
not compromise sessions that used a `<kem-pk>`. Compromise of the `<kem-spk>`
private key does compromise last-resort sessions, but does not affect sessions
that used one-time `<kem-pk>` keys (already deleted).

### 6.4 Replay Attack on Last-Resort Keys

Because `<kem-spk>` is reused across multiple sessions, a malicious server could
replay a previous session initiation using the same KEM ciphertext. Implementations
MUST prevent this by tracking `(kemPreKeyId, signedPreKeyId, senderBaseKey)` tuples
(cf. `KyberPreKeyStore.markKyberPreKeyUsed` with `ReusedBaseKeyException`).

### 6.5 KEM Ciphertext Size

ML-KEM-1024 ciphertexts are 1568 bytes, compared to 33 bytes for an EC point.
This increases the size of `PreKeySignalMessage`s by ~2.1 KB (base64-encoded).
This is a one-time cost per session initiation; subsequent messages in an
established session are unaffected.

### 6.6 Trust-on-First-Use

This extension does not change the OMEMO trust model. Identity key verification
(via fingerprint comparison or BTBV) applies equally to the classical identity key;
there is no separate KEM identity key. The KEM keys are ephemeral relative to the
session; the identity key is the trust anchor.

### 6.7 Algorithm Agility

This document specifies ML-KEM-1024 exclusively. Future revisions MAY introduce
support for additional KEM algorithms via the `<kem-spk>` element's `type`
attribute (undefined in this version; defaults to ML-KEM-1024).

### 6.8 `isTrustedIdentity()` Implementation Note

Signal's `SessionBuilder` calls `isTrustedIdentity()` as part of `process()`.
Implementations that always return `true` from this method (delegating trust
management to the application layer, as is common in XMPP clients) are responsible
for enforcing key-change detection in the application layer. A changed identity key
that is not flagged by the application layer constitutes an undetected MITM
opportunity. Implementations SHOULD display UI warnings on identity key changes.

### 6.9 SCE Envelope Binding (XEP-0420 §4.5)

The receiver MUST validate the SCE envelope's `<from>` and `<to>` elements (see
§4.6.1). Skipping the `<to>` check enables a stanza-rerouting attack: an
adversary who can manipulate stanza addressing could take a ciphertext bound
for one recipient and deliver it to another whose device key was already in the
`<header>` (e.g., from group-chat membership). Without the `<to>` binding, the
victim's device would decrypt the ciphertext as if it had been addressed to
them, allowing the attacker to convert a group message into a private one or
vice versa.

### 6.10 Receiver-Side `<keys jid>` Enforcement

The receiver MUST select wrapped keys only from the `<keys>` block whose `jid`
attribute matches its own bare JID (see §4.6.4). Although the libsignal
session is cryptographically bound to the (sender, sender-deviceId) pair —
making cross-jid key reuse infeasible to exploit in practice — the check is a
required defence-in-depth measure and a XEP-0420 conformance requirement.

### 6.11 SCE `<time>` Replay Window

Replay protection rests on three layers that act *before* the `<time>` check
can: the Double Ratchet's duplicate-message detection, one-time-prekey deletion
(EC and KEM, §4.5.1), and the last-resort-prekey replay tracker (§6.4). The
`<time>` binding (§4.6.2) adds a fourth: the envelope's internal stamp must
agree with the sending time derived from the stanza itself, so an old
ciphertext cannot be re-presented as a fresh live message (nor future-dated),
even across a reset of the ratchet-layer state. Because the reference point is
the stanza's own delay/MAM stamp rather than the local clock, archived history
of arbitrary age still verifies — see §4.6.2, including its note on what this
check can and cannot guarantee against the receiver's own server.

### 6.12 Always-Encrypted HTTP File Upload

The reference implementation enforces aesgcm (per-upload random AES-256-GCM
key + IV in the URL fragment) for **every** HTTP file upload, irrespective of
whether the parent message is end-to-end encrypted. For OMEMO2 chats the URL
(and therefore the key) travels inside the SCE envelope; the HTTP host stores
ciphertext only. For cleartext chats the HTTP host still stores ciphertext —
only the link URL is in cleartext, which is the same trust model as the chat
itself. This change closes the per-upload metadata-leak vector that would
otherwise allow a malicious or compromised HTTP host to inspect file
contents.

### 6.13 Metadata Stanza Encryption

For OMEMO2 conversations, chat states, chat markers and delivery receipts
travel inside the SCE envelope (see §4.6, §4.7). Sending them on the outer
stanza would leak typing-, read- and delivery- activity to the server even
when the message body itself is encrypted. Senders MUST place these elements
inside `<content>` when the conversation uses OMEMO2; receivers re-inject them
onto the outer-stanza representation after decryption so existing handler
logic continues to work. When such a stanza carries no body, the result is a
metadata-only envelope (§4.6.6): it is decrypted and its metadata processed,
but no visible message is created. For the most security-sensitive metadata —
live-location updates and stops — the reference implementation additionally
rejects any *plaintext* element arriving on the outer (unauthenticated,
server-readable) stanza, accepting it only from inside a decrypted SCE envelope.
This prevents a federated JID or the server from registering or hijacking
incoming live-location sessions and bypassing the metadata-encryption
guarantee.

### 6.14 Continuous Post-Quantum Security (SPQR)

PQXDH alone bounds post-quantum protection to the handshake: after a state
compromise, a quantum adversary could follow the *classical* Double Ratchet
forward until the next DH step. With SPQR (§4.8) active, post-compromise healing
is itself post-quantum — recovery does not rely on the classical DH ratchet
remaining unbroken against a quantum adversary. Two properties follow:

- **Forward secrecy and PCS are hybrid.** As with the handshake, an attacker must
  break *both* the classical ratchet and the ML-KEM braid to track the session
  across a healing epoch.
- **Healing is gradual, not instantaneous.** Because an SPQR epoch completes only
  after enough braided chunks have been exchanged, post-quantum PCS is regained
  over a number of messages rather than at a single round-trip. Implementations
  and users should treat the post-quantum PCS window as spanning several
  exchanges, not one message.

Because SPQR is mandatory for newly-established sessions in the reference
libsignal, a peer running a pre-SPQR OMEMO2 stack may be unable to complete the
session at all. This is intentional: the reference implementation does not
silently downgrade to a session without continuous post-quantum protection.
Deployments mixing SPQR-capable and pre-SPQR OMEMO2 clients SHOULD ensure all
participants are upgraded (cf. the legacy-OMEMO separation in §1.2, which is a
different, explicitly user-selected fallback and never a silent downgrade).

The SPQR construction, its chunking, and its security proofs are defined by
[MLKEMBRAID]; this document only specifies that its messages ride unmodified
inside the OMEMO2 `<key>` transport (§4.8).

### 6.15 Post-Quantum Identity Authentication (harvest-and-forge)

PQXDH and SPQR protect confidentiality and PCS against a quantum adversary, but on
their own leave *authentication* classical: the pre-key signatures, and therefore
the binding of the published keys to the identity, rest on Ed25519 (§6.2). An
adversary who can forge Ed25519 — e.g. a future quantum adversary, or one who has
"harvested" enough to later forge — could publish a bundle in the victim's name and
actively machine-in-the-middle session establishment, recovering the plaintext that
PQXDH/SPQR were meant to protect. The hybrid post-quantum identity (§4.9) closes
this: every bundle additionally carries an ML-DSA-87 signature over the identity
transcript, and the verified fingerprint commits to the ML-DSA-87 key, so forging a
bundle requires breaking **both** Ed25519 and ML-DSA-87.

Two properties are essential and are requirements, not options:

- **The fingerprint MUST commit to `PQ-IK`** (§4.9.3). A fingerprint over the
  classical key alone is useless here: the adversary keeps the victim's classical
  key (or forges signatures under it) and swaps in its own `PQ-IK`, which a
  classical-only fingerprint would not reveal.
- **`PQ-IK` MUST be pinned to the classical identity** (§4.9.2) so it cannot be
  silently changed after first contact. On an already-pinned, *unverified* peer a
  different `PQ-IK` is rejected; once the classical fingerprint is user-verified a
  change MAY be accepted and re-pinned (the out-of-band verification authenticates
  the identity, so an attacker still cannot complete the handshake). On genuine first
  contact an attacker cannot in any case complete the handshake, because the DH/PQXDH
  agreement needs the victim's classical private keys, which the attacker does not
  have — so first-contact pinning bounds the attacker to denial of service, not a
  confidentiality break.

Because the app is built before release, the hybrid identity is mandatory with no
classical-only fallback (§4.9.4): a peer that omits a valid `<pq-ik>`/`<pq-sig>` is
refused rather than downgraded.

### 6.16 Heartbeats and One-Directional Conversations

The Double Ratchet — and the SPQR braid (§4.8) — only take a DH/KEM ratchet step when
the conversation changes direction. In a strictly one-directional conversation (one
party sends many messages without reply) no such step occurs, so forward secrecy
degrades to symmetric-chain ratcheting alone and post-compromise security is suspended
until a reply is sent; the receiver must also retain an ever-growing set of skipped
message keys. The XEP-0384 heartbeat (§4.10) bounds both: on processing the first
message for a ratchet key whose counter has reached 53, the receiver sends an empty
message, forcing the next ratchet step. Heartbeats carry no plaintext, are sent only
to already-trusted devices, and are rate-limited to one per receiving ratchet key, so
they add no new exposure while strictly improving the ongoing session's security. The
empty-message wire format (§4.10.1) matters here for correctness: a header-only
message a peer drops would leave the counter unbounded and the ratchet stalled.

### 6.17 Group-Message Payload Authenticity

In a group (MUC) message, every recipient device receives a wrap of the **same**
payload message key MK (§4.4.3, the shared AES-256-GCM input of §5.4). Payload
authenticity within a group is therefore only as strong as the circle of
MK-holders: any co-recipient of a message knows MK and could, in principle,
construct a *different* payload valid under the same key. Exploiting this
requires more than knowing MK — the forger must also (a) replay the victim's
original wrapped `<key>` element before the victim consumes the genuine one
(afterwards the Double Ratchet rejects it as a duplicate), and (b) get the
forged stanza attributed to the impersonated sender's occupant, which the
`<from>` binding (§4.6.1) ties to stanza-level attribution — in practice
demanding collusion with the MUC service. This bounds the attack to a narrow
race by a malicious member colluding with a malicious room host. The property
is identical in classical XEP-0384 (whose shared payload key + HMAC is equally
forgeable by co-recipients); it is documented here so readers do not assume
per-sender payload signatures. Deployments needing cryptographic sender
attribution *within* a group against colluding members and room hosts would
need per-message sender signatures, which this profile does not add. Users can
detect impersonation of a *verified* contact via the failure of that contact's
own devices to decrypt replies, and 1:1 conversations are unaffected (the only
other MK-holders are the sender's and recipient's own devices).

---

## 7. Implementation Notes

### 7.1 libsignal Dependency

The reference implementation (monocles chat Android and desktop clients) uses the
monocles fork of Signal's open-source libsignal library
(https://codeberg.org/monocles/pq-omemo-2), based on upstream libsignal
(https://github.com/signalapp/libsignal) version ≥ 0.94.1. Key API mapping:

| PQXDH Operation | libsignal API |
|----------------|---------------|
| Generate KEM keypair | `KEMKeyPair.generate(KEMKeyType.KYBER_1024)` |
| Sign KEM public key | `identityKeyPair.getPrivateKey().calculateSignature(kemPair.getPublicKey().serialize())` |
| Create KEM prekey record | `new KyberPreKeyRecord(id, timestamp, kemPair, signature)` |
| Process bundle (PQXDH) | `new SessionBuilder(store, remote, local).process(preKeyBundle)` |
| KEM key cleanup | `KyberPreKeyStore.markKyberPreKeyUsed(id, signedPreKeyId, baseKey)` |

The same libsignal build also drives SPQR / ML-KEM Braid (§4.8) transparently:
sessions established via `SessionBuilder.process()` initialise SPQR state
internally (seeded from a PQXDH handshake output), and `SessionCipher.encrypt` /
`decrypt` carry the braid chunks inside the serialised message. The OMEMO2 layer
exposes **no** API for SPQR and MUST NOT need one — it only base64-transports the
serialised `CiphertextMessage` in the `<key>` element (§4.4.3) and feeds received
`<key>` bytes back to `new SignalMessage(...)` / `new PreKeySignalMessage(...)`
unmodified. Re-encoding, splitting, or version-pinning those bytes would break the
braid.

### 7.2 Prekey Replenishment

Implementations SHOULD monitor the one-time KEM prekey count after each session
initiation and republish the bundle when the count drops below 50% of the
published batch size (50 of the published 100 in the reference implementation).
This keeps last-resort-prekey fallback rare: each fallback re-uses the same
`<kem-spk>` and so gives weaker forward-secrecy for the handshake step itself.
A simpler approach is to trigger replenishment on every inbound
`PreKeySignalMessage`.

Republishing MUST be a *top-up*, not a wholesale regeneration: retained
unconsumed one-time keys stay in the bundle, only the shortfall is freshly
generated, and the `<kem-spk>` is reused until its rotation window expires
(§4.5.1). Implementations SHOULD reconcile against their **own published
bundle** on connect (fetch the `…:bundles` item, keep every published key still
present locally, replace the rest) rather than assuming local state matches the
server — a failed publish or a server-side node wipe is then self-healing,
while a healthy node produces no republish at all.

### 7.3 F-Droid / Reproducible Build Compatibility

Building libsignal from source is required for F-Droid compatibility, as F-Droid
does not accept prebuilt native `.so` binaries. Clients distributing via F-Droid
MUST build libsignal from source at a pinned release tag (the reference clients
build the monocles fork at https://codeberg.org/monocles/pq-omemo-2) and
cross-compile for all Android ABIs using the Rust toolchain with Android targets:
```
rustup target add aarch64-linux-android armv7-linux-androideabi \
                  x86_64-linux-android i686-linux-android
```

### 7.4 Bundle Size

A full bundle with 100 EC prekeys and 100 KEM prekeys occupies approximately:
- EC prekeys: 100 × 44 bytes (base64) ≈ 4.4 KB
- KEM prekeys (key + sig): 100 × (2092 + 88) bytes ≈ 218 KB

Clients and servers SHOULD be prepared to handle PEP items of this size.

---

## 8. XML Schema

```xml
<?xml version='1.0' encoding='UTF-8'?>
<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'
           targetNamespace='urn:monocles:omemo-pq:1'
           xmlns='urn:monocles:omemo-pq:1'
           elementFormDefault='qualified'>

  <xs:element name='bundle'>
    <xs:complexType>
      <xs:sequence>
        <xs:element ref='spk'/>
        <xs:element ref='spks'/>
        <xs:element ref='ik'/>
        <xs:element ref='prekeys'/>
        <!-- PQXDH extensions (optional for backward compat) -->
        <xs:element ref='kem-spk' minOccurs='0'/>
        <xs:element ref='kem-spks' minOccurs='0'/>
        <xs:element ref='kem-prekeys' minOccurs='0'/>
        <!-- Hybrid post-quantum identity (§4.9). Optional for backward compat in
             the schema, but mandatory in the reference implementation. -->
        <xs:element ref='pq-ik' minOccurs='0'/>
        <xs:element ref='pq-sig' minOccurs='0'/>
      </xs:sequence>
    </xs:complexType>
  </xs:element>

  <!-- Existing OMEMO2 elements (abbreviated) -->
  <xs:element name='spk'>
    <xs:complexType>
      <xs:simpleContent>
        <xs:extension base='xs:base64Binary'>
          <xs:attribute name='id' type='xs:unsignedInt' use='required'/>
        </xs:extension>
      </xs:simpleContent>
    </xs:complexType>
  </xs:element>
  <xs:element name='spks' type='xs:base64Binary'/>
  <xs:element name='ik' type='xs:base64Binary'/>
  <xs:element name='prekeys'>
    <xs:complexType>
      <xs:sequence>
        <xs:element name='pk' maxOccurs='unbounded'>
          <xs:complexType>
            <xs:simpleContent>
              <xs:extension base='xs:base64Binary'>
                <xs:attribute name='id' type='xs:unsignedInt' use='required'/>
              </xs:extension>
            </xs:simpleContent>
          </xs:complexType>
        </xs:element>
      </xs:sequence>
    </xs:complexType>
  </xs:element>

  <!-- PQXDH: Signed KEM Prekey -->
  <xs:element name='kem-spk'>
    <xs:complexType>
      <xs:simpleContent>
        <xs:extension base='xs:base64Binary'>
          <xs:attribute name='id' type='xs:unsignedInt' use='required'/>
          <!-- Reserved for future algorithm agility -->
          <xs:attribute name='type' type='xs:string' use='optional'/>
        </xs:extension>
      </xs:simpleContent>
    </xs:complexType>
  </xs:element>

  <!-- PQXDH: KEM Signed Prekey Signature -->
  <xs:element name='kem-spks' type='xs:base64Binary'/>

  <!-- PQXDH: One-time KEM Prekeys -->
  <xs:element name='kem-prekeys'>
    <xs:complexType>
      <xs:sequence>
        <xs:element name='kem-pk' maxOccurs='unbounded'>
          <xs:complexType>
            <xs:simpleContent>
              <xs:extension base='xs:base64Binary'>
                <xs:attribute name='id' type='xs:unsignedInt' use='required'/>
                <xs:attribute name='sig' type='xs:base64Binary' use='required'/>
                <!-- Reserved for future algorithm agility -->
                <xs:attribute name='type' type='xs:string' use='optional'/>
              </xs:extension>
            </xs:simpleContent>
          </xs:complexType>
        </xs:element>
      </xs:sequence>
    </xs:complexType>
  </xs:element>

  <!-- Hybrid PQ identity (§4.9): ML-DSA-87 identity key + bundle signature -->
  <xs:element name='pq-ik'>
    <xs:complexType>
      <xs:simpleContent>
        <xs:extension base='xs:base64Binary'>
          <!-- KEM/signature algorithm; defaults to ML-DSA-87 -->
          <xs:attribute name='type' type='xs:string' use='optional'/>
        </xs:extension>
      </xs:simpleContent>
    </xs:complexType>
  </xs:element>
  <xs:element name='pq-sig' type='xs:base64Binary'/>

</xs:schema>
```

---

## 9. Example Bundle Publication

```xml
<iq type='set' from='alice@example.com/monocles' id='pub1'>
  <pubsub xmlns='http://jabber.org/protocol/pubsub'>
    <publish node='urn:monocles:omemo-pq:1:bundles'>
      <item id='12345'>
        <bundle xmlns='urn:monocles:omemo-pq:1'>

          <!-- EC identity key -->
          <ik>Bfp1aF3bABb0/rqRhFcjkpnCW/...</ik>

          <!-- EC signed prekey -->
          <spk id='42'>BzRdA3CJH9rCb3QfPjUikA...</spk>
          <spks>ZXhhbXBsZXNpZ25hdHVyZW9mc3BrYnlpZGVudGl0eWtleQ==</spks>

          <!-- 100 EC one-time prekeys -->
          <prekeys>
            <pk id='100'>BaqO...</pk>
            <pk id='101'>BjXP...</pk>
            <!-- ... -->
          </prekeys>

          <!-- ML-KEM-1024 signed prekey (last-resort) -->
          <kem-spk id='1'>
            <!-- ~2092 base64 characters for 1568-byte ML-KEM-1024 public key -->
            MIID5QIBADCC... (truncated for readability)
          </kem-spk>
          <kem-spks>
            <!-- 88 base64 chars for 64-byte Ed25519 signature -->
            aGVsbG8gd29ybGQgdGhpcyBpcyBhbiBleGFtcGxlIHNpZ25hdHVyZSE=
          </kem-spks>

          <!-- 100 ML-KEM-1024 one-time prekeys -->
          <kem-prekeys>
            <kem-pk id='200' sig='c2lnMQ=='>
              <!-- ~2092 base64 chars -->
              MIID5QIBADCC... (truncated)
            </kem-pk>
            <kem-pk id='201' sig='c2lnMg=='>
              MIID5QIBADCC... (truncated)
            </kem-pk>
            <!-- ... -->
          </kem-prekeys>

        </bundle>
      </item>
    </publish>
    <publish-options>
      <x xmlns='jabber:x:data' type='submit'>
        <field var='FORM_TYPE' type='hidden'>
          <value>http://jabber.org/protocol/pubsub#publish-options</value>
        </field>
        <field var='pubsub#access_model'>
          <value>open</value>
        </field>
      </x>
    </publish-options>
  </pubsub>
</iq>
```

---

## 10. IANA / XMPP Registrar Considerations

This document defines the new XML namespace `urn:monocles:omemo-pq:1`, covering
the `<encrypted>` element, the `<bundle>` element (including `<kem-spk>`,
`<kem-spks>`, `<kem-prekeys>`, `<kem-pk>`, `<pq-ik>`, `<pq-sig>`), the
`<devices>` element, and the two PEP nodes derived from it (§4.2). It does NOT
add elements to — and implementations MUST NOT read from or publish to — the
`urn:xmpp:omemo:2` namespace defined in XEP-0384, which remains reserved for
classical OMEMO2. §1.2 explains why sharing that namespace would harm both
ecosystems: this profile cannot complete a session with a classical client, so
a shared namespace would only make classical clients burn prekeys and fail
undebuggably.

Should the XSF adopt this profile, the namespace would move into the
`urn:xmpp:` tree under the usual namespace-versioning rules (e.g.
`urn:xmpp:omemo-pq:0`); the vendor prefix exists precisely so the interim
deployment cannot collide with a future standardised namespace.

---

## 11. Open Issues

1. **Algorithm agility**: The `type` attribute on `<kem-spk>` and `<kem-pk>` is
   reserved but not specified. A future revision should define negotiation of
   additional KEM algorithms (e.g., ML-KEM-768 for constrained devices).

2. **Last-resort key replay tracking** (storage schema): §6.4 requires tracking
   `(kemPreKeyId, signedPreKeyId, senderBaseKey)` tuples. The reference
   implementation persists these in a dedicated SQLite table
   (`kyber_last_resort_sessions`) and rejects duplicates with a
   `ReusedBaseKeyException`. The storage schema itself is implementation
   defined.

3. **Feature advertisement**: Now largely addressed by the dedicated namespace:
   the presence of the `urn:monocles:omemo-pq:1:bundles` PEP node is itself the
   authoritative capability signal (§4.2), and the reference implementation
   additionally advertises `urn:monocles:omemo-pq:1`,
   `urn:monocles:omemo-pq:1:pqxdh` and `urn:monocles:omemo-pq:1:spqr` in
   disco#info as a pre-flight diagnostics aid.

   **Security note.** Such a feature would be a *usability/diagnostics* aid only and
   MUST NOT be used as a security gate. disco#info is served unauthenticated (by the
   peer's server / an attacker-controllable path) and can be spoofed or stripped, so
   a client MUST NOT decide whether a conversation is post-quantum-protected from a
   disco feature. The authenticated decision is the bundle itself: a bundle without a
   valid `<pq-ik>`/`<pq-sig>` is refused (§4.9.4) regardless of what disco advertises,
   and stripping the feature string gains an attacker nothing. This is why the hybrid
   identity does not rely on, and this document does not require, a disco feature for
   its security — only, optionally, for nicer pre-flight detection.

4. **Multi-device key agreement**: This document does not address group messaging
   (MUC) scenarios; the behaviour is the same as standard OMEMO2 (encrypt
   separately for each device of each MUC participant).

5. **Skew window for `<time>`**: §4.6.2 verifies the stamp against the
   stanza-derived sending time (per XEP-0420 v0.5.0) with a ±7-day window, plus
   a +7-day future cap against the local clock. The window is sized to absorb
   badly wrong sender clocks; a future revision could define server-assisted
   clock alignment to allow a tighter bound.

6. **Transcript version (v0.0.3)**: the bundle-signature transcript label is
   `monocles:omemo2:pq-bundle:v2` and now covers the ML-KEM pre-keys via the
   §4.9.1 KEM binding. The label change means a `v1` signature (which omitted the
   KEM binding) fails verification. As this profile is unreleased, no
   compatibility shim is provided: clients predating v0.0.3 cannot establish PQ
   sessions with v0.0.3+ clients and MUST be upgraded in lockstep (Android and
   desktop alike).

---

## Revision History

- **0.0.6** (2026-07-28): Hardening pass from an implementation audit; no wire
  format change, so **no lockstep deployment is required** — each client can
  adopt these independently. (1) `<time>` affix verification (§4.6.2): the
  reference for the replay window MUST NOT be a `<delay/>` the sender asserted
  about their own stanza, since attaching a matching one neutralises the check;
  for live stanzas, honour only intermediary-supplied delays and otherwise use
  the receive time. Groupchat history stays an acknowledged exception.
  (2) Stack routing (§1.2): a container MUST be processed with the stack its
  namespace names, for every message shape including empty/key-transport ones —
  otherwise a legacy container re-wrapping a captured OMEMO2 key blob can be
  used to drive the OMEMO2 ratchet. (3) Sender-side minimum recipients
  (§4.6.9): a header that ended up with no key for any recipient device MUST
  fail the send rather than go out readable only by the sender's own devices.
  (4) EC signed prekey rotation (§4.5.1): `<spk>` now rotates on the same
  30-day schedule as `<kem-spk>`, with superseded private keys retained.
- **0.0.5** (2026-07-26): File sharing (§4.6.8). XEP-0447 `<file-sharing>` elements —
  several per message, which is how several files travel as one message — are carried
  inside the SCE envelope, because the `aesgcm:` source URL contains the file key and the
  `<file>` metadata (name, size, plaintext hash) describes the cleartext. Receivers MUST
  ignore `<file-sharing>` and `<x jabber:x:oob>` on the outer stanza, as they already do
  for `<subject>`. Additive and backward compatible: implementations that do not parse
  the element still receive every file through the body URLs and the OOB fallback, so no
  lockstep deployment is required — but a receiver that strips XEP-0428 fallback spans
  must deduplicate spans marked for both `jabber:x:oob` and `urn:xmpp:sfs:0`.
- **0.0.4** (2026-07-10): Key commitment (§5.5). A single shared `<commit>` element
  (HKDF over the message key under a distinct label) is now published beside the
  `<payload>` and MUST be verified before decryption, making the AES-256-GCM payload
  key-committing. Closes the invisible-salamander AEAD collision and malicious-sender
  equivocation across a peer's devices / group members. A `<payload>` without a valid
  `<commit>` MUST be rejected (deploy Android + desktop in lockstep; no compat shim).
- **0.0.3** (2026-07-07): Transcript v2 — `<pq-sig>` now covers all ML-KEM
  pre-keys via the §4.9.1 KEM binding digest (closes a harvest-and-forge gap where
  the KEM key underpinning post-quantum confidentiality was authenticated by
  Ed25519 alone). `<time>` made a REQUIRED affix that receivers MUST reject when
  absent/unparseable (§4.6.0, §4.6.2).
- **0.0.2** (2026-07-05): New namespace `urn:monocles:omemo-pq:1`; SCE affix
  profile, stanza-time `<time>` verification, server-processed-element discard,
  256-byte bucket padding, KEM prekey lifecycle, hybrid ML-DSA-87 identity.

## 12. References

- [PQXDH] Signal Foundation, "The PQXDH Key Agreement Protocol",
  https://signal.org/docs/specifications/pqxdh/ (2023)
- [MLKEMBRAID] Signal Foundation, "The ML-KEM Braid Protocol" (Sparse Post-Quantum
  Ratchet), https://signal.org/docs/specifications/mlkembraid/ ; reference
  implementation and ProVerif models at
  https://github.com/signalapp/sparsepostquantumratchet
- [FIPS203] NIST, "Module-Lattice-Based Key-Encapsulation Mechanism Standard
  (ML-KEM)", FIPS 203, August 2024
- [XEP-0384] OMEMO Encryption, https://xmpp.org/extensions/xep-0384.html
- [XEP-0420] Stanza Content Encryption (SCE), version 0.5.0 (2026-06-23),
  https://xmpp.org/extensions/xep-0420.html
- [libsignal] Signal's libsignal library, https://github.com/signalapp/libsignal
- [libsignal-monocles] monocles fork of libsignal (PQ OMEMO 2) used by the
  monocles chat Android and desktop clients, https://codeberg.org/monocles/pq-omemo-2
- [X3DH] Signal Foundation, "The X3DH Key Agreement Protocol",
  https://signal.org/docs/specifications/x3dh/ (2016)
- [DR] Signal Foundation, "The Double Ratchet Algorithm",
  https://signal.org/docs/specifications/doubleratchet/ (2016)
