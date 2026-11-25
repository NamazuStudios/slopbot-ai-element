// The slop sandbox — shared by the /slop overlay and the deck's preview
// modal. Mounts one gated slop (code + inlined assets) into a fire-and-
// forget sandboxed iframe: unique origin, CSP blocks all network, assets
// ride along as base64. The parent owns the fuse — the slop cannot extend
// its own life. Chained plays (cameos) get a no-op ctx.playSlop.
//
// mountSlop(stage, bundle, opts) → { stop(), iframe, bundle, opts }
//   opts.reportEnded — POST /slop/api/slop/ended when this mount ends or is
//     replaced (the overlay does this; the deck's local preview does not)
//   opts.feedOrigin  — where those reports go (overlay: the feed port)
//   opts.playId      — the play instance this stage mount belongs to
//   opts.onEnded     — called when the fuse burns out or stop() runs
//   opts.muted       — previews: the host drops sound commands entirely

// --- host-side sound engine ----------------------------------------------------
// ONE AudioContext for the whole page, owned here. Sandbox iframes post
// 'slop-sound' commands and the host synthesizes them, gain-capped. A
// per-iframe context exhausted Chrome's ~6-per-page pool after a few mounts
// (previews loop!) — which first crashed sandboxes, then (once defensive)
// silenced every slop after it. One host-owned context never runs out.
let pageAudio = null;
function pageAudioEngine() {
  if (pageAudio) return pageAudio;
  const AudioCtx = window.AudioContext ?? window.webkitAudioContext;
  if (!AudioCtx) return null;
  try {
    const actx = new AudioCtx();
    actx.resume().catch(() => {}); // OBS's browser allows autoplay; browsers may not
    // Some CEF builds leave the context suspended until a gesture — keep
    // nudging it until it's actually running.
    const kick = setInterval(() => {
      if (actx.state === 'running') {
        clearInterval(kick);
      } else {
        actx.resume().catch(() => {});
      }
    }, 250);
    setTimeout(() => clearInterval(kick), 10000);
    const master = actx.createGain();
    master.gain.value = 0.5; // the ear-rape cap, one level for the whole page
    master.connect(actx.destination);
    pageAudio = { actx, master, buffers: new Map() }; // buffers: slop name → (asset name → AudioBuffer)
  } catch {
    pageAudio = null;
  }
  return pageAudio;
}

