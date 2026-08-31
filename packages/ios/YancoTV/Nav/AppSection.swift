import SwiftUI

/// Port of `ui/nav/AppSection.kt` — the nav destinations, in display order.
///
/// Icons: the Android app ships hand-rolled 24x24 vectors at 1.6 stroke
/// with round caps and joins. The SF Symbol equivalents below are the
/// outline (not `.fill`) variants deliberately — this is a line-weight
/// family, and filled glyphs would read as a different product.
enum AppSection: String, CaseIterable, Identifiable {
    case home
    case liveTv
    case guide
    case movies
    case series
    case favorites
    case recordings
    case search
    case settings

    var id: String { rawValue }

    var label: String {
        switch self {
        case .home: return "Home"
        case .liveTv: return "Live TV"
        case .guide: return "Guide"
        case .movies: return "Movies"
        case .series: return "Series"
        case .favorites: return "Favorites"
        case .recordings: return "Recordings"
        case .search: return "Search"
        case .settings: return "Settings"
        }
    }

    var symbol: String {
        switch self {
        case .home: return "house"
        case .liveTv: return "tv"
        case .guide: return "tablecells"
        case .movies: return "film"
        case .series: return "play.tv"
        case .favorites: return "heart"
        case .recordings: return "record.circle"
        case .search: return "magnifyingglass"
        case .settings: return "gearshape"
        }
    }

    /// The browse sections map onto a content type; the rest are their own
    /// screens. Mirrors `AppSection.contentType` on the Kotlin side.
    var contentKind: ContentKind? {
        switch self {
        case .liveTv: return .live
        case .movies: return .movie
        case .series: return .series
        default: return nil
        }
    }

    /// The compact (iPhone) tab bar can't carry nine destinations. These
    /// five are the primary ones; the rest stay reachable from the More
    /// sheet, so nothing is lost — only re-homed.
    static let compactPrimary: [AppSection] = [.home, .liveTv, .movies, .series, .favorites]
    static let compactOverflow: [AppSection] = [.guide, .recordings, .search, .settings]
}
