import Foundation
import Shared

/// A source as the UI needs it — a Swift value type, so it can cross from
/// the shared queue to the main actor. Mapped from the Kotlin `Source`
/// inside `SharedServices.run`.
struct SourceSummary: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let typeLabel: String
    let url: String?
    let channelCount: Int
    let lastSynced: Date?
    let lastSyncError: String?
    let isActive: Bool

    var subtitle: String {
        if let lastSyncError, !lastSyncError.isEmpty { return lastSyncError }
        if channelCount > 0 {
            return "\(channelCount.formatted()) channels · \(typeLabel)"
        }
        return typeLabel
    }
}

/// Reads and writes the user's library through the shared Kotlin
/// repositories.
///
/// Replaces `SampleContent` as the app's data source. The sample fixture is
/// still reachable behind a debug flag (`YANCO_SAMPLE=1`) because it is what
/// the design screenshots are captured against, but it is no longer what the
/// app shows by default — an install with no sources now gets the real empty
/// state, exactly as the Android app does.
@MainActor
@Observable
final class LibraryStore {

    private(set) var sources: [SourceSummary] = []
    private(set) var isLoading = false

    /// Cache per content kind so a rail redraw does not re-hit SQLite.
    /// Invalidated wholesale after a sync, which is the only thing that
    /// changes the catalogue.
    private(set) var live: [YancoItem] = []
    private(set) var movies: [YancoItem] = []
    private(set) var series: [YancoItem] = []

    private(set) var syncingSourceID: String?
    private(set) var syncPhase: SyncPhase?
    private(set) var syncCurrent = 0
    private(set) var syncTotal = 0
    private(set) var lastError: String?

    private var syncHandle: SyncHandle?

    /// True when the user has no sources at all — the trigger for the
    /// first-run empty state rather than an empty rail.
    var isEmpty: Bool { sources.isEmpty }

    /// Sample fixtures are opt-in now. Kept for design captures.
    static var usesSampleData: Bool {
        ProcessInfo.processInfo.environment["YANCO_SAMPLE"] == "1"
    }

    // MARK: - Loading

    func refresh() async {
        if Self.usesSampleData {
            live = SampleContent.channels
            movies = SampleContent.movies
            series = SampleContent.series
            sources = [
                SourceSummary(
                    id: "sample", name: "Sample Library", typeLabel: "Demo",
                    url: nil, channelCount: live.count + movies.count + series.count,
                    lastSynced: .now, lastSyncError: nil, isActive: true
                )
            ]
            return
        }

        isLoading = true
        let snapshot = await SharedServices.shared.run { services -> LibrarySnapshot in
            LibrarySnapshot(
                sources: services.sources.getAll().map(SourceSummary.init(kotlin:)),
                live: services.content.page(
                    type: .live, group: nil, offset: 0, limit: 300, sourceId: nil
                ).map(YancoItem.init(kotlin:)),
                movies: services.content.page(
                    type: .movie, group: nil, offset: 0, limit: 300, sourceId: nil
                ).map(YancoItem.init(kotlin:)),
                series: services.content.page(
                    type: .series, group: nil, offset: 0, limit: 300, sourceId: nil
                ).map(YancoItem.init(kotlin:))
            )
        }
        sources = snapshot.sources
        live = snapshot.live
        movies = snapshot.movies
        series = snapshot.series
        isLoading = false

        #if DEBUG
        // Lets a debug launch add and sync a source without driving the UI,
        // so the whole pipeline (fetch → shared parser → SQLDelight → rails)
        // can be exercised headlessly:
        //   SIMCTL_CHILD_YANCO_DEBUG_M3U=https://… xcrun simctl launch …
        if sources.isEmpty,
           let debugURL = ProcessInfo.processInfo.environment["YANCO_DEBUG_M3U"] {
            await addSource(
                name: "Debug playlist", type: .m3u, url: debugURL,
                username: nil, password: nil
            )
        }
        #endif
    }

    func items(for kind: ContentKind) -> [YancoItem] {
        switch kind {
        case .live: return live
        case .movie: return movies
        case .series: return series
        }
    }

    func groups(for kind: ContentKind) -> [String] {
        var seen = Set<String>()
        return items(for: kind).compactMap { seen.insert($0.group).inserted ? $0.group : nil }
    }

    // MARK: - Mutating

    /// Adds a source and immediately syncs it — a source with no catalogue
    /// is not a useful state to leave the user in.
    func addSource(
        name: String,
        type: SourceKind,
        url: String,
        username: String?,
        password: String?
    ) async {
        lastError = nil
        let created = await SharedServices.shared.run { services -> String? in
            let input = AddSourceInput(
                name: name,
                type: type.kotlin,
                url: url,
                filePath: nil,
                username: username,
                password: password,
                macAddress: nil,
                epgUrl: nil,
                userAgent: nil,
                referer: nil
            )
            // addSource validates and can throw on malformed input; the
            // message is the user's only clue about what was wrong.
            return services.sources.addSource(input: input).id
        }
        await refresh()
        if let created { await sync(sourceID: created) }
    }

