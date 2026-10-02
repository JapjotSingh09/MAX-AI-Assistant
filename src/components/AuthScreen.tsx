"use client";
import { useEffect, useState } from "react";
import { Orb } from "@/components/Orb";
import { ApiFail, call, errMsg } from "@/components/client";

type Mode = "login" | "signup" | "forgot" | "reset";

export function AuthScreen({ resetToken, notice, onAuthed }: { resetToken: string | null; notice: string | null; onAuthed: (devVerifyLink?: string) => void }) {
  const [mode, setMode] = useState<Mode>(resetToken ? "reset" : "login");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [isSigningUp, setIsSigningUp] = useState(false);
  const [isLoggingIn, setIsLoggingIn] = useState(false);
  const [busy, setBusy] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(notice);
  const [devLink, setDevLink] = useState<string | null>(null);

  useEffect(() => {
    if (cooldown <= 0) return;
    const t = setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => clearTimeout(t);
  }, [cooldown]);

  const working = isSigningUp || isLoggingIn || busy;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    // Duplicate-request protection: ignore submits while one is running or cooling down.
    if (working || cooldown > 0) return;
    setError(null);
    setInfo(null);
    try {
      if (mode === "signup") {
        setIsSigningUp(true);
        const r = await call<{ devVerifyLink?: string }>("/api/auth/signup", { method: "POST", json: { email, password, fullName: name || undefined } });
        onAuthed(r.devVerifyLink);
      } else if (mode === "login") {
        setIsLoggingIn(true);
        await call("/api/auth/login", { method: "POST", json: { email, password }, quiet401: true });
        onAuthed();
      } else if (mode === "forgot") {
        setBusy(true);
        const r = await call<{ message: string; devLink?: string }>("/api/auth/forgot", { method: "POST", json: { email } });
        setInfo(r.message);
        setDevLink(r.devLink ?? null);
      } else {
        setBusy(true);
        const r = await call<{ message: string }>("/api/auth/reset", { method: "POST", json: { token: resetToken, password } });
        setInfo(r.message);
        setMode("login");
        setPassword("");
      }
    } catch (err) {
      setError(errMsg(err));
      if (err instanceof ApiFail && err.status === 429) setCooldown(20);
    } finally {
      setIsSigningUp(false);
      setIsLoggingIn(false);
      setBusy(false);
    }
  }

  const titles: Record<Mode, string> = { login: "Welcome back", signup: "Create your account", forgot: "Reset your password", reset: "Choose a new password" };
  const cta: Record<Mode, string> = { login: "Sign in", signup: "Create account", forgot: "Send reset link", reset: "Update password" };

  return (
    <div className="mx-auto flex min-h-dvh max-w-md flex-col justify-center px-6 py-10">
      <div className="mb-6 flex flex-col items-center">
        <Orb size={0.7} state={working ? "processing" : "idle"} />
        <h1 className="-mt-6 text-3xl font-bold tracking-[0.25em] text-gold">MAX</h1>
        <p className="mt-1 text-sm text-white/50">Your Personal AI Assistant</p>
      </div>
      <form onSubmit={submit} className="glass fade-up space-y-4 p-6">
        <h2 className="text-lg font-semibold text-white">{titles[mode]}</h2>
        {info && <p role="status" className="rounded-lg bg-azure/10 p-3 text-sm text-blue-200">{info}</p>}
        {error && <p role="alert" className="rounded-lg bg-red-500/10 p-3 text-sm text-red-300">{error}</p>}
        {devLink && (
          <p className="break-all rounded-lg bg-gold/10 p-3 text-xs text-amber-200">
            Dev mode (no email provider configured): <a className="underline" href={devLink}>open reset link</a>
          </p>
        )}
        {mode === "signup" && <input className="field" placeholder="Your name" autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} maxLength={80} />}
        {mode !== "reset" && <input className="field" type="email" required placeholder="Email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />}
        {mode !== "forgot" && (
          <input
            className="field"
            type="password"
            required
            minLength={mode === "login" ? 1 : 8}
            placeholder={mode === "login" ? "Password" : "Password (8+ characters)"}
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        )}
        <button className="btn-gold w-full py-3" disabled={working || cooldown > 0}>
          {cooldown > 0 ? `Please wait ${cooldown}s` : working ? "Please wait..." : cta[mode]}
        </button>
        <div className="flex justify-between text-sm text-white/50">
          {mode === "login" ? (
            <>
              <button type="button" className="hover:text-gold" onClick={() => { setMode("signup"); setError(null); }}>Create account</button>
              <button type="button" className="hover:text-gold" onClick={() => { setMode("forgot"); setError(null); }}>Forgot password?</button>
            </>
          ) : (
            <button type="button" className="hover:text-gold" onClick={() => { setMode("login"); setError(null); setDevLink(null); }}>← Back to sign in</button>
          )}
        </div>
      </form>
    </div>
  );
}
