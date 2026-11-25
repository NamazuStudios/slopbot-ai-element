// Local stream control deck (/deck): OBS scene switching, source toggles and
// a chat monitor. Open this page in a browser on the streaming machine — it
// is a tool, not an OBS source.
import { createObsClient } from './lib/obs.js';
import { mountSlop } from './lib/slop-sandbox.js';
import { clampNumber, createChatMonitor } from './lib/twitch-chat.js';
import {
  parseDuration,
  formatDuration,
  fetchTimerState,
  startTimer,
  stopTimer,
} from './lib/timer.js';

const PINNED_SCENES = ['Starting Soon', 'BRB', 'Coding'];
const HIDDEN_SCENES = ['Desktop Main', 'Camera']; // raw OBS scenes we never control from the deck
const TOGGLE_SOURCES = ['Hide Desktop']; // extend this list for more toggles
// Mobile quick column — the buttons that stay always-visible on narrow
// screens; everything else folds into the More menu.
const PRIMARY_SCENES = ['Starting Soon', 'BRB', 'Coding'];
const PRIMARY_SOURCES = ['Hide Desktop'];

const params = new URLSearchParams(window.location.search);

// OBS connection settings — URL params win over saved ones, defaults last.
// Everything persists to localStorage so the deck reconnects on its own.
const obsSettings = {
  host: (params.get('obsHost') || localStorage.getItem('obs.host') || 'localhost').replace(/[^\w.\-:[\]]/g, ''),
  port: clampNumber(params.get('obsPort') ?? localStorage.getItem('obs.port'), 4455, 1, 65535),
  password: params.get('password') || localStorage.getItem('obs.password') || '',
  enabled: localStorage.getItem('obs.enabled') !== '0', // disabled = '0'
};
const chatChannel = params.get('channel') || 'savorysailor';
const chatLines = clampNumber(params.get('lines'), 30, 1, 100);

// --- chat monitor -----------------------------------------------------------

createChatMonitor(document.getElementById('chat'), {
  channel: chatChannel,
  maxLines: chatLines,
});

// --- starting soon timer ----------------------------------------------------

const timerForm = document.getElementById('timer-form');
const timerInput = document.getElementById('timer-input');
const timerStop = document.getElementById('timer-stop');
const timerStatus = document.getElementById('timer-status');

let timerOffset = 0; // serverNow - clientNow, captured at each fetch
let timerEndTime = null; // last known endTime, ticked locally between polls

timerForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const seconds = parseDuration(timerInput.value);
  if (seconds === null) {
    timerInput.classList.add('invalid');
    timerStatus.classList.remove('running');
    timerStatus.textContent = 'invalid time — try 10 (minutes), 10:00 or 1:30:00';
    return;
  }
  timerInput.classList.remove('invalid');
  try {
    const state = await startTimer(seconds);
    timerOffset = state.now - Date.now();
    timerEndTime = state.endTime;
    renderTimerStatus();
  } catch (error) {
    timerStatus.textContent = `timer API error — ${error.message}`;
  }
});

timerInput.addEventListener('input', () => timerInput.classList.remove('invalid'));

timerStop.addEventListener('click', async () => {
  try {
    await stopTimer();
    timerEndTime = null;
    renderTimerStatus();
  } catch (error) {
    timerStatus.textContent = `timer API error — ${error.message}`;
  }
});

function renderTimerStatus() {
  if (timerEndTime === null) {
    timerStatus.classList.remove('running');
    timerStatus.textContent = 'no timer running';
    return;
  }
  const remaining = timerEndTime - (Date.now() + timerOffset);
  timerStatus.classList.add('running');
  if (remaining <= 0) {
    timerStatus.textContent = 'finished — holding at 00:00.00';
  } else {
    timerStatus.textContent = `${formatDuration(remaining)} remaining`;
  }
}

// Keep the readout live (and pick up timers even if started elsewhere).
async function pollTimer() {
  try {
    const state = await fetchTimerState();
    timerOffset = state.now - Date.now();
    timerEndTime = state.endTime;
  } catch {
    // Server momentarily unreachable — keep the last known state.
  }
  renderTimerStatus();
}
pollTimer();
setInterval(pollTimer, 1000);
setInterval(renderTimerStatus, 100); // hundredths need a fast local tick

// --- OBS --------------------------------------------------------------------

const statusDot = document.querySelector('#obs-status .dot');
const statusText = document.getElementById('obs-status-text');
const reconnectButton = document.getElementById('obs-reconnect');
const historyList = document.getElementById('obs-history-list');
const sceneButtons = document.getElementById('scene-buttons');
const toggleButtons = document.getElementById('toggle-buttons');
const primaryButtons = document.getElementById('primary-buttons'); // mobile quick column

let scenes = [];
let allSceneNames = []; // every OBS scene, unfiltered — toggle sources may live in hidden scenes
let currentScene = '';
const trackedSources = new Map(); // sourceName → [{sceneName, sceneItemId, enabled}]

const client = createObsClient({
  url: `ws://${obsSettings.host}:${obsSettings.port}`,
  password: obsSettings.password,
  onStatus: handleStatus,
});

// Last few status transitions with timestamps — the morning-after record of
// exactly when and how the OBS connection misbehaved.
const history = [];

// The header shows a short, stable label — the long detail lives in the
// settings modal, so retry cycles never push the header around.
const SHORT_STATE = {
  connected: 'obs connected',
  connecting: 'obs connecting…',
  'auth-required': 'obs password?',
  'auth-failed': 'obs password?',
  disabled: 'obs off',
  error: 'obs error',
  disconnected: 'obs offline',
  unreachable: 'obs offline',
  'handshake-dropped': 'obs offline',
};

function handleStatus(state, detail) {
  lastObsState = { state, detail: detail ?? '' };
  // History records outcomes, not every retry pulse: 'connecting' is the
  // header's live state, and an identical consecutive outcome (the retry
  // loop failing the same way over and over) collapses into one line, so
  // the list can't grow while OBS stays down.
  const last = history[0];
  if (state !== 'connecting' && !(last && last.state === state && last.detail === lastObsState.detail)) {
    history.unshift({ at: new Date(), state, detail: detail ?? '' });
    if (history.length > 20) history.pop(); // ring buffer — the last 20
    renderHistory();
  }
  renderObsState();

  // Buttons that drive OBS only exist while we're actually connected —
  // disabled/offline removes them entirely.
  const online = state === 'connected';
  sceneButtons.hidden = !online;
  toggleButtons.hidden = !online;
  primaryButtons.hidden = !online;

  // Contextual dot: green connected, yellow connecting, red anything else
  // (offline, disabled, auth trouble) — per the connection state.
  statusDot.className = `dot ${online ? 'connected' : state === 'connecting' ? 'connecting' : 'disconnected'}`;
  statusText.textContent = SHORT_STATE[state] ?? `obs ${state}`;
  if (online) {
    refreshScenes()
      .then(refreshToggles)
      .catch((error) => handleStatus('error', error.message));
  }
}

function renderHistory() {
  historyList.replaceChildren(
    ...history.map(({ at, state, detail }) => {
      const item = document.createElement('li');
      const stamp = document.createElement('time');
      stamp.textContent = at.toLocaleTimeString([], { hour12: false });
      const text = document.createElement('span');
      text.textContent = detail ? `${state} — ${detail}` : state;
      item.append(stamp, text);
      return item;
    }),
  );
}

// The dot is a reconnect button — one click, fresh backoff, no page reload.
reconnectButton.addEventListener('click', () => client.forceReconnect());

// While hidden, obs.js pauses its retry loop entirely; waking the page
// reconnects immediately instead of waiting out the backoff.
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && !client.isConnected()) client.forceReconnect();
});

// --- OBS settings modal --------------------------------------------------------
// Gear on the Scenes header: server, port, password, enable/disable, live
// state, and the connection-attempt history. Save applies and reconnects.

const obsSettingsModal = document.getElementById('obs-settings-modal');
const obsHostInput = document.getElementById('obs-host-input');
const obsPortInput = document.getElementById('obs-port-input');
const obsPasswordField = document.getElementById('obs-password');
const obsEnabledInput = document.getElementById('obs-enabled');
const obsStateDot = document.getElementById('obs-state-dot');
const obsStateText = document.getElementById('obs-state-text');

let lastObsState = { state: 'connecting', detail: '' };

function renderObsState() {
  const { state, detail } = lastObsState;
  if (obsStateDot) {
    obsStateDot.className = `dot ${state === 'connected' ? 'connected' : state === 'connecting' ? 'connecting' : state === 'disabled' ? 'disabled' : 'disconnected'}`;
  }
  if (obsStateText) obsStateText.textContent = detail ? `${state} — ${detail}` : state;
}

const openObsSettings = () => {
  obsHostInput.value = obsSettings.host;
  obsPortInput.value = String(obsSettings.port);
  obsPasswordField.value = obsSettings.password;
  obsEnabledInput.checked = obsSettings.enabled;
  renderObsState();
  renderHistory();
  obsSettingsModal.hidden = false;
};

const closeObsSettings = () => {
  obsSettingsModal.hidden = true;
};

document.getElementById('obs-settings').addEventListener('click', openObsSettings);
document.getElementById('obs-settings-close').addEventListener('click', closeObsSettings);
obsSettingsModal.addEventListener('click', (event) => {
  if (event.target === obsSettingsModal) closeObsSettings();
});

// Both buttons read the form — "Reconnect now" applies it too, so a checked
// "Connect to OBS" is never ignored.
function applyObsSettings() {
  const host = (obsHostInput.value || 'localhost').replace(/[^\w.\-:[\]]/g, '');
  const port = clampNumber(obsPortInput.value, 4455, 1, 65535);
  const password = obsPasswordField.value;
  const enabled = obsEnabledInput.checked;

  obsSettings.host = host;
  obsSettings.port = port;
  obsSettings.password = password;
  obsSettings.enabled = enabled;
  localStorage.setItem('obs.host', host);
  localStorage.setItem('obs.port', String(port));
  localStorage.setItem('obs.password', password);
  localStorage.setItem('obs.enabled', enabled ? '1' : '0');

  // One-pass apply — a disable closes and detaches the socket before
  // anything can reopen it.
  client.applySettings({
    url: `ws://${host}:${port}`,
    password,
    enabled,
  });
}

document.getElementById('obs-reconnect-now').addEventListener('click', () => {
  applyObsSettings();
  client.forceReconnect();
});

document.getElementById('obs-settings-form').addEventListener('submit', (event) => {
  event.preventDefault();
  applyObsSettings();
  closeObsSettings();
});

// --- scenes -------------------------------------------------------------------

