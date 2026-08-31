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

    private var heroSlides: [HomeHero.HeroSlide] {
        var slides: [HomeHero.HeroSlide] = SampleContent.continueWatching.prefix(1).map {
            .init(
                item: $0,
                eyebrow: "CONTINUE WATCHING",
                symbol: "play.fill",
                headline: $0.title,
                subhead: "\(Int((1 - ($0.resume ?? 0)) * 100))% left • pick up where you stopped"
            )
        }
        slides += SampleContent.channels.prefix(2).map {
            .init(
                item: $0,
                eyebrow: "ON AIR NOW",
                symbol: "dot.radiowaves.left.and.right",
                headline: $0.nowTitle ?? $0.title,
                subhead: "\($0.title)  •  20:00 – 22:00"
            )
        }
        return slides
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.xxxl) {
                HomeHero(slides: heroSlides) { state.detailItem = $0 }
                    .padding(.horizontal, pageInset)

                rail(
                    eyebrow: "FOR YOU",
                    title: "Continue watching",
                    caption: "Jump back where you left off",
                    items: SampleContent.continueWatching,
                    style: .poster
                )

                rail(
                    eyebrow: "ON AIR",
                    title: "On now",
                    caption: "Live right this second on your favorite channels",
                    items: Array(SampleContent.channels.prefix(6)),
                    style: .onNow
                )

                rail(
                    eyebrow: "YOUR LIBRARY",
                    title: "Favorites",
                    caption: "Movies and series you starred",
                    items: state.favoriteItems,
                    style: .poster
                )

                rail(
                    eyebrow: "TONIGHT",
                    title: "Up next",
                    caption: "Starting soon on your favorite channels",
                    items: Array(SampleContent.channels.suffix(4)),
                    style: .upNext
                )

                rail(
                    eyebrow: "BROWSE",
                    title: "Movies",
                    caption: "Straight from your library",
                    items: SampleContent.movies,
                    style: .poster
                )

                rail(
                    eyebrow: "BROWSE",
                    title: "Series",
                    caption: "Straight from your library",
                    items: SampleContent.series,
                    style: .poster
                )
            }
            .padding(.top, Space.xl)
            .padding(.bottom, Space.section)
        }
        .background(palette.BackgroundDeep)
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
