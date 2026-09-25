# Play Store submission — KaChat 5.1

Everything the Play Console asks for that is *not* answerable from the listing text, plus the
build-level checks that have to pass before an upload. Written for the operator doing the
submission; the companion doc for FCM is `PUSH_ANDROID_SETUP.md`.

> **Open item — Android App Links.** `https://kachat.app/.well-known/assetlinks.json` currently
> answers `[]`, because the links server's `ANDROID_SHA256` is unset, so the `autoVerify` intent
> filter on `kachat.app` does not verify and a `https://kachat.app/post/<txid>` link opens the
> browser fallback page instead of the app. Nothing is broken by it — that page carries the
> download buttons, and `kachat://` links still open the app directly — and it cannot be finished
> before the first upload anyway, since the file has to carry the SHA-256 of the **Play
> app-signing** certificate, which Play generates rather than the upload key. Right after the
> first release: copy the fingerprint from Play Console > Setup > App integrity > App signing key
> certificate into the server's `ANDROID_SHA256`, then re-check with
> `curl -s https://kachat.app/.well-known/assetlinks.json`.

## 1. The artifact

```bash
./gradlew bundlePlayRelease
```

`app/build/outputs/bundle/playRelease/app-play-release.aab`

- Signed with the **upload** key (`uploadStoreFile` in `keystore.properties`, Play expects
  SHA-1 `CF:58:68:0B:…`), not the key the GitHub APK uses. The swap is per-variant, in
  `androidComponents.onVariants` — see `app/build.gradle.kts`.
- `versionCode` must exceed Play's high-water mark for `com.kachat.app`, which is permanent and
  never resets on a `versionName` change. 5.0 shipped as 69; the 5.1 betas have run past 92.
- The play flavour sets `KACHAT_IS_RELEASE = true`, so About and the crash/diagnostics reports
  read plain **"5.1"**. The github flavour keeps `false` and reads "5.1 (N)". Nothing to flip by
  hand at submission time — which is the point, since a forgotten flip ships a store build
  labelled like a beta.

  The trade: the same AAB goes to internal testing and to production, so a **Play beta** now also
  reads plain "5.1" instead of "5.1 (N)". Build identity on that track comes from the versionCode
  instead, which every crash and diagnostics report already prints next to the version. If build
  numbers on Play betas matter more, set the play flavour's `KACHAT_IS_RELEASE` to `"false"` and
  flip it by hand for the production upload, the way iOS's single flag works.
- All four ABIs stay in the bundle (only the github APK is arm64-only) — Play serves each device
  its own, so 32-bit and x86 devices can still install.

## 2. Pre-upload checks

| Check | Command | Requirement |
| --- | --- | --- |
| Unit tests | `./gradlew testGithubReleaseUnitTest` | all green |
| Release lint | `./gradlew lintPlayRelease` | no new Error-severity issues |
| 16 KB page size | see below | every 64-bit `.so` has `p_align >= 16384` |
| R8 mapping saved | `app/build/outputs/mapping/playRelease/mapping.txt` | upload with the bundle |

Last verified on versionCode 92: 358 unit tests green, lint clean against the baseline, bundle
signed with the upload key (SHA-1 `CF:58:68:0B:11:F0:C5:E9:BF:40:5B:07:85:5D:86:EC:3B:A0:31:02`),
all four ABIs present, 39 MB.

Google requires 16 KB page-size support for anything targeting Android 15+. Both halves have to
hold — the ELF segment alignment of each prebuilt library, and the zip alignment inside the
bundle. It applies to the **64-bit** ABIs only, which is where it currently stands: `arm64-v8a`
and `x86_64` are fully 16 KB-aligned, while WebRTC's and ML Kit's `armeabi-v7a`/`x86` libraries
are still 4 KB-aligned. That is fine — no 16 KB device is 32-bit — but it means the check has to
be read per ABI, not as one pass/fail. To re-verify after a dependency bump (WebRTC is the one
that matters, at ~12 MB per architecture):

