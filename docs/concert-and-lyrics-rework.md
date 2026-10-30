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
- No parallel muted YouTube playback is introduced. The existing connected
  account, opt-in, thresholds, and offline YouTube submission mechanisms remain
  in place. A submission does not establish remote history visibility or badge
  eligibility; those need verification with YouTube.

The song shown in the system/media notification remains the actual full concert
item; this change projects identity for LastWave's own attribution and lyrics.
External scrobblers reading that notification do not receive the setlist's
projected song metadata.

## Downloads

The existing FIFO concurrency limiter, validated HTTP ranges, exact-recording
policy, and partial audio/video preservation are retained. The rework removes
a redundant blocking song-details lookup for a selected recording whose title
and artist are already valid. Lyrics lookup now starts **before** transfer, not
after it. Lyrics/artwork enrichment has a bounded final wait; it cannot hold a
completed file and the next queued job behind another provider waterfall.
This does not claim measured parity with YouTube Music or change provider
bandwidth limits. The retry action preserves the original request, including
the exact selected video and Audio + video choice.

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
