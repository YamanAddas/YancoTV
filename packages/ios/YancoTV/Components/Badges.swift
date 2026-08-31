import SwiftUI

/// The small chrome pieces shared by tiles, heroes and the preview pane.
/// Ported from `HomeContent.kt`, `FeatureHero.kt`, `WatchIndicator.kt` and
/// `CoverflowSectionScreen.kt` — same shapes, same alphas, same paddings.

/// `LIVE` pill — solid red, white dot, uppercase overline.
struct LivePill: View {
    @Environment(\.yancoPalette) private var palette

    var body: some View {
        HStack(spacing: Space.xs) {
            Circle()
                .fill(.white)
                .frame(width: 6, height: 6)
            Text("LIVE")
                .yancoType(YancoType.overline)
                .foregroundStyle(.white)
        }
        .padding(.horizontal, Space.md)
        .padding(.vertical, Space.xs)
        .background(palette.Live, in: ChipBevelShape())
    }
}

/// `LOCKED` chip — parental gate marker.
struct LockChip: View {
    @Environment(\.yancoPalette) private var palette

    var body: some View {
        HStack(spacing: Space.xs) {
            Image(systemName: "lock")
                .font(.system(size: 10, weight: .semibold))
            Text("LOCKED")
                .yancoType(YancoType.overline)
        }
        .foregroundStyle(palette.Accent)
        .padding(.horizontal, Space.md)
        .padding(.vertical, Space.xs)
        .background(palette.BackgroundDeep.opacity(0.85), in: ChipBevelShape())
        .overlay { ChipBevelShape().stroke(palette.Accent.opacity(0.45), lineWidth: 1) }
    }
}

/// Group / type chip on a tile's bottom-left. Label capped at 28 chars,
/// as in `HomeContent.TypeChip`.
struct TypeChip: View {
    @Environment(\.yancoPalette) private var palette
    let label: String

    var body: some View {
        Text(String(label.prefix(28)))
            .yancoType(YancoType.caption)
            .foregroundStyle(palette.TextSecondary)
            .lineLimit(1)
            .padding(.horizontal, Space.md)
            .padding(.vertical, 3)
            .background(palette.BackgroundDeep.opacity(0.72), in: ChipBevelShape())
    }
}

/// Quality marker — `4K` gets the premium gold, everything else is muted.
struct QualityChip: View {
    @Environment(\.yancoPalette) private var palette
    let quality: String

    private var tint: Color { quality == "4K" ? palette.Premium : palette.TextSecondary }

    var body: some View {
        Text(quality)
            .yancoType(YancoType.captionStrong)
            .foregroundStyle(tint)
            .padding(.horizontal, Space.sm)
            .padding(.vertical, 2)
            .background(palette.BackgroundDeep.opacity(0.72), in: RoundedRectangle(cornerRadius: Radius.chip))
            .overlay {
                RoundedRectangle(cornerRadius: Radius.chip)
                    .stroke(tint.opacity(0.4), lineWidth: 1)
            }
    }
}

/// Small dark pill used for resume / watched / start-time markers.
/// `WatchIndicator.kt`: BackgroundDeep 75%, h8/v3, 10pt icon, 4pt gap.
struct StatusBadge: View {
    @Environment(\.yancoPalette) private var palette
    let symbol: String
    let label: String
    var tint: Color?

    var body: some View {
        HStack(spacing: Space.xs) {
            Image(systemName: symbol)
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(tint ?? palette.Accent)
            Text(label)
                .yancoType(YancoType.caption)
                .foregroundStyle(palette.TextPrimary)
                .lineLimit(1)
        }
        .padding(.horizontal, Space.sm)
        .padding(.vertical, 3)
        .background(palette.BackgroundDeep.opacity(0.75), in: Capsule())
    }
}

/// `LIVE` badge for on-air tiles — red wash, white dot.
struct LiveBadge: View {
    @Environment(\.yancoPalette) private var palette

    var body: some View {
        HStack(spacing: Space.xs) {
            Circle()
                .fill(palette.TextPrimary)
                .frame(width: 6, height: 6)
            Text("LIVE")
                .yancoType(YancoType.overline)
                .foregroundStyle(palette.TextPrimary)
        }
        .padding(.horizontal, Space.sm)
        .padding(.vertical, 3)
        .background(palette.Live.opacity(0.88), in: Capsule())
    }
}

/// Progress stripe pinned to the bottom of a tile's art box.
/// 4pt tall, track BackgroundDeep 60%, fill AccentDeep → Accent → AccentGlow.
struct ProgressStripe: View {
    @Environment(\.yancoPalette) private var palette
    let progress: Double
    var height: CGFloat = 4

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(palette.BackgroundDeep.opacity(0.6))
                Rectangle()
                    .fill(
                        LinearGradient(
                            colors: [palette.AccentDeep, palette.Accent, palette.AccentGlow],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .frame(width: geo.size.width * max(0, min(1, progress)))
            }
        }
        .frame(height: height)
    }
}

/// The hero / preview EPG progress line — 3pt track on `BorderSubtle`,
/// same accent gradient fill, caption underneath.
struct EpgProgressLine: View {
    @Environment(\.yancoPalette) private var palette
    let progress: Double
    let caption: String

    var body: some View {
        VStack(alignment: .leading, spacing: Space.xxs) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.BorderSubtle)
                    Capsule()
                        .fill(
                            LinearGradient(
                                colors: [palette.AccentDeep, palette.Accent, palette.AccentGlow],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .frame(width: geo.size.width * max(0, min(1, progress)))
                }
            }
            .frame(height: 3)

            Text(caption)
                .yancoType(YancoType.caption)
                .foregroundStyle(palette.TextMuted)
        }
    }
}
