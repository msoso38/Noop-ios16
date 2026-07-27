import Foundation

/// Formats the WHOOP MG ECG ("Labrador") turn-on attempt into one readable, copyable report — the
/// per-command COMMAND_RESPONSE result codes, whether any ECG-shaped packet actually arrived, and the
/// verdict on the ONE gating question the client cannot answer: whether the strap's firmware
/// `WhoopDeviceFlag` layer returns `blockedByDeviceFlags` for this feature.
///
/// Pure + deterministic, so `swift test` covers it with no strap. Structurally the twin of
/// `BodyLocationProbe` / `ExtendedBatteryProbe`.
///
/// Deliberately NOT mirrored in Kotlin: this formats the output of an app-layer probe, and the Android
/// client has no ECG app layer — it takes the decoder (`com.noop.protocol.Whoop5Ecg`) only. The parity
/// contract binds decoders and stored values, and nothing here decodes or stores anything. If Android
/// ever grows the probe, this file is what it mirrors.
///
/// ## How the block is detected
///
/// The client-side gates are known and satisfiable: the hardware gate is `gen5MG` (non-bypassable, and
/// an MG owner satisfies it), while the entitlement and feature-flag gates live entirely in the app.
/// The remaining unknown is a FIRMWARE gate, so it can only be answered by what the strap does. Three
/// signals separate the cases, and the report states which one fired:
///
///   1. `UNSUPPORTED(3)` — the firmware does not implement the opcode at all. Not a flag block; a
///      different (and more final) answer.
///   2. `FAILURE(0)` — the firmware KNOWS the opcode and REFUSES to run it. That is the single-frame
///      signature most consistent with a device-flag block, and the strongest evidence a one-shot probe
///      can produce.
///   3. `SUCCESS(1)` on every command, but ZERO ECG packets across the capture window — a silent
///      no-op: acknowledged and then not honoured. Also consistent with a flag block, and the reason
///      this probe counts arriving packets instead of trusting the acks.
///
/// A block is never inferred from silence alone: no reply at all is reported as exactly that, because
/// an in-flight sync or a dropped notification produces the same silence.
public enum Whoop5EcgProbe {

    /// The 5/MG COMMAND_RESPONSE result code, at frame[12] — the same offset `BodyLocationProbe` reads.
    public static let resultCodeOffset = 12

    public enum CommandOutcome: Equatable, Sendable {
        case success
        case failure
        case pending
        case unsupported
        case unmapped(Int)
        /// No COMMAND_RESPONSE arrived inside the probe window.
        case noReply

        public var token: String {
            switch self {
            case .success: return "SUCCESS(1)"
            case .failure: return "FAILURE(0)"
            case .pending: return "PENDING(2)"
            case .unsupported: return "UNSUPPORTED(3)"
            case .unmapped(let v): return "result\(v)"
            case .noReply: return "no reply"
            }
        }
    }

    /// Read the result code out of a 5/MG COMMAND_RESPONSE frame. nil when the frame is too short.
    public static func outcome(frame: [UInt8]) -> CommandOutcome? {
        guard frame.count > resultCodeOffset else { return nil }
        switch Int(frame[resultCodeOffset]) {
        case 0: return .failure
        case 1: return .success
        case 2: return .pending
        case 3: return .unsupported
        case let other: return .unmapped(other)
        }
    }

    /// One command in the turn-on sequence and what came back.
    public struct Step: Equatable, Sendable {
        public let label: String
        public let outcome: CommandOutcome
        /// The reply frame as hex, when one arrived.
        public let replyHex: String?

        public init(label: String, outcome: CommandOutcome, replyHex: String? = nil) {
            self.label = label
            self.outcome = outcome
            self.replyHex = replyHex
        }
    }

    /// The gate verdict, kept separate from the report text so it is assertable in a test.
    public enum Verdict: Equatable, Sendable {
        /// Frames that PASS THE STRUCTURAL TRIAGE arrived. Not proof: the triage is a heuristic over
        /// four booleans, three enum ranges and a length agreement (see
        /// `Whoop5Ecg.plausibleFilteredPayload`), run against ordinary live 5/MG traffic, so an
        /// unrelated packet can match. The verdict says "candidate", and confirming it needs the raw
        /// bytes in the log — never this count alone.
        case ecgCandidatesArrived(packets: Int)
        /// At least one command came back FAILURE: the opcode exists and execution was refused.
        case blockedByDeviceFlagsLikely(commands: [String])
        /// Everything acked SUCCESS and nothing ever arrived.
        case acceptedButSilent(windowSeconds: Int)
        /// The firmware rejected an opcode as unimplemented.
        case opcodeUnsupported(commands: [String])
        /// Nothing replied at all.
        case noReplies
        /// A mixed or unmapped set of codes that none of the above describes.
        case inconclusive

