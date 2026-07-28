import XCTest
@testable import WhoopProtocol

/// #891 — a history offload must not silently discard a packet type nobody has mapped.
///
/// `extractHistoricalStreams` switches on four of the schema's sixteen packet types and `default:`s the
/// rest, and `rejectedHistoricalRecords` archives only type-47 ("Console (50) and METADATA frames have a
/// different type byte, so they never pass this gate — they are excluded by construction"). So before this
/// census an unmapped record was dropped twice and counted zero times, and the sync reported clean.
///
/// That is not a hypothetical gap. `HISTORICAL_IMU_DATA_STREAM(52)` is a banked raw-stream type the schema
/// already names and the funnel does not handle. It is also the shape #891's leading hypothesis predicts:
/// if a 5/MG banks ECG to flash after the Labrador toggles, the experiment that would find it cannot
/// currently produce a finding, because nothing would report the record.
///
/// These mirror the Android `HistoricalStreamsUnhandledTypeTest` 1:1 — SAME exclusions, SAME names.
final class UnhandledPacketTypeTests: XCTestCase {

    private let wallNow = Int(Date().timeIntervalSince1970) - 7 * 86_400

    /// A CRC-valid frame of `typeName` carrying nothing the funnel wants — the shape of a record whose
    /// envelope decodes but whose body this decoder has no rows for.
    private func frame(_ typeName: String) -> ParsedFrame {
        ParsedFrame(ok: true, typeName: typeName, seq: 0, cmdName: nil, crcOK: true,
                    lenBytes: 0, rawHex: "", fields: [], parsed: [:])
    }

    private func histFrame(unix: Int, bpm: Int = 60) -> ParsedFrame {
        ParsedFrame(
            ok: true, typeName: "HISTORICAL_DATA", seq: 24, cmdName: nil, crcOK: true,
            lenBytes: 0, rawHex: "", fields: [],
            parsed: ["hist_version": .int(24), "unix": .int(unix), "heart_rate": .int(bpm)]
        )
    }

    private func extract(_ frames: [ParsedFrame]) -> Streams {
        extractHistoricalStreams(frames, deviceClockRef: wallNow, wallClockRef: wallNow)
    }

    // MARK: - the gap this closes

    /// A schema-NAMED type the funnel has no case for is counted, not silently dropped. This is the
    /// banked-raw-stream type behind #891 hypothesis (b).
    func testNamedButUnhandledTypeIsCounted() {
        let st = extract([frame("HISTORICAL_IMU_DATA_STREAM"), frame("HISTORICAL_IMU_DATA_STREAM")])
        XCTAssertEqual(st.unhandledPacketTypes, ["HISTORICAL_IMU_DATA_STREAM": 2])
        XCTAssertTrue(st.isEmpty, "it still yields no rows — this is a census, not a decoder")
    }

    /// A byte the schema does not name at all renders `type<N>` (`Schema.typeName`) and is counted under
    /// that name, so the number reaches the strap log even with no vocabulary for it.
    func testUnmappedByteIsCountedUnderItsRenderedName() {
        let st = extract([frame("type53")])
        XCTAssertEqual(st.unhandledPacketTypes, ["type53": 1])
    }

    /// Several unmapped types in one chunk are counted separately, so a report names each.
    func testDistinctTypesAreTalliedSeparately() {
        let st = extract([frame("type53"), frame("HISTORICAL_IMU_DATA_STREAM"), frame("type53")])
        XCTAssertEqual(st.unhandledPacketTypes, ["type53": 2, "HISTORICAL_IMU_DATA_STREAM": 1])
    }

    // MARK: - and does not cry wolf

    /// METADATA and CONSOLE_LOGS reach `default:` on a normal sync and decode to zero rows BY DESIGN.
    /// Counting them would put a scary line in every strap log and train people to ignore the one that
    /// matters, so they are excluded by name.
    func testExpectedUnhandledTypesAreNotCounted() {
        let st = extract([frame("CONSOLE_LOGS"), frame("METADATA"), frame("CONSOLE_LOGS")])
        XCTAssertTrue(st.unhandledPacketTypes.isEmpty)
    }

    /// The exclusion set is pinned: it must stay exactly these two, and must stay in lockstep with Android.
    /// Adding to it is how this census would quietly stop reporting the thing it was built for.
    func testExclusionSetIsExactlyMetadataAndConsole() {
        XCTAssertEqual(expectedUnhandledHistoricalTypes, ["METADATA", "CONSOLE_LOGS"])
    }

    /// A normal offload logs nothing — the common case must stay silent or the signal is worthless.
    func testOrdinaryRecordsProduceNoCensus() {
        let st = extract([histFrame(unix: wallNow), histFrame(unix: wallNow + 1)])
        XCTAssertEqual(st.hr.count, 2)
        XCTAssertTrue(st.unhandledPacketTypes.isEmpty)
    }

    /// A CRC-failed frame is skipped before the switch and is NOT reported as an unhandled type — it is a
    /// different failure with its own archive (`rejectedHistoricalRecords`), and conflating them would
    /// misattribute a corrupt frame to an unknown firmware feature.
    func testCrcFailedFrameIsNotCountedAsUnhandled() {
        let bad = ParsedFrame(ok: true, typeName: "HISTORICAL_IMU_DATA_STREAM", seq: 0, cmdName: nil,
                              crcOK: false, lenBytes: 0, rawHex: "", fields: [], parsed: [:])
        XCTAssertTrue(extract([bad]).unhandledPacketTypes.isEmpty)
    }

    /// The census is transient diagnostics: it must not ride the Codable path that golden fixtures assert.
    func testCensusIsNotEncoded() throws {
        var st = Streams()
        st.unhandledPacketTypes = ["HISTORICAL_IMU_DATA_STREAM": 3]
        let json = String(data: try JSONEncoder().encode(st), encoding: .utf8) ?? ""
        XCTAssertFalse(json.contains("HISTORICAL_IMU_DATA_STREAM"))
        XCTAssertFalse(json.contains("unhandledPacketTypes"))
    }
}
