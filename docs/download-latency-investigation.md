# Download latency investigation

The initial diagnostic test build (1000040) added measurements, not a claimed speed fix. Audio/video quality,
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

## Phone capture: 2026-10-30, build 1000040

The three completed Audio + video jobs identify a concrete transfer-path problem:

| Anonymous job | Audio resolve | Video resolve | Main audio transfer | Video / soundtrack transfer span | Final processing | Transfer-slot wait |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 7.68 s | 1.74 s | 0.27 s | 134.10 s | 5.65 s | 0 s |
| 2 | 7.76 s | 1.76 s | 0.30 s | 94.93 s | 4.44 s | 0 s |
| 3 | 8.12 s | 1.82 s | 0.29 s | 127.51 s | 5.40 s | 88.45 s |

Main audio used four bounded byte ranges and achieved 83–128 Mbit/s. Video
(5.6–9.7 MB) and its own soundtrack (3.1–4.4 MB) each used one unbounded GET;
their effective rates were 0.26–0.81 Mbit/s. First-byte waits were under 0.7 s,
with no failed/retried requests in these three jobs. Video transfer, not initial
connection setup or writing tags, dominated this capture. Job 3 waited for the
admitted slow transfers ahead of it; increasing playlist concurrency alone is
not the fix. Timers overlap and the table must not be summed as sequential stages.

These measurements strongly implicate the different request strategy. They do
not establish the CDN's internal throttling algorithm or YouTube Music's private
implementation. Raw logs, recording identities, and signed URLs are not stored
in this repository.

### Targeted follow-up change

Google video CDN offline video and soundtrack downloads now use 1 MiB ranges
with at most four workers per media file. The first actual range discovers the
total length and is retained (no HEAD/probe request or duplicate first chunk).
A dedicated, bounded offline dispatcher prevents these subrequests from taking
over playback/API admission. Existing playlist transfer/finishing limits remain.

Every response must match its requested range, total and exact body length;
entity validators are retained when supplied. If the first request returns a
whole-file 200, it consumes that body once as a compatible single-request
fallback. A later ignored/changed range fails safely instead of mixing bytes.
Transient I/O/5xx errors get one bounded same-range retry, with monotonic logical
progress; 4xx and protocol errors do not silently retry unbounded transfers.
Cancellation joins all native readers before partial-file cleanup. Device and
non-Google-video HTTP sources retain their existing single-stream strategy.

Both media files remain the selected video's own exact streams. Main audio,
video format/resolution, soundtrack selection, storage destinations, tagging,
and the app's playlist concurrency setting are unchanged. There is no transcoding,
substitution, or studio-audio reuse. The same diagnostics remain enabled for the
next phone comparison; **post-change phone speed is not yet measured**.

The 7.7–8.1 s audio resolution and 4.4–5.7 s final processing remain secondary
targets. They are not claimed fixed by this transfer change, and duplicate
audio/video extraction or optional enrichment can be investigated separately
after checking the dominant bottleneck. Persisted crashes from older build
1000036 in the report are not evidence of a new crash in the captured 1000040
download jobs, which all completed successfully.

## Follow-up: lookup and final processing

The user reports a major improvement after the range-transfer build 1000043.
No numeric post-range phone capture has been provided yet. The next change
addresses identifiable work in the remaining lookup/finalization paths:

- Downloads first reuse a fresh authentication-scoped audio URL, then immediately
  try the NewPipe extractor that succeeded in the phone capture. Previously,
  that extractor ran only after a playback-client stage with a 4-second budget.
  The full playback resolver remains a fallback for anonymous-extraction failure
  (including recordings that need the authenticated path). Extraction has a
  6-second budget. Parent cancellation is not treated as a reason to start a fallback.
- NewPipe's anonymous StreamInfo response is reused for both audio and video of
  the **same video ID**. Same-ID concurrent callers coalesce; unrelated recordings
  do not block each other. The process-only LRU holds at most 16 responses for
  90 seconds, never beyond the earliest supplied signed-URL expiry minus two
  minutes. Rejected URL invalidation evicts the response; an old in-flight load
  cannot repopulate the invalidated entry. Account credentials/responses are not
  placed in this anonymous cache.
- The synchronous NewPipe downloader bridges into cancellable HTTP reads, tied
  to the extraction caller's job, so a timeout/cancellation cancels and joins its
  active HTTP reader instead of leaving work behind the fallback.
- Actual cover-image download and normalization start during preparation, not
  inside final tagging. Same cover URLs share an 8-entry/5-minute normalized-image
  cache (maximum 16 MiB at the existing 2 MiB per-cover limit). Final tagging uses
  only prepared bytes and keeps the existing 500 ms optional-artwork wait budget;
  it never starts a second synchronous download/retry chain. Slow/unavailable
  optional artwork can be omitted while title/artist/album/lyrics still get tagged;
  its source URL remains available to the app and missing art is reported honestly.
  No extra background retagging or writes to published files are introduced.

Audio bitrate selection is not lowered to AAC/M4A just to avoid remuxing. Video
quality, byte-range transfers, exact recording/soundtrack preservation, public
storage destinations, metadata/lyrics embedding, and playlist admission limits
are retained. This change does not replace the user's files with an encrypted
cache, remove tags, skip publication validation, or claim YouTube Music parity.

Finishing now reports REMUX (including provider conversion when relevant),
FILE_METADATA, LYRICS_WAIT, ARTWORK_WAIT, TAG_WRITE, PUBLIC_COPY (including lyrics
sidecars), and DB_PUBLISH separately. ARTWORK_PREPARE overlaps resolution and
transfers and is **not** additional serial finishing time. A new phone report is
still needed to measure the actual lookup/finishing improvement and establish
whether remuxing, metadata extraction, or storage copies are the next bottleneck.
