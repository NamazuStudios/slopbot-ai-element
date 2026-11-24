# SlopBot AI Element — Implementation SPEC (first pass)

The design of record is the **AI Slop Bot design notebook** (Namazu Cloud); this file is the distilled, implementation-ready contract for this repository. Reference implementation (behavioral oracle, JS): `NamazuStudios/slopbot-ai` — **reference only, never shipped**. All review decisions below are binding (from the designer, 2026-11-24).

## Architecture in one paragraph

SlopBot AI is the slop engine as a service: any Twitch user is a self-serve tenant (login == their Twitch channel; they see only their own slops). The Elements deployment runs this element (**the ELM**) as the control plane: the data authority (Morphia/MongoDB + GridFS), the MCP tool surface (the ONLY way agents read/write data), the relay (OBS overlay websocket ↔ pod webserver), and the dispatcher (Twitch events → Conductor job → per-stream worker pod). The per-stream pod's main container (the **worker**) runs this same element in worker mode: it monitors the streamer's chat/EventSub, evaluates triggers, and triggers slops directly — persisting everything through the ELM's MCP surface.

## Binding decisions (do not revisit)

- **Kotlin.** The engine is re-implemented in Kotlin in this element. The JS is reference-only. Parity harness: the regression suite in `NamazuStudios/slopbot-ai` (`scripts/regression.js`).
- **All data lives in the database.** One shared `slopbot` DB, tenant-scoped; this element's APIs are the data authority. The pod holds no data.
- **Identity: instance-local OIDC.** Sessions are Elements-issued (Twitch-only login; login == channel). No Namazu Cloud in the trust path.
- **Endpoint rule:** every endpoint lives under the element's REST root with `slop` in the path (e.g. `/slop/api/...`). No system-endpoint collisions.
- **MCP tools execute inside this ELM**; agent pods call them over the network; OIDC enforces.
- **Asset access:** `assets.read` returns the large-object id (fetch via the Large Object API); 8 MiB cap.
- **`slops.update` keeps a running history of 50 modifications.**
- **Assets:** one shared pool (Namazu-provided) for now; schema must not preclude per-tenant assets later. Replace = new revision, slops pinned to old.
- **No mock agent.**
- **Superuser** = an Elements user with SUPERUSER status; impersonation = acting-identity header; audit = append-only, superuser-readable; impersonation is main-dashboard only; admins can reassign slop fields (owner, creator).
- **Remarks (two kinds, both records):** `agentRemarks` (process: narration, CHAT SUMMARY, build notes) and `contentRemarks` (about the contents) — both searchable via the MetadataSpec; a remark may be a separately linked document.

## Repository layout (Maven multi-module, element-example conventions)

```
slopbot-ai-element/
  pom.xml                  # parent: dev.slopbot:element-aggregator
  api/                     # exported interfaces + DTOs (classified jar)
  element/                 # impl: entities, DAO, REST, MCP, engine, guice → builds the .elm
  docs/                    # this spec
```

> **SCOPE CHANGE (designer decision, 2026-11-24 — "thin forwarder"):** the former
> `worker/` module (a Kotlin `WorkerMain` pod runner) is **REMOVED**. The pod's
> main container is a thin Node forwarder implemented in the POD repo
> (NamazuStudios/slopbot-ai-pod); this element repo ships ONLY the ELM server
> side. All trigger evaluation runs ELM-side, via the worker event-ingestion
> surface below. The spec's earlier "in the pod's worker, the live bot" split is
> withdrawn; the engine is one ELM-side engine plus the ingestion surface.

