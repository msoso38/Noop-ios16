import WidgetKit
import SwiftUI
import StrandDesign

/// Timeline entry backed by the latest `WidgetSnapshot` the app published into the App Group.
struct NOOPEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
}

struct NOOPProvider: TimelineProvider {
    func placeholder(in context: Context) -> NOOPEntry {
        NOOPEntry(date: Date(), snapshot: .placeholder)
    }

    func getSnapshot(in context: Context, completion: @escaping (NOOPEntry) -> Void) {
        completion(NOOPEntry(date: Date(), snapshot: WidgetSnapshot.load() ?? .placeholder))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NOOPEntry>) -> Void) {
        let snap = WidgetSnapshot.load() ?? .placeholder
        // Refresh roughly every 15 minutes; the app also forces a reload when it publishes fresh data.
        let next = Calendar.current.date(byAdding: .minute, value: 15, to: Date()) ?? Date().addingTimeInterval(900)
        completion(Timeline(entries: [NOOPEntry(date: Date(), snapshot: snap)], policy: .after(next)))
    }
}

/// The glanceable widget — the iOS analogue of the macOS menu-bar extra.
/// Home Screen families mirror Today's hero trio (Charge · Effort · Rest) as score rings; Lock Screen
/// accessories stay compact single-line / gauge layouts.
struct NOOPWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: NOOPEntry

    private var snap: WidgetSnapshot { entry.snapshot }

    var body: some View {
        switch family {
        case .accessoryCircular:
            recoveryGauge
        case .accessoryInline:
            Text(inlineText)
        case .accessoryRectangular:
            rectangular
        case .systemLarge:
            large
        case .systemMedium:
            medium
        default:
            // systemSmall (and any future compact family)
            small
        }
    }

    // MARK: - Colours (match Today tile tints)

    private var chargeColor: Color {
        guard snap.recovery != nil else { return StrandPalette.textTertiary }
        return StrandPalette.chargeColor
    }

    private var effortColor: Color {
        guard let e = snap.effort else { return StrandPalette.textTertiary }
        return StrandPalette.effortTint(fraction: Double(e) / 100)
    }

    private var restColor: Color {
        guard let r = snap.rest else { return StrandPalette.textTertiary }
        return StrandPalette.restColor
    }

    private var inlineText: String {
        var parts: [String] = []
        if let r = snap.recovery { parts.append("Charge \(r)%") }
        if let b = snap.bpm { parts.append("\(b) bpm") }
        return parts.isEmpty ? "NOOP" : parts.joined(separator: " · ")
    }

    // MARK: - Lock Screen accessories

    private var recoveryGauge: some View {
        Gauge(value: Double(snap.recovery ?? 0), in: 0...100) {
            Image(systemName: "heart.fill")
        } currentValueLabel: {
            Text(snap.recovery.map { "\($0)" } ?? "–")
        }
        .gaugeStyle(.accessoryCircular)
        .tint(chargeColor)
    }

    /// Lock-Screen rectangular accessory: Charge · Effort · Rest, same trio as the Home Screen rings.
    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Text("NOOP")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(StrandPalette.textSecondary)
                Spacer(minLength: 0)
                if let bpm = snap.bpm {
                    Text("\(bpm) bpm")
                        .font(.caption2)
                        .foregroundStyle(StrandPalette.textTertiary)
                }
            }
            HStack(alignment: .top, spacing: 0) {
                accessoryScore("Charge", value: snap.recovery, tint: chargeColor, suffix: "%")
                accessoryScore("Effort", value: snap.effort, tint: effortColor)
                accessoryScore("Rest", value: snap.rest, tint: restColor, suffix: "%")
            }
        }
    }

    private func accessoryScore(_ label: String, value: Int?, tint: Color, suffix: String = "") -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(value.map { "\($0)\(suffix)" } ?? "–")
                .font(.system(size: 16, weight: .semibold, design: .rounded))
                .foregroundStyle(value == nil ? StrandPalette.textTertiary : tint)
                .minimumScaleFactor(0.7)
            Text(label)
                .font(.system(size: 9))
                .foregroundStyle(StrandPalette.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Home Screen: systemSmall

    /// Compact three-ring hero: Charge · Effort · Rest. HR + battery stay on a thin footer so the
    /// rings own the card (the old big Charge-% layout hid Effort/Rest entirely on small).
    private var small: some View {
        VStack(spacing: 6) {
            headerRow
            scoreRings(diameter: 52, lineWidth: 5, labelFont: .system(size: 9, weight: .medium))
            Spacer(minLength: 0)
            vitalsFooter(compact: true)
        }
        .padding(10)
    }

    // MARK: - Home Screen: systemMedium

    /// Wider three-ring row with room for a fuller vitals footer (live HR + strap battery).
    private var medium: some View {
        VStack(spacing: 8) {
            headerRow
            scoreRings(diameter: 72, lineWidth: 7, labelFont: .caption2)
            Spacer(minLength: 0)
            vitalsFooter(compact: false)
        }
        .padding(12)
    }

    // MARK: - Home Screen: systemLarge

    /// Rings on top, then the richer stat grid (HRV, RHR, live HR, battery) — "show me more".
    private var large: some View {
        VStack(alignment: .leading, spacing: 12) {
            headerRow
            scoreRings(diameter: 88, lineWidth: 8, labelFont: .caption)
            Divider()
            HStack(alignment: .top, spacing: 0) {
                statCell("HRV", value: snap.hrv.map { "\($0)" }, unit: "ms")
                statCell("Rest HR", value: snap.restingHr.map { "\($0)" }, unit: "bpm")
                statCell("HR", value: snap.bpm.map { "\($0)" }, unit: "bpm")
                statCell("Battery", value: snap.batteryPct.map { "\($0)%" })
            }
            Spacer(minLength: 0)
        }
        .padding(16)
    }

    // MARK: - Shared pieces

    private var headerRow: some View {
        HStack {
            Text("NOOP")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(StrandPalette.textSecondary)
            Spacer()
            Circle()
                .fill(snap.bonded ? StrandPalette.statusPositive : StrandPalette.statusCritical)
                .frame(width: 8, height: 8)
                .accessibilityLabel(snap.bonded ? Text("Connected") : Text("Disconnected"))
        }
    }

    /// The Today hero trio as static score rings (widget-safe: no draw-in animation / onAppear race).
    /// Order matches TodayView: Charge · Effort · Rest. Each cell is honest-null ("–") until scored.
    private func scoreRings(diameter: CGFloat, lineWidth: CGFloat, labelFont: Font) -> some View {
        HStack(alignment: .top, spacing: 0) {
            WidgetScoreRing(
                value: snap.recovery,
                label: "Charge",
                color: chargeColor,
                diameter: diameter,
                lineWidth: lineWidth,
                labelFont: labelFont
            )
            WidgetScoreRing(
                value: snap.effort,
                label: "Effort",
                color: effortColor,
                diameter: diameter,
                lineWidth: lineWidth,
                labelFont: labelFont
            )
            WidgetScoreRing(
                value: snap.rest,
                label: "Rest",
                color: restColor,
                diameter: diameter,
                lineWidth: lineWidth,
                labelFont: labelFont
            )
        }
        .frame(maxWidth: .infinity)
    }

    private func vitalsFooter(compact: Bool) -> some View {
        HStack {
            Label(snap.bpm.map(String.init) ?? "–", systemImage: "waveform.path.ecg")
            Spacer()
            if !compact, let hrv = snap.hrv {
                Label("\(hrv)", systemImage: "heart.fill")
                Spacer()
            }
            Label(snap.batteryPct.map { "\($0)%" } ?? "–", systemImage: "battery.50")
        }
        .font(.caption2)
        .foregroundStyle(StrandPalette.textSecondary)
        .labelStyle(.titleAndIcon)
    }

    /// One labelled stat in the large grid — value over a caption, equal-width so the columns align.
    private func statCell(_ label: String, value: String?, unit: String? = nil,
                          tint: Color = StrandPalette.textPrimary) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value ?? "–")
                    .font(.system(size: 20, weight: .semibold, design: .rounded))
                    .foregroundStyle(value == nil ? StrandPalette.textTertiary : tint)
                if let unit, value != nil {
                    Text(unit).font(.caption2).foregroundStyle(StrandPalette.textTertiary)
                }
            }
            Text(label).font(.caption2).foregroundStyle(StrandPalette.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - WidgetScoreRing

/// A static, widget-safe score ring: full-circle track + solid arc + centre number + caption.
/// Deliberately avoids `GlowRing`'s draw-in `@State` animation — WidgetKit timelines don't reliably
/// fire `onAppear`, so an animated ring can freeze empty (0 fill) until the next timeline rebuild.
private struct WidgetScoreRing: View {
    let value: Int?
    let label: String
    let color: Color
    let diameter: CGFloat
    let lineWidth: CGFloat
    let labelFont: Font

    private var fraction: CGFloat {
        guard let value else { return 0 }
        return CGFloat(min(max(Double(value) / 100.0, 0), 1))
    }

    var body: some View {
        VStack(spacing: 4) {
            ZStack {
                Circle()
                    .stroke(StrandPalette.textPrimary.opacity(0.10),
                            style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                if value != nil {
                    Circle()
                        .trim(from: 0, to: max(0.0001, fraction))
                        .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
                Text(value.map(String.init) ?? "–")
                    .font(StrandFont.rounded(diameter * 0.34, weight: .bold))
                    .foregroundStyle(value == nil ? StrandPalette.textTertiary : StrandPalette.textPrimary)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(.horizontal, lineWidth + 2)
            }
            .frame(width: diameter, height: diameter)
            Text(label)
                .font(labelFont)
                .foregroundStyle(StrandPalette.textTertiary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(label))
        .accessibilityValue(Text(value.map { "\($0) out of 100" } ?? "unavailable"))
    }
}

struct NOOPWidget: Widget {
    let kind = "NOOPWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: NOOPProvider()) { entry in
            if #available(iOS 17.0, *) {
                NOOPWidgetView(entry: entry)
                    .containerBackground(StrandPalette.surfaceBase, for: .widget)
            } else {
                NOOPWidgetView(entry: entry)
                    .padding()
                    .background(StrandPalette.surfaceBase)
            }
        }
        .configurationDisplayName("NOOP")
        .description("Charge, Effort and Rest as score rings, plus live HR and strap battery at a glance.")
        .supportedFamilies([
            .systemSmall, .systemMedium, .systemLarge,
            .accessoryCircular, .accessoryInline, .accessoryRectangular
        ])
    }
}
