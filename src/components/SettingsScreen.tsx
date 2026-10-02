"use client";
import { useEffect, useState } from "react";
import { call, errMsg } from "@/components/client";
import type { Me, Prefs, ScreenProps } from "@/components/types";

type Usage = { last24h: { aiRequests: number; tokens: number }; limits: { aiPerDay: number; aiPerMinute: number }; ai: { configured: boolean; provider: string | null; model: string | null } };

const LANGS = [["en-US", "English (US)"], ["en-GB", "English (UK)"], ["hi-IN", "हिन्दी"], ["es-ES", "Español"], ["fr-FR", "Français"], ["de-DE", "Deutsch"]];

// Android-only permissions: the web console can't grant these, so we explain them honestly.
const ANDROID_PERMS = [
  ["Microphone", "Voice commands. Only used when you tap the mic."],
  ["Contacts", "Find 'Dad' when you say 'Call Dad'."],
  ["Phone", "Place calls after you confirm."],
  ["Notifications", "Show reminders and timers."],
  ["Overlay", "Optional floating MAX bubble."],
  ["Accessibility", "Optional. Only for automation you request; never for spying."],
];

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mt-6">
      <h2 className="mb-2 text-xs font-semibold tracking-widest text-gold">{title}</h2>
      <div className="glass divide-y divide-white/5">{children}</div>
    </section>
  );
}
const Row = ({ children }: { children: React.ReactNode }) => <div className="flex items-center justify-between gap-3 px-4 py-3 text-sm">{children}</div>;

