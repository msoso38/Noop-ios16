import XCTest
import GRDB
import WhoopProtocol
@testable import WhoopStore

/// v28-ppg-resp-sample migration + dedicated reader: PPG-derived respiratory rate from the WHOOP 5.0
/// v26 optical buffer (#103). Unlike `ppgHrSample` (COALESCEd into `hrSample` reads), this stream has
/// its own reader — see `PpgHrSampleTests.swift` for the sibling HR-side migration tests.
final class PpgRespSampleTests: XCTestCase {
    func testV28CreatesPpgRespTable() async throws {
        let store = try await WhoopStore.inMemory()
        let tables = try await store.tableNames()
        XCTAssertTrue(tables.contains("ppgRespSample"))
    }

    func testPpgRespPrimaryKeyIsDeviceIdTs() async throws {
        let store = try await WhoopStore.inMemory()
        let cols = try await store.primaryKeyColumns("ppgRespSample")
        XCTAssertEqual(cols, ["deviceId", "ts"])
    }

    func testPpgRespInsertRoundTripAndDedup() async throws {
        let store = try await WhoopStore.inMemory()
        let streams = Streams(ppgResp: [
            PpgRespSample(ts: 1_780_916_150, bpm: 14.5, conf: 8.2),
            PpgRespSample(ts: 1_780_916_190, bpm: 15.0, conf: 12.1),
        ])
        _ = try await store.insert(streams, deviceId: "my-whoop")
        let n1 = try await store.ppgRespCountForTest()
        XCTAssertEqual(n1, 2)
        // Re-inserting the same (deviceId, ts) is idempotent — ON CONFLICT DO NOTHING.
        _ = try await store.insert(streams, deviceId: "my-whoop")
        let n2 = try await store.ppgRespCountForTest()
        XCTAssertEqual(n2, 2)
    }

    /// The dedicated reader returns rows in `[from, to]`, ascending by ts, un-COALESCEd — this stream
    /// is consumed as its own distinct array by `SleepStager.respRateFromPpg`, never merged with `resp`.
    func testPpgRespSamplesReaderFiltersAndOrders() async throws {
        let store = try await WhoopStore.inMemory()
        let dev = "my-whoop"
        let base = 1_780_000_000
        try await store.insert(Streams(ppgResp: [
            PpgRespSample(ts: base - 100, bpm: 20.0, conf: 5.0),  // before range
            PpgRespSample(ts: base + 40, bpm: 14.0, conf: 9.0),
            PpgRespSample(ts: base, bpm: 15.5, conf: 3.0),
            PpgRespSample(ts: base + 1000, bpm: 12.0, conf: 4.0), // after range
        ]), deviceId: dev)

        let samples = try await store.ppgRespSamples(deviceId: dev, from: base, to: base + 100, limit: 10)
        XCTAssertEqual(samples, [
            PpgRespSample(ts: base, bpm: 15.5, conf: 3.0),
            PpgRespSample(ts: base + 40, bpm: 14.0, conf: 9.0),
        ])
    }

    func testPpgRespSamplesReaderIsDeviceScoped() async throws {
        let store = try await WhoopStore.inMemory()
        let base = 1_780_000_000
        try await store.insert(Streams(ppgResp: [PpgRespSample(ts: base, bpm: 15.0, conf: 5.0)]),
                               deviceId: "device-a")
        try await store.insert(Streams(ppgResp: [PpgRespSample(ts: base, bpm: 99.0, conf: 5.0)]),
                               deviceId: "device-b")
        let samples = try await store.ppgRespSamples(deviceId: "device-a", from: base, to: base + 10, limit: 10)
        XCTAssertEqual(samples, [PpgRespSample(ts: base, bpm: 15.0, conf: 5.0)])
    }
}
