# LastWave custom video prototype

Prepared from Clash-Projects/LastWave-Native commit `97c68d5ace29da2363023e3f3d7723859a7061a0`.

This is modified source for a custom debug APK. No upstream repository or account was changed.

## Compilation completed on October 3, 2026

- Uploaded to the private repository `peachiyfallisss/LastWave-custom-video`.
- [GitHub Actions build](https://github.com/peachiyfallisss/LastWave-custom-video/actions/runs/37136012271) succeeded for source commit `4ff9a4a17c5b2d3bee78e434d8d990ce6b1b89a6`.
- `testDebugUnitTest assembleDebug` passed: 233 unit tests, zero failures, zero skipped. The standalone recording-identity check also passed all 27 scenarios.
- Verified the APK's v2 signature and package `com.lastwave.app.custom`, version `4.2.2-custom-video`, minimum Android API 29. Native libraries include arm64-v8a, armeabi-v7a, x86, and x86_64.
- Fixed the obsolete Android SDK setup action, explicitly selected SDK platform 37.0 with `compileSdkMinor = 0`, and supplied the missing `allowLossless` argument when restoring audio playback, honoring the existing lossless bypass setting.
- [Private release downloads](https://github.com/peachiyfallisss/LastWave-custom-video/releases/tag/v4.2.2-custom-video) include the APK, its SHA-256 checksum, and the exact built source archive.
- SHA-256: `ce728f8aa26e54716a19ecd222ce40f0c97ae5fffa2ab0a0ea899e7a4164782c`.
- This compilation does not validate real-device installation, YouTube/Bilibili playback, or badge behavior. No upstream addon credentials were included.

## Fullscreen and recording-preservation update

- A fullscreen button opens video in landscape with hidden system bars, play/pause, seeking, and Back/exit controls. It shares the existing player and playhead.
- Downloads with a selected YouTube ID use that exact recording, including playlist bulk downloads. Failed extraction reports an error instead of choosing another upload. These downloads use the original YouTube audio quality.
- Playlist bulk downloads now pass each track’s video ID and verify the source of existing files before skipping them. Unverified old downloads can be downloaded again; existing files are not silently deleted.
- “Make available locally” copies the playlist into LastWave. Its original URLs survive storage and determine playback; locally imported YouTube tracks preserve the original recording instead of substituting a provider result. Different known uploads remain separate even if the title/artist match.
- Live-marker recognition now also covers a bare LIVE suffix, LIVE prefix, 【LIVE】, and Japanese live-footage labels.
- 31 recording-identity, 17 exact-recording-policy, and 11 playback-cache regression checks passed locally. Android compilation, fullscreen on a phone, and these exact song downloads require the new build/device check.

## Previous player update

The updates below add clearer controls and background video lookup. The earlier APK and release linked above contain the previous controls. This revision must complete a new Android build before its APK is ready.

## Changes

- The shared addon resolver rejects incompatible recording variants before choosing quality. This applies to streaming and downloads. It recognizes live, acoustic, instrumental, cover, remix, demo, clean, remaster, slowed, sped-up and other edition markers, including selected Japanese/Chinese/Portuguese labels. Album labels help identify live tracks with ordinary song titles. If it cannot match safely, the existing YouTube fallback remains available. Incomplete or incorrect metadata can still prevent an exact match; this is not audio fingerprinting.
- The full player has a high-contrast Audio/Video pill over an opaque dark background. The active mode is highlighted without a checkmark. Choose video is available only inside the existing three-dot track menu; there is no inline source-selection button. Video plays its own soundtrack, as requested. Switching back resolves the normal audio source and seeks to the same elapsed position, clamped if the new source is shorter. Crossfade and background audio upgrades are suppressed while video is active. Pause/seek/notification controls use the same ExoPlayer.
- Switching retains the original audio stream and each selected video stream in a bounded, expiry-aware cache. Returning to a cached source avoids another addon search or video extraction. Failed streams are invalidated, changed video links use separate keys, and signed URLs are refreshed before expiry. The current source keeps playing during resolution, a spinner appears beside the requested mode, and the artwork/video changes fade over 180 ms. When a song starts playing, its selected video is resolved in the background and cached. A Video tap while lookup is still running shares that request; moving to another song, changing the source, stopping, or casting cancels obsolete lookups. Background lookup failures leave the audio playing and a later Video tap can retry. This resolves stream URLs in advance, rather than downloading or buffering the whole video. Buffering and taps before lookup finishes still depend on the provider/network; no device timing measurements were possible here.
- Per-song video choices persist. Choose a local video through Android's document picker, or paste a YouTube, Bilibili, or direct HTTPS MP4/WebM/HLS/DASH link. Local selections retain read permission. Stored links are stable page URLs; signed streams are resolved when playback starts and refreshed when needed.
- For YouTube without a custom source, Video uses the selected track's exact YouTube ID. If that ID has only a static album cover, select the music video's link yourself. No speculative cross-recording video search is performed.
- Separate video/audio streams are merged, using the selected video's soundtrack. Bilibili support covers public BV/av links, selected `p=` parts, and b23.tv short links. Sign-in-only, restricted, and older multi-part FLV streams may fail; the player displays an error. Bilibili API behavior and playback have not been verified on a device in this environment.
- Canonical song metadata and original YouTube ID survive source changes, including content:// device files. The existing YouTube Music history feature continues to count actual listening with its normal connected-account setting and threshold. A source-switch marker prevents a temporary playhead reset from starting a duplicate history session. Original song duration remains available for history matching when a video includes an intro/outro.
- YouTube controls badge eligibility; successful history submission does not guarantee badges. No silent parallel playback or artificial extra listens were added.
- Debug builds use `com.lastwave.app.custom`, so they can be installed alongside the original app. Sign in and configure the custom app separately. Its data is separate from the original app.

## Validation for the latest update

- 27 recording-identity and 11 playback-mode cache regression checks passed locally. The cache checks cover audio/video separation, changed source identity, expiry, eviction, invalidation, and local files.
- Kotlin syntax checks found no new reported error spans compared with the baseline; these are not Kotlin type checks.
- `git diff --check` passed.
- The prior Android build passed 233 unit tests, as recorded above. The updated Android build runs automatically on this repository’s main branch. Device playback and switching latency still require testing on a phone.

## Build on a computer

1. Extract this archive. Open the `LastWave-native` folder in Android Studio.
2. Install JDK 17, Android SDK platform 37.0 (package `platforms;android-37.0`), platform 36 for the USB audio module, and CMake 3.22.1 when requested. The app explicitly sets `compileSdkMinor = 0` to select the 37.0 platform. Allow Gradle to download the project's dependencies. The public upstream build generates an empty native secrets header when build secrets are absent; addon access still depends on the configured provider's authentication behavior.
3. In that folder, run:

```sh
bash tools/test-recording-identity.sh
bash tools/test-playback-mode-cache.sh
bash gradlew testDebugUnitTest assembleDebug
```

On Windows, run `gradlew.bat testDebugUnitTest assembleDebug` from a terminal in the folder.

4. The output should be `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone and install it. The source commit listed above was compiled successfully; address any errors introduced by later changes before installing.

## Build using GitHub Actions

For a repository you own containing this source, open Actions → Build custom LastWave video APK → Run workflow. The included workflow only builds/tests and uploads the APK as an artifact; it does not publish a release. Open the completed run and download `LastWave-custom-video-debug`, then extract the APK. This repository remains private.

## Device checks before relying on this build

- Live song with only a studio addon result: streaming and downloading must keep the original YouTube recording.
- Same live recording in the addon: confirm the selected edition and duration.
- Check that Audio/Video remains readable over bright and dark artwork, selection has no checkmark, and Choose video appears only in the three-dot menu.
- Start a song in Audio, allow the background lookup to finish, then tap Video. Also tap Video immediately to check it shares the pending lookup. Skip songs during lookup, change/remove video sources, stop playback, and test failed/expired links. Confirm audio continues when background lookup fails.
- Switch Audio → Video → Audio repeatedly and compare cached switches with the first load.
- Switch Audio → Video → Audio while playing and while paused; check playhead, pause, quality label, lyrics timing, and lock-screen controls. Intro/outro timing differences can require a manual seek.
- Choose a local file, restart the app, and reopen it. Remove the video and confirm normal audio playback.
- Test YouTube, a public Bilibili link, a `p=2` Bilibili link, a short link, a failed link, repeat-one, repeat-all, shuffle, and skipping while a source is opening.
- Enable history sync with your connected YouTube account, listen past the normal threshold, and check that the original song appears once. Check actual badges separately.


## 4.2.2-custom-video.6

- Persist qualified offline listens and automatically submit them through network-constrained WorkManager jobs after reconnecting, including after app restarts. Pending count appears in YouTube history settings. Original listening timestamps are kept locally; YouTube's history endpoint accepts no original timestamp, so late entries can appear at sync time. Badge credit remains determined by YouTube.
- Clear queued listens on disconnect, a new login, channel switch, or disabling history sync. Cookie/display refresh within the same session preserves the queue. Claim each listen before submission so an uncertain network outcome is not replayed.
- Add Audio only / Audio + video choices to song downloads and both playlist bulk-download menus. Save the currently selected rendition with its own adaptive soundtrack; use local files in video mode, including from Downloads. Add video to an existing verified audio download without downloading its audio again. A video failure leaves completed audio intact.
- Progressive YouTube/Bilibili streams, direct MP4/WebM links, and device video files can be saved. Custom HLS/DASH manifest links remain playback-only and report a clear download error.
- Start interactive playback from the first usable YouTube/lossless stream instead of holding ready YouTube audio for the lossless lookup. Continue eligible quality upgrades in the background. Race the bounded direct YouTube and InnerTubeX extraction methods, and stop the direct-path wait as soon as all clients fail. Reduce initial playback buffering from 1.5s to 0.5s; keep rebuffer protection unchanged.
- Add persistence/account-isolation tests, exact video/soundtrack and partial-transfer tests, and controlled-time startup race tests. Android playback timing and offline behavior still require verification on a real device.