    func removeSource(_ id: String) async {
        await SharedServices.shared.run { services in
            services.sources.removeSource(id: id)
        }
        await refresh()
    }

    // MARK: - Sync

    func sync(sourceID: String) async {
        guard syncingSourceID == nil else { return }
        syncingSourceID = sourceID
        syncPhase = .fetching
        syncCurrent = 0
        syncTotal = 0
        lastError = nil

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            syncHandle = SharedServices.shared.startSync(
                sourceID: sourceID,
                onProgress: { [weak self] phase, current, total in
                    self?.syncPhase = phase
                    self?.syncCurrent = current
                    self?.syncTotal = total
                },
                onComplete: { [weak self] failure in
                    self?.lastError = failure
                    continuation.resume()
                }
            )
        }

        syncingSourceID = nil
        syncHandle = nil
        syncPhase = nil
        await refresh()
    }

    func cancelSync() {
        syncHandle?.cancel()
        syncHandle = nil
        syncingSourceID = nil
        syncPhase = nil
    }

    // MARK: - Resume points

    /// Where playback should start, in seconds. `positionFor` returns nil
    /// past 95%, so a finished title restarts rather than seeking to the
    /// credits — that rule lives in the shared repository and is not
    /// re-derived here.
    func resumePosition(for contentID: String) async -> Double? {
        await SharedServices.shared.run { services in
            services.history.positionFor(contentId: contentID)?.doubleValue
        }
    }

    /// Persists a resume point. Live channels are skipped — the shared
    /// repository treats them as a no-op anyway, and writing a position for
    /// a stream with no meaningful offset would pollute Continue Watching.
    func savePosition(for item: YancoItem, seconds: Double, duration: Double?) {
        guard item.kind != .live, seconds > 0 else { return }
        SharedServices.shared.perform { services in
            services.history.upsert(
                contentId: item.id,
                episodeId: nil,
                positionSeconds: Int64(seconds),
                durationSeconds: duration.map { KotlinLong(value: Int64($0)) }
            )
        }
    }

    /// Human-readable one-liner for the sync banner.
    var syncStatusText: String? {
        guard let syncPhase else { return nil }
        if syncTotal > 0 {
            return "\(syncPhase.rawValue) \(syncCurrent.formatted()) / \(syncTotal.formatted())"
        }
        return syncPhase.rawValue + "…"
    }
}

/// One trip across the queue boundary instead of four.
private struct LibrarySnapshot: Sendable {
    let sources: [SourceSummary]
    let live: [YancoItem]
    let movies: [YancoItem]
    let series: [YancoItem]
}

/// The source kinds the iOS add-source form offers. `M3U_FILE` and
/// `STALKER` exist in the shared enum but need a document picker and a MAC
/// address field respectively — both later milestones.
enum SourceKind: String, CaseIterable, Identifiable, Sendable {
    case m3u = "M3U playlist"
    case xtream = "Xtream Codes"

    var id: String { rawValue }

    var kotlin: SourceType {
        switch self {
        case .m3u: return .m3uUrl
        case .xtream: return .xtream
        }
    }

    var urlPlaceholder: String {
        switch self {
        case .m3u: return "https://provider.tv/get.php?…"
        case .xtream: return "http://provider.tv:8080"
        }
    }

    var needsCredentials: Bool { self == .xtream }
}

// MARK: - Kotlin → Swift mapping

extension SourceSummary {
    init(kotlin source: Source) {
        self.init(
            id: source.id,
            name: source.name,
            typeLabel: {
                switch source.type {
                case .m3uUrl: return "M3U"
                case .m3uFile: return "M3U file"
                case .xtream: return "Xtream"
                default: return "Stalker"
                }
            }(),
            url: source.url,
            channelCount: Int(source.channelCount),
            lastSynced: source.lastSynced.map { Date(timeIntervalSince1970: $0.doubleValue / 1000) },
            lastSyncError: source.lastSyncError,
            isActive: source.isActive
        )
    }
}

extension YancoItem {
    /// Maps a shared `ContentItem` onto the UI model the shell already
    /// renders, so no view changes when real data replaces the fixture.
    ///
    /// `displayTitle` is used rather than `title`: it resolves the user's
    /// override, then the cleaned title, then the raw M3U text — the same
    /// fallback the Android UI reads through.
    init(kotlin item: ContentItem) {
        self.init(
            id: item.id,
            title: item.displayTitle,
            kind: ContentKind(item.type),
            group: item.groupName ?? "Uncategorised",
            backdropSeed: item.id,
            posterSeed: item.id,
            plot: "",
            year: nil,
            rating: nil,
            quality: nil,
            channelNumber: nil,
            nowTitle: nil,
            nowProgress: nil,
            nextTitle: nil,
            resume: nil,
            seasonSummary: nil,
            artworkURL: item.displayLogoUrl.flatMap(URL.init(string:)),
            streamURL: item.streamUrl
        )
    }
}

extension ContentKind {
    init(_ type: ContentType) {
        switch type {
        case .live: self = .live
        case .movie: self = .movie
        default: self = .series
        }
    }

    var kotlin: ContentType {
        switch self {
        case .live: return .live
        case .movie: return .movie
        case .series: return .series
        }
    }
}
