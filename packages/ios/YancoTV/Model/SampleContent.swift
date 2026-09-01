import Foundation

/// Placeholder content model for the MK.iOS.1 shell.
///
/// **This is scaffolding with a scheduled demolition date.** MK.iOS.2
/// replaces it with the real pipeline — `SourceRepository` /
/// `ContentRepository` out of the shared KMP module, fed by the user's
/// own M3U / Xtream / Stalker sources. The shape of `YancoItem`
/// deliberately tracks the fields `ContentItem` already exposes over the
/// bridge, so the swap is a data-source change and not a UI rewrite.
///
/// Nothing here implements behaviour that belongs in `packages/shared/` —
/// it is a fixture, not a second port of the business logic.
enum ContentKind {
    case live
    case movie
    case series
}

struct YancoItem: Identifiable, Hashable {
    let id: String
    let title: String
    let kind: ContentKind
    /// Provider category — "Sports", "News", "Arabic"…
    let group: String
    /// Landscape artwork (16:9) for channel tiles, heroes and backdrops.
    let backdropSeed: String
    /// Portrait artwork (2:3) for VOD posters. Nil for live channels.
    let posterSeed: String?
    let plot: String
    let year: Int?
    /// Provider rating out of 10, when supplied.
    let rating: Double?
    /// "4K" / "HD" / "FHD" — rendered as the quality chip.
    let quality: String?
    /// Channel number, live only.
    let channelNumber: Int?
    /// EPG now-playing title, live only.
    let nowTitle: String?
    /// 0…1 through the current programme, live only.
    let nowProgress: Double?
    /// EPG next-up title, live only.
    let nextTitle: String?
    /// 0…1 resume position, VOD only. Nil when unwatched.
    let resume: Double?
    /// Series only — "3 Seasons · 28 Episodes".
    let seasonSummary: String?
    /// Provider artwork (`tvg-logo` / `stream_icon`). Nil for fixtures and
    /// for the large share of provider URLs that 404 — [ProceduralArt] is
    /// what renders underneath either way.
    let artworkURL: URL?
    /// The playable stream. Nil for fixtures and for series containers,
    /// which are not `Playable` — the shared `toPlayable()` returns null for
    /// them and callers must short-circuit rather than open a player on a
    /// blank URL.
    let streamURL: String?

    init(
        id: String,
        title: String,
        kind: ContentKind,
        group: String,
        backdropSeed: String,
        posterSeed: String? = nil,
        plot: String = "",
        year: Int? = nil,
        rating: Double? = nil,
        quality: String? = nil,
        channelNumber: Int? = nil,
        nowTitle: String? = nil,
        nowProgress: Double? = nil,
        nextTitle: String? = nil,
        resume: Double? = nil,
        seasonSummary: String? = nil,
        artworkURL: URL? = nil,
        streamURL: String? = nil
    ) {
        self.id = id
        self.title = title
        self.kind = kind
        self.group = group
        self.backdropSeed = backdropSeed
        self.posterSeed = posterSeed
        self.plot = plot
        self.year = year
        self.rating = rating
        self.quality = quality
        self.channelNumber = channelNumber
        self.nowTitle = nowTitle
        self.nowProgress = nowProgress
        self.nextTitle = nextTitle
        self.resume = resume
        self.seasonSummary = seasonSummary
        self.artworkURL = artworkURL
        self.streamURL = streamURL
    }

    /// Series containers carry no stream of their own; everything else does
    /// once it comes from a real source.
    var isPlayable: Bool {
        guard let streamURL, !streamURL.isEmpty else { return false }
        return kind != .series
    }

    /// Two-or-three letter monogram, the fallback every IPTV UI needs
    /// because a good share of provider logos 404.
    var monogram: String {
        let words = title
            .replacingOccurrences(of: "HD", with: "")
            .split(separator: " ")
            .filter { !$0.isEmpty }
        if words.count >= 2 {
            return words.prefix(2).map { String($0.prefix(1)) }.joined().uppercased()
        }
        return String(title.prefix(2)).uppercased()
    }
}

/// A titled horizontal row of items — the unit the browse screens are
/// built from, matching the Android rails.
struct Rail: Identifiable {
    let id = UUID()
    let title: String
    let items: [YancoItem]
    /// Live rails render landscape tiles; VOD rails render 2:3 posters.
    var isLandscape: Bool
}

enum SampleContent {

    // MARK: - Live