export function mountSlop(stage, bundle, opts = {}) {
  const muted = Boolean(opts.muted);
  const config = {
    reportEnded: Boolean(opts.reportEnded),
    feedOrigin: opts.feedOrigin ?? '',
    playId: opts.playId ?? bundle.playId ?? null,
    onEnded: opts.onEnded ?? null,
  };

  // Report the replaced mount (only if that mount reports at all).
  const previous = stage._slopMount;
  if (previous?.opts.reportEnded) {
    reportEndedFor(previous);
  }

  stage.replaceChildren();

  const html = `<!doctype html>
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src blob: 'unsafe-inline'; media-src blob:; img-src blob: data:; style-src 'unsafe-inline'">
<body style="margin:0;background:${opts.stageBg ?? "transparent"};overflow:hidden"><canvas id="c"></canvas>
<script type="module">
const ASSETS = ${JSON.stringify(bundle.assets)};
const MAX_SECONDS = ${bundle.maxSeconds};
const b64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

const canvas = document.getElementById('c');
const fit = () => { canvas.width = innerWidth; canvas.height = innerHeight; };
fit();

// Sounds play on the PARENT page: the sandbox posts a command, the host
// synthesizes it on its own single AudioContext (see pageAudioEngine below).
const sound = (detail) => { try { parent.postMessage({ type: 'slop-sound', ...detail }, '*'); } catch {} };

const video = {};
for (const a of ASSETS) {
  const bytes = b64(a.base64);
  if (a.kind === 'video') {
    const el = document.createElement('video');
    el.src = URL.createObjectURL(new Blob([bytes], { type: a.mime }));
    el.muted = false; el.volume = 0.6; el.loop = true;
    el.preload = 'auto';
    video[a.name] = { el, ready: new Promise((r) => el.oncanplay = r) };
    el.load();
  }
  // audio assets are decoded host-side; sample() just names them
}

const ctx = {
  width: canvas.width, height: canvas.height,
  accent: ${JSON.stringify(opts.accent ?? '')},
  bg: ${JSON.stringify(opts.bg ?? '')},
  canvas: canvas.getContext('2d'),
  rand: (() => { let s = 1337; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; })(),
  sound: {
    beep(freq = 440, ms = 200) {
      sound({ sound: 'beep', freq: Number(freq) || 440, ms: Math.max(1, Math.min(Number(ms) || 200, 2000)) });
    },
    noise(ms = 200) {
      // A shaped noise HIT — fast attack, exponential decay, lowpass sweep —
      // so effects read as whooshes/impacts, never raw static. Shaped
      // host-side; here it's just the duration.
      sound({ sound: 'noise', ms: Math.max(20, Math.min(Number(ms) || 200, 2000)) });
    },
    sample(name) {
      sound({ sound: 'sample', name: String(name ?? '').slice(0, 60) });
    },
    // Informational: the host caps the mix at this level (previews: 0).
    gain: { value: ${muted ? 0 : 0.5} },
  },
  media: {
    video(name) { return video[name] ?? null; },
  },
};

// Cameo hook: ask the host to play another registered slop. The host applies
// all its rules; cameos get a no-op — one level of chaining only.
ctx.playSlop = ${bundle.chained
    ? '() => {}'
    : '(name) => parent.postMessage({ type: "slop-play", name: String(name) }, "*")'};

// Early end: the arc landed, the slop is finished — tell the host, which
// tears down and reports ended exactly like the fuse. This can only
// SHORTEN the run; nothing can extend it past MAX_SECONDS.
ctx.done = () => { try { parent.postMessage({ type: 'slop-done' }, '*'); } catch {} };

// The trigger event that started this play (null when played manually —
// deck, !slop, redeem approval). Whitelisted fields only: what the event
// MEANS (type, who, how big, first or not), never raw chat text.
ctx.trigger = ${JSON.stringify(bundle.trigger ?? null)};

// Where the streamer's camera sits on this canvas, normalized 0–1 —
// { x, y, w, h }. Pixel coords: x * ctx.width, y * ctx.height. null when
// the streamer hasn't marked their position.
ctx.streamer = ${JSON.stringify(bundle.streamerCam ?? null)};

addEventListener('resize', () => { fit(); ctx.width = canvas.width; ctx.height = canvas.height; });

// Interactive slops: the host forwards viewer chat here (rate limited
// server-side). The slop's optional onChat(ctx, msg) gets a plain object:
// { user, text, cmd, args, at } — at matches frame's t. Only the parent may
// talk to this frame, and only 'slop-chat' messages are accepted.
let elapsedSec = 0;
window.addEventListener('message', (event) => {
  if (event.source !== parent) return;
  const d = event.data;
  if (!d || d.type !== 'slop-chat') return;
  const text = String(d.text ?? '').slice(0, 120);
  const words = text.trim().split(/\s+/).filter(Boolean);
  const msg = {
    user: String(d.user ?? '').slice(0, 30),
    text,
    cmd: (words[0] ?? '').toLowerCase().slice(0, 30),
    args: words.slice(1).join(' '),
    at: elapsedSec,
  };
  try { slop.onChat?.(ctx, msg); } catch (err) { console.error('slop onChat failed', err); }
});

const code = atob(${JSON.stringify(bundle.codeBase64)});
const blob = new Blob([code], { type: 'text/javascript' });
const slop = await import(URL.createObjectURL(blob));
try { slop.init?.(ctx); } catch (err) { console.error('slop init failed', err); }
// Trigger-fired plays hand the slop its event context once, right after
// init — onTrigger(ctx, event) is optional; manual plays simply skip it.
if (ctx.trigger) { try { await slop.onTrigger?.(ctx, ctx.trigger); } catch (err) { console.error('slop onTrigger failed', err); } }

// The canvas can resize after boot (OBS resizing/re-embedding the source),
// and slops precompute their layout in init() — so a size change re-runs
// init with the corrected ctx. Keep init re-runnable.
const refit = () => {
  fit();
  ctx.width = canvas.width;
  ctx.height = canvas.height;
  try { slop.init?.(ctx); } catch (err) { console.error('slop re-init failed', err); }
  // Re-post the boot report: the debug chip (and tests) should show the
  // CURRENT truth after a re-init, not the original boot's.
  try { parent.postMessage({ type: 'slop-boot', w: ctx.width, h: ctx.height, streamer: ctx.streamer }, '*'); } catch {}
};
addEventListener('resize', refit);
// Tell the host what this sandbox actually booted with — the ?debug=1 chip
// surfaces it, so a bad play shows its own boot size and cam state.
try { parent.postMessage({ type: 'slop-boot', w: innerWidth, h: innerHeight, streamer: ctx.streamer }, '*'); } catch {}

const started = performance.now();
let last = started;
// 30fps cap: full-canvas 2D fills are the overlay's biggest GPU composite
// cost. Slops animate from t and dt, so timing stays correct at any rate —
// dt is clamped so a backgrounded frame (RAF pauses when hidden) can't leap.
const FRAME_MIN_MS = 1000 / 30;
const loop = (now) => {
  const t = (now - started) / 1000;
  if (t > MAX_SECONDS) { try { slop.dispose?.(ctx); } catch {} return; }
  if (now - last < FRAME_MIN_MS) { requestAnimationFrame(loop); return; }
  // Self-heal the canvas size: a remount can race the parent's sizing of
  // the fresh iframe, and a stale canvas draws scaled or cropped. Compare
  // against the live viewport every frame — near-free — and re-fit + sync
  // ctx when they drift.
  if (canvas.width !== innerWidth || canvas.height !== innerHeight) {
    refit(); // re-fit + re-init: the slop's init-time layout was sized wrong
  }
  const dt = Math.min((now - last) / 1000, 0.1);
  last = now;
  elapsedSec = t; // onChat messages carry this as msg.at (matches frame's t)
  try { slop.frame?.(ctx, t, dt); } catch (err) { console.error('slop frame failed', err); }
  requestAnimationFrame(loop);
};
requestAnimationFrame(loop);
<\/script></body>`;

  const iframe = document.createElement('iframe');
  iframe.setAttribute('sandbox', 'allow-scripts'); // unique origin — no parent DOM, no storage
  iframe.srcdoc = html;
  iframe.allow = 'autoplay';
  stage.append(iframe);

  // Decode this slop's audio assets into the host engine (once per slop
  // name) — sample() commands play from these host-side buffers.
  const engine = pageAudioEngine();
  if (engine && !engine.buffers.has(bundle.name)) {
    const map = new Map();
    engine.buffers.set(bundle.name, map);
    for (const a of bundle.assets) {
      if (a.kind !== 'audio') continue;
      try {
        const bytes = Uint8Array.from(atob(a.base64), (c) => c.charCodeAt(0));
        engine.actx.decodeAudioData(bytes.buffer).then((buf) => map.set(a.name, buf)).catch(() => {});
      } catch {
        // undecodable asset — sample() for it stays silent
      }
    }
  }

  const handle = {
    iframe,
    bundle,
    opts: config,
    // Forward one viewer chat message to the slop's onChat handler — the
    // overlay relays Twitch chat; the deck preview sends test messages.
    sendChat(msg) {
      iframe.contentWindow?.postMessage({
        type: 'slop-chat',
        user: String(msg?.user ?? ''),
        text: String(msg?.text ?? ''),
      }, '*');
    },
    stop() {
      if (stage._slopMount?.handle !== handle) return;
      teardown();
      if (config.reportEnded) reportEndedFor(handle);
      config.onEnded?.();
    },
  };

  // The fuse belongs to the parent: when it burns, the slop goes away.
  const fuse = setTimeout(() => {
    if (stage._slopMount?.handle !== handle) return;
    teardown();
    if (config.reportEnded) reportEndedFor(handle);
    config.onEnded?.();
  }, (bundle.maxSeconds + 3) * 1000);

  // Early end: ctx.done() from inside the sandbox — same teardown and
  // end-report as the fuse, just sooner. Only this mount's frame counts.
  const mountIsCurrent = () => stage._slopMount?.handle === handle;
  const endMount = () => {
    teardown();
    if (config.reportEnded) reportEndedFor(handle);
    config.onEnded?.();
  };
  const onDoneMessage = (event) => {
    if (event.source !== iframe.contentWindow) return;
    if (event.data?.type !== 'slop-done') return;
    if (!mountIsCurrent()) return;
    endMount();
  };
  window.addEventListener('message', onDoneMessage);

  // Sound commands from the sandbox → the host engine. Only the CURRENT
  // mount may make sound, and previews (muted) drop commands entirely.
  const onSoundMessage = (event) => {
    if (event.source !== iframe.contentWindow) return;
    const d = event.data;
    if (!d || d.type !== 'slop-sound') return;
    if (muted || !mountIsCurrent()) return;
    const engine = pageAudio;
    if (!engine || engine.actx.state !== 'running') return; // suspended → silent
    const t0 = engine.actx.currentTime + 0.01;
    const gain = engine.actx.createGain();
    gain.connect(engine.master);
    if (d.sound === 'beep') {
      const freq = Math.min(20000, Math.max(20, Number(d.freq) || 440));
      const ms = Math.max(1, Math.min(Number(d.ms) || 200, 2000)) / 1000;
      const osc = engine.actx.createOscillator();
      osc.frequency.value = freq;
      osc.connect(gain);
      gain.gain.setValueAtTime(0.0001, t0);
      gain.gain.exponentialRampToValueAtTime(0.6, t0 + 0.01);
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + ms);
      osc.start();
      osc.stop(t0 + ms + 0.05);
    } else if (d.sound === 'noise') {
      // shaped hit: fast attack, exponential decay, lowpass sweep
      const dur = Math.max(0.02, Math.min(Number(d.ms) || 200, 2000) / 1000);
      const actx = engine.actx;
      const buf = actx.createBuffer(1, Math.ceil(actx.sampleRate * dur), actx.sampleRate);
      const data = buf.getChannelData(0);
      for (let i = 0; i < data.length; i++) data[i] = Math.random() * 2 - 1;
      const src = actx.createBufferSource();
      src.buffer = buf;
      const filter = actx.createBiquadFilter();
      filter.type = 'lowpass';
      filter.frequency.setValueAtTime(1800 + Math.random() * 1600, t0);
      filter.frequency.exponentialRampToValueAtTime(160, t0 + dur);
      filter.Q.value = 0.7;
      src.connect(filter);
      filter.connect(gain);
      const attack = Math.min(0.012, dur * 0.15);
      gain.gain.setValueAtTime(0.0001, t0);
      gain.gain.exponentialRampToValueAtTime(0.5 + Math.random() * 0.1, t0 + attack);
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
      src.start(t0);
    } else if (d.sound === 'sample') {
      const buf = engine.buffers.get(bundle.name)?.get(String(d.name ?? '').toLowerCase());
      if (!buf) return;
      const src = engine.actx.createBufferSource();
      src.buffer = buf;
      src.connect(gain);
      src.start(t0);
    } else {
      return; // unknown command — disconnect the unused gain
    }
  };
  window.addEventListener('message', onSoundMessage);

  // Boot report: what this sandbox ACTUALLY saw at boot (size + cam state)
  // — the ?debug=1 chip surfaces it, so a bad play shows its own truth.
  const onBootMessage = (event) => {
    if (event.source !== iframe.contentWindow) return;
    const d = event.data;
    if (!d || d.type !== 'slop-boot') return;
    if (stage._slopMount?.handle !== handle) return;
    stage._slopMount.bootInfo = { w: d.w, h: d.h, streamer: d.streamer ?? null };
  };
  window.addEventListener('message', onBootMessage);

  function teardown() {
    clearTimeout(fuse);
    window.removeEventListener('message', onDoneMessage);
    window.removeEventListener('message', onSoundMessage);
    window.removeEventListener('message', onBootMessage);
    if (stage._slopMount?.handle === handle) stage._slopMount = null;
    stage.replaceChildren();
  }

  stage._slopMount = { handle, bundle, opts: config };
  return handle;
}

function reportEndedFor(mount) {
  // Same-origin on purpose: the report is a JSON POST, which is a
  // preflighted CORS request against the feed port — and the feed server
  // never answered preflights, so every end report was silently blocked
  // and plays stalled to the watchdog. The overlay/deck pages are served
  // from the main app, so a plain same-origin POST just works.
  fetch('/slop/api/slop/ended', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: mount.bundle.name, playId: mount.opts.playId ?? null }),
  }).catch(() => {});
}
