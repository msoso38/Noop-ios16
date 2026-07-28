#if !os(watchOS)
// Popovers don't fit the watch's tiny surface (mirrors DayNavBar.swift's own watchOS exclusion), so
// this whole control is excluded there.
import SwiftUI

/// A small "(i)" affordance that reveals a short explanatory popover — for the "nice to know
/// background" prose that used to sit as a permanent caption under a control. Any genuine
/// safety/battery-impact warning should stay inline at the call site as plain text; only background
/// or context prose belongs here. Modeled on SleepView's nap-row `whyPopover` idiom (a popover, not a
/// sheet), pulled into StrandDesign so every screen can share one implementation.
public struct InfoButton: View {
    private let title: LocalizedStringKey?
    private let text: LocalizedStringKey
    private let accessibilityLabel: LocalizedStringKey
    @State private var isPresented = false

    public init(title: LocalizedStringKey? = nil,
                text: LocalizedStringKey,
                accessibilityLabel: LocalizedStringKey = "More information") {
        self.title = title
        self.text = text
        self.accessibilityLabel = accessibilityLabel
    }

    public var body: some View {
        Button {
            isPresented = true
        } label: {
            Image(systemName: "info.circle")
                .font(StrandFont.headline)
                .foregroundStyle(StrandPalette.textTertiary)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        // Plain, not a Liquid press style: those live in the app target (Strand/Liquid/), which this
        // design-system package can't import.
        .buttonStyle(.plain)
        .help(accessibilityLabel)
        .accessibilityLabel(accessibilityLabel)
        .popover(isPresented: $isPresented, arrowEdge: .bottom) {
            VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                if let title {
                    Text(title)
                        .font(StrandFont.subhead.weight(.semibold))
                        .foregroundStyle(StrandPalette.textPrimary)
                }
                Text(text)
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(NoopMetrics.cardInnerPadding)
            .frame(width: 260)
            .background(StrandPalette.surfaceOverlay)
        }
    }
}
#endif
