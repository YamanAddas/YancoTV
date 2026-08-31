import SwiftUI

/// App entry view. MK.iOS.0's proof-of-bridge screen is retired here —
/// the shared framework's role is now to feed the real shell (MK.iOS.2
/// onward), not to prove it links.
struct ContentView: View {
    var body: some View {
        RootShell()
    }
}

#Preview {
    ContentView()
}
