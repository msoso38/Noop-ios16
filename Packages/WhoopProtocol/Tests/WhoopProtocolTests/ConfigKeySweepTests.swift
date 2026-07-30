import XCTest
@testable import WhoopProtocol

/// #103: the key-existence ORACLE, the candidate catalogue's derivation discipline, and the batching
/// arithmetic that makes a catalogue larger than one run's budget truncate visibly instead of silently.
///
/// The catalogue is data, so these tests are about its INVARIANTS — no duplicates, nothing already known,
/// nothing already ruled out, nothing that cannot survive the 32-byte wire field — plus a golden string
/// the Kotlin twin asserts byte-for-byte so the two lists cannot drift.
final class ConfigKeySweepTests: XCTestCase {

    private var flagKeys: [String] { Whoop5Config.enableR22Sequence.map(\.name) }

    // MARK: - The oracle

    func testOracleReadsTheResultCodeAndNothingElse() {
        XCTAssertEqual(ConfigKeySweep.existence(resultCode: 1), .exists)      // SUCCESS
        XCTAssertEqual(ConfigKeySweep.existence(resultCode: 0), .unknown)     // FAILURE
        XCTAssertEqual(ConfigKeySweep.existence(resultCode: 2), .inconclusive) // PENDING
        XCTAssertEqual(ConfigKeySweep.existence(resultCode: 3), .inconclusive) // UNSUPPORTED
        XCTAssertEqual(ConfigKeySweep.existence(resultCode: 9), .inconclusive)
    }

    /// WHOOP 4.0 carries no labelled result code here, so the oracle must decline rather than read the
    /// absence as "the key does not exist".
    func testAbsentResultCodeIsInconclusiveNotUnknown() {
        XCTAssertEqual(ConfigKeySweep.existence(resultCode: nil), .inconclusive)
        XCTAssertNotEqual(ConfigKeySweep.existence(resultCode: nil), .unknown)
    }

    func testExistenceLabelsAreStableAcrossPlatforms() {
        XCTAssertEqual(ConfigKeySweep.Existence.exists.label, "exists")
        XCTAssertEqual(ConfigKeySweep.Existence.unknown.label, "unknown")
        XCTAssertEqual(ConfigKeySweep.Existence.inconclusive.label, "inconclusive")
    }

    // MARK: - Enumeration verbs

    func testEnumerationOpcodesAreTheDeviceConfigPair() {
        XCTAssertEqual(ConfigKeySweep.startDeviceConfigKeyExchangeCmd, 115)   // 0x73
        XCTAssertEqual(ConfigKeySweep.sendNextDeviceConfigCmd, 116)           // 0x74
        // The body is the bare inner b3 byte, exactly as the 117/118 pair sends it.
        XCTAssertEqual(ConfigKeySweep.enumerationRequestBody, [0x01])
    }

    // MARK: - Catalogue invariants

    func testCatalogueHasNoDuplicateNames() {
        let keys = ConfigKeySweep.catalogue.map(\.key)
        XCTAssertEqual(Set(keys).count, keys.count, "a duplicate spends a round-trip for no information")
    }

    /// A candidate that NOOP already writes is not a candidate — it is a known key, and asking it in the
    /// candidate phase would inflate an "exists" count with something the probe already knew.
    func testCatalogueNeverRepeatsAKeyNOOPAlreadyWrites() {
        for c in ConfigKeySweep.catalogue {
            XCTAssertFalse(flagKeys.contains(c.key), "\(c.key) is already in enableR22Sequence")
            XCTAssertNotEqual(c.key, DeviceConfigReadProbe.deviceConfigDiscoveryKey)
        }
    }

    /// The eight plain-English oxygen names already came back FAILURE on a real strap. Re-asking them
    /// would spend round-trips to re-learn a known negative.
    func testCatalogueNeverRepeatsARetiredName() {
        for c in ConfigKeySweep.catalogue {
            XCTAssertFalse(ConfigKeySweep.retiredKeys.contains(c.key),
                           "\(c.key) already answered FAILURE — it belongs in retiredKeys, not the catalogue")
        }
        XCTAssertEqual(Set(ConfigKeySweep.retiredKeys).count, ConfigKeySweep.retiredKeys.count)
    }

    /// Names are TRUNCATED to 32 bytes on the wire, not rejected, so two candidates sharing a 32-byte
    /// prefix would be indistinguishable — and a name longer than the field could never match anyway.
    func testEveryCandidateFitsTheWireNameField() {
        for c in ConfigKeySweep.catalogue {
            let bytes = Array(c.key.utf8)
            XCTAssertLessThanOrEqual(bytes.count, DeviceConfigReadProbe.nameFieldBytes, "\(c.key) is too long")
            XCTAssertFalse(bytes.isEmpty)
            for b in bytes {
                XCTAssertTrue((97...122).contains(b) || (48...57).contains(b) || b == 95,
                              "\(c.key) is not lowercase snake_case, which every confirmed key is")
            }
        }
    }

