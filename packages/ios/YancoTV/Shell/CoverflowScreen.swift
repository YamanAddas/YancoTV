import SwiftUI

/// Port of `ui/shell/CoverflowSectionScreen.kt` — the Live / Movies /
/// Series browse screen, shared verbatim by all three on Android.
///
/// Composition: a preview pane taking 0.62 of the height over the
/// coverflow wheel at 0.38.
///
/// ### Two wheels, deliberately not unified
///
/// This is **not** `WheelRail`. The home rails use a 14pt camera, a pivot
/// that lerps to the card's inner edge, and ±38°. The browse coverflow
/// uses a 128pt camera (a much milder perspective), a fixed centre pivot,
/// and ±58°. The Kotlin keeps them separate and so does this port.
///
/// The Android transform is index-based (`distance = index - focusedIndex`,
/// an integer) and snaps between discrete states as selection moves. Here
/// it is driven continuously from scroll offset, which is the natural iOS
/// model — the coefficients are unchanged (16°/step, 0.07 scale/step,
/// 0.18 alpha/step, 4pt translate/step, clamped at 58° / 0.62 / 0.32) and
/// interpolated so every integer distance lands on exactly the Android
/// value.
struct CoverflowScreen: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass

    let kind: ContentKind
    @Bindable var state: ShellState

    @State private var centeredID: String?

    private var compact: Bool { sizeClass == .compact }
    private var items: [YancoItem] { state.items(for: kind) }

    private var selected: YancoItem? {
        items.first { $0.id == centeredID } ?? items.first
    }

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 0) {
                CategoryChipBar(kind: kind, state: state)
                    .padding(.top, Space.sm)
                    .frame(height: chipBarHeight)

                if items.isEmpty {
                    emptyState
                } else {
                    // Compose's `weight(0.62) / weight(0.38)` is a
                    // proportional split. SwiftUI's `layoutPriority` is
                    // *not* the same thing — it ranks who gets space, not
                    // how much — so the two panes are measured explicitly.
                    //
                    // The wheel is also capped at its natural height. 0.38
                    // of a 540pt TV viewport is 205pt, about what a 200pt
                    // orb plus padding needs; 0.38 of a 1210pt iPad is
                    // 460pt of mostly air. Above the cap the surplus goes
                    // to the preview, which can actually use it.
                    let available = geo.size.height - chipBarHeight
                    let wheel = min(available * 0.38, compact ? 236 : 252)

                    PreviewPane(
                        item: selected,
                        kind: kind,
                        state: state,
                        size: CGSize(width: geo.size.width, height: available - wheel)
                    )
                    .frame(height: available - wheel)

                    coverflow.frame(height: wheel)
                }
            }
        }
    }

    private var chipBarHeight: CGFloat { 38 + Space.sm + Space.xs * 2 }

    // MARK: - The wheel

    private let orbWidth: CGFloat = 140
    private let orbHeight: CGFloat = 200
    private let orbSpacing: CGFloat = 28

    private var coverflow: some View {
        GeometryReader { outer in
            let viewportCenter = outer.size.width / 2
            let slotPitch = orbWidth + orbSpacing

            ScrollView(.horizontal) {
                LazyHStack(spacing: orbSpacing) {
                    ForEach(items) { item in
                        ContentOrb(item: item, kind: kind, isCentered: item.id == centeredID) {
                            state.detailItem = item
                        }
                        .frame(width: orbWidth, height: orbHeight)
                        .visualEffect { view, proxy in
                            let center = proxy.frame(in: .scrollView(axis: .horizontal)).midX
                            let distance = (center - viewportCenter) / slotPitch
                            let magnitude = min(abs(distance), 6)
                            // Blend factor: 0 at dead centre, 1 by one full
                            // slot out. Reproduces the Kotlin's isCenter
                            // special case without the discrete jump.
                            let t = min(abs(distance), 1)

                            let scale = 1.18 + (max(0.62, 1.0 - magnitude * 0.07) - 1.18) * t
                            let alpha = 1.0 + (max(0.32, 1.0 - magnitude * 0.18) - 1.0) * t

                            return view
                                .rotation3DEffect(
                                    .degrees(max(-58, min(58, -distance * 16))),
                                    axis: (x: 0, y: 1, z: 0),
                                    anchor: .center,
                                    perspective: 1.09
                                )
                                .scaleEffect(scale)
                                .opacity(alpha)
                                .offset(x: -distance * 4)
                        }
                    }
                }
                .scrollTargetLayout()
                .padding(.vertical, Space.lg)
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollPosition(id: $centeredID, anchor: .center)
            .contentMargins(.horizontal, max((outer.size.width - orbWidth) / 2, Space.lg), for: .scrollContent)
            .scrollIndicators(.hidden)
        }
        .onAppear { centeredID = centeredID ?? items.first?.id }
        .onChange(of: items.map(\.id)) { _, ids in
            if centeredID == nil || !ids.contains(centeredID ?? "") { centeredID = ids.first }
        }
    }

    private var emptyState: some View {
        VStack(spacing: Space.sm) {
            Spacer()
            Text("Nothing here yet")
                .yancoType(YancoType.titleL)
                .foregroundStyle(palette.TextPrimary)
            Text("No items match this category.")
                .yancoType(YancoType.body)
                .foregroundStyle(palette.TextMuted)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(Space.page)
    }
}

// MARK: - Orb

/// One coverflow card: a 140x140 hex art box over a title and subtitle,
/// in a 140x200 column. The shadow and border live on the art box only,
/// inside the 3D transform, so they rotate and scale with the card.
private struct ContentOrb: View {
    @Environment(\.yancoPalette) private var palette

    let item: YancoItem
    let kind: ContentKind
    let isCentered: Bool
    let action: () -> Void

    @State private var pressed = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: Space.sm) {
                artBox
                Text(item.title)
                    .yancoType(isCentered ? YancoType.labelStrong : YancoType.label)
                    .foregroundStyle(isCentered ? palette.TextPrimary : palette.TextSecondary)
                    .lineLimit(1)
                Text(subtitle)
                    .yancoType(YancoType.caption)
                    .foregroundStyle(palette.TextMuted)
                    .lineLimit(1)
            }
        }
        .buttonStyle(.plain)
        ._onPressChange { pressed = $0 }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(item.title), \(subtitle)")
    }

    private var subtitle: String {
        if kind == .live { return item.nowTitle ?? item.group }
        return item.group
    }

    /// The hex's side bevels: `HexCapsuleShape` cuts `clamp(h * 0.28, 10,
    /// 36)` off each side, so at 140 the flat bottom edge runs from x=36
    /// to x=104. Anything pinned to the bottom has to stay inside that or
    /// it gets clipped to a sliver by the diagonal.
    private var sideCut: CGFloat { min(max(140 * 0.28, 10), 36) }

    /// Ambient bed colour, taken from the same genre hue the artwork uses
    /// so the glow belongs to the card rather than tinting it.
    private var genreGlow: Color {
        Color(hue: GenrePalette.hues(for: item.group).0, saturation: 0.65, brightness: 0.75)
    }

    private var artBox: some View {
        TileArt(
            url: kind == .live
                ? Artwork.backdrop(item.backdropSeed, width: 400)
                : Artwork.poster(item.posterSeed ?? item.backdropSeed, width: 400),
            seed: item.posterSeed ?? item.backdropSeed,
            monogram: item.monogram,
            group: item.group,
            lit: isCentered
        )
        // Live channel logos are letterboxed inside the hex (Fit + 16pt
        // inset on Android); VOD art fills it.
        .padding(kind == .live ? 16 : 0)
        .frame(width: 140, height: 140)
        .background(
            LinearGradient(
                colors: [palette.BackgroundElevated, palette.BackgroundDeep],
                startPoint: .top,
                endPoint: .bottom
            )
        )
        .overlay(alignment: .bottom) { footer }
        .clipShape(YancoShapes.hexCapsule)
        .overlay { innerRing }
        .overlay { outerBevel }
        // Two shadows: the accent one is the focus signal and belongs to
        // the centred card alone, but every card gets a faint bed in its
        // own genre hue. Without it the off-centre orbs sit on flat black
        // and the rail reads as cut-out stickers rather than lit objects.
        .shadow(color: genreGlow.opacity(isCentered ? 0.5 : 0.28), radius: isCentered ? 26 : 12, y: 6)
        .shadow(
            color: isCentered ? palette.Accent.opacity(0.45) : .black.opacity(0.45),
            radius: isCentered ? 22 : 6,
            y: isCentered ? 9 : 3
        )
        .scaleEffect(pressed ? 0.94 : 1)
        .animation(.spring(response: 0.25, dampingFraction: 0.75), value: pressed)
    }

    /// Outer edge. A flat stroke reads as a sticker; a gradient one reads
    /// as a bevel catching light from the upper left, which is the same
    /// story `HexSurface`'s specular hairline tells on the rectangular
    /// cards.
    private var outerBevel: some View {
        YancoShapes.hexCapsule
            .stroke(
                LinearGradient(
                    colors: isCentered
                        ? [palette.AccentSoft, palette.Accent, palette.AccentDeep]
                        : [
                            palette.PanelBorder,
                            palette.BorderSubtle,
                            palette.BackgroundDeep.opacity(0.9),
                        ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ),
                lineWidth: isCentered ? 2 : 1
            )
    }

    /// A second, dimmer ring set in from the first. Two edges a few points
    /// apart is what gives the hex a machined thickness instead of a
    /// drawn outline.
    private var innerRing: some View {
        YancoShapes.hexCapsule
            .stroke(
                Color.white.opacity(isCentered ? 0.18 : 0.07),
                lineWidth: 1
            )
            .padding(4)
    }

    /// Scrim plus status, pinned inside the hex's flat bottom edge.
    @ViewBuilder
    private var footer: some View {
        VStack(spacing: 5) {
            if kind != .live, let quality = item.quality {
                Text(quality)
                    .font(.system(size: 9, weight: .bold))
                    .tracking(0.6)
                    .foregroundStyle(quality == "4K" ? palette.Premium : palette.TextSecondary)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(palette.BackgroundDeep.opacity(0.72), in: Capsule())
            }
            if let resume = item.resume, kind != .live {
                ProgressStripe(progress: resume, height: 3)
                    .clipShape(Capsule())
            }
        }
        .padding(.horizontal, sideCut * 0.55)
        .padding(.bottom, 10)
        .background(alignment: .bottom) {
            LinearGradient(
                colors: [.clear, palette.BackgroundDeep.opacity(0.85)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 58)
        }
    }
}

// MARK: - Preview pane

/// The pane above the wheel. LIVE splits 0.6 / 0.4 between the frame and
/// the meta column; VOD gives the poster 0.30 (`posterSlotWeight`) and the
/// metadata 0.70.
private struct PreviewPane: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass

    let item: YancoItem?
    let kind: ContentKind
    @Bindable var state: ShellState
    let size: CGSize

    private var compact: Bool { sizeClass == .compact }
    private var inset: CGFloat { compact ? Space.xl : Space.page }

    /// LIVE splits the row 0.6 / 0.4 between the frame and the metadata;
    /// VOD gives the poster 0.30 (`posterSlotWeight`) and metadata 0.70.
    private var frameFraction: CGFloat { kind == .live ? 0.6 : 0.3 }

    private var contentWidth: CGFloat { max(size.width - inset * 2, 1) }
    private var contentHeight: CGFloat { max(size.height - Space.lg * 2, 1) }

    /// The TV lane is wider than it is tall (628 x 335), so side-by-side is
    /// the right shape there and on a landscape iPad. A portrait lane is
    /// the opposite, and keeping the horizontal split would leave the
    /// artwork stranded at 0.6 of the width with several hundred points of
    /// dead air above and below it. Stacking uses that height instead.
    private var stacked: Bool { compact || size.height > size.width * 0.9 }

    private var frameWidth: CGFloat {
        stacked ? contentWidth : (contentWidth - Space.xxxl) * frameFraction
    }
    private var metaWidth: CGFloat {
        stacked ? contentWidth : (contentWidth - Space.xxxl) * (1 - frameFraction)
    }

    var body: some View {
        if let item {
            Group {
                if stacked {
                    VStack(alignment: .leading, spacing: Space.xl) {
                        if !compact {
                            frame(item)
                                .frame(width: frameWidth, height: contentHeight * 0.52)
                        }
                        meta(item).frame(width: metaWidth, alignment: .leading)
                    }
                } else {
                    HStack(alignment: .center, spacing: Space.xxxl) {
                        frame(item)
                            .frame(width: frameWidth, height: contentHeight)
                        meta(item).frame(width: metaWidth, alignment: .leading)
                    }
                }
            }
            // Compact hides the artwork frame, so the metadata is short —
            // centring it in a tall pane leaves it stranded in the middle
            // with dead space above and below. Top-align there.
            .frame(
                maxWidth: .infinity,
                maxHeight: .infinity,
                alignment: compact ? .topLeading : .leading
            )
            .padding(.horizontal, inset)
            .padding(.vertical, Space.lg)
        } else {
            Color.clear
        }
    }

    private func frame(_ item: YancoItem) -> some View {
        TileArt(
            url: kind == .live
                ? Artwork.backdrop(item.backdropSeed, width: 800)
                : Artwork.poster(item.posterSeed ?? item.backdropSeed, width: 500),
            seed: kind == .live ? item.backdropSeed : (item.posterSeed ?? item.backdropSeed),
            monogram: item.monogram,
            group: item.group
        )
        .aspectRatio(kind == .live ? 16.0 / 9.0 : ShellDim.posterAspect, contentMode: .fit)
        .overlay {
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .clear, location: 0.85),
                    .init(color: palette.BackgroundDeep.opacity(0.55), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .clipShape(YancoShapes.cutCornerCardLarge)
        .overlay {
            YancoShapes.cutCornerCardLarge
                .stroke(
                    LinearGradient(
                        colors: [palette.Accent.opacity(0.45), palette.PanelBorder],
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    lineWidth: 1
                )
        }
    }

    private func meta(_ item: YancoItem) -> some View {
        VStack(alignment: .leading, spacing: Space.md) {
            FlowLayout(horizontalSpacing: Space.sm, verticalSpacing: Space.xs) {
                Text(overline)
                    .yancoType(YancoType.overline)
                    .foregroundStyle(palette.Accent)
                    .lineLimit(1)
                    .fixedSize()
                if kind == .live { LivePill() }
            }

            Text(item.title)
                .yancoType(YancoType.displayS)
                .foregroundStyle(palette.TextPrimary)
                .lineLimit(2)

            if kind == .live {
                if let now = item.nowTitle {
                    Text(now)
                        .yancoType(YancoType.titleM)
                        .foregroundStyle(palette.TextPrimary)
                        .lineLimit(2)
                }
                EpgProgressLine(
                    progress: item.nowProgress ?? 0,
                    caption: "\(Int((1 - (item.nowProgress ?? 0)) * 60)) min left"
                )
                if let next = item.nextTitle {
                    Text("Up next: \(next)")
                        .yancoType(YancoType.caption)
                        .foregroundStyle(palette.TextMuted)
                        .lineLimit(1)
                }
            } else {
                Text(facts(item))
                    .yancoType(YancoType.captionStrong)
                    .foregroundStyle(palette.TextSecondary)
                Text(item.plot)
                    .yancoType(YancoType.bodyLong)
                    .foregroundStyle(palette.TextSecondary)
                    .lineLimit(4)
            }

            Spacer().frame(height: Space.xs)

            // FlowRow, as on Android — the CTAs wrap to a second line
            // rather than compressing or overflowing the narrow column.
            FlowLayout {
                HexCta(
                    title: kind == .live ? "Watch live" : "Play now",
                    symbol: "play.fill",
                    primary: true
                ) {
                    state.detailItem = item
                }
                HexCta(
                    title: state.isFavorite(item) ? "In favorites" : "Favorite",
                    symbol: state.isFavorite(item) ? "heart.fill" : "heart",
                    highlighted: state.isFavorite(item)
                ) {
                    state.toggleFavorite(item)
                }
            }
        }
    }

    private var overline: String {
        switch kind {
        case .live: return "LIVE CHANNEL"
        case .movie: return "FEATURE"
        case .series: return "SERIES"
        }
    }

    private func facts(_ item: YancoItem) -> String {
        var parts: [String] = []
        if let year = item.year { parts.append(String(year)) }
        if let rating = item.rating { parts.append(String(format: "%.1f ★", rating)) }
        if let quality = item.quality { parts.append(quality) }
        parts.append(item.group)
        if let seasons = item.seasonSummary { parts.append(seasons) }
        return parts.joined(separator: "  ·  ")
    }
}
