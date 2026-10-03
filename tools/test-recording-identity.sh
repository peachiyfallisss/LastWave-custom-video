#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_output="$(mktemp -d)"
trap 'rm -rf "$test_output"' EXIT
java com.sun.tools.javac.Main -encoding UTF-8 -d "$test_output" \
  "$repo_root/app/src/main/java/com/lastwave/app/data/lossless/RecordingIdentity.java" \
  "$repo_root/app/src/test/java/com/lastwave/app/data/lossless/RecordingIdentityRegression.java"
java -cp "$test_output" com.lastwave.app.data.lossless.RecordingIdentityRegression
