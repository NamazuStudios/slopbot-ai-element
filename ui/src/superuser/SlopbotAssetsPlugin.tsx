import React from 'react'

/**
 * The SlopBot AI asset-admin plugin — the superuser segment's dashboard page.
 * Talks to the element's own REST surface (every route under `/slop/api/…`
 * — the element's absolute REST root, so no serve-prefix derivation needed)
 * with the session token from `window.__elementsApiClient.getSessionToken()`
 * sent as the `Elements-SessionSecret` header.
 *
 * Screens: the asset table (name, kind, size, tags, referenced-by, state),
 * the upload dialog with validation feedback, retire/unretire, the replace
 * flow, and the slop reassign form.
 */

declare const window: Window & {
  __elementsApiClient?: {
    getSessionToken?(): string | null
  }
}

interface Asset {
  id: string
  name: string
  displayName: string
  kind: string
  mimeType: string
  size: number
  tags: string[]
  state: string
  revision: number
  timestamp: number
  originalFilename?: string
  referencedBy: number
}

function authHeaders(): Record<string, string> {
  const token = window.__elementsApiClient?.getSessionToken?.() ?? null
  const headers: Record<string, string> = { 'Elements-SessionSecret': token ?? '' }
  if (!token) delete headers['Elements-SessionSecret']
  return headers
}

async function api(path: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: { ...authHeaders(), ...(init?.headers ?? {}) },
  })
  return res
}

