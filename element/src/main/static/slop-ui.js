'use strict';
// slop-ui.js — the shared API client for the slopbot.gg pages. Same-origin
// credentials ride the adopted slop_session cookie automatically; the
// whoami is cached; errors surface as a branded toast.
window.SLOP = (() => {
  let mePromise = null;

  function toast(text, bad = false) {
    let host = document.getElementById('slop-toast-host');
    if (!host) {
      host = document.createElement('div');
      host.id = 'slop-toast-host';
      host.style.cssText = 'position:fixed; top:1rem; right:1rem; z-index:80; display:flex; flex-direction:column; gap:.5rem;';
      document.body.appendChild(host);
    }
    const t = document.createElement('div');
    t.className = 'card';
    t.style.cssText = `padding:.6rem 1rem; font-size:.85rem; max-width:340px; color:#fff;
      border-color:${bad ? '#ff5c7a' : 'var(--pink)'}; background:rgba(16,23,64,.95)`;
    t.textContent = text;
    host.appendChild(t);
    setTimeout(() => t.remove(), 4200);
  }

  async function api(path, opts = {}) {
    const res = await fetch(path, {
      method: opts.method || (opts.body ? 'POST' : 'GET'),
      headers: opts.body ? { 'Content-Type': 'application/json' } : undefined,
      body: opts.body ? JSON.stringify(opts.body) : undefined,
      credentials: 'same-origin',
    });
    if (res.status === 401 && !opts.quiet401) throw Object.assign(new Error('authentication required'), { status: 401 });
    if (!res.ok) throw Object.assign(new Error(`${res.status} on ${path}`), { status: res.status });
    return res.json();
  }

  /** The session user (cached) — null when anonymous. */
  function me() {
    if (!mePromise) {
      mePromise = fetch('/slop/api/me', { credentials: 'same-origin' })
        .then((r) => (r.ok ? r.json() : null))
        .catch(() => null);
    }
    return mePromise;
  }

  /** The live engine snapshot — registry, queues, plays, config. */
  function snapshot() { return api('/slop/api/slop'); }

  /** The live event stream (SSE). Returns the EventSource. */
  function events(onEvent) {
    const es = new EventSource('/slop/api/slop/events');
    for (const type of ['registry', 'plays', 'queue', 'note', 'complete', 'cooldowns', 'presented', 'stageclear', 'stream', 'dismissed']) {
      es.addEventListener(type, (e) => {
        let data = {};
        try { data = JSON.parse(e.data); } catch (_) { /* keep {} */ }
        onEvent(type, data);
      });
    }
    return es;
  }

  return { api, me, snapshot, events, toast };
})();