// One scene button — shared by the full scene list and the mobile quick
// column, so both stay live and behave identically.
function sceneButton(name, missing = false) {
  const button = document.createElement('button');
  button.className = 'scene';
  button.textContent = name;
  if (missing) {
    button.classList.add('missing');
    button.title = 'Not found in OBS — check the scene name';
    return button;
  }
  if (name === currentScene) button.classList.add('active');
  button.addEventListener('click', () => switchWithStinger(name));
  return button;
}

async function refreshScenes() {
  const data = await client.getSceneList();
  currentScene = data.currentProgramSceneName ?? currentScene;
  allSceneNames = (data.scenes ?? []).map((s) => s.sceneName);
  const names = allSceneNames.filter((name) => !HIDDEN_SCENES.includes(name));
  const missing = new Set(PINNED_SCENES.filter((name) => !names.includes(name)));
  scenes = [
    ...PINNED_SCENES,
    ...names.filter((name) => !PINNED_SCENES.includes(name)),
  ];
  renderScenes(missing);
}

function renderScenes(missing = new Set()) {
  sceneButtons.textContent = '';
  for (const name of scenes) sceneButtons.appendChild(sceneButton(name, missing.has(name)));
  renderPrimary(missing);
}

// --- source toggles ---------------------------------------------------------

// One source toggle button — shared by the full list and the quick column.
function toggleButton(sourceName, items) {
  const button = document.createElement('button');
  button.className = 'toggle';

  if (items.length === 0) {
    button.classList.add('missing');
    button.textContent = `${sourceName} — not found`;
    button.title = 'No scene item with this source name was found in any scene';
    return button;
  }

  const on = items.some((item) => item.enabled);
  if (on) button.classList.add('on');
  button.textContent = `${sourceName} — ${on ? 'ON' : 'OFF'}`;
  button.addEventListener('click', async () => {
    const enable = !items.some((item) => item.enabled);
    for (const item of items) item.enabled = enable; // optimistic
    renderToggles();
    try {
      for (const item of items) {
        await client.setSceneItemEnabled(item.sceneName, item.sceneItemId, enable);
      }
    } catch (error) {
      handleStatus('error', error.message);
      refreshToggles().catch(() => {});
    }
  });
  return button;
}

async function refreshToggles() {
  for (const sourceName of TOGGLE_SOURCES) {
    const found = [];
    // Scan EVERY scene, not just the deck's button list: a toggle source can
    // live in a hidden/raw scene (Hide Desktop sits inside Desktop Main, which
    // Coding embeds) — filtering here made it report "not found".
    for (const sceneName of allSceneNames) {
      if (sceneName === undefined) continue;
      try {
        const { sceneItems = [] } = await client.getSceneItemList(sceneName);
        for (const item of sceneItems) {
          if (item.sourceName === sourceName) {
            found.push({
              sceneName,
              sceneItemId: item.sceneItemId,
              enabled: item.sceneItemEnabled,
            });
          }
        }
      } catch {
        // Scene vanished mid-scan (or an unreadable list) — skip it.
      }
    }
    trackedSources.set(sourceName, found);
  }
  renderToggles();
}

function renderToggles() {
  toggleButtons.textContent = '';
  for (const sourceName of TOGGLE_SOURCES) {
    toggleButtons.appendChild(toggleButton(sourceName, trackedSources.get(sourceName) ?? []));
  }
  renderPrimary();
}

// The mobile quick column: the primary scenes and source toggles, in order.
function renderPrimary(missingScenes = new Set()) {
  primaryButtons.textContent = '';
  for (const name of PRIMARY_SCENES) {
    primaryButtons.appendChild(sceneButton(name, missingScenes.has(name)));
  }
  for (const sourceName of PRIMARY_SOURCES) {
    primaryButtons.appendChild(toggleButton(sourceName, trackedSources.get(sourceName) ?? []));
  }
}

// --- secondary menu (mobile) ------------------------------------------------

// On narrow screens the secondary controls (every scene, the timer, the
// stinger) fold away behind the More bar so the primary buttons and chat
// fill the screen without scrolling. Wide screens show everything and hide
// the bar via CSS.
const menuToggle = document.getElementById('menu-toggle');
const deckRoot = document.getElementById('deck');

menuToggle.addEventListener('click', () => {
  const open = deckRoot.classList.toggle('menu-open');
  menuToggle.setAttribute('aria-expanded', String(open));
  menuToggle.textContent = open ? 'Close ▴' : 'More — scenes · timer · stinger ▾';
});

// --- stinger transition -----------------------------------------------------

// Auto: scene switches fire the wipe and cut inside its hold — the window
// where the cover is fully opaque, so the composite swap is invisible. The
// cut is scheduled from the run's own timestamps (the at/now pair corrects
// for clock skew) a fixed margin after the cover completes, leaving the
// rest of the hold as safety margin before the reveal. Switches made
// outside the deck (OBS UI, hotkeys) already happened uncovered, so they
// get a quick reveal-only flash instead. Everything degrades to a plain
// instant cut if the stinger API is down — a missing overlay must never
// strand a switch.
const CUT_MARGIN_MS = 80; // cut this far into the hold, after the cover completes
const STINGER_STYLES = {
  // sceneName → { style, direction } — per-destination looks; the default
  // is a slash entering from the lower left
};

let stingerOn = true;
let cutTimer = null;
let expectedScene = null; // the cut our choreography is about to make

const stingerAuto = document.getElementById('stinger-auto');
const stingerTest = document.getElementById('stinger-test');

stingerAuto.addEventListener('click', () => {
  stingerOn = !stingerOn;
  stingerAuto.classList.toggle('on', stingerOn);
  stingerAuto.textContent = `Auto — ${stingerOn ? 'ON' : 'OFF'}`;
});

async function switchScene(name) {
  try {
    await client.setCurrentScene(name);
  } catch (error) {
    handleStatus('error', error.message);
    expectedScene = null;
  }
}

async function switchWithStinger(name) {
  if (name === currentScene) return; // the active scene needs no switch
  if (!stingerOn) {
    await switchScene(name);
    return;
  }
  let run = null;
  try {
    const response = await fetch('/api/stinger', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ to: name, ...STINGER_STYLES[name] }),
    });
    if (!response.ok) throw new Error(`stinger API ${response.status}`);
    run = await response.json();
  } catch {
    // Stinger API unreachable — fall through to a plain cut.
  }
  if (!run) {
    await switchScene(name);
    return;
  }
  // Latest click wins: cancel any pending cut before scheduling this one.
  if (cutTimer) clearTimeout(cutTimer);
  expectedScene = { name, at: Date.now() };
  const delay = run.at - run.now + run.coverMs + CUT_MARGIN_MS;
  cutTimer = setTimeout(() => {
    cutTimer = null;
    switchScene(name);
  }, Math.max(0, delay));
}

// The test button fires a run on its own, for tuning the look.
stingerTest.addEventListener('click', async () => {
  stingerTest.disabled = true;
  try {
    const response = await fetch('/api/stinger', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    });
    if (!response.ok) throw new Error(`stinger API ${response.status}`);
    stingerTest.textContent = 'fired';
  } catch (error) {
    stingerTest.textContent = `error — ${error.message}`;
  } finally {
    stingerTest.disabled = false;
    setTimeout(() => { stingerTest.textContent = 'Fire test wipe'; }, 1500);
  }
});

// --- live updates from OBS --------------------------------------------------

client.on('CurrentProgramSceneChanged', (eventData) => {
  if (!eventData.sceneName) return;

  // Was this our own choreographed cut (or the plain path when Auto is
  // off)? If so it's already covered by the hold — consume the
  // expectation. Anything else is an external switch (OBS UI, hotkeys):
  // the cut already happened uncovered, so dress the aftermath with a
  // quick reveal-only flash.
  const ours = expectedScene
    && Date.now() - expectedScene.at < 2000
    && expectedScene.name === eventData.sceneName;
  expectedScene = null;

  currentScene = eventData.sceneName;
  renderScenes();

  if (!ours && stingerOn) {
    fetch('/api/stinger', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ to: eventData.sceneName, coverMs: 40, holdMs: 40 }),
    }).catch(() => {});
  }
});

client.on('SceneItemEnableStateChanged', (eventData) => {
  for (const items of trackedSources.values()) {
    const item = items.find(
      (i) => i.sceneName === eventData.sceneName && i.sceneItemId === eventData.sceneItemId,
    );
    if (item) {
      item.enabled = eventData.sceneItemEnabled;
      renderToggles();
    }
  }
});

client.on('SceneListChanged', () => {
  refreshScenes().then(refreshToggles).catch(() => {});
});

// --- twitch connect status ---------------------------------------------------

// The header chip: authorization + EventSub state, with a Connect jump when
// action is needed. Polls — it's a status light, not a live feed.
const twitchDot = document.getElementById('twitch-dot');
const twitchText = document.getElementById('twitch-text');
const twitchConnect = document.getElementById('twitch-connect');

async function refreshTwitchStatus() {
  try {
    const s = await (await fetch('/api/rewards/status')).json();
    // The connect link never hides: a saved token can go stale or revoked
    // while still looking "authorized", so re-authorizing must always be
    // one click away.
    if (s.authorized && s.broadcaster) {
      twitchDot.className = 'dot connected';
      twitchText.textContent = s.connected
        ? `twitch: live as ${s.broadcaster.login}`
        : `twitch: authorized as ${s.broadcaster.login}`;
      twitchConnect.textContent = 'Re-authorize';
      twitchConnect.hidden = false;
    } else if (s.configured) {
      twitchDot.className = 'dot auth-required';
      twitchText.textContent = 'twitch: needs authorization';
      twitchConnect.textContent = 'Connect';
      twitchConnect.hidden = false;
    } else {
      twitchDot.className = 'dot disconnected';
      twitchText.textContent = 'twitch: not configured';
      twitchConnect.textContent = 'Connect';
      twitchConnect.hidden = false;
    }
  } catch {
    twitchDot.className = 'dot';
    twitchText.textContent = 'twitch: server unreachable';
    twitchConnect.hidden = true;
  }
}
refreshTwitchStatus();
setInterval(refreshTwitchStatus, 30000);
window.addEventListener('focus', refreshTwitchStatus); // returning from the auth tab

if (obsSettings.enabled) {
  client.applySettings({
    url: `ws://${obsSettings.host}:${obsSettings.port}`,
    password: obsSettings.password,
    enabled: true,
  });
} else {
  client.applySettings({ url: `ws://${obsSettings.host}:${obsSettings.port}`, password: obsSettings.password, enabled: false });
}

// --- slop ---------------------------------------------------------------------

// The channel-point slop machine: review queue, agent progress, and the
// "check in your slops at stream end" nudge. Feed: /slop/api/slop/events (SSE).

const slopSection = document.getElementById('slop-section');
const slopQueue = document.getElementById('slop-queue');
const slopPlaylist = document.getElementById('slop-playlist');
const slopPlaypause = document.getElementById('slop-playpause');
let lastPaused = false;
let sseClientCount = 0; // browser sources holding a feed — connection-budget watch

