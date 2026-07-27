import XCTest
@testable import WhoopProtocol

/// The v18 fields the decoder produced and `extractHistoricalStreams` used to DISCARD.
///
/// Every expectation below is read off the SAME real worn 5/MG frame `Whoop5HistoricalTests` uses (and
/// that `decoder_oracle.json` pins on both platforms), so nothing here asserts a value the decoder was
/// not already independently proven to produce — these tests are about the STORAGE funnel, which is
/// where the loss happened.
final class DeepCaptureChannelsTests: XCTestCase {

    /// A real type-47 HISTORICAL_DATA v18 frame (worn WHOOP 5, captured 2026-06-08). Same fixture as
    /// `Whoop5HistoricalTests.historicalHex`.
    private let v18Hex =
        "aa01740001003fb12f1280733d8401b69f266a66460066025a0265020000000000007b0a8d656463ff0012163cf6a439bf2924fd3ed763fe3e3200aa000000000000000000f7000901f10b0007010c020c00000000000000000000000000000000000000000000000100656f1e1e0000009d61a7c00000003e862817"

    /// A synthetic WHOOP 4.0 v24 record — same fixture as `HistoricalV24Tests`.
    private let v24Hex =
        "aa5a008e2f18000000000000f153650000000000003f0152030000000000000000dc053075" +
        "000000cdcc4c3dcdcccc3d5a657e3f00000040cdcc4c3dcdcccc3d5a657e3f504668428403" +
        "200364006400b80bb80b000000000000c25c1a88"

