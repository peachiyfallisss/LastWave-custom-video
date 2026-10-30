"""Manual, reviewed managed builds only. Never import signing secrets into PR jobs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

if not __debug__:
    raise RuntimeError("Publishing validation must not be disabled")

REPO = "peachiyfallisss/LastWave-custom-video"
PACKAGE = "com.lastwave.app.custom.updates"
MAX_SIZE = 300 * 1024 * 1024


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def signer_fingerprints(output):
    # apksigner uses either numbered signers or SDK-range labels for v3.1.
    # A source-stamp certificate is not an APK signer. Every SDK-range signer
    # must still be the same pinned identity; key rotation is not implicit.
    return {digest.lower() for digest in re.findall(
        r"^Signer(?: #[0-9]+| \(minSdkVersion=[^\r\n]*\)) certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$",
        output, flags=re.MULTILINE)}


def validate_release(item, channel):
    assert type(item["dataSchemaVersion"]) is int and item["dataSchemaVersion"] > 0
    code = item["versionCode"]
    assert type(code) is int and 0 < code <= 2_147_483_647
    assert channel in ("test", "stable")
    tag = f"managed-{channel}-{code}"
    assert item["apkUrl"] == f"https://github.com/{REPO}/releases/download/{tag}/LastWave-Updates.apk"
    assert item["releaseUrl"] == f"https://github.com/{REPO}/releases/tag/{tag}"
    assert type(item["sizeBytes"]) is int and 0 < item["sizeBytes"] <= MAX_SIZE
    assert type(item["minSdk"]) is int and 29 <= item["minSdk"] <= 100
    assert isinstance(item["versionName"], str) and 0 < len(item["versionName"]) <= 100
    for key in ("sha256", "certificateSha256"):
        assert re.fullmatch("[a-f0-9]{64}", item[key])


def advance_feed(previous, release, channel, activate):
    validate_release(release, channel)
    if previous is None:
        assert activate, "The first publication must activate its channel"
        previous = {"schemaVersion": 1, "packageId": PACKAGE, "activeChannel": channel, "releases": {}}
    assert previous["schemaVersion"] == 1 and previous["packageId"] == PACKAGE
    assert previous["activeChannel"] in ("test", "stable")
    assert set(previous["releases"]).issubset({"stable", "test"})
    for name, item in previous["releases"].items():
        validate_release(item, name)
        assert item["certificateSha256"] == release["certificateSha256"], "Never rotate the permanent signing identity implicitly"
        assert release["dataSchemaVersion"] >= item["dataSchemaVersion"], "Cannot publish a database downgrade; retain forward-compatible migrations in stable source"
    current = previous["releases"].get(channel)
    if current != release:  # Re-publishing exactly the same manifest is idempotent.
        maximum = max((item["versionCode"] for item in previous["releases"].values()), default=0)
        assert release["versionCode"] > maximum, "Rebuild the selected source with a higher global version code; Android cannot downgrade"
    result = json.loads(json.dumps(previous))
    result["releases"][channel] = release
    if activate:
        # A previously published stable APK is NOT a rollback from a newer test.
        assert release["versionCode"] >= max(item["versionCode"] for item in result["releases"].values())
        result["activeChannel"] = channel
    assert result["activeChannel"] in result["releases"]
    return result


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout


def prepare(args):
    badging = run(args.aapt, "dump", "badging", args.apk)
    package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    minimum = re.search(r"sdkVersion:'([0-9]+)'", badging)
    assert package and minimum and package[1] == PACKAGE and int(package[2]) == args.code
    assert package[3].endswith(f"-{args.channel}.{args.code}")
    certs = run(args.apksigner, "verify", "--verbose", "--print-certs", args.apk)
    fingerprints = signer_fingerprints(certs)
    pin = Path(args.pin).read_text().strip()
    assert fingerprints == {pin}, f"APK signer mismatch: expected {pin}, found {sorted(fingerprints)}"
    tag = f"managed-{args.channel}-{args.code}"
    database_source = Path("app/src/main/java/com/lastwave/app/data/local/db/AppDatabase.kt").read_text()
    schema = int(re.search(r"version\s*=\s*(\d+)", database_source)[1])
    item = {"dataSchemaVersion": schema, "versionCode": args.code, "versionName": package[3], "minSdk": int(minimum[1]),
            "sizeBytes": Path(args.apk).stat().st_size, "sha256": sha256(args.apk), "certificateSha256": pin,
            "apkUrl": f"https://github.com/{REPO}/releases/download/{tag}/LastWave-Updates.apk",
            "releaseUrl": f"https://github.com/{REPO}/releases/tag/{tag}", "sourceCommit": args.source,
            "notes": os.environ.get("MANAGED_NOTES", "").strip()[:20_000] or f"{args.channel.title()} build from {args.source}. UI/device testing is still required."}
    validate_release(item, args.channel)
    dest = Path(args.directory)
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.apk, dest / "LastWave-Updates.apk")
    (dest / "LastWave-Updates.apk.sha256").write_text(f"{item['sha256']}  LastWave-Updates.apk\n")
    (dest / "release.json").write_text(json.dumps(item, indent=2) + "\n")
    print(f"Verified managed {args.channel} build {args.code}, signer {pin}")


def gh(*args):
    return run("gh", *args, "--repo", REPO)


def release_by_tag(tag):
    response = subprocess.run(["gh", "api", f"repos/{REPO}/releases/tags/{tag}"], text=True, capture_output=True)
    if response.returncode:
        if "HTTP 404" in response.stderr:
            return None
        raise RuntimeError("Could not read release metadata; publication stopped")
    return json.loads(response.stdout)


def publish(args):
    assert os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch", "Publishing is manual only"
    assert os.environ.get("GITHUB_REPOSITORY") == REPO
    dest = Path(args.directory)
    item = json.loads((dest / "release.json").read_text())
    validate_release(item, args.channel)
    assert item["versionCode"] == args.code and item["sourceCommit"] == args.source
    assert item["certificateSha256"] == Path(args.pin).read_text().strip()
    assert item["sha256"] == sha256(dest / "LastWave-Updates.apk")
    assert item["sizeBytes"] == (dest / "LastWave-Updates.apk").stat().st_size
    feed_release = release_by_tag("update-feed")
    previous = None
    if feed_release is not None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "updates.json"
            # Any network/permission error is fatal, never a reason to reset a feed.
            gh("release", "download", "update-feed", "--pattern", "updates.json", "--output", str(manifest))
            previous = json.loads(manifest.read_text())
    feed = advance_feed(previous, item, args.channel, args.activate)
    tag = f"managed-{args.channel}-{args.code}"
    existing = release_by_tag(tag)
    notes = dest / "notes.md"
    notes.write_text(item["notes"] + f"\n\nSource: {args.source}\n\nSigned managed update, package {PACKAGE}. Existing custom/preview apps are separate.\n")
    assets = [str(dest / name) for name in ("LastWave-Updates.apk", "LastWave-Updates.apk.sha256", "release.json")]
    if existing is None:
        gh("release", "create", tag, "--draft", "--target", args.source, "--title", f"LastWave Updates — {args.channel} {args.code}",
           "--notes-file", str(notes), "--prerelease" if args.channel == "test" else "--prerelease=false", "--latest=false", *assets)
    elif not existing.get("draft"):
        # Never overwrite a published APK under an existing version/tag.
        with tempfile.TemporaryDirectory() as directory:
            remote = Path(directory) / "release.json"
            gh("release", "download", tag, "--pattern", "release.json", "--output", str(remote))
            assert json.loads(remote.read_text()) == item, "Existing published version differs"
    else:
        gh("release", "upload", tag, *assets, "--clobber")
    gh("release", "edit", tag, "--draft=false", "--latest=false")
    manifest = dest / "updates.json"
    manifest.write_text(json.dumps(feed, indent=2) + "\n")
    if feed_release is None:
        gh("release", "create", "update-feed", "--target", args.source, "--title", "LastWave Updates channel feed",
           "--notes", "In-app update metadata. Install the managed APK from its linked release, not this JSON file.",
           "--prerelease", "--latest=false", str(manifest))
    else:
        gh("release", "upload", "update-feed", str(manifest), "--clobber")
    print(f"Published {tag}; active channel: {feed['activeChannel']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["prepare", "publish"])
    parser.add_argument("--channel", choices=["test", "stable"], required=True)
    parser.add_argument("--code", type=int, required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--directory", default="managed-dist")
    parser.add_argument("--pin", default="config/managed-updates-signer.sha256")
    parser.add_argument("--apk")
    parser.add_argument("--aapt")
    parser.add_argument("--apksigner")
    parser.add_argument("--activate", action="store_true")
    arguments = parser.parse_args()
    (prepare if arguments.command == "prepare" else publish)(arguments)
