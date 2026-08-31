import SwiftUI

/// Port of `HomeContent.PosterTile` / `OnNowTile` / `UpNextTile`.
///
/// One view with a style discriminator rather than three near-copies —
/// the Kotlin trio differ only in the top-right badge, the scrim's bottom
/// alpha, whether the progress stripe is unconditional, and what the two
/// text lines say. Those four differences are spelled out below rather
/// than buried in three files that drift apart.
struct RailTile: View {
    enum Style {
        /// VOD or channel card. Resume badge, progress only when resumable.
        case poster
        /// Currently-airing programme. LIVE badge, progress always shown.
        case onNow
        /// Scheduled programme. Start-time badge, no progress.
        case upNext
    }

    @Environment(\.yancoPalette) private var palette

    let item: YancoItem
    var style: Style = .poster
    var width: CGFloat = ShellDim.posterTile
    var isFavorite: Bool = false
    var action: () -> Void

    @State private var pressed = false

    /// `HomeContent` uses 0.90 on poster tiles and 0.92 on the two EPG
    /// variants — a barely-visible difference that is nonetheless in the
    /// source, so it is here too.
    private var scrimBottomAlpha: Double {
        style == .poster ? 0.90 : 0.92
    }

    private var showsProgress: Bool {
        switch style {
        case .poster: return (item.resume ?? 0) > 0
        case .onNow: return true
        case .upNext: return false
        }
    }

    private var progressValue: Double {
        style == .onNow ? (item.nowProgress ?? 0) : (item.resume ?? 0)
    }

    private var primaryLine: String {
        switch style {
        case .poster: return item.title
        case .onNow, .upNext: return item.nowTitle ?? item.title
        }
    }

    private var secondaryLine: String {
        switch style {
        case .poster:
            if let resume = item.resume {
                return "\(Int(resume * 100))% watched"
            }
            return item.group
        case .onNow, .upNext:
            return item.title
        }
    }

    var body: some View {
        Button {
            action()
        } label: {
            HexSurface(shape: YancoShapes.cutCornerCardSmall, lit: pressed, bevelInset: 3) {
                VStack(spacing: 0) {
                    artBox
                    textBlock
                }
            }
        }
        .buttonStyle(.plain)
        .frame(width: width)
        ._onPressChange { pressed = $0 }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(primaryLine), \(secondaryLine)")
    }

    private var artBox: some View {
        TileArt(
            url: Artwork.backdrop(item.backdropSeed, width: 640),
            seed: item.backdropSeed,
            monogram: item.monogram,
            lit: pressed
        )
        .aspectRatio(16.0 / 9.0, contentMode: .fit)
        .overlay {
            // Transparent through the top half, then down to the canvas —
            // stops 0 / 0.5 / 1 as in the Kotlin.
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .clear, location: 0.5),
                    .init(color: palette.BackgroundDeep.opacity(scrimBottomAlpha), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .overlay(alignment: .topLeading) {
            if isFavorite {
                Image(systemName: "heart.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(palette.Accent)
                    .frame(width: 24, height: 24)
                    .background(palette.BackgroundDeep.opacity(0.75), in: Circle())
                    .padding(Space.sm)
            }
        }
        .overlay(alignment: .topTrailing) { topTrailingBadge.padding(Space.sm) }
        .overlay(alignment: .bottomLeading) {
            TypeChip(label: item.group).padding(Space.sm)
        }
        .overlay(alignment: .bottom) {
            if showsProgress {
                ProgressStripe(progress: progressValue)
            }
        }
    }

    @ViewBuilder
    private var topTrailingBadge: some View {
        switch style {
        case .poster:
            if let resume = item.resume {
                StatusBadge(symbol: "play.fill", label: "\(Int((1 - resume) * 100))% left")
            } else if let quality = item.quality {
                QualityChip(quality: quality)
            }
        case .onNow:
            LiveBadge()
        case .upNext:
            Text(item.nextTitle == nil ? "SOON" : "20:00")
                .yancoType(YancoType.captionStrong)
                .foregroundStyle(palette.Accent)
                .padding(.horizontal, Space.sm)
                .padding(.vertical, 3)
                .background(palette.BackgroundDeep.opacity(0.78), in: Capsule())
        }
    }

    private var textBlock: some View {
        VStack(alignment: .leading, spacing: Space.xxs) {
            Text(primaryLine)
                .yancoType(YancoType.titleS)
                .foregroundStyle(palette.TextPrimary)
                .lineLimit(1)
            Text(secondaryLine)
                .yancoType(YancoType.caption)
                .foregroundStyle(palette.TextMuted)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Space.md)
        .padding(.vertical, Space.sm)
        .background(palette.BackgroundDeep.opacity(0.55))
    }
}

/// SwiftUI has no press callback outside `ButtonStyle`, and the tile needs
/// one to drive `HexSurface`'s lit state from *inside* the label. A
/// zero-area simultaneous gesture is the least invasive way to get it
/// without reimplementing Button.
extension View {
    func _onPressChange(_ handler: @escaping (Bool) -> Void) -> some View {
        simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in handler(true) }
                .onEnded { _ in handler(false) }
        )
    }
}
