import SwiftUI

/// Port of `HomeContent.HomeHero`.
///
/// A 320pt `HexSurface` card that auto-advances through its slides every
/// 7 seconds, cross-fading. The whole hero is one target — on TV the CTA
/// shares the hero's interaction source and is not independently
/// focusable, so on iOS the whole card is one button rather than a card
/// containing a second tap target.
struct HomeHero: View {
    @Environment(\.yancoPalette) private var palette

    let slides: [HeroSlide]
    var height: CGFloat = 320
    var onPlay: (YancoItem) -> Void

    @State private var index = 0
    @State private var pressed = false

    private let advanceInterval: TimeInterval = 7

    struct HeroSlide: Identifiable {
        let id: String
        let item: YancoItem
        let eyebrow: String
        let symbol: String
        /// Headline is explicit rather than always `item.title`: an ON AIR
        /// slide leads with the *programme* and puts the channel in the
        /// subhead, which is what the Android hero does. Deriving it from
        /// the item printed the channel name twice.
        let headline: String
        let subhead: String

        init(item: YancoItem, eyebrow: String, symbol: String, headline: String, subhead: String) {
            self.id = item.id
            self.item = item
            self.eyebrow = eyebrow
            self.symbol = symbol
            self.headline = headline
            self.subhead = subhead
        }
    }

    private var slide: HeroSlide? {
        guard !slides.isEmpty else { return nil }
        return slides[min(index, slides.count - 1)]
    }

    var body: some View {
        Group {
            if let slide {
                Button {
                    onPlay(slide.item)
                } label: {
                    HexSurface(shape: YancoShapes.cutCornerCard, lit: pressed, bevelInset: 4) {
                        heroFrame(slide)
                    }
                }
                .buttonStyle(.plain)
                ._onPressChange { pressed = $0 }
            }
        }
        .frame(height: height)
        .task(id: slides.count) {
            // Auto-advance. Cancelled and restarted whenever the slide set
            // changes; `task` tears this down when the view goes away.
            guard slides.count > 1 else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(advanceInterval))
                guard !Task.isCancelled else { return }
                withAnimation(.easeInOut(duration: 0.24)) {
                    index = (index + 1) % slides.count
                }
            }
        }
    }

    private func heroFrame(_ slide: HeroSlide) -> some View {
        ZStack(alignment: .bottomLeading) {
            TileArt(
                url: Artwork.backdrop(slide.item.backdropSeed, width: 1200),
                seed: slide.item.backdropSeed,
                monogram: slide.item.monogram,
                group: slide.item.group,
                lit: false
            )
            .id(slide.id)
            .transition(.opacity)

            // Horizontal left-darken for copy legibility, then the vertical
            // bottom fade. Evenly-spaced stops, per HomeHero (FeatureHero
            // uses explicit 0.45 / 0.75 stops instead).
            LinearGradient(
                colors: [
                    palette.BackgroundDeep.opacity(0.92),
                    palette.BackgroundDeep.opacity(0.40),
                    .clear,
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
            LinearGradient(
                colors: [
                    .clear,
                    palette.BackgroundDeep.opacity(0.45),
                    palette.BackgroundDeep.opacity(0.85),
                ],
                startPoint: .top,
                endPoint: .bottom
            )

            copyBlock(slide)
        }
        .overlay(alignment: .topTrailing) { pips.padding(Space.lg) }
        .clipped()
    }

    private func copyBlock(_ slide: HeroSlide) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Space.sm) {
                Image(systemName: slide.symbol)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(palette.Accent)
                Text(slide.eyebrow)
                    .yancoType(YancoType.overline)
                    .foregroundStyle(palette.Accent)
            }

            Spacer().frame(height: Space.sm)

            Text(slide.headline)
                .yancoType(YancoType.displayM)
                .foregroundStyle(palette.TextPrimary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)

            Spacer().frame(height: Space.xs)

            Text(slide.subhead)
                .yancoType(YancoType.body)
                .foregroundStyle(palette.TextSecondary)
                .lineLimit(1)

            Spacer().frame(height: Space.lg)

            HStack(spacing: Space.md) {
                heroCta
                Text(slide.item.group)
                    .yancoType(YancoType.caption)
                    .foregroundStyle(palette.TextMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Space.xxxl)
        .padding(.vertical, Space.xxl)
    }

    /// `HeroCta` — not a Button. It renders the hero's own press state, so
    /// the entire card stays a single tap target as it is on TV.
    private var heroCta: some View {
        HStack(spacing: Space.sm) {
            Image(systemName: "play.fill")
                .font(.system(size: 16, weight: .semibold))
            Text("Watch now")
                .yancoType(YancoType.labelStrong)
        }
        .foregroundStyle(pressed ? palette.BackgroundDeep : palette.Accent)
        .padding(.horizontal, Space.lg)
        .padding(.vertical, Space.sm)
        .background(
            pressed ? palette.Accent : palette.Accent.opacity(0.22),
            in: ButtonBevelShape()
        )
    }

    private var pips: some View {
        HStack(spacing: Space.xs) {
            ForEach(slides.indices, id: \.self) { i in
                Capsule()
                    .fill(i == index ? palette.Accent : palette.TextFaint)
                    .frame(width: i == index ? 18 : 6, height: 6)
            }
        }
        .opacity(slides.count > 1 ? 1 : 0)
        .animation(.easeInOut(duration: 0.24), value: index)
    }
}
