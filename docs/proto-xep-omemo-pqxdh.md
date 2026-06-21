# Proto-XEP: OMEMO Post-Quantum Extended Diffie-Hellman (OMEMO-PQXDH)

**Title:** OMEMO Post-Quantum Extended Diffie-Hellman
**Version:** 0.0.1
**Status:** ProtoXEP
**Type:** Standards Track
**Author:** Arne-Brün Vogelsang
**Extends:** XEP-0384 (OMEMO Encryption), version 0.9.1; XEP-0420 (Stanza Content Encryption)
**Namespace:** `urn:xmpp:omemo:2` (extension of existing OMEMO2 namespace)
**Date:** 2026-06-14

---

## Abstract

This document specifies an extension to OMEMO Encryption (XEP-0384 version 0.9.1)
that adds Post-Quantum Extended Diffie-Hellman (PQXDH) using ML-KEM-1024
(CRYSTALS-Kyber, NIST FIPS 203). It extends the OMEMO2 bundle format with signed
and one-time KEM prekeys, making OMEMO session **initiation** resistant to
"harvest now, decrypt later" attacks by quantum-capable adversaries, while
preserving full backwards-compatibility with the existing OMEMO2 message format.
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

This proto-XEP does not replace XEP-0384. It extends the OMEMO2 bundle
publication format and the session initialisation handshake. The encrypted
*message* format (the `<encrypted>` stanza) is unchanged. Clients that do not
understand the new `<kem-spk>` and `<kem-prekeys>` bundle elements fall back
gracefully: they are ignored.

Note that this document REQUIRES the OMEMO2 message format from XEP-0384 v0.9.x
(SCE-based). It is incompatible with legacy XEP-0384 v0.3 (the pre-SCE format):
v0.3 bundles do not carry KEM prekeys and cannot produce a Kyber signature, so
sessions cannot be established under PQXDH. PQXDH-mandating clients MUST refuse
to fall back to v0.3.

A client MAY additionally implement legacy OMEMO (v0.3) as a **separate,
independently-selectable** stack so that it can still reach peers on older
clients. In that case the two stacks MUST be kept strictly separate:

- The legacy stack and the OMEMO2/PQXDH stack use different PEP nodes, different
  device-id lists, and different session stores.
- PQXDH session establishment MUST NOT reuse an existing legacy Signal session
  for a peer device, even when one is present; it MUST always build a fresh
  OMEMO2 session from the peer's OMEMO2 bundle. Conversely, a legacy send MUST
  NOT consume an OMEMO2 session.
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
- MUST interoperate with existing OMEMO2 (v0.9.x) implementations (legacy peers
  receive bundles with new elements present; new elements are ignored)
- MUST NOT change the encrypted message (`<encrypted>`) stanza format
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
- SHOULD include a `<time>` element in the SCE envelope and reject envelopes
  whose stamp is outside a tolerated clock-skew window

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
4. Encrypted message format is unchanged.
5. The SCE envelope (XEP-0420) carries the body **and** all per-conversation
   metadata. The outer stanza is reduced to routing fields, the OMEMO fallback
   body, and a small set of server-readable hints (see §4.6).

### 4.2 PEP Nodes

| Purpose | PEP Node | Item ID |
|---------|----------|---------|
| Device list | `urn:xmpp:omemo:2:devices` | `current` |
| Bundle (per device) | `urn:xmpp:omemo:2:bundles` | Device ID (integer string) |

These are unchanged from XEP-0384 v0.9.1.

### 4.3 Extended Bundle Format

The bundle item (PEP node `urn:xmpp:omemo:2:bundles`, item id = device ID) is
extended with four new child elements inside `<bundle xmlns='urn:xmpp:omemo:2'>`:

```xml
<bundle xmlns='urn:xmpp:omemo:2'>

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

A client that does not implement PQXDH MUST ignore `<kem-spk>`, `<kem-spks>`,
and `<kem-prekeys>` elements when parsing a bundle. It will continue to initiate
sessions using only the classical OMEMO2 key material.

A PQXDH-capable client receiving a bundle without `<kem-spk>` MUST refuse to
initiate a PQXDH session with that peer. The client SHOULD log a warning and MAY
display a UI indicator that the session is not post-quantum secure. It MUST NOT
fall back to classical-only session initiation if the user's security policy
requires PQXDH.

### 4.4 Session Initiation (PQXDH Handshake)

#### 4.4.1 Sender Side (Alice)

1. Fetch Bob's bundle from `urn:xmpp:omemo:2:bundles`, item id = Bob's device ID.
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
8. Send the `<encrypted>` stanza (format unchanged from OMEMO2).

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
| `<kem-spk>` | Periodic (≥7 days, ≤90 days), or on demand | Generate new KEM keypair; sign with IK; publish updated bundle |
| `<kem-pk>` (one-time) | After each session initiation consuming that key | Delete from store; if stock falls below 50% of the published batch (50 of 100 in the reference implementation), generate and publish a refresh batch |

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
    <subject>…</subject>
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
    <x xmlns='urn:xmpp:webxdc:0'>…</x>
    <data xmlns='urn:xmpp:bob' cid='…' type='…'>…</data>
    <html xmlns='http://jabber.org/protocol/xhtml-im'>…</html>

    <!-- OGP / RDF link-preview description (see §4.6.5) -->
    <Description xmlns='http://www.w3.org/1999/02/22-rdf-syntax-ns#' …>…</Description>
  </content>

  <rpad>…1–200 random bytes (base64)…</rpad>
  <time stamp='2026-05-27T12:34:56Z'/>
  <from jid='alice@example.com'/>
  <to   jid='bob@example.com'/>   <!-- MUC: room bare JID -->
</envelope>
```

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

