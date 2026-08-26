# KaChat

KaChat is an encrypted, peer-to-peer messaging app built directly on the Kaspa blockchain. There
are no servers, no accounts, and no phone numbers. Your identity is your Kaspa wallet, and every
message is end-to-end encrypted and sent as a transaction on-chain. You can chat and send KAS
payments to anyone from the same app.

This is the Android version, a companion to [KaChat on iOS](https://github.com/vsmirn0v/KaChat).

## Features

- End-to-end encrypted 1:1 messaging, with no central server and nothing to trust but the blockchain
- Encrypted group chats
- Broadcast channels — public, unencrypted feeds anyone can follow or run
- KaPosts — an on-chain public posting feed with replies, reposts, bookmarks, and KAS tips
- Send and receive KAS payments right inside a chat
- Voice messages, photo messages, reactions, replies, and link previews
- Play chess inside a chat
- KNS domain names (send to a human-readable name instead of a raw address), including creating
  your own domain and profile from the app
- Cold storage: watch-only accounts with offline signing via animated QR codes (KSPT)
- Portfolio tracking and in-app swaps between KAS and other coins (via ChangeNOW)
- Multiple wallet accounts on one device, plus an optional Child Mode lock
- Optional, off-by-default encrypted backup of your chat history to your own Google Drive or
  Nextcloud server
- Optional push notifications for messages, groups, channels, and KaPosts (FCM; see
  `PUSH_ANDROID_SETUP.md`)
- QR code scanning for addresses and contacts
- Available in 19 languages

## Download

1. Go to the [Releases page](../../releases) and download the latest `.apk` file.
2. On your Android phone, open the downloaded file. If you're prompted to allow installing from
   this source, allow it. This is expected for an app installed outside the Play Store.
3. Open KaChat and either create a new wallet or import an existing one with your seed phrase.

Your seed phrase is the only way to recover your wallet, so write it down and keep it somewhere
safe. Nobody, including the developers, can recover it for you if it's lost.

## Building from source

```bash
git clone https://github.com/KaspaSilver/KaChatForAndroid.git
cd KaChatForAndroid
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 36). The resulting APK is at
`app/build/outputs/apk/debug/app-debug.apk`.

Optional pieces, both gitignored and absent by default:

- `app/google-services.json` — your own Firebase config; without it the app builds and runs with
  push notifications off (`PUSH_ANDROID_SETUP.md` walks through it).
- `keystore.properties` + your release keystore — only needed for signed release builds; debug
  builds work without them.

## Security notes

- Your message history is stored in the app's private storage, which no other app can read and
  which is protected at rest by Android's file-based encryption. Android's system backup is
  disabled for the app (`allowBackup="false"`), so chat history and keys are never copied into
  device backups.
- Optional cloud backups (Google Drive or Nextcloud) are end-to-end encrypted with a key derived
  from your wallet before they leave the device. The cloud provider only ever stores ciphertext.
- Group chat keys are kept in encrypted preferences whose master key lives in the Android
  Keystore, hardware-backed on devices that support it.
- Seed phrase and private key screens block screenshots and screen recording while they are
  visible.

## Support

Questions or issues: [kaspasilver@gmail.com](mailto:kaspasilver@gmail.com)