function updatePlayPauseControl(isPaused) {
  lastPaused = isPaused;
  // Red square + "Playing" while running; green triangle + "Stopped" when not.
  slopPlaypause.innerHTML = `<span class="light ${isPaused ? 'stop' : 'go'}"></span> ${isPaused ? 'Stopped' : 'Playing'}`;
  slopPlaypause.title = isPaused ? 'Playback stopped — click to resume' : 'Playback running — click to pause';
  slopPlaypause.classList.toggle('running', !isPaused);
  slopPlaypause.classList.toggle('stopped', isPaused);
}

slopPlaypause.addEventListener('click', async () => {
  try {
    await fetch(lastPaused ? '/slop/api/slop/plays/resume' : '/slop/api/slop/plays/pause', { method: 'POST' });
  } catch {
    // server unreachable — the next snapshot restores the truth
  }
});
const slopRegistry = document.getElementById('slop-registry');
const slopCount = document.getElementById('slop-count');
let lastSlopConfig = {};

let slopQueueState = [];
let lastQueueSig = null; // skip identical queue re-renders — see renderSlopQueue
const knownSlopIds = new Set(); // for fresh-arrival detection
let expandedSlopId = null; // the item opened for review — local, not broadcast

const slopAction = async (id, action) => {
  try {
    await fetch(`/slop/api/slop/queue/${encodeURIComponent(id)}/${action}`, { method: 'POST' });
  } catch {
    // the server dropped it — the next queue snapshot reflects the truth
  }
};

// --- cancel-build confirm modal ------------------------------------------------
// The queue card's ✕ opens this instead of arming in place: the prompt and
// state are shown, and the agent run is only canceled on explicit confirm.

const cancelModal = document.getElementById('slop-cancel-modal');
let cancelId = null;

function openCancelConfirm(item) {
  cancelId = item.id;
  document.getElementById('slop-cancel-prompt').textContent = item.input;
  const statusText = { buildqueued: 'waiting to build', generating: 'building right now', failed: 'failed' }[item.status] ?? item.status;
  document.getElementById('slop-cancel-status').textContent = statusText + (item.reason ? ` — ${item.reason}` : '');
  cancelModal.hidden = false;
}

const closeCancelConfirm = () => {
  cancelModal.hidden = true;
  cancelId = null;
};

document.getElementById('slop-cancel-close').addEventListener('click', closeCancelConfirm);
document.getElementById('slop-cancel-keep').addEventListener('click', closeCancelConfirm);
cancelModal.addEventListener('click', (event) => {
  if (event.target === cancelModal) closeCancelConfirm();
});

document.getElementById('slop-cancel-confirm').addEventListener('click', () => {
  const id = cancelId;
  closeCancelConfirm();
  if (id) slopAction(id, 'cancel');
});

// Job timers tick locally from the server's timestamps — no extra traffic.
const fmtElapsed = (ms) => {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};
setInterval(() => {
  document.querySelectorAll('.slop-timer[data-start]').forEach((el) => {
    el.textContent = fmtElapsed(Date.now() - Number(el.dataset.start));
  });
}, 1000);

// The stage monitor: what's playing right now and what's lined up behind
// it — plays requested from the deck, chat, or cameos while the stage is
// busy queue up here instead of being refused.
function renderSlopPlays(plays) {
  const current = plays?.current ?? null;
  const upcoming = plays?.upcoming ?? [];
  const isPaused = Boolean(plays?.paused);
  const pausedPlay = plays?.pausedPlay ?? null;
  updatePlayPauseControl(isPaused);
  // Always visible — an idle stage says so explicitly instead of vanishing.
  const rows = [];

  const rowFor = (chipText, chipClass, entry, stoppable) => {
    const row = document.createElement('div');
    row.className = `play-row ${chipClass}`;
    // ✕ on the left, next to the chip.
    if (stoppable) {
      const remove = document.createElement('button');
      remove.className = 'removex';
      remove.textContent = '✕';
      remove.title = 'Stop / remove from the queue';
      remove.addEventListener('click', async () => {
        try {
          await fetch('/slop/api/slop/plays/remove', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: entry.name }),
          });
        } catch {
          // server unreachable — the next snapshot reflects the truth
        }
      });
      row.append(remove);
    }
    const chip = document.createElement('span');
    chip.className = `chip ${chipClass}`;
    chip.textContent = chipText;
    const title = document.createElement('span');
    title.className = 'reg-title';
    title.textContent = entry.title;
    row.append(chip, title);
    return row;
  };

  if (current) rows.push(rowFor('playing', 'playing', current, true));
  if (pausedPlay) rows.push(rowFor('paused', 'paused', pausedPlay, true));
  upcoming.forEach((entry, index) => {
    rows.push(rowFor(`#${index + 1} queued`, '', entry, true));
  });
  slopPlaylist.replaceChildren(...rows);
}

// The timeout bar: the countdown of the last-ended slop, with clear,
// fasttrack, and the adjustable interval. Lives at the top of the queue
// panel; ticks down locally each second.

function renderSlopQueue({ freshIds = [] } = {}) {
  // Newest first — a new request can never hide below the fold.
  const items = [...slopQueueState].reverse();
  // Identical content = no rebuild: the 1s poll used to replace every card
  // each tick, eating clicks mid-press (the "click twice" bug). Timers tick
  // locally, so skipping costs nothing.
  const sig = `${expandedSlopId}|${items.map((i) => `${i.id}|${i.status}|${i.model ?? ''}|${i.reason ?? ''}|${i.input ?? ''}|${i.target?.mode ?? ''}|${i.target?.name ?? ''}|${i.user?.login ?? ''}`).join(';')}`;
  if (sig === lastQueueSig) return;
  lastQueueSig = sig;
  slopQueue.replaceChildren(
    ...items.map((item) => {
      const row = document.createElement('div');
      row.className = `slop-item ${item.status}`;
      if (item.status === 'presented') row.classList.add('presented');
      const expanded = expandedSlopId === item.id;
      if (expanded) row.classList.add('expanded');
      if (freshIds.includes(item.id)) {
        row.classList.add('fresh');
        setTimeout(() => row.classList.remove('fresh'), 6000);
      }

      // Header: mode badge, redeemer, and the explicit action buttons.
      const head = document.createElement('div');
      head.className = 'head';

      const mode = item.target?.mode === 'delete' ? 'DEL' : item.target?.mode === 'modify' ? 'MOD' : 'NEW';
      const badge = document.createElement('span');
      badge.className = 'mode';
      badge.textContent = mode;

      const who = document.createElement('span');
      who.className = 'who';
      who.textContent = item.user?.login ?? '?';

      // The build model sits right of the username and survives failure —
      // it's the first thing to check when a build misbehaves.
      let modelSpan = null;
      if (item.model) {
        modelSpan = document.createElement('span');
        modelSpan.className = 'slop-model';
        modelSpan.textContent = item.model;
        modelSpan.title = 'build model';
      }

      // Where this request is in the review pipeline. Completed/denied ones
      // never reach the queue at all — they leave it the moment they resolve.
      const STATUS_LABELS = { pending: 'waiting', presented: 'reviewing', buildqueued: 'queued', generating: 'building', failed: 'failed' };
      const status = document.createElement('span');
      status.className = `chip ${item.status}`;
      status.textContent = STATUS_LABELS[item.status] ?? item.status.toUpperCase();
      if (item.status === 'generating') status.classList.add('busy');

      // Job timer: ticking since the job lined up (queued) or started
      // building — client-side from the server timestamps, no extra traffic.
      const t0 = item.status === 'generating' ? item.startedAt : item.queuedAt;
      if (t0 && item.status !== 'failed') {
        const timer = document.createElement('span');
        timer.className = 'slop-timer';
        timer.dataset.start = String(t0);
        timer.textContent = fmtElapsed(Date.now() - t0);
        head.append(timer);
      }

      const actions = document.createElement('span');
      actions.className = 'actions';

      if (item.status === 'generating' || item.status === 'failed') {
        // Manual override: restart kills any live agent run and re-lines the
        // job; on a failed job it just re-queues it.
        const restart = document.createElement('button');
        restart.textContent = '↻';
        restart.title = 'Restart this build';
        restart.addEventListener('click', (event) => {
          event.stopPropagation();
          slopAction(item.id, 'restart');
        });
        actions.append(restart);
      }
      if (item.status === 'buildqueued' || item.status === 'generating' || item.status === 'failed') {
        const cancel = document.createElement('button');
        cancel.textContent = '✕';
        cancel.title = 'Cancel — opens a confirmation';
        cancel.addEventListener('click', (event) => {
          event.stopPropagation();
          openCancelConfirm(item);
        });
        actions.append(cancel);
      }

      if (item.status === 'pending' || item.status === 'presented') {
        // One toggle: Show review puts the judgment on screen (viewers see
        // the modal), Hide review takes it back down. The modal itself
        // stays up through everything else until hidden or decided.
        const shown = item.status === 'presented';
        const toggle = document.createElement('button');
        toggle.className = 'review';
        toggle.textContent = shown ? 'Hide review' : 'Show review';
        toggle.addEventListener('click', (event) => {
          event.stopPropagation();
          slopAction(item.id, shown ? 'dismiss' : 'present');
        });
        actions.append(toggle);
      }

      const approve = document.createElement('button');
      approve.textContent = 'Approve';
      approve.addEventListener('click', (event) => {
        event.stopPropagation();
        slopAction(item.id, 'approve');
      });
      const deny = document.createElement('button');
      deny.textContent = 'Deny';
      deny.addEventListener('click', (event) => {
        event.stopPropagation();
        slopAction(item.id, 'reject');
      });
      if (item.status === 'pending' || item.status === 'presented') {
        // Decision buttons only exist while a decision is possible — a
        // BUILDING item is past the point of approval.
        actions.append(approve, deny);
      }
      head.append(badge, who, modelSpan, status, actions);
      // The timer goes between the chip and the buttons (append above moves
      // an already-attached timer to the end — reinsert it in place).
      const timerEl = head.querySelector('.slop-timer');
      if (timerEl) head.insertBefore(timerEl, actions);

      // Body: the prompt — clamped to two lines collapsed, full when open.
      // Failed jobs carry the reason under the prompt.
      const text = document.createElement('div');
      text.className = 'text';
      text.textContent = item.reason ? `${item.input}\n✗ ${item.reason}` : item.input;
      text.addEventListener('click', () => {
        expandedSlopId = expanded ? null : item.id;
        renderSlopQueue();
      });

      row.append(head, text);
      return row;
    }),
  );
}

