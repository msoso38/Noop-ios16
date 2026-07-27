#!/usr/bin/env bash
# Builds Liters.xcframework + the Swift bindings NOOP compiles, from the pinned liters-ffi git
# dependency, and writes the xcconfig that links them.
#
# Run this once before building the app with the page-replication trial. Afterwards a plain
# `xcodegen generate && xcodebuild …` picks the archive up with no extra flags: the last step here
# writes Config/LitersLocal.xcconfig, which the tracked Config/Liters.xcconfig optionally includes.
# Without that file every liters build setting expands to empty and the app builds exactly as it
# does today, so running this script is opt-in and skipping it is not an error.
#
# Prereqs:
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios \
#                     aarch64-apple-darwin x86_64-apple-darwin
#   Xcode command line tools
#
# There is deliberately no bundled/system switch here. NOOP links GRDB, which links Apple's system
# libsqlite3, so liters MUST link that same libsqlite3 — `Cargo.toml` pins `liters-ffi` with
# `default-features = false` to guarantee it, and this script has no way to opt back in. See
# Cargo.toml for why two SQLite copies in one process is a correctness bug rather than a size
# problem, and the SANITY CHECK below, which fails the build if it ever stops holding.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)

OUT=target/apple
BINDINGS=$OUT/swift
DEVICE_TARGET=aarch64-apple-ios
SIM_TARGETS=(aarch64-apple-ios-sim x86_64-apple-ios)
# macOS matters for more than completeness: StrandTests is a macOS unit-test bundle hosted in the
# macOS app (project.yml), so the round-trip tests in StrandTests/LitersRoundTripTests.swift can only
# run against a macOS slice. Both arches, because the macOS app builds ONLY_ACTIVE_ARCH=NO in Release.
MAC_TARGETS=(aarch64-apple-darwin x86_64-apple-darwin)

# `--lib` on the cross-compiled targets skips the `uniffi-bindgen` BIN, which is a host developer
# tool and has no business being linked for a phone. (It does not avoid *compiling* the
# `uniffi_bindgen` crate: liters-ffi declares `uniffi = { features = ["cli"] }` among its normal
# dependencies, and cargo feature unification means a downstream crate cannot turn that off. Moving
# liters-ffi's own bindgen bin behind a `cli` feature would cut several minutes per target off this
# script; that has to be fixed in liters-mobile, not here.)
for t in "$DEVICE_TARGET" "${SIM_TARGETS[@]}" "${MAC_TARGETS[@]}"; do
  echo "==> building $t"
  cargo build -p noop-liters --release --target "$t" --lib
done

# Generate Swift bindings from the host library's embedded metadata, built from the same sources and
# features as the device libraries so the bindings cannot drift from the shipped staticlib.
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

# Fat simulator + fat macOS archives. `lipo` because an xcframework slice holds one archive per
# platform, not one per arch.
mkdir -p "$OUT/sim" "$OUT/mac"
lipo -create \
  $(for t in "${SIM_TARGETS[@]}"; do echo "target/$t/release/libnoop_liters.a"; done) \
  -output "$OUT/sim/libnoop_liters.a"
lipo -create \
  $(for t in "${MAC_TARGETS[@]}"; do echo "target/$t/release/libnoop_liters.a"; done) \
  -output "$OUT/mac/libnoop_liters.a"

rm -rf "$OUT/Liters.xcframework"
xcodebuild -create-xcframework \
  -library "target/$DEVICE_TARGET/release/libnoop_liters.a" -headers "$HEADERS" \
  -library "$OUT/sim/libnoop_liters.a" -headers "$HEADERS" \
  -library "$OUT/mac/libnoop_liters.a" -headers "$HEADERS" \
  -output "$OUT/Liters.xcframework"

# ---------------------------------------------------------------------------
# SANITY CHECK — a hard gate, not advice.
#
# The device archive must DEFINE no sqlite3_* symbols and leave them undefined, so they resolve
# against the libsqlite3 GRDB links. A non-zero count means `bundled-sqlite` came back on and a second
# SQLite is about to be linked into the app, which silently breaks the advisory locking liters'
# correctness rests on. That is worth failing a build over, so it does.
# ---------------------------------------------------------------------------
SYMS=$(nm -g "target/$DEVICE_TARGET/release/libnoop_liters.a" 2>/dev/null || true)
DEFINED=$(printf '%s\n' "$SYMS" | grep -c ' T _sqlite3_' || true)
UNDEFINED=$(printf '%s\n' "$SYMS" | grep -c ' U _sqlite3_' || true)
if [ "$DEFINED" -ne 0 ]; then
  echo "FATAL: the device archive DEFINES $DEFINED sqlite3_* symbols — liters bundled its own SQLite." >&2
  echo "       Check that Cargo.toml still pins liters-ffi with default-features = false." >&2
  exit 1
fi
if [ "$UNDEFINED" -eq 0 ]; then
  echo "FATAL: the device archive references NO sqlite3_* symbols at all, which cannot be right." >&2
  exit 1
fi
echo "==> sqlite linkage OK: 0 defined, $UNDEFINED undefined (resolve from the host's libsqlite3)"

