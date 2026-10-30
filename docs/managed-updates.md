# Managed in-app updates

## One-time installation

The new app is **LastWave Updates**, package `com.lastwave.app.custom.updates`.
It is separate from the original custom app (`.custom`) and old throwaway-key
previews (`.custom.rework`). Do not uninstall those apps to install it. Existing
private data is not automatically migrated; keep the old installation and use
its supported backup/export tools where appropriate. Pick a separate Music
folder before download/deletion tests, since public media folders may be shared.

Install a verified managed APK once. Afterward use **Settings > About > Updates**:

- **Automatic** (default): follow the published active channel. We activate Test
  when publishing a test build, then publish/activate a new approved Stable
  build when testing ends. This is controlled by publication, not chat activity.
- **Stable**: only approved managed stable builds.
- **Test**: explicitly opt in to managed test builds.

Update checks run at startup and when returning to the app (five-minute silent
check cooldown); manual checks bypass the cooldown. Tap Download update, see
progress in the app, then confirm Android's installer. First use may require
allowing installations from LastWave Updates in Android settings; return and tap
Install update afterward. There is no silent installation or GitHub login.
Cancellation/retry does not modify the installed app or existing music.

The old updater pointed at an unrelated upstream repository and compared only
`4.2.2`, ignoring custom/test suffixes. The managed updater instead reads our
public `update-feed/updates.json` release asset and compares Android version codes.
Old/unmanaged packages do not offer incompatible upstream APKs.

## Publishing an update

Dispatch `.github/workflows/custom-video-debug.yml` on a **reviewed, trusted source
ref** with `managed_channel=test` or `stable`, `activate=true`, and optional notes.
The workflow already exists on the default branch, so it can be dispatched on
this feature branch without merging unfinished features. PR/push verification
runs do not use the permanent key or publish updates.

The workflow:

1. Runs the regression checks, publishing-policy tests, Android unit tests, and
   APK assembly. Managed builds use the same package and permanent key for both
   channels and are not debuggable. No fallback random signing key is permitted.
2. Assigns `versionCode = 1000000 + GITHUB_RUN_NUMBER` from this single workflow.
   Do not reset/migrate the counter without preserving monotonicity. Version names
   identify the source base version, channel, and build code.
3. Verifies APK signature, package, version, minimum SDK, and the pinned signer.
4. Publishes a unique `managed-test-CODE` prerelease or `managed-stable-CODE`
   release with the APK, checksum, and exact-source metadata. Already-published
   versions cannot be overwritten with different manifests.
5. Updates the shared feed, preserving the other channel. Only manually dispatched
   successful builds reach the contents-write publishing job. Publication is
   serialized; tokens are read-only during builds and signing secrets are never
   passed to pull-request builds.

Example (authenticated repository maintainer):

```powershell
gh workflow run custom-video-debug.yml --ref feat/custom-lyrics-translations -f managed_channel=test -f activate=true -f notes='Testing the download pipeline and in-app updates'
```

To return from a test to approved stable behavior, dispatch a **new build of an
approved, compatible source ref** on Stable. Never repoint Automatic at an older
stable APK: Android will not downgrade its version code. The publisher rejects
non-monotonic builds and database schema downgrades. The updater checks the APK's
embedded database schema too. Managed databases do not use destructive Room
fallbacks: missing migrations must be fixed, not silently erase user data.
Review all non-database file/preference migrations for compatibility as well.

Stable here means approved source in this managed distribution; it is not an
old differently signed `.custom` APK or an upstream LastWave release. This change
does not merge the feature branch or publish it as stable automatically.

## Integrity and installation

The dedicated HTTPS update client has no account cookies, auth interceptors,
shared cache, or HTTP logging. Redirects are limited to GitHub's release asset
infrastructure. Feed/APK sizes are bounded. APKs are downloaded to app-private
cache, checked using streaming SHA-256, then checked against the installed
signer, package, exact version, minimum SDK, and database schema. Cached APKs are
checked again before every installer launch. Android performs the actual signed
package installation and requires user confirmation. A missing/incompatible
channel fails closed rather than substituting another app or silently downgrading.

References: [Android update requirements](https://developer.android.com/google/play/app-updates),
[app signing](https://developer.android.com/studio/publish/app-signing),
[installation permission settings](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES).

## Permanent signing identity

Public certificate SHA-256 is pinned in `config/managed-updates-signer.sha256`.
The private PKCS12 and Windows-account-encrypted password backup live outside
this repository under `%USERPROFILE%\.lastwave\signing`. The key/password are
stored as encrypted repository secrets `MANAGED_UPDATES_KEYSTORE` and
`MANAGED_UPDATES_PASSWORD`. No private signing material is committed or printed.
Keep the key and its recoverable password backed up securely: the local DPAPI
password file alone is tied to this Windows account/machine.

`tools/setup-managed-updates.ps1` uses an authenticated GitHub CLI and keytool.
It refuses to generate a replacement when the pinned identity already exists.
Do not rotate/reset these secrets for routine tests. A lost key cannot be
reconstructed from an APK certificate.

## Validation still needed on a phone

Install once alongside existing apps; check a subsequent managed update keeps
sign-in, playlists, custom lyrics, concert mappings, and downloads. Exercise
Automatic/Test/Stable, Android's initial installation permission, cancelling the
installer, interrupted network downloads, and switching channels during a
transfer. A stable promotion must have a higher build code and compatible data
schema. Automated tests are not a substitute for these device checks.