function renderSlopCount(registered) {
  const clients = sseClientCount ?? 0;
  const line = registered.length === 0
    ? ''
    : `${registered.length} slop${registered.length === 1 ? '' : 's'} on file — check slops/ in at the end of the stream`;
  slopCount.textContent = line;
  // Connection-budget warning: OBS shares one browser process across all
  // sources, and ~6 connections per host is the ceiling. Every browser
  // source holding a feed eats one.
  const warn = document.getElementById('slop-connwarn');
  if (!warn) return;
  if (clients >= 5) {
    warn.textContent = `⚠ ${clients} browser sources are connected to the server (limit ≈6). Delete unused OBS browser sources — an exhausted budget is why slops stop playing.`;
    warn.classList.add('warn');
  } else {
    warn.textContent = clients ? `${clients} browser source${clients === 1 ? '' : 's'} connected` : '';
    warn.classList.remove('warn');
  }
}

// One compact row per slop: an enable checkbox (unchecked = suspended), the
// short handle, a live cooldown countdown with a clear button, and a Play
// button that runs it right now. The list scrolls inside its fixed 40%
// panel; the deck never grows.
let lastRegistry = [];
let lastRegistrySig = null; // skip identical re-renders — a 1s rebuild eats clicks mid-press
let lastCooldownUntil = {}; // name → epoch ms; ticked down locally each second
let slopSearchQuery = ''; // the registry search filter (live substring match)

// --- slop preview modal --------------------------------------------------------

// Preview modal: plays the slop muted inside the deck, exactly as the
// overlay would render it — same sandbox, same fuse, zero server side
// effects (a local preview never touches the play queue).
let previewMount = null;

function closePreview() {
  previewMount?.stop();
  previewMount = null;
  previewEntry = null;
  previewMode = null;
  previewBundle = null;
  sptEntry = null;
  sptMatchedFlash = new Set();
  renderPreviewChatControls(null);
  document.getElementById('slop-preview').hidden = true;
}

let previewEntry = null; // the slop being decided/deleted, if any
let previewMode = null; // null = view | 'decide' | 'delete'
let previewBundle = null; // the mounted bundle (carries meta.controls)

// --- preview test chat ---------------------------------------------------------
// Interactive slops react to viewer chat — this fires fake messages at the
// previewed slop locally, so onChat behavior is testable before approval.

function controlKeysFrom(controls) {
  // Controls are multi-line: a description line, then one
  // '!command - what it does' per line. Returns [{ cmd, desc, intro }].
  const lines = String(controls ?? '').split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const intro = lines.find((l) => !/^!\S+\s*-\s*/.test(l)) ?? null;
  const out = [];
  for (const line of lines) {
    const m = line.match(/^!(\S+)\s*-\s*(.+)$/);
    if (m) out.push({ cmd: m[1].toLowerCase(), desc: m[2].trim() });
  }
  return { intro, commands: out };
}

// Quick-fire buttons under the chat input, plus the full command list —
// the slop's own meta.controls, spelled out so the operator knows what
// viewers can type at it.
function renderPreviewChatControls(controls) {
  const quick = document.getElementById('slop-preview-quick');
  const list = document.getElementById('slop-preview-controls');
  if (!quick || !list) return;
  quick.replaceChildren();
  const { intro, commands } = controlKeysFrom(controls);
  if (!controls || commands.length === 0) {
    list.hidden = true;
    list.replaceChildren();
    return;
  }
  for (const { cmd, desc } of commands) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'quickbtn';
    button.textContent = cmd;
    button.title = `Send "${cmd}" as chat — ${desc}`;
    button.addEventListener('click', () => previewMount?.sendChat?.({ user: 'deck-preview', text: cmd }));
    quick.append(button);
  }
  list.replaceChildren();
  if (intro) {
    const p = document.createElement('p');
    p.className = 'spt-intro';
    p.textContent = intro;
    list.append(p);
  }
  for (const { cmd, desc } of commands) {
    const row = document.createElement('div');
    row.className = 'ctrl-row';
    const k = document.createElement('span');
    k.className = 'ctrl-cmd';
    k.textContent = '!' + cmd;
    const v = document.createElement('span');
    v.className = 'ctrl-desc';
    v.textContent = desc;
    row.append(k, v);
    list.append(row);
  }
  list.hidden = false;
}

// (the preview test-chat keydown handler lives with the trigger settings —
// it sends to onChat AND tests the chat triggers)

async function openPreview(entry, decide = false, del = false) {
  previewMode = del ? 'delete' : decide ? 'decide' : null;
  previewEntry = previewMode ? entry : null;
  const modal = document.getElementById('slop-preview');
  document.getElementById('slop-preview-title').textContent =
    (previewMode === 'delete' ? 'Delete — ' : previewMode === 'decide' ? 'Decide — ' : 'Preview — ') + entry.title;
  document.getElementById('slop-preview-approve').hidden = previewMode !== 'decide';
  document.getElementById('slop-preview-reject').hidden = previewMode !== 'decide';
  document.getElementById('slop-preview-delete').hidden = previewMode !== 'delete';
  modal.hidden = false;
  renderTriggersPane(entry); // the settings screen half of the modal
  try {
    const res = await fetch('/slop/api/slop/bundle/' + encodeURIComponent(entry.name));
    if (!res.ok) throw new Error('bundle failed (' + res.status + ')');
    const bundle = await res.json();
    previewBundle = bundle;
    renderPreviewChatControls(bundle.controls);
    const stage = document.getElementById('slop-preview-stage');
    previewMount = mountSlop(stage, bundle, {
      reportEnded: false, // local preview — never touches the play queue
      muted: true,
      stageBg: '#0d1335',
      onEnded: () => {
        // Deleting: the stage goes blank but the confirm button must survive.
        // Anything else: LOOP the preview — the slop replays instead of
        // closing the window, so triggers/settings can be edited while it
        // runs over and over.
        if (previewMode === 'delete') {
          previewMount?.stop();
          previewMount = null;
        } else {
          startPreviewMount();
        }
      },
    });
    fitPreviewStage();
  } catch (err) {
    closePreview();
    console.error('[deck] preview failed:', err.message);
  }
}

// Mount (or remount) the captured bundle into the preview stage — the
// replay path for when a previewed slop ends.
function startPreviewMount() {
  const stage = document.getElementById('slop-preview-stage');
  if (!stage || !previewBundle) return;
  previewMount = mountSlop(stage, previewBundle, {
    reportEnded: false, // local preview — never touches the play queue
    muted: true,
    stageBg: '#0d1335',
    onEnded: () => {
      if (previewMode === 'delete') {
        previewMount?.stop();
        previewMount = null;
      } else {
        startPreviewMount();
      }
    },
  });
  fitPreviewStage();
}
// Letterbox the preview iframe to an exact 16:9 inside whatever space the
// head/chat/triggers rows leave — the slop then draws with true overlay
// proportions instead of being cropped by a squashed stage.
let previewStageObserver = null;
function fitPreviewStage() {
  const stage = document.getElementById('slop-preview-stage');
  const iframe = previewMount?.iframe;
  if (!stage || !iframe) return;
  const rect = stage.getBoundingClientRect();
  if (rect.width < 2 || rect.height < 2) return;
  const byHeight = { w: rect.height * (16 / 9), h: rect.height };
  const byWidth = { w: rect.width, h: rect.width * (9 / 16) };
  const fit = byHeight.w <= rect.width ? byHeight : byWidth;
  iframe.style.width = Math.floor(fit.w) + 'px';
  iframe.style.height = Math.floor(fit.h) + 'px';
  if (!previewStageObserver) {
    previewStageObserver = new ResizeObserver(() => fitPreviewStage());
    previewStageObserver.observe(stage);
  }
}

document.getElementById('slop-preview-close').addEventListener('click', closePreview);

// The operator's verdict, straight from the preview modal.
document.getElementById('slop-preview-approve').addEventListener('click', async () => {
  if (!previewEntry) return;
  try {
    await fetch('/slop/api/slop/pending/' + encodeURIComponent(previewEntry.name) + '/approve', { method: 'POST' });
  } catch {
    // server unreachable — the next registry snapshot restores the truth
  }
  closePreview();
});
document.getElementById('slop-preview-reject').addEventListener('click', async () => {
  if (!previewEntry) return;
  try {
    await fetch('/slop/api/slop/pending/' + encodeURIComponent(previewEntry.name) + '/reject', { method: 'POST' });
  } catch {
    // server unreachable — the next registry snapshot restores the truth
  }
  closePreview();
});

// Destructive delete: confirmed from the preview modal, after seeing the
// slop one last time — no more 2.5s "sure?" window to race.
document.getElementById('slop-preview-delete').addEventListener('click', async () => {
  if (!previewEntry) return;
  try {
    const res = await fetch('/slop/api/slop/delete/' + encodeURIComponent(previewEntry.name), { method: 'POST' });
    const data = await res.json().catch(() => null);
    if (!data?.ok) {
      // Keep the modal open and say why — a silent failure reads as a dead
      // button. The registry snapshot will restore the truth on fix.
      document.getElementById('slop-preview-title').textContent =
        `Delete failed — ${data?.error ?? 'unknown error'} (close and retry)`;
      return;
    }
  } catch {
    // server unreachable — the next registry snapshot restores the truth
  }
  closePreview();
});

// --- slop trigger settings (ticket #3) ----------------------------------------
// The preview modal is the per-slop settings screen: below the stage, every
// trigger that can make this slop play — Twitch events and chat lines with
// detailed string matching. Saves go through the server's validator; the
// test-chat input doubles as a live matcher test.

const SPT_MODES = [
  ['contains', 'contains'],
  ['exact', 'exact line'],
  ['starts', 'starts with'],
  ['ends', 'ends with'],
  ['word', 'whole word'],
  ['wildcard', 'wildcard (pog*)'],
  ['regex', 'regex'],
];
const SPT_WHO = [['anyone', 'anyone'], ['mods', 'mods+streamer'], ['broadcaster', 'streamer only']];

// Is this trigger type in the registry allow-list? (Mirrors the server's
// gate — used only to badge creator proposals the server is holding back.)
const sptAllowed = (type) =>
  (Array.isArray(lastSlopConfig?.allowedTriggers) && lastSlopConfig.allowedTriggers.length
    ? lastSlopConfig.allowedTriggers
    : ['chat', 'firstchat']
  ).includes(type);

let sptEntry = null; // the slop whose triggers are being edited
let sptList = []; // editable copies of its triggers
let sptDirty = false;
let sptMatchedFlash = new Set(); // trigger ids the last test line matched

