#!/usr/bin/env bash
# Builds Liters.xcframework + Swift bindings for NOOP from the pinned
# liters-ffi git dependency.
#
# Prereqs:
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
#   Xcode command line tools
#
# There is deliberately no bundled/system switch here. NOOP links GRDB, which
# links Apple's system libsqlite3, so liters MUST link that same libsqlite3 —
# `Cargo.toml` pins `liters-ffi` with `default-features = false` to guarantee
# it, and this script has no way to opt back in. See Cargo.toml for why two
# SQLite copies in one process is a correctness bug rather than a size problem.
#
# Nothing in the app consumes the output yet; this builds the dependency so the
# sync work has something real to build against.
set -euo pipefail
cd "$(dirname "$0")"

OUT=target/apple
BINDINGS=$OUT/swift
DEVICE_TARGET=aarch64-apple-ios
SIM_TARGETS=(aarch64-apple-ios-sim x86_64-apple-ios)

for t in "$DEVICE_TARGET" "${SIM_TARGETS[@]}"; do
  echo "==> building $t"
  cargo build -p noop-liters --release --target "$t"
done

# Generate Swift bindings from the host library's embedded metadata, built from
# the same sources and features as the device libraries so the bindings cannot
# drift from the shipped staticlib.
echo "==> generating Swift bindings"
cargo build -p noop-liters --release
rm -rf "$BINDINGS" && mkdir -p "$BINDINGS"
cargo run -p noop-liters --bin uniffi-bindgen -- generate \
  --library target/release/libnoop_liters.dylib \
  --language swift --out-dir "$BINDINGS"

# Headers directory for the xcframework: the C header + module map.
HEADERS=$OUT/headers
rm -rf "$HEADERS" && mkdir -p "$HEADERS"
cp "$BINDINGS"/*.h "$HEADERS"/
# uniffi emits a .modulemap; xcodebuild wants module.modulemap
cat "$BINDINGS"/*.modulemap > "$HEADERS"/module.modulemap

# Fat simulator library.
mkdir -p "$OUT/sim"
lipo -create \
  $(for t in "${SIM_TARGETS[@]}"; do echo "target/$t/release/libnoop_liters.a"; done) \
  -output "$OUT/sim/libnoop_liters.a"

rm -rf "$OUT/Liters.xcframework"
xcodebuild -create-xcframework \
  -library "target/$DEVICE_TARGET/release/libnoop_liters.a" -headers "$HEADERS" \
  -library "$OUT/sim/libnoop_liters.a" -headers "$HEADERS" \
  -output "$OUT/Liters.xcframework"

echo
echo "xcframework:   $(pwd)/$OUT/Liters.xcframework"
echo "swift sources: $(pwd)/$BINDINGS/*.swift"
echo
echo "Sanity check — the device archive must DEFINE no sqlite3_* symbols and"
echo "leave them undefined, so they resolve against the libsqlite3 GRDB uses:"
echo "  nm -g target/$DEVICE_TARGET/release/libnoop_liters.a | grep -c ' T _sqlite3_'   # expect 0"