export function SettingsScreen({ me, prefs, showToast, go, onPrefs, onMe, onLogout, onDeleted }: ScreenProps & { onPrefs: (p: Prefs) => void; onMe: (m: Me) => void; onLogout: () => void; onDeleted: () => void }) {
  const [usage, setUsage] = useState<Usage | null>(null);
  const [name, setName] = useState(me.user.fullName ?? "");
  const [mic, setMic] = useState<string>("unknown");
  const [voices, setVoices] = useState<string[]>([]);
  const [devLink] = useState(() => (typeof window !== "undefined" ? sessionStorage.getItem("max_dev_verify") : null));

  useEffect(() => {
    call<Usage>("/api/usage").then(setUsage).catch(() => {});
    navigator.permissions?.query({ name: "microphone" as PermissionName }).then((r) => setMic(r.state)).catch(() => {});
    const load = () => setVoices(window.speechSynthesis?.getVoices().map((v) => v.name) ?? []);
    load();
    window.speechSynthesis?.addEventListener?.("voiceschanged", load);
    return () => window.speechSynthesis?.removeEventListener?.("voiceschanged", load);
  }, []);

  async function savePrefs(p: Partial<Prefs>) {
    onPrefs({ ...prefs, ...p });
    try {
      await call("/api/profile", { method: "PATCH", json: { preferences: p } });
    } catch (e) {
      showToast(errMsg(e), "error");
    }
  }
  async function saveName() {
    if (!name.trim() || name === me.user.fullName) return;
    try {
      await call("/api/profile", { method: "PATCH", json: { fullName: name } });
      onMe({ ...me, user: { ...me.user, fullName: name.trim() } });
      showToast("Profile updated.");
    } catch (e) {
      showToast(errMsg(e), "error");
    }
  }
  async function clear(path: string, label: string) {
    if (!confirm(`${label}? This can't be undone.`)) return;
    try {
      await call(path, { method: "DELETE" });
      showToast(`${label} — done.`);
    } catch (e) {
      showToast(errMsg(e), "error");
    }
  }
  async function resend() {
    try {
      const r = await call<{ message: string; devLink?: string }>("/api/auth/verify", { method: "POST", json: {} });
      showToast(r.message);
      if (r.devLink) sessionStorage.setItem("max_dev_verify", r.devLink);
    } catch (e) {
      showToast(errMsg(e), "error");
    }
  }
  async function deleteAccount() {
    const password = prompt("Enter your password to permanently delete your account and all data:");
    if (!password) return;
    try {
      await call("/api/profile", { method: "DELETE", json: { password }, quiet401: true });
      onDeleted();
    } catch (e) {
      showToast(errMsg(e), "error");
    }
  }

  const initials = (me.user.fullName || me.user.email).slice(0, 1).toUpperCase();

  return (
    <div className="px-5 pt-6">
      <h1 className="text-2xl font-semibold text-white">Settings</h1>

      <div className="glass mt-5 flex items-center gap-4 p-4">
        <div className="grid h-14 w-14 place-items-center rounded-full bg-gradient-to-br from-gold to-amber text-xl font-bold text-[#1a0e00]">{initials}</div>
        <div className="min-w-0">
          <p className="truncate font-medium text-white">{me.user.fullName || "MAX user"}</p>
          <p className="truncate text-sm text-white/50">{me.user.email}</p>
          <p className="text-xs text-white/35">Joined {new Date(me.user.joinedAt).toLocaleDateString()} · Free plan</p>
        </div>
      </div>

      <Section title="ACCOUNT">
        <Row>
          <input className="field" value={name} maxLength={80} onChange={(e) => setName(e.target.value)} onBlur={saveName} aria-label="Display name" />
        </Row>
        <Row>
          <span className="text-white/70">Email {me.user.emailVerified ? "verified ✓" : "not verified"}</span>
          {!me.user.emailVerified && <button className="btn-ghost px-3 py-1.5 text-xs" onClick={resend}>Resend email</button>}
        </Row>
        {devLink && !me.user.emailVerified && <p className="break-all px-4 py-2 text-xs text-amber-200">Dev mode link: <a className="underline" href={devLink}>verify email</a></p>}
        <Row><button className="text-red-300" onClick={onLogout}>Sign out</button></Row>
      </Section>

      <Section title="AI">
        <Row><span className="text-white/60">Provider</span><span>{usage?.ai.configured ? usage.ai.provider : "Not configured"}</span></Row>
        <Row><span className="text-white/60">Model</span><span className="truncate">{usage?.ai.configured ? usage.ai.model : "—"}</span></Row>
        <Row><span className="text-white/60">Usage (24h)</span><span>{usage ? `${usage.last24h.aiRequests} / ${usage.limits.aiPerDay} requests` : "—"}</span></Row>
      </Section>

      <Section title="VOICE">
        <Row>
          <span>Voice responses</span>
          <button role="switch" aria-checked={prefs.voiceEnabled} aria-label="Voice responses" onClick={() => savePrefs({ voiceEnabled: !prefs.voiceEnabled })} className={`relative h-6 w-11 rounded-full transition ${prefs.voiceEnabled ? "bg-gold" : "bg-white/15"}`}>
            <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white transition-all ${prefs.voiceEnabled ? "left-[22px]" : "left-0.5"}`} />
          </button>
        </Row>
        <Row>
          <span>Language</span>
          <select className="field !w-auto" value={prefs.language} onChange={(e) => savePrefs({ language: e.target.value })}>
            {LANGS.map(([v, l]) => <option key={v} value={v} className="bg-zinc-900">{l}</option>)}
          </select>
        </Row>
        <Row>
          <span>Voice</span>
          <select className="field !w-40" value={prefs.voiceName ?? ""} onChange={(e) => savePrefs({ voiceName: e.target.value || null })}>
            <option value="" className="bg-zinc-900">Default</option>
            {voices.map((v) => <option key={v} value={v} className="bg-zinc-900">{v}</option>)}
          </select>
        </Row>
      </Section>

      <Section title="PERMISSIONS">
        <Row><span>Microphone (this browser)</span><span className="text-white/50">{mic}</span></Row>
        {ANDROID_PERMS.map(([n, why]) => (
          <div key={n} className="px-4 py-3 text-sm">
            <p className="text-white/85">{n} <span className="text-[10px] text-gold">ANDROID APP</span></p>
            <p className="text-xs text-white/45">{why} Requested only when you use the feature.</p>
          </div>
        ))}
      </Section>

      <Section title="AUTOMATIONS">
        <Row><button className="text-white/85" onClick={() => go("automations")}>Manage automations →</button></Row>
        <Row><button className="text-white/85" onClick={() => go("memory")}>Memory →</button></Row>
      </Section>

      <Section title="PRIVACY">
        <Row><button onClick={() => clear("/api/conversations", "Clear conversations")}>Clear conversations</button></Row>
        <Row><button onClick={() => clear("/api/activity", "Clear activity")}>Clear activity</button></Row>
        <Row><button onClick={() => clear("/api/memories", "Clear memory")}>Clear memory</button></Row>
        <Row><button className="text-red-300" onClick={deleteAccount}>Delete account</button></Row>
      </Section>

      <Section title="ABOUT">
        <Row><span className="text-white/60">MAX</span><span>Your Personal AI Assistant · v1.0</span></Row>
      </Section>
      <div className="h-6" />
    </div>
  );
}
