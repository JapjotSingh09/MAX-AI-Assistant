"use client";
import { useCallback, useEffect, useState } from "react";
import { call, errMsg } from "@/components/client";
import type { Toast } from "@/components/types";

type Auto = {
  id: string;
  name: string;
  triggerType: string;
  triggerConfig: { time?: string; deviceName?: string };
  actionType: string;
  actionConfig: Record<string, unknown>;
  enabled: boolean;
};

// Each action offers ONE simple input. We translate it to the validated action_config.
const ACTIONS: Record<string, { label: string; field?: { label: string; placeholder: string; type?: string }; build: (v: string) => Record<string, unknown>; read: (c: Record<string, unknown>) => string }> = {
  OPEN_APP: { label: "Open an app", field: { label: "App name", placeholder: "Spotify" }, build: (v) => ({ appName: v }), read: (c) => String(c.appName ?? "") },
  CREATE_REMINDER: { label: "Create a reminder", field: { label: "Reminder text", placeholder: "Class starts soon" }, build: (v) => ({ text: v }), read: (c) => String(c.text ?? "") },
  SET_TIMER: { label: "Start a timer", field: { label: "Minutes", placeholder: "10", type: "number" }, build: (v) => ({ seconds: Math.round(Number(v) * 60) }), read: (c) => String(Number(c.seconds ?? 0) / 60 || "") },
  OPEN_BROWSER: { label: "Open a website", field: { label: "Website URL", placeholder: "https://example.com" }, build: (v) => ({ url: v }), read: (c) => String(c.url ?? "") },
  OPEN_CAMERA: { label: "Open the camera", build: () => ({}), read: () => "" },
  TOGGLE_FLASHLIGHT: { label: "Turn flashlight on", build: () => ({ state: "on" }), read: () => "" },
};

const TRIGGERS: Record<string, string> = { TIME: "At a set time", BLUETOOTH_CONNECTED: "When Bluetooth headphones connect", CHARGER_CONNECTED: "When the charger is plugged in" };

const describe = (a: Auto) =>
  `${a.triggerType === "TIME" ? `At ${a.triggerConfig.time}` : TRIGGERS[a.triggerType]} → ${ACTIONS[a.actionType]?.label ?? a.actionType}${ACTIONS[a.actionType]?.read(a.actionConfig) ? `: ${ACTIONS[a.actionType].read(a.actionConfig)}` : ""}`;

