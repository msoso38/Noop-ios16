# Rust — the liters dependency

This directory is NOOP's build surface for
[liters](https://github.com/vishk23/liters-mobile): Litestream v0.5-compatible
SQLite replication, embeddable in iOS and Android apps. liters is
**[Kurt Mackey's](https://github.com/mrkurt/liters)** library, MIT-licensed;
`liters-mobile` is the standalone derivative we develop in while upstream
review is paused.

`noop-liters` contains no logic. It exists so NOOP can pin liters as a git
dependency and build it with the one feature choice NOOP cannot get wrong, then
package it as an xcframework plus generated Swift bindings.

## The SQLite rule

**liters must link the system libsqlite3, never a bundled copy.**

`Packages/NoopLocalAccess` and `Packages/StrandImport` both depend on
GRDB.swift 6.29.3, which links Apple's system libsqlite3 through
`.systemLibrary(name: "CSQLite")`. If liters bundled its own SQLite there would
be two SQLite libraries in one process, and they do not share the
process-global `unixInodeInfo` table SQLite uses to work around POSIX's "close
any descriptor to a file, lose all your locks on it" rule. Either library can
then silently drop the other's advisory locks.

For most SQLite users that is a latent hazard. For liters it is a direct
correctness bug: the writer's guarantee that no foreign checkpointer restarts
the WAL underneath it *is* a long-running read lock. Drop that lock and a
foreign checkpoint restarts the WAL, the resume frame is overwritten, and the
next push recovers the only way it can — by uploading a full snapshot of the
database, which is the exact upload liters was adopted to avoid.

`Cargo.toml` enforces this with `default-features = false` on `liters-ffi`, and
`build-ios.sh` offers no way to opt back in. Verify it on the built archive:

```sh
nm -g target/aarch64-apple-ios/release/libnoop_liters.a | grep -c ' T _sqlite3_'
# 0 — defines none; they stay undefined and resolve against the same
#     libsqlite3 GRDB uses (iOS SDK usr/lib/libsqlite3.tbd exports them)
```

## Building

```sh
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
./build-ios.sh
```

Outputs `target/apple/Liters.xcframework` and `target/apple/swift/*.swift`.
Neither is committed, and neither is referenced by `project.yml` yet.

To move the pin to a newer liters commit:

```sh
cargo update -p liters-ffi     # then commit the Cargo.lock change
```

`Cargo.lock` is committed on purpose: it records the exact liters commit this
tree builds against, so a checkout is reproducible even though the dependency
tracks a branch.

## What is deliberately not here

Nothing in the app calls liters yet — no Swift target imports the bindings, and
`project.yml` does not reference the xcframework. Wiring NOOP's sync path to
liters is separate work (delta sync, stage 1 of
`noop-cloud/docs/SYNC_BUILD_VS_BUY.md` §1.3). This directory exists so that work
has a real, building dependency to start from rather than a plan.
