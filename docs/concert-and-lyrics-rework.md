# Concert, download, and lyrics rework

Development branch: `feat/custom-lyrics-translations`, draft PR #2. Android
compilation, 303 unit tests, APK assembly, and signature verification passed in
[the initial CI build](https://github.com/peachiyfallisss/LastWave-custom-video/actions/runs/40975825024).
On-device visual/behavior verification is still pending.

## Install-alongside preview

Pull-request builds use `-PreworkPreview=true`: package
`com.lastwave.app.custom.rework`, launcher label **LastWave Rework Preview**.
They do not replace the existing custom app, which may have a different debug
signing key. The preview has separate app-private data and requires its own
sign-in/settings; it does not migrate the old app's data. Keep the original
app installed. Select a separate download folder before testing download or
deletion actions, because public Music folders can be shared between apps.
Normal main-branch/manual builds retain `com.lastwave.app.custom`.

## UI

- **Playlists → Concert library** is a named entry rather than an unexplained
  icon. The library opens full-screen, with recording cards and an Add recording
  action. Device import and URL import are separate choices. Device filenames
  seed the recording title; a failed persistent permission grant is reported.
- A recording has an embedded video preview, transport/seek controls, its
  chronological setlist, and a Link a song action. Full show and Songs only are
  distinct modes. Each song can be previewed, edited, or removed.
- Song linking is a focused sheet: choose a song, then set a segment or a
  whole-video link. Start/end may be typed or marked from the playing video.
  Invalid and overlapping ranges cannot be saved. The sheet stays open on a
  save error, and repeated saves are guarded. Existing links remain editable.
- **Downloads → Download queue** is available independently of the
  Songs/Artists/Albums tabs. Active, Issues, and Finished are separate views.
  Waiting work is visibly queued; preparation is indeterminate rather than a
  fictional 0% transfer. Transfers show bytes and progress. Failed jobs can be
  retried individually or together; connection waits have a reconnect action.
  The queue/history view covers the current process session, not durable job
  resumption after process death.
- The lyrics editor separates **Lyrics**, **Timing**, and **Translation**.
  Reusing another version and Google API setup are expandable tools, not a wall
  of mandatory fields. Per-recording offsets, tap-to-sync lines/words, undo,
  plain lyrics, LRC, enhanced LRC, and line-by-line or pasted manual translations
  are supported. Unsaved lyric changes require confirmation before dismissal.

## Playback and attribution

Full concerts no longer need to be reopened as one clipped player item per
song to receive segment attribution. The original full-video playback state is
projected into a separate listening identity at the absolute source playhead:

- Segment start is inclusive and end is exclusive.
- Intro/applause/gaps have no linked-song identity.
- Two performances of the same song have distinct segment session keys.
- YouTube history and the app's Last.fm scrobbler use that projected identity
  and the segment-relative duration/position. Seeking is not credited as
  elapsed listening; pauses and buffering do not accrue scrobble time.
- Lyrics follow the projected song and segment-relative clock while the actual
  concert video and its ordinary seekbar stay continuous. Lyric seeks are
  translated back into the concert's absolute timeline.
- A whole-video song link is used only when no segment setlist is present.
- Explicit device/external concert sources open directly, without first finding
  a song-audio stream. A failed video is not replaced by a studio recording.
- Editing the currently playing setlist shows **Apply updated setlist**. This
  refreshes the playback snapshot at the current absolute position; the UI does
  not pretend newly saved mappings are already active before they are applied.
- No parallel muted YouTube playback is introduced. The existing connected
  account, opt-in, thresholds, and offline YouTube submission mechanisms remain
  in place. A submission does not establish remote history visibility or badge
  eligibility; those need verification with YouTube.

The song shown in the system/media notification remains the actual full concert
item; this change projects identity for LastWave's own attribution and lyrics.
External scrobblers reading that notification do not receive the setlist's
projected song metadata.

## Downloads

The download backend now has three bounded stages, without a UI redesign:

- **Preparation:** up to two preparers resolve upcoming recordings while media
  transfers run. Admission bounds pre-finish jobs to the selected transfer limit
  plus two lookahead jobs. Playlist preflight disk checks run in batches of four
  rather than one filesystem/DAO check per enqueue.
- **Transfers:** the existing 1–4-song setting is still honored (default two).
  Main song audio and optional video overlap. Adaptive video and its own
  soundtrack also transfer concurrently; studio audio is never substituted for a
  custom video's soundtrack. Validated byte ranges and exact-recording checks
  remain in place.
- **Finishing:** after media transfers settle, their network permit is released
  before remuxing, tagging, bounded lyrics/artwork waits, copying, and database
  publication. Two finishers and bounded upstream admission provide disk/memory
  backpressure rather than accumulating a playlist's temporary files.

Video pairs are staged privately and published with aliases only after audio is
saved. Failed video leaves the audio usable; retry preserves the exact original
request and reuses completed matching audio. Cancellation joins native HTTP
readers before removing staged files or returning a network permit. Cancelled
keys stay reserved during cleanup so immediate requeues cannot race old writes.

`LastWaveDownloadPipeline` debug logs report counter-only job IDs, stage timings,
active song transfers, network-idle gaps, and whole-batch elapsed time. They do
not include signed URLs, authentication headers, or song names. A batch is a
continuous period with pending jobs, not a persistent playlist identifier.

A deterministic eight-song virtual-clock regression, at the **same two-song
limit**, takes 8,000 ms under the old sequential stages and 4,400 ms with this
pipeline, with zero inter-transfer idle gaps. These are simulated workloads,
**not phone benchmarks or measured parity with YouTube Music**. Provider
bandwidth, extraction latency, device storage, and very slow finishing still
matter. No quality reduction or source substitution was introduced.

To benchmark on device, use the same playlist, quality, network, storage target,
and concurrency setting. Remove only the test downloads before each run; do not
clear unrelated user files. Capture `adb logcat -s LastWaveDownloadPipeline`,
compare final `batchElapsedMs`, per-job `stageTimesMs`, and `idleGapMs`, and repeat
cold and warm runs. Also test Audio only, Audio + video, custom live sources,
failed video retry, cancellation during transfer/tagging, and clear-all while
preparation/finishing are active. UI changes remain deferred.

## Lyrics storage and translation

Custom lyrics are private app data and take precedence in the player over
automatic provider refreshes. Keys include the actual recording/source and
segment boundaries, so a live edit does not overwrite studio lyrics. Reset
removes that override. Translations are separate from transliteration and are
stored by lyric row, inheriting its timestamp and offset. Line counts are
validated; a partially failed generated translation is not applied.

Google generation uses the official [Cloud Translation Basic endpoint](https://docs.cloud.google.com/translate/docs/reference/rest/v2/translate).
It requires the user's own enabled Google Cloud API key and may incur charges.
It runs only after an explicit click. The key is not persisted, placed in saved
instance state, or sent through the shared account/logging HTTP client.
No live Google API call has been made as part of this implementation.

## Validation

Portable checks on Windows:

```powershell
.\tools\test-feature-core.ps1
```

This compiles the actual pure lyric/listening/enrichment code with the actual
repository parser and models extracted without Android/DI/provider wiring. It
runs JUnit core tests and parses the changed Kotlin sources for syntax errors.
It is **not** Android type checking, Hilt validation, Compose rendering, or a
replacement for the complete Android test/build command:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain
# Install-alongside preview:
.\gradlew.bat testDebugUnitTest assembleDebug -PreworkPreview=true --console=plain
```

The current local machine has no configured Android SDK. The baseline Gradle
test command stops during configuration with `SDK location not found`.

### Required device/UI acceptance checks before release

1. Build the branch successfully; verify the resulting APK signature and install
   it on a phone. Do not relabel an earlier APK as this rework.
2. Review 320dp/360dp portrait, landscape, tablet widths, dark/light themes,
   keyboard-open sheets, TalkBack labels, and 200% font sizing.
3. Import a device recording, restart the app, and reopen it. Exercise Bilibili
   and direct links, missing files, failed network resolution, rename, and delete.
4. Link Ado/Odo at 1:35:54–1:38:20. Play the full show through both boundaries,
   seek over the range, pause/buffer inside it, and repeat the same song later
   in the setlist. Check actual history and Last.fm results separately.
5. Edit/remove overlapping and adjacent mappings. Preview a range, mark its
   playhead, then return to the full show. Confirm preview surface restoration.
6. Test mixed queued/running/failed downloads from all library tabs, reconnect,
   retry failed video without losing audio, cancellation, and clear-all during
   enrichment. Measure batch wall-clock times on the same network and files.
7. Save custom lyrics, restart, switch audio/video and between concerts sharing
   a linked song, and verify independent overrides. Test both lyric UIs,
   positive/negative offsets, line/word sync, undo, blank translation rows,
   translated long/RTL lines, bad Google credentials, and network cancellation.
