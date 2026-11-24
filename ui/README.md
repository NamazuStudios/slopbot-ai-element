# Dashboard UI Plugin — SlopBot AI (superuser)

Follows the NamazuStudios/element-example `ui/` conventions: a Vite/TS module
that builds an IIFE React bundle into `../element/src/main/ui/superuser/`
(`plugin.json` + `plugin.bundle.js`), which the Maven build packages into the
`.elm` artifact. **The built bundle is committed** — that is this repo's
convention; `node_modules/` is not.

## Build

```bash
npm install
npm run build          # writes element/src/main/ui/superuser/plugin.bundle.js
npm run dev:superuser  # standalone dev server; proxies /api, /app, /slop
                       # to the instance at ELEMENTS_URL (default :8080)
```

## Plugin surface

Route `slopbot-ai-assets` (sidebar: "SlopBot AI Assets"), superuser segment:

- the asset table — name, kind, size, tags, referenced-by, revision, state;
- the upload dialog — extension allow-list (.mp3/.ogg/.wav/.mp4/.webm), the
  8 MiB cap, bare-name normalization, all mirrored client-side for immediate
  feedback (the server re-validates and returns the real error otherwise);
- retire / unretire, the replace flow (a new LargeObject revision; existing
  slops stay pinned to the old), guarded delete (referenced assets refuse);
- the slop reassign form (owner / creator — the content-attribution repair).

All calls go to the element's own REST root — the absolute `/slop/api/...`
(the element's `rs.root` attribute) — with the session token from
`window.__elementsApiClient.getSessionToken()` as the `Elements-SessionSecret`
header, per element-example's authenticated-plugin pattern.