        public var headline: String {
            switch self {
            case .ecgCandidatesArrived(let packets):
                return "\(packets) frame(s) matched the ECG structural triage. That is a CANDIDATE, not proof: "
                    + "the triage is a shape heuristic and unrelated traffic can match it. Confirm against the "
                    + "raw bytes below before concluding anything about whether the feature is blocked."
            case .blockedByDeviceFlagsLikely(let commands):
                return "LIKELY blockedByDeviceFlags — the firmware returned FAILURE for \(commands.joined(separator: ", ")): "
                    + "it knows the opcode and refused to run it."
            case .acceptedButSilent(let windowSeconds):
                return "Accepted but SILENT — every command returned SUCCESS yet no ECG packet arrived in \(windowSeconds)s. "
                    + "Consistent with a device-flag block applied as a silent no-op."
            case .opcodeUnsupported(let commands):
                return "Opcode UNSUPPORTED on this firmware for \(commands.joined(separator: ", ")) — "
                    + "not a device-flag block, the command is not implemented."
            case .noReplies:
                return "No COMMAND_RESPONSE at all — the strap answered nothing. Silence is not evidence of a block "
                    + "(a mid-flight sync or a missed notification looks identical); retry idle."
            case .inconclusive:
                return "INCONCLUSIVE — the result codes do not match a known pattern. The raw replies below are the record."
            }
        }
    }

    /// Classify the run.
    ///
    /// Order matters, and it puts the ATTESTED signals first: the COMMAND_RESPONSE result codes are
    /// real wire semantics (the same 0/1/2/3 the haptics rejection capture pinned in #48), whereas the
    /// packet count comes from a shape heuristic that unrelated traffic can trip. So an explicit
    /// FAILURE or UNSUPPORTED from the firmware outranks a candidate count, and candidates are the
    /// fallback rather than an override. Nothing is lost either way — `report` always prints the
    /// candidate count alongside the verdict.
    ///
    /// "Unsupported" is reported as itself rather than folded into the block case, and silence is never
    /// reported as a block.
    public static func verdict(steps: [Step], ecgPacketsSeen: Int, windowSeconds: Int) -> Verdict {
        let failures = steps.filter { $0.outcome == .failure }.map(\.label)
        if !failures.isEmpty { return .blockedByDeviceFlagsLikely(commands: failures) }
        let unsupported = steps.filter { $0.outcome == .unsupported }.map(\.label)
        if !unsupported.isEmpty { return .opcodeUnsupported(commands: unsupported) }
        if ecgPacketsSeen > 0 { return .ecgCandidatesArrived(packets: ecgPacketsSeen) }
        guard !steps.isEmpty else { return .noReplies }
        if steps.allSatisfy({ $0.outcome == .noReply }) { return .noReplies }
        if steps.allSatisfy({ $0.outcome == .success }) {
            return .acceptedButSilent(windowSeconds: windowSeconds)
        }
        return .inconclusive
    }

    /// The full report: verdict, per-command outcomes, the ECG-packet tally, and the raw replies.
    ///
    /// `candidateFrames` are the type/length lines for frames that passed the structural triage in
    /// `Whoop5Ecg.plausibleFilteredPayload` — the empirical answer to "which packet type do these arrive
    /// under", which no table in this repo yet holds.
    public static func report(steps: [Step],
                              ecgPacketsSeen: Int,
                              candidateFrames: [String],
                              windowSeconds: Int) -> String {
        var sb = ""
        sb += "WHOOP MG ECG (Labrador) TURN-ON PROBE\n"
        sb += "Verdict: \(verdict(steps: steps, ecgPacketsSeen: ecgPacketsSeen, windowSeconds: windowSeconds).headline)\n"
        sb += "\nCommands sent:\n"
        if steps.isEmpty {
            sb += "  (none)\n"
        } else {
            for step in steps {
                sb += "  \(step.label): \(step.outcome.token)\n"
            }
        }
        sb += "\nECG-shaped packets seen in \(windowSeconds)s: \(ecgPacketsSeen)\n"
        if candidateFrames.isEmpty {
            sb += "Candidate packet types: none — no frame passed the structural triage.\n"
        } else {
            sb += "Candidate packet types (structural triage only, NOT a confirmed mapping):\n"
            for line in candidateFrames { sb += "  \(line)\n" }
        }
        let replies = steps.compactMap { step in step.replyHex.map { "  \(step.label): \($0)" } }
        if !replies.isEmpty {
            sb += "\nRaw replies:\n"
            sb += replies.joined(separator: "\n") + "\n"
        }
        sb += "\nThis is unvalidated instrumentation, not a medical measurement or a diagnosis.\n"
        return sb
    }
}