const sptDefault = (type) => {
  const base = { id: 't' + Math.random().toString(36).slice(2, 8), type, enabled: true, source: 'streamer', dispatch: 'queue', bypassCooldown: false };
  if (type === 'chat') return { ...base, mode: 'contains', patterns: ['pog'], caseSensitive: false, who: 'anyone' };
  if (type === 'firstchat') return { ...base, mode: 'contains', patterns: [], caseSensitive: false, who: 'anyone' };
  if (type === 'raid') return { ...base, minViewers: 0 };
  if (type === 'cheer') return { ...base, minBits: 0 };
  if (type === 'sub') return { ...base, tier: 0 };
  if (type === 'resub') return { ...base, minMonths: 0 };
  if (type === 'redemption') return { ...base, rewardId: null };
  return base; // follow, firstfollow — no extra criteria
};

function sptField(label, input) {
  const wrap = document.createElement('label');
  wrap.className = 'spt-field';
  const k = document.createElement('span');
  k.textContent = label;
  wrap.append(k, input);
  return wrap;
}

function sptRow(trigger) {
  const row = document.createElement('div');
  const isCreator = trigger.source === 'creator';
  const isHeld = Boolean(trigger.held) || (isCreator && !sptAllowed(trigger.type));
  row.className = 'spt-row' + (trigger.enabled ? '' : ' off') + (isCreator ? ' creator' : '');
  row.dataset.id = trigger.id;

  const enabled = document.createElement('input');
  enabled.type = 'checkbox';
  enabled.checked = trigger.enabled !== false;
  enabled.title = 'Active — uncheck to keep it configured but inert';
  enabled.addEventListener('change', () => { trigger.enabled = enabled.checked; sptDirty = true; sptPaint(row, trigger); });

  const typeLabel = document.createElement('span');
  typeLabel.className = 'spt-type';
  typeLabel.textContent = TRIGGER_TYPE_LABELS[trigger.type] ?? trigger.type;

  // Creator-proposed triggers carry a badge; held ones say why they're inert.
  const badge = document.createElement('span');
  badge.className = 'spt-badge';
  if (isHeld) {
    badge.textContent = 'held';
    badge.classList.add('held');
    badge.title = 'This type is outside the registry allow-list (⚙ settings) — creator proposals stay inactive until you allow the type or edit + save this trigger yourself';
  } else if (isCreator) {
    badge.textContent = 'proposed';
    badge.title = 'Proposed by the creator in their prompt — edit + save to make it yours';
  }

  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'spt-remove';
  remove.textContent = '✕';
  remove.title = 'Remove this trigger';
  remove.addEventListener('click', () => {
    sptList = sptList.filter((t) => t !== trigger);
    sptDirty = true;
    row.remove();
    sptPaintEmpty();
  });

  row.append(enabled, typeLabel);
  if (badge.textContent) row.append(badge);

  const criteria = document.createElement('span');
  criteria.className = 'spt-criteria';

  if (trigger.type === 'chat' || trigger.type === 'firstchat') {
    const mode = document.createElement('select');
    for (const [value, label] of SPT_MODES) {
      const opt = document.createElement('option');
      opt.value = value;
      opt.textContent = label;
      mode.append(opt);
    }
    mode.value = trigger.mode;
    mode.title = 'How the pattern matches the chat line' + (trigger.type === 'firstchat' ? ' (first-ever message only)' : '');
    mode.addEventListener('change', () => { trigger.mode = mode.value; sptDirty = true; });

    const patterns = document.createElement('input');
    patterns.type = 'text';
    patterns.className = 'spt-patterns';
    patterns.value = (trigger.patterns ?? []).join(', ');
    patterns.spellcheck = false;
    patterns.placeholder = trigger.type === 'firstchat'
      ? 'pattern (optional — blank = any first line)'
      : 'pattern (comma-separated — matches any)';
    patterns.addEventListener('input', () => {
      trigger.patterns = patterns.value.split(',').map((p) => p.trim()).filter(Boolean);
      sptDirty = true;
    });

    const caseSensitive = document.createElement('input');
    caseSensitive.type = 'checkbox';
    caseSensitive.checked = Boolean(trigger.caseSensitive);
    caseSensitive.addEventListener('change', () => { trigger.caseSensitive = caseSensitive.checked; sptDirty = true; });

    const who = document.createElement('select');
    for (const [value, label] of SPT_WHO) {
      const opt = document.createElement('option');
      opt.value = value;
      opt.textContent = label;
      who.append(opt);
    }
    who.value = trigger.who ?? 'anyone';
    who.title = 'Whose chat lines may fire this trigger';
    who.addEventListener('change', () => { trigger.who = who.value; sptDirty = true; });

    criteria.append(
      sptField('match', mode),
      sptField('patterns', patterns),
      sptField('case', caseSensitive),
      sptField('who', who),
    );
  } else if (trigger.type === 'raid') {
    criteria.append(sptField('min viewers', sptNumber(trigger, 'minViewers', '0 = any raid')));
  } else if (trigger.type === 'cheer') {
    criteria.append(sptField('min bits', sptNumber(trigger, 'minBits', '0 = any cheer')));
  } else if (trigger.type === 'resub') {
    criteria.append(sptField('min months', sptNumber(trigger, 'minMonths', '0 = any resub')));
  } else if (trigger.type === 'sub') {
    const tier = document.createElement('select');
    for (const [value, label] of [[0, 'any tier'], [1, 'T1+'], [2, 'T2+'], [3, 'T3']]) {
      const opt = document.createElement('option');
      opt.value = String(value);
      opt.textContent = label;
      tier.append(opt);
    }
    tier.value = String(trigger.tier ?? 0);
    tier.addEventListener('change', () => { trigger.tier = Number(tier.value); sptDirty = true; });
    criteria.append(sptField('tier', tier));
  } else if (trigger.type === 'redemption') {
    const rewardId = document.createElement('input');
    rewardId.type = 'text';
    rewardId.value = trigger.rewardId ?? '';
    rewardId.spellcheck = false;
    rewardId.placeholder = 'reward ID — blank = any reward';
    rewardId.addEventListener('input', () => { trigger.rewardId = rewardId.value.trim() || null; sptDirty = true; });
    criteria.append(sptField('reward', rewardId));
  }

  // Dispatch behavior (streamer-only): queue like !slop, or interrupt —
  // stop the current slop, play now, requeue the interrupt in front.
  // A creator row keeps the default until the streamer saves it as theirs.
  const dispatch = document.createElement('select');
  for (const [value, label] of [['queue', 'queued'], ['immediate', 'immediate']]) {
    const opt = document.createElement('option');
    opt.value = value;
    opt.textContent = label;
    dispatch.append(opt);
  }
  dispatch.value = trigger.dispatch === 'immediate' ? 'immediate' : 'queue';
  dispatch.disabled = isCreator;
  dispatch.title = isCreator
    ? 'Creator proposals always queue — save the trigger as yours to change this'
    : 'queued: lines up like !slop · immediate: stops the current slop, plays now, puts the interrupt back in front of the queue';
  dispatch.addEventListener('change', () => { trigger.dispatch = dispatch.value; sptDirty = true; });
  criteria.append(sptField('play', dispatch));

  // Per-slop cooldown (#5): while cooling down, triggers can't queue this
  // slop — this trigger is the exception.
  const bypass = document.createElement('input');
  bypass.type = 'checkbox';
  bypass.checked = Boolean(trigger.bypassCooldown);
  bypass.disabled = isCreator;
  bypass.title = 'Bypass the cool down — this trigger can still fire the slop while it\'s cooling down. The window itself arms when the slop plays via a (non-bypassing) trigger';
  if (isCreator) bypass.checked = false;
  criteria.append(sptField('bypass cool down', bypass));

  row.append(criteria, remove);
  return row;
}

function sptNumber(trigger, key, title) {
  const input = document.createElement('input');
  input.type = 'number';
  input.min = '0';
  input.value = String(trigger[key] ?? 0);
  input.title = title;
  input.addEventListener('input', () => { trigger[key] = Math.max(0, Math.round(Number(input.value) || 0)); sptDirty = true; });
  return input;
}

// Flash the rows a test line would fire — the server-side matcher is the
// source of truth, so what lights up is what a real event would hit.
function sptPaint(row, trigger) {
  row.classList.toggle('off', trigger.enabled === false);
  row.classList.toggle('hit', sptMatchedFlash.has(trigger.id));
}

function sptPaintEmpty() {
  const list = document.getElementById('spt-list');
  const empty = list.querySelector('.empty');
  if (sptList.length === 0 && !empty) {
    const p = document.createElement('p');
    p.className = 'empty';
    p.textContent = 'no triggers — this slop only plays on demand';
    list.append(p);
  } else if (sptList.length > 0 && empty) {
    empty.remove();
  }
}

// The cam editor lives ONLY in the ⚙ settings modal (with the live Twitch
// stream embedded beneath the placement overlay) — one global placement,
// one place to edit it.

function renderTriggersPane(entry) {
  sptEntry = entry;
  sptList = (entry.triggers ?? []).map((t) => ({ ...t, patterns: [...(t.patterns ?? [])] }));
  sptDirty = false;
  sptMatchedFlash = new Set();
  const list = document.getElementById('spt-list');
  list.replaceChildren(...sptList.map(sptRow));
  sptPaintEmpty();
  // The !slop trigger — an explicit boolean row, on by default for everything.
  const slopCommandBox = document.getElementById('spt-slopcommand');
  slopCommandBox.checked = entry.slopCommand !== false;
  document.getElementById('spt-slopcommand-row').classList.toggle('off', slopCommandBox.checked === false);
  // The per-slop cooldown duration in SECONDS (0 = off) — the window itself
  // arms on trigger plays, this only sets how long it lasts.
  document.getElementById('spt-cooldown').value = String(entry.cooldownSeconds ?? 0);
  document.getElementById('spt-add-type').value = '';
}

document.getElementById('spt-add-type').addEventListener('change', (event) => {
  const type = event.currentTarget.value;
  if (!type || !sptEntry) return;
  sptList.push(sptDefault(type));
  sptDirty = true;
  const list = document.getElementById('spt-list');
  list.replaceChildren(...sptList.map(sptRow));
  sptPaintEmpty();
  event.currentTarget.value = '';
  event.currentTarget.focus();
});

document.getElementById('spt-slopcommand').addEventListener('change', (event) => {
  sptDirty = true;
  document.getElementById('spt-slopcommand-row').classList.toggle('off', !event.currentTarget.checked);
});

// --- streamer cam placement (ctx.streamer) ------------------------------------
// A 16:9 stand-in of the slop canvas with a draggable/resizable bust marking
// where the streamer's camera sits. Registry-global (every slop sees it as
// ctx.streamer), stored normalized 0–1 in slop.json via /slop/api/slop/config.
// Two views of the same value: the ⚙ settings editor (with the live Twitch
// stream embedded beneath it) and a light overlay drawn directly on the
// per-slop preview stage.

const DEFAULT_CAM = { x: 0.03, y: 0.55, w: 0.24, h: 0.42 };
let camRect = null;

