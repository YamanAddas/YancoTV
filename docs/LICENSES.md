# Third-party licenses

Obligations YancoTV carries from the code it ships. This file is the
repo-side record; the **user-facing** notice is what actually discharges
the duty, and that lives in the app (Settings → About → Open source
licenses on iOS).

---

## VLCKit / libVLC — LGPL-2.1-or-later

**Where:** `packages/ios/` (MK.iOS.3b), via the SPM wrapper
[`tylerjonesio/vlckit-spm`](https://github.com/tylerjonesio/vlckit-spm),
which redistributes VideoLAN's official binary XCFramework.

**Why it is here.** AVFoundation demuxes HLS and progressive MP4 and
nothing else that matters to IPTV — in particular it does not open raw
MPEG-TS, which remains a large share of live provider streams, nor MKV,
AVI, or `rtsp://`. Android has no equivalent gap because ExoPlayer
handles those natively. Every serious iOS IPTV player bundles a software
decoder for exactly this reason; libVLC is the mature, maintained one.

This is the "strong justification" that AGENTS.md's no-third-party-Swift-
dependencies rule asks for, and it was named as the intended fallback in
`PRODUCTION_PLAN_NATIVE.md` before any of it was written.

### Why LGPL is safe for a closed-source App Store app

libVLC was **GPL-2.0-or-later** until 2011, and that is precisely why VLC
was pulled from the App Store that year — GPL's terms conflict with
Apple's distribution conditions. VideoLAN then relicensed the engine to
**LGPL-2.1-or-later** specifically to resolve it, and VLC returned to the
iOS App Store in July 2013. The path this project takes is the one that
relicensing exists to enable.

LGPL's copyleft reaches **VLCKit itself, not the application that links
it**. YancoTV stays closed-source. What it must do instead:

| Obligation | How YancoTV meets it |
|---|---|
| Link dynamically, so a user could relink a modified VLCKit | VLCKit ships as a binary framework and is linked as one. Do **not** re-target it as a static archive |
| Publish any modifications to VLCKit | None made. If that ever changes, the fork must be published and linked from here |
| Tell users VLCKit is embedded, and what rights they gain | Settings → About → Open source licenses, in-app |
| Provide the library's source | The same screen links to `https://code.videolan.org/videolan/VLCKit` and to the exact wrapper release the build pins |
| Include the licence text | Full LGPL-2.1 text reachable from that screen |

### The trap to avoid

libVLC **can** be built with GPL-licensed modules. If a GPL module is
ever linked in, the whole application falls under GPL and YancoTV would
have to be open-sourced. The rule that prevents it:

> Use VideoLAN's official binary distribution. Never substitute a custom
> libVLC built with GPL options enabled.

Verify the `LICENSE` in the pinned wrapper release whenever the version
is bumped, rather than assuming it carried over.

### Size

The wrapper's `VLCKit-all.xcframework.zip` is **~778 MB** to download —
it carries every Apple platform and architecture. That is a build-time
and CI cost, not the shipped size: only the iOS slice is linked, and App
Store thinning strips it further per device. Measure the `.ipa` delta
before treating any figure as final, and consider an iOS-only framework
if the download cost becomes a problem for CI.

**Not legal advice.** This records the reasoning behind a decision. For
anything commercial, have a lawyer read the licence rather than this
file.
