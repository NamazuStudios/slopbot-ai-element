// ---------------------------------------------------------------------------
// Shared Twitch chat client: anonymous read-only IRC-over-WebSocket plus
// DOM rendering. Used by the /chat overlay and the /deck chat monitor.
// Renders Twitch-native emotes from the IRC `emotes` tag plus third-party
// emotes (7TV / BTTV / FFZ, fetched from their public no-auth APIs), and
// marks shared-stream messages with a mini channel-avatar badge
// (official-style) using the `source-room-id` tag. Pure parsing helpers
// are exported for testing outside the browser.
// ---------------------------------------------------------------------------

const IRC_WS_URL = 'wss://irc-ws.chat.twitch.tv:443';
const EMOTE_CDN = 'https://static-cdn.jtvnw.net/emoticons/v2';

// --- pure helpers (exported for testing) ------------------------------------

export function clampNumber(raw, fallback, min, max) {
  if (raw === null || raw === '') return fallback;
  const value = Number(raw);
  return Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : fallback;
}

export function unescapeTag(value) {
  return value
    .replace(/\\s/g, ' ')
    .replace(/\\:/g, ';')
    .replace(/\\\\/g, '\\')
    .replace(/\\r/g, '')
    .replace(/\\n/g, '');
}

// Parses one raw IRC line:
// @tags :prefix COMMAND #channel :trailing
// The trailing parameter starts at the first ':' after the command — the
// message text may itself contain further colons.
export function parseIrcMessage(raw) {
  let line = raw;
  const tags = {};

  if (line.startsWith('@')) {
    const space = line.indexOf(' ');
    if (space === -1) return { tags, prefix: '', command: '', channel: '', trailing: '' };
    for (const pair of line.slice(1, space).split(';')) {
      const eq = pair.indexOf('=');
      if (eq === -1) tags[pair] = true;
      else tags[pair.slice(0, eq)] = unescapeTag(pair.slice(eq + 1));
    }
    line = line.slice(space + 1);
  }

  let prefix = '';
  if (line.startsWith(':')) {
    const space = line.indexOf(' ');
    if (space === -1) return { tags, prefix: line.slice(1), command: '', channel: '', trailing: '' };
    prefix = line.slice(1, space);
    line = line.slice(space + 1);
  }

  const space = line.indexOf(' ');
  const command = space === -1 ? line : line.slice(0, space);
  const rest = space === -1 ? '' : line.slice(space + 1);

  const colon = rest.indexOf(':');
  const channel = colon === -1 ? rest.trim() : rest.slice(0, colon).trim();
  const trailing = colon === -1 ? '' : rest.slice(colon + 1);

  return { tags, prefix, command, channel, trailing };
}

// Splits a message into text and emote parts using Twitch's `emotes` tag
// ("id:start-end,start-end/id2:..."). Positions index into the message text.
export function parseEmotes(message, emotesTag) {
  if (!emotesTag) return [{ type: 'text', text: message }];

  const ranges = [];
  for (const group of emotesTag.split('/')) {
    const [id, positions] = group.split(':');
    if (!id || !positions) continue;
    for (const pos of positions.split(',')) {
      const [start, end] = pos.split('-').map(Number);
      if (Number.isFinite(start) && Number.isFinite(end) && start <= end) {
        // The id is kept VERBATIM: native emotes are numeric, but shared
        // streams deliver third-party emotes here as `emotesv2_<uuid>` —
        // and the Twitch CDN serves those as-is. Stripping anything (an
        // old "safety" here) mangles them into broken URLs.
        ranges.push({ id, start, end });
      }
    }
  }
  ranges.sort((a, b) => a.start - b.start);

  const parts = [];
  let cursor = 0;
  for (const range of ranges) {
    if (range.start > cursor) parts.push({ type: 'text', text: message.slice(cursor, range.start) });
    if (range.start >= cursor) {
      parts.push({ type: 'emote', id: range.id, alt: message.slice(range.start, range.end + 1) });
      cursor = range.end + 1;
    }
  }
  if (cursor < message.length) parts.push({ type: 'text', text: message.slice(cursor) });
  return parts;
}

