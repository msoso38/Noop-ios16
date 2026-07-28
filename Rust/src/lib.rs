//! NOOP's build surface for [liters](https://github.com/vishk23/liters-mobile).
//!
//! This crate deliberately contains no logic. It exists so NOOP can pin liters
//! as a git dependency and build it with the feature set NOOP needs — chiefly
//! `default-features = false`, which makes liters link the system libsqlite3
//! that GRDB already links instead of bundling a second SQLite into the
//! process. See `Cargo.toml` for why that matters.
//!
//! Re-exporting `liters_ffi` keeps its UniFFI scaffolding — the `#[no_mangle]`
//! entry points and the embedded interface metadata that
//! `uniffi-bindgen --library` reads — reachable from this crate's staticlib and
//! cdylib, so the generated Swift bindings and the shipped archive describe the
//! same API.
//!
//! Nothing in NOOP calls this yet. Wiring the app's sync path to it is separate
//! work; this crate is the dependency those changes build against.

pub use liters_ffi::*;