function formatBytes(n: number): string {
  if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(2)} MiB`
  if (n >= 1024) return `${(n / 1024).toFixed(1)} KiB`
  return `${n} B`
}

function btn(extra: string): string {
  return `px-3 py-1.5 rounded-md text-sm font-medium transition-opacity disabled:opacity-50 ${extra}`
}

export function SlopbotAssetsPlugin() {
  const [assets, setAssets] = React.useState<Asset[]>([])
  const [error, setError] = React.useState<string | null>(null)
  const [notice, setNotice] = React.useState<string | null>(null)
  const [loading, setLoading] = React.useState(false)
  const [busyId, setBusyId] = React.useState<string | null>(null)
  const [showUpload, setShowUpload] = React.useState(false)
  const [showReassign, setShowReassign] = React.useState(false)

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const res = await api('/slop/api/admin/slop-assets')
      if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
      const body = await res.json()
      setAssets(body.assets ?? [])
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  React.useEffect(() => { load() }, [])

  async function post(path: string, ok: string) {
    setBusyId(path)
    setError(null); setNotice(null)
    try {
      const res = await api(path, { method: 'POST' })
      if (!res.ok) {
        const body = await res.json().catch(() => null)
        throw new Error(body?.error ?? `${res.status} ${res.statusText}`)
      }
      setNotice(ok)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="p-6 max-w-6xl">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">SlopBot AI — Asset Pool</h1>
          <p className="text-muted-foreground">
            The shared asset pool and per-slop assets (superuser surface).
          </p>
        </div>
        <div className="flex gap-2">
          <button className={btn('bg-primary text-primary-foreground hover:opacity-90')} onClick={() => { setShowUpload(!showUpload); setShowReassign(false) }}>
            Upload asset
          </button>
          <button className={btn('border hover:opacity-90')} onClick={() => { setShowReassign(!showReassign); setShowUpload(false) }}>
            Reassign slop
          </button>
          <button className={btn('border hover:opacity-90')} onClick={load} disabled={loading}>
            {loading ? 'Loading…' : 'Refresh'}
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">{error}</div>
      )}
      {notice && (
        <div className="mb-4 rounded-lg border border-green-500/50 bg-green-500/10 p-3 text-sm text-green-600">{notice}</div>
      )}

      {showUpload && <UploadDialog onDone={(msg) => { setShowUpload(false); setNotice(msg); load() }} onCancel={() => setShowUpload(false)} />}
      {showReassign && <ReassignForm onDone={(msg) => { setShowReassign(false); setNotice(msg); }} onCancel={() => setShowReassign(false)} />}

      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="text-left text-muted-foreground border-b">
            <th className="py-2 pr-3">Name</th>
            <th className="py-2 pr-3">Kind</th>
            <th className="py-2 pr-3">Size</th>
            <th className="py-2 pr-3">Tags</th>
            <th className="py-2 pr-3">Referenced by</th>
            <th className="py-2 pr-3">Rev</th>
            <th className="py-2 pr-3">State</th>
            <th className="py-2 pr-3">Actions</th>
          </tr>
        </thead>
        <tbody>
          {assets.map((a) => (
            <tr key={a.id} className="border-b hover:bg-muted/50">
              <td className="py-2 pr-3 font-mono">{a.name}</td>
              <td className="py-2 pr-3">{a.kind.toLowerCase()}</td>
              <td className="py-2 pr-3">{formatBytes(a.size ?? 0)}</td>
              <td className="py-2 pr-3">{a.tags.join(', ')}</td>
              <td className="py-2 pr-3">{a.referencedBy}</td>
              <td className="py-2 pr-3">{a.revision}</td>
              <td className="py-2 pr-3">
                <span className={a.state === 'RETIRED' ? 'text-destructive' : 'text-green-600'}>{a.state}</span>
              </td>
              <td className="py-2 pr-3">
                <div className="flex gap-1">
                  {a.state === 'RETIRED' ? (
                    <button className={btn('border hover:opacity-90')} disabled={busyId === a.id}
                      onClick={() => post(`/slop/api/admin/slop-assets/${a.id}/unretire`, `${a.name} unretired`)}>
                      Unretire
                    </button>
                  ) : (
                    <button className={btn('border hover:opacity-90')} disabled={busyId === a.id}
                      onClick={() => post(`/slop/api/admin/slop-assets/${a.id}/retire`, `${a.name} retired`)}>
                      Retire
                    </button>
                  )}
                  <ReplaceButton asset={a} onDone={(msg) => { setNotice(msg); load() }} />
                  <DeleteButton asset={a} onDone={(msg) => { setNotice(msg); load() }} />
                </div>
              </td>
            </tr>
          ))}
          {assets.length === 0 && !loading && (
            <tr><td className="py-4 text-muted-foreground" colSpan={8}>No assets yet — upload the first one.</td></tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

/** The replace flow — a new LargeObject revision; existing slops stay pinned to the old. */
function ReplaceButton({ asset, onDone }: { asset: Asset; onDone: (msg: string) => void }) {
  const ref = React.useRef<HTMLInputElement>(null)
  const [busy, setBusy] = React.useState(false)

  async function replace(file: File) {
    setBusy(true)
    try {
      const form = new FormData()
      form.append('file', file, file.name)
      const res = await api(`/slop/api/admin/slop-assets/${asset.id}/bytes`, { method: 'PUT', body: form })
      if (!res.ok) {
        const body = await res.json().catch(() => null)
        throw new Error(body?.error ?? `${res.status} ${res.statusText}`)
      }
      onDone(`${asset.name} replaced (new revision; existing slops pinned to the old)`)
    } catch (e) {
      window.alert(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <input ref={ref} type="file" accept=".mp3,.ogg,.wav,.mp4,.webm" className="hidden"
        onChange={(e) => { const f = e.target.files?.[0]; if (f) replace(f); e.target.value = '' }} />
      <button className={btn('border hover:opacity-90')} disabled={busy} onClick={() => ref.current?.click()}>
        Replace
      </button>
    </>
  )
}

/** The guarded delete (only unreferenced assets — the server refuses the rest). */
function DeleteButton({ asset, onDone }: { asset: Asset; onDone: (msg: string) => void }) {
  const [busy, setBusy] = React.useState(false)
  return (
    <button
      className={btn('border border-destructive/50 text-destructive hover:opacity-90')}
      disabled={busy}
      title={asset.referencedBy > 0 ? `Referenced by ${asset.referencedBy} slop(s) — retire instead` : 'Delete'}
      onClick={async () => {
        if (busy) return
        if (!window.confirm(`Delete ${asset.name}? Only unreferenced assets can be deleted.`)) return
        setBusy(true)
        try {
          const res = await api(`/slop/api/admin/slop-assets/${asset.id}`, { method: 'DELETE' })
          if (!res.ok) {
            const body = await res.json().catch(() => null)
            throw new Error(body?.error ?? `${res.status} ${res.statusText}`)
          }
          onDone(`${asset.name} deleted`)
        } catch (e) {
          window.alert(e instanceof Error ? e.message : String(e))
        } finally {
          setBusy(false)
        }
      }}
    >
      {busy ? '…' : 'Delete'}
    </button>
  )
}

/** The upload dialog — extension/mime allow-list, 8 MiB cap, name normalization. */
function UploadDialog({ onDone, onCancel }: { onDone: (msg: string) => void; onCancel: () => void }) {
  const [file, setFile] = React.useState<File | null>(null)
  const [name, setName] = React.useState('')
  const [slop, setSlop] = React.useState('')
  const [busy, setBusy] = React.useState(false)
  const [feedback, setFeedback] = React.useState<string | null>(null)

  const ALLOWED = ['.mp3', '.ogg', '.wav', '.mp4', '.webm']
  const MAX_BYTES = 8 * 1024 * 1024

  function validate(f: File | null, customName: string): string | null {
    const base = (customName.trim() || f?.name || '').toLowerCase().split(/[\\/]/).pop() ?? ''
    if (!base) return 'Pick a file (or type a name).'
    const ext = base.includes('.') ? `.${base.split('.').pop()}` : ''
    if (!ALLOWED.includes(ext)) return `Unsupported extension ${ext || '(none)'} — allowed: ${ALLOWED.join(', ')}`
    if (base.includes('..')) return 'Path shapes are not allowed — use a bare name.'
    if (f && f.size > MAX_BYTES) return `File exceeds the 8 MiB cap (${formatBytes(f.size)}).`
    return null
  }

  async function upload() {
    const problem = validate(file, name)
    if (problem) { setFeedback(problem); return }
    setBusy(true); setFeedback(null)
    try {
      const form = new FormData()
      if (file) form.append('file', file, file.name)
      if (name.trim()) form.append('name', name.trim())
      if (slop.trim()) form.append('slop', slop.trim())
      const res = await api('/slop/api/admin/slop-assets', { method: 'POST', body: form })
      if (!res.ok) {
        const body = await res.json().catch(() => null)
        throw new Error(body?.error ?? `${res.status} ${res.statusText}`)
      }
      const created = await res.json()
      onDone(`Uploaded ${created.name}`)
    } catch (e) {
      setFeedback(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const liveFeedback = feedback ?? validate(file, name)

  return (
    <div className="mb-6 rounded-lg border p-4 space-y-3">
      <h2 className="font-semibold">Upload asset</h2>
      <div className="grid grid-cols-2 gap-3">
        <label className="text-sm space-y-1 block">
          <span className="text-muted-foreground">File (audio .mp3/.ogg/.wav, video .mp4/.webm, ≤ 8 MiB)</span>
          <input type="file" accept={ALLOWED.join(',')} className="block w-full text-sm"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </label>
        <label className="text-sm space-y-1 block">
          <span className="text-muted-foreground">Name (optional — bare name; defaults to the filename)</span>
          <input className="w-full rounded-md border bg-transparent px-2 py-1 text-sm" value={name}
            onChange={(e) => setName(e.target.value)} placeholder="boing.mp3" />
        </label>
        <label className="text-sm space-y-1 block">
          <span className="text-muted-foreground">Slop owner (optional — a slop's file name for a per-slop asset)</span>
          <input className="w-full rounded-md border bg-transparent px-2 py-1 text-sm" value={slop}
            onChange={(e) => setSlop(e.target.value)} placeholder="savorysailor-tomato-tribunal.js" />
        </label>
      </div>
      {liveFeedback && <div className="text-sm text-destructive">{liveFeedback}</div>}
      <div className="flex gap-2">
        <button className={btn('bg-primary text-primary-foreground hover:opacity-90')} disabled={busy || !!validate(file, name)} onClick={upload}>
          {busy ? 'Uploading…' : 'Upload'}
        </button>
        <button className={btn('border hover:opacity-90')} onClick={onCancel}>Cancel</button>
      </div>
    </div>
  )
}

/** The slop reassign form — the content-attribution repair (audited server-side). */
function ReassignForm({ onDone, onCancel }: { onDone: (msg: string) => void; onCancel: () => void }) {
  const [slopName, setSlopName] = React.useState('')
  const [owner, setOwner] = React.useState('')
  const [creatorLogin, setCreatorLogin] = React.useState('')
  const [creatorDisplay, setCreatorDisplay] = React.useState('')
  const [busy, setBusy] = React.useState(false)
  const [feedback, setFeedback] = React.useState<string | null>(null)

  async function submit() {
    if (!slopName.trim()) { setFeedback('The slop name is required.'); return }
    if (!owner.trim() && !creatorLogin.trim()) { setFeedback('Nothing to reassign — set an owner and/or a creator.'); return }
    setBusy(true); setFeedback(null)
    try {
      const body: Record<string, unknown> = {}
      if (owner.trim()) body.owner = owner.trim()
      if (creatorLogin.trim()) {
        body.creator = { login: creatorLogin.trim(), displayName: creatorDisplay.trim() || creatorLogin.trim() }
      }
      const res = await api(`/slop/api/admin/slop/${encodeURIComponent(slopName.trim())}/reassign`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!res.ok) {
        const errBody = await res.json().catch(() => null)
        throw new Error(errBody?.error ?? `${res.status} ${res.statusText}`)
      }
      onDone(`Reassigned ${slopName.trim()}`)
    } catch (e) {
      setFeedback(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mb-6 rounded-lg border p-4 space-y-3">
      <h2 className="font-semibold">Reassign slop (attribution repair)</h2>
      <div className="grid grid-cols-2 gap-3">
        <label className="text-sm space-y-1 block">
          <span className="text-muted-foreground">Slop name</span>
          <input className="w-full rounded-md border bg-transparent px-2 py-1 text-sm" value={slopName}
            onChange={(e) => setSlopName(e.target.value)} placeholder="savorysailor-tomato-tribunal.js" />
        </label>
        <label className="text-sm space-y-1 block">
          <span className="text-muted-foreground">New owner (the tenant streamer's login)</span>
          <input className="w-full rounded-md border bg-transparent px-2 py-1 text-sm" value={owner}
            onChange={(e) => setOwner(e.target.value)} placeholder="othersailor" />
        </label>
        <label className="text-sm space-y-1 block">
          <span className="text-muted-foreground">New creator login</span>
          <input className="w-full rounded-md border bg-transparent px-2 py-1 text-sm" value={creatorLogin}
            onChange={(e) => setCreatorLogin(e.target.value)} placeholder="newcreator" />
        </label>
        <label className="text-sm space-y-1 block">
          <span className="text-muted-foreground">New creator display name</span>
          <input className="w-full rounded-md border bg-transparent px-2 py-1 text-sm" value={creatorDisplay}
            onChange={(e) => setCreatorDisplay(e.target.value)} placeholder="NewCreator" />
        </label>
      </div>
      {feedback && <div className="text-sm text-destructive">{feedback}</div>}
      <div className="flex gap-2">
        <button className={btn('bg-primary text-primary-foreground hover:opacity-90')} disabled={busy} onClick={submit}>
          {busy ? 'Reassigning…' : 'Reassign'}
        </button>
        <button className={btn('border hover:opacity-90')} onClick={onCancel}>Cancel</button>
      </div>
    </div>
  )
}