# ---------------------------------------------------------------------------
# Install the Swift bindings into the app's source tree.
#
# Wrapped in `#if LITERS` because the file is TRACKED (it is source the app compiles, and a reviewer
# should be able to read it) while the archive it needs is NOT (83 MB per slice). A checkout that has
# never run this script must still build, so without the LITERS condition — set below, in the
# untracked xcconfig — the file has to compile to nothing rather than fail on `import liters_ffiFFI`.
# ---------------------------------------------------------------------------
GEN=$ROOT/Strand/CloudSync/Generated
mkdir -p "$GEN"
{
  echo "// GENERATED by Rust/build-ios.sh from the pinned liters-ffi. Do not edit by hand."
  echo "// The #if is added by that script, not by uniffi: see the script for why it is here."
  echo "#if LITERS"
  cat "$BINDINGS/liters_ffi.swift"
  echo "#endif"
} > "$GEN/liters_ffi.swift"
echo "==> installed bindings: $GEN/liters_ffi.swift"

# ---------------------------------------------------------------------------
# Write the linkage xcconfig FROM the layout xcodebuild just produced, so the slice directory names
# cannot drift from the ones actually in the xcframework.
# ---------------------------------------------------------------------------
slice_for() {  # $1 = SupportedPlatform, $2 = SupportedPlatformVariant ("" for none)
  python3 - "$OUT/Liters.xcframework/Info.plist" "$1" "$2" <<'PY'
import plistlib, sys
plist, platform, variant = sys.argv[1], sys.argv[2], sys.argv[3]
with open(plist, 'rb') as f:
    libs = plistlib.load(f)['AvailableLibraries']
for lib in libs:
    if lib.get('SupportedPlatform') == platform and lib.get('SupportedPlatformVariant', '') == variant:
        print(lib['LibraryIdentifier']); break
else:
    sys.exit("no slice for %s/%s" % (platform, variant or '-'))
PY
}
IOS_SLICE=$(slice_for ios "")
SIM_SLICE=$(slice_for ios simulator)
MAC_SLICE=$(slice_for macos "")

XCCONFIG=$ROOT/Config/LitersLocal.xcconfig
cat > "$XCCONFIG" <<EOF
// GENERATED by Rust/build-ios.sh. Untracked (see .gitignore) — it names slice directories under
// Rust/target/, which is itself untracked, so committing it would point other checkouts at an
// archive that is not there. Regenerate by re-running the script; delete it to turn liters off.
//
// Included from the tracked Config/Liters.xcconfig, which project.yml applies project-wide.

LITERS_XCFRAMEWORK = \$(SRCROOT)/Rust/target/apple/Liters.xcframework

// One slice per SDK. This is what an xcframework's Info.plist encodes; expressing it as build
// settings is what lets the linkage come from an xcconfig instead of a "Link Binary With Libraries"
// build phase, which in turn is what keeps it absent — rather than broken — on a checkout that has
// never built the Rust side.
LITERS_LIB_DIR[sdk=iphoneos*]        = \$(LITERS_XCFRAMEWORK)/$IOS_SLICE
LITERS_LIB_DIR[sdk=iphonesimulator*] = \$(LITERS_XCFRAMEWORK)/$SIM_SLICE
LITERS_LIB_DIR[sdk=macosx*]          = \$(LITERS_XCFRAMEWORK)/$MAC_SLICE

// Where \`import liters_ffiFFI\` resolves from: each slice carries the uniffi header + module.modulemap.
LITERS_HEADER_DIR = \$(LITERS_LIB_DIR)/Headers

// Only the SDKs with a slice get the -l flag. watchOS has none, so the watch app and its
// complication extension link nothing and need no per-target opt-out.
LITERS_LDFLAGS[sdk=iphoneos*]        = -lnoop_liters
LITERS_LDFLAGS[sdk=iphonesimulator*] = -lnoop_liters
LITERS_LDFLAGS[sdk=macosx*]          = -lnoop_liters

// Compiles Strand/CloudSync/Generated/liters_ffi.swift and StrandTests/LitersRoundTripTests.swift.
//
// OTHER_SWIFT_FLAGS rather than the idiomatic SWIFT_ACTIVE_COMPILATION_CONDITIONS, because that
// setting cannot be appended to from here and be seen: xcodegen writes a bare
// \`SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG\` into the PROJECT's Debug config, and a project-level
// build setting outranks the project-level xcconfig this file is included from — so the LITERS entry
// is silently dropped (verified with \`xcodebuild -showBuildSettings\`). OTHER_SWIFT_FLAGS has no
// xcodegen default to collide with, so the append survives.
OTHER_SWIFT_FLAGS = \$(inherited) -D LITERS
EOF

echo
echo "xcframework:   $(pwd)/$OUT/Liters.xcframework"
echo "  ios:         $IOS_SLICE"
echo "  simulator:   $SIM_SLICE"
echo "  macos:       $MAC_SLICE"
echo "bindings:      $GEN/liters_ffi.swift"
echo "linkage:       $XCCONFIG"
echo
echo "Next: xcodegen generate && xcodebuild -scheme Strand -destination 'platform=macOS' test"
