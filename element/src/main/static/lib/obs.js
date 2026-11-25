// ---------------------------------------------------------------------------
// Minimal obs-websocket v5 client (OBS Studio 28+ built-in server).
//
// Speaks the JSON protocol over ws://localhost:4455: Hello → Identify →
// Identified, then Request/RequestResponse (op 6/7) and Events (op 5).
// Pure message builders and the auth hash are exported for testing; the
// client itself is browser-side only (it needs WebSocket).
// ---------------------------------------------------------------------------

// Scenes (4) + SceneItems (128) event intents.
export const OBS_EVENT_SUBSCRIPTIONS = 4 | 128;

// --- pure helpers (exported for testing) ------------------------------------

export function buildIdentify({
  rpcVersion = 1,
  eventSubscriptions = OBS_EVENT_SUBSCRIPTIONS,
  authString,
} = {}) {
  const d = { rpcVersion, eventSubscriptions };
  if (authString) d.authentication = { string: authString };
  return { op: 1, d };
}

export function buildRequest(requestType, requestData = {}, requestId) {
  return { op: 6, d: { requestType, requestId, requestData } };
}

export function parseMessage(raw) {
  return JSON.parse(raw);
}

// obs-websocket v5 auth:
//   base64( sha256( base64( sha256(password + salt) ) + challenge ) )
export async function computeAuthString(password, salt, challenge) {
  const encoder = new TextEncoder();
  const inner = await sha256Base64(encoder.encode(password + salt));
  return sha256Base64(encoder.encode(inner + challenge));
}

async function sha256Base64(bytes) {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return base64FromArrayBuffer(digest);
}

