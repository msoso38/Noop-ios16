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
        let fallback: WidgetSnapshot = context.isPreview ? .placeholder : .unavailable
        completion(NOOPEntry(date: Date(), snapshot: WidgetSnapshot.load() ?? fallback))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NOOPEntry>) -> Void) {
        // Gallery previews use `placeholder(in:)` / getSnapshot's preview branch. A real timeline
        // with no shared snapshot must show missing data honestly, never plausible sample numbers.
        let snap = WidgetSnapshot.load() ?? .unavailable
        // Refresh roughly every 15 minutes; the app also forces a reload when it publishes fresh data.
        let next = Calendar.current.date(byAdding: .minute, value: 15, to: Date()) ?? Date().addingTimeInterval(900)
        completion(Timeline(entries: [NOOPEntry(date: Date(), snapshot: snap)], policy: .after(next)))
    }
}

/// The glanceable widget — the iOS analogue of the macOS menu-bar extra. Recovery, live/last HR,
/// and strap battery.
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
        default:
            home
        }
    }

    private var recoveryColor: Color {
        guard let r = snap.recovery else { return StrandPalette.textTertiary }
        return r >= 67 ? StrandPalette.statusPositive : r >= 34 ? StrandPalette.statusWarning : StrandPalette.statusCritical
    }

    /// Effort is on the 0–100 axis (`StrainScorer.maxStrain == 100`), so the fraction is just the value
    /// over 100 — the same input `effortTint` takes on the Today Effort tile.
    private var effortColor: Color {
        guard let e = snap.effort else { return StrandPalette.textTertiary }
        return StrandPalette.effortTint(fraction: Double(e) / 100)
    }

    private var restColor: Color {
        guard let r = snap.rest else { return StrandPalette.textTertiary }
        return StrandPalette.recoveryColor(Double(r))
    }

    private var inlineText: String {
        var parts: [String] = []
        if let r = snap.recovery { parts.append("Charge \(r)%") }
        if let b = snap.bpm { parts.append("\(b) bpm") }
        return parts.isEmpty ? "NOOP" : parts.joined(separator: " · ")
    }

    private var recoveryGauge: some View {
        Gauge(value: Double(snap.recovery ?? 0), in: 0...100) {
            Image(systemName: "heart.fill")
        } currentValueLabel: {
            Text(snap.recovery.map { "\($0)" } ?? "–")
        }
        .gaugeStyle(.accessoryCircular)
        .tint(recoveryColor)
    }

    /// Lock-Screen rectangular accessory. Two lines (#446): line 1 the Charge headline, line 2 the live
    /// HR alongside Effort so the at-a-glance pair the users asked for both fit the tinted accessory.
    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Image(systemName: "heart.fill").foregroundStyle(recoveryColor)
                Text("Charge \(snap.recovery.map(String.init) ?? "–")%").font(.headline)
            }
            Text("HR \(snap.bpm.map(String.init) ?? "–") · Effort \(snap.effort.map(String.init) ?? "–")")
                .font(.caption)
        }
    }

    private var home: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("NOOP").font(.system(size: 13, weight: .bold))
                    .foregroundStyle(StrandPalette.textSecondary)
                Spacer()
                Circle().fill(snap.bonded ? StrandPalette.statusPositive : StrandPalette.statusCritical)
                    .frame(width: 8, height: 8)
            }
            Spacer(minLength: 0)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(snap.recovery.map(String.init) ?? "–")
                    .font(.system(size: 40, weight: .bold, design: .rounded))
                    .foregroundStyle(recoveryColor)
                Text("%").font(.headline).foregroundStyle(StrandPalette.textTertiary)
            }
            Text("Charge").font(.caption).foregroundStyle(StrandPalette.textTertiary)
            Spacer(minLength: 0)
            HStack {
                Label("\(snap.bpm.map(String.init) ?? "–")", systemImage: "waveform.path.ecg")
                // Medium has room for one more stat (#446); small stays a clean Charge + HR + battery.
                if family == .systemMedium {
                    Spacer()
                    Label("\(snap.effort.map(String.init) ?? "–")", systemImage: "bolt.fill")
                }
                Spacer()
                Label("\(snap.batteryPct.map { "\($0)%" } ?? "–")", systemImage: "battery.50")
            }
            .font(.caption2).foregroundStyle(StrandPalette.textSecondary)
        }
        .padding(12)
    }

    /// The rich `systemLarge` layout (#446): the Charge headline plus a stat grid of Effort, Rest, HRV,
    /// Resting HR, live HR and strap battery — the "show me more" the issue asked for.
    private var large: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("NOOP").font(.system(size: 13, weight: .bold))
                    .foregroundStyle(StrandPalette.textSecondary)
                Spacer()
                Circle().fill(snap.bonded ? StrandPalette.statusPositive : StrandPalette.statusCritical)
                    .frame(width: 8, height: 8)
            }
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(snap.recovery.map(String.init) ?? "–")
                    .font(.system(size: 48, weight: .bold, design: .rounded))
                    .foregroundStyle(recoveryColor)
                Text("%").font(.title3).foregroundStyle(StrandPalette.textTertiary)
                Text("Charge").font(.subheadline).foregroundStyle(StrandPalette.textTertiary)
                    .padding(.leading, 2)
            }
            Divider()
            // Two-by-three stat grid of the richer scores. Each cell is a value + label pairing, tinted to
            // match its Today tile where a token exists (Effort, Rest); raw vitals stay neutral.
            HStack(alignment: .top, spacing: 0) {
                statCell("Effort", value: snap.effort.map(String.init), tint: effortColor)
                statCell("Rest", value: snap.rest.map { "\($0)%" }, tint: restColor)
                statCell("HRV", value: snap.hrv.map { "\($0)" }, unit: "ms")
            }
            HStack(alignment: .top, spacing: 0) {
                statCell("Rest HR", value: snap.restingHr.map { "\($0)" }, unit: "bpm")
                statCell("HR", value: snap.bpm.map { "\($0)" }, unit: "bpm")
                statCell("Battery", value: snap.batteryPct.map { "\($0)%" })
            }
            Spacer(minLength: 0)
        }
        .padding(16)
    }

    /// One labelled stat in the large grid — value over a caption, equal-width so the three columns align.
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
        .configurationDisplayName("NOOP Charge")
        .description("Charge, Effort, Rest, HRV, resting and live heart rate, and strap battery at a glance.")
        .supportedFamilies([
            .systemSmall, .systemMedium, .systemLarge,
            .accessoryCircular, .accessoryInline, .accessoryRectangular
        ])
    }
}
