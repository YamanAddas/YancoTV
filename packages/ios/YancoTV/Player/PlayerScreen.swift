import AVKit
import SwiftUI

/// Full-screen player — port of `PlayerActivity` + `VodPlayerDock`.
///
/// ### Which design this is
///
/// `PRODUCTION_PLAN_NATIVE.md`'s MK.16 entry describes a **superseded**
/// dock: a 44sp gradient title, a hex-clipped 8dp track, an 88dp glowing
/// play orb, a PREV control and a remote hint strip. MK.34.2–34.10
/// replaced all of it, and `VodDockTransportRow`'s own KDoc repudiates the
/// old hero directly — "a solid green-black button with a glow, which is
/// exactly the 'excessive glow / 3D-game styling' the brief warns off".
/// This is a port of the **shipped code**, not of that plan text.
///
/// The dock is one bottom-anchored column: metadata, a glass progress
/// ribbon, and a single glass slab holding transport *and* secondary
/// controls separated by a hairline — not two separate strips.
struct PlayerScreen: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let item: YancoItem
    @Bindable var controller: PlaybackController
    @Bindable var state: ShellState
    let onClose: () -> Void

    @State private var chromeVisible = true
    @State private var aspect: VideoAspect = .fit
    @State private var scrubbing = false
    @State private var scrubTarget: Double = 0
    @State private var hideTask: Task<Void, Never>?

    private var compact: Bool { sizeClass == .compact }

    var body: some View {
        GeometryReader { geo in
            let metrics = PlayerChromeMetrics.dock(width: geo.size.width, compact: compact)

            ZStack {
                Color.black.ignoresSafeArea()

                if let engine = controller.engine {
                    EngineSurface(
                        engineID: ObjectIdentifier(engine),
                        makeSurface: { engine.surface }
                    )
                    .ignoresSafeArea()
                }

                if controller.isBuffering && controller.errorMessage == nil {
                    BufferingOverlay()
                }

                if let error = controller.errorMessage {
                    PlayerErrorOverlay(message: error, title: item.title, onClose: onClose)
                }

                if chromeVisible {
                    VStack(spacing: 0) {
                        topBar
                        Spacer(minLength: 0)
                        dock(metrics, width: geo.size.width)
                    }
                    // Without an explicit width the trailing `Spacer` in the
                    // top bar expands into the ZStack's unbounded proposal
                    // and pushes the LIVE pill off the right edge.
                    .frame(width: geo.size.width)
                    .transition(.opacity)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                withAnimation(.easeOut(duration: 0.2)) { chromeVisible.toggle() }
                if chromeVisible { scheduleHide() }
            }
        }
        .statusBarHidden(!chromeVisible)
        .task {
            await controller.load(item)
            scheduleHide()
        }
        .onDisappear {
            hideTask?.cancel()
            controller.teardown()
        }
    }

    // MARK: - Top bar

    private var topBar: some View {
        HStack(spacing: Space.md) {
            Button(action: onClose) {
                Image(systemName: "chevron.down")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(palette.TextPrimary)
                    .frame(width: 40, height: 40)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close player")

            Spacer()

            if controller.isLive { LivePill() }
        }
        .padding(.horizontal, compact ? Space.xl : Space.page)
        .padding(.top, Space.md)
    }

    // MARK: - Dock

    private func dock(_ m: PlayerChromeMetrics.Dock, width: CGFloat) -> some View {
        // Inner width after the dock's own horizontal padding — the base
        // the 0.55 and 0.78 fractions are taken from.
        let inner = max(width - (compact ? Space.xl : Space.page) * 2, 1)

        return VStack(spacing: 0) {
            metadata(m, inner: inner)
                .frame(maxWidth: .infinity, alignment: .leading)

            Spacer().frame(height: 6)

            if !controller.isLive {
                progressRibbon(m, inner: inner)
                Spacer().frame(height: 6)
            }

            transportSlab(m)
        }
        // The scrim is painted over the column's own measured height, then
        // padding is applied — so it covers the padding too, as on Android.
        .background(
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: palette.BackgroundDeep.opacity(0.42), location: 0.5),
                    .init(color: palette.BackgroundDeep.opacity(0.86), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        )
        .padding(.horizontal, compact ? Space.xl : Space.page)
        .padding(.top, 6)
        .padding(.bottom, 10)
    }

    // MARK: Metadata

    private func metadata(_ m: PlayerChromeMetrics.Dock, inner: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("NOW PLAYING")
                .font(.system(size: 9, weight: .semibold))
                .tracking(1.8)
                .foregroundStyle(palette.Accent)

            Spacer().frame(height: 3)

            // Solid TextPrimary at 10–15pt. Not a gradient, and nowhere
            // near the 44sp the stale plan text describes.
            Text(item.title.isEmpty ? "—" : item.title)
                .font(.system(size: m.titleFont, weight: .semibold))
                .tracking(-0.2)
                .foregroundStyle(palette.TextPrimary)
                .lineLimit(1)
                .truncationMode(.tail)

            if !metadataLine.isEmpty {
                Spacer().frame(height: 3)
                // Separator is "  ·  " with doubled spaces — at this size a
                // bare middot collides with digits.
                Text(metadataLine)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(palette.TextSecondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }

            Spacer().frame(height: 5)
            typeBadge
        }
        // 55% of the inner width, so the block never reaches the centre of
        // the frame "where the reference shot has a face".
        //
        // This was `.infinity * 0.55`, which is just infinity — the
        // constraint silently did nothing and the block ran full width.
        .frame(maxWidth: inner * 0.55, alignment: .leading)
    }

    private var metadataLine: String {
        var parts: [String] = []
        if let year = item.year { parts.append(String(year)) }
        if let seasons = item.seasonSummary { parts.append(seasons) }
        if !item.group.isEmpty { parts.append(item.group) }
        return parts.joined(separator: "  ·  ")
    }

    /// Outlined only — accent at 50% for the border, full accent for the
    /// text, no fill.
    private var typeBadge: some View {
        Text(typeLabel)
            .font(.system(size: 8, weight: .semibold))
            .tracking(1.2)
            .foregroundStyle(palette.Accent)
            .lineLimit(1)
            .padding(.horizontal, 11)
            .padding(.vertical, 4)
            .overlay {
                YancoShapes.hexCapsule.stroke(palette.Accent.opacity(0.5), lineWidth: 1)
            }
    }

    private var typeLabel: String {
        switch item.kind {
        case .live: return "LIVE"
        case .movie: return "MOVIE"
        case .series: return "EPISODE"
        }
    }

    // MARK: Progress ribbon

    private var displayedTime: Double { scrubbing ? scrubTarget : controller.currentTime }

    private func progressRibbon(_ m: PlayerChromeMetrics.Dock, inner: CGFloat) -> some View {
        HStack(spacing: m.horizontalPadding) {
            Text(DockTime.clock(displayedTime))
                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                .foregroundStyle(palette.TextPrimary)

            track(m)

            HStack(spacing: 6) {
                Text(DockTime.remaining(displayedTime, duration: controller.duration))
                    .font(.system(size: 9, weight: .semibold, design: .monospaced))
                    .foregroundStyle(palette.TextPrimary)
                if let ends = DockTime.endsAt(displayedTime, duration: controller.duration) {
                    Text(ends)
                        .font(.system(size: 8, weight: .regular, design: .monospaced))
                        .foregroundStyle(palette.TextMuted)
                        .truncationMode(.tail)
                }
            }
            .frame(maxWidth: 132)
        }
        .padding(.horizontal, m.horizontalPadding)
        .padding(.vertical, m.verticalPadding)
        .glassSurface(Capsule(), palette: palette, alpha: 0.75)
        // Same bug as the metadata block: `.infinity * 0.78` is infinity.
        .frame(maxWidth: inner * 0.78)
        // Seek keys and drags are physical, so the ribbon never mirrors.
        .environment(\.layoutDirection, .leftToRight)
    }

    private func track(_ m: PlayerChromeMetrics.Dock) -> some View {
        GeometryReader { geo in
            let width = geo.size.width
            // MB-340: an unprepared stream must render an *empty* track,
            // not a full one.
            let ready = controller.duration > 0
            let played = ready ? min(1, max(0, displayedTime / controller.duration)) : 0
            let buffered = ready ? min(1, max(0, controller.bufferedTime / controller.duration)) : 0

            ZStack(alignment: .leading) {
                Capsule().fill(palette.TextMuted.opacity(0.35)).frame(height: 3)
                Capsule().fill(palette.TextSecondary.opacity(0.3))
                    .frame(width: width * buffered, height: 3)
                Capsule().fill(palette.AccentSoft)
                    .frame(width: width * played, height: 3)

                // A hexagon, not a circle — the signature shape carries
                // into the timeline instead of stopping at the dock.
                MidnightHexShape()
                    .fill(palette.Accent)
                    .frame(width: 10, height: 10)
                    .offset(x: width * played - 5)
            }
            .frame(height: geo.size.height, alignment: .center)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard ready else { return }
                        scrubbing = true
                        scrubTarget = Double(value.location.x / width) * controller.duration
                        resetHide()
                    }
                    .onEnded { _ in
                        guard ready else { return }
                        controller.seek(to: scrubTarget)
                        scrubbing = false
                        scheduleHide()
                    }
            )
        }
        // 14pt is the touch surface; the visible bar is 3pt.
        .frame(height: 14)
        .frame(maxWidth: .infinity)
    }

    // MARK: Transport slab

    /// The slab holds eleven controls. At TV and iPad widths they fit
    /// comfortably; on a phone in portrait the row is wider than the
    /// screen, so it scrolls rather than dropping controls — the Android
    /// set is the design, and hiding half of it to fit would be a worse
    /// answer than letting the user reach them.
    private func transportSlab(_ m: PlayerChromeMetrics.Dock) -> some View {
        ScrollView(.horizontal) {
            transportRow(m)
        }
        .scrollIndicators(.hidden)
        .scrollBounceBehavior(.basedOnSize)
        .frame(maxWidth: .infinity)
    }

    private func transportRow(_ m: PlayerChromeMetrics.Dock) -> some View {
        HStack(spacing: m.gap) {
            hexControl("-10", size: m.transport, font: 9, enabled: !controller.isLive) {
                controller.skip(-10)
            }
            hexControl(
                controller.isPlaying ? "II" : "▶",
                size: m.hero,
                font: 16,
                hero: true
            ) {
                controller.togglePlayPause()
            }
            hexControl("+10", size: m.transport, font: 9, enabled: !controller.isLive) {
                controller.skip(10)
            }

            // 1pt divider at 62% of the secondary height.
            Rectangle()
                .fill(palette.PanelBorder)
                .frame(width: 1, height: m.secondary * 0.62)
                .padding(.horizontal, 4)

            hexControl("CC", size: m.secondary, font: 9, wide: true, enabled: false) {}
            hexControl("AUDIO", size: m.secondary, font: 9, wide: true, enabled: false) {}
            hexControl("SPEED", size: m.secondary, font: 9, wide: true, enabled: false) {}
            hexControl(aspect.rawValue, size: m.secondary, font: 9, wide: true) {
                aspect = aspect.next
                controller.setAspectFill(aspect.isFill)
            }
            hexControl(
                "♥", size: m.secondary, font: 11,
                selected: state.isFavorite(item)
            ) {
                state.toggleFavorite(item)
            }
            hexControl("•••", size: m.secondary, font: 11, enabled: false) {}
        }
        .padding(.horizontal, m.horizontalPadding)
        .padding(.vertical, m.verticalPadding)
        .glassSurface(RoundedRectangle(cornerRadius: 18), palette: palette)
        .environment(\.layoutDirection, .leftToRight)
    }

    /// `HexControl` — the one control the whole dock is built from.
    ///
    /// Focus/press is "a 2pt champagne ring plus a small lift in fill
    /// opacity, and nothing else". No glow, no shadow, no scale-on-press:
    /// those are precisely what MK.34 removed.
    private func hexControl(
        _ label: String,
        size: CGFloat,
        font: CGFloat,
        hero: Bool = false,
        wide: Bool = false,
        selected: Bool = false,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        let width = wide
            ? PlayerChromeMetrics.chipWidth(label: label, secondary: size)
            : size

        let fill: LinearGradient = {
            if !enabled {
                return LinearGradient(
                    colors: [palette.BackgroundRaised.opacity(0.45), palette.BackgroundRaised.opacity(0.30)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
            }
            if hero || selected {
                return LinearGradient(
                    colors: [palette.Accent.opacity(0.32), palette.Accent.opacity(0.14)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
            }
            return LinearGradient(
                colors: [palette.BackgroundElevated.opacity(0.72), palette.BackgroundDeep.opacity(0.58)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        }()

        let ring: Color = {
            if !enabled { return palette.BorderSubtle.opacity(0.4) }
            if hero || selected { return palette.Accent.opacity(0.55) }
            return palette.PanelBorder
        }()

        let content: Color = {
            if !enabled { return palette.TextMuted }
            if hero || selected { return palette.TextPrimary }
            return palette.TextSecondary
        }()

        return Button {
            action()
            resetHide()
        } label: {
            Text(label)
                .font(.system(size: font, weight: .semibold))
                .tracking(0.6)
                .foregroundStyle(content)
                .lineLimit(1)
                .frame(width: width, height: size)
                .background(fill, in: MidnightHexShape())
                .overlay {
                    MidnightHexShape().stroke(ring, lineWidth: hero ? 2 : 1)
                }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityName(label))
        .accessibilityAddTraits(.isButton)
    }

    private func accessibilityName(_ label: String) -> String {
        switch label {
        case "II": return "Pause"
        case "▶": return "Play"
        case "-10": return "Back 10 seconds"
        case "+10": return "Forward 10 seconds"
        case "♥": return state.isFavorite(item) ? "Remove from favorites" : "Add to favorites"
        case "•••": return "More options"
        default: return label
        }
    }

    // MARK: - Auto-hide

    /// 4 seconds, matching Media3's controller timeout on Android.
    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.2)) { chromeVisible = false }
        }
    }

    private func resetHide() {
        guard chromeVisible else { return }
        scheduleHide()
    }
}

// MARK: - Glass

extension View {
    /// Port of `MidnightGlass.glassSurface`.
    ///
    /// Android omits the backdrop blur only because Compose cannot sample
    /// a `SurfaceView` behind a separate overlay — the KDoc says so. The
    /// original brief asked for `backdrop-filter: blur(18px)`, and
    /// `AVPlayerLayer` + a SwiftUI material genuinely composites, so the
    /// material here is *more* faithful than the reference render, not a
    /// deviation from it. The two-stop tint and 1pt rim sit on top.
    func glassSurface<S: Shape>(_ shape: S, palette: YancoPalette, alpha: Double = 1) -> some View {
        background {
            shape.fill(.ultraThinMaterial)
            shape.fill(
                LinearGradient(
                    colors: [
                        palette.BackgroundElevated.opacity(0.72 * alpha),
                        palette.BackgroundDeep.opacity(0.58 * alpha),
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
        }
        .overlay { shape.stroke(palette.PanelBorder, lineWidth: 1) }
        .clipShape(shape)
    }
}

// MARK: - Time labels

/// Port of `DockTimeLabels.kt`.
enum DockTime {
    /// `H:MM:SS` past an hour, else `M:SS` — no leading-zero hours, so a
    /// 22-minute episode reads `22:14`, not `00:22:14`.
    static func clock(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds)
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%d:%02d", m, s)
    }

    /// Nil-equivalent (an empty string) when duration is unknown or live —
    /// the caller renders elapsed only.
    static func remaining(_ current: Double, duration: Double) -> String {
        guard duration > 0 else { return "" }
        return "-" + clock(max(0, duration - current))
    }

    /// Wall-clock time the title ends at, in the device's locale and zone.
    static func endsAt(_ current: Double, duration: Double) -> String? {
        guard duration > 0 else { return nil }
        let end = Date().addingTimeInterval(duration - current)
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.setLocalizedDateFormatFromTemplate("Hm")
        return "ENDS \(formatter.string(from: end))"
    }
}