function base64FromArrayBuffer(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

// --- client -------------------------------------------------------------------

export function createObsClient(options = {}) {
  let url = options.url ?? 'ws://localhost:4455';
  let password = options.password ?? '';
  const onStatus = options.onStatus ?? (() => {});

  let ws = null;
  let closedByUs = false;
  let disabled = false;
  let reconnectTimer = null;
  let nextRequestId = 1;
  let backoff = 1000;
  // Failure classification, per current socket: did the server ever say
  // Hello, and did we ever get past Identify? The close handler turns this
  // into three distinct statuses (unreachable / handshake-dropped /
  // disconnected) instead of one generic 1006.
  let gotHello = false;
  let wasIdentified = false;
  let connectedNow = false;

  // Retries pause while the page is hidden (phone screens off, browsers
  // suspend sockets anyway) — visibilitychange triggers a fresh attempt on
  // wake instead, so the backoff clock never burns while nobody's watching.
  const scheduleReconnect = () => {
    if (reconnectTimer) return;
    if (typeof document !== 'undefined' && document.hidden) return;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, backoff);
    backoff = Math.min(backoff * 2, 10000);
  };
  const pending = new Map(); // requestId → { resolve, reject }
  const listeners = new Map(); // eventType → Set<handler>

  function request(requestType, requestData = {}) {
    return new Promise((resolve, reject) => {
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        reject(new Error('OBS WebSocket is not connected'));
        return;
      }
      const requestId = `deck-${nextRequestId++}`;
      pending.set(requestId, { resolve, reject });
      ws.send(JSON.stringify(buildRequest(requestType, requestData, requestId)));
    });
  }

  async function handleMessage(data, socket) {
    switch (data.op) {
      case 0: { // Hello → answer with Identify
        gotHello = true;
        const auth = data.d?.authentication;
        if (auth && !password) {
          // Stop reconnecting until a password is supplied.
          closedByUs = true;
          socket.close();
          onStatus('auth-required', 'password required');
          return;
        }
        const identify = buildIdentify(
          auth ? { authString: await computeAuthString(password, auth.salt, auth.challenge) } : {},
        );
        // Reply on the socket the Hello arrived on — `ws` may have been
        // replaced (or closed) while the auth hash was being computed.
        if (socket.readyState === WebSocket.OPEN) {
          socket.send(JSON.stringify(identify));
        }
        break;
      }
      case 2: // Identified
        wasIdentified = true;
        connectedNow = true;
        backoff = 1000;
        onStatus('connected');
        break;
      case 5: { // Event
        const handlers = listeners.get(data.d?.eventType);
        if (handlers) for (const handler of handlers) handler(data.d.eventData ?? {});
        break;
      }
      case 7: { // RequestResponse
        const entry = pending.get(data.d?.requestId);
        if (entry) {
          pending.delete(data.d.requestId);
          if (data.d.requestStatus?.result) {
            entry.resolve(data.d.responseData ?? {});
          } else {
            const status = data.d.requestStatus;
            entry.reject(new Error(
              `OBS request ${data.d.requestType} failed: ${status?.code} ${status?.comment ?? ''}`.trim(),
            ));
          }
        }
        break;
      }
      default:
        break;
    }
  }

  function connect() {
    if (disabled) {
      // Stays closed while disabled — no socket may survive this state.
      disconnect();
      onStatus('disabled');
      return;
    }
    // A fresh connect() supersedes any previous attempt: cancel its pending
    // reconnect and detach the old socket, so it cannot schedule another
    // reconnect when it closes (otherwise every manual retry while a
    // reconnect is pending leaves an extra permanent socket behind).
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (ws) {
      ws.onclose = null;
      ws.onerror = null;
      try {
        ws.close();
      } catch {
        // Already closing/closed — nothing to detach.
      }
    }
    for (const entry of pending.values()) entry.reject(new Error('OBS WebSocket closed'));
    pending.clear();
    closedByUs = false;
    gotHello = false;
    wasIdentified = false;
    onStatus('connecting');
    const socket = new WebSocket(url);
    ws = socket;
    socket.onmessage = (event) => {
      try {
        handleMessage(parseMessage(event.data), socket);
      } catch (error) {
        onStatus('error', String(error));
      }
    };
    socket.onclose = (event) => {
      for (const entry of pending.values()) entry.reject(new Error('OBS WebSocket closed'));
      pending.clear();
      connectedNow = false;
      if (closedByUs) return;
      if (event.code === 4009) {
        // The password we hold is known-bad — retrying it forever just loops,
        // so stop and wait for the auth form instead.
        onStatus('auth-failed', 'password rejected');
        return;
      }
      // Three distinct failure shapes, from the socket's own history:
      if (wasIdentified) {
        onStatus('disconnected', `connection lost (code ${event.code})`);
      } else if (gotHello) {
        // TCP was accepted and the server said Hello, but the session never
        // completed — the server is up yet refusing to finish the handshake.
        onStatus('handshake-dropped', `session dropped (code ${event.code})`);
      } else {
        // No Hello at all: nothing listening, or the server died before
        // speaking — the "OBS stopped answering" shape.
        onStatus('unreachable', `nothing answered on ${url.replace(/^ws:\/\//, '')}`);
      }
      scheduleReconnect();
    };
    socket.onerror = () => socket.close();
  }

  function disconnect() {
    closedByUs = true;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    // Detach every handler before closing — nothing may speak or reconnect
    // after a disable, whatever state the socket is in.
    if (ws) {
      ws.onclose = null;
      ws.onerror = null;
      ws.onmessage = null;
    }
    try {
      ws?.close();
    } catch {
      // Already closing/closed — nothing to detach.
    }
    for (const entry of pending.values()) entry.reject(new Error('OBS WebSocket closed'));
    pending.clear();
    connectedNow = false;
    ws = null;
  }

  function setPassword(newPassword) {
    password = newPassword;
    backoff = 1000; // a manual attempt is a fresh start
    connect();
  }

  // Toggle the whole connection from the settings modal. Disabled: the
  // socket closes and the retry loop stays off until re-enabled.
  function setEnabled(flag) {
    disabled = !flag;
    if (disabled) {
      disconnect();
      onStatus('disabled');
      return;
    }
    forceReconnect();
  }

  // Point the client at a new server (ws://host:port) and reconnect.
  // Apply server + password + enabled in ONE pass with a single reconnect —
  // no ordering races where a socket opens after a disable.
  function applySettings({ url: newUrl, password: newPassword, enabled: newEnabled } = {}) {
    disabled = newEnabled === false;
    if (newUrl) url = String(newUrl).replace(/\/+$/, '');
    if (newPassword !== undefined) password = String(newPassword);
    if (disabled) {
      disconnect();
      onStatus('disabled');
      return;
    }
    forceReconnect();
  }

  function setServer(newUrl) {
    const next = String(newUrl ?? '').replace(/\/+$/, '');
    if (!next || next === url) return;
    url = next;
    forceReconnect();
  }

  // Manual retry: fresh backoff, immediate attempt. Wired to the deck's
  // status dot and to visibilitychange so a wake-up doesn't wait out the
  // backoff clock.
  function forceReconnect() {
    backoff = 1000;
    connect();
  }

  const isConnected = () => connectedNow;

  function on(eventType, handler) {
    if (!listeners.has(eventType)) listeners.set(eventType, new Set());
    listeners.get(eventType).add(handler);
    return () => listeners.get(eventType)?.delete(handler);
  }

  return {
    connect,
    disconnect,
    setPassword,
    setEnabled,
    setServer,
    applySettings,
    forceReconnect,
    isConnected,
    on,
    request,
    getSceneList: () => request('GetSceneList', {}),
    setCurrentScene: (sceneName) => request('SetCurrentProgramScene', { sceneName }),
    getSceneItemList: (sceneName) => request('GetSceneItemList', { sceneName }),
    setSceneItemEnabled: (sceneName, sceneItemId, sceneItemEnabled) =>
      request('SetSceneItemEnabled', { sceneName, sceneItemId, sceneItemEnabled }),
  };
}
