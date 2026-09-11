# Android push: notifications only arrive when the app is reopened

**For:** whoever maintains the KaChat indexer / push sender at `kachat.duckdns.org`.

**Reported by users:** with the app fully closed, nothing arrives. Open the app and the
notifications all appear at once. On iOS the same messages arrive normally.

That "all at once on reopen" shape is the important detail. The pushes are being *held*, not lost —
something is queuing them until the app comes to the foreground. Below is what to check, in the
order most likely to be the cause.

---

## First, narrow it down

The Android client has two background paths, and they fail differently. Ask an affected user:

> With the app fully closed, do **group** messages notify (possibly up to 15 minutes late), while
> **1:1** messages only appear when you reopen?

- **Groups arrive, 1:1 does not** → FCM delivery is the broken link. Groups have a 15-minute
  WorkManager fallback that does not use push at all; 1:1, broadcasts and KaPosts have **no**
  fallback and rely on FCM alone. Go to §1.
- **Nothing arrives at all until reopen, groups included** → likely registration, not sending.
  Go to §3 first.

The user can also read **Settings → Notifications → Push diagnostics** in the app, which shows the
last registration attempt, whether it succeeded, any server error, and whether an FCM token exists.
If that says the last register **succeeded**, the device is registered and the problem is on the
sending side.

---

## 1. FCM message priority — the most likely cause

KaChat sends **data-only** messages (no `notification` block) so the client can decrypt the body
locally. Data-only messages are exactly the ones Android defers: at **normal** priority they are
queued while the device is dozing and flushed when the app next comes to the foreground. That is
this bug, verbatim.

Every KaChat push must be sent at high priority. FCM HTTP v1:

```json
{
  "message": {
    "token": "<device_token>",
    "data": { "type": "contextual", "sender": "kaspa:...", "...": "..." },
    "android": {
      "priority": "high"
    },
    "apns": {
      "headers": {
        "apns-priority": "10",
        "apns-push-type": "alert"
      }
    }
  }
}
```

Legacy HTTP API (if still in use): `"priority": "high"` at the top level.

**Check:** find where the FCM request body is built and confirm `android.priority` is set. If the
field is absent, FCM defaults to `NORMAL` — and normal-priority data-only messages are not
guaranteed delivery while the device is idle at all.

> iOS is unaffected by this because APNs has no equivalent deferral for its priority 10 alerts,
> which is why the same messages arrive there. A platform split like that is itself evidence for
> this being the cause.

## 2. Other send-side settings worth confirming

- **`collapse_key` / `android.collapse_key`** — if every message shares one collapse key, FCM keeps
  only the most recent while the device is offline and silently drops the rest. Either omit it, or
  key it per conversation, never per app.
- **`ttl`** — the default is 4 weeks, which is fine. If it has been set low (minutes), messages
  expire before a dozing device wakes. If it has been set to `0`, FCM delivers only if the device is
  connected *right now* and discards otherwise.
- **Payload size** — FCM's limit is 4096 bytes for the whole message. KaChat's client expects
  `enc_payload` to be **absent** when a message is too large (photos, voice) and falls back to
  fetching the transaction itself, so oversize content should be sent without `enc_payload` rather
  than dropped entirely. Confirm the sender does that rather than skipping the push.
- **Per-device failures** — FCM returns `UNREGISTERED` / `NOT_FOUND` for stale tokens. If those are
  not being pruned, a user who reinstalled may have an old token receiving everything and the new
  one registered but never targeted. Check whether the send path records and acts on these.

## 3. Registration, if nothing arrives at all

The client registers against these routes on the same host that serves the indexer:

```
POST   /v1/push/challenge     -> { nonce, issued_at_ms, expires_at_ms }
POST   /v1/push/register      -> { status }
DELETE /v1/push/unregister    (with body) -> { status }
```

`/v1/push/register` currently answers `405` to a GET, which is correct and confirms the route
exists. Registration body:

```json
{
  "device_token": "<FCM token>",
  "platform": "android",
  "watched_addresses": ["kaspa:...", "..."],
  "watched_group_ids": ["<64-hex blinded group id>", "..."],
  "capabilities": ["..."],
  "primary_address": "kaspa:...",
  "aliases": ["..."],
  "watched_broadcast_channels": ["..."],
  "hidden_broadcast_senders": { "<channel>": ["kaspa:..."] },
  "kaposts_pubkey": "<hex>",
  "auth": {
    "auth_version": 1,
    "wallet_pubkey": "<hex>",
    "wallet_address": "kaspa:...",
    "nonce": "<from /challenge>",
    "timestamp_ms": 0,
    "expires_at_ms": 0,
    "signature": "<BIP-340 Schnorr over the canonical preimage>"
  }
}
```

Two auth-preimage shapes exist and the server picks by field presence:

- `watched_group_ids` **present** → `TransitionalGroups` preimage (adds the group-hash line, forces
  the `group_v1` capability, requires `primary_address == wallet_address`).
- `watched_group_ids` **absent** → `LegacyV1` (DM + broadcast only).

If signature verification is rejecting Android registrations while accepting iOS ones, that
mismatch is the place to look — both clients sign the same canonical preimage, so a divergence is a
server-side branch, not a client difference.

**Worth checking in logs:** how many `platform: "android"` device tokens are currently registered,
and whether the count is plausible against your user base. A near-zero count with healthy iOS
numbers points at registration; a healthy count points at sending (§1).

## 4. What the client expects in `data`

Message types it acts on. Anything else is logged and ignored, so a typo in `type` means silence:

| `type` | Required keys | Notes |
|---|---|---|
| `contextual` | `sender`, `tx_id` | `enc_payload` optional; absent means the client fetches and decrypts the tx itself |
| `payment` | `sender`, `tx_id` | `amount` optional |
| `handshake` | `sender`, `tx_id` | |
| `group_message` | `blinded_group_id`, `tx_id` | |
| `group_control` | `blinded_group_id` or `tx_id` | |
| `broadcast` | `channel`, `tx_id` | |
| `kaposts` | `tx_id`, `kaposts_kind` | plus one of `post_id` / `postId` / `content_id`, absent for a follow |

Common to all: `type`, `title`, `body`, `tx_id`, `timestamp`, `daa_score`.

`tx_id` is used to deduplicate against the same message arriving through the app's own sync, so
sending it is not optional — without it a user gets two notifications for one message whenever the
app happens to be awake.

---

## Summary

Most likely: **`android.priority` is not set to `"high"`** on the FCM messages, so data-only pushes
are deferred while the device is idle and flushed on next app open. Confirm that first; it is one
field in the send path and it matches the reported behaviour exactly, including why iOS is fine.
