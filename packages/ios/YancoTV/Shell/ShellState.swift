import SwiftUI

/// Shell-wide navigation and selection state.
///
/// MK.iOS.1 keeps this deliberately thin — section, open detail item,
/// favourites, and the per-section category filter. The Android app holds
/// the equivalent in `HomeScreen`'s composable state; the note in
/// PRODUCTION_PLAN_NATIVE.md about "zero ViewModels in the codebase" is
/// exactly why that state could not cross over, so the iOS side starts
/// with a real holder rather than repeating the mistake. When the shared
/// KMP ViewModels land, this becomes a thin adapter over their StateFlow.
@MainActor
@Observable
final class ShellState {
    var section: AppSection = .home
    var detailItem: YancoItem?
    var favoriteIDs: Set<String> = Set(SampleContent.favorites.map(\.id))
    /// Selected category per browse section. `nil` == the "All" pill.
    var selectedGroup: [String: String] = [:]
    var searchQuery: String = ""

    init() {
        #if DEBUG
        // Lets a debug launch open straight onto a section, so each screen
        // can be captured without driving the UI:
        //   SIMCTL_CHILD_YANCO_START_SECTION=liveTv xcrun simctl launch …
        if let raw = ProcessInfo.processInfo.environment["YANCO_START_SECTION"],
           let parsed = AppSection(rawValue: raw) {
            section = parsed
        }
        #endif
    }

    func isFavorite(_ item: YancoItem) -> Bool { favoriteIDs.contains(item.id) }

    func toggleFavorite(_ item: YancoItem) {
        if favoriteIDs.contains(item.id) {
            favoriteIDs.remove(item.id)
        } else {
            favoriteIDs.insert(item.id)
        }
    }

    func group(for kind: ContentKind) -> String? {
        selectedGroup[String(describing: kind)]
    }

    func setGroup(_ group: String?, for kind: ContentKind) {
        selectedGroup[String(describing: kind)] = group
    }

    /// Items for a browse section, filtered by the active category pill.
    func items(for kind: ContentKind) -> [YancoItem] {
        let all = SampleContent.items(for: kind)
        switch group(for: kind) {
        case .none:
            return all
        case .some(SpecialGroup.favorites):
            return all.filter { favoriteIDs.contains($0.id) }
        case .some(let group):
            return all.filter { $0.group == group }
        }
    }

    var favoriteItems: [YancoItem] {
        (SampleContent.channels + SampleContent.movies + SampleContent.series)
            .filter { favoriteIDs.contains($0.id) }
    }

    var searchResults: [YancoItem] {
        let query = searchQuery.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return [] }
        return (SampleContent.channels + SampleContent.movies + SampleContent.series)
            .filter {
                $0.title.lowercased().contains(query) || $0.group.lowercased().contains(query)
            }
    }
}

/// Pinned pills that aren't provider categories. The Kotlin rail uses the
/// sentinel keys `__fav__` / `__all__` for the same purpose.
enum SpecialGroup {
    static let favorites = "__fav__"
}