Module conventions (from `NamazuStudios/element-example` — copy its pom shapes):
- `api` — pure interfaces/DTOs; classified jar `dev.slopbot.api`.
- `element` — `@ElementDefinition(recursive = true)` `package-info.java`, `@GuiceElementModule`, element dependencies `dev.getelements.elements.sdk.dao`, `dev.getelements.elements.sdk.service`, `dev.getelements.elements.sdk.mongo`; REST via Jakarta RS registered through an `Application` subclass (`@ElementServiceImplementation` + `@ElementServiceExport`); WebSockets via `@ServerEndpoint`; Morphia entities `@Entity(useDatabase = "slopbot")`; **set the element attribute `dev.getelements.elements.element.rs.root` = `/slop/api`** (via `@ElementDefaultAttribute`).
- `worker` — a main class (`WorkerMain.kt`) that boots the element in worker mode: reads env (`ELEMENTS_*`, `SLOP_STREAMER`, `SLOP_MCP_URL`, `SLOP_OIDC_TOKEN`, Twitch credentials), connects Twitch IRC + EventSub, evaluates triggers, dispatches slop plays, and talks to the ELM over MCP/REST. Runs inside a minimal Elements runtime (mirror `element-example`'s `debug` module pattern — a small runner that loads the built `.elm`).

## Data model (`element/src/main/kotlin/dev/slopbot/model/` + `dao/`)

Morphia entities — `@Entity(useDatabase = "slopbot", value = "slops")` / `"slopAssets"`:

**Slop**: `ObjectId id`; `@Indexed(unique) String name` (e.g. `savorysailor-tomato-tribunal.js`); `String displayName` (title); `Long timestamp`; `List<String> tags` (`["legacy"]`); `@Embedded Participant creator` (`{id, login, displayName}` — the chat user who prompted); `@Embedded Participant streamer` (the tenant owner — the streamer whose chat created it); `String moderation` (`PENDING|APPROVED|REJECTED|SUSPENDED`); `boolean suspended`; `@Embedded List<Trigger> triggers`; `Integer cooldownSeconds`; `boolean slopCommand`; `Integer playCount`; `Long lastPlayedAt`; `@Embedded Metadata metadata` (`{short, author, summary, controls, moderationLevel}`); `@Embedded LargeObjectRef source` (`{ObjectId id, String mimeType, String originalFilename, String state}` → GridFS); `@Embedded List<Remark> agentRemarks`; `@Embedded List<Remark> contentRemarks`.

**SlopAsset**: `ObjectId id`; `@Indexed(unique) String name` (`shared/boing` or `<slop-name>/boing`); `String displayName` (bare sample name); `Long timestamp`; `List<String> tags` (`["shared"]` | `["slop", "<slop-name>"]`); `String kind` (`AUDIO|VIDEO`); `String mimeType`; `@Embedded Metadata metadata` (`{originalFilename}`); `@Embedded LargeObjectRef source`; `@Embedded List<Remark> agentRemarks`; `@Embedded List<Remark> contentRemarks`.

**Remark**: `{ String text; String kind (`AGENT_BUILD`|`CONTENT`); Long timestamp; String author; }` — searchable via the MetadataSpec; a remark may carry a `LargeObjectRef linkedDoc` (a separately linked document).

**Trigger**: `{ String type (`chat|firstchat|follow|firstfollow|sub|resub|subgift|raid|cheer|redemption`); String mode (`exact|contains|starts|ends|word|wildcard|regex`); List<String> patterns; Boolean caseSensitive; String who (`anyone|mods|broadcaster`); Integer minViewers; Integer minBits; String rewardId; boolean enabled; boolean bypassCooldown; String source (`streamer|creator`); }` — creator-sourced triggers are PROPOSALS (see engine rules).

**Metadata/searchability:** register a `MetadataSpec` on the application configuration so `metadata` + remarks are queryable per the Elements manual ("Metadata Spec", queries). Indexed fields stay in `tags`.

DAOs (Guice `PrivateModule`, exposed): `SlopDao`, `SlopAssetDao` — tenant-scoped queries (`streamer.login`); write-through semantics ported from `lib/store.js`: register, triggers save, suspend, cooldown changes, verdicts, `$inc` play statistics; source cache refreshed on registration.

## REST surface (Jakarta RS, all under `/slop/api`)

Session auth = Elements session (`Authorization` bearer / session forms per the manual). Roles: `streamer` (tenant owner), `viewer`, `SUPERUSER` (Elements user level). Management routes: 401 anonymous / 403 viewer. Overlay/viewer routes stay OPEN (no session): snapshot, SSE events, bundles, heartbeat, end reports, cameo plays.

- `GET  /slop/api/slop` — the status snapshot (registry, queue, cooldowns, config echo, connection liveness: `irc.connected`, `lastTrafficAgoMs`).
- `GET  /slop/api/slop/events` — SSE feed (queue/plays/cooldowns broadcasts).
- `GET  /slop/api/slop/bundle/{name}` — the playable bundle: code (base64) + inlined assets + shared sounds + `ctx.streamer` cam + `maxSeconds` + `controls` + `trigger` + `chained` flag (cameos cannot chain).
- `POST /slop/api/slop/queue/{id}/{present|dismiss|reject|approve|restart|cancel}` — queue management (management).
- `POST /slop/api/slop/suspend/{name}` — quick-suspend (management).
- `GET|POST /slop/api/slop/triggers/{name}` — get/save trigger list (management; `normalizeTrigger` validation).
- `POST /slop/api/slop/triggers/{name}/test` — synthetic trigger test (management).
- `POST /slop/api/slop/cooldown/{name}/clear` — cooldown reset (management).
- `POST /slop/api/slop/trigger/dispatch` — synthetic trigger dispatch (management).
- `POST /slop/api/slop/config` — config update (management; BOM-tolerant parse heritage).
- `POST /slop/api/slop/plays/heartbeat` — overlay heartbeat (OPEN).
- `POST /slop/api/slop/plays/pause|resume|remove` — playback queue ops (management for pause/resume/remove; the overlay's own play-remove is OPEN).
- `POST /slop/api/slop/play/{name}` — manual play (OPEN — deck buttons + `!slop` resolve here).
- `POST /slop/api/slop/ended` — overlay end report (OPEN; CORS preflight answered; retry-tolerant).
- `POST /slop/api/slop/pending/{name}/approve|reject` — moderation verdicts (management).
- `POST /slop/api/slop/delete/{name}` — delete (management).
- `POST /slop/api/slop/build` — enqueue a build for the agent (management; one at a time — the `generation` slot).
- **Websockets (TRANSPORT REFINEMENT, 2026-11-24 — supersedes REST batching):**
  - **The ELM runs a websocket CLIENT connecting OUT to the pod's agent-address websocket** (`ws://<pod-webserver>`; the worker runs the websocket SERVER, the ELM is the authenticated client). Inbound: the worker's forwarded events (chat lines, follows/subs/resubs/subgifts/raids/cheers/redemptions, stream-start/end notices — coalesced frames accepted) run the ELM-side trigger loop. Outbound over the SAME websocket: the playback commands (play, stageclear, plays updates, notes). OIDC-verified end to end — the ELM presents its bearer in the client handshake; the worker validates it against the bound instance. Element attributes: `dev.slopbot.element.pod.ws.url` / `.token`.
  - **A websocket SERVER for pages:** each page (the OBS overlay, the deck) holds its own websocket connection to the ELM (mounted under `/slop/ws` — `@ServerEndpoint("/pages")`); the ELM **relays everything through its code** — the pod's events (post trigger evaluation, plus raw passthrough where the design requires) and the playback/queue updates — out to each connected page. Websockets are the primary page transport; the SSE events feed stays as a fallback/compat shim.
- Admin (SUPERUSER + acting-identity header): `POST /slop/api/admin/slop/impersonate {streamer}`; `POST /slop/api/admin/slop/impersonate/exit`; `GET /slop/api/admin/slop/queues`; `GET /slop/api/admin/slop/registry`; `POST /slop/api/admin/slop/{name}/suspend|reinstate|remove`; `GET /slop/api/admin/slop/moderation-queue`; `GET /slop/api/admin/slop/audit`. Every mutating admin route records `actingAs` + `actor` in an append-only `slop_audit` collection (who, whom, what, when, payload digest).

## MCP tool surface (in this ELM; the agent's ONLY data access)

Transport: MCP over HTTP (JSON-RPC; Streamable-HTTP semantics — one endpoint `POST /slop/api/mcp` + SSE stream endpoint `GET /slop/api/mcp/events` is acceptable for the first pass). Auth middleware: bearer OIDC token minted by the bound Elements instance; validate signature/issuer/audience/expiry per call; 401 missing/expired/foreign; 403 wrong scope. The token is **streamer-scoped**: that streamer, and only that streamer, runs that pod.

Tools (8 verbs; JSON-RPC method names `tools/call` with `name`):
1. `slops.search` — query owned slops by name/displayName/creator/tags → summary list (no source).
2. `slops.source` — ONE merged source-reading tool with a scope flag: `scope=owned` → metadata + source of an owned slop (content reference; includes same-channel references); `scope=library` → raw source of ANY slop, read-only (technical/mechanical reference only). Remarks are searchable when finding reference materials.
3. `slops.read` — read one owned slop: metadata + source.
4. `slops.create` — create for this streamer: runs the bouncer (below) then `metaOf`, assigns the name server-side (author-slug of the title, capped 39 chars, `-2`/`-3`… collision counters), records creator (from the call — fed through the prompt), lands `moderation: PENDING`. Returns the assigned name.
5. `slops.update` — update an owned slop's source and/or metadata: same bouncer + meta pipeline; **a running history of 50 modifications** (previous source revisions kept in large-object history).
6. `assets.list` — every asset available to the tenant (shared pool tagged `shared`), with kind/mime/size.
7. `assets.read` — returns the **large-object id** (the agent fetches bytes via the Large Object API); **8 MiB cap**.
8. `slops.submitModeration` — submit a created/updated slop for moderation (not playable until approved).

**The bouncer (`inspectSlopCode` port) — byte-for-byte semantics:** source is a string ≤ 250 KB (`MAX_SLOP_CODE_BYTES = 250*1024`); requires `export const meta`; requires `export function init` or `frame`; denylist regexes (reject): `require(`, `from 'node:` / `from "node:`, `fetch(`, `XMLHttpRequest`, `new WebSocket`, `importScripts(`, `eval(`, `new Function`, `document.cookie`, `localStorage`, `sessionStorage`, `indexedDB`. Error messages match the original ("slop code exceeds the 244 KB cap", "no meta block", "no init/frame exports", "the bouncer says no: <label>").

**`metaOf` port:** regex-extract `title`, `author`, `prompt`, `createdAt`, `moderationLevel` (single-quoted fallbacks: "Untitled slop", "unknown"); `controls` from a backtick template literal (multiline, `\\`` unescaped) else single-line (`\n` unescaped); `triggers` bracket-matched from the source then `JSON.parse`d with trailing-comma tolerance (`,\s*[}\]]` → stripped), malformed → dropped, capped at 12 entries.

**Naming port:** author = meta.author lowercased `[a-z0-9]`-only (fallback `anonymous`); slug = title lowercased, non-alphanumerics → `-`, trimmed, ≤ 25 chars (fallback `slop`); name = `author-slug.js` ≤ 39 chars else `author-<base36 ts>.js` else `slop-<base36 ts>.js`; on collision append `-2`, `-3`… (safe-name regex `^[a-z0-9][a-z0-9-]{0,47}\.js$`).

## Engine semantics (Kotlin port; parity = the regression suite)

- **Trigger matching:** chat criteria modes `exact|contains|starts|ends|word|wildcard|regex` with `caseSensitive`; `who` = anyone/mods/broadcaster; event types map (`chat`→[chat, firstchat], `follow`→[follow, firstfollow], else identity); `firstchat` fires only on first-ever message (allow empty patterns = any first line; `chat` requires patterns); registry allow-list gates creator-proposed triggers only (default allow: chat, firstchat); non-chat creator proposals are stored but held until the streamer enables them.
- **Cooldowns:** per-slop seconds; arm on trigger plays only (`bypassCooldown` skips); manual plays never arm; streamer-resettable.
- **Flood cap:** `triggerDispatchesPerMinute` (default 6) — dropped triggers surface a visible throttled overlay note.
- **First-ever tracking:** firstchat/firstfollower per channel.
- **Immediate vs queued dispatch** per trigger config; playback queue with `maxQueueDepth` (default 10).
- **Cameos:** `ctx.playSlop(name)` — same rules (cooldowns, no immediate repeats, suspended refuse), chain depth 1 (a cameo cannot chain further).
- **Moderation levels:** `teen` default; the tier in effect at build time is stamped on the result even if the agent omitted it.
- **Config:** `slop.json` keys — `redeemTitle`, `channel`, `announce`, `maxSlopSeconds` (60), `maxQueueDepth` (10), `moderationLevel`, `customRules`, `agentInstructions`, `model`, `triggerDispatchesPerMinute` (6), `allowedTriggers`, `streamerCam` — BOM-tolerant reads; `configure()` merges (never clobbers newer keys).

## Worker mode (`worker/`)

> **REMOVED** (thin-forwarder scope change, 2026-11-24). The pod's main
> container is a thin Node forwarder in the POD repo
> (NamazuStudios/slopbot-ai-pod): it monitors the streamer's chat/EventSub and
> forwards events to `POST /slop/api/worker/events`. The trigger loop runs
> ELM-side in this element's engine; playback commands relay back through the
> ELM's `WorkerRelay` chain to the pod's webserver. This repo ships only the
> ELM.

`WorkerMain.kt`: boots a minimal Elements runtime carrying this element (mirror element-example's debug runner); env-injected config (`SLOP_STREAMER`, `SLOP_MCP_URL`, `SLOP_OIDC_TOKEN`, Twitch credentials via the Conductor-injected secret); connects Twitch IRC (keepalive ping every 15s, force-reconnect when pongs stop) + EventSub; runs the trigger loop (evaluate → flood-cap → dispatch immediate/queued); drives inference through the pod-local proxy (OpenCode API behind the security proxy — the ELM connects headlessly); serves a **lightweight webserver** (the relay endpoint the ELM connects to: events relayed, OIDC-verified end to end); lingers ~10 minutes after stream end, then exits. Exit code 0 on clean shutdown — the Conductor job wrapper retries the pod until successful exit.

## Tests (must pass before review)

1. **Bouncer port tests:** every denylist pattern fires; the 250 KB boundary; meta/init/frame requirements.
2. **`metaOf` tests:** extraction; controls (template-literal + single-line); triggers strict-JSON with trailing-comma tolerance; malformed → dropped; 12-entry cap.
3. **Naming tests:** author-slug; 39-char cap; collision counters; safe-name fallbacks.
4. **Trigger tests:** every mode; case sensitivity; who-gating; firstchat semantics; allow-list gating; flood cap; immediate vs queued; bypassCooldown.
5. **Cooldown tests:** arm-on-trigger only; manual plays unaffected; clear endpoint.
6. **Round-trip test:** Slop → GridFS source → bundle → equality (the `store-roundtrip.js` port).
7. **Auth matrix test:** every REST route × (anonymous/viewer/streamer/SUPERUSER) → 401/403/200; overlay routes open; admin routes under the acting-identity header write audit rows.
8. **MCP tests:** token matrix (none/expired/foreign/wrong-scope); create→moderate→play flow with zero filesystem.

## Build

`mvn install` produces `element/target/<artifact>-<version>.elm` (elm plugin per element-example). Java 21, Kotlin 2.1. `mvn verify` runs all tests.

## Implementation notes (first pass, 2026-11-24)

**Status: implemented and green.** `mvn install` succeeds from a clean checkout with all tests passing (see counts below). The engine is one ELM-side engine (Kotlin) plus the thin-forwarder ingestion surface — per the 2026-11-24 scope change.

### Scope change — "thin forwarder" (2026-11-24)

- The `worker/` module (Kotlin `WorkerMain.kt`: Twitch IRC/EventSub client, trigger loop, relay webserver, MCP client) is **REMOVED** from this repo. The pod's main container is a thin Node forwarder implemented in NamazuStudios/slopbot-ai-pod.
- **TRANSPORT REFINEMENT (2026-11-24) — websockets end to end, not REST batching.** The interim REST ingestion route (`POST /slop/api/worker/events`) is **dropped**; the ELM implements two websocket surfaces instead:
  - a websocket **CLIENT** connecting OUT to the pod's agent-address websocket (`PodWebsocketClient` + `PodWsProtocol`): inbound frames (the worker's forwarded events, coalesced batches accepted) run the ELM-side trigger loop; outbound command frames on the same websocket carry the playback commands; the ELM presents its OIDC bearer in the handshake and the worker validates it (end-to-end verification);
  - a websocket **SERVER for pages** (`PageWebsocketEndpoint` at `/slop/ws/pages` + `PageRelay`): the overlay and the deck each hold their own connection; the ELM relays everything through its code — post-evaluation events, raw passthrough where the design requires, and playback/queue updates. The SSE events feed remains as a fallback/compat shim.
- The trigger loop, cooldowns, flood cap, queues, and moderation stay ELM-side (unchanged) — only the transport changed.
- Worker env contract (now in the POD repo): `SLOP_STREAMER`, `SLOP_MCP_URL`, `SLOP_OIDC_TOKEN`, **`SLOP_PROXY_AUDIENCE`** (added to the pod's allow-listed env — the audience value the security proxy validates tokens against; the worker presents tokens through the proxy, whose audience claim must match), `SLOP_RELAY_PORT`, `SLOP_LINGER_MINUTES`, Twitch credentials.

### Implemented

- **Modules:** `api` (Kotlin DTOs + `SlopEngineService` interface, classified jar) and `element` (Morphia entities, DAOs, engine ports, REST, MCP, Guice → the `.elm` archive). Platform: `dev.getelements.elements:*:3.9.0`, Java 21, Kotlin 2.1.0, Morphia 2.4.14, MongoDB driver 4.10.2 (the platform's own pinned versions).
- **Entities:** `Slop`, `SlopAsset`, `SlopAudit`, `SlopModification` (Morphia, `@Entity("slops"|"slopAssets"|"slop_audit"|"slop_mod_history")`) with embedded `Participant`, `Trigger`, `Remark` (agentRemarks/contentRemarks split + `linkedDoc`), `Metadata`, `LargeObjectRef`; unique `name` indexes via `@Indexed(options = @IndexOptions(unique = true))`.
- **DAO layer:** `SlopDao`/`SlopAssetDao` (+ `SlopAuditDao`, `SlopHistoryDao`) — tenant-scoped queries (`streamer.login`) and the write-through semantics ported from `lib/store.js` (put upsert, trigger/cooldown/suspend/moderation patches, `$inc` play stats, delete-with-own-assets, source read).
- **Engine ports (behavior-exact against the JS oracle):** the bouncer (`inspectSlopCode` — 250×1024 UTF-16-unit cap, meta/init/frame checks, the full 14-entry denylist with the original messages), `metaOf` (line-anchored scalar extraction, template-literal/single-line controls, bracket-matched triggers with trailing-comma tolerance, 12-entry cap), naming (author-slug, 39-char cap, base-36 timestamp fallbacks, `-2`/`-3` collision counters, safeName/shortName), trigger engine (all 7 chat modes, `triggerTypesFor` mapping, who-gating, firstchat semantics, `normalizeTrigger` with the reference's coercions, allow-list gating), cooldowns (arm-on-trigger only, streamer clear), flood cap (default 6/min with the throttled visible note), first-ever tracking (chat + follow, never reset; synthetic dispatches don't consume slots), immediate-vs-queued dispatch, cameos (cooldown-aware, no self-cameo, chain depth 1), and the redemption-queue/play state machine.
- **REST:** all routes under `/slop/api` (`dev.getelements.elements.element.rs.root = "/slop/api"`), overlay routes OPEN (snapshot, SSE, bundle, play, heartbeat, ended), management routes session-gated (401 anonymous / 403 viewer / 200 streamer-or-superuser), admin routes SUPERUSER + `X-Slop-Acting-As` with append-only audit rows (SHA-256 payload digest).
- **MCP:** `POST /slop/api/mcp` (JSON-RPC `initialize`/`tools/list`/`tools/call`) + `GET /slop/api/mcp/events` (SSE); the 8 verbs backed by the DAOs; OIDC bearer middleware (RS256 via cached JWKS, iss/aud/exp/scope — 401/403 matrix); 8 MiB `assets.read` cap; 50-modification running history with previous source revisions in large-object history.
- **Ingestion transport (websockets end to end):** the ELM's authenticated WS **client** to the pod (`PodWebsocketClient`/`PodWsProtocol` — events in, playback commands out, OIDC bearer in the handshake) and the pages' WS **server** (`PageWebsocketEndpoint` at `/slop/ws/pages` + `PageRelay` — every engine event fanned to each connected page, raw event passthrough, failed pages reaped). The SSE feed stays as a compat shim.
- **Tests:** 161 unit tests green (bouncer 25, metaOf 18, naming 13, trigger engine 39, config 11, engine state machine 15, auth matrix 7, MCP surface 13 incl. the live-JWKS token matrix and create→moderate→play with zero filesystem, websocket transport 9 covering the pod protocol (events in → trigger loop, coalesced frames, stream notices, malformed-frame resilience, cooldowns over the wire), the command frames back to the pod, the pages' relay fan-out + dead-page reaping, and raw passthrough) + 2 integration tests (`SlopDaoRoundTripIT`, `-Pintegration`, `MONGO_URI`-gated) **verified against a real MongoDB**: Slop → GridFS source → read → equality, write-throughs, play stats, tenant scoping, delete-with-assets, and the asset round-trip.

### Deviations from the spec (adapted to the real platform API)

1. **`@Entity(useDatabase = "slopbot")` is not expressible** — Morphia 2.4's `@Entity` carries no `useDatabase` attribute. The element instead builds its own private Morphia Datastore over the shared `slopbot` database (`SlopbotDatabase`, from the injected platform `MongoClient`; collections `slops`/`slopAssets`/`slop_audit`/`slop_mod_history` + GridFS bucket `largeObjects` all live there). The spec's intent — one shared `slopbot` DB — is honored on the real API.
2. **The bouncer's size message says "250 KB"** (the JS's dynamic `Math.round(MAX_SLOP_CODE_BYTES / 1024)`), not the spec's quoted "244 KB" — the spec's own binding rule is "error messages match the original", and the original computes 250.
3. **Playback queue depth** uses `maxQueueDepth` (default 10) per the spec; the JS reference hard-codes 5 in `play()`/`playImmediate()`.
4. **Cameo cooldowns:** the spec (binding) says cameos obey cooldowns; the JS's play route never checks them. Cameos (`chained=true`) check the window and refuse a self-cameo; manual plays never do.
5. **Tenant-unscoped management routes** (config, queue ops, build): an authenticated USER passes as the self-serve tenant (every Twitch login IS a streamer identity; data access is DAO-scoped). The viewer→403 case is enforced on tenant-scoped routes (a USER touching another tenant's resource).
6. **First-ever chat tracking** lives in `dispatchTrigger` (the ELM's only chat entry point) rather than the reference's IRC line handler — the worker (now the thin forwarder) has no engine state; synthetic dispatches carry `synthetic=true` so they never consume first-ever slots.
7. **REST resources resolve services via `ElementSupplier`/`getServiceLocator()`** (the element-example pattern — the Jersey runtime doesn't inject Guice directly into resources).
8. **JWKS/OIDC is real but issuer-configured:** the validator fetches+cache-caches JWKS (discovery → `<issuer>/.well-known/openid-configuration`, fallback `/.well-known/jwks.json`), verifies RS256, iss/aud/exp/nbf/scope. The issuer URL is an element attribute (`dev.slopbot.element.oidc.issuer`) — until the deployment wires a real issuer the MCP/ingestion surfaces fail closed (401). This is the "needs the platform" item: the bound instance's OIDC endpoints.
9. **GridFS is used directly** (`GridFSBuckets` on the element's Datastore, bucket `largeObjects`, metadata path/mimeType/state/originalFilename per the manual) rather than the platform `LargeObjectDao` service — the Large Object API wiring (CDN URLs, `assets.read` via `LargeObjectDao.findLargeObject`) is the platform-integration follow-up; the `LargeObjectRef` shape already matches.
10. **First-ever seen-sets persist in-process** for the first pass (the reference persisted them to a JSON file). Durable per-channel first-ever tracking wants a small platform collection — follow-up.

### Needs the platform (follow-ups)

- Real OIDC issuer wiring for the pod/MCP tokens (JWKS endpoint + audience claims minted by the bound instance).
- `LargeObjectDao` integration for CDN-backed asset reads (the MCP `assets.read` returns the large-object id; the fetch path is wired once the platform's Large Object API is bound into the element).
- Durable first-ever tracking and the config persistence (currently in-process; a `slop_config` collection would match the reference's merged-write semantics).
- The moderation build queue consumes MCP clients (the agent polls/creates via MCP); the deck's `generation` slot is ELM-side state.
