import SwiftUI

/// Port of `ui/theme/Palette.kt`.
///
/// Same 21 tokens, same names, same hex values as the Android/TV app — a
/// palette swap on one platform should be a mechanical copy to the other.
/// Field names deliberately keep the Kotlin capitalisation so a diff
/// against `Palette.kt` is line-for-line readable.
struct YancoPalette: Equatable {
    // Base canvases. BackgroundDeep is the near-black floor; each step adds
    // a touch more colour saturation so panels read as lit by the accent
    // without changing hue.
    let BackgroundDeep: Color
    let BackgroundRaised: Color
    let BackgroundHover: Color
    let BackgroundElevated: Color
    // Hairline borders. Translucent so the canvas bleeds through.
    let BorderSubtle: Color
    let PanelBorder: Color
    // Text ramp — primary / secondary / muted / faint.
    let TextPrimary: Color
    let TextSecondary: Color
    let TextMuted: Color
    let TextFaint: Color
    // Brand accent + partners for gradients / idle progress fills.
    let Accent: Color
    let AccentSoft: Color
    let AccentDeep: Color
    let AccentGlow: Color
    let AccentMuted: Color
    // Status accents.
    let Live: Color
    let Premium: Color
    let Error: Color
    let FocusRing: Color
    // Semi-transparent layers.
    let Scrim: Color
    let Veil: Color
}

extension YancoPalette {
    /// Concept A — "Frosted Glass Emerald". The default on every platform.
    static let frostedEmerald = YancoPalette(
        BackgroundDeep: Color(hex: 0x050A08),
        BackgroundRaised: Color(hex: 0x0A1410),
        BackgroundHover: Color(hex: 0x0F1C17),
        BackgroundElevated: Color(hex: 0x14251F),
        BorderSubtle: Color(hex: 0xFFFFFF, alpha: 0x14 / 255),
        PanelBorder: Color(hex: 0xFFFFFF, alpha: 0x1F / 255),
        TextPrimary: Color(hex: 0xF0FFF6),
        TextSecondary: Color(hex: 0xA7B8AF),
        TextMuted: Color(hex: 0x5F7068),
        TextFaint: Color(hex: 0x56675F),
        Accent: Color(hex: 0x00E28A),
        AccentSoft: Color(hex: 0x66F0B5),
        AccentDeep: Color(hex: 0x00B872),
        AccentGlow: Color(hex: 0x66F0B5),
        AccentMuted: Color(hex: 0x1C7A55),
        Live: Color(hex: 0xFF3B3B),
        Premium: Color(hex: 0xD7B36A),
        Error: Color(hex: 0xFF6B6B),
        FocusRing: Color(hex: 0x66F0B5),
        Scrim: Color(hex: 0x050A08, alpha: 0xCC / 255),
        Veil: Color(hex: 0x000000, alpha: 0x66 / 255)
    )

    /// "Midnight Sapphire" — cool blue-black canvases, cobalt accent.
    static let midnightSapphire = YancoPalette(
        BackgroundDeep: Color(hex: 0x050810),
        BackgroundRaised: Color(hex: 0x0A1020),
        BackgroundHover: Color(hex: 0x0F1730),
        BackgroundElevated: Color(hex: 0x14203F),
        BorderSubtle: Color(hex: 0xFFFFFF, alpha: 0x14 / 255),
        PanelBorder: Color(hex: 0xFFFFFF, alpha: 0x1F / 255),
        TextPrimary: Color(hex: 0xEDF3FF),
        TextSecondary: Color(hex: 0xA7B3CC),
        TextMuted: Color(hex: 0x5F6B85),
        TextFaint: Color(hex: 0x565F78),
        Accent: Color(hex: 0x4A8CFF),
        AccentSoft: Color(hex: 0x8FB6FF),
        AccentDeep: Color(hex: 0x2A6BD8),
        AccentGlow: Color(hex: 0x8FB6FF),
        AccentMuted: Color(hex: 0x1F3F7A),
        Live: Color(hex: 0xFF3B3B),
        Premium: Color(hex: 0xD7B36A),
        Error: Color(hex: 0xFF6B6B),
        FocusRing: Color(hex: 0x8FB6FF),
        Scrim: Color(hex: 0x050810, alpha: 0xCC / 255),
        Veil: Color(hex: 0x000000, alpha: 0x66 / 255)
    )

