import SwiftUI

/// Sources management — the screen that turns an empty install into a
/// library. Reached from Settings, and from the first-run empty state.
struct SourcesScreen: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Bindable var library: LibraryStore

    @State private var showsAdd = false
    /// Removing a source deletes its entire catalogue. Android gates the
    /// same action behind `ConfirmDangerDialog`; one stray tap should not
    /// discard a 10,000-channel sync.
    @State private var pendingRemoval: SourceSummary?

    private var inset: CGFloat { sizeClass == .compact ? Space.xl : Space.section }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.xl) {
                header

                if let status = library.syncStatusText {
                    syncBanner(status)
                }

                if let error = library.lastError, !error.isEmpty {
                    errorBanner(error)
                }

                if library.sources.isEmpty {
                    SectionPlaceholder(
                        overline: "NO SOURCES",
                        title: "Add your first source",
                        message: "Point YancoTV at an M3U playlist or Xtream Codes account and it will pull in your channels, movies and series."
                    )
                } else {
                    ForEach(library.sources) { source in
                        sourceCard(source)
                    }
                    .padding(.horizontal, inset)
                }
            }
            .padding(.vertical, Space.xl)
        }
        .sheet(isPresented: $showsAdd) {
            AddSourceSheet(library: library)
        }
        .confirmationDialog(
            "Remove \(pendingRemoval?.name ?? "")?",
            isPresented: .init(
                get: { pendingRemoval != nil },
                set: { if !$0 { pendingRemoval = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Remove source", role: .destructive) {
                if let id = pendingRemoval?.id {
                    Task { await library.removeSource(id) }
                }
                pendingRemoval = nil
            }
            Button("Cancel", role: .cancel) { pendingRemoval = nil }
        } message: {
            Text("Its channels, movies and series are removed from your library. The provider account itself is untouched.")
        }
    }

    private var header: some View {
        HStack(alignment: .bottom) {
            RailHeader(
                eyebrow: "LIBRARY",
                title: "Sources",
                caption: library.sources.isEmpty ? "None yet" : "\(library.sources.count) configured"
            )
            Spacer()
            HexCta(title: "Add", symbol: "plus", primary: true) { showsAdd = true }
        }
        .padding(.horizontal, inset)
    }

    private func sourceCard(_ source: SourceSummary) -> some View {
        HexSurface(shape: YancoShapes.cutCornerCardSmall, lit: false, bevelInset: 3) {
            VStack(alignment: .leading, spacing: Space.md) {
                HStack(alignment: .top, spacing: Space.md) {
                    VStack(alignment: .leading, spacing: Space.xxs) {
                        Text(source.name)
                            .yancoType(YancoType.titleM)
                            .foregroundStyle(palette.TextPrimary)
                            .lineLimit(1)
                        Text(source.subtitle)
                            .yancoType(YancoType.caption)
                            .foregroundStyle(
                                source.lastSyncError == nil ? palette.TextMuted : palette.Error
                            )
                            .lineLimit(2)
                        if let url = source.url {
                            // The shared `redactCredentials` already strips
                            // secrets from anything the repository surfaces;
                            // this is only ever a display string.
                            Text(url)
                                .yancoType(YancoType.caption)
                                .foregroundStyle(palette.TextFaint)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                    }
                    Spacer(minLength: 0)
                    Text(source.typeLabel.uppercased())
                        .yancoType(YancoType.overline)
                        .foregroundStyle(palette.Accent)
                }

                HStack(spacing: Space.md) {
                    HexCta(
                        title: library.syncingSourceID == source.id ? "Syncing…" : "Sync",
                        symbol: "arrow.triangle.2.circlepath"
                    ) {
                        Task { await library.sync(sourceID: source.id) }
                    }
                    HexCta(title: "Remove", symbol: "trash") {
                        pendingRemoval = source
                    }
                }
                .fixedSize()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Space.lg)
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private func syncBanner(_ status: String) -> some View {
        HStack(spacing: Space.md) {
            ProgressView().tint(palette.Accent)
            Text(status)
                .yancoType(YancoType.label)
                .foregroundStyle(palette.TextPrimary)
            Spacer()
            Button("Cancel") { library.cancelSync() }
                .yancoType(YancoType.label)
                .foregroundStyle(palette.TextMuted)
        }
        .padding(Space.lg)
        .background(palette.BackgroundRaised, in: RoundedRectangle(cornerRadius: Radius.card))
        .overlay {
            RoundedRectangle(cornerRadius: Radius.card)
                .stroke(palette.Accent.opacity(0.4), lineWidth: 1)
        }
        .padding(.horizontal, inset)
    }

    private func errorBanner(_ error: String) -> some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text("SYNC FAILED")
                .yancoType(YancoType.overline)
                .foregroundStyle(palette.Error)
            Text(error)
                .yancoType(YancoType.body)
                .foregroundStyle(palette.TextSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Space.lg)
        .background(palette.BackgroundRaised, in: RoundedRectangle(cornerRadius: Radius.card))
        .overlay {
            RoundedRectangle(cornerRadius: Radius.card)
                .stroke(palette.Error.opacity(0.4), lineWidth: 1)
        }
        .padding(.horizontal, inset)
    }
}

/// Add-source form. M3U URL and Xtream Codes only for now — an M3U file
/// needs a document picker and Stalker needs a MAC field, both later.
struct AddSourceSheet: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @Bindable var library: LibraryStore

    @State private var kind: SourceKind = .m3u
    @State private var name = ""
    @State private var url = ""
    @State private var username = ""
    @State private var password = ""
    @State private var submitting = false

    private var canSubmit: Bool {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        guard !url.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        if kind.needsCredentials {
            // Trimmed, because Kotlin validates with `isNullOrBlank()`: a
            // single space passed this guard and then failed `require(...)`
            // on the other side of a bridge that cannot raise.
            return !username.trimmingCharacters(in: .whitespaces).isEmpty
                && !password.trimmingCharacters(in: .whitespaces).isEmpty
        }
        return true
    }

    var body: some View {
        ZStack {
            palette.BackgroundDeep.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: Space.xl) {
                    Text("ADD SOURCE")
                        .yancoType(YancoType.overline)
                        .foregroundStyle(palette.Accent)

                    Picker("Type", selection: $kind) {
                        ForEach(SourceKind.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)

                    field("Name", text: $name, placeholder: "My provider")
                    field(
                        kind == .xtream ? "Server URL" : "Playlist URL",
                        text: $url,
                        placeholder: kind.urlPlaceholder,
                        keyboard: .URL
                    )

                    if kind.needsCredentials {
                        field("Username", text: $username, placeholder: "username")
                        secureField("Password", text: $password)
                        Text("Credentials are encrypted with a key held in the iOS Keychain and never stored in plain text.")
                            .yancoType(YancoType.caption)
                            .foregroundStyle(palette.TextMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    HStack(spacing: Space.md) {
                        HexCta(title: submitting ? "Adding…" : "Add & sync", symbol: "plus", primary: true) {
                            submit()
                        }
                        HexCta(title: "Cancel", symbol: "xmark") { dismiss() }
                    }
                    .fixedSize()
                    .opacity(canSubmit && !submitting ? 1 : 0.5)
                    .disabled(!canSubmit || submitting)
                }
                .padding(Space.xl)
            }
        }
        .preferredColorScheme(.dark)
    }

    private func submit() {
        submitting = true
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let trimmedURL = url.trimmingCharacters(in: .whitespaces)
        Task {
            await library.addSource(
                name: trimmedName,
                type: kind,
                url: trimmedURL,
                username: kind.needsCredentials
                    ? username.trimmingCharacters(in: .whitespaces) : nil,
                password: kind.needsCredentials
                    ? password.trimmingCharacters(in: .whitespaces) : nil
            )
            submitting = false
            // Stay open when it failed, so the message is visible next to
            // the field that caused it.
            if library.lastError == nil { dismiss() }
        }
    }

    private func field(
        _ label: String,
        text: Binding<String>,
        placeholder: String,
        keyboard: UIKeyboardType = .default
    ) -> some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text(label.uppercased())
                .yancoType(YancoType.overline)
                .foregroundStyle(palette.TextMuted)
            TextField(
                "",
                text: text,
                prompt: Text(placeholder).foregroundStyle(palette.TextFaint)
            )
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(keyboard)
            .foregroundStyle(palette.TextPrimary)
            .padding(.horizontal, Space.lg)
            .frame(height: 44)
            .background(palette.BackgroundRaised, in: RoundedRectangle(cornerRadius: Radius.control))
            .overlay {
                RoundedRectangle(cornerRadius: Radius.control)
                    .stroke(palette.PanelBorder, lineWidth: 1)
            }
        }
    }

    private func secureField(_ label: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text(label.uppercased())
                .yancoType(YancoType.overline)
                .foregroundStyle(palette.TextMuted)
            SecureField("", text: text, prompt: Text("••••••••").foregroundStyle(palette.TextFaint))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .foregroundStyle(palette.TextPrimary)
                .padding(.horizontal, Space.lg)
                .frame(height: 44)
                .background(palette.BackgroundRaised, in: RoundedRectangle(cornerRadius: Radius.control))
                .overlay {
                    RoundedRectangle(cornerRadius: Radius.control)
                        .stroke(palette.PanelBorder, lineWidth: 1)
                }
        }
    }
}