    private func bytes(_ s: String) -> [UInt8] {
        var out = [UInt8](); out.reserveCapacity(s.count / 2); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2)
            out.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return out
    }

    /// The fixture frame, optionally with one byte replaced AND its CRC32 trailer recomputed. The reseal
    /// matters: `extractHistoricalStreams` skips any frame whose CRC fails, so a mutated frame would
    /// otherwise silently test nothing at all.
    private func v18Frame(mutating index: Int? = nil, to value: UInt8 = 0) -> [UInt8] {
        var b = bytes(v18Hex)
        guard let i = index else { return b }
        b[i] = value
        let payloadEnd = b.count - 4
        let c = crc32(b, 8, payloadEnd)
        for k in 0..<4 { b[payloadEnd + k] = UInt8(truncatingIfNeeded: c >> (8 * UInt32(k))) }
        return b
    }

    /// The record's own unix; used as both clock refs so the FIX #72 correction is a no-op.
    private let ts = 1_780_916_150

    private func extract(_ frames: [[UInt8]]) -> Streams {
        extractHistoricalStreams(frames.map { parseFrame($0, family: .whoop5) },
                                 deviceClockRef: ts, wallClockRef: ts)
    }

    // MARK: - The four named channels

    /// `dynamic_acceleration@41` now rides the gravity row for the same second instead of being dropped.
    func testDynamicAccelerationRidesTheGravityRow() throws {
        let g = try XCTUnwrap(extract([v18Frame()]).gravity.first)
        XCTAssertEqual(g.dynAccel ?? .nan, 0.0091596, accuracy: 1e-6)
        // Stored BESIDE the vector, never instead of it — the stager still reads x/y/z.
        XCTAssertEqual(sqrt(g.x * g.x + g.y * g.y + g.z * g.z), 1.0, accuracy: 0.05)
    }

    /// The two auxiliary thermal channels ride the primary skin-temp row.
    func testAuxThermalChannelsRideTheSkinTempRow() throws {
        let s = try XCTUnwrap(extract([v18Frame()]).skinTemp.first)
        XCTAssertEqual(s.raw, 3057)          // primary, unchanged (°C = raw/100 = 30.6)
        XCTAssertEqual(s.aux1Raw, 247)       // @69, °C = raw/10 = 24.7
        XCTAssertEqual(s.aux2Raw, 265)       // @71, °C = raw/10 = 26.5
    }

    /// The WHOLE @81 byte survives, and `state` stays exactly its high nibble.
    func testSleepStateCarriesTheWholeFlagByteAndStateIsUnchanged() throws {
        // 0xE9 = 1110 1001: onwrist(b0-1)=1, wake_quality(b2-3)=2, sleep_state(b4-5)=2, reserved(b6-7)=3.
        // The reserved bits are the point: they read 0 on every real capture and have NO interpretation,
        // so a per-nibble store would make them permanently unrecoverable.
        let s = try XCTUnwrap(extract([v18Frame(mutating: 81, to: 0xE9)]).sleepState.first)
        XCTAssertEqual(s.rawByte, 0xE9)
        XCTAssertEqual(s.state, 2, "state must remain (rawByte >> 4) & 3 — #175 behaviour is bit-identical")
        let raw = try XCTUnwrap(s.rawByte)
        XCTAssertEqual((raw >> 4) & 3, s.state)
        XCTAssertEqual(raw & 3, 1)              // onwrist
        XCTAssertEqual((raw >> 2) & 3, 2)       // wake_quality
        XCTAssertNotEqual(raw & 0xC0, 0, "the fixture must actually exercise the reserved bits")
    }

    /// A zero @81 byte is a REAL reading (worn daytime wake), not an absent one — it must be carried.
    func testZeroFlagByteIsCarriedNotTreatedAsAbsent() throws {
        let s = try XCTUnwrap(extract([v18Frame()]).sleepState.first)
        XCTAssertEqual(s.rawByte, 0)
        XCTAssertEqual(s.state, 0)
    }

    // MARK: - The remaining v18 slots

    /// Every slot the funnel used to drop is banked, verbatim, under the decoder's own name.
    func testEveryRemainingV18SlotIsCollected() throws {
        let a = try XCTUnwrap(extract([v18Frame()]).v18Aux.first)
        XCTAssertEqual(a.ts, ts)
        XCTAssertEqual(a.recordIndex, 25_443_699)     // @11  u32
        XCTAssertEqual(a.rrCount, 2)                  // @23  u8
        XCTAssertEqual(a.cardiacFlags, 0)             // @33  u8
        XCTAssertEqual(a.rawU16At36, 25_997)          // @36  u16 raw (see testRawU16At36IsNotASubBpmHR)
        XCTAssertEqual(a.rrPacked, 25_444)            // @38  u16
        XCTAssertEqual(a.cardiacStatus, 255)          // @40  u8
        XCTAssertEqual(a.stepCadence, 170)            // @59  u8
        XCTAssertEqual(a.statusWord, 1_792)           // @75  u16
        XCTAssertEqual(a.statusWord1, 3_073)          // @77  u16
        XCTAssertEqual(a.statusWord2, 3_074)          // @79  u16
        XCTAssertEqual(a.auxByte82, 0)                // @82  u8 (raw; the gated 70-100 view is derived)
        XCTAssertEqual(a.opticalBaseline106, 28_517)  // @106 u16
        XCTAssertEqual(a.opticalAmpA, 30)             // @108 u8
        XCTAssertEqual(a.opticalAmpB, 30)             // @109 u8
        // The SAME decimal literal the Kotlin suite asserts. 0xC0A7619D has bit 31 set, so a 32-bit
        // decoded domain reads it as -1_062_772_323 — this is the value, not the blob, that has to match.
        XCTAssertEqual(a.unknownF32Bits, 3_232_194_973)  // @113 raw 32-bit pattern, unsigned domain
        XCTAssertEqual(a.unknownF32At113 ?? .nan, -5.2307, accuracy: 0.001)
    }

    /// `@36` is banked RAW, under a name that makes no claim, because the "higher-precision HR" reading
    /// the decoder key still carries is arithmetically empty (#845).
    ///
    /// A LE u16 at 36 is `frame[37] << 8 | frame[36]`, so `value / 256` is exactly
    /// `frame[37] + frame[36]/256`. The integer part IS the HR-like byte at @37 — the entire source of
    /// the 0.989 correlation with `heart_rate@22` — and the "sub-bpm fraction" is the unpinned byte at
    /// @36 over 256. This test pins that decomposition on the same fixture the old claim was read off, so
    /// the disproof is enforced rather than merely written down.
    func testRawU16At36IsNotASubBpmHR() throws {
        let f = parseFrame(v18Frame(), family: .whoop5)
        let a = try XCTUnwrap(extract([v18Frame()]).v18Aux.first)
        let v = try XCTUnwrap(a.rawU16At36)
        XCTAssertEqual(v, 25_997)
        // The two bytes, apart.
        XCTAssertEqual(a.byteAt36, 141)                       // the unpinned low byte
        XCTAssertEqual(a.byteAt37, 101)                       // the HR-like high byte
        XCTAssertEqual(v, 101 * 256 + 141)
        // "101.55 bpm" is 101 (the @37 byte) + 141/256 (an unrelated byte read as a fraction).
        XCTAssertEqual(Double(v) / 256.0, 101.0 + 141.0 / 256.0, accuracy: 1e-12)
        // And it is not even the measured HR: @22 reads 102 on this frame while @37 reads 101, so the
        // high byte is HR-LIKE, not a copy — which is exactly why it is banked raw and named for nothing.
        XCTAssertEqual(f.parsed["heart_rate"]?.intValue, 102)
        XCTAssertNotEqual(a.byteAt37, f.parsed["heart_rate"]?.intValue)
        // The slot's own name must stay claim-free even though the decoder key it reads is not.
        XCTAssertEqual(V18AuxSlot.rawU16At36.decoderKey, "hr_fixed_8_8")
    }

    /// The decoded domain must hold an unsigned 32-bit slot. Swift's `Int` is 64-bit on every supported
    /// target and the Kotlin twin carries `Long` for exactly this reason; a 32-bit domain on either side
    /// silently flips a bit-31 slot negative while the stored bytes stay identical.
    func testDecodedDomainHoldsAFullU32() {
        XCTAssertGreaterThanOrEqual(Int.bitWidth, 64)
        let widest = V18AuxSlot.allCases.map(\.width).max() ?? 0
        XCTAssertEqual(widest, 4)
        let s = V18AuxSample(ts: 0, recordIndex: 0xFFFF_FFFF, unknownF32Bits: 0xC0A7_619D)
        XCTAssertEqual(s.recordIndex, 4_294_967_295)
        XCTAssertEqual(s.unknownF32Bits, 3_232_194_973)
    }

    /// The slot enum and the struct's ordered view cannot drift: the codec indexes one by the other.
    func testSlotValuesOrderMatchesTheSlotEnum() {
        let a = V18AuxSample(ts: 1, recordIndex: 10, rrCount: 11, cardiacFlags: 12, rawU16At36: 13,
                             rrPacked: 14, cardiacStatus: 15, stepCadence: 16, statusWord: 17,
                             statusWord1: 18, statusWord2: 19, auxByte82: 20, opticalBaseline106: 21,
                             opticalAmpA: 22, opticalAmpB: 23, unknownF32Bits: 24)
        XCTAssertEqual(a.slotValues.count, V18AuxSlot.allCases.count)
        // Slot i must round-trip to position i — this is what makes the persisted bitmap meaningful.
        XCTAssertEqual(a.slotValues, Array(10...24).map { Optional($0) })
        XCTAssertEqual(V18AuxSample(ts: 1, slotValues: a.slotValues), a)
        // Enum raw values must be a dense 0..<n range in declaration order (bitmap bit positions).
        XCTAssertEqual(V18AuxSlot.allCases.map(\.rawValue), Array(0..<V18AuxSlot.allCases.count))
    }

    /// A short slot array (a blob from an older build with fewer slots) leaves the tail nil, never 0.
    func testShortSlotArrayLeavesTheTailAbsent() {
        let a = V18AuxSample(ts: 1, slotValues: [7, 8])
        XCTAssertEqual(a.recordIndex, 7)
        XCTAssertEqual(a.rrCount, 8)
        XCTAssertNil(a.unknownF32Bits)
        XCTAssertNil(a.opticalAmpA)
    }

    // MARK: - What must NOT change

    /// WHOOP 4.0 is untouched: no aux rows, and the new columns stay nil on the streams it does produce.
    func testWhoop4V24RecordAddsNothing() throws {
        let s = extractHistoricalStreams([parseFrame(bytes(v24Hex))],
                                         deviceClockRef: 1_700_000_000, wallClockRef: 1_700_000_000)
        XCTAssertTrue(s.v18Aux.isEmpty, "a 4.0 v24 record must bank no aux row")
        XCTAssertNil(s.gravity.first?.dynAccel)
        XCTAssertNil(s.skinTemp.first?.aux1Raw)
        XCTAssertNil(s.skinTemp.first?.aux2Raw)
        // The 4.0 schema DOES emit rr_count, which is why the aux gate keys on hist_version rather than
        // on which keys happen to be present — a presence test would bank a near-empty row per 4.0 second.
        XCTAssertEqual(parseFrame(bytes(v24Hex)).parsed["rr_count"]?.intValue, 1)
    }

    /// The v18 gate is explicit: only layout 18 banks aux rows.
    func testAuxCollectionIsGatedOnLayoutV18() {
        XCTAssertEqual(parseFrame(v18Frame(), family: .whoop5).parsed["hist_version"]?.intValue, 18)
        // A v26 (optical PPG) record carries no v18 biometric slots at all.
        let v26 = extract([v18Frame(mutating: 9, to: 26)])
        XCTAssertTrue(v26.v18Aux.isEmpty)
    }

    /// The #520 whole-session diagnostic still folds the same field it always did — the new per-second
    /// column is carried BESIDE it, not in place of it.
    func testDynAccelDiagnosticStillFoldsAlongsideTheNewColumn() {
        let s = extract([v18Frame()])
        XCTAssertEqual(s.dynAccel.count, 1)
        XCTAssertEqual(s.gravity.count, 1)
        XCTAssertNotNil(s.gravity.first?.dynAccel)
    }

    /// Everything with an existing durable home is banked once, not twice.
    func testNoSlotDuplicatesAnAlreadyPersistedField() {
        let persistedElsewhere: Set<String> = [
            "unix", "heart_rate", "rr_intervals", "gravity_x", "gravity_y", "gravity_z",
            "dynamic_acceleration", "skin_temp_raw", "temp_aux_1_raw", "temp_aux_2_raw",
            "step_motion_counter", "activity_class", "motion_wear_quality", "sleep_state",
            "sleep_state_byte", "spo2_candidate_82", "ppg_waveform",
        ]
        for slot in V18AuxSlot.allCases {
            XCTAssertFalse(persistedElsewhere.contains(slot.decoderKey),
                           "\(slot.decoderKey) already has a durable home — banking it twice is waste")
        }
    }
}