The sender SHOULD include a `<time stamp='…'/>` child in the envelope. The
stamp is an ISO-8601 UTC timestamp at second resolution. The receiver, when the
`<time>` element is present, MUST reject the envelope if the stamp differs from
local time by more than a tolerated skew window. The reference implementation
allows ±7 days to accommodate MAM catch-up and modest clock drift. A receiver
MAY accept envelopes lacking `<time>` for compatibility with older senders.

#### 4.6.3 `<rpad>` random padding

The sender MUST include `<rpad>` with 1–200 random bytes (base64-encoded). This
defeats length-based traffic analysis on a per-message basis. Receivers ignore
the content.

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
message for an envelope whose `<content>` carries no body and no file reference.

### 4.7 Outer-Stanza Minimisation

The outer stanza carries only what the server must read in cleartext:

- `from`, `to`, `type`, `id`, `origin-id` (routing)
- `<encrypted xmlns='urn:xmpp:omemo:2'>` (header + payload)
- `<encryption name='OMEMO2' namespace='urn:xmpp:omemo:2'>` (EME hint)
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

The identity transcript binds the post-quantum identity to the classical identity
and the EC signed pre-key. It is the concatenation, in order:

```
"monocles:omemo2:pq-bundle:v1"
  || u32_be(len(IK))        || IK            (classical identity public key)
  || u32_be(len(PQ-IK))     || PQ-IK         (ML-DSA-87 public key)
  || u32_be(signedPreKeyId)
  || u32_be(len(SPK))       || SPK           (EC signed pre-key public key)
```

`IK` and `SPK` are the libsignal public-key serialization (the 33-byte type-prefixed
form: a `0x05` DJB-type byte followed by the 32-byte Curve25519/Ed25519 key). `PQ-IK`
is the raw ML-DSA-87 verification key (2592 bytes). `signedPreKeyId` is the `<spk>`
`id`. All multi-byte integers are big-endian. The signature itself is produced with
ML-DSA-87 under the FIPS-204 signing context string `"monocles:omemo2:pqid:v1"`.

The one-time EC and KEM pre-keys are NOT in the transcript: a served bundle carries
only the per-recipient selection, not the whole published set, and those keys are
already bootstrapped from the (now hybrid-authenticated) signed pre-key and identity
via their Ed25519 signatures and the PQXDH agreement. Signing the long-lived
identity binding is sufficient — an attacker who cannot forge `<pq-sig>` can neither
substitute the classical identity nor the signed pre-key under a pinned PQ identity.

#### 4.9.2 Receiver processing

On receiving a bundle the initiator MUST, in addition to the §4.4.1 checks:

1. Verify `<pq-sig>` against `<pq-ik>` over the recomputed transcript. Any failure
   MUST abort session establishment. (In the reference implementation this check
   runs inside `SessionBuilder.process()`, so it cannot be bypassed at the
   application layer.)
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

The `<time>` binding (§4.6.2) detects long-tail replays that survive the
Double Ratchet's session-bound replay protection. Combined with the
last-resort-prekey replay tracker (§6.4) and the one-time-prekey deletion
(§4.5.1), this gives three independent replay-protection layers for the
session-initiation step.

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
           targetNamespace='urn:xmpp:omemo:2'
           xmlns='urn:xmpp:omemo:2'
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
    <publish node='urn:xmpp:omemo:2:bundles'>
      <item id='12345'>
        <bundle xmlns='urn:xmpp:omemo:2'>

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

This document proposes no new XML namespaces. The `<kem-spk>`, `<kem-spks>`,
`<kem-prekeys>`, and `<kem-pk>` elements are added to the existing
`urn:xmpp:omemo:2` namespace defined in XEP-0384.

If the XSF adopts this extension as a revision to XEP-0384, the version of XEP-0384
that includes these elements should be noted in the namespace (e.g.,
`urn:xmpp:omemo:2` with a version attribute in the `<bundle>` element).

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

3. **Feature advertisement**: There is no defined service-discovery feature
   string for PQXDH, SPQR (§4.8), or the hybrid identity (§4.9) capability. Because
   the reference libsignal makes SPQR mandatory and this profile makes the hybrid
   identity mandatory, a peer cannot currently tell ahead of time whether a target
   device speaks them, and a mismatch surfaces only as a failed session build. A
   future revision could define disco#info features (e.g. `urn:xmpp:omemo:2:pqxdh`,
   `urn:xmpp:omemo:2:spqr`, `urn:xmpp:omemo:2:pq-identity`) so a client can detect
   capability before attempting a session and show a clearer error.

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

5. **Skew window for `<time>`**: §4.6.2 sets the reference window at ±7 days.
   This trade-off favours MAM catch-up over tight replay protection. A future
   revision could define server-assisted clock alignment to allow tighter
   windows.

---

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
- [XEP-0420] Stanza Content Encryption (SCE), https://xmpp.org/extensions/xep-0420.html
- [libsignal] Signal's libsignal library, https://github.com/signalapp/libsignal
- [libsignal-monocles] monocles fork of libsignal (PQ OMEMO 2) used by the
  monocles chat Android and desktop clients, https://codeberg.org/monocles/pq-omemo-2
- [X3DH] Signal Foundation, "The X3DH Key Agreement Protocol",
  https://signal.org/docs/specifications/x3dh/ (2016)
- [DR] Signal Foundation, "The Double Ratchet Algorithm",
  https://signal.org/docs/specifications/doubleratchet/ (2016)