    /// "Warm Amber" — charcoal warmed toward brown, amber accent.
    static let warmAmber = YancoPalette(
        BackgroundDeep: Color(hex: 0x0E0907),
        BackgroundRaised: Color(hex: 0x18110D),
        BackgroundHover: Color(hex: 0x231811),
        BackgroundElevated: Color(hex: 0x2E1F15),
        BorderSubtle: Color(hex: 0xFFFFFF, alpha: 0x14 / 255),
        PanelBorder: Color(hex: 0xFFFFFF, alpha: 0x1F / 255),
        TextPrimary: Color(hex: 0xFFF3E5),
        TextSecondary: Color(hex: 0xC9B5A0),
        TextMuted: Color(hex: 0x7A6A5C),
        TextFaint: Color(hex: 0x6E5F52),
        Accent: Color(hex: 0xFFB14A),
        AccentSoft: Color(hex: 0xFFD18F),
        AccentDeep: Color(hex: 0xE08826),
        AccentGlow: Color(hex: 0xFFD18F),
        AccentMuted: Color(hex: 0x7A4A1F),
        Live: Color(hex: 0xFF3B3B),
        Premium: Color(hex: 0xD7B36A),
        Error: Color(hex: 0xFF6B6B),
        FocusRing: Color(hex: 0xFFD18F),
        Scrim: Color(hex: 0x0E0907, alpha: 0xCC / 255),
        Veil: Color(hex: 0x000000, alpha: 0x66 / 255)
    )

    /// "Monochrome" — no accent hue. Doubles as the check that nothing in
    /// the UI relies on colour alone to convey state.
    static let monochrome = YancoPalette(
        BackgroundDeep: Color(hex: 0x080808),
        BackgroundRaised: Color(hex: 0x121212),
        BackgroundHover: Color(hex: 0x1C1C1C),
        BackgroundElevated: Color(hex: 0x262626),
        BorderSubtle: Color(hex: 0xFFFFFF, alpha: 0x14 / 255),
        PanelBorder: Color(hex: 0xFFFFFF, alpha: 0x1F / 255),
        TextPrimary: Color(hex: 0xF5F5F5),
        TextSecondary: Color(hex: 0xB8B8B8),
        TextMuted: Color(hex: 0x6E6E6E),
        TextFaint: Color(hex: 0x616161),
        Accent: Color(hex: 0xE0E0E0),
        AccentSoft: Color(hex: 0xFFFFFF),
        AccentDeep: Color(hex: 0xB0B0B0),
        AccentGlow: Color(hex: 0xFFFFFF),
        AccentMuted: Color(hex: 0x555555),
        Live: Color(hex: 0xFF6B6B),
        Premium: Color(hex: 0xD7B36A),
        Error: Color(hex: 0xFF6B6B),
        FocusRing: Color(hex: 0xFFFFFF),
        Scrim: Color(hex: 0x080808, alpha: 0xCC / 255),
        Veil: Color(hex: 0x000000, alpha: 0x66 / 255)
    )
}

// MARK: - Palette scope

/// Mirrors Compose's `LocalYancoPalette` — every surface reads colours
/// through `@Environment(\.yancoPalette)` so a theme swap recomposes the
/// whole tree, and a view rendered outside a themed container still gets
/// the Emerald default rather than crashing.
private struct YancoPaletteKey: EnvironmentKey {
    static let defaultValue: YancoPalette = .frostedEmerald
}

extension EnvironmentValues {
    var yancoPalette: YancoPalette {
        get { self[YancoPaletteKey.self] }
        set { self[YancoPaletteKey.self] = newValue }
    }
}

// MARK: - Hex helper

extension Color {
    /// `Color(hex: 0x00E28A)` — keeps the Swift palette visually diffable
    /// against the Kotlin `Color(0xFF00E28A)` literals it ports.
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}
