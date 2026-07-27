// swift-tools-version: 5.9
import PackageDescription

// Brand-neutral raw BLE frame capture: the on-disk record shape, an accumulator, and a
// fixture-compatible JSON writer, shared by every protocol package (WhoopProtocol, OuraProtocol,
// PolarProtocol, …) instead of each one reinventing the same capture-file format. Pure value
// types — no CoreBluetooth, no parsing — so it builds and tests on Linux/CI with no strap.
let package = Package(
    name: "RawCapture",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "RawCapture", targets: ["RawCapture"]),
    ],
    targets: [
        .target(
            name: "RawCapture"
        ),
        .testTarget(
            name: "RawCaptureTests",
            dependencies: ["RawCapture"]
        ),
    ]
)