// Splits the native-tag-parsed parts further on third-party emote names
// (7TV / BTTV / FFZ): whole words only — "NotPog" doesn't match. Punctuation
// hugging the word ("Pog!", "(Pog)") is kept around the emote, matching how
// Twitch's positional tag treats native emotes. Native emote parts pass
// through untouched (they're positional, so they can never collide).
export function splitEmoteWords(parts, emotes) {
  if (!emotes || emotes.size === 0) return parts;
  const out = [];
  const pushText = (text) => {
    if (text) out.push({ type: 'text', text });
  };
  for (const part of parts) {
    if (part.type !== 'text') {
      out.push(part);
      continue;
    }
    for (const piece of part.text.split(/(\s+)/)) {
      if (!piece) continue;
      const exact = emotes.get(piece);
      if (exact) {
        out.push({ type: 'emote', url: exact, alt: piece });
        continue;
      }
      const core = piece.replace(/^[.,!?;:()"']+/, '').replace(/[.,!?;:()"']+$/, '');
      const url = core && core !== piece ? emotes.get(core) : undefined;
      if (url) {
        const at = piece.indexOf(core);
        pushText(piece.slice(0, at));
        out.push({ type: 'emote', url, alt: core });
        pushText(piece.slice(at + core.length));
      } else {
        pushText(piece);
      }
    }
  }
  return out;
}

// Twitch computes the `emotes` ranges against the raw CTCP text, so a /me
// message must be parsed BEFORE its \x01ACTION wrapper is stripped — the
// wrapper chars then land in the first/last text parts, and are trimmed
// off here. Plain messages pass through unchanged.
export function trimActionWrapper(parts) {
  const trimmed = parts.map((part) => ({ ...part }));
  if (trimmed.length > 0 && trimmed[0].type === 'text') {
    trimmed[0].text = trimmed[0].text.replace(/^\x01ACTION /, '');
  }
  const last = trimmed[trimmed.length - 1];
  if (last && last.type === 'text') {
    last.text = last.text.replace(/\x01$/, '');
  }
  return trimmed.filter((part) => part.type !== 'text' || part.text !== '');
}

// --- monitor ------------------------------------------------------------------

// Renders live chat into `container` and keeps the connection alive.
// `container` may be null for a headless listener (with `onMessage`) — used
// by scene pages that react to chat commands without rendering chat.
// Returns { dispose() }.
export function createChatMonitor(container, {
  channel = 'savorysailor',
  maxLines = 12,
  ttlMs = 0,
  fadeMs = 0,
  onMessage, // ({ name, color, text, action, source }) per chat message;
             // source = { id } when a shared stream relayed it from the other chat
} = {}) {
  const room = String(channel)
    .toLowerCase()
    .replace(/^#/, '')
    .replace(/[^a-z0-9_]/g, '');

  let closedByUs = false;
  let sweepTimer = null;

  function addLine(node) {
    node.dataset.ts = String(Date.now());
    if (fadeMs > 0) {
      // Gradual age fade: the line dims to nothing over its lifetime (the
      // CSS animation drives opacity), then removes itself.
      node.classList.add('fading');
      node.style.setProperty('--life', `${fadeMs / 1000}s`);
      setTimeout(() => node.remove(), fadeMs + 700);
    }
    container.appendChild(node);
    // Cap the *live* line count. retire() only marks a line for its fade-out
    // and removes it later on a timer, so counting raw children would spin
    // this loop forever as soon as a retired line is still in the DOM.
    const live = Array.from(container.children).filter(
      (child) => !child.classList.contains('out'),
    );
    while (live.length > maxLines) retire(live.shift());
  }

  function retire(node) {
    if (!node || node.classList.contains('out')) return;
    node.classList.add('out');
    setTimeout(() => node.remove(), 450);
  }

  if (container && ttlMs > 0 && fadeMs === 0) { // fade, when set, owns the lifetime
    sweepTimer = setInterval(() => {
      const now = Date.now();
      for (const node of Array.from(container.children)) {
        if (!node.classList.contains('out') && now - Number(node.dataset.ts) > ttlMs) {
          retire(node);
        }
      }
    }, 1000);
  }

  // --- rendering (textContent only — never innerHTML with user data) -----

  function renderMessage({ name, color, parts, action, source }) {
    const line = document.createElement('div');
    line.className = 'line' + (action ? ' action' : '');

    // Shared streams: a mini avatar of the chat the message came from —
    // the official-style source badge. It renders as a plain ring and
    // fills in once the channel's logo resolves, never blocking the
    // message itself.
    if (source) {
      const badge = document.createElement('span');
      badge.className = 'source';
      const avatar = document.createElement('img');
      avatar.className = 'source-avatar';
      avatar.alt = '';
      badge.appendChild(avatar);
      source.ready.then((info) => {
        if (info?.logo) avatar.src = info.logo;
        badge.title = info?.displayName
          ? `from ${info.displayName}'s chat`
          : 'from the other chat in the shared stream';
      });
      line.appendChild(badge);
    }

    const user = document.createElement('span');
    user.className = 'user';
    user.textContent = name;
    user.style.color = color || 'hsl(324 100% 62%)'; // brand pink fallback

    const sep = document.createElement('span');
    sep.className = 'sep';
    sep.textContent = ':';

    const msg = document.createElement('span');
    msg.className = 'msg';
    for (const part of parts) {
      if (part.type === 'text') {
        msg.appendChild(document.createTextNode(part.text));
      } else {
        const img = document.createElement('img');
        img.className = 'emote';
        img.alt = part.alt;
        // Third-party emote parts carry a full url; native ones build the
        // Twitch CDN url from their id.
        img.src = part.url ?? `${EMOTE_CDN}/${part.id}/default/dark/1.0`;
        msg.appendChild(img);
      }
    }

    line.append(user, sep, msg);
    return line;
  }

  function renderEvent(text) {
    const line = document.createElement('div');
    line.className = 'line event';
    line.textContent = text;
    return line;
  }

  // --- third-party emotes (7TV / BTTV / FFZ) ---------------------------------
  // Twitch's `emotes` tag only ever carries Twitch-native emote ids; the
  // rest of what chat actually types comes from third-party sets. The
  // joined channel's sets and the global sets load once the channel's
  // Twitch user id is known (ROOMSTATE, right after JOIN) and refresh
  // every ten minutes; shared-stream co-host channels load once, on first
  // sight, so their chat's emotes render too. Any source that is
  // unreachable or rate-limited is simply skipped — chat and native
  // emotes keep working regardless.

  const emoteSetsByChannel = new Map(); // twitch user id → Map(name → url); '' = merged globals
  let thirdPartyEmotes = new Map(); // flattened: channels first, globals last
  let joinedRoomId = null;
  let emoteRefreshTimer = null;
  const loadedChannels = new Set(); // co-host channels already fetched once

  function rebuildEmoteMap() {
    const combined = new Map();
    for (const [id, set] of emoteSetsByChannel) {
      if (id === '') continue;
      for (const [name, url] of set) if (!combined.has(name)) combined.set(name, url);
    }
    const globals = emoteSetsByChannel.get('');
    if (globals) for (const [name, url] of globals) if (!combined.has(name)) combined.set(name, url);
    thirdPartyEmotes = combined;
  }

  async function fetchJson(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  }

  const ffzUrl = (urls) => {
    const url = urls?.['1'] ?? urls?.['2'] ?? Object.values(urls ?? {})[0];
    return url ? (url.startsWith('//') ? `https:${url}` : url) : null;
  };
  const ffzSet = (d) => Object.values(d.sets ?? {})
    .flatMap((set) => (set.emoticons ?? []).map((e) => [e.name, ffzUrl(e.urls)]));
  const sevenTv = (emotes) => (emotes ?? [])
    .map((e) => [e.name, `https://cdn.7tv.app/emote/${e.id}/1x.webp`]);
  const bttv = (emotes) => (emotes ?? [])
    .map((e) => [e.code, `https://cdn.betterttv.net/emote/${e.id}/1x`]);

  async function fetchGlobalEmotes() {
    const results = await Promise.allSettled([
      fetchJson('https://7tv.io/v3/emote-sets/global').then((d) => sevenTv(d.emotes)),
      fetchJson('https://api.betterttv.net/3/cached/emotes/global').then(bttv),
      fetchJson('https://api.frankerfacez.com/v1/set/global').then(ffzSet),
    ]);
    const found = new Map();
    for (const result of results) {
      if (result.status !== 'fulfilled') continue;
      for (const [name, url] of result.value) {
        if (name && url && !found.has(name)) found.set(name, url);
      }
    }
    return found;
  }

  async function fetchChannelEmotes(twitchUserId) {
    const id = String(twitchUserId).replace(/\D/g, '');
    const found = new Map();
    const add = (list) => {
      for (const [name, url] of list) if (name && url && !found.has(name)) found.set(name, url);
    };
    // 7TV: the user object carries the emote set — or its id, when only
    // the set reference is embedded.
    try {
      const user = await fetchJson(`https://7tv.io/v3/users/twitch/${id}`);
      let emotes = user?.emote_set?.emotes;
      if (!emotes && user?.emote_set_id) {
        emotes = (await fetchJson(`https://7tv.io/v3/emote-sets/${user.emote_set_id}`))?.emotes;
      }
      add(sevenTv(emotes));
    } catch { /* no 7TV set / unreachable — skip */ }
    // BTTV: channel + shared emotes.
    try {
      const d = await fetchJson(`https://api.betterttv.net/3/cached/users/twitch/${id}`);
      add(bttv([...(d.channelEmotes ?? []), ...(d.sharedEmotes ?? [])]));
    } catch { /* skip */ }
    // FFZ: the joined channel is addressable by login (co-hosts are only
    // known by id, and this endpoint takes names).
    if (id === joinedRoomId) {
      try {
        add(ffzSet(await fetchJson(`https://api.frankerfacez.com/v1/room/${room}`)));
      } catch { /* skip */ }
    }
    return found;
  }

  function loadChannelSet(twitchUserId) {
    const id = String(twitchUserId).replace(/\D/g, '');
    return fetchChannelEmotes(id)
      .then((set) => {
        emoteSetsByChannel.set(id, set);
        rebuildEmoteMap();
      })
      .catch(() => {});
  }

  function ensureEmoteSets(twitchUserId, { joined = false } = {}) {
    const id = String(twitchUserId).replace(/\D/g, '');
    if (!id) return;
    // The joined channel (from ROOMSTATE, or early messages before it)
    // runs the load-and-refresh cycle; any other id is a shared-stream
    // co-host — fetched once, on first sight.
    if (joined || emoteRefreshTimer === null) {
      joinedRoomId ??= id;
      if (emoteRefreshTimer === null) {
        const refresh = () => {
          fetchGlobalEmotes()
            .then((set) => { emoteSetsByChannel.set('', set); rebuildEmoteMap(); })
            .catch(() => {});
          loadChannelSet(joinedRoomId);
        };
        refresh();
        emoteRefreshTimer = setInterval(refresh, 10 * 60 * 1000);
      }
      return;
    }
    if (id === joinedRoomId || loadedChannels.has(id)) return;
    loadedChannels.add(id);
    loadChannelSet(id);
  }

  // --- shared-stream source channels -----------------------------------------
  // Messages relayed from the other channel in a shared stream carry a
  // source-room-id that differs from the joined channel's room-id. The
  // official-style marker is a mini channel avatar, resolved through
  // Twitch's own GQL endpoint (the same public query the official client
  // runs) with BTTV's cached user as fallback. Lookups are cached,
  // failures included, so a busy shared chat never hammers anything.

  const channelCache = new Map(); // twitch user id → Promise<info | null>

  function resolveChannel(id) {
    const cleanId = String(id).replace(/\D/g, '');
    if (!cleanId) return Promise.resolve(null);
    if (!channelCache.has(cleanId)) {
      const ready = (async () => {
        try {
          const response = await fetch('https://gql.twitch.tv/gql', {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              // Twitch's public web client id — the one every browser
              // session uses, not a secret.
              'Client-ID': 'kimne78kx3ncx6brgo4mv6wki5h1ko',
            },
            body: JSON.stringify({
              query: `query { user(id: "${cleanId}") { login displayName profileImageURL(width: 70) } }`,
            }),
          });
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          const { data } = await response.json();
          if (data?.user) {
            return {
              login: data.user.login,
              displayName: data.user.displayName ?? data.user.login,
              logo: data.user.profileImageURL,
            };
          }
        } catch { /* fall through to BTTV */ }
        try {
          const d = await fetchJson(`https://api.betterttv.net/3/cached/users/twitch/${cleanId}`);
          if (d?.avatar) {
            return { login: d?.name, displayName: d?.displayName ?? d?.name, logo: d.avatar };
          }
        } catch { /* ring stays empty */ }
        return null;
      })();
      channelCache.set(cleanId, ready);
    }
    return channelCache.get(cleanId);
  }

  // --- Twitch IRC over WebSocket ------------------------------------------

  let ws = null;
  let backoff = 1000;

  function connect() {
    ws = new WebSocket(IRC_WS_URL);

    ws.onopen = () => {
      backoff = 1000;
      ws.send('CAP REQ :twitch.tv/tags twitch.tv/commands');
      ws.send('NICK justinfan' + (10000 + Math.floor(Math.random() * 90000)));
      ws.send('JOIN #' + room);
    };

    ws.onmessage = (event) => {
      for (const raw of String(event.data).split('\r\n')) {
        if (raw) handleIrc(raw);
      }
    };

    ws.onclose = () => {
      if (closedByUs) return;
      setTimeout(connect, backoff);
      backoff = Math.min(backoff * 2, 10000);
    };

    ws.onerror = () => ws.close();
  }

  function handleIrc(raw) {
    if (raw.startsWith('PING')) {
      ws.send('PONG :tmi.twitch.tv');
      return;
    }

    const { tags, prefix, command, trailing } = parseIrcMessage(raw);

    if (command === 'PRIVMSG') {
      const roomId = tags['room-id'];
      const sourceRoomId = tags['source-room-id'];
      if (roomId) ensureEmoteSets(roomId); // in case ROOMSTATE was missed
      // A shared stream's co-host chat uses the co-host's channel emotes —
      // load their sets too, once.
      if (sourceRoomId) ensureEmoteSets(sourceRoomId);

      // Shared streams: a source-room-id differing from the joined
      // channel's room-id means the message was relayed from the other
      // channel's chat.
      const source = roomId && sourceRoomId && sourceRoomId !== roomId
        ? { ready: resolveChannel(sourceRoomId) }
        : null;

      // Emote ranges index the raw text — the \x01ACTION wrapper included —
      // so parse first, then trim the wrapper off the parts (a plain
      // message parses and trims to itself).
      let parts = parseEmotes(trailing, tags['emotes']);
      let text = trailing;
      let action = false;
      if (text.startsWith('\x01ACTION ') && text.endsWith('\x01')) {
        action = true; // /me message
        parts = trimActionWrapper(parts);
        text = text.slice(8, -1);
      }
      parts = splitEmoteWords(parts, thirdPartyEmotes);

      const name = tags['display-name'] || prefix.split('!')[0] || 'user';
      const color = tags['color'];
      onMessage?.({
        name,
        color,
        text,
        action,
        source: source ? { id: tags['source-room-id'] } : null,
      });
      if (container) {
        addLine(renderMessage({ name, color, parts, action, source }));
      }
    } else if (command === 'ROOMSTATE') {
      // Arrives right after JOIN and carries the channel's Twitch user id,
      // which the third-party emote sets are addressed by.
      if (tags['room-id']) ensureEmoteSets(tags['room-id'], { joined: true });
    } else if (command === 'USERNOTICE' && tags['system-msg']) {
      if (container) addLine(renderEvent(tags['system-msg']));
    } else if (command === 'RECONNECT') {
      ws.close(); // server asked us to reconnect; onclose backs off and retries
    }
  }

  connect();

  return {
    dispose() {
      closedByUs = true;
      if (sweepTimer) clearInterval(sweepTimer);
      if (emoteRefreshTimer) clearInterval(emoteRefreshTimer);
      ws?.close();
    },
  };
}
