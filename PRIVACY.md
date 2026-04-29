# YancoTV Privacy Policy

> **Draft, last updated 2026-04-29.** This is the policy that ships
> with the v1 build for friend-group testing — built around standard
> indie-app practice + the rights-disclosure clauses GDPR / CCPA /
> KCDPA expect. Before any public store submission you should (a)
> host this file at a stable HTTPS URL, (b) update the
> `PRIVACY_POLICY_URL` constant in
> `packages/android/app/src/main/java/com/yancotv/android/ui/settings/SettingsAboutTab.kt`
> to point at it, and (c) have a Kentucky-licensed attorney review
> the document — this draft is a reasonable starting template, not
> legal advice.

---

## What YancoTV is

YancoTV is an IPTV client app for Android TV, Fire TV, and Android
phones. It plays back live TV channels, movies, and series from
playlists (M3U / Xtream) that **you bring to it**. YancoTV doesn't
host, stream, or distribute any content of its own.

## What data the app stores on your device

Everything below stays on your device by default — none of it is
transmitted anywhere except where this policy explicitly says
otherwise.

- **Playlist sources** you've added (M3U URL, Xtream credentials,
  Stalker portal MAC). Provider URLs and credentials are stored in
  Android Keystore — encrypted at rest by the OS.
- **Channel + programme metadata** parsed from your playlists and
  EPG sources (titles, descriptions, schedules, logos).
- **Watch history**, favourites, custom logos, hidden channels, lock
  PINs, recording schedules, recordings, app preferences.
- **Crash diagnostic state** (a small log file on disk under the
  app's private directory, written when the app crashes — see the
  next section).

This data is wiped when you uninstall the app.

## What data leaves your device

### Playlist + provider traffic
When you play a channel, YancoTV connects directly to the IPTV
provider URL you supplied. This is identical to opening the URL in
any IPTV player. We don't proxy, mirror, or copy this traffic.

### Catch-up / EPG
When you tap catch-up or refresh the guide, YancoTV fetches the
relevant URLs from the provider you configured. Same posture as
above — direct provider connection.

### Auto-update check
Once a day (and on user demand via Settings → About → Check now), the
app fetches a small JSON file from the URL configured in
`update.endpoint` to see if a newer version is available. The fetch
is anonymous; no device identifier or user data is sent.

### Crash + error reports (Sentry)

When something crashes or hits an uncaught error, YancoTV sends a
minimal diagnostic packet to **Sentry**, our error-tracking
provider:

- The stack trace (line numbers + Kotlin/Java function names)
- App version + build number
- Device model + manufacturer
- OS version (Android API level)
- Sentry-managed event ID + timestamp

We **don't** send:

- Your IP address (Sentry's default IP capture is disabled)
- Personally identifying information (names, emails, accounts)
- Provider credentials or playlist URLs
- The content of any channel, programme, or recording
- The contents of your watch history, favourites, search queries

You can turn this off entirely:

> **Settings → About → Privacy → Send crash reports** (toggle off)

When the toggle is off, no crash, breadcrumb, or error event leaves
your device. Existing reports already on Sentry's servers are not
recalled by the toggle — Sentry's documented data-retention is the
authority on that.

Sentry's own privacy policy: <https://sentry.io/privacy/>

### What we don't do

- No analytics SDKs (no Google Analytics, Firebase Analytics,
  Mixpanel, etc.)
- No advertising SDKs
- No first-party telemetry beyond crash reports
- No social-network integration
- No backend account system; you don't sign in to YancoTV

## Children

YancoTV isn't directed at children under 13. The app surfaces
playback of whatever IPTV content you bring to it — content
appropriateness is your responsibility (the parental-lock feature
exists to help, but it isn't a substitute for adult supervision).

## Your rights

- **Disable crash reports** at any time via the toggle above.
- **Delete all app data** by uninstalling YancoTV. There's no cloud
  backup — uninstalling wipes everything.
- **Export / import your sources + favourites + history** via
  Settings → Backup before uninstall, if you want to migrate.
- **Right to access / deletion under GDPR / CCPA / KCDPA**: because
  YancoTV doesn't operate a backend account, the only YancoTV-side
  data we could be holding for you is in Sentry. Email the address
  below if you want any Sentry-side data associated with your
  install reviewed or deleted; include the device model + approximate
  first install date so we can scope the search.

  Kentucky residents: the **Kentucky Consumer Data Protection Act
  (KCDPA)** took effect 1 January 2026. It grants you rights to
  access, correct, delete, and port your personal data, and to
  opt out of targeted advertising / sale of personal data / profiling.
  YancoTV doesn't engage in targeted advertising, doesn't sell
  personal data, and doesn't profile users — so several of those
  rights have nothing to act on. The access / correct / delete
  rights are honoured via the email contact below.

## Third parties this policy covers

- **Sentry** (<https://sentry.io>) — crash + error reporting, opt-out
  available as described above.

This policy does NOT cover the IPTV providers you bring to YancoTV.
Those are separate services with their own privacy practices —
review whatever you've signed up with.

## Changes to this policy

We'll update this document as the app evolves. The "Last updated"
date at the top reflects the most recent change. Material changes
(adding a new third-party SDK, expanding what crash reports include)
will be flagged in the in-app Updates banner when shipped.

## Contact

Questions or requests: **contact@yancoverse.com**
