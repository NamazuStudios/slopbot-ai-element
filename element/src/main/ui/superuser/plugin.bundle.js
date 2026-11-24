(function(React) {
  "use strict";
  var _a;
  function authHeaders() {
    var _a2, _b;
    const token = ((_b = (_a2 = window.__elementsApiClient) == null ? void 0 : _a2.getSessionToken) == null ? void 0 : _b.call(_a2)) ?? null;
    const headers = { "Elements-SessionSecret": token ?? "" };
    if (!token) delete headers["Elements-SessionSecret"];
    return headers;
  }
  async function api(path, init) {
    const res = await fetch(path, {
      ...init,
      credentials: "include",
      headers: { ...authHeaders(), ...(init == null ? void 0 : init.headers) ?? {} }
    });
    return res;
  }
  function formatBytes(n) {
    if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(2)} MiB`;
    if (n >= 1024) return `${(n / 1024).toFixed(1)} KiB`;
    return `${n} B`;
  }
  function btn(extra) {
    return `px-3 py-1.5 rounded-md text-sm font-medium transition-opacity disabled:opacity-50 ${extra}`;
  }
  function SlopbotAssetsPlugin() {
    const [assets, setAssets] = React.useState([]);
    const [error, setError] = React.useState(null);
    const [notice, setNotice] = React.useState(null);
    const [loading, setLoading] = React.useState(false);
    const [busyId, setBusyId] = React.useState(null);
    const [showUpload, setShowUpload] = React.useState(false);
    const [showReassign, setShowReassign] = React.useState(false);
    async function load() {
      setLoading(true);
      setError(null);
      try {
        const res = await api("/slop/api/admin/slop-assets");
        if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
        const body = await res.json();
        setAssets(body.assets ?? []);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setLoading(false);
      }
    }
    React.useEffect(() => {
      load();
    }, []);
    async function post(path, ok) {
      setBusyId(path);
      setError(null);
      setNotice(null);
      try {
        const res = await api(path, { method: "POST" });
        if (!res.ok) {
          const body = await res.json().catch(() => null);
          throw new Error((body == null ? void 0 : body.error) ?? `${res.status} ${res.statusText}`);
        }
        setNotice(ok);
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusyId(null);
      }
    }
    return /* @__PURE__ */ React.createElement("div", { className: "p-6 max-w-6xl" }, /* @__PURE__ */ React.createElement("div", { className: "flex items-center justify-between mb-6" }, /* @__PURE__ */ React.createElement("div", null, /* @__PURE__ */ React.createElement("h1", { className: "text-2xl font-bold" }, "SlopBot AI — Asset Pool"), /* @__PURE__ */ React.createElement("p", { className: "text-muted-foreground" }, "The shared asset pool and per-slop assets (superuser surface).")), /* @__PURE__ */ React.createElement("div", { className: "flex gap-2" }, /* @__PURE__ */ React.createElement("button", { className: btn("bg-primary text-primary-foreground hover:opacity-90"), onClick: () => {
      setShowUpload(!showUpload);
      setShowReassign(false);
    } }, "Upload asset"), /* @__PURE__ */ React.createElement("button", { className: btn("border hover:opacity-90"), onClick: () => {
      setShowReassign(!showReassign);
      setShowUpload(false);
    } }, "Reassign slop"), /* @__PURE__ */ React.createElement("button", { className: btn("border hover:opacity-90"), onClick: load, disabled: loading }, loading ? "Loading…" : "Refresh"))), error && /* @__PURE__ */ React.createElement("div", { className: "mb-4 rounded-lg border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive" }, error), notice && /* @__PURE__ */ React.createElement("div", { className: "mb-4 rounded-lg border border-green-500/50 bg-green-500/10 p-3 text-sm text-green-600" }, notice), showUpload && /* @__PURE__ */ React.createElement(UploadDialog, { onDone: (msg) => {
      setShowUpload(false);
      setNotice(msg);
      load();
    }, onCancel: () => setShowUpload(false) }), showReassign && /* @__PURE__ */ React.createElement(ReassignForm, { onDone: (msg) => {
      setShowReassign(false);
      setNotice(msg);
    }, onCancel: () => setShowReassign(false) }), /* @__PURE__ */ React.createElement("table", { className: "w-full text-sm border-collapse" }, /* @__PURE__ */ React.createElement("thead", null, /* @__PURE__ */ React.createElement("tr", { className: "text-left text-muted-foreground border-b" }, /* @__PURE__ */ React.createElement("th", { className: "py-2 pr-3" }, "Name"), /* @__PURE__ */ React.createElement("th", { className: "py-2 pr-3" }, "Kind"), /* @__PURE__ */ React.createElement("th", { className: "py-2 pr-3" }, "Size"), /* @__PURE__ */ React.createElement("th", { className: "py-2 pr-3" }, "Tags"), /* @__PURE__ */ React.createElement("th", { className: "py-2 pr-3" }, "Referenced by"), /* @__PURE__ */ React.createElement("th", { className: "py-2 pr-3" }, "Rev"), /* @__PURE__ */ React.createElement("th", { className: "py-2 pr-3" }, "State"), /* @__PURE__ */ React.createElement("th", { className: "py-2 pr-3" }, "Actions"))), /* @__PURE__ */ React.createElement("tbody", null, assets.map((a) => /* @__PURE__ */ React.createElement("tr", { key: a.id, className: "border-b hover:bg-muted/50" }, /* @__PURE__ */ React.createElement("td", { className: "py-2 pr-3 font-mono" }, a.name), /* @__PURE__ */ React.createElement("td", { className: "py-2 pr-3" }, a.kind.toLowerCase()), /* @__PURE__ */ React.createElement("td", { className: "py-2 pr-3" }, formatBytes(a.size ?? 0)), /* @__PURE__ */ React.createElement("td", { className: "py-2 pr-3" }, a.tags.join(", ")), /* @__PURE__ */ React.createElement("td", { className: "py-2 pr-3" }, a.referencedBy), /* @__PURE__ */ React.createElement("td", { className: "py-2 pr-3" }, a.revision), /* @__PURE__ */ React.createElement("td", { className: "py-2 pr-3" }, /* @__PURE__ */ React.createElement("span", { className: a.state === "RETIRED" ? "text-destructive" : "text-green-600" }, a.state)), /* @__PURE__ */ React.createElement("td", { className: "py-2 pr-3" }, /* @__PURE__ */ React.createElement("div", { className: "flex gap-1" }, a.state === "RETIRED" ? /* @__PURE__ */ React.createElement(
      "button",
      {
        className: btn("border hover:opacity-90"),
        disabled: busyId === a.id,
        onClick: () => post(`/slop/api/admin/slop-assets/${a.id}/unretire`, `${a.name} unretired`)
      },
      "Unretire"
    ) : /* @__PURE__ */ React.createElement(
      "button",
      {
        className: btn("border hover:opacity-90"),
        disabled: busyId === a.id,
        onClick: () => post(`/slop/api/admin/slop-assets/${a.id}/retire`, `${a.name} retired`)
      },
      "Retire"
    ), /* @__PURE__ */ React.createElement(ReplaceButton, { asset: a, onDone: (msg) => {
      setNotice(msg);
      load();
    } }), /* @__PURE__ */ React.createElement(DeleteButton, { asset: a, onDone: (msg) => {
      setNotice(msg);
      load();
    } }))))), assets.length === 0 && !loading && /* @__PURE__ */ React.createElement("tr", null, /* @__PURE__ */ React.createElement("td", { className: "py-4 text-muted-foreground", colSpan: 8 }, "No assets yet — upload the first one.")))));
  }
  function ReplaceButton({ asset, onDone }) {
    const ref = React.useRef(null);
    const [busy, setBusy] = React.useState(false);
    async function replace(file) {
      setBusy(true);
      try {
        const form = new FormData();
        form.append("file", file, file.name);
        const res = await api(`/slop/api/admin/slop-assets/${asset.id}/bytes`, { method: "PUT", body: form });
        if (!res.ok) {
          const body = await res.json().catch(() => null);
          throw new Error((body == null ? void 0 : body.error) ?? `${res.status} ${res.statusText}`);
        }
        onDone(`${asset.name} replaced (new revision; existing slops pinned to the old)`);
      } catch (e) {
        window.alert(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    }
    return /* @__PURE__ */ React.createElement(React.Fragment, null, /* @__PURE__ */ React.createElement(
      "input",
      {
        ref,
        type: "file",
        accept: ".mp3,.ogg,.wav,.mp4,.webm",
        className: "hidden",
        onChange: (e) => {
          var _a2;
          const f = (_a2 = e.target.files) == null ? void 0 : _a2[0];
          if (f) replace(f);
          e.target.value = "";
        }
      }
    ), /* @__PURE__ */ React.createElement("button", { className: btn("border hover:opacity-90"), disabled: busy, onClick: () => {
      var _a2;
      return (_a2 = ref.current) == null ? void 0 : _a2.click();
    } }, "Replace"));
  }
  function DeleteButton({ asset, onDone }) {
    const [busy, setBusy] = React.useState(false);
    return /* @__PURE__ */ React.createElement(
      "button",
      {
        className: btn("border border-destructive/50 text-destructive hover:opacity-90"),
        disabled: busy,
        title: asset.referencedBy > 0 ? `Referenced by ${asset.referencedBy} slop(s) — retire instead` : "Delete",
        onClick: async () => {
          if (busy) return;
          if (!window.confirm(`Delete ${asset.name}? Only unreferenced assets can be deleted.`)) return;
          setBusy(true);
          try {
            const res = await api(`/slop/api/admin/slop-assets/${asset.id}`, { method: "DELETE" });
            if (!res.ok) {
              const body = await res.json().catch(() => null);
              throw new Error((body == null ? void 0 : body.error) ?? `${res.status} ${res.statusText}`);
            }
            onDone(`${asset.name} deleted`);
          } catch (e) {
            window.alert(e instanceof Error ? e.message : String(e));
          } finally {
            setBusy(false);
          }
        }
      },
      busy ? "…" : "Delete"
    );
  }
  function UploadDialog({ onDone, onCancel }) {
    const [file, setFile] = React.useState(null);
    const [name, setName] = React.useState("");
    const [slop, setSlop] = React.useState("");
    const [busy, setBusy] = React.useState(false);
    const [feedback, setFeedback] = React.useState(null);
    const ALLOWED = [".mp3", ".ogg", ".wav", ".mp4", ".webm"];
    const MAX_BYTES = 8 * 1024 * 1024;
    function validate(f, customName) {
      const base = (customName.trim() || (f == null ? void 0 : f.name) || "").toLowerCase().split(/[\\/]/).pop() ?? "";
      if (!base) return "Pick a file (or type a name).";
      const ext = base.includes(".") ? `.${base.split(".").pop()}` : "";
      if (!ALLOWED.includes(ext)) return `Unsupported extension ${ext || "(none)"} — allowed: ${ALLOWED.join(", ")}`;
      if (base.includes("..")) return "Path shapes are not allowed — use a bare name.";
      if (f && f.size > MAX_BYTES) return `File exceeds the 8 MiB cap (${formatBytes(f.size)}).`;
      return null;
    }
    async function upload() {
      const problem = validate(file, name);
      if (problem) {
        setFeedback(problem);
        return;
      }
      setBusy(true);
      setFeedback(null);
      try {
        const form = new FormData();
        if (file) form.append("file", file, file.name);
        if (name.trim()) form.append("name", name.trim());
        if (slop.trim()) form.append("slop", slop.trim());
        const res = await api("/slop/api/admin/slop-assets", { method: "POST", body: form });
        if (!res.ok) {
          const body = await res.json().catch(() => null);
          throw new Error((body == null ? void 0 : body.error) ?? `${res.status} ${res.statusText}`);
        }
        const created = await res.json();
        onDone(`Uploaded ${created.name}`);
      } catch (e) {
        setFeedback(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    }
    const liveFeedback = feedback ?? validate(file, name);
    return /* @__PURE__ */ React.createElement("div", { className: "mb-6 rounded-lg border p-4 space-y-3" }, /* @__PURE__ */ React.createElement("h2", { className: "font-semibold" }, "Upload asset"), /* @__PURE__ */ React.createElement("div", { className: "grid grid-cols-2 gap-3" }, /* @__PURE__ */ React.createElement("label", { className: "text-sm space-y-1 block" }, /* @__PURE__ */ React.createElement("span", { className: "text-muted-foreground" }, "File (audio .mp3/.ogg/.wav, video .mp4/.webm, ≤ 8 MiB)"), /* @__PURE__ */ React.createElement(
      "input",
      {
        type: "file",
        accept: ALLOWED.join(","),
        className: "block w-full text-sm",
        onChange: (e) => {
          var _a2;
          return setFile(((_a2 = e.target.files) == null ? void 0 : _a2[0]) ?? null);
        }
      }
    )), /* @__PURE__ */ React.createElement("label", { className: "text-sm space-y-1 block" }, /* @__PURE__ */ React.createElement("span", { className: "text-muted-foreground" }, "Name (optional — bare name; defaults to the filename)"), /* @__PURE__ */ React.createElement(
      "input",
      {
        className: "w-full rounded-md border bg-transparent px-2 py-1 text-sm",
        value: name,
        onChange: (e) => setName(e.target.value),
        placeholder: "boing.mp3"
      }
    )), /* @__PURE__ */ React.createElement("label", { className: "text-sm space-y-1 block" }, /* @__PURE__ */ React.createElement("span", { className: "text-muted-foreground" }, "Slop owner (optional — a slop's file name for a per-slop asset)"), /* @__PURE__ */ React.createElement(
      "input",
      {
        className: "w-full rounded-md border bg-transparent px-2 py-1 text-sm",
        value: slop,
        onChange: (e) => setSlop(e.target.value),
        placeholder: "savorysailor-tomato-tribunal.js"
      }
    ))), liveFeedback && /* @__PURE__ */ React.createElement("div", { className: "text-sm text-destructive" }, liveFeedback), /* @__PURE__ */ React.createElement("div", { className: "flex gap-2" }, /* @__PURE__ */ React.createElement("button", { className: btn("bg-primary text-primary-foreground hover:opacity-90"), disabled: busy || !!validate(file, name), onClick: upload }, busy ? "Uploading…" : "Upload"), /* @__PURE__ */ React.createElement("button", { className: btn("border hover:opacity-90"), onClick: onCancel }, "Cancel")));
  }
  function ReassignForm({ onDone, onCancel }) {
    const [slopName, setSlopName] = React.useState("");
    const [owner, setOwner] = React.useState("");
    const [creatorLogin, setCreatorLogin] = React.useState("");
    const [creatorDisplay, setCreatorDisplay] = React.useState("");
    const [busy, setBusy] = React.useState(false);
    const [feedback, setFeedback] = React.useState(null);
    async function submit() {
      if (!slopName.trim()) {
        setFeedback("The slop name is required.");
        return;
      }
      if (!owner.trim() && !creatorLogin.trim()) {
        setFeedback("Nothing to reassign — set an owner and/or a creator.");
        return;
      }
      setBusy(true);
      setFeedback(null);
      try {
        const body = {};
        if (owner.trim()) body.owner = owner.trim();
        if (creatorLogin.trim()) {
          body.creator = { login: creatorLogin.trim(), displayName: creatorDisplay.trim() || creatorLogin.trim() };
        }
        const res = await api(`/slop/api/admin/slop/${encodeURIComponent(slopName.trim())}/reassign`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body)
        });
        if (!res.ok) {
          const errBody = await res.json().catch(() => null);
          throw new Error((errBody == null ? void 0 : errBody.error) ?? `${res.status} ${res.statusText}`);
        }
        onDone(`Reassigned ${slopName.trim()}`);
      } catch (e) {
        setFeedback(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    }
    return /* @__PURE__ */ React.createElement("div", { className: "mb-6 rounded-lg border p-4 space-y-3" }, /* @__PURE__ */ React.createElement("h2", { className: "font-semibold" }, "Reassign slop (attribution repair)"), /* @__PURE__ */ React.createElement("div", { className: "grid grid-cols-2 gap-3" }, /* @__PURE__ */ React.createElement("label", { className: "text-sm space-y-1 block" }, /* @__PURE__ */ React.createElement("span", { className: "text-muted-foreground" }, "Slop name"), /* @__PURE__ */ React.createElement(
      "input",
      {
        className: "w-full rounded-md border bg-transparent px-2 py-1 text-sm",
        value: slopName,
        onChange: (e) => setSlopName(e.target.value),
        placeholder: "savorysailor-tomato-tribunal.js"
      }
    )), /* @__PURE__ */ React.createElement("label", { className: "text-sm space-y-1 block" }, /* @__PURE__ */ React.createElement("span", { className: "text-muted-foreground" }, "New owner (the tenant streamer's login)"), /* @__PURE__ */ React.createElement(
      "input",
      {
        className: "w-full rounded-md border bg-transparent px-2 py-1 text-sm",
        value: owner,
        onChange: (e) => setOwner(e.target.value),
        placeholder: "othersailor"
      }
    )), /* @__PURE__ */ React.createElement("label", { className: "text-sm space-y-1 block" }, /* @__PURE__ */ React.createElement("span", { className: "text-muted-foreground" }, "New creator login"), /* @__PURE__ */ React.createElement(
      "input",
      {
        className: "w-full rounded-md border bg-transparent px-2 py-1 text-sm",
        value: creatorLogin,
        onChange: (e) => setCreatorLogin(e.target.value),
        placeholder: "newcreator"
      }
    )), /* @__PURE__ */ React.createElement("label", { className: "text-sm space-y-1 block" }, /* @__PURE__ */ React.createElement("span", { className: "text-muted-foreground" }, "New creator display name"), /* @__PURE__ */ React.createElement(
      "input",
      {
        className: "w-full rounded-md border bg-transparent px-2 py-1 text-sm",
        value: creatorDisplay,
        onChange: (e) => setCreatorDisplay(e.target.value),
        placeholder: "NewCreator"
      }
    ))), feedback && /* @__PURE__ */ React.createElement("div", { className: "text-sm text-destructive" }, feedback), /* @__PURE__ */ React.createElement("div", { className: "flex gap-2" }, /* @__PURE__ */ React.createElement("button", { className: btn("bg-primary text-primary-foreground hover:opacity-90"), disabled: busy, onClick: submit }, busy ? "Reassigning…" : "Reassign"), /* @__PURE__ */ React.createElement("button", { className: btn("border hover:opacity-90"), onClick: onCancel }, "Cancel")));
  }
  (_a = window.__elementsPlugins) == null ? void 0 : _a.register("slopbot-ai-assets", SlopbotAssetsPlugin);
})(window.React);
