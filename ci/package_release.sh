#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
test "${CI_SERVER_HOST:-}" = git.tak.gov
test "${CI_PROJECT_PATH:-}" = weartak-plugins/weartak-plugin-aster
test "${CI_COMMIT_BRANCH:-}" = main
test "${CI_DEFAULT_BRANCH:-}" = main
test "${CI_COMMIT_REF_PROTECTED:-}" = true
test "${CI_PIPELINE_SOURCE:-}" = push
[[ ${CI_COMMIT_SHA:-} =~ ^[0-9a-f]{40}$ ]]
test "$(git rev-parse HEAD)" = "$CI_COMMIT_SHA"
for NAME in CI_PIPELINE_IID CI_PIPELINE_ID CI_JOB_ID; do
    [[ $(printenv "$NAME") =~ ^[1-9][0-9]*$ ]]
done
ROOT=$(git rev-parse --show-toplevel)
SDK_ROOT=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk-linux}}
TOOLS=$(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
test -x "$TOOLS/apksigner"
test -x "$TOOLS/aapt"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
CERTIFICATES=$("$TOOLS/apksigner" verify --print-certs "$APK")
SIGNERS=$(printf '%s\n' "$CERTIFICATES" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')
EXPECTED=1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d
test "$SIGNERS" = "$EXPECTED"
BADGING=$("$TOOLS/aapt" dump badging "$APK")
PACKAGE=$(printf '%s\n' "$BADGING" | sed -n '1p')
case "$PACKAGE" in
    "package: name='com.weartak.plugin.aster' versionCode='$CI_PIPELINE_IID' versionName='0.1.0-evaluation'"*) ;;
    *) echo "Unexpected release package identity/version." >&2; exit 1 ;;
esac
for ABI in armeabi-v7a arm64-v8a; do
    unzip -Z1 "$APK" | grep -Fx "lib/$ABI/libweartak_aster_spike.so" >/dev/null
done
unzip -Z1 "$APK" | grep -Fx assets/legal/LICENSE >/dev/null
unzip -Z1 "$APK" | grep -Fx assets/legal/native-dependencies/inventory.json >/dev/null
OUTPUT="$ROOT/app/build/outputs/aster/release"
test ! -e "$OUTPUT"
mkdir -p "$OUTPUT"
NAME="WearTAK-Plugin-Aster-0.1.0-evaluation-p$CI_PIPELINE_IID.apk"
cp "$APK" "$OUTPUT/$NAME"
cp "$ROOT/build/native-inputs.sha256" "$OUTPUT/native-inputs.sha256"
(
    cd "$OUTPUT"
    sha256sum "$NAME" > "$NAME.sha256"
)
printf 'source_sha=%s\npipeline_id=%s\npipeline_iid=%s\njob_id=%s\npackage=com.weartak.plugin.aster\nversion_name=0.1.0-evaluation\nversion_code=%s\nsigner_sha256=%s\nchannel=evaluation\n' \
    "$CI_COMMIT_SHA" "$CI_PIPELINE_ID" "$CI_PIPELINE_IID" "$CI_JOB_ID" "$CI_PIPELINE_IID" "$EXPECTED" > "$OUTPUT/$NAME.provenance.txt"
echo "Verified signed evaluation artifact: $NAME (not production-ready)."
