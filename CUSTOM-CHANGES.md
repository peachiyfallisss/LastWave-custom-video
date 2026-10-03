# LastWave custom video prototype

Prepared from Clash-Projects/LastWave-Native commit `97c68d5ace29da2363023e3f3d7723859a7061a0`.

This is modified source, not a verified APK. No upstream repository or account was changed.

## Changes

- The shared addon resolver rejects incompatible recording variants before choosing quality. This applies to streaming and downloads. It recognizes live, acoustic, instrumental, cover, remix, demo, clean, remaster, slowed, sped-up and other edition markers, including selected Japanese/Chinese/Portuguese labels. Album labels help identify live tracks with ordinary song titles. If it cannot match safely, the existing YouTube fallback remains available. Incomplete or incorrect metadata can still prevent an exact match; this is not audio fingerprinting.
- The full player has Audio, Video, and Choose video controls. Video plays its own soundtrack, as requested. Switching back resolves the normal audio source and seeks to the same elapsed position, clamped if the new source is shorter. Crossfade and background audio upgrades are suppressed while video is active. Pause/seek/notification controls use the same ExoPlayer.
- Per-song video choices persist. Choose a local video through Android's document picker, or paste a YouTube, Bilibili, or direct HTTPS MP4/WebM/HLS/DASH link. Local selections retain read permission. Stored links are stable page URLs; signed streams are resolved on demand.
- For YouTube without a custom source, Video uses the selected track's exact YouTube ID. If that ID has only a static album cover, select the music video's link yourself. No speculative cross-recording video search is performed.
- Separate video/audio streams are merged, using the selected video's soundtrack. Bilibili support covers public BV/av links, selected `p=` parts, and b23.tv short links. Sign-in-only, restricted, and older multi-part FLV streams may fail; the player displays an error. Bilibili API behavior and playback have not been verified on a device in this environment.
- Canonical song metadata and original YouTube ID survive source changes, including content:// device files. The existing YouTube Music history feature continues to count actual listening with its normal connected-account setting and threshold. A source-switch marker prevents a temporary playhead reset from starting a duplicate history session. Original song duration remains available for history matching when a video includes an intro/outro.
- YouTube controls badge eligibility; successful history submission does not guarantee badges. No silent parallel playback or artificial extra listens were added.
- Debug builds use `com.lastwave.app.custom`, so they can be installed alongside the original app. Sign in and configure the custom app separately. Its data is separate from the original app.

## Validation completed here

- 27 Java recording-identity regression scenarios passed with the actual Java implementation, including live vs studio, album-only live labels, Unicode markers, display-only video labels, and ordinary titles such as Live Forever.
- New Kotlin source files passed a tree-sitter syntax check. Modified large files were compared with the upstream baseline; the grammar's existing unsupported constructs remain, with no new reported error spans. This is not Kotlin type checking.
- `git diff --check` passed. The source patch was checked against the exact upstream commit.
- Android tests were added for canonical identity across local/Bilibili video attachments and restoration of the original audio source. They could not be run here.
- `./gradlew testDebugUnitTest assembleDebug` stopped while downloading Gradle 9.3.1: network unreachable. No Android compilation, APK, emulator test, real YouTube/Bilibili playback test, or badge validation was completed.

## Build on a computer

1. Extract this archive. Open the `LastWave-native` folder in Android Studio.
2. Install JDK 17, Android SDK platform 37.0 (package `platforms;android-37.0`), platform 36 for the USB audio module, and CMake 3.22.1 when requested. The app explicitly sets `compileSdkMinor = 0` to select the 37.0 platform. Allow Gradle to download the project's dependencies. The public upstream build generates an empty native secrets header when build secrets are absent; addon access still depends on the configured provider's authentication behavior.
3. In that folder, run:

```sh
bash tools/test-recording-identity.sh
bash gradlew testDebugUnitTest assembleDebug
```

On Windows, run `gradlew.bat testDebugUnitTest assembleDebug` from a terminal in the folder.

4. The output should be `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone and install it. This source has not yet been compiled; address any compiler/dependency errors before installing.

## Build using GitHub Actions

For a repository you own containing this source, open Actions → Build custom LastWave video APK → Run workflow. The included workflow only builds/tests and uploads the APK as an artifact; it does not publish a release. Open the completed run and download `LastWave-custom-video-debug`, then extract the APK. Creating/uploading a public fork shares its code publicly; no fork or upload was performed here.

## Device checks before relying on this build

- Live song with only a studio addon result: streaming and downloading must keep the original YouTube recording.
- Same live recording in the addon: confirm the selected edition and duration.
- Switch Audio → Video → Audio while playing and while paused; check playhead, pause, quality label, lyrics timing, and lock-screen controls. Intro/outro timing differences can require a manual seek.
- Choose a local file, restart the app, and reopen it. Remove the video and confirm normal audio playback.
- Test YouTube, a public Bilibili link, a `p=2` Bilibili link, a short link, a failed link, repeat-one, repeat-all, shuffle, and skipping while a source is opening.
- Enable history sync with your connected YouTube account, listen past the normal threshold, and check that the original song appears once. Check actual badges separately.