const CAM_VIEWS = () => ([
  { box: document.getElementById('spt-cam-box'), frame: document.getElementById('spt-cam-frame') },
]);

function paintCam() {
  for (const { box, frame } of CAM_VIEWS()) {
    if (!box || !camRect) continue;
    if (frame === box) {
      // the preview overlay IS the frame — it's positioned over the stage
      continue;
    }
    box.style.left = (camRect.x * 100).toFixed(2) + '%';
    box.style.top = (camRect.y * 100).toFixed(2) + '%';
    box.style.width = (camRect.w * 100).toFixed(2) + '%';
    box.style.height = (camRect.h * 100).toFixed(2) + '%';
  }
  const hint = document.getElementById('spt-cam-hint');
  if (hint && camRect) {
    hint.textContent =
      `cam at x ${(camRect.x * 100).toFixed(0)}% · y ${(camRect.y * 100).toFixed(0)}% · ${(camRect.w * 100).toFixed(0)}% × ${(camRect.h * 100).toFixed(0)}% of the canvas — slops read it as ctx.streamer`;
  }
}

function camEditorDrag(kind, frameEl) {
  // kind: 'move' | 'resize' — pointer-drag against the given 16:9 frame.
  return (event) => {
    event.preventDefault();
    const frameRect = frameEl.getBoundingClientRect();
    const startX = event.clientX;
    const startY = event.clientY;
    const start = { ...camRect };
    const onMove = (move) => {
      const dx = (move.clientX - startX) / frameRect.width;
      const dy = (move.clientY - startY) / frameRect.height;
      if (kind === 'move') {
        camRect.x = Math.min(1 - start.w, Math.max(0, start.x + dx));
        camRect.y = Math.min(1 - start.h, Math.max(0, start.y + dy));
      } else {
        camRect.w = Math.min(1 - camRect.x, Math.max(0.05, start.w + dx));
        camRect.h = Math.min(1 - camRect.y, Math.max(0.05, start.h + dy));
      }
      paintCam();
    };
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };
}

// The cam editor lives ONLY in the ⚙ settings modal (with the live Twitch
// stream embedded beneath the placement overlay) — one global placement,
// one place to edit it.

function initCamEditor() {
  const box = document.getElementById('spt-cam-box');
  const grip = document.getElementById('spt-cam-grip');
  const frame = document.getElementById('spt-cam-frame');
  box.addEventListener('pointerdown', camEditorDrag('move', frame));
  grip.addEventListener('pointerdown', (event) => {
    event.stopPropagation(); // the box's move-drag must not also start
    camEditorDrag('resize', frame)(event);
  });
  // Saving happens with the dialog's Save (the form submit carries
  // streamerCam) — and from a slop's properties screen, its save button.
}

// The streamer cam saves with the ⚙ settings dialog's Save (the form submit
// carries streamerCam when the editor holds a placement).

initCamEditor();

document.getElementById('spt-save').addEventListener('click', async () => {
  if (!sptEntry) return;
  const button = document.getElementById('spt-save');
  try {
    const res = await fetch('/slop/api/slop/triggers/' + encodeURIComponent(sptEntry.name), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        triggers: sptList,
        cooldownSeconds: Math.max(0, Math.round(Number(document.getElementById('spt-cooldown').value) || 0)),
        slopCommand: document.getElementById('spt-slopcommand').checked,
      }),
    });
    const data = await res.json().catch(() => null);
    if (!data?.ok) {
      button.textContent = '✕ ' + (data?.error ?? 'failed');
      setTimeout(() => { button.textContent = 'save'; }, 2500);
      return;
    }
    sptList = data.triggers;
    sptEntry.triggers = data.triggers;
    if (data.cooldownSeconds !== undefined) sptEntry.cooldownSeconds = data.cooldownSeconds;
    if (data.slopCommand !== undefined) sptEntry.slopCommand = data.slopCommand;
    document.getElementById('spt-slopcommand').checked = data.slopCommand !== false;
    document.getElementById('spt-slopcommand-row').classList.toggle('off', data.slopCommand === false);
    sptDirty = false;
    document.getElementById('spt-list').replaceChildren(...sptList.map(sptRow));
    sptPaintEmpty();
    button.textContent = 'saved ✓';
    setTimeout(() => { button.textContent = 'save'; }, 1500);
  } catch {
    button.textContent = '✕ unreachable';
    setTimeout(() => { button.textContent = 'save'; }, 2500);
  }
});

// The preview test-chat line is ALSO a trigger matcher test — one POST and
// the chat rows that would fire light up.
async function sptTestLine(text) {
  if (!sptEntry) return;
  sptMatchedFlash = new Set();
  try {
    const res = await fetch('/slop/api/slop/triggers/' + encodeURIComponent(sptEntry.name) + '/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text }),
    });
    const data = await res.json().catch(() => null);
    if (data?.ok) sptMatchedFlash = new Set(data.matched ?? []);
  } catch {
    // offline — no flash, the local preview still sends to onChat
  }
  document.querySelectorAll('#spt-list .spt-row').forEach((row) => {
    row.classList.toggle('hit', sptMatchedFlash.has(row.dataset.id));
    if (sptMatchedFlash.has(row.dataset.id)) {
      setTimeout(() => row.classList.remove('hit'), 2500);
    }
  });
}

document.getElementById('slop-preview-chat-input').addEventListener('keydown', (event) => {
  if (event.key !== 'Enter') return;
  event.preventDefault();
  const input = event.currentTarget;
  const text = input.value.trim();
  if (!text) return;
  previewMount?.sendChat?.({ user: 'deck-preview', text });
  sptTestLine(text);
  input.value = '';
});

// --- slop moderation tier helpers ------------------------------------------------

const MODERATION_LABELS = {
  kid: 'Kid Friendly',
  teen: 'Teen / Young Adult',
  adult: 'Adult',
  anything: 'Anything Goes',
};

const MODERATION_SHORT = {
  kid: 'kid',
  teen: 'teen',
  adult: 'adult',
  anything: 'any',
};

// Plain indicator chip — the tier this slop was built under. The encircled
// italic "i" button elsewhere in the row opens the about dialog.
function tierBadge(entry) {
  const known = Boolean(MODERATION_LABELS[entry.moderationLevel]);
  const span = document.createElement('span');
  span.className = 'tierchip tier-' + (known ? entry.moderationLevel : 'unknown');
  span.textContent = known ? MODERATION_SHORT[entry.moderationLevel] : '?';
  span.title = known
    ? 'Built under moderation: ' + MODERATION_LABELS[entry.moderationLevel]
    : 'Built before moderation tiers were recorded';
  return span;
}

function renderModStatus() {
  const el = document.getElementById('slop-modstatus');
  if (!el) return;
  const level = MODERATION_LABELS[lastSlopConfig?.moderationLevel] ? lastSlopConfig.moderationLevel : 'teen';
  el.className = 'tierchip tier-' + level;
  el.id = 'slop-modstatus';
  el.textContent = 'moderation: ' + MODERATION_LABELS[level];
  el.title = 'Current content moderation tier — change it in ⚙ settings. ' + (MODERATION_NOTES[level] ?? '');
}

function openSlopInfo(entry) {
  const tier = MODERATION_LABELS[entry.moderationLevel] ? entry.moderationLevel : null;
  const rows = [
    ['Title', entry.title],
    ['Creator', entry.author],
    ['Prompt', entry.prompt ?? '(not recorded)'],
    ['Built', entry.createdAt ?? 'unknown date'],
    ['Moderation', tier ? MODERATION_LABELS[tier] : 'not recorded'],
    ['!slop handle', '!slop ' + (entry.short ?? entry.name.replace(/\.js$/, ''))],
    ['File', entry.name],
    ['Status', entry.pending ? 'pending approval' : entry.suspended ? 'suspended' : 'live'],
  ];
  const body = document.getElementById('slop-info-body');
  body.replaceChildren(...rows.map(([key, value]) => {
    const row = document.createElement('div');
    row.className = 'info-row';
    const k = document.createElement('span');
    k.className = 'k';
    k.textContent = key;
    const v = document.createElement('span');
    v.className = 'v';
    v.textContent = String(value ?? '');
    row.append(k, v);
    return row;
  }));
  document.getElementById('slop-info-modal').hidden = false;
}

const infoModal = document.getElementById('slop-info-modal');
const closeInfo = () => {
  infoModal.hidden = true;
};
document.getElementById('slop-info-close').addEventListener('click', closeInfo);
infoModal.addEventListener('click', (event) => {
  if (event.target === infoModal) closeInfo();
});

