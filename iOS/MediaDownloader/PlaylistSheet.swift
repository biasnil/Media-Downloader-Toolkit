import SwiftUI

/// iPhone version of desktop's playlist_dialog.py: tick videos, or pick a numbered range.
struct PlaylistSheet: View {
    let prompt: PlaylistPrompt
    let onDone: ([PlaylistItem]?) -> Void

    @State private var selected: Set<String>
    @State private var from = "1"
    @State private var to: String

    init(prompt: PlaylistPrompt, onDone: @escaping ([PlaylistItem]?) -> Void) {
        self.prompt = prompt
        self.onDone = onDone
        _selected = State(initialValue: prompt.preselected)
        _to = State(initialValue: String(prompt.items.count))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Text("#")
                        TextField("1", text: $from).numberField()
                        Text("to #")
                        TextField("\(prompt.items.count)", text: $to).numberField()
                        Spacer()
                        Button("Apply", action: applyRange)
                            .buttonStyle(.borderedProminent)
                    }
                    HStack {
                        Button("Select all") { selected = Set(prompt.items.map(\.id)) }
                        Spacer()
                        Button("Select none") { selected.removeAll() }
                    }
                    .buttonStyle(.borderless)
                } footer: {
                    Text("\(prompt.items.count) videos. Tick the ones you want.")
                }

                Section {
                    ForEach(Array(prompt.items.enumerated()), id: \.element.id) { index, item in
                        Button { toggle(item.id) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: selected.contains(item.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(selected.contains(item.id) ? Color.ytRed : Color.muted)
                                    .font(.title3)
                                Text("\(index + 1). \(item.title)")
                                    .lineLimit(2)
                                    .foregroundStyle(Color.primary)
                            }
                        }
                    }
                }
            }
            .navigationTitle(prompt.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Skip") { onDone(nil) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Download (\(selected.count))") {
                        onDone(prompt.items.filter { selected.contains($0.id) })
                    }
                    .disabled(selected.isEmpty)
                }
            }
        }
    }

    private func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }

    private func applyRange() {
        let count = prompt.items.count
        guard let a = Int(from), let b = Int(to) else { return }
        let low = max(1, min(a, b)), high = min(count, max(a, b))
        guard low <= high else { return }
        selected = Set(prompt.items[(low - 1)...(high - 1)].map(\.id))
    }
}

private extension View {
    func numberField() -> some View {
        self.keyboardType(.numberPad)
            .multilineTextAlignment(.center)
            .frame(width: 56)
            .padding(.vertical, 4)
            .background(Color.surface, in: RoundedRectangle(cornerRadius: 6))
    }
}
