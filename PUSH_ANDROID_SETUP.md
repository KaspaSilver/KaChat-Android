# Android native push (FCM) — app setup

This branch adds **native push notifications** via Firebase Cloud Messaging (FCM). When the app is
backgrounded or killed, the KaChat indexer delivers pushes (KaPosts pings, broadcast channels, 1:1
DMs) through FCM instead of relying on the in-app 60s poller.

Push stays **inactive until you add `app/google-services.json`** — the app still builds and runs
(poller-only) without it, thanks to the conditional `google-services` plugin apply in
`app/build.gradle.kts`.

## 1. One-time Firebase setup

1. In the [Firebase console](https://console.firebase.google.com), create/open your project and
   **add an Android app** with package name **`com.kachat.app`**.
   - For debug builds, also add **`com.kachat.app.debug`** (the debug `applicationIdSuffix`).
2. Download **`google-services.json`** and drop it at **`app/google-services.json`**.
   It is gitignored (per-operator config) — every developer/build machine adds their own.
3. The **server side** (Firebase project id + service-account key) is set up separately — see
   `docker/kachat/PUSH_ANDROID.md` in the `kachat-indexer` repo.

## 2. Build

```bash
./gradlew :app:assembleDebug
```

With `google-services.json` present, the `com.google.gms.google-services` plugin activates and
`FirebaseApp` auto-initializes. Without it, the build still succeeds and FCM is simply off.

## 3. How it works

- **`KaChatFirebaseMessagingService`** (registered in the manifest) receives **data-only** FCM
  messages and renders them via the existing `NotificationHelper` (DM / KaPosts / broadcast /
  group channels). This mirrors iOS's Notification Service Extension.
- **`PushRegistrationManager`** registers this device's FCM token with the indexer's
  `/v1/push/register`, authenticated by a **BIP-340 Schnorr signature** over the canonical preimage
  (the exact `kasia-push-auth:v1` / LegacyV1 shape the server verifies). It runs on wallet
  unlock / account switch (from `WalletViewModel`) and whenever FCM rotates the token
  (`onNewToken`).
- The push host is `AppSettingsRepository.kapostIndexerUrl` (default `https://kachat.duckdns.org`),
  reached via a new `NetworkService.pushApi` Retrofit client.
- **`POST_NOTIFICATIONS`** is requested at runtime in `MainActivity` (required on Android 13+).

The registration currently includes: your active DM contacts (`watched_addresses`), notify-enabled
broadcast channels, your KaPosts pubkey, your primary address, and — when the device has group
memberships — blinded group ids (`watched_group_ids`, switching the auth preimage to the
TransitionalGroups shape). DM pushes carry an `enc_payload` that is decrypted inline for the real
message preview; group pushes trigger a local group sync that decrypts and posts the precise
banner (see `KaChatFirebaseMessagingService`).

## 4. Test

1. Install, unlock a wallet, accept the notification prompt.
2. Confirm registration in the server log (`docker logs kachat-app` → a `[Push] register` line).
3. From another device: reply to one of your KaPosts, post to a bell-enabled broadcast channel, or
   send your wallet a DM. Background the app — the notification should arrive.

Troubleshooting: if nothing arrives, check `adb logcat -s PushRegistration KaChatFCM` for the
registration result and incoming messages, and verify `https://kachat.duckdns.org/v1/push/challenge`
is reachable from the phone's network.