    static let channels: [YancoItem] = [
        YancoItem(
            id: "ch-bein1", title: "beIN SPORTS 1 HD", kind: .live, group: "Sports",
            backdropSeed: "stadium-night", quality: "FHD", channelNumber: 101,
            nowTitle: "Premier League: Arsenal v Chelsea", nowProgress: 0.62,
            nextTitle: "Match of the Day"
        ),
        YancoItem(
            id: "ch-skysports", title: "Sky Sports Main Event", kind: .live, group: "Sports",
            backdropSeed: "pitch-lights", quality: "4K", channelNumber: 102,
            nowTitle: "Live: Champions League Round of 16", nowProgress: 0.28,
            nextTitle: "The Football Show"
        ),
        YancoItem(
            id: "ch-tnt", title: "TNT Sports 1", kind: .live, group: "Sports",
            backdropSeed: "arena-crowd", quality: "HD", channelNumber: 103,
            nowTitle: "Rugby: Six Nations Highlights", nowProgress: 0.81,
            nextTitle: "Boxing Tonight"
        ),
        YancoItem(
            id: "ch-aljazeera", title: "Al Jazeera Arabic", kind: .live, group: "News",
            backdropSeed: "newsroom-desk", quality: "HD", channelNumber: 201,
            nowTitle: "نشرة الأخبار", nowProgress: 0.44,
            nextTitle: "ما وراء الخبر"
        ),
        YancoItem(
            id: "ch-bbc", title: "BBC One HD", kind: .live, group: "News",
            backdropSeed: "london-skyline", quality: "HD", channelNumber: 202,
            nowTitle: "BBC News at Ten", nowProgress: 0.15,
            nextTitle: "Question Time"
        ),
        YancoItem(
            id: "ch-cnn", title: "CNN International", kind: .live, group: "News",
            backdropSeed: "global-map", quality: "HD", channelNumber: 203,
            nowTitle: "Amanpour", nowProgress: 0.55, nextTitle: "World Sport"
        ),
        YancoItem(
            id: "ch-mbc1", title: "MBC 1", kind: .live, group: "Arabic",
            backdropSeed: "desert-dunes", quality: "HD", channelNumber: 301,
            nowTitle: "مسلسل الاختيار", nowProgress: 0.33, nextTitle: "برنامج المساء"
        ),
        YancoItem(
            id: "ch-rotana", title: "Rotana Cinema", kind: .live, group: "Arabic",
            backdropSeed: "cinema-seats", quality: "HD", channelNumber: 302,
            nowTitle: "فيلم: الفيل الأزرق", nowProgress: 0.71, nextTitle: "فيلم: تراب الماس"
        ),
        YancoItem(
            id: "ch-natgeo", title: "National Geographic", kind: .live, group: "Documentary",
            backdropSeed: "wild-savanna", quality: "4K", channelNumber: 401,
            nowTitle: "Life Below Zero", nowProgress: 0.19, nextTitle: "Drain the Oceans"
        ),
        YancoItem(
            id: "ch-discovery", title: "Discovery HD", kind: .live, group: "Documentary",
            backdropSeed: "mountain-ridge", quality: "HD", channelNumber: 402,
            nowTitle: "Gold Rush", nowProgress: 0.88, nextTitle: "Deadliest Catch"
        ),
        YancoItem(
            id: "ch-cartoon", title: "Cartoon Network", kind: .live, group: "Kids",
            backdropSeed: "bright-shapes", quality: "HD", channelNumber: 501,
            nowTitle: "Adventure Time", nowProgress: 0.4, nextTitle: "Regular Show"
        ),
        YancoItem(
            id: "ch-disney", title: "Disney Channel", kind: .live, group: "Kids",
            backdropSeed: "castle-fireworks", quality: "HD", channelNumber: 502,
            nowTitle: "Phineas and Ferb", nowProgress: 0.66, nextTitle: "Gravity Falls"
        ),
    ]

    // MARK: - Movies

    static let movies: [YancoItem] = [
        YancoItem(
            id: "mv-dune", title: "Dune: Part Two", kind: .movie, group: "Sci-Fi",
            backdropSeed: "dune-sand", posterSeed: "dune-poster",
            plot: "Paul Atreides unites with the Fremen to wage war against House Harkonnen, torn between the love of his life and the fate of the known universe.",
            year: 2024, rating: 8.5, quality: "4K", resume: 0.42
        ),
        YancoItem(
            id: "mv-oppenheimer", title: "Oppenheimer", kind: .movie, group: "Drama",
            backdropSeed: "desert-test", posterSeed: "oppen-poster",
            plot: "The story of J. Robert Oppenheimer and his role in the development of the atomic bomb.",
            year: 2023, rating: 8.4, quality: "4K"
        ),
        YancoItem(
            id: "mv-blade", title: "Blade Runner 2049", kind: .movie, group: "Sci-Fi",
            backdropSeed: "neon-rain", posterSeed: "blade-poster",
            plot: "A young blade runner's discovery of a long-buried secret leads him to track down former blade runner Rick Deckard.",
            year: 2017, rating: 8.0, quality: "4K", resume: 0.77
        ),
        YancoItem(
            id: "mv-interstellar", title: "Interstellar", kind: .movie, group: "Sci-Fi",
            backdropSeed: "space-nebula", posterSeed: "inter-poster",
            plot: "A team of explorers travel through a wormhole in space in an attempt to ensure humanity's survival.",
            year: 2014, rating: 8.7, quality: "FHD"
        ),
        YancoItem(
            id: "mv-heat", title: "Heat", kind: .movie, group: "Thriller",
            backdropSeed: "la-night", posterSeed: "heat-poster",
            plot: "A group of high-end professional thieves start to feel the heat from the LAPD when they unknowingly leave a clue at their latest heist.",
            year: 1995, rating: 8.3, quality: "HD"
        ),
        YancoItem(
            id: "mv-arrival", title: "Arrival", kind: .movie, group: "Sci-Fi",
            backdropSeed: "fog-craft", posterSeed: "arrival-poster",
            plot: "A linguist is recruited by the military to communicate with alien lifeforms after twelve mysterious spacecraft land worldwide.",
            year: 2016, rating: 7.9, quality: "FHD"
        ),
        YancoItem(
            id: "mv-mad", title: "Mad Max: Fury Road", kind: .movie, group: "Action",
            backdropSeed: "desert-chase", posterSeed: "madmax-poster",
            plot: "In a post-apocalyptic wasteland, a woman rebels against a tyrannical ruler in search for her homeland.",
            year: 2015, rating: 8.1, quality: "4K"
        ),
        YancoItem(
            id: "mv-sicario", title: "Sicario", kind: .movie, group: "Thriller",
            backdropSeed: "border-convoy", posterSeed: "sicario-poster",
            plot: "An idealistic FBI agent is enlisted by a government task force to aid in the escalating war against drugs at the border.",
            year: 2015, rating: 7.6, quality: "FHD"
        ),
    ]