```bash
rm -rf /tmp/soaudit && unzip -o -q \
  app/build/outputs/bundle/playRelease/app-play-release.aab 'base/lib/*' -d /tmp/soaudit
python3 -c "
import glob, struct
for p in sorted(glob.glob('/tmp/soaudit/base/lib/*/*.so')):
    d = open(p, 'rb').read()
    if d[4] != 2:            # 32-bit: exempt, 16 KB devices are all 64-bit
        continue
    off = struct.unpack_from('<Q', d, 0x20)[0]
    size, num = struct.unpack_from('<H', d, 0x36)[0], struct.unpack_from('<H', d, 0x38)[0]
    a = [struct.unpack_from('<Q', d, off + i * size + 0x30)[0]
         for i in range(num) if struct.unpack_from('<I', d, off + i * size)[0] == 1]
    print('OK  ' if all(x >= 16384 for x in a) else 'FAIL', p, sorted(set(a)))
"
```

## 3. Console declarations

These are the ones that block a release if they are missing or wrong.

**App access** — no login is required to use the app; a wallet is created on device. No reviewer
credentials needed.

**Foreground service types** (mandatory since Aug 2024). `CallForegroundService` declares
`microphone|camera`. Justification: keeping a Nextcloud Talk voice/video call's microphone and
camera usable while the call is backgrounded — Android 14 cuts both off otherwise. Play asks for
a short screen recording showing the in-app path that starts it: place a call, background the
app, keep talking.

**No `USE_FULL_SCREEN_INTENT`.** It was removed deliberately after the 2026-09-22 policy notice:
Play grants it only to apps whose core purpose is calling or alarms, and KaChat is a chat app
that can also call. Incoming calls ring through a CallStyle notification instead
(`IncomingCallNotifier`). Do not re-add it.

**Financial features.** Declare **crypto exchanges and digital wallets** — the app holds Kaspa
keys on device and includes a ChangeNOW swap. Have the region/licensing answers ready; this is
the declaration most likely to hold up a first submission.

**Target audience: 18+.** Not negotiable alongside the crypto declaration — Play's Families
policy excludes crypto apps, so the app must not claim a child audience. Child Mode is a
*parental control inside an adult app* (it hides Swaps, KaPosts and Public Chats behind a
password); describe it that way if asked, never as a kids mode.

**Privacy policy URL.** Mandatory, and there is no in-app link to one today. It has to cover the
third-party endpoints in §4.

**Data deletion.** The app stores keys and history on device only; uninstalling removes them.
Account deletion does not apply (there is no account), but the Console still wants that stated.

## 4. Data Safety

No analytics or crash-reporting SDK is linked. Crash records are written and read locally
(`CrashRecorder`); nothing is uploaded. System contacts are read for matching and written for
KaChat's own callable rows, and never leave the device. Language identification for KaPosts runs
on device (ML Kit language-id, bundled).

| Data type | Collected | Shared | Purpose / endpoint |
| --- | --- | --- | --- |
| Financial info — other (Kaspa address) | Yes | Yes | Push routing (KaChat indexer), KNS lookups, and the swap destination sent to ChangeNOW. App functionality; required. |
| Messages — other in-app messages | Yes (ephemeral) | No | Public KaPost text goes to KaChat's translation service **only** when the reader taps Translate. The request carries no identifier of any kind; see `TRANSLATION_SERVICE.md`. |
| App activity — other | Yes (ephemeral) | No | Which addresses/rooms/groups to watch, sent to the indexer for push. Addresses are hashed in the auth preimage. |
| Device ID | Yes | No | FCM registration token, sent to the KaChat indexer's `/v1/push`, signed with the wallet key. |
| Photos | No | No | Images are attached from the device and sent in-chat; never uploaded to a KaChat service. |
| Audio | No | No | Voice messages and calls. Calls traverse the user's own Nextcloud Talk server. |
| Contacts | No | No | Read locally for matching only. |
| Location | No | No | No location permission. |

