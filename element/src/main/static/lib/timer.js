// ---------------------------------------------------------------------------
// Starting Soon countdown timer, shared by the deck (control) and the
// /starting-soon scene (display). The timer state lives on the server
// (in-memory) because the deck tab and the OBS browser source are separate
// browser instances — the API is the only channel between them.
//
// Pure helpers (parseDuration / formatDuration) are exported for testing.
// ---------------------------------------------------------------------------

// Parses deck input into seconds. Accepts:
//   "10"      → 10 minutes
//   "10:00"   → 10 minutes
//   "1:30:00" → 1 hour 30 minutes
// Returns null for anything invalid.
export function parseDuration(text) {
  if (typeof text !== 'string') return null;
  const trimmed = text.trim();
  if (!/^\d+(:\d{1,2}){0,2}$/.test(trimmed)) return null;

  const parts = trimmed.split(':').map(Number);
  let hours = 0;
  let minutes = 0;
  let seconds = 0;
  if (parts.length === 1) {
    minutes = parts[0]; // bare number = minutes
  } else if (parts.length === 2) {
    [minutes, seconds] = parts;
  } else {
    [hours, minutes, seconds] = parts;
  }
  if (parts.length >= 2 && (minutes > 59 || seconds > 59)) return null;

  const total = hours * 3600 + minutes * 60 + seconds;
  if (total < 1 || total > 86400) return null; // 1 second … 24 hours
  return total;
}

// Formats remaining milliseconds as mm:ss.cc (or h:mm:ss.cc beyond an
// hour), truncated to hundredths so 00:00.00 only appears once the time
// has actually elapsed.
export function formatDuration(remainingMs) {
  const total = Math.max(0, Math.floor(remainingMs / 10)); // hundredths
  const hours = Math.floor(total / 360000);
  const minutes = Math.floor((total % 360000) / 6000);
  const seconds = Math.floor((total % 6000) / 100);
  const hundredths = total % 100;
  const mm = String(minutes).padStart(2, '0');
  const ss = String(seconds).padStart(2, '0');
  const cc = String(hundredths).padStart(2, '0');
  return hours > 0 ? `${hours}:${mm}:${ss}.${cc}` : `${mm}:${ss}.${cc}`;
}

// --- server API ---------------------------------------------------------------

export async function fetchTimerState() {
  const response = await fetch('/api/timer');
  if (!response.ok) throw new Error(`timer API ${response.status}`);
  return response.json(); // { endTime, now }
}

export async function startTimer(seconds) {
  const response = await fetch('/api/timer/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ seconds }),
  });
  if (!response.ok) throw new Error(`timer API ${response.status}`);
  return response.json();
}

export async function stopTimer() {
  const response = await fetch('/api/timer/stop', { method: 'POST' });
  if (!response.ok) throw new Error(`timer API ${response.status}`);
  return response.json();
}

// --- scene-side display ---------------------------------------------------------

// Drives a countdown element: polls the API every 2s, ticks locally between
// polls, holds at 00:00 (pulsing) when finished, and hides itself whenever
// no timer is running.
export function attachCountdown(el) {
  let endTime = null;
  let offset = 0; // serverNow - clientNow, captured at each fetch

  async function sync() {
    try {
      const state = await fetchTimerState();
      endTime = state.endTime;
      offset = state.now - Date.now();
    } catch {
      // Server momentarily unreachable — keep the last known state.
    }
    render();
  }

  function render() {
    if (endTime === null) {
      el.hidden = true;
      el.classList.remove('zero');
      el.textContent = '';
      return;
    }
    el.hidden = false;
    const remaining = endTime - (Date.now() + offset);
    el.textContent = formatDuration(remaining);
    el.classList.toggle('zero', remaining <= 0);
  }

  sync();
  setInterval(sync, 2000);
  setInterval(render, 50); // hundredths need a fast local tick
}
