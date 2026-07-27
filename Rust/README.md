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
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios \
                  aarch64-apple-darwin x86_64-apple-darwin
./build-ios.sh
xcodegen generate
```

`build-ios.sh` takes about 25 minutes cold and produces four things:

| output | tracked | why |
|---|---|---|
| `target/apple/Liters.xcframework` | no | 83 MB per slice, three slices |
| `Strand/CloudSync/Generated/liters_ffi.swift` | **yes** | source the app compiles; a reviewer should be able to read it |
| `Config/LitersLocal.xcconfig` | no | names slice directories under the untracked `target/` |
| `target/apple/swift/*` | no | the staging copy the two above are made from |

There is a macOS slice as well as the two iOS ones because `StrandTests` is a
macOS unit-test bundle (`project.yml`), so `StrandTests/LitersRoundTripTests.swift`
can only run against a macOS build.

**Skipping this script is not an error.** `Config/Liters.xcconfig` is tracked and
defines every liters build setting as empty, then optionally includes the local
file this script writes. With no local file, `project.yml`'s three appends
(`LIBRARY_SEARCH_PATHS`, `SWIFT_INCLUDE_PATHS`, `OTHER_LDFLAGS`) expand to
nothing, the `LITERS` compilation condition is unset, and the generated bindings
compile to nothing. A checkout that has never seen Rust builds exactly as it
does today. Deleting `Config/LitersLocal.xcconfig` is how you turn liters back
off.

To move the pin to a newer liters commit:

```sh
cargo update -p liters-ffi     # then commit the Cargo.lock change
```

`Cargo.lock` is committed on purpose: it records the exact liters commit this
tree builds against, so a checkout is reproducible even though the dependency
tracks a branch.

## Build time, and the one thing that would fix it

Most of the wall clock is `uniffi_bindgen` — a code-generation CLI — being
compiled at `opt-level=3` **once per cross-compiled target**. It is dead weight
in every one of them: nothing on a phone runs a bindings generator.

It cannot be turned off from this crate. `liters-ffi` declares
`uniffi = { version = "0.32", features = ["cli"] }` among its normal
dependencies, and cargo feature unification means a downstream crate cannot
remove a feature an upstream crate asked for. The fix belongs in `liters-mobile`:
move that bin behind a `cli` feature and depend on plain `uniffi` otherwise.
`build-ios.sh` already passes `--lib` for the cross targets, which at least skips
*linking* the useless binary.

## Known gotcha: the replica's integrity check under system SQLite

`liters`' own test suite passes 80/80 with its default bundled SQLite, and
**31 passed / 49 failed** under `cargo test -p liters --no-default-features`, the
configuration NOOP actually ships. Every one of the 49 fails with the same
`SQLITE_CANTOPEN` ("unable to open database file"), and all of them are on the
Replica restore path.

The cause is a single line — `crates/liters/src/replica.rs:648`, the only
read-only open in the crate, reached only from `check_integrity` after a restore:

```rust
rusqlite::Connection::open_with_flags(&self.db_path, OpenFlags::SQLITE_OPEN_READ_ONLY)
```

Apple's `libsqlite3` (3.51.0) cannot run a statement on a **WAL-mode** database
opened `SQLITE_OPEN_READONLY` when the `-shm` sidecar is absent — and the restore
deletes `-wal`/`-shm` just before this call. Reduced to a 20-line C program
against `/usr/lib/libsqlite3.dylib`:

```
WAL, sidecars removed, READONLY     open=0  prepare=14  unable to open database file
WAL, sidecars intact,  READONLY     open=0  prepare=0   OK
WAL, sidecars removed, READWRITE    open=0  prepare=0   OK
rollback journal,      READONLY     open=0  prepare=0   OK
```

**This does not affect NOOP's phone.** The phone is a Writer, and `writer.rs`
contains no read-only open at all — the whole crate has exactly one, on the
Replica path. The Replica runs on the server, in a Rust process with no GRDB,
which therefore builds liters with bundled SQLite and never reaches this. It is
still a real bug for any embedder that restores on a system-SQLite platform, and
it should be fixed upstream (open the integrity check READWRITE, or materialise
the `-shm` first).
