import SwiftUI

/// Port of `ui/theme/Typography.kt`.
///
/// The ramp is carried over 1:1 (12 → 34), including the MK.29.4
/// compression: largest-to-smallest is 2.8x, not the 4.4x it started at.
/// The Kotlin KDoc derives those numbers from angular size at 10 ft on a
/// 55" panel; they land in a sane place for a hand-held screen too, and
/// keeping them identical is what makes the two apps read as one product.
///
/// Compose's `lineHeight` sets total line box height; SwiftUI's
/// `lineSpacing` adds space *between* lines. `YancoTextStyle` converts —
/// see `lineSpacing` below.
struct YancoTextStyle {
    var size: CGFloat
    var lineHeight: CGFloat
    var weight: Font.Weight
    var tracking: CGFloat = 0
    var italic: Bool = false

    var font: Font {
        let base = Font.system(size: size, weight: weight)
        return italic ? base.italic() : base
    }

    /// Compose line box → SwiftUI inter-line gap. The system font's
    /// natural line box is ≈1.2x the point size, so the extra we ask for
    /// is whatever the design's line height wants beyond that. Clamped at
    /// 0 — a negative gap would tighten lines past what the glyphs allow.
    var lineSpacing: CGFloat { max(0, lineHeight - size * 1.2) }
}

enum YancoType {
    /// Small uppercase labels above sections ("LIVE", "EPISODES").
    /// Letterspacing makes these read larger than their nominal size.
    static let overline = YancoTextStyle(size: 12, lineHeight: 16, weight: .bold, tracking: 1.6)
    /// Supporting metadata — channel numbers, timestamps.
    static let caption = YancoTextStyle(size: 12, lineHeight: 16, weight: .medium, tracking: 0.2)
    static let captionStrong = YancoTextStyle(size: 12, lineHeight: 16, weight: .semibold, tracking: 0.2)
    /// Most row subtitles / muted descriptions.
    static let body = YancoTextStyle(size: 14, lineHeight: 19, weight: .regular)
    static let bodyStrong = YancoTextStyle(size: 14, lineHeight: 19, weight: .semibold)
    /// Multi-line reading text (plot synopses). Loosest leading in the scale.
    static let bodyLong = YancoTextStyle(size: 15, lineHeight: 22, weight: .regular)
    /// Buttons, chips, sidebar rows.
    static let label = YancoTextStyle(size: 15, lineHeight: 20, weight: .semibold, tracking: 0.2)
    static let labelStrong = YancoTextStyle(size: 15, lineHeight: 20, weight: .bold, tracking: 0.2)
    /// Row titles ("Live TV"), card titles.
    static let titleS = YancoTextStyle(size: 16, lineHeight: 21, weight: .semibold)
    static let titleM = YancoTextStyle(size: 19, lineHeight: 25, weight: .semibold)
    static let titleL = YancoTextStyle(size: 23, lineHeight: 29, weight: .bold)
    /// Section banners, hero + preview titles.
    static let displayS = YancoTextStyle(size: 26, lineHeight: 32, weight: .bold, tracking: -0.4)
    static let displayM = YancoTextStyle(size: 30, lineHeight: 38, weight: .bold, tracking: -0.6)
    static let displayCinematic = YancoTextStyle(
        size: 34, lineHeight: 42, weight: .bold, tracking: -0.7, italic: true
    )
}

extension View {
    /// `Text("Live TV").yancoType(.titleM)` — applies font, tracking and
    /// the converted line spacing in one call, mirroring Compose's
    /// `style = YancoType.TitleM`.
    func yancoType(_ style: YancoTextStyle) -> some View {
        font(style.font)
            .tracking(style.tracking)
            .lineSpacing(style.lineSpacing)
    }
}