    /// The derivation is the product: a candidate whose family is unexplained is a guess with no argument
    /// behind it, and a negative result on it rules nothing out.
    func testEveryDerivationIsUsedAndTitled() {
        for d in ConfigKeySweep.Derivation.allCases {
            XCTAssertTrue(ConfigKeySweep.catalogue.contains { $0.derivation == d },
                          "\(d.rawValue) has a title but no candidates")
            XCTAssertFalse(d.title.isEmpty)
        }
    }

    /// Only the `whoop_…` family belongs to the device-config namespace — that prefix is the one shape a
    /// confirmed device-config key has.
    func testNamespaceAssignmentFollowsTheOnlyConfirmedDeviceConfigShape() {
        for c in ConfigKeySweep.catalogue {
            if c.namespace == .deviceConfig {
                XCTAssertTrue(c.key.hasPrefix("whoop_"), "\(c.key) is asked of 121 but is not whoop_-shaped")
            } else {
                XCTAssertFalse(c.key.hasPrefix("whoop_"), "\(c.key) is whoop_-shaped but asked of 128")
            }
        }
    }

    /// The single highest-value entry: v7 is the hole in an OBSERVED contiguous series, so a SUCCESS on it
    /// would prove the oracle finds keys NOOP does not already know.
    func testTheObservedSeriesHoleIsInTheCatalogue() {
        let keys = ConfigKeySweep.catalogue.map(\.key)
        XCTAssertTrue(keys.contains("enable_r22_v7_packets"))
        // …and it is genuinely a hole: NOOP writes v2…v6 and v8, never v7.
        XCTAssertFalse(flagKeys.contains("enable_r22_v7_packets"))
        XCTAssertTrue(flagKeys.contains("enable_r22_v6_packets"))
        XCTAssertTrue(flagKeys.contains("enable_r22_v8_packets"))
    }

    // MARK: - Batching

    func testTodaysCatalogueFitsInOneRun() {
        XCTAssertLessThanOrEqual(ConfigKeySweep.catalogue.count, ConfigKeySweep.maxKeysPerRun)
        let b = ConfigKeySweep.batch(from: 0)
        XCTAssertEqual(b.start, 0)
        XCTAssertEqual(b.candidates.count, ConfigKeySweep.catalogue.count)
        XCTAssertEqual(b.remaining, 0)
        XCTAssertTrue(b.completesCatalogue)
        XCTAssertEqual(b.nextCursor, 0, "a completed catalogue restarts the next run at the top")
    }

    /// The property the sweep exists to guarantee: a catalogue larger than one run's budget is truncated
    /// VISIBLY (`remaining` is non-zero) and resumed from `nextCursor`, never silently cut.
    func testAnOversizeCatalogueTruncatesVisiblyAndResumes() {
        let total = ConfigKeySweep.catalogue.count
        let first = ConfigKeySweep.batch(from: 0, limit: 10)
        XCTAssertEqual(first.candidates.count, 10)
        XCTAssertEqual(first.start, 0)
        XCTAssertEqual(first.remaining, total - 10)
        XCTAssertFalse(first.completesCatalogue)
        XCTAssertEqual(first.nextCursor, 10)

        let second = ConfigKeySweep.batch(from: first.nextCursor, limit: 10)
        XCTAssertEqual(second.start, 10)
        XCTAssertEqual(second.candidates.first?.key, ConfigKeySweep.catalogue[10].key)
        XCTAssertEqual(second.remaining, total - 20)

        // Walk to the end: the union of every slice is the whole catalogue, each name exactly once.
        var seen: [String] = []
        var cursor = 0
        repeat {
            let b = ConfigKeySweep.batch(from: cursor, limit: 10)
            seen += b.candidates.map(\.key)
            cursor = b.nextCursor
        } while cursor != 0
        XCTAssertEqual(seen, ConfigKeySweep.catalogue.map(\.key))
    }

    func testAStaleOrNonsenseCursorRestartsRatherThanWastingARun() {
        XCTAssertEqual(ConfigKeySweep.batch(from: -1).start, 0)
        XCTAssertEqual(ConfigKeySweep.batch(from: 10_000).start, 0)
        XCTAssertEqual(ConfigKeySweep.batch(from: ConfigKeySweep.catalogue.count).start, 0)
        XCTAssertFalse(ConfigKeySweep.batch(from: -1).candidates.isEmpty)
    }

