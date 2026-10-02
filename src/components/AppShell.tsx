"use client";
import { useCallback, useEffect, useState } from "react";
import { call, errMsg, setUnauthorizedHandler } from "@/components/client";
import type { ConnStatus, Me, Prefs, Screen, Seed, Toast } from "@/components/types";
import { Onboarding } from "@/components/Onboarding";
import { AuthScreen } from "@/components/AuthScreen";
import { HomeScreen } from "@/components/HomeScreen";
import { AssistantScreen } from "@/components/AssistantScreen";
import { AutomationsScreen } from "@/components/AutomationsScreen";
import { ActivityScreen } from "@/components/ActivityScreen";
import { SettingsScreen } from "@/components/SettingsScreen";
import { MemoryScreen } from "@/components/MemoryScreen";
import { DrivingMode } from "@/components/DrivingMode";

const DEFAULT_PREFS: Prefs = { theme: "dark", voiceEnabled: true, voiceName: null, assistantName: "MAX", language: "en-US" };

const NAV: { id: Screen; label: string; icon: string }[] = [
  { id: "home", label: "Home", icon: "◉" },
  { id: "assistant", label: "Assistant", icon: "✦" },
  { id: "automations", label: "Automations", icon: "⚡" },
  { id: "activity", label: "Activity", icon: "☰" },
  { id: "settings", label: "Settings", icon: "⚙" },
];

export function AppShell() {
  const [boot, setBoot] = useState<"loading" | "onboarding" | "auth" | "app">("loading");
  const [me, setMe] = useState<Me | null>(null);
  const [prefs, setPrefs] = useState<Prefs>(DEFAULT_PREFS);
  const [screen, setScreen] = useState<Screen>("home");
  const [seed, setSeed] = useState<Seed>(null);
  const [online, setOnline] = useState(true);
  const [resetToken, setResetToken] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; kind: "ok" | "error" } | null>(null);

  const showToast: Toast = useCallback((message, kind = "ok") => {
    setToast({ message, kind });
    setTimeout(() => setToast(null), 4000);
  }, []);

  const loadSession = useCallback(async () => {
    try {
      const data = await call<Me>("/api/auth/me", { quiet401: true });
      const p = await call<{ preferences: Prefs }>("/api/profile");
      setMe(data);
      setPrefs(p.preferences);
      setBoot("app");
      return true;
    } catch {
      setMe(null);
      setBoot("auth");
      return false;
    }
  }, []);

  // First load: handle email links (?verify= / ?reset=), restore the session, or show onboarding.
  useEffect(() => {
    (async () => {
      const params = new URLSearchParams(window.location.search);
      const verify = params.get("verify");
      const reset = params.get("reset");
      if (verify || reset) window.history.replaceState({}, "", "/");
      if (verify) {
        try {
          const r = await call<{ message: string }>("/api/auth/verify", { method: "POST", json: { token: verify }, quiet401: true });
          setNotice(r.message);
        } catch (e) {
          setNotice(errMsg(e));
        }
      }
      if (reset) setResetToken(reset);
      const hasSession = reset ? false : await loadSession();
      if (!hasSession) setBoot(localStorage.getItem("max_onboarded") || reset || verify ? "auth" : "onboarding");
    })();
  }, [loadSession]);

  // A 401 anywhere sends the user back to sign in.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setMe(null);
      setBoot("auth");
      setNotice("Your session has expired. Please sign in again.");
    });
    return () => setUnauthorizedHandler(null);
  }, []);

  useEffect(() => {
    const up = () => setOnline(true);
    const down = () => setOnline(false);
    setOnline(navigator.onLine);
    window.addEventListener("online", up);
    window.addEventListener("offline", down);
    return () => {
      window.removeEventListener("online", up);
      window.removeEventListener("offline", down);
    };
  }, []);

  const status: ConnStatus = !online ? "offline" : me && !me.aiAvailable ? "ai_unavailable" : "online";

  const go = useCallback((s: Screen, s2?: Omit<NonNullable<Seed>, "nonce">) => {
    setSeed(s2 ? { ...s2, nonce: Date.now() } : null);
    setScreen(s);
  }, []);

  async function logout() {
    await call("/api/auth/logout", { method: "POST" }).catch(() => {});
    setMe(null);
    setScreen("home");
    setBoot("auth");
    setNotice(null);
  }

  if (boot === "loading") return <div className="grid min-h-dvh place-items-center text-sm tracking-widest text-gold/70">MAX</div>;
  if (boot === "onboarding") return <Onboarding onDone={() => { localStorage.setItem("max_onboarded", "1"); setBoot("auth"); }} />;
  if (boot === "auth" || !me)
    return (
      <AuthScreen
        resetToken={resetToken}
        notice={notice}
        onAuthed={async (link) => {
          setResetToken(null);
          const ok = await loadSession();
          if (ok && link) showToast("Account created. Check your email to verify (dev: link shown in Settings).");
          if (ok && link) sessionStorage.setItem("max_dev_verify", link);
        }}
      />
    );

  const common = { me, prefs, status, showToast, go };

  return (
    <div className="mx-auto flex min-h-dvh max-w-xl flex-col">
      <main className="flex-1 pb-24">
        {screen === "home" && <HomeScreen {...common} />}
        {screen === "assistant" && <AssistantScreen {...common} seed={seed} />}
        {screen === "automations" && <AutomationsScreen showToast={showToast} />}
        {screen === "activity" && <ActivityScreen showToast={showToast} />}
        {screen === "settings" && (
          <SettingsScreen
            {...common}
            onPrefs={setPrefs}
            onMe={(m) => setMe(m)}
            onLogout={logout}
            onDeleted={() => { setMe(null); setBoot("auth"); setNotice("Your account was deleted."); }}
          />
        )}
        {screen === "memory" && <MemoryScreen showToast={showToast} back={() => setScreen("settings")} />}
        {screen === "driving" && <DrivingMode {...common} back={() => setScreen("home")} />}
      </main>

      {toast && (
        <div role="status" className={`fixed inset-x-0 bottom-24 z-50 mx-auto w-[92%] max-w-md rounded-xl px-4 py-3 text-sm shadow-xl fade-up ${toast.kind === "error" ? "bg-red-950 text-red-200 border border-red-500/30" : "bg-zinc-900 text-white border border-white/10"}`}>
          {toast.message}
        </div>
      )}

      {screen !== "driving" && (
        <nav className="fixed inset-x-0 bottom-0 z-40 mx-auto max-w-xl border-t border-white/10 bg-ink/90 px-2 pb-[env(safe-area-inset-bottom)] backdrop-blur-xl">
          <ul className="grid grid-cols-5">
            {NAV.map((n) => {
              const active = screen === n.id || (n.id === "settings" && screen === "memory");
              return (
                <li key={n.id}>
                  <button onClick={() => go(n.id)} aria-current={active ? "page" : undefined} className={`flex w-full flex-col items-center gap-0.5 py-2.5 text-[11px] transition ${active ? "text-gold" : "text-white/45 hover:text-white/80"}`}>
                    <span className="text-lg leading-none">{n.icon}</span>
                    {n.label}
                  </button>
                </li>
              );
            })}
          </ul>
        </nav>
      )}
    </div>
  );
}
