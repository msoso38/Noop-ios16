// UniFFI's bindings generator, built from this workspace so it is the exact
// uniffi version the scaffolding was compiled with. `liters-ffi` ships the same
// bin, but it arrives here as a git dependency and `cargo run -p` cannot reach
// a dependency's binaries.
fn main() {
    uniffi::uniffi_bindgen_main()
}
