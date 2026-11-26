// Slop overlay (/slop) — thin page boot. Listens on the SSE feed at
// /slop/api/slop/events and renders: the review modal,
// the SLOP COMPLETE + confetti moment, and the sandboxed slop stage.
// server.js owns the queue and the agent; this page only consumes.

import { mountSlop } from './lib/slop-sandbox.js';

function clampNumber(value, fallback, min, max) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
}

function hexColor(value, fallback) {
  return /^[0-9a-fA-F]{6}$/.test(value ?? '') ? `#${value}` : fallback;
}

// A playing slop asks for a cameo: postMessage from the sandbox → the host
// endpoint, which applies every server rule (cooldowns, repeats, suspends)
// and only honors one level of chaining (cameo bundles ship a no-op hook).
window.addEventListener('message', (event) => {
  const stage = document.getElementById('stage');
  const iframe = stage?._slopMount?.handle?.iframe;
  if (!iframe || event.source !== iframe.contentWindow) return;
  if (event.data?.type !== 'slop-play') return;
  const name = String(event.data.name ?? '').trim();
  if (!name) return;
  fetch(`/slop/api/slop/play/${encodeURIComponent(name)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ chained: true, via: 'cameo' }),
  }).catch(() => {});
});

// Liveness: the overlay pings the host every 2s. If this page dies (OBS
// source reload/deleted), the pings stop and the host ends the current
// play within seconds instead of stalling the queue for the full 75s fuse.
setInterval(() => {
  fetch('/slop/api/slop/plays/heartbeat', { method: 'POST' }).catch(() => {});
}, 2000);

// Module-level sinks: loadSlop lives outside boot()'s closure but still
// needs the toast surface and the feed origin for starve-proof bundles.
let toastSink = () => {};
let feedOrigin = '';

async function loadSlop(name, chained = false, playId = null) {
  // A replaced stage reports its end so the host's play queue advances —
  // carrying the play instance id so a replay of the same slop can't have
  // its own report mistaken for this one.
  try {
    // The playId MUST ride along: the host attaches the triggering event's
    // context (ctx.trigger) to the bundle for this exact play instance.
    const params = new URLSearchParams();
    if (chained) params.set('chained', '1');
    if (playId) params.set('playId', playId);
    const qs = params.toString();
    const path = `/slop/api/slop/bundle/${encodeURIComponent(name)}${qs ? '?' + qs : ''}`;
    let res;
    try {
      res = await fetch(feedOrigin + path);
    } catch {
      res = null;
    }
    if (!res || !res.ok) {
      res = await fetch(path); // same-origin fallback (older feed servers / CORS)
    }
    if (!res.ok) throw new Error(`bundle failed (${res.status})`);
    const stage = document.getElementById('stage');
    const bundle = await res.json();
    lastBundleInfo = { name: bundle.name, playId: bundle.playId ?? null, streamerCam: bundle.streamerCam ?? null, trigger: bundle.trigger ?? null };
    renderDebugChip('open');
    mountSlop(stage, bundle, {
      reportEnded: true,
      feedOrigin,
      playId: playId ?? null,
      accent: getComputedStyle(document.documentElement).getPropertyValue('--accent').trim(),
      bg: getComputedStyle(document.documentElement).getPropertyValue('--bg').trim(),
    });
    renderControlsHud(bundle.controls);
  } catch (err) {
    renderControlsHud(null);
    toastSink(`the bouncer says no: ${err.message}`);
  }
}

// On-screen hint for chat interactions ("1: launch · 2: flip"), from the
// slop's meta.controls — hidden when the author didn't define any.
const controlsHud = document.getElementById('slop-controls-hud');
function renderControlsHud(controls) {
  if (!controlsHud) return;
  if (controls) {
    controlsHud.textContent = `chat: ${controls}`;
    controlsHud.hidden = false;
  } else {
    controlsHud.hidden = true;
  }
}

// --- confetti stars -----------------------------------------------------------

function celebrate() {
  const canvas = document.getElementById('confetti');
  canvas.width = innerWidth;
  canvas.height = innerHeight;
  const c = canvas.getContext('2d');
  const accent = (getComputedStyle(document.documentElement).getPropertyValue('--accent') || '').trim() || '#ff0099';
  const parts = [];
  for (let i = 0; i < 140; i++) {
    parts.push({
      x: innerWidth / 2 + (Math.random() - 0.5) * innerWidth * 0.3,
      y: innerHeight / 2,
      vx: (Math.random() - 0.5) * 900,
      vy: -Math.random() * 900 - 200,
      s: 6 + Math.random() * 10,
      spin: Math.random() * Math.PI * 2,
      vspin: (Math.random() - 0.5) * 10,
      life: 2.5 + Math.random() * 1.5,
    });
  }
  const startedAt = performance.now();
  const step = (now) => {
    try {
      const t = (now - startedAt) / 1000;
      c.clearRect(0, 0, canvas.width, canvas.height);
      let alive = false;
      for (const p of parts) {
        const age = t;
        if (age > p.life) continue;
        alive = true;
        const x = p.x + p.vx * age;
        const y = p.y + p.vy * age + 600 * age * age;
        c.save();
        c.translate(x, y);
        c.rotate(p.spin + p.vspin * age);
        c.globalAlpha = Math.min(1, (p.life - age) / 0.8);
        c.fillStyle = Math.random() < 0.7 ? accent : '#ffffff';
        drawStar(c, p.s);
        c.restore();
      }
      if (alive) requestAnimationFrame(step);
      else c.clearRect(0, 0, canvas.width, canvas.height);
    } catch (err) {
      console.error('[slop] confetti broke:', err);
    }
  };
  requestAnimationFrame(step);
}

function drawStar(c, r) {
  c.beginPath();
  for (let i = 0; i < 5; i++) {
    const a = (i * 4 * Math.PI) / 5 - Math.PI / 2;
    const px = Math.cos(a) * r;
    const py = Math.sin(a) * r;
    if (i === 0) c.moveTo(px, py);
    else c.lineTo(px, py);
  }
  c.closePath();
  c.fill();
}

// --- wiring ---------------------------------------------------------------------

// ?debug=1 — a corner chip showing the feed origin, SSE state, and the last
// bundle's cam/trigger payloads, so OBS-side staleness is visible on screen.
let debugChip = null;
let lastBundleInfo = null;
function renderDebugChip(sseState) {
  if (!debugChip) return;
  const cam = lastBundleInfo?.streamerCam;
  const camText = cam
    ? `cam ${(cam.x * 100).toFixed(0)}/${(cam.y * 100).toFixed(0)} ${(cam.w * 100).toFixed(0)}x${(cam.h * 100).toFixed(0)}`
    : 'cam NULL';
  const boot = document.getElementById('stage')?._slopMount?.bootInfo;
  const bootText = boot ? `boot ${boot.w}x${boot.h} cam ${boot.streamer ? 'ok' : 'NULL'}` : 'boot ?';
  debugChip.textContent =
    `feed ${feedOrigin || '(same-origin)'} · sse ${sseState} · ${bootText} · last: ${lastBundleInfo?.name ?? 'none'} · ${camText} · trig ${lastBundleInfo?.trigger?.type ?? 'none'}`;
}

// Error surface: overlay failures toast instead of dying silently.
window.addEventListener('error', (event) => {
  const box = document.getElementById('toasts');
  if (!box) return;
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = 'error: ' + (event.message || 'unknown');
  box.append(el);
  requestAnimationFrame(() => el.classList.add('show'));
});

function boot() {
  const params = new URLSearchParams(window.location.search);
  // The event feed lives on its own port so it never competes with page
  // loads for connection slots (see server.js). ?feed= overrides.
  // The ELM serves the event feed on the SAME origin (/slop/api) — the
  // monolith's :3010 feed port is gone with the thin-forwarder split.
  feedOrigin = params.get('feed') || `${location.protocol}//${location.host}`;
  document.documentElement.style.setProperty('--accent', hexColor(params.get('accent'), '#ff0099'));
  document.documentElement.style.setProperty('--bg', hexColor(params.get('bg'), '#0d1335'));
  document.documentElement.style.setProperty('--scale', String(clampNumber(params.get('scale'), 1, 0.5, 3)));

  const modal = document.getElementById('modal');
  const complete = document.getElementById('complete');
  let presentingId = null;

  // The stage's source of truth is the host's plays state: a new/changed
  // current play loads (fresh from the top), a cleared one empties the
  // stage. SSE delivers it instantly; a 1s poll below heals any gap.
  let lastStageName = null;
  let lastStageStartedAt = 0;
  const credit = document.getElementById('slop-credit');

  // Creator credit: who made the slop on screen, so viewers know who to
  // redeem (or blame). Shows for the whole play, gone when the stage is.
  function renderCredit(cur) {
    if (!credit) return;
    if (cur && cur.name) {
      credit.textContent = `${cur.title ?? cur.name.replace(/\.js$/, '')} — by @${cur.author ?? 'unknown'}`;
      credit.hidden = false;
    } else {
      credit.hidden = true;
    }
  }

  function syncStage(cur) {
    const stage = document.getElementById('stage');
    const name = cur?.name ?? null;
    const startedAt = cur?.startedAt ?? 0;
    renderCredit(cur);
    if (name && (name !== lastStageName || startedAt !== lastStageStartedAt)) {
      loadSlop(name, Boolean(cur.chained), cur.playId);
    } else if (!name && lastStageName) {
      clearStage();
    }
    lastStageName = name;
    lastStageStartedAt = startedAt;
  }
  function clearStage() {
    const stage = document.getElementById('stage');
    if (stage?._slopMount) stage._slopMount = null; // no ended report — host knows
    stage?.replaceChildren();
    renderCredit(null);
    renderControlsHud(null);
  }

  const toast = (text) => {
    const box = document.getElementById('toasts');
    const el = document.createElement('div');
    el.className = 'toast';
    el.textContent = text;
    box.append(el);
    requestAnimationFrame(() => el.classList.add('show'));
    setTimeout(() => {
      el.classList.add('gone');
      setTimeout(() => el.remove(), 300);
    }, 6000);
  };
  toastSink = toast; // module-level loadSlop reports through here

  // The agent comments readout: upper-left ticker, live while the agent
  // builds, hidden the moment the run finishes or fails. Shows the original
  // prompt next to the thinking rings, then the agent's narrated comments below it.
  const ticker = document.getElementById('ticker');
  const tickerPrompt = document.getElementById('ticker-prompt');
  const tickerModel = document.getElementById('ticker-model');
  const tickerText = document.getElementById('ticker-text');
  function renderGeneration(gen) {
    if (gen && gen.text) {
      ticker.hidden = false;
      tickerPrompt.hidden = !gen.prompt;
      tickerPrompt.textContent = gen.prompt ? 'redeem: ' + gen.prompt : 'building your slop…';
      tickerModel.hidden = !gen.model;
      tickerModel.textContent = gen.model ? 'model: ' + gen.model : '';
      tickerText.textContent = gen.text;
    } else {
      ticker.hidden = true;
      tickerPrompt.hidden = true;
      tickerPrompt.textContent = '';
      tickerModel.hidden = true;
      tickerModel.textContent = '';
      tickerText.textContent = '';
    }
  }

  // Fallback monitor: OBS shares ONE browser process across every source,
  // and its per-host connection limit can starve this page's SSE feed. A
  // 1s poll keeps the stage, the review modal, and the queue correct even
  // when the stream never arrives.
  //
  // Self-healing end reports: if the host still thinks a play is on this
  // stage but the stage is empty, the play's end report was lost (a dropped
  // fetch). Re-report it — with a short grace so a just-starting mount
  // (bundle still in flight) isn't mistaken for an ended one — otherwise
  // the host stalls the whole queue for its full 75s watchdog window.
  let stageEmptySince = null;
  setInterval(async () => {
    try {
      let res;
      try {
        res = await fetch(feedOrigin + '/slop/api/slop');
      } catch {
        res = null;
      }
      if (!res || !res.ok) res = await fetch('/slop/api/slop');
      const s = await res.json();
      renderGeneration(s.generation ?? null);
      syncStage(s.plays?.current ?? null);
      const cur = s.plays?.current ?? null;
      const mount = document.getElementById('stage')?._slopMount;
      if (cur && !mount) {
        stageEmptySince = stageEmptySince ?? Date.now();
        if (Date.now() - stageEmptySince > 2500) {
          // same-origin — the cross-origin variant dies in CORS preflight
          fetch('/slop/api/slop/ended', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: cur.name, playId: cur.playId ?? null }),
          }).catch(() => {});
        }
      } else {
        stageEmptySince = null;
      }
      if (presentingId) {
        const stillPresented = (s.queue ?? []).some((i) => i.id === presentingId && i.status === 'presented');
        if (!stillPresented) {
          presentingId = null;
          modal.classList.remove('show');
          setTimeout(() => { modal.hidden = true; }, 300);
        }
      }
    } catch {
      // server hiccup — try again next tick
    }
  }, 1000);

  // The keyed overlay: the OBS URL carries ?stream=<login>&k=<overlay-key> —
  // pass both through so the ELM validates the browser source (403 on a
  // wrong/rotated key).
  const overlayStream = params.get('stream');
  const overlayKey = params.get('k');
  const eventsUrl = feedOrigin + "/slop/api/slop/events" +
    ((overlayStream && overlayKey) ? `?stream=${encodeURIComponent(overlayStream)}&k=${encodeURIComponent(overlayKey)}` : "");
  const source = new EventSource(eventsUrl);
  if (params.get('debug')) {
    debugChip = document.createElement('div');
    debugChip.className = 'debugchip';
    document.body.append(debugChip);
    renderDebugChip('connecting');
    setInterval(() => renderDebugChip(['connecting', 'open', 'closed'][source.readyState] ?? source.readyState), 1000);
  }
  source.addEventListener('agent', (event) => {
    // The agent's narrated comments, upper left, while it builds.
    renderGeneration(JSON.parse(event.data));
  });
  source.addEventListener('chat', (event) => {
    // Viewer chat for the on-stage interactive slop → forwarded to its
    // onChat handler (the sandbox verifies the playId).
    const data = JSON.parse(event.data);
    const mount = document.getElementById('stage')?._slopMount;
    mount?.handle?.sendChat?.({ user: data.user, text: data.text });
  });
  source.addEventListener('state', (event) => {
    const data = JSON.parse(event.data);
    renderGeneration(data.generation ?? null);
    syncStage(data.plays?.current ?? null);
    // A stale page can't keep a judgment up for an item that already resolved.
    if (presentingId) {
      const stillPresented = (data.queue ?? []).some((i) => i.id === presentingId && i.status === 'presented');
      if (!stillPresented) {
        presentingId = null;
        modal.classList.remove('show');
        setTimeout(() => { modal.hidden = true; }, 300);
      }
    }
  });
  source.addEventListener('presented', (event) => {
    const { item } = JSON.parse(event.data);
    presentingId = item.id;
    modal.querySelector('.who').textContent = `by ${item.user?.name ?? item.user?.login ?? 'someone'}`;
    modal.querySelector('.text').textContent = item.input;
    modal.querySelector('.cost').textContent = `${item.reward?.cost ?? 0} points · judged live`;
    modal.hidden = false;
    requestAnimationFrame(() => modal.classList.add('show'));
  });
  // The modal's lifecycle is derived from the queue: the moment its item is
  // no longer presented (approved, denied, removed, or the SSE event was
  // missed), the judgment is over. A transient event can never strand it.
  source.addEventListener('queue', (event) => {
    if (!presentingId) return;
    const queue = JSON.parse(event.data).queue ?? [];
    const stillPresented = queue.some((i) => i.id === presentingId && i.status === 'presented');
    if (!stillPresented) {
      presentingId = null;
      modal.classList.remove('show');
      setTimeout(() => { modal.hidden = true; }, 300);
    }
  });
  source.addEventListener('dismissed', () => {
    modal.classList.remove('show');
    setTimeout(() => { modal.hidden = true; }, 300);
  });
  source.addEventListener('agent', (event) => {
    renderGeneration(JSON.parse(event.data));
  });
  source.addEventListener('complete', (event) => {
    const { title, author } = JSON.parse(event.data);
    // Note: no modal teardown here — a modal belongs to its review decision.
    // If another slop's generation completes mid-review, the review stays up.
    complete.hidden = false;
    complete.classList.add('show');
    celebrate();
    setTimeout(() => {
      complete.classList.remove('show');
      setTimeout(() => { complete.hidden = true; }, 400);
    }, 3500);
    toast(`${title} — by ${author}`);
  });
  source.addEventListener('failed', (event) => {
    const { reason } = JSON.parse(event.data);
    toast(`slop failed: ${reason}`);
  });
  source.addEventListener('stageclear', () => {
    // Pause (or a deck-side ✕): the stage empties immediately. No fuse
    // report — the host knows it stopped it.
    clearStage();
  });
  source.addEventListener('play', (event) => {
    // A replay must never dismiss an open review modal — the judgment is in
    // progress. The played slop simply runs beneath it until the decision.
    syncStage(JSON.parse(event.data).current ?? null);
  });
  source.addEventListener('note', (event) => {
    const { text } = JSON.parse(event.data);
    toast(text);
  });
}

if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  boot();
}
