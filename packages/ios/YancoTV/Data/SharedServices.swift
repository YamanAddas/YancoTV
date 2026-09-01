import Foundation
import Shared

/// The boundary between Swift concurrency and the shared Kotlin graph.
///
/// Two constraints shape this type:
///
/// 1. **The repositories block.** SQLDelight reads are synchronous; hard
///    rule 3 in `packages/android/CLAUDE.md` says never call one from a
///    Compose lambda, and the same applies to a SwiftUI body. Every call
///    goes through [run] onto a serial background queue.
/// 2. **Kotlin objects are not `Sendable`.** Kotlin/Native exports classes
///    as plain Obj-C classes with no concurrency annotations, so Swift 6
///    refuses to let them cross an isolation boundary. The unchecked
///    conformance below is the honest way to say "I have reasoned about
///    this": the SQLDelight driver is thread-safe, the repositories are
///    stateless wrappers over it, and every access is funnelled through one
///    serial queue anyway.
///
/// The rule that keeps the unchecked conformance sound: **Kotlin objects
/// never escape the queue.** [run] returns `Sendable` Swift values, so the
/// mapping from Kotlin types to Swift models happens *inside* the closure.
final class SharedServices: @unchecked Sendable {

    static let shared = SharedServices()

    private let services: YancoServices
    private let queue = DispatchQueue(label: "com.yancotv.shared", qos: .userInitiated)

    private init() {
        // Kotlin default arguments do not generate Obj-C overloads, so the
        // logger is required at this boundary even though `commonMain`
        // defaults it to NOOP.
        services = YancoServices(
            credentialStore: KeychainCredentialStore(),
            logger: ConsoleLogger()
        )
    }

    /// Runs `body` on the shared queue. The closure receives the Kotlin
    /// graph; whatever it returns must be a Swift value type.
    func run<T: Sendable>(_ body: @escaping @Sendable (YancoServices) -> T) async -> T {
        await withCheckedContinuation { continuation in
            queue.async { [services] in
                continuation.resume(returning: body(services))
            }
        }
    }

    /// Fire-and-forget variant for writes whose result nobody awaits.
    func perform(_ body: @escaping @Sendable (YancoServices) -> Void) {
        queue.async { [services] in body(services) }
    }

    /// Starts a catalogue sync.
    ///
    /// `syncSource` is a Kotlin `Flow`, which has no Obj-C representation —
    /// `YancoServices.startSync` drains it into these callbacks. They arrive
    /// on a background dispatcher, so both hop to the main actor here rather
    /// than making every caller remember to.
    func startSync(
        sourceID: String,
        onProgress: @escaping @MainActor (SyncPhase, Int, Int) -> Void,
        onComplete: @escaping @MainActor (String?) -> Void
    ) -> SyncHandle {
        services.startSync(
            sourceId: sourceID,
            onProgress: { progress in
                // Read the Kotlin object here, on the background thread, and
                // send only plain values across.
                let phase = SyncPhase(progress.phase)
                let current = Int(progress.current)
                let total = Int(progress.total)
                Task { @MainActor in onProgress(phase, current, total) }
            },
            onComplete: { failure in
                Task { @MainActor in onComplete(failure) }
            }
        )
    }
}

/// Swift mirror of `SyncProgress.Phase`, so the Kotlin enum does not have to
/// cross an isolation boundary to reach the UI.
///
/// The Kotlin enum arrives as a *class* (`SyncProgress.Phase`, a
/// `KotlinEnum` subclass), not a Swift enum — its cases are class
/// properties, so this maps by identity rather than by pattern match.
enum SyncPhase: String, Sendable {
    case fetching = "Fetching"
    case parsing = "Parsing"
    case classifying = "Classifying"
    case writing = "Writing"
    case done = "Done"
    case error = "Failed"

    init(_ phase: SyncProgress.Phase) {
        switch phase {
        case SyncProgress.Phase.fetching: self = .fetching
        case SyncProgress.Phase.parsing: self = .parsing
        case SyncProgress.Phase.classifying: self = .classifying
        case SyncProgress.Phase.writing: self = .writing
        case SyncProgress.Phase.done: self = .done
        default: self = .error
        }
    }
}

/// Routes shared-module logging to the Xcode console.
///
/// `SourceRepository` logs a breadcrumb before every step of `addSource`
/// and the sync pipeline precisely so a stall can be located by reading the
/// last line — that is worth having on iOS too, and it is the only way to
/// see inside a sync that fails against a real provider.
final class ConsoleLogger: NSObject, Shared.Logger {
    func info(msg: String) {
        #if DEBUG
        print("[Yanco] \(msg)")
        #endif
    }

    func warn(msg: String) {
        #if DEBUG
        print("[Yanco][warn] \(msg)")
        #endif
    }

    func error(msg: String) {
        print("[Yanco][error] \(msg)")
    }
}
