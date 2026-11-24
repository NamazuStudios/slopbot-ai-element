import React from 'react'
import { createRoot } from 'react-dom/client'
import { SlopbotAssetsPlugin } from './SlopbotAssetsPlugin'
import '../dev.css'

// The standalone dev mount (not shipped) — `window.__elementsApiClient` is
// stubbed with a token from the query string or localStorage for local work.
;(window as unknown as Record<string, unknown>).__elementsApiClient = {
  getSessionToken: () =>
    new URLSearchParams(window.location.search).get('token') ??
    window.localStorage.getItem('elements-session-secret'),
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <SlopbotAssetsPlugin />
  </React.StrictMode>,
)
