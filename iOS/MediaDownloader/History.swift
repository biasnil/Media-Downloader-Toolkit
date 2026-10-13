import Foundation
import Observation
import SwiftUI
import UIKit

// MARK: - Model

struct HistoryEntry: Codable, Identifiable, Hashable, Sendable {
    enum Status: String, Codable, Sendable {
        case done, skipped, failed, cancelled
    }

    var id = UUID()
    var date = Date()
    var source: String          // "YouTube", "Instagram", "Reddit"...
    var title: String
    var link: String
    var status: Status
    var detail: String?         // why it failed / was skipped
    var fileName: String?       // the saved file, for "done"

    var fileURL: URL? {
        fileName.map { DownloadManager.saveFolder.appendingPathComponent($0) }
    }

    var fileExists: Bool {
        fileURL.map { FileManager.default.fileExists(atPath: $0.path) } ?? false
    }
}

// MARK: - Store

/// Every download attempt, newest first. Saved privately (not in the Files folder), up to 1,000 entries.
@MainActor
@Observable
final class HistoryStore {
    static let shared = HistoryStore()

    private(set) var entries: [HistoryEntry] = []

    private let file: URL = {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent("history.json")
    }()

    private init() {
        if let data = try? Data(contentsOf: file),
           let saved = try? JSONDecoder().decode([HistoryEntry].self, from: data) {
            entries = saved
        }
    }

    func add(source: String, title: String, link: String, status: HistoryEntry.Status,
             detail: String? = nil, fileName: String? = nil) {
        let entry = HistoryEntry(source: source, title: title.isEmpty ? link : title, link: link,
                                 status: status, detail: detail, fileName: fileName)
        entries.insert(entry, at: 0)
        if entries.count > 1000 { entries.removeLast(entries.count - 1000) }
        save()
    }

    func remove(_ ids: Set<UUID>) {
        entries.removeAll { ids.contains($0.id) }
        save()
    }

    func remove(where shouldRemove: (HistoryEntry) -> Bool) {
        entries.removeAll(where: shouldRemove)
        save()
    }

    /// Deletes the saved files of these entries (if still there) and removes the entries.
    func deleteFiles(_ ids: Set<UUID>) {
        for entry in entries where ids.contains(entry.id) {
            if let url = entry.fileURL { try? FileManager.default.removeItem(at: url) }
        }
        remove(ids)
    }

    private func save() {
        let snapshot = entries
        let target = file
        Task.detached(priority: .utility) {
            if let data = try? JSONEncoder().encode(snapshot) {
                try? data.write(to: target, options: .atomic)
            }
        }
    }
}

// MARK: - Screen

/// Like desktop's History window: filter, retry, remove one, remove selected, or clear by type.
struct HistoryView: View {
    /// Starts a new download of this link (routes it to the right tab).
    var onDownloadAgain: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var store = HistoryStore.shared
    @State private var filter: Filter = .all
    @State private var selection = Set<UUID>()
    @State private var editMode: EditMode = .inactive
    @State private var confirm: Confirm?
    @State private var report: ProblemReport?

    enum Filter: String, CaseIterable, Identifiable {
        case all, done, failed, skipped
        var id: String { rawValue }

        var label: String {
            switch self {
            case .all: return AppText.History.all
            case .done: return AppText.History.downloaded
            case .failed: return AppText.History.failed
            case .skipped: return AppText.History.skipped
            }
        }

        func matches(_ e: HistoryEntry) -> Bool {
            switch self {
            case .all: return true
            case .done: return e.status == .done
            case .failed: return e.status == .failed || e.status == .cancelled
            case .skipped: return e.status == .skipped
            }
        }
    }

    enum Confirm: Identifiable {
        case clearAll, deleteSelectedFiles, deleteAllDownloadedFiles
        var id: Int { hashValue }
    }

