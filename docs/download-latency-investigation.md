# Download latency investigation

This test build adds measurements, not a claimed speed fix. Audio/video quality,
HTTP request sizes, file-export behavior, and concurrency defaults are unchanged.
The user sees roughly 1 second per song in YouTube Music versus 10+ seconds in
LastWave and specifically notices Finding YouTube stream and Saving video.

The Saving video badge can be shown while the separately started video job is
still resolving its source, before its media GET starts. It is not proof that
filesystem writes are the bottleneck. Also, current LastWave video selection can
reach 1080p while the user's YouTube Music High downloads are 720p. That mismatch
is recorded, but is not treated as the established explanation for a 10x gap.

## Capture on the phone (no ADB)

1. In LastWave Updates, check for the latest Test update and install it.
2. Download 2-3 tracks that are not already saved, using Audio + video and the
   usual settings. Do not clear unrelated downloads just to run this test.
3. In the same app session, open Settings > About > Diagnostics and export/share
   the report. The dedicated download timings section is enough for analysis.
   It contains no track names, URLs, cookies, or token values. Other pre-existing
   diagnostic sections can contain device/playback/log information; review them
   before sharing. Measurements are in memory and reset when the process restarts.

## What the section records

- Queue wait before preparation and total job wall time, including cancellation.
- PREPARE_METADATA, AUDIO_RESOLVE, and VIDEO_RESOLVE separately. Transfer-slot
  and finishing-slot waits are separate so queued work is not mistaken for slow
  extraction. FINISHING is tagging/publication.
  Phase durations may overlap with media transfers and must not simply be added.
- Audio extractor profile/bitrate and the actual selected YouTube video height.
- AUDIO, VIDEO, and VIDEO_AUDIO (adaptive soundtrack) network bytes and requests.
  All three streams remain separate; no recording is substituted or deduplicated.
- First-body-byte latency, client setup/queue time, server header wait, failed and
  cancelled requests, and effective throughput over overlapping-request wall time.
- Whether requests use ranges, their maximum requested size, whether the server
  is a Google video CDN, and whether an n parameter exists (never its value).
- Logical completed file sizes separately from network bytes: this exposes
  cached responses, duplicate soundtrack transfers, and retry overhead.

Network observers count reads but do not eagerly consume, rewrite, buffer entire
media files, alter headers, add requests, or change the scheduling policy. Only
explicitly tagged media downloads are traced, not account/authentication traffic.
At most 20 completed job records are retained plus active jobs; report output is
bounded to recently started/finished jobs so a queued playlist cannot obscure
completed measurements.

## Interpreting results before choosing a fix

Long resolution and short transfers suggest extraction/search/fallback overhead,
including resolving video separately after audio resolution. Long setup suggests
connection/dispatcher delays. Large first-byte/server waits or low effective
transfer rate suggest the delivery path or retries rather than file tagging.
Large video byte counts require a like-for-like quality comparison. Long
FINISHING with short network spans identifies file/metadata work specifically.
Zero network bytes with logical media bytes can indicate an HTTP cache hit;
a reused saved track can have no media requests at all.

A public maintainer reference documents that large YouTube HTTP requests may
be throttled: https://github.com/yt-dlp/yt-dlp/wiki/FAQ . That is a lead to test,
not evidence that this phone's current bottleneck is throttling. No speculative
chunking, n transformation, quality downgrade, or file-storage migration is
introduced in this diagnostic build.
