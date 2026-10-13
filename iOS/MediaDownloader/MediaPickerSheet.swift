import SwiftUI

/// A post with several photos/videos (Instagram carousel, Facebook album, X post...): tick which to download.
struct MediaPickerSheet: View {
    let prompt: MediaPrompt
    let onDone: ([SiteMedia]?) -> Void

    @State private var selected: Set<Int>

    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 6)]

    init(prompt: MediaPrompt, onDone: @escaping ([SiteMedia]?) -> Void) {
        self.prompt = prompt
        self.onDone = onDone
        _selected = State(initialValue: Set(prompt.items.indices))
    }

    private var photoCount: Int { prompt.items.filter(\.isImage).count }
    private var videoCount: Int { prompt.items.count - photoCount }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    buttons
                    Text(AppText.Picker.count(photos: photoCount, videos: videoCount))
                        .font(.footnote)
                        .foregroundStyle(Color.muted)
                    LazyVGrid(columns: columns, spacing: 6) {
                        ForEach(Array(prompt.items.enumerated()), id: \.offset) { index, item in
                            tile(item, index: index)
                        }
                    }
                }
                .padding(16)
            }
            .background(Color.appBackground)
            .navigationTitle(prompt.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppText.Common.skip) { onDone(nil) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(AppText.Common.downloadCount(selected.count)) {
                        onDone(prompt.items.indices.filter { selected.contains($0) }.map { prompt.items[$0] })
                    }
                    .disabled(selected.isEmpty)
                }
            }
        }
    }

    private var buttons: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Button(AppText.Common.selectAll) { selected = Set(prompt.items.indices) }
                // Only useful when the post mixes both
                if photoCount > 0 && videoCount > 0 {
                    Button(AppText.Picker.photosOnly) { selected = Set(prompt.items.indices.filter { prompt.items[$0].isImage }) }
                    Button(AppText.Picker.videosOnly) { selected = Set(prompt.items.indices.filter { !prompt.items[$0].isImage }) }
                }
                Button(AppText.Common.selectNone) { selected.removeAll() }
            }
            .buttonStyle(.bordered)
            .font(.footnote)
        }
    }

    private func tile(_ item: SiteMedia, index: Int) -> some View {
        let isOn = selected.contains(index)
        return Button {
            if isOn { selected.remove(index) } else { selected.insert(index) }
        } label: {
            Color.surface
                .aspectRatio(1, contentMode: .fit)
                .overlay { preview(item) }
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(alignment: .topTrailing) {
                    Image(systemName: isOn ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(isOn ? Color.ytRed : Color.white)
                        .background(Circle().fill(isOn ? Color.white : Color.black.opacity(0.3)))
                        .padding(6)
                }
                .overlay(alignment: .bottomLeading) {
                    Image(systemName: item.isImage ? "photo" : "video.fill")
                        .font(.caption)
                        .foregroundStyle(.white)
                        .padding(5)
                        .background(Color.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 5))
                        .padding(6)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(isOn ? Color.ytRed : Color.outline, lineWidth: isOn ? 3 : 1)
                }
                .opacity(isOn ? 1 : 0.6)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func preview(_ item: SiteMedia) -> some View {
        if let url = item.thumbnailURL {
            AsyncImage(url: url) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                ProgressView()
            }
        } else {
            // No preview (e.g. archive.org files): show the name instead
            VStack(spacing: 6) {
                Image(systemName: item.isImage ? "photo" : item.isAudioOnly ? "music.note" : "film")
                    .font(.title2)
                Text(item.title)
                    .font(.caption2)
                    .lineLimit(3)
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(Color.muted)
            .padding(8)
        }
    }
}
