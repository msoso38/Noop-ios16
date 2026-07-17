#if os(iOS)
import SwiftUI
import StrandDesign
import UniformTypeIdentifiers

/// NDJSON export for sideloaded iOS installs. A free (7-day) signing identity can't carry the
/// HealthKit entitlement, so HealthKitBridge never runs for them; this toggle has NOOP rewrite
/// NDJSON files on every background transition, and the user's Siri Shortcuts read the files and
/// log the rows into Apple Health. Default OFF.
struct ShortcutExportSettingsView: View {
    @AppStorage(ShortcutNdjsonExport.enabledKey) private var ndjsonEnabled = false
    @AppStorage(ShortcutNdjsonExport.lookbackKey) private var lookbackDays = 90
    @State private var ndjsonFolderName: String = ShortcutNdjsonExport.outputFolderLabel()
    @EnvironmentObject var repo: Repository

    var body: some View {
        ScreenScaffold(title: "Shortcuts Export",
                       subtitle: "Export strap data into Apple Health for sideloaded installs.") {
            exportCard
        }
    }

    private var exportCard: some View {
        StrandCard(padding: 20) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    Image(systemName: "doc.text.fill")
                        .foregroundStyle(StrandPalette.accent)
                        .accessibilityHidden(true)
                    Text("Export Data for Shortcuts")
                        .font(StrandFont.headline)
                        .foregroundStyle(StrandPalette.textPrimary)
                }

                Toggle(isOn: $ndjsonEnabled) {
                    Text("Export for Shortcuts (Apple Health)")
                        .font(StrandFont.subhead)
                        .foregroundStyle(StrandPalette.textPrimary)
                }
                .toggleStyle(.switch)
                .tint(StrandPalette.accent)

                if ndjsonEnabled {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Lookback window")
                            .font(StrandFont.caption)
                            .foregroundStyle(StrandPalette.textTertiary)
                        Picker("Lookback", selection: $lookbackDays) {
                            Text("7 days").tag(7)
                            Text("30 days").tag(30)
                            Text("90 days").tag(90)
                        }
                        .pickerStyle(.segmented)

                        Button {
                            presentFolderPicker()
                        } label: {
                            HStack {
                                Text("Output folder")
                                    .font(StrandFont.subhead)
                                    .foregroundStyle(StrandPalette.textPrimary)
                                Spacer()
                                Text(ndjsonFolderName)
                                    .font(StrandFont.caption)
                                    .foregroundStyle(StrandPalette.textTertiary)
                            }
                        }

                        Button {
                            Task { await ShortcutNdjsonExport.forceFullExport(repo: repo) }
                        } label: {
                            HStack {
                                Image(systemName: "arrow.counterclockwise")
                                    .foregroundStyle(StrandPalette.recovery000)
                                Text("Force full re-export")
                                    .font(StrandFont.subhead)
                                    .foregroundStyle(StrandPalette.recovery000)
                            }
                        }
                        Text("Clears the delta watermark so the next background export rewrites all files from scratch. Useful if sleep or daily metrics files are empty.")
                            .font(StrandFont.caption)
                            .foregroundStyle(StrandPalette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.top, 4)
                }

                Text("Produces 7 NDJSON files for import into Apple Health via iOS Shortcuts. Works on sideloaded installs without HealthKit entitlement.")
                    .font(StrandFont.caption)
                    .foregroundStyle(StrandPalette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func presentFolderPicker() {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        picker.allowsMultipleSelection = false
        picker.delegate = FolderPickerDelegate.shared
        FolderPickerDelegate.shared.onPick = { url in
            ShortcutNdjsonExport.saveOutputFolder(url)
            ndjsonFolderName = url.lastPathComponent
        }
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.windows.first?.rootViewController else { return }
        root.present(picker, animated: true)
    }
}

private class FolderPickerDelegate: NSObject, UIDocumentPickerDelegate {
    static let shared = FolderPickerDelegate()
    var onPick: ((URL) -> Void)?

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        onPick?(url)
    }
}
#endif