    /// A slice never wraps mid-batch, so one run can never ask the same name twice.
    func testASliceNeverWrapsWithinOneRun() {
        let b = ConfigKeySweep.batch(from: ConfigKeySweep.catalogue.count - 3, limit: 10)
        XCTAssertEqual(b.candidates.count, 3)
        XCTAssertEqual(b.nextCursor, 0)
        XCTAssertEqual(Set(b.candidates.map(\.key)).count, b.candidates.count)
    }

    // MARK: - Cross-platform lockstep

    /// The catalogue is duplicated in Kotlin by hand, so pin it as one string the Kotlin twin asserts
    /// byte-for-byte. A name added on one platform and not the other fails HERE, not on a user's strap.
    func testCatalogueIsPinnedForTheKotlinTwin() {
        let pinned = ConfigKeySweep.catalogue
            .map { "\($0.derivation.rawValue):\($0.namespace.rawValue):\($0.key)" }
            .joined(separator: "\n")
        XCTAssertEqual(pinned, ConfigKeySweepTests.goldenCatalogue)
        XCTAssertEqual(ConfigKeySweep.catalogue.count, 54)
        XCTAssertEqual(ConfigKeySweep.retiredKeys.joined(separator: "\n"),
                       ConfigKeySweepTests.goldenRetired)
    }

    static let goldenCatalogue = """
    sigSeries:featureFlag:enable_sig1
    sigSeries:featureFlag:enable_sig2
    sigSeries:featureFlag:enable_sig3
    sigSeries:featureFlag:enable_sig4
    sigSeries:featureFlag:enable_sig5
    sigSeries:featureFlag:enable_sig6
    sigSeries:featureFlag:enable_sig7
    sigSeries:featureFlag:enable_sig8
    sigSeries:featureFlag:enable_sig9
    sigSeries:featureFlag:enable_sig10
    sigSeries:featureFlag:enable_sig13
    sigSeries:featureFlag:enable_sig14
    sigSeries:featureFlag:enable_sig15
    sigSeries:featureFlag:enable_sig16
    sigSeries:featureFlag:enable_sig11
    sigSeries:featureFlag:enable_sig12_during_sleep
    r22VersionGaps:featureFlag:enable_r22_v1_packets
    r22VersionGaps:featureFlag:enable_r22_v7_packets
    r22VersionGaps:featureFlag:enable_r22_v9_packets
    r22VersionGaps:featureFlag:enable_r22_v10_packets
    revisionSlot:featureFlag:enable_r7_packets
    revisionSlot:featureFlag:enable_r10_packets
    revisionSlot:featureFlag:enable_r11_packets
    revisionSlot:featureFlag:enable_r16_packets
    revisionSlot:featureFlag:enable_r17_packets
    revisionSlot:featureFlag:enable_r20_packets
    revisionSlot:featureFlag:enable_r21_packets
    revisionSlot:featureFlag:enable_pip_r26_packets
    opticalAfe:featureFlag:enable_optical_data
    opticalAfe:featureFlag:enable_optical_packets
    opticalAfe:featureFlag:make_optical_visible
    opticalAfe:featureFlag:enable_afe_packets
    opticalAfe:featureFlag:red_hw_switching
    opticalAfe:featureFlag:green_hw_switching
    labradorEcg:featureFlag:enable_labrador_packets
    labradorEcg:featureFlag:enable_labrador_raw_save
    labradorEcg:featureFlag:enable_labrador_filtered
    labradorEcg:featureFlag:make_labrador_visible
    labradorEcg:featureFlag:enable_ecg_packets
    researchHighRate:featureFlag:enable_research_packets
    researchHighRate:featureFlag:make_research_visible
    researchHighRate:featureFlag:enable_raw_packets
    researchHighRate:featureFlag:enable_hrfm_packets
    sigprocOxygen:featureFlag:make_spo2_visible
    sigprocOxygen:featureFlag:enable_spo2_during_sleep
    sigprocOxygen:featureFlag:enable_spo2_gen5
    sigprocOxygen:featureFlag:spo2_ch_switching
    sigprocOxygen:featureFlag:disable_spo2_packets
    sigprocOxygen:featureFlag:enable_sigproc_spo2
    sigprocOxygen:featureFlag:sigproc_spo2_during_sleep
    deviceConfigNamespace:deviceConfig:whoop_live_hrv_in_adv_ind_pkt
    deviceConfigNamespace:deviceConfig:whoop_live_spo2_in_adv_ind_pkt
    deviceConfigNamespace:deviceConfig:whoop_live_temp_in_adv_ind_pkt
    deviceConfigNamespace:deviceConfig:whoop_live_ecg_in_adv_ind_pkt
    """

    static let goldenRetired = """
    enable_spo2
    enable_spo2_packets
    spo2_enable
    enable_blood_oxygen
    blood_oxygen_enable
    enable_pulse_ox
    enable_oxygen_packets
    spo2_subscription_enabled
    """
}
