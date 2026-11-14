<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# CamVault

**CamVault** is an Android app for **monitoring IP security cameras**. It discovers cameras on your network (ONVIF), plays live RTSP streams, records clips, stores them on-device, and syncs published to a MEGA cloud account — all with credentials encrypted at rest.

> Built with Kotlin + Jetpack Compose, Room, Media3, and MEGA SDK protocols.
> View the source project in AI Studio: https://ai.studio/apps/75c6e7f8-17aa-41c7-ac94-de5171fb2872

---

## What the project does

| Area | What it provides |
|---|---|
| **Live monitoring** | Browse discovered cameras and watch live RTSP streams (native player with MP4 fallback). |
| **Recording** | Record RTSP streams to MP4 (real RTP/H.264/HEVC/AAC depacketization). |
| **ONVIF discovery** | Broadcast discovery + SOAP camera management on your LAN. |
| **Cloud sync** | Upload recordings to your own MEGA account; schedule background sync. |
| **Security** | Credentials encrypted with AES-256-GCM backed by `AndroidKeyStore`; backups and device transfer disabled so camera passwords never leave the device. |

---

## How the project is organized

```
app/src/main/java/com/example/
├── MainActivity.kt              # Entry point, host Compose UI
├── CamVaultApplication.kt       # Application class (DB + repository wiring)
├── data/                        # Room database (entities, DAOs, repository)
├── media/                       # RTSP live player, MP4 fallback, stream recorder, probe
├── network/
│   ├── onvif/                  # ONVIF discovery + SOAP client
│   └── cloud/                  # MEGA cloud upload, scheduled sync, background worker
├── security/                    # CredentialCipher (encryption) + SecurityGuards
└── ui/
    ├── navigation/              # Routes (Monitor, Recordings, CloudStorage, CameraPlayer, AddEditCamera)
    ├── screens/                 # Compose screens (Monitor, CameraPlayer, AddEditCamera, Recordings, CloudStorage)
    └── theme/                   # Material 3 theme
```

**Screens** (bottom navigation):
- **Monitor** — your cameras; tap to view live, record, or edit.
- **Add / Edit Camera** — add a camera manually (name, RTSP URL, credentials) or pick a discovered one.
- **Camera Player** — live view with record controls.
- **Recordings** — list of saved clips; play or mark for cloud upload.
- **Cloud Storage** — connect a MEGA account, manage sync / uploads.

**How data flows:**

```
IP camera ──RTSP/ONVIF──► media/ (player/recorder) ──► Room database (data/)
                                                          │
                                  security/ (CredentialCipher)
                                                          │
                                     network/cloud/ ──► MEGA upload & sync
```

---

## Getting started (for developers)

**Prerequisites:** [Android Studio](https://developer.android.com/studio) (the app targets Android with Kotlin 2.x + Compose).

1. Open Android Studio.
2. Select **Open** and choose this directory. Allow Android Studio to fix any import incompatibilities.
3. Create a file named `.env` in the project root and set `GEMINI_API_KEY` to your Gemini API key (see `.env.example` for the shape). This enables the AI Studio–generated features.
4. **Signing (IMPORTANT):** the app's `app/build.gradle.kts` references a `debugConfig` signing config. On your **first** local debug run you may need to either create a `debug.keystore` or temporarily adjust the signing block — see the comments inside `app/build.gradle.kts`.
5. Run the app on an emulator or physical device.

> If you published the app through AI Studio and use the same key in Google Play, [request an upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) before releasing.

---

## Using the app (end user)

### 1. Add your cameras
- Open **Monitor** → **Add camera**.
- Either let the app **discover** ONVIF cameras on your network, or enter details manually:
  - **Name** — a friendly label.
  - **RTSP URL** — e.g. `rtsp://192.168.1.20:554/stream1`.
  - **Username / Password** — camera credentials (stored encrypted).

### 2. Watch and record live
- Tap a camera on **Monitor** to open the **Camera Player** — live video starts automatically.
- Use **Record** to capture a clip. Recordings are stored on-device as MP4 files.

### 3. Manage recordings
- Open **Recordings** to play, delete, or flag recordings for cloud upload.

### 4. Connect MEGA and sync
- Open **Cloud Storage** and sign in to your MEGA account.
- Choose what to sync; the background worker uploads recordings and can run on a schedule.

### 5. Security notes
- Camera credentials are encrypted with AES-256-GCM stored in `AndroidKeyStore`.
- `allowBackup` is disabled, so credentials are **not** copied to cloud backups or transferred to new devices.
- **Cleartext/HTTP is denied by default** (see `network_security_config.xml`). Only loopback and explicitly allow-listed hosts may use plain HTTP; use `rtsp://`/HTTPS on your LAN where your cameras support it.

---

## Building & testing

- The project uses the Gradle version catalog (`gradle/libs.versions.toml`).
- Unit + instrumentation tests live in `app/src/test/` and `app/src/androidTest/`.
- Generate a debug build in Android Studio (**Build ▸ Make Project**) or via Gradle.

---

## Security before pushing to GitHub

This repo is intended to be **public-safe**. Before you push, confirm:

- [ ] No real keys in `.env`, `.env.example`, or code — `GEMINI_API_KEY` is a placeholder.
- [ ] `local.properties`, `debug.keystore`, and build folders are gitignored (they are).
- [ ] `google-services.json` is not committed (currently absent — if Firebase is used, keep it out or treat as a secret).
- [ ] You've reviewed `network_security_config.xml` and the signing config for your use case.
