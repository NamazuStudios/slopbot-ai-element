'use strict';
// deck-lite.js — the deck's look-and-feel pass: registry + the two queues,
// split panes, no OBS scene switcher, no countdown timer. Live data from
// /slop/api/slop when the ELM answers; placeholder data otherwise (the
// look-and-feel evaluation runs on placeholder per the designer).

const BASE = window.location.pathname.startsWith('/slop') ? '/slop/api' : '/slop/api';

const PLACEHOLDER = {
  registered: [
    { name: 'rubbery-hotdog.js', title: 'Rubbery Hotdog', author: 'viewerdude', suspended: false },
    { name: 'cuck-chair-monsoon.js', title: 'Cuck Chair Monsoon', author: 'chatgremlin', suspended: false },
    { name: 'pixel-cat-pounce.js', title: 'Pixel Cat Pounce', author: 'lurkerno9', suspended: false },
    { name: 'spiky-pine-scam-hour.js', title: 'Spiky Pine Scam Hour', author: 'modlyperson', suspended: true },
    { name: 'tomato-tribunal.js', title: 'Tomato Tribunal', author: 'savorysailor', suspended: false },
    { name: 'got-milk-bandit.js', title: 'Got Milk Bandit', author: 'absurdsage', suspended: false },
  ],
  queue: [
    { id: 'q1', text: 'a raccoon DJ drops a beat', status: 'building' },
    { id: 'q2', text: 'the chat decides the streamer fate', status: 'pending' },
  ],
  plays: {
    current: { name: 'got-milk-bandit.js', title: 'Got Milk Bandit' },
    upcoming: [
      { name: 'pixel-cat-pounce.js', title: 'Pixel Cat Pounce' },
      { name: 'tomato-tribunal.js', title: 'Tomato Tribunal' },
    ],
    paused: false,
  },
};

const el = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const short = (e) => e.short || String(e.name || '').replace(/\.js$/, '');

function renderRegistry(entries) {
  const list = el('registry-list');
  list.innerHTML = '';
  el('reg-count').textContent = `· ${entries.length}`;
  if (!entries.length) { list.innerHTML = '<div class="empty">No slops yet — your chat fixes that.</div>'; return; }
  for (const e of entries) {
    const d = document.createElement('div');
    d.className = 'item';
    d.innerHTML = `
      <div class="t"><b>${esc(e.title || short(e))}</b><span>by ${esc(e.author || 'unknown')} · ${esc(e.name)}</span></div>
      ${e.suspended ? '<span class="st pill rejected">suspended</span>' : ''}
      <div class="act"><button data-play="${esc(short(e))}">Play</button></div>`;
    list.appendChild(d);
  }
}

function renderGeneration(queue) {
  const list = el('generation-list');
  list.innerHTML = '';
  el('gen-count').textContent = `· ${queue.length}`;
  if (!queue.length) { list.innerHTML = '<div class="empty">Nothing building. Chat prompts land here.</div>'; return; }
  queue.forEach((q, i) => {
    const d = document.createElement('div');
    d.className = 'item';
    d.innerHTML = `
      <span class="badge">${i + 1}</span>
      <div class="t"><b>${esc(q.text || q.prompt || '(no prompt)')}</b><span>${esc(q.status || 'queued')}</span></div>
      <span class="st">${esc(q.status || '')}</span>`;
    list.appendChild(d);
  });
}

function renderPlayback(plays) {
  const list = el('playback-list');
  list.innerHTML = '';
  const current = plays.current ? [plays.current] : [];
  const upcoming = plays.upcoming || [];
  el('play-count').textContent = `· ${current.length + upcoming.length}${plays.paused ? ' (paused)' : ''}`;
  if (!current.length && !upcoming.length) { list.innerHTML = '<div class="empty">Nothing queued to play.</div>'; return; }
  for (const p of current) {
    const d = document.createElement('div');
    d.className = 'item';
    d.style.borderColor = 'rgba(46, 230, 168, .45)';
    d.innerHTML = `
      <span class="chip"><span class="dot"></span> now</span>
      <div class="t"><b>${esc(p.title || short(p))}</b><span>on screen now</span></div>`;
    list.appendChild(d);
  }
  upcoming.forEach((p, i) => {
    const d = document.createElement('div');
    d.className = 'item';
    d.innerHTML = `
      <span class="badge">${i + 1}</span>
      <div class="t"><b>${esc(p.title || short(p))}</b><span>up next${plays.paused ? ' · paused' : ''}</span></div>`;
    list.appendChild(d);
  });
}

function renderCounts(s) {
  el('counts').textContent = `${(s.registered || []).length} slops · ${(s.queue || []).length} building · ${(s.plays?.upcoming || []).length} queued`;
}

function render(s) {
  renderRegistry(s.registered || []);
  renderGeneration(s.queue || []);
  renderPlayback(s.plays || {});
  renderCounts(s);
}

async function tick() {
  try {
    const res = await fetch(`${BASE}/slop`);
    if (!res.ok) throw new Error(res.status);
    render(await res.json());
  } catch {
    render(PLACEHOLDER);
  }
}

document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-play]');
  if (!b) return;
  fetch(`${BASE}/slop/play/${encodeURIComponent(b.dataset.play)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ via: 'deck' }),
  }).catch(() => {});
});

render(PLACEHOLDER);
tick();
setInterval(tick, 5000);
