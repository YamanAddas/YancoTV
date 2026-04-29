# YancoTV Terms of Service

> **Draft, last updated 2026-04-29.** Built around standard indie-app
> practice for a Kentucky-based US developer: governing-law clause
> selects Kentucky law + Kentucky-USA courts as the dispute venue,
> with a small-claims-court carve-out so users keep their normal
> consumer remedy. Before any public store submission you should
> (a) host this file at a stable HTTPS URL, (b) point
> `TERMS_OF_SERVICE_URL` in
> `packages/android/app/src/main/java/com/yancotv/android/ui/settings/SettingsAboutTab.kt`
> at it, and (c) have a Kentucky-licensed attorney review — this
> draft is a reasonable template, not legal advice.

---

## What you're agreeing to

By installing and using YancoTV, you accept these terms. If you don't
accept them, uninstall the app — you can also opt out of crash
reports first (Settings → About → Send crash reports) if you want
that data wiped from leaving your device retroactively, though
events already received by Sentry are subject to their retention
policy.

## What YancoTV is

YancoTV is a media-playback client app. It plays back IPTV
playlists you supply (M3U, Xtream, Stalker portal). It is **not** a
streaming service. We do not host, store, distribute, encode,
re-broadcast, or otherwise traffic in any audio-visual content. The
app simply opens URLs you provide and renders the bytes that come
back.

## What you're responsible for

### Content you bring

You are solely responsible for:

- The legality of any IPTV service, playlist, or stream URL you
  configure in YancoTV.
- Ensuring you have the right to view + record any content you
  access via those services.
- Compliance with copyright, broadcast, and content-distribution
  laws in your jurisdiction.

YancoTV doesn't validate that you have permission to access or
record any particular stream. It's a tool; what you do with it is on
you.

### Recording

YancoTV's recording feature captures bytes from streams to local
storage. Whether that recording is legal depends on:

- The content's copyright status and licence terms
- The broadcaster / provider's terms of service
- Your jurisdiction's law on time-shifting, fair use, private
  copying, and broadcast recording
- Whether the recording is for personal use or further distribution

We surface a one-time disclaimer the first time you initiate a
recording, but that acknowledgement doesn't relieve you of
responsibility for understanding + complying with applicable law.

### Provider credentials + accounts

If you supply a paid IPTV subscription's credentials to YancoTV,
your relationship with that provider is governed by the provider's
own terms — not these. YancoTV stores those credentials encrypted
on your device (Android Keystore) and only transmits them to the
provider URL you configured.

## What we provide and don't provide

### As-is, no warranty

YancoTV is provided "as is", without warranty of any kind, express
or implied, including but not limited to merchantability, fitness
for a particular purpose, or non-infringement. We don't guarantee:

- Continuous availability
- Compatibility with every device, OS version, or IPTV provider
- That recordings will succeed
- That the app is free of bugs or vulnerabilities
- That auto-update will deliver every release to every install

### Updates

We may release updates that change features, fix bugs, or remove
functionality. The auto-update channel will surface them; you can
opt out of automatic checks via Settings → About. Older versions of
the app may stop working if a backend dependency (e.g. the
update-check endpoint) changes or is retired.

### No support obligation

This is currently a small project built for personal use and a
small testing group. There's no SLA, no guaranteed support window,
no ticketing system. Bug reports + feature requests are welcome via
the contact below but we don't commit to responding within any
particular timeframe.

## Limitation of liability

To the maximum extent permitted by law, the developers of YancoTV
shall not be liable for any direct, indirect, incidental, special,
consequential, or punitive damages — including but not limited to
loss of profits, data, goodwill, or other intangible losses —
resulting from:

- Your use of, or inability to use, the app
- Unauthorized access to or alteration of your transmissions or
  data
- Statements or conduct of any third party (including IPTV
  providers) accessed via the app
- Any other matter relating to the app

The maximum aggregate liability of the developers under any theory
shall not exceed the amount you paid for the app — which, as a free
download, is zero.

## Termination

These terms apply for as long as you use the app. You can terminate
at any time by uninstalling. We can terminate by ceasing to publish
new versions; existing installs continue to work as-is.

## Governing law and dispute resolution

These Terms are governed by and construed in accordance with the
laws of the **Commonwealth of Kentucky, United States of America**,
without regard to its conflict-of-laws principles. The United Nations
Convention on Contracts for the International Sale of Goods does not
apply.

Any dispute, claim, or controversy arising out of or relating to
these Terms, your use of YancoTV, or the relationship between you
and the developer shall be resolved exclusively in the state or
federal courts located in Kentucky, USA, and you and the developer
each consent to the personal jurisdiction of those courts.

Notwithstanding the above, either party may bring an individual
action in **small-claims court** in the consumer's county of
residence for any claim that qualifies for that court's
jurisdiction.

## Severability and entire agreement

If any provision of these Terms is held to be invalid, illegal, or
unenforceable by a court of competent jurisdiction, that provision
shall be limited or eliminated to the minimum extent necessary so
that the remaining provisions stay in full force and effect.

These Terms, together with the [Privacy Policy](PRIVACY.md),
constitute the entire agreement between you and the developer
regarding YancoTV and supersede any prior agreements between you
and us regarding the app. The developer's failure to enforce any
right or provision of these Terms shall not be a waiver of that
right or provision.

## Changes to these terms

We may update these terms as the app evolves. The "Last updated"
date at the top reflects the most recent change. Material changes
will be surfaced in the in-app Updates banner when shipped;
continued use after an update means acceptance of the new terms.

## Contact

Questions or notices: **contact@yancoverse.com**