function renderSlopRegistry(registered, cooldownUntil = {}) {
  lastRegistry = registered;
  // The live cooldown windows (epoch ms per name), rebuilt from server truth
  // each pass — a cleared cooldown must vanish, not linger locally.
  const now = Date.now();
  const fresh = {};
  for (const [name, until] of Object.entries(cooldownUntil ?? {})) {
    if (Number(until) > now) fresh[name] = Number(until);
  }
  for (const entry of registered) {
    if (Number(entry.cooldownUntil) > now) fresh[entry.name] = Number(entry.cooldownUntil);
  }
  lastCooldownUntil = fresh;
  renderModStatus();
  // The registry search box filters live — simple substring match on the
  // short handle, filename, and title. Pending approvals sort to the top,
  // highlighted — the operator's verdict queue. Icon buttons keep the rows
  // compact.
  const query = slopSearchQuery.trim().toLowerCase();
  const matchesQuery = (entry) =>
    [entry.short, entry.name.replace(/\.js$/, ''), entry.title]
      .some((field) => String(field ?? '').toLowerCase().includes(query));
  const sorted = [
    ...registered.filter((entry) => entry.pending),
    ...registered.filter((entry) => !entry.pending),
  ].filter((entry) => !query || matchesQuery(entry));
  // The 1s poll re-renders on every tick — rebuilding the whole list each
  // time eats clicks that land mid-press (the "click it twice" bug) and
  // resets the destructive "sure?" arm. Identical content = no rebuild;
  // job timers keep ticking via their own local interval.
  const sig = `${query}|${sorted.map((e) => `${e.name}|${e.title ?? ''}|${e.pending ? 1 : 0}|${e.suspended ? 1 : 0}|${(e.triggers ?? []).filter((t) => t.enabled).length}|${Number(e.cooldownUntil) > Date.now() ? 1 : 0}`).join(';')}`;
  if (sig === lastRegistrySig) return;
  lastRegistrySig = sig;
  if (sorted.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'empty';
    empty.textContent = query ? `no slops matching "${slopSearchQuery.trim()}"` : 'no slops on file yet';
    slopRegistry.replaceChildren(empty);
    return;
  }
  slopRegistry.replaceChildren(
    ...sorted.map((entry) => {
      const row = document.createElement('div');
      row.className = 'slop-reg';
      if (entry.pending) row.classList.add('pending');
      if (entry.suspended) row.classList.add('suspended');

      const iconButton = (glyph, title, cls, onClick, { disabled = false, destructive = false } = {}) => {
        const button = document.createElement('button');
        button.className = 'iconbtn ' + cls;
        button.textContent = glyph;
        button.title = title;
        button.disabled = disabled;
        if (!onClick) return button;
        button.addEventListener('click', () => {
          // Destructive actions arm on the first click — a second click
          // within 2.5s executes. Mis-clicks recover by doing nothing.
          if (destructive && !button.dataset.armed) {
            button.dataset.armed = '1';
            button.dataset.prev = button.textContent;
            button.textContent = 'sure?';
            setTimeout(() => {
              button.dataset.armed = '';
              button.textContent = glyph;
            }, 2500);
            return;
          }
          button.dataset.armed = '';
          onClick(button);
        });
        return button;
      };
      const post = async (url, body) => {
        try {
          return await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body ?? {}),
          });
        } catch {
          // server unreachable — the next snapshot restores the truth
          return { ok: false };
        }
      };

      const title = document.createElement('span');
      title.className = 'reg-title';
      title.textContent = entry.short ?? entry.name.replace(/\.js$/, '');
      title.title = entry.title + ' - by ' + entry.author;

      // Active trigger count — click through to the settings screen to edit.
      const activeTriggers = (entry.triggers ?? []).filter((t) => t.enabled).length;
      let zap = null;
      if (activeTriggers > 0) {
        zap = document.createElement('span');
        zap.className = 'zapchip';
        zap.textContent = `⚡${activeTriggers}`;
        zap.title = `${activeTriggers} active trigger${activeTriggers === 1 ? '' : 's'} — properties (📋) to edit`;
      }

      // The tier this slop was built under — doubles as the info button.
      const tier = tierBadge(entry);

      if (entry.pending) {
        const chip = document.createElement('span');
        chip.className = 'chip pending';
        chip.textContent = 'pending approval';
        row.append(tier, chip, title, ...(zap ? [zap] : []),
          iconButton('📋', 'Properties — preview, triggers, and settings before deciding', 'dopreview', () => openPreview(entry)),
          iconButton('✓', 'Preview it, then approve from the modal', 'approve', () => openPreview(entry, true)),
          iconButton('✕', 'Reject — deletes it and refunds the redeem', 'reject', () => post('/slop/api/slop/pending/' + encodeURIComponent(entry.name) + '/reject'), { destructive: true }),
          iconButton('i', 'About this slop — creator, prompt, moderation tier', 'infob', () => openSlopInfo(entry)),
        );
        return row;
      }

      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.checked = !entry.suspended;
      checkbox.title = entry.suspended ? 'Suspended — check to re-enable' : 'Enabled — uncheck to suspend';
      checkbox.addEventListener('change', () => post('/slop/api/slop/suspend/' + encodeURIComponent(entry.name), { suspended: !checkbox.checked }));

      row.append(
        tier,
        checkbox,
        title,
        ...(zap ? [zap] : []),
        cooldownChip(entry),
        iconButton('⧉', 'Copy the !slop handle for chat', 'copy', async () => {
          const handle = '!slop ' + (entry.short ?? entry.name.replace(/\.js$/, ''));
          try {
            await navigator.clipboard.writeText(handle);
          } catch {
            const temp = document.createElement('textarea');
            temp.value = handle;
            document.body.append(temp);
            temp.select();
            document.execCommand('copy');
            temp.remove();
          }
        }),
        iconButton('▶', 'Play it on the overlay now', 'doplay', async (btn) => {
          const res = await post('/slop/api/slop/play/' + encodeURIComponent(entry.name), { chained: false });
          const data = res ? await res.json().catch(() => null) : null;
          if (!data?.ok) {
            // Surface the refusal instead of a silent no-op — a button that
            // does nothing is indistinguishable from a broken one.
            const prev = btn.textContent;
            btn.textContent = '✕';
            btn.title = 'play refused: ' + (data?.error ?? 'server unreachable');
            setTimeout(() => {
              btn.textContent = prev;
              btn.title = 'Play it on the overlay now';
            }, 2500);
          }
        }, entry.suspended),
        iconButton('📋', 'Properties — preview, triggers, and settings', 'dopreview', () => openPreview(entry)),
        iconButton('✕', 'Preview it, then confirm the delete', 'reject', () => openPreview(entry, false, true)),
        iconButton('i', 'About this slop — creator, prompt, moderation tier', 'infob', () => openSlopInfo(entry)),
      );
      return row;
    }),
  );
}
function cooldownChip(entry) {
  const wrap = document.createDocumentFragment();
  const until = lastCooldownUntil[entry.name] ?? 0;
  const remaining = until - Date.now();
  if (remaining <= 0) return wrap;
  const seconds = Math.ceil(remaining / 1000);
  const chip = document.createElement('span');
  chip.className = 'cooldown';
  chip.dataset.until = String(until);
  chip.textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  chip.title = 'Cooling down — triggers can\'t fire this slop until the timer ends (manual plays always work)';
  const clear = document.createElement('button');
  clear.className = 'cdclear';
  clear.textContent = 'clear';
  clear.title = 'Reset the cool down now';
  clear.addEventListener('click', async () => {
    try {
      await fetch(`/slop/api/slop/cooldown/${encodeURIComponent(entry.name)}/clear`, { method: 'POST' });
    } catch {
      // server unreachable — the next tick restores the truth
    }
  });
  wrap.append(chip, clear);
  return wrap;
}