    private var shown: [HistoryEntry] { store.entries.filter(filter.matches) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker(AppText.History.show, selection: $filter) {
                    ForEach(Filter.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

                if shown.isEmpty {
                    ContentUnavailableView(AppText.History.nothingHere, systemImage: "clock.arrow.circlepath",
                                           description: Text(AppText.History.emptyNote))
                } else {
                    List(selection: $selection) {
                        ForEach(shown) { entry in
                            row(entry)
                                .tag(entry.id)
                                .swipeActions(edge: .trailing) {
                                    Button(AppText.History.remove, role: .destructive) { store.remove([entry.id]) }
                                    if entry.fileExists {
                                        Button(AppText.History.deleteFile) { store.deleteFiles([entry.id]) }
                                            .tint(.orange)
                                    }
                                }
                                .swipeActions(edge: .leading) {
                                    if entry.status != .done {
                                        Button(AppText.History.retry) { downloadAgain(entry) }.tint(.blue)
                                    }
                                }
                                .contextMenu { menu(for: entry) }
                        }
                    }
                    .listStyle(.plain)
                    .environment(\.editMode, $editMode)
                }
            }
            .background(Color.appBackground)
            .reportProblem($report)
            .navigationTitle(AppText.History.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .confirmationDialog(AppText.History.areYouSure, isPresented: Binding(
                get: { confirm != nil }, set: { if !$0 { confirm = nil } }
            ), presenting: confirm) { what in
                switch what {
                case .clearAll:
                    Button(AppText.History.clearAllHistory, role: .destructive) { store.remove { _ in true } }
                case .deleteSelectedFiles:
                    Button(AppText.History.deleteCount(selection.count), role: .destructive) {
                        store.deleteFiles(selection)
                        finishEditing()
                    }
                case .deleteAllDownloadedFiles:
                    Button(AppText.History.deleteAllDownloaded, role: .destructive) {
                        store.deleteFiles(Set(store.entries.filter { $0.status == .done }.map(\.id)))
                    }
                }
            } message: { what in
                switch what {
                case .clearAll: Text(AppText.History.clearAllNote)
                case .deleteSelectedFiles: Text(AppText.History.deleteSelectedNote)
                case .deleteAllDownloadedFiles: Text(AppText.History.deleteAllNote)
                }
            }
        }
    }

    // MARK: Rows

    private func row(_ e: HistoryEntry) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon(e.status))
                .foregroundStyle(color(e.status))
                .font(.title3)
            VStack(alignment: .leading, spacing: 3) {
                Text(e.title)
                    .font(.subheadline)
                    .lineLimit(2)
                Text(subtitle(e))
                    .font(.caption)
                    .foregroundStyle(Color.muted)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 2)
    }

    private func subtitle(_ e: HistoryEntry) -> String {
        var parts = [e.source, e.date.formatted(date: .abbreviated, time: .shortened)]
        switch e.status {
        case .done: parts.append(e.fileExists ? (e.fileName ?? AppText.History.saved) : AppText.History.fileGone)
        case .skipped: parts.append(e.detail ?? AppText.History.alreadyExisted)
        case .failed: parts.append(e.detail ?? AppText.History.failed)
        case .cancelled: parts.append(AppText.History.cancelled)
        }
        return parts.joined(separator: " · ")
    }

    private func icon(_ s: HistoryEntry.Status) -> String {
        switch s {
        case .done: return "checkmark.circle.fill"
        case .skipped: return "arrow.uturn.forward.circle"
        case .failed: return "xmark.octagon.fill"
        case .cancelled: return "minus.circle"
        }
    }

    private func color(_ s: HistoryEntry.Status) -> Color {
        switch s {
        case .done: return .green
        case .failed: return .ytRed
        case .skipped, .cancelled: return .muted
        }
    }

    @ViewBuilder
    private func menu(for e: HistoryEntry) -> some View {
        Button { downloadAgain(e) } label: {
            Label(e.status == .done ? AppText.History.downloadAgain : AppText.History.retry, systemImage: "arrow.clockwise")
        }
        Button { UIPasteboard.general.string = e.link } label: {
            Label(AppText.History.copyLink, systemImage: "doc.on.doc")
        }
        if e.status == .failed {
            Button {
                report = ProblemReport(context: "Site: \(e.source)\nLink: \(e.link)\nError: \(e.detail ?? "-")")
            } label: {
                Label(AppText.Report.title, systemImage: "exclamationmark.bubble")
            }
        }
        if let url = e.fileURL, e.fileExists {
            ShareLink(item: url) { Label(AppText.History.shareFile, systemImage: "square.and.arrow.up") }
        }
        Divider()
        Button(role: .destructive) { store.remove([e.id]) } label: {
            Label(AppText.History.removeFromHistory, systemImage: "trash")
        }
        if e.fileExists {
            Button(role: .destructive) { store.deleteFiles([e.id]) } label: {
                Label(AppText.History.deleteFile, systemImage: "trash.slash")
            }
        }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button(AppText.Common.done) { dismiss() }
        }
        ToolbarItemGroup(placement: .primaryAction) {
            if editMode.isEditing {
                Button(AppText.Common.cancel) { finishEditing() }
            } else {
                Menu {
                    Button { editMode = .active } label: {
                        Label(AppText.History.select, systemImage: "checkmark.circle")
                    }
                    Section(AppText.History.clearFromHistory) {
                        Button { store.remove { $0.status == .done } } label: {
                            Label(AppText.History.clearDownloaded, systemImage: "checkmark.circle")
                        }
                        Button { store.remove { $0.status == .failed || $0.status == .cancelled } } label: {
                            Label(AppText.History.clearFailed, systemImage: "xmark.octagon")
                        }
                        Button { store.remove { $0.status == .skipped } } label: {
                            Label(AppText.History.clearSkipped, systemImage: "arrow.uturn.forward.circle")
                        }
                        Button(role: .destructive) { confirm = .clearAll } label: {
                            Label(AppText.History.clearAll, systemImage: "trash")
                        }
                    }
                    Section(AppText.History.files) {
                        Button(role: .destructive) { confirm = .deleteAllDownloadedFiles } label: {
                            Label(AppText.History.deleteAllDownloadedMenu, systemImage: "trash.slash")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .disabled(store.entries.isEmpty)
            }
        }
        if editMode.isEditing {
            ToolbarItemGroup(placement: .bottomBar) {
                Button(AppText.History.selectAll) { selection = Set(shown.map(\.id)) }
                Spacer()
                Button(AppText.History.deleteFiles) { confirm = .deleteSelectedFiles }
                    .disabled(!store.entries.contains { selection.contains($0.id) && $0.fileExists })
                Spacer()
                Button(AppText.History.removeCount(selection.count), role: .destructive) {
                    store.remove(selection)
                    finishEditing()
                }
                .disabled(selection.isEmpty)
            }
        }
    }

    private func finishEditing() {
        selection.removeAll()
        editMode = .inactive
    }

    private func downloadAgain(_ e: HistoryEntry) {
        onDownloadAgain(e.link)
        dismiss()
    }
}
