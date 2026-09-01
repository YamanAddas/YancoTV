import SwiftUI

/// Port of `HomeContent.kt`'s home screen.
///
/// A vertical scroll of a 320pt hero followed by wheel rails, spaced 32pt
/// apart with no dividers or per-rail backgrounds — the gap *is* the
/// separation. Home paints an opaque `BackgroundDeep` over the cinematic
/// backdrop, exactly as the Kotlin does; the browse sections let it show
/// through instead.
struct HomeScreen: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Bindable var state: ShellState

    private var compact: Bool { sizeClass == .compact }
    private var pageInset: CGFloat { compact ? Space.xl : Space.section }
    private var tileWidth: CGFloat { compact ? 200 : ShellDim.posterTile }
    private var railHeight: CGFloat { tileWidth * 9 / 16 + 88 }

    /// Up to three slides drawn from what the library actually has —
    /// resumable titles first, then live channels. EPG programme titles
    /// arrive with MK.iOS.4; until then a live slide leads with the channel.
    private var heroSlides: [HomeHero.HeroSlide] {
        var slides: [HomeHero.HeroSlide] = state.allItems
            .filter { $0.resume != nil }
            .prefix(1)
            .map {
                .init(
                    item: $0,
                    eyebrow: "CONTINUE WATCHING",
                    symbol: "play.fill",
                    headline: $0.title,
                    subhead: "\(Int((1 - ($0.resume ?? 0)) * 100))% left • pick up where you stopped"
                )
            }
        slides += state.library.live.prefix(3 - slides.count).map {
            .init(
                item: $0,
                eyebrow: "ON AIR NOW",
                symbol: "dot.radiowaves.left.and.right",
                headline: $0.nowTitle ?? $0.title,
                subhead: $0.nowTitle == nil ? $0.group : $0.title
            )
        }
        return slides
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.xxxl) {
                if state.library.isEmpty {
                    // Mirrors the Android `EmptyHome` card: an install with
                    // no sources gets a route forward, not an empty rail.
                    emptyHome
                } else {
                    HomeHero(slides: heroSlides) { state.detailItem = $0 }
                        .padding(.horizontal, pageInset)

                    rail(
                        eyebrow: "FOR YOU",
                        title: "Continue watching",
                        caption: "Jump back where you left off",
                        items: state.allItems.filter { $0.resume != nil },
                        style: .poster
                    )

                    rail(
                        eyebrow: "ON AIR",
                        title: "Live now",
                        caption: "Channels from your sources",
                        items: Array(state.library.live.prefix(20)),
                        style: .poster
                    )

                    rail(
                        eyebrow: "YOUR LIBRARY",
                        title: "Favorites",
                        caption: "Everything you starred",
                        items: state.favoriteItems,
                        style: .poster
                    )

                    rail(
                        eyebrow: "BROWSE",
                        title: "Movies",
                        caption: "Straight from your library",
                        items: Array(state.library.movies.prefix(20)),
                        style: .poster
                    )

                    rail(
                        eyebrow: "BROWSE",
                        title: "Series",
                        caption: "Straight from your library",
                        items: Array(state.library.series.prefix(20)),
                        style: .poster
                    )
                }
            }
            .padding(.top, Space.xl)
            .padding(.bottom, Space.section)
        }
        .background(palette.BackgroundDeep)
    }

    private var emptyHome: some View {
        VStack(alignment: .leading, spacing: Space.xl) {
            SectionPlaceholder(
                overline: "YANCOTV+",
                title: "Your cinematic IPTV suite",
                message: "Add an M3U playlist or an Xtream Codes account and your channels, movies and series land here."
            )
            HexCta(title: "Add your first source", symbol: "plus", primary: true) {
                state.section = .settings
            }
            .fixedSize()
            .padding(.horizontal, pageInset)
        }
    }

    @ViewBuilder
    private func rail(
        eyebrow: String,
        title: String,
        caption: String,
        items: [YancoItem],
        style: RailTile.Style
    ) -> some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: Space.md) {
                RailHeader(eyebrow: eyebrow, title: title, caption: caption)
                    .padding(.horizontal, pageInset)

                WheelRail(items: items, itemWidth: tileWidth, minSidePadding: pageInset) { item in
                    RailTile(
                        item: item,
                        style: style,
                        width: tileWidth,
                        isFavorite: state.isFavorite(item)
                    ) {
                        state.detailItem = item
                    }
                }
                .frame(height: railHeight)
            }
        }
    }
}
