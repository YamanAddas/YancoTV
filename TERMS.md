# YancoTV — Terms of Use

**This file is not the terms.** It is a pointer, so that nobody edits a copy
that nothing reads.

## Where the live terms are

| Copy | Location |
|---|---|
| Source of truth (all four languages) | `assets/legal-strings.js` in the [yancotv-releases](https://github.com/YamanAddas/yancotv-releases) repo, keys `tm.*` |
| Published | <https://yamanaddas.github.io/yancotv-releases/terms.html> |
| Bundled in the app | `packages/android/app/src/main/res/raw{,-ar,-es,-fr}/terms_of_use.txt` — Settings → About → Terms of service |

The bundled copies are **generated**, not written:

```bash
node scripts/gen-legal-raw.mjs
```

Change the wording on the site, re-run it, commit the result. The published
page and the text inside the app are the same document by construction.

## What happened to the old draft

Until 2026-08-24 this file held a 171-line draft dated 2026-04-29. Like
[PRIVACY.md](PRIVACY.md) it had drifted from what actually ships, and it still
told you to point a `TERMS_OF_SERVICE_URL` constant at a hosted copy — that
constant is gone (MB-372); the documents open in an in-app viewer instead,
because Fire TV has no browser to hand them to.

`git show d75047aa:TERMS.md` is the last version of it.

## The part of that draft still worth acting on

The live terms cover what the app is, whose responsibility the content is,
recording, no warranty, where builds come from, and open-source components.
They deliberately do **not** include:

- A governing-law / dispute-venue clause. The old draft selected Kentucky law
  and Kentucky-USA courts with a small-claims-court carve-out. Worth adding
  back if the app is ever distributed beyond a friend group.
- An arbitration or class-action-waiver clause. There has never been one.

Get a lawyer in your jurisdiction to read the live text before any public
store submission. Nothing here is legal advice.