Third-party hosts contacted and what each sees: `api.coingecko.com`, `api.gateio.ws`,
`query1.finance.yahoo.com` (prices — no user data), `api.knsdomains.org` (domain/address
lookups), the KaChat/Kasia indexers (addresses, push token), `api.changenow.io` (swap amount and
destination address), the user's own Nextcloud server (backups, calls), and — on link previews —
whatever host a received link points at, which is how that host learns the reader's IP. That
last one is inherent to link previews and matches iOS.

## 5. Permissions

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Kaspa node gRPC, indexer, REST |
| `POST_NOTIFICATIONS` | Message and call notifications (runtime-requested) |
| `RECEIVE_BOOT_COMPLETED` | Re-arm the periodic group-sync worker after a reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets data-only encrypted DM/group push wake a force-closed app. See the risk note in §6. |
| `RECORD_AUDIO`, `CAMERA`, `MODIFY_AUDIO_SETTINGS` | Voice messages, photo sharing, voice/video calls |
| `FOREGROUND_SERVICE` + `_MICROPHONE` + `_CAMERA` | Backgrounded calls — see §3 |
| `READ_CONTACTS`, `WRITE_CONTACTS` | Match existing contacts; add KaChat call rows to a contact card |
| `WRITE_EXTERNAL_STORAGE` (maxSdk 28) | Saving a received photo on API ≤28 only |
| `VIBRATE`, `USE_BIOMETRIC` | Notification feedback; the seed-phrase / account-unlock prompt |

The manifest also declares `android.hardware.camera`, `camera.any`, `camera.autofocus` and
`android.hardware.microphone` as `required="false"`. Play otherwise infers them as *required* from
the CAMERA and RECORD_AUDIO permissions and hides the app from every device without that hardware
— most Chromebooks, camera-less tablets, TV and auto form factors — even though KaChat is a chat
app whose camera and microphone are optional. Do not drop these.

`usesCleartextTraffic="true"` is deliberate and documented in the manifest: Kaspa node gRPC
(protowire, ports 16110/16210) is plaintext by protocol and a `networkSecurityConfig` can only
scope a cleartext allowance by domain, which is useless for a pool of bare peer IPs. Every HTTP
surface in the app is https-only at the application layer, and the one user-editable server
field (Nextcloud) rejects `http://` in the UI and again in `NextcloudService.normalizeServer`.
Expect to explain this if a reviewer asks.

## 6. Risk notes

- **Battery-optimization dialog on first launch.** `MainActivity.maybeRequestBatteryExemption`
  fires the system exemption dialog once, as soon as notifications are granted, with no in-app
  explanation first. It is asked once ever and is the only way push can wake a force-closed app,
  but an unexplained system dialog at first launch is the pattern reviewers flag, and this app
  already took one policy notice. Consider gating it behind a short in-app rationale.
- **One iOS string nuance is not matched.** On a shared public-chat link, iOS says "Tap to *open*
  this KaChat public chat room." for a room you already have and "Tap to *join*…" for one you do
  not; Android always says "join". Closing it means threading the joined/curated channel list into
  `KaChatInternalLinkCard`, which is deliberately presentational with no ViewModel — a refactor not
  worth its regression risk during launch prep. Everything else in that card matches.
- **The translations need a pass, on two counts.** English now reads "Public Chats" everywhere,
  matching iOS, but the 18 localized `strings.xml` files still carry a translation of the old
  "Broadcasts" name. Separately, lint finds 97 strings missing their diacritics — 55 in
  `values-pt`, 28 in `values-es`, 14 in `values-it` ("Nao" for "Não", "camara" for "cámara",
  "dias" for "días", and so on). Neither blocks a launch, and English is unaffected, but a
  Portuguese or Spanish speaker sees an app that reads as machine-translated. Worth fixing before
  promoting the listing in those locales.
