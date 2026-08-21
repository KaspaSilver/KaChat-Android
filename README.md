# KaChat

KaChat is an encrypted, peer-to-peer messaging app built directly on the Kaspa blockchain. There
are no servers, no accounts, and no phone numbers. Your identity is your Kaspa wallet, and every
message is end-to-end encrypted and sent as a transaction on-chain. You can chat and send KAS
payments to anyone from the same app.

This is the Android version, a companion to [KaChat on iOS](https://github.com/vsmirn0v/KaChat).

## Features

<details>
<summary>Summary</summary>

- End-to-end encrypted messaging, with no central server and nothing to trust but the blockchain
- Send and receive KAS payments right inside a chat
- Voice messages
- KNS domain names (send to a human-readable name instead of a raw address)
- Multiple wallet accounts on one device
- Optional, off-by-default backup of your chat history to your own Google Drive
- QR code scanning for addresses and contacts

</details>

## Download

<details>
<summary>Summary</summary>

1. Go to the [Releases page](../../releases) and download the latest `.apk` file.
2. On your Android phone, open the downloaded file. If you're prompted to allow installing from
   this source, allow it. This is expected for an app installed outside the Play Store.
3. Open KaChat and either create a new wallet or import an existing one with your seed phrase.

Your seed phrase is the only way to recover your wallet, so write it down and keep it somewhere
safe. Nobody, including the developers, can recover it for you if it's lost.

</details>

## Building from source

<details>
<summary>Summary</summary>

```bash
git clone https://github.com/KaspaSilver/KaChatForAndroid.git
cd KaChatForAndroid
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 35). The resulting APK is at
`app/build/outputs/apk/debug/app-debug.apk`.

</details>

## Support

Questions or issues: [kaspasilver@gmail.com](mailto:kaspasilver@gmail.com)
