import CoreGraphics

/// Port of `ui/theme/Dimens.kt`.
///
/// 8-point spacing scale. Every padding / gap in the shell pulls from
/// here so the rhythm stays consistent. `xxs` is the only value below 8
/// (pixel-snug dividers); everything else respects the grid.
enum Space {
    static let xxs: CGFloat = 2
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 20
    static let xxl: CGFloat = 24
    static let xxxl: CGFloat = 32
    static let section: CGFloat = 40
    static let page: CGFloat = 48
}

/// Corner radius scale. Bands rather than a geometric series — picks map
/// to real components so intent is readable from the token.
enum Radius {
    static let chip: CGFloat = 6
    static let control: CGFloat = 10
    static let card: CGFloat = 14
    static let panel: CGFloat = 20
    static let hero: CGFloat = 28
    static let pill: CGFloat = 999
}

/// Structural dimensions for the shell.
///
/// The TV values assume a 960x540 dp viewport (Fire TV at 1920x1080 /
/// 320 dpi). An iPhone in portrait is roughly 390x844 pt — taller than it
/// is wide, and half the width — so the handful of values that are
/// explicitly TV-viewport-derived get a compact counterpart here rather
/// than being scaled blindly. Anything without a `compact` twin is
/// viewport-independent and ports as-is.
enum ShellDim {
    static let sidebarCollapsed: CGFloat = 92
    static let sidebarExpanded: CGFloat = 260

    static let categoriesPanelWidth: CGFloat = 240

    static let rowThumb: CGFloat = 56
    static let posterTile: CGFloat = 220
    static let posterTileAspect: CGFloat = 16.0 / 9.0

    /// Width ÷ height of a VOD poster frame. Movie/series artwork is
    /// portrait — Xtream `stream_icon` / `cover_big` and TMDB
    /// `poster_path` are both 2:3. Frames are height-driven and derive
    /// width from this so the whole poster stays on screen (MB-303).
    static let posterAspect: CGFloat = 2.0 / 3.0

    /// Backdrop height on the content detail page. 330 is 61% of the TV
    /// viewport; on a phone the same fraction of a much taller screen
    /// would swallow the fold, so compact uses a flatter hero.
    static let heroHeight: CGFloat = 330
    static let heroHeightCompact: CGFloat = 260

    /// How far the detail page's title/actions column is pushed down so
    /// it sits in the dark band of the backdrop gradient rather than over
    /// the bright middle of the artwork.
    static let detailHeroContentOffset: CGFloat = 140
    static let detailHeroContentOffsetCompact: CGFloat = 96

    static let detailPosterWidth: CGFloat = 200
    static let detailPosterWidthCompact: CGFloat = 132

    // MARK: - Rail card sizing

    /// Landscape channel tile (Live TV rails).
    static let channelTileWidth: CGFloat = 240
    static let channelTileWidthCompact: CGFloat = 190

    /// Portrait poster tile (Movies / Series rails).
    static let posterTileWidth: CGFloat = 150
    static let posterTileWidthCompact: CGFloat = 118
}