function playButton(entry) {
  const play = document.createElement('button');
  play.textContent = 'Play';
  play.disabled = Boolean(entry.suspended);
  play.addEventListener('click', async () => {
    play.disabled = true;
    try {
      await fetch(`/slop/api/slop/play/${encodeURIComponent(entry.name)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chained: false }),
      });
      // refusals (cooldowns, repeats, suspends) surface as overlay toasts
    } catch {
      // server unreachable — the play simply didn't happen
    } finally {
      setTimeout(() => { play.disabled = false; }, 2000);
    }
  });
  return play;
}

// The cooldown chips tick down locally, one second at a time — a full
// registry re-render per second would eat clicks mid-press, so only the
// countdown text updates; expired chips remove themselves.
setInterval(() => {
  const now = Date.now();
  for (const chip of document.querySelectorAll('#slop-registry .cooldown')) {
    const until = Number(chip.dataset.until ?? 0);
    const remaining = until - now;
    if (remaining <= 0) {
      chip.nextElementSibling?.remove(); // its clear button
      chip.remove();
      continue;
    }
    const seconds = Math.ceil(remaining / 1000);
    chip.textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  }
}, 1000);


// --- registry search -----------------------------------------------------------
// Live substring filter over the slop list; the query survives snapshot
// re-renders because renderSlopRegistry reads it each pass.

const searchInput = document.getElementById('slop-search');
const searchClear = document.getElementById('slop-search-clear');

document.getElementById('slop-search-form').addEventListener('submit', (event) => event.preventDefault());

searchInput.addEventListener('input', () => {
  slopSearchQuery = searchInput.value;
  searchClear.hidden = !slopSearchQuery;
  renderSlopRegistry(lastRegistry);
});
searchClear.addEventListener('click', () => {
  searchInput.value = '';
  slopSearchQuery = '';
  searchClear.hidden = true;
  renderSlopRegistry(lastRegistry);
  searchInput.focus();
});

// --- add-a-slop modal ------------------------------------------------------------

const addModal = document.getElementById('slop-add-modal');
const addInput = document.getElementById('slop-add-input');
const addError = document.getElementById('slop-add-error');
const addModelSelect = document.getElementById('slop-add-model');

const openAdd = async () => {
  addError.hidden = true;
  const config = lastSlopConfig ?? {};
  const defaultLabel = config.model ? `Default (${config.model})` : 'Default (agent default)';
  await populateModelSelect(addModelSelect, '', defaultLabel);
  addModal.hidden = false;
  addInput.focus();
};
const closeAdd = () => {
  addModal.hidden = true;
};

document.getElementById('slop-add').addEventListener('click', () => { openAdd(); });
document.getElementById('slop-add-close').addEventListener('click', closeAdd);
document.getElementById('slop-add-cancel').addEventListener('click', closeAdd);
addModal.addEventListener('click', (event) => {
  if (event.target === addModal) closeAdd();
});

document.getElementById('slop-add-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  try {
    const res = await fetch('/slop/api/slop/build', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: addInput.value, model: addModelSelect.value }),
    });
    const data = await res.json();
    if (data.ok) {
      addInput.value = '';
      closeAdd(); // the queue monitor shows the BUILDING card
    } else {
      addError.textContent = data.error ?? 'the build did not start — give it a prompt';
      addError.hidden = false;
    }
  } catch {
    addError.textContent = 'server unreachable — try again';
    addError.hidden = false;
  }
});

// --- slop settings modal -----------------------------------------------------------

// Fast models that fit slop builds — flagship reasoning models (opus-5,
// gemini-4-argon, gpt-5.5-pro…) deliberate for 8-20 minutes per build.
// Catalog ids on the opencode gateway (without the "opencode/" prefix).
const FAST_BUILD_MODELS = new Set([
  'gemini-4-neon',
  'gemini-3.8-flash',
  'gemini-3.7-flash',
  'claude-haiku-4-5',
  'grok-4.7',
  'grok-build-0.1',
  'qwen3.8-flash',
  'qwen3.6-plus',
  'qwen3.5-plus',
  'gpt-5.4-mini',
  'prism',
  'prism-core',
  'prism-flash',
  'deepseek-v4.1-flash-1017',
  'deepseek-v4.1-flash',
]);

const MODERATION_NOTES = {  kid: 'Child-safe themes only. Very conservative in judgment.',
  teen: 'Somewhat crude humor (fart/poop jokes, light innuendo). No drugs or alcohol.',
  adult: 'Lewd content, sexual references, and drug or alcohol references allowed.',
  anything: 'Anything the model allows — except genitals or explicit nudity (Twitch TOS). No protected-group hate speech or self-harm/harm-to-others.',
};

const settingsModal = document.getElementById('slop-settings-modal');
const redeemTitleInput = document.getElementById('slop-redeem-title-input');
const modelSelect = document.getElementById('slop-model-select');
const moderationSelect = document.getElementById('slop-moderation');
const moderationNote = document.getElementById('slop-moderation-note');
const customRulesInput = document.getElementById('slop-custom-rules');
const agentInstructionsInput = document.getElementById('slop-agent-instructions');
const allowedTriggersBox = document.getElementById('slop-allowed-triggers');
const floodCapInput = document.getElementById('slop-floodcap');
const authUserEl = document.getElementById('auth-user');
const authLoginEl = document.getElementById('auth-login');
const authLogoutEl = document.getElementById('auth-logout');

// Auth bar (ticket 02): who am I, or the Twitch-login door. A 401 on any
// management call means the session expired — flip the bar back to login.
const renderAuth = (me) => {
  const user = me?.user;
  authUserEl.hidden = !user;
  authLoginEl.hidden = Boolean(user);
  authLogoutEl.hidden = !user;
  if (user) {
    authUserEl.textContent = `${user.display} (${user.role})`;
  }
};
fetch('/api/auth/me').then((r) => r.json()).then(renderAuth).catch(() => renderAuth(null));

const bareFetch = window.fetch.bind(window);
window.fetch = async (...args) => {
  const res = await bareFetch(...args);
  if (res.status === 401) renderAuth(null);
  return res;
};

// The registry allow-list (issue #4): which trigger types may fire slops.
// Everything the streamer sets themselves is exempt — this gates
// creator-proposed triggers only.
const TRIGGER_TYPE_LABELS = {
  chat: 'chat line',
  firstchat: 'first-time chatter',
  follow: 'follow',
  firstfollow: 'first-time follower',
  sub: 'new sub',
  resub: 'resub',
  subgift: 'sub gift',
  raid: 'raid',
  cheer: 'cheer',
  redemption: 'channel points redeem',
};
const TRIGGER_TYPE_NOTES = {
  chat: 'any chat line matching string criteria',
  firstchat: 'a viewer’s first-ever message',
  follow: 'new follower',
  firstfollow: 'a viewer’s first-ever follow',
  sub: 'new sub — optional tier floor',
  resub: 'resub — optional months floor',
  subgift: 'sub gift or mystery gift',
  raid: 'incoming raid — optional viewer floor',
  cheer: 'bits cheer — optional amount floor',
  redemption: 'any or one specific reward',
};
const DEFAULT_ALLOWED_TRIGGERS = ['chat', 'firstchat'];

let allowedList = [...DEFAULT_ALLOWED_TRIGGERS];

function renderAllowedTriggers(allowed) {
  allowedList = Array.isArray(allowed) && allowed.length ? [...allowed] : [...DEFAULT_ALLOWED_TRIGGERS];
  paintAllowedTriggers();
}

// Compact allow-list: one row per ALLOWED type (✕ deletes the row — the
// type stops firing), plus an "add event…" dropdown that adds a row for a
// currently-disabled type. No checkboxes — the row's presence IS the toggle.
function paintAllowedTriggers() {
  allowedTriggersBox.replaceChildren();
  for (const type of allowedList) {
    const row = document.createElement('div');
    row.className = 'at-row';
    const text = document.createElement('span');
    text.className = 'at-name';
    text.textContent = TRIGGER_TYPE_LABELS[type] ?? type;
    const desc = document.createElement('span');
    desc.className = 'at-desc';
    desc.textContent = TRIGGER_TYPE_NOTES[type] ?? '';
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'at-remove';
    remove.textContent = '✕';
    remove.title = 'Disallow — creator proposals of this type will be held';
    remove.addEventListener('click', () => {
      allowedList = allowedList.filter((t) => t !== type);
      paintAllowedTriggers();
    });
    row.append(text, desc, remove);
    allowedTriggersBox.append(row);
  }
  if (allowedList.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'at-empty';
    empty.textContent = 'no trigger types allowed — only triggers you set on a slop yourself can fire';
    allowedTriggersBox.append(empty);
  }
  const disabled = Object.keys(TRIGGER_TYPE_LABELS).filter((type) => !allowedList.includes(type));
  if (disabled.length > 0) {
    const add = document.createElement('select');
    add.className = 'at-add';
    add.append(new Option('add event…', ''));
    for (const type of disabled) add.append(new Option(TRIGGER_TYPE_LABELS[type], type));
    add.addEventListener('change', () => {
      if (!add.value) return;
      allowedList.push(add.value);
      paintAllowedTriggers();
    });
    allowedTriggersBox.append(add);
  }
}

const selectedAllowedTriggers = () => [...allowedList];

const setModerationNote = () => {
  moderationNote.textContent = MODERATION_NOTES[moderationSelect.value] ?? '';
};
moderationSelect.addEventListener('change', setModerationNote);

async function populateModelSelect(select, selected, defaultLabel = 'Agent default') {
  select.replaceChildren(new Option(defaultLabel, ''));
  let models = [];
  try {
    models = await (await fetch('/slop/api/slop/models')).json();
  } catch {
    // gateway unreachable — the default still works
  }
  const seen = new Set();
  const usable = [];
  for (const m of (Array.isArray(models) ? models : [])) {
    if (!FAST_BUILD_MODELS.has(m.id)) continue;
    const key = `${m.providerID ?? 'opencode'}/${m.id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    usable.push(m);
  }
  usable.sort((a, b) => (a.providerName || a.providerID || '').localeCompare(b.providerName || b.providerID || '') || (a.name || a.id).localeCompare(b.name || b.id));
  for (const m of usable) {
    const providerID = m.providerID ?? 'opencode';
    const providerLabel = m.providerName && m.providerName !== providerID ? `${m.providerName} (${providerID})` : providerID;
    const option = new Option(`${m.name} — ${providerLabel}`, `${providerID}/${m.id}`);
    option.title = [m.description, `${providerID}/${m.id}`].filter(Boolean).join(' · ');
    select.append(option);
  }
  if (selected) {
    if (![...select.options].some((o) => o.value === selected)) {
      select.append(new Option(selected + ' (saved)', selected));
    }
    select.value = selected;
  } else {
    select.selectedIndex = 0;
  }
}

async function openSettings() {
  const config = lastSlopConfig ?? {};
  moderationSelect.value = MODERATION_NOTES[config.moderationLevel] ? config.moderationLevel : 'teen';
  setModerationNote();
  customRulesInput.value = config.customRules ?? '';
  agentInstructionsInput.value = config.agentInstructions ?? '';
  redeemTitleInput.value = config.redeemTitle ?? '';
  renderAllowedTriggers(config.allowedTriggers);
  floodCapInput.value = String(config.triggerDispatchesPerMinute ?? 6);
  // The streamer cam editor: the live Twitch stream plays beneath the
  // placement overlay (muted; parent= this host, as Twitch requires).
  const embed = document.getElementById('spt-cam-embed');
  const channel = String(config.channel ?? '').trim();
  if (channel) {
    embed.src = `https://player.twitch.tv/?channel=${encodeURIComponent(channel)}&parent=${location.hostname}&muted=true`;
    embed.hidden = false;
  } else {
    embed.removeAttribute('src');
    embed.hidden = true;
  }
  camRect = { ...(config.streamerCam ?? DEFAULT_CAM) };
  paintCam();

  // Build-model picker: the catalog comes from the opencode gateway (cached
  // server-side), filtered to fast models that fit slop builds. Empty = agent's default.
  await populateModelSelect(modelSelect, config.model ?? '', 'Agent default');
  settingsModal.hidden = false;
}

const closeSettings = () => {
  settingsModal.hidden = true;
};

document.getElementById('slop-settings').addEventListener('click', openSettings);
document.getElementById('slop-settings-close').addEventListener('click', closeSettings);
document.getElementById('slop-settings-cancel').addEventListener('click', closeSettings);
settingsModal.addEventListener('click', (event) => {
  if (event.target === settingsModal) closeSettings();
});

document.getElementById('slop-settings-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const body = {
    moderationLevel: moderationSelect.value,
    customRules: customRulesInput.value,
    agentInstructions: agentInstructionsInput.value,
    model: modelSelect.value,
    allowedTriggers: selectedAllowedTriggers(),
    triggerDispatchesPerMinute: Math.max(1, Math.min(30, Math.round(Number(floodCapInput.value) || 6))),
  };
  // The cam editor rides the same Save — but only when it holds a placement
  // (a null would wipe the global).
  if (camRect) body.streamerCam = camRect;
  // Free-form title matching: saving a title clears any pinned rewardId so
  // the title is what actually matches (matches() prefers rewardId when set).
  body.redeemTitle = redeemTitleInput.value;
  body.rewardId = null;
  try {
    await fetch('/slop/api/slop/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch {
    // server unreachable — the next snapshot restores the truth
  }
  closeSettings();
});

// Esc closes whichever modal is open.
window.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  if (!addModal.hidden) closeAdd();
  if (!settingsModal.hidden) closeSettings();
  if (!infoModal.hidden) closeInfo();
  if (!obsSettingsModal.hidden) closeObsSettings();
  if (!cancelModal.hidden) closeCancelConfirm();
});

// One render path for every slop snapshot — the SSE feed delivers it
// instantly, and a 1s poll below guarantees it even if the feed is starved
// (OBS shares one browser process across sources; its per-host connection
// limit can block new SSE connections entirely).
function handleSlopSnapshot(data) {
  sseClientCount = data.sseClients ?? sseClientCount;
  slopQueueState = data.queue ?? [];
  for (const item of slopQueueState) knownSlopIds.add(item.id); // seed before fresh-tracking starts
  renderSlopQueue();
  renderSlopRegistry(data.registered ?? [], data.cooldownUntil ?? {});
  renderSlopCount(data.registered ?? []);
  renderSlopPlays(data.plays);
  if (data.config) lastSlopConfig = data.config;
}

const slopSource = new EventSource('/slop/api/slop/events');
slopSource.addEventListener('state', (event) => {
  handleSlopSnapshot(JSON.parse(event.data));
});
slopSource.addEventListener('plays', (event) => {
  renderSlopPlays(JSON.parse(event.data));
});
slopSource.addEventListener('registry', (event) => {
  const list = JSON.parse(event.data).registered ?? [];
  renderSlopRegistry(list);
  renderSlopCount(list);
});
slopSource.addEventListener('queue', (event) => {
  const previous = new Set(slopQueueState.map((i) => i.id));
  const next = JSON.parse(event.data).queue ?? [];
  const fresh = next.map((i) => i.id).filter((id) => !previous.has(id) && !knownSlopIds.has(id));
  for (const id of fresh) knownSlopIds.add(id);
  slopQueueState = next;
  // A brand-new request opens itself so it gets read immediately.
  if (fresh.length > 0) {
    const newest = next.find((i) => i.id === fresh[fresh.length - 1] && i.status === 'pending');
    if (newest) expandedSlopId = newest.id;
  }
  renderSlopQueue({ freshIds: fresh });
});
// The 1s fallback poll: identical snapshot path, no SSE required.
setInterval(async () => {
  try {
    handleSlopSnapshot(await (await fetch('/slop/api/slop')).json());
  } catch {
    // server hiccup — try again next tick
  }
}, 1000);

slopSource.addEventListener('config', (event) => {
  lastSlopConfig = JSON.parse(event.data).config ?? lastSlopConfig;
});
