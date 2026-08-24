# Store submission — Amazon Appstore (Fire TV) and Google Play

Everything needed to put YancoTV on a store, and the reasons behind the
choices. Written 2026-08-24 against 1.6.7 (versionCode 31).

The sideload channel (GitHub Releases + in-app updater) is unaffected by any
of this and keeps working exactly as it does today.

## Which build to upload

**Always the `storeRelease` build type — never the `release` one.**

```bash
cd packages/android
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleStoreRelease   # APK -> Amazon
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:bundleStoreRelease     # AAB -> Google Play
```

| | `release` (sideload) | `storeRelease` (Play / Amazon) |
|---|---|---|
| In-app updater | On | **Off** (`UPDATE_ENDPOINT` forced empty) |
| `REQUEST_INSTALL_PACKAGES` | Declared | **Stripped from the manifest** |
| About → Updates copy | "Check now" button | "Updates arrive through the app store…" |
| Signing key | release.keystore | Same key |
| applicationId | `com.yancotv.android` | Same |

Why the updater has to go: Google Play's *Device and Network Abuse* policy and
Amazon's equivalent both forbid an app that downloads and installs executable
code. Our updater does exactly that. Shipping it is a likely rejection, and
declaring a permission the build cannot use is itself a review flag. Stores
deliver updates themselves, so nothing is lost.

Outputs:
- APK — `app/build/outputs/apk/storeRelease/app-storeRelease.apk`
- AAB — `app/build/outputs/bundle/storeRelease/app-storeRelease.aab`

## Amazon Appstore (Fire TV) — free

No registration fee. Note Amazon **discontinued its Appstore for regular
Android phones in August 2025**; it continues for Fire TV and Fire tablets,
which is exactly our primary target.

1. developer.amazon.com → sign in → **Apps & Services → Add New App → Android**.
2. Upload `app-storeRelease.apk`.
3. Fill the listing (copy below), upload assets, set device support to the
   Fire TV family, complete the content-rating questionnaire.
4. Submit. Review is typically a few days.

### Device support

Min SDK 24 covers every current Fire TV. The oldest test device we own,
Fire TV Stick AFTDCT31, is API 28. Leave phones/tablets unchecked unless you
want Fire tablets too — the app is landscape-only by design, which Amazon's
tablet reviewers may question.

## Google Play — $25 once

One-time registration fee (not annual), plus identity verification.

**If you register a personal (individual) account**, Google requires a closed
test with **at least 12 testers for 14 continuous days** before you may apply
for production. Internal testing (up to 100 testers, invite by email) is
available immediately — that is the fast path for friends and family, and it
already removes the "might not be safe" sideload warning for them.

Play needs, beyond the listing text:
- **Data safety form** — declare Sentry crash reporting (crash logs +
  diagnostics, not linked to identity, not shared for ads).
- **Privacy policy URL** — https://yamanaddas.github.io/yancotv-releases/privacy.html
- Target API 35 ✔ (current requirement satisfied).

## Listing copy (draft — edit freely)

**Title:** YancoTV

**Short description (Amazon ≤ 80 / Play ≤ 80):**
Your IPTV playlist, beautifully organised — live TV, movies and series.

**Long description:**

```
YancoTV is a premium player for your own IPTV subscription. Add your provider
once and YancoTV turns the raw playlist into a proper TV experience: a browsable
catalogue with artwork, a full programme guide, and playback tuned for the
living room.

BRING YOUR OWN PROVIDER
YancoTV does not include, sell, or provide any channels or content. You supply
your own playlist or account from a provider you already pay for. Supported:
M3U/M3U8 links and files, Xtream Codes, and Stalker/MAG portals.

FEATURES
- Live TV, movies and series, sorted automatically with posters and details
- Full EPG programme guide with reminders and catch-up where your provider
  offers it
- Recording, including scheduled recordings from the guide
- Continue watching, favourites, and resume across your library
- Parental controls with a PIN, and an adult-content filter
- Subtitles, multiple audio tracks, and per-source streaming options
- Built for the remote: designed for Android TV and Fire TV first
- Available in English, Arabic, Spanish and French, with full right-to-left
  support
- Your provider credentials are encrypted on device and never leave it
```

**Category:** Entertainment
**Keywords:** iptv, m3u, xtream, playlist player, epg, live tv, media player

### Review notes (important — do not skip)

Reviewers cannot test YancoTV without a playlist, and a reviewer who cannot
open the app usually rejects it. Give them one in the testing-instructions
field:

```
YancoTV requires the user's own IPTV playlist; the app ships with no content.
To test, open Settings > Sources > Add Source, choose "M3U URL", and paste this
free, publicly available playlist of over-the-air news channels:

https://iptv-org.github.io/iptv/categories/news.m3u

Leave the name as anything you like and press Save. The catalogue populates in
under a minute, after which Live TV, the guide and playback can all be
exercised. No account or credentials are needed.
```

That URL is the same public iptv-org list our debug builds seed, is legal
free-to-air content, and needs no credentials.

## Assets you still need to produce

These need a device with a real catalogue, so they cannot be generated from a
clean test install:

- **Screenshots, 1920×1080, 3–10** of Fire TV in use: Home, Live TV browse,
  the guide, a movie detail page, the player with the info bar.
- **App icon 512×512 PNG** — from `ic_launcher` / `ic_logo_mark`.
- **Fire TV banner 1920×720** — we ship `tv_banner`; check whether the existing
  asset is that size or needs a scaled export.
- Play additionally wants a **feature graphic, 1024×500**.

Take the screenshots on a device with your own source loaded, since the empty
states are not a good advertisement. Avoid capturing provider names, channel
lists or anything that identifies your subscription — both stores dislike
listings that appear to advertise access to paid channels, and that is the
single most common reason IPTV apps get pulled.

## The one policy risk worth understanding

Both stores remove apps that bundle channel lists or otherwise facilitate
piracy. YancoTV is defensible because it ships **no content**: the sample
source is wrapped in `BuildConfig.DEBUG` and is absent from every release
build. Keep it that way — do not add bundled playlists, provider branding, or
screenshots that showcase premium channel names, and the "neutral player"
position holds. Several comparable players are listed on both stores on
exactly this basis.