    // MARK: - Series

    static let series: [YancoItem] = [
        YancoItem(
            id: "sr-severance", title: "Severance", kind: .series, group: "Drama",
            backdropSeed: "office-corridor", posterSeed: "sev-poster",
            plot: "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.",
            year: 2022, rating: 8.7, quality: "4K", resume: 0.18,
            seasonSummary: "2 Seasons · 19 Episodes"
        ),
        YancoItem(
            id: "sr-chernobyl", title: "Chernobyl", kind: .series, group: "Drama",
            backdropSeed: "reactor-smoke", posterSeed: "cher-poster",
            plot: "In April 1986, an explosion at the Chernobyl nuclear power plant becomes one of the world's worst man-made catastrophes.",
            year: 2019, rating: 9.3, quality: "FHD",
            seasonSummary: "1 Season · 5 Episodes"
        ),
        YancoItem(
            id: "sr-breaking", title: "Breaking Bad", kind: .series, group: "Crime",
            backdropSeed: "rv-desert", posterSeed: "bb-poster",
            plot: "A chemistry teacher diagnosed with cancer turns to manufacturing to secure his family's future.",
            year: 2008, rating: 9.5, quality: "FHD",
            seasonSummary: "5 Seasons · 62 Episodes"
        ),
        YancoItem(
            id: "sr-thelastofus", title: "The Last of Us", kind: .series, group: "Drama",
            backdropSeed: "overgrown-city", posterSeed: "tlou-poster",
            plot: "Twenty years after modern civilization has been destroyed, a hardened survivor takes charge of a 14-year-old girl.",
            year: 2023, rating: 8.7, quality: "4K", resume: 0.55,
            seasonSummary: "2 Seasons · 16 Episodes"
        ),
        YancoItem(
            id: "sr-succession", title: "Succession", kind: .series, group: "Drama",
            backdropSeed: "boardroom-glass", posterSeed: "succ-poster",
            plot: "The Roy family controls one of the biggest media and entertainment conglomerates in the world.",
            year: 2018, rating: 8.9, quality: "FHD",
            seasonSummary: "4 Seasons · 39 Episodes"
        ),
        YancoItem(
            id: "sr-planet", title: "Planet Earth III", kind: .series, group: "Documentary",
            backdropSeed: "ocean-whale", posterSeed: "pe3-poster",
            plot: "David Attenborough returns with a new exploration of the natural world's most spectacular habitats.",
            year: 2023, rating: 9.0, quality: "4K",
            seasonSummary: "1 Season · 8 Episodes"
        ),
    ]

    // MARK: - Derived collections

    /// The hero item — the app opens on this.
    static let featured = movies[0]

    static var continueWatching: [YancoItem] {
        (movies + series).filter { $0.resume != nil }
    }

    static var favorites: [YancoItem] {
        [channels[0], channels[3], movies[1], series[1], channels[8], movies[3]]
    }

    /// Home rails, in render order — mirrors the Android home layout.
    static var homeRails: [Rail] {
        [
            Rail(title: "Continue Watching", items: continueWatching, isLandscape: true),
            Rail(title: "Live Now", items: Array(channels.prefix(6)), isLandscape: true),
            Rail(title: "Movies", items: movies, isLandscape: false),
            Rail(title: "Series", items: series, isLandscape: false),
            Rail(title: "Favorites", items: favorites, isLandscape: true),
        ]
    }

    /// Distinct provider categories per section, "All" prepended by the UI.
    static func groups(for kind: ContentKind) -> [String] {
        let source: [YancoItem]
        switch kind {
        case .live: source = channels
        case .movie: source = movies
        case .series: source = series
        }
        var seen = Set<String>()
        return source.compactMap { seen.insert($0.group).inserted ? $0.group : nil }
    }

    static func items(for kind: ContentKind) -> [YancoItem] {
        switch kind {
        case .live: return channels
        case .movie: return movies
        case .series: return series
        }
    }
}
