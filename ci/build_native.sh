#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
WORK="$ROOT/build/native"
ASTER_REV=d8b8e4141fc6b1accc6f183e6ad65d03bc3b0679
test "$(uname -m)" = x86_64
test ! -e "$WORK"
mkdir -p "$WORK" "$ROOT/build/jni"

curl --fail --silent --show-error --location --retry 3 \
    https://dl.google.com/android/repository/android-ndk-r27d-linux.zip -o "$WORK/ndk.zip"
# Checksum from Google's repository2-3.xml for ndk;27.3.13750724 (Linux).
echo "22105e410cf29afcf163760cc95522b9fb981121  $WORK/ndk.zip" | sha1sum --check --strict
unzip -q "$WORK/ndk.zip" -d "$WORK"
TOOLS="$WORK/android-ndk-r27d/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$TOOLS/clang"

git init -q "$WORK/aster"
git -C "$WORK/aster" remote add origin https://github.com/defenseunicorns/aster.git
git -C "$WORK/aster" fetch -q --depth 1 origin "$ASTER_REV"
git -C "$WORK/aster" checkout -q --detach FETCH_HEAD
test "$(git -C "$WORK/aster" rev-parse HEAD)" = "$ASTER_REV"
git -C "$WORK/aster" apply --check "$ROOT/third_party/aster/android-control-directory.patch"
git -C "$WORK/aster" apply "$ROOT/third_party/aster/android-control-directory.patch"

curl --fail --silent --show-error --location --retry 3 \
    https://static.crates.io/crates/redb/redb-4.4.0.crate -o "$WORK/redb.crate"
echo "39178e0a867f9e3eaa6292a4ab1dd3613f2519c5b564341e1e7b29b270736cc4  $WORK/redb.crate" | sha256sum --check --strict
tar -xf "$WORK/redb.crate" -C "$WORK"
git init -q "$WORK/redb-4.4.0"
for PATCH in android-ofd-locks.patch android-armv7-ofd-locks.patch; do
    git -C "$WORK/redb-4.4.0" apply --check "$ROOT/third_party/redb/$PATCH"
    git -C "$WORK/redb-4.4.0" apply "$ROOT/third_party/redb/$PATCH"
done

# Override resolution changes only a scratch copy, never the tracked lockfile/cache.
mkdir "$WORK/probe"
cp "$ROOT/spike/native/Cargo.toml" "$ROOT/spike/native/Cargo.lock" "$WORK/probe/"
cp -R "$ROOT/spike/native/src" "$ROOT/spike/native/examples" "$WORK/probe/"
cat > "$WORK/evaluation.toml" <<EOF
[patch.crates-io]
redb = { path = "$WORK/redb-4.4.0" }
[patch.'https://github.com/defenseunicorns/aster']
aster-node = { path = "$WORK/aster/crates/aster-node" }
EOF
rustup target add armv7-linux-androideabi aarch64-linux-android
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TOOLS/armv7a-linux-androideabi30-clang"
export CC_armv7_linux_androideabi="$CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER"
export AR_armv7_linux_androideabi="$TOOLS/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLS/aarch64-linux-android30-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$TOOLS/llvm-ar"
export CARGO_TARGET_DIR="$WORK/target"
export CARGO_BUILD_JOBS=2
cargo metadata --manifest-path "$WORK/probe/Cargo.toml" \
    --format-version 1 --config "$WORK/evaluation.toml" > "$WORK/metadata.json"
cargo build --locked --manifest-path "$WORK/probe/Cargo.toml" --lib --release \
    --features android-control-evaluation --target armv7-linux-androideabi \
    --target aarch64-linux-android --config "$WORK/evaluation.toml"
for PAIR in armv7-linux-androideabi:armeabi-v7a aarch64-linux-android:arm64-v8a; do
    TARGET=${PAIR%%:*}
    ABI=${PAIR##*:}
    mkdir -p "$ROOT/build/jni/$ABI"
    cp "$WORK/target/$TARGET/release/libweartak_aster_spike.so" "$ROOT/build/jni/$ABI/"
    "$TOOLS/llvm-strip" --strip-unneeded "$ROOT/build/jni/$ABI/libweartak_aster_spike.so"
done
python3 "$ROOT/ci/native_notices.py" "$WORK/metadata.json" "$ROOT/build/native-notices"
cp "$WORK/probe/Cargo.lock" "$ROOT/build/native-notices/Cargo.lock"
(
    cd "$ROOT"
    sha256sum third_party/aster/*.patch third_party/redb/*.patch \
        app/libs/*.aar build/jni/*/*.so > build/native-inputs.sha256
)
git -C "$ROOT" diff --exit-code -- spike/native/Cargo.lock