- **No app-specific baseline profile.** The bundle carries the AndroidX libraries' profiles only,
  so first-run and first-scroll of KaChat's own Compose code are interpreted. A
  `baselineProfile` module would measurably improve cold start; nothing blocks the launch.
- **Startup does two blocking reads before the first frame.** `KaChatApplication.onCreate` calls
  `CrashRecorder.noteProcessExits` (system exit history, and a file write when the last exit was
  abnormal) and `kaPostsScheduledStore.reloadIfNeeded()` (SharedPreferences + JSON) synchronously.
  Both are small and neither is a network call, but they are the only work between process start
  and Compose; worth a Macrobenchmark measurement rather than a guess if cold start ever looks slow.
- **30 `AutoboxingStateCreation` warnings** — `mutableStateOf(0)` where `mutableIntStateOf(0)` would
  avoid boxing on every write. Pure allocation churn in list-heavy screens; not a launch blocker.
- **Dependency updates are available** (24 `GradleDependency` + 19 `NewerVersionAvailable`
  warnings). Deliberately not taken here: shipping the store build on the exact versions the 5.1
  betas were tested against is worth more than the bumps. Revisit right after launch.
- **`fallbackToDestructiveMigration()`** is on the Room builder (`di/AppModule.kt`). An
  unhandled schema step wipes cached chat history rather than crashing. Deliberate, and the
  registered migrations are schema-tested, but every new release must keep its migration
  registered and green.

## 7. Security review, 2026-09-25

Checked and clean:

- **Secrets.** Nothing sensitive is tracked: `keystore.properties`, `*.jks/*.keystore`,
  `local.properties`, `google-services.json` and the whole `gift-server/` tree are gitignored, and
  a scan of `app/src/main` finds no hardcoded key, token or password literal. The one ChangeNOW key
  is read from `local.properties` at build time.
- **Key storage.** The seed and per-account material live in `EncryptedSharedPreferences`
  (`AES256_SIV` keys / `AES256_GCM` values) under a Keystore-backed `MasterKey`, with no plaintext
  fallback path. `android:allowBackup="false"`, so nothing goes to Android Auto Backup.
- **Screen capture.** `FLAG_SECURE` is set on the seed-phrase and private-key screens (onboarding
  and settings), with tap-to-reveal and a 7-second auto-hide.
- **Clipboard.** `ClipboardUtils` marks copied secrets with `ClipDescription.EXTRA_IS_SENSITIVE`
  so the OS does not preview them, and the private-key copy auto-wipes.
- **Logging.** No `Log.*` call carries a seed, mnemonic, private key or passphrase, and no
  `printStackTrace()` anywhere. R8 strips `Log.v`/`Log.d` from release builds outright
  (`-assumenosideeffects`), leaving only the info/warn/error lines that were written not to carry
  secrets.
- **Transport.** Every HTTP surface is https at the application layer; the two raw
  `HttpURLConnection` callers added for the portfolio charts (Gate.io, Yahoo) are https, on
  `Dispatchers.IO`, with connect/read timeouts, and fail closed. See §5 for why
  `usesCleartextTraffic` stays true.
- **Exported components.** Three are exported, each deliberately: `MainActivity` (launcher and
  deep links), `CallFromContactsActivity` (accepts only KaChat's own contact MIME types), and the
  two contacts account/sync services the platform requires to be exported to own contact rows.
  Nothing else is reachable from another app.
- **No third-party telemetry.** No analytics or crash SDK is linked; crash records stay on device.

Accepted, not fixed:

- **Link previews reveal the reader's IP.** A received link is fetched automatically when the
  bubble renders, so the host it points at learns the reader's IP and that they saw the message.
  The scheme is restricted to http/https but private/loopback addresses are not blocked. There is
  no channel back to the sender for anything but a public host they control, and iOS behaves the
  same way, so this matches rather than diverges. Inherent to link previews as a feature.
