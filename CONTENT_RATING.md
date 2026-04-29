# YancoTV Content Rating — store-questionnaire answer sheet

> Pre-filled answers for Play Console (IARC), Amazon Appstore, and
> Apple App Store content-rating questionnaires when YancoTV is
> eventually submitted. The premise: YancoTV is a media-playback
> client. It doesn't generate, host, or curate content — it just
> renders whatever IPTV playlist URLs the user supplies. The honest
> rating is "the app itself is benign, but the content played
> through it can be anything the user brings to it" — same posture
> VLC, MX Player, and TiviMate take.

## Pre-flight: what the questionnaire asks

Each store has its own form. The questions overlap heavily — they
cluster around violence, sexual content, drugs, gambling, language,
fear, in-app purchases, location, user-generated-content, account
creation. Below are the answers for YancoTV's actual capabilities.

## Common answers (all stores)

### Violence
- **Cartoon, fantasy, or realistic violence directly produced by the app**: No
- **The app itself depicts violence**: No
- **App can play user-supplied media that may contain violence**: Yes — YancoTV is a media player; the user can configure it to play any IPTV stream they have rights to.

### Sexual content / nudity
- **App contains sexual content or nudity**: No (the app's UI doesn't)
- **App can play user-supplied media that may contain it**: Yes (same reason as violence — depends on the user's playlist)

### Drugs / alcohol / tobacco
- **App depicts or promotes substance use**: No

### Gambling / wagering
- **App contains gambling or simulated gambling mechanics**: No

### Language
- **App contains or facilitates strong language in its UI / chat**: No
- **App can play user-supplied media containing it**: Yes (same media-player caveat)

### Horror / fear
- **App depicts horror content**: No

### User-generated content (UGC)
- This is where store questionnaires get fiddly. **YancoTV does NOT
  host UGC** — there's no community feed, no shared library, no
  upload mechanism. Users only configure private settings (their own
  playlists, their own custom logos, their own recordings).
- If a store specifically asks "can users supply media URLs": **Yes**.
- If a store specifically asks "is content shared between users":
  **No**.

### In-app purchases
- **App offers IAP**: No (as of v1; if a premium tier ships later,
  update this).

### Personal information
- **App collects PII**: No, beyond opt-in crash reports (see
  [PRIVACY.md](PRIVACY.md)).
- **App accesses location**: No.
- **App accesses contacts / camera / microphone**: No.

### Account creation
- **Users create an account in YancoTV**: No (no backend account).
- Users may enter credentials for **third-party** IPTV providers —
  those credentials are stored locally and only transmitted to the
  provider the user chose.

### Network / internet usage
- **Yes**, the app makes HTTPS (and HTTP, since IPTV providers often
  serve plain HTTP — see [AGENTS.md cleartext-traffic note](AGENTS.md))
  requests to user-configured provider hosts and to the Sentry crash-
  reporting endpoint.

### Advertising
- **App displays ads**: No.
- **Third-party ad SDKs**: None.

## Store-specific notes

### Google Play Console
- **Target audience and content** form: pick "All ages" for the app
  itself; mark "User-generated content" → "Users supply content" for
  the playlist input mechanism.
- **App access** → if you support a premium tier later, declare the
  unlock mechanism here.
- **Ads declaration**: "App contains ads" → No.
- **Data safety** form: see [PRIVACY.md](PRIVACY.md). Fill in:
  - Data collected: **Crashes** (optional, opt-out via in-app
    setting).
  - Data shared: **Crashes** (with Sentry as a service provider).
  - All other categories: not collected.
- **Content rating** (IARC): submit answers above. Expected outcome
  is a "user-content media player" classification — likely PEGI 12 /
  Teen / similar, since content played can vary.

### Amazon Appstore
- Has its own simpler rating form. Same answers apply.
- Amazon also asks about "Web access" — answer Yes (the app fetches
  user-supplied URLs).
- Amazon's content guideline §8 (third-party content) explicitly
  permits "user-installable content via URL configuration" — that's
  the bucket YancoTV fits into.

### Apple App Store (iOS — post-MK.iOS)
- Content rating form is similar. Set:
  - Violence / Sexual / Profanity = "Frequent" only if you expect
    users to play 18+ material; otherwise "Mild" or "Infrequent".
- Apple is stricter about "ability to consume content the developer
  doesn't control" — be explicit in the App Review notes that
  YancoTV is an IPTV client (akin to VLC, IPTV Smarters).
- Reviewers often ask for sample provider credentials. Either supply
  a free/test provider or provide a public-domain M3U for review.

## Last-updated

2026-04-29 (drafted alongside `PRIVACY.md` and `TERMS.md`). Update
this file when the answers change — e.g., adding IAP, ads, account
creation.
