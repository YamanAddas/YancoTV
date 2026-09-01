import SwiftUI

/// Port of `ui/detail/ContentDetailScreen.kt`.
///
/// A backdrop band with the title column pushed down into the dark part
/// of its gradient — `heroHeight` 330 with a 140 content offset on TV.
/// Both shrink on a phone (260 / 96): the TV numbers are a fraction of a
/// 540pt-tall viewport, and reusing them on an 844pt screen would push
/// the actions below the fold, which is the exact bug MK.29.4 fixed in
/// the other direction (MB-303).
struct DetailScreen: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass

    let item: YancoItem
    @Bindable var state: ShellState
    let onClose: () -> Void

    private var compact: Bool { sizeClass == .compact }
    private var heroHeight: CGFloat { compact ? ShellDim.heroHeightCompact : ShellDim.heroHeight }
    private var contentOffset: CGFloat {
        compact ? ShellDim.detailHeroContentOffsetCompact : ShellDim.detailHeroContentOffset
    }
    private var posterWidth: CGFloat {
        compact ? ShellDim.detailPosterWidthCompact : ShellDim.detailPosterWidth
    }
    private var pageInset: CGFloat { compact ? Space.xl : Space.page }

    var body: some View {
        ZStack(alignment: .topLeading) {
            palette.BackgroundDeep.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: Space.xxl) {
                    hero
                    body(for: item)
                        .padding(.horizontal, pageInset)
                    Spacer(minLength: Space.section)
                }
            }

            closeButton
                .padding(pageInset)
        }
    }

    private var hero: some View {
        ZStack(alignment: .bottomLeading) {
            TileArt(
                url: item.artworkURL,
                seed: item.backdropSeed,
                monogram: item.monogram,
                group: item.group
            )
            .frame(height: heroHeight)
            .clipped()

            LinearGradient(
                colors: [
                    .clear,
                    palette.BackgroundDeep.opacity(0.55),
                    palette.BackgroundDeep,
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: heroHeight)

            HStack(alignment: .bottom, spacing: Space.xl) {
                poster
                titleColumn
            }
            .padding(.horizontal, pageInset)
            .padding(.top, contentOffset)
        }
    }

    private var poster: some View {
        TileArt(
            url: item.artworkURL,
            seed: item.posterSeed ?? item.backdropSeed,
            monogram: item.monogram,
            group: item.group
        )
        .frame(width: posterWidth, height: posterWidth / ShellDim.posterAspect)
        .clipShape(YancoShapes.cutCornerCard)
        .overlay {
            YancoShapes.cutCornerCard.stroke(palette.PanelBorder, lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.6), radius: 18, y: 8)
    }

    private var titleColumn: some View {
        VStack(alignment: .leading, spacing: Space.sm) {
            HStack(spacing: Space.sm) {
                Text(overline)
                    .yancoType(YancoType.overline)
                    .foregroundStyle(palette.Accent)
                if item.kind == .live { LivePill() }
                if let quality = item.quality { QualityChip(quality: quality) }
            }

            Text(item.title)
                .yancoType(compact ? YancoType.displayS : YancoType.displayCinematic)
                .foregroundStyle(palette.TextPrimary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)

            Text(facts)
                .yancoType(YancoType.captionStrong)
                .foregroundStyle(palette.TextSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func body(for item: YancoItem) -> some View {
        VStack(alignment: .leading, spacing: Space.xl) {
            HStack(spacing: Space.md) {
                HexCta(
                    title: item.kind == .live ? "Watch live" : (item.resume != nil ? "Resume" : "Play now"),
                    symbol: "play.fill",
                    primary: true
                ) {
                    state.play(item)
                }
                HexCta(
                    title: state.isFavorite(item) ? "In favorites" : "Favorite",
                    symbol: state.isFavorite(item) ? "heart.fill" : "heart",
                    highlighted: state.isFavorite(item)
                ) {
                    state.toggleFavorite(item)
                }
            }
            .fixedSize()

            if let resume = item.resume {
                VStack(alignment: .leading, spacing: Space.sm) {
                    Text("CONTINUE")
                        .yancoType(YancoType.overline)
                        .foregroundStyle(palette.Accent)
                    EpgProgressLine(
                        progress: resume,
                        caption: "\(Int((1 - resume) * 100))% remaining"
                    )
                }
            }

            if !item.plot.isEmpty {
                VStack(alignment: .leading, spacing: Space.sm) {
                    Text("SYNOPSIS")
                        .yancoType(YancoType.overline)
                        .foregroundStyle(palette.Accent)
                    Text(item.plot)
                        .yancoType(YancoType.bodyLong)
                        .foregroundStyle(palette.TextSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            if item.kind == .live {
                VStack(alignment: .leading, spacing: Space.sm) {
                    Text("ON NOW")
                        .yancoType(YancoType.overline)
                        .foregroundStyle(palette.Accent)
                    Text(item.nowTitle ?? "—")
                        .yancoType(YancoType.titleM)
                        .foregroundStyle(palette.TextPrimary)
                    EpgProgressLine(
                        progress: item.nowProgress ?? 0,
                        caption: "\(Int((1 - (item.nowProgress ?? 0)) * 60)) min left"
                    )
                    if let next = item.nextTitle {
                        Text("Up next: \(next)")
                            .yancoType(YancoType.caption)
                            .foregroundStyle(palette.TextMuted)
                    }
                }
            }
        }
    }

    private var closeButton: some View {
        Button(action: onClose) {
            Image(systemName: "chevron.left")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(palette.TextPrimary)
                .frame(width: 40, height: 40)
                .background(palette.BackgroundDeep.opacity(0.7), in: Circle())
                .overlay { Circle().stroke(palette.PanelBorder, lineWidth: 1) }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Back")
    }

    private var overline: String {
        switch item.kind {
        case .live: return "LIVE CHANNEL"
        case .movie: return "FEATURE"
        case .series: return "SERIES"
        }
    }

    private var facts: String {
        var parts: [String] = []
        if let year = item.year { parts.append(String(year)) }
        if let rating = item.rating { parts.append(String(format: "%.1f ★", rating)) }
        parts.append(item.group)
        if let seasons = item.seasonSummary { parts.append(seasons) }
        if let number = item.channelNumber { parts.append("CH \(number)") }
        return parts.joined(separator: "  ·  ")
    }
}