export function AutomationsScreen({ showToast }: { showToast: Toast }) {
  const [items, setItems] = useState<Auto[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<Auto | "new" | null>(null);

  const load = useCallback(
    async (more = false, from?: string | null) => {
      try {
        const r = await call<{ items: Auto[]; nextCursor: string | null }>(`/api/automations?limit=20${more && from ? `&cursor=${from}` : ""}`);
        setItems((cur) => (more ? [...cur, ...r.items] : r.items));
        setCursor(r.nextCursor);
      } catch (e) {
        showToast(errMsg(e), "error");
      } finally {
        setLoading(false);
      }
    },
    [showToast],
  );
  useEffect(() => {
    load();
  }, [load]);

  async function toggle(a: Auto) {
    try {
      await call(`/api/automations/${a.id}`, { method: "PATCH", json: { enabled: !a.enabled } });
      setItems((l) => l.map((x) => (x.id === a.id ? { ...x, enabled: !a.enabled } : x)));
    } catch (e) {
      showToast(errMsg(e), "error");
    }
  }
  async function remove(a: Auto) {
    if (!confirm(`Delete "${a.name}"?`)) return;
    try {
      await call(`/api/automations/${a.id}`, { method: "DELETE" });
      setItems((l) => l.filter((x) => x.id !== a.id));
    } catch (e) {
      showToast(errMsg(e), "error");
    }
  }

  if (editing) return <AutomationForm initial={editing === "new" ? null : editing} showToast={showToast} onClose={(changed) => { setEditing(null); if (changed) load(); }} />;

  return (
    <div className="px-5 pt-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-white">Automations</h1>
        <button className="btn-gold px-4 py-2 text-sm" onClick={() => setEditing("new")}>＋ New</button>
      </div>
      <p className="mt-2 text-xs text-white/45">Automations are saved to your account and run on the MAX Android app using WorkManager/AlarmManager. This web console only manages them; it can&apos;t run them in the background.</p>
      <div className="mt-5 space-y-3">
        {loading && <p className="py-10 text-center text-sm text-white/40">Loading…</p>}
        {!loading && items.length === 0 && <p className="glass p-6 text-center text-sm text-white/50">No automations yet. Try &quot;At 8 AM remind me about class&quot;.</p>}
        {items.map((a) => (
          <div key={a.id} className="glass fade-up p-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate font-medium text-white">{a.name}</p>
                <p className="mt-1 text-xs text-white/50">{describe(a)}</p>
              </div>
              <button role="switch" aria-checked={a.enabled} aria-label={`Enable ${a.name}`} onClick={() => toggle(a)} className={`relative h-6 w-11 shrink-0 rounded-full transition ${a.enabled ? "bg-gold" : "bg-white/15"}`}>
                <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white transition-all ${a.enabled ? "left-[22px]" : "left-0.5"}`} />
              </button>
            </div>
            <div className="mt-3 flex gap-4 text-xs">
              <button className="text-white/60 hover:text-gold" onClick={() => setEditing(a)}>Edit</button>
              <button className="text-white/60 hover:text-red-300" onClick={() => remove(a)}>Delete</button>
            </div>
          </div>
        ))}
        {cursor && <button className="btn-ghost w-full py-2 text-sm" onClick={() => load(true, cursor)}>Load more</button>}
      </div>
    </div>
  );
}

function AutomationForm({ initial, showToast, onClose }: { initial: Auto | null; showToast: Toast; onClose: (changed: boolean) => void }) {
  const [name, setName] = useState(initial?.name ?? "");
  const [trigger, setTrigger] = useState(initial?.triggerType ?? "TIME");
  const [time, setTime] = useState(initial?.triggerConfig.time ?? "08:00");
  const [device, setDevice] = useState(initial?.triggerConfig.deviceName ?? "");
  const [action, setAction] = useState(initial?.actionType ?? "CREATE_REMINDER");
  const [value, setValue] = useState(initial ? ACTIONS[initial.actionType]?.read(initial.actionConfig) ?? "" : "");
  const [saving, setSaving] = useState(false);
  const def = ACTIONS[action];

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    setSaving(true);
    const body = {
      name,
      triggerType: trigger,
      triggerConfig: trigger === "TIME" ? { time } : trigger === "BLUETOOTH_CONNECTED" && device ? { deviceName: device } : {},
      actionType: action,
      actionConfig: def.build(value),
    };
    try {
      if (initial) await call(`/api/automations/${initial.id}`, { method: "PATCH", json: body });
      else await call("/api/automations", { method: "POST", json: body });
      showToast(initial ? "Automation updated." : "Automation created.");
      onClose(true);
    } catch (err) {
      showToast(errMsg(err), "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="space-y-4 px-5 pt-6">
      <button type="button" className="text-sm text-white/50 hover:text-gold" onClick={() => onClose(false)}>← Back</button>
      <h1 className="text-2xl font-semibold text-white">{initial ? "Edit automation" : "New automation"}</h1>
      <label className="block space-y-1.5 text-sm text-white/60">Name
        <input className="field" required maxLength={80} value={name} onChange={(e) => setName(e.target.value)} placeholder="Morning class reminder" />
      </label>
      <label className="block space-y-1.5 text-sm text-white/60">When
        <select className="field" value={trigger} onChange={(e) => setTrigger(e.target.value)}>
          {Object.entries(TRIGGERS).map(([k, v]) => <option key={k} value={k} className="bg-zinc-900">{v}</option>)}
        </select>
      </label>
      {trigger === "TIME" && <label className="block space-y-1.5 text-sm text-white/60">Time<input className="field" type="time" required value={time} onChange={(e) => setTime(e.target.value)} /></label>}
      {trigger === "BLUETOOTH_CONNECTED" && <label className="block space-y-1.5 text-sm text-white/60">Device name (optional)<input className="field" maxLength={80} value={device} onChange={(e) => setDevice(e.target.value)} placeholder="My headphones" /></label>}
      <label className="block space-y-1.5 text-sm text-white/60">Then
        <select className="field" value={action} onChange={(e) => { setAction(e.target.value); setValue(""); }}>
          {Object.entries(ACTIONS).map(([k, v]) => <option key={k} value={k} className="bg-zinc-900">{v.label}</option>)}
        </select>
      </label>
      {def.field && (
        <label className="block space-y-1.5 text-sm text-white/60">{def.field.label}
          <input className="field" required type={def.field.type ?? "text"} min={def.field.type === "number" ? 1 : undefined} max={def.field.type === "number" ? 1440 : undefined} value={value} onChange={(e) => setValue(e.target.value)} placeholder={def.field.placeholder} />
        </label>
      )}
      <button className="btn-gold w-full py-3" disabled={saving}>{saving ? "Saving…" : "Save automation"}</button>
    </form>
  );
}
