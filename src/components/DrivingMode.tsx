"use client";
import { useState } from "react";
import { Orb } from "@/components/Orb";
import { call, errMsg } from "@/components/client";
import type { ScreenProps } from "@/components/types";
import { useVoice } from "@/components/useVoice";
import { runWebAction } from "@/components/webActions";

type Card = { id: string; label: string; action: string; parameters: Record<string, unknown>; requiresConfirmation: boolean };

// Driving mode: huge controls, voice-first, no typing. Every action still needs one big tap to confirm.
export function DrivingMode({ prefs, showToast, back }: ScreenProps & { back: () => void }) {
  const [reply, setReply] = useState("Tap the orb and speak.");
  const [card, setCard] = useState<Card | null>(null);
  const [busy, setBusy] = useState(false);

  async function ask(text: string, speakReply = true) {
    setBusy(true);
    try {
      const r = await call<{ assistantMessage: { content: string }; action: Card | null }>("/api/chat", { method: "POST", json: { message: text } });
      setReply(r.assistantMessage.content);
      setCard(r.action);
      if (speakReply && prefs.voiceEnabled) voice.speak(r.assistantMessage.content, prefs.voiceName);
      else voice.setState("idle");
    } catch (e) {
      setReply(errMsg(e));
      voice.setState("error");
      showToast(errMsg(e), "error");
    } finally {
      setBusy(false);
    }
  }

  const voice = useVoice(prefs.language, (t) => ask(t));

  async function resolve(decision: "run" | "cancel") {
    if (!card) return;
    const r = decision === "cancel" ? { status: "cancelled" as const, message: "Cancelled." } : runWebAction(card.action, card.parameters);
    await call(`/api/actions/${card.id}`, { method: "PATCH", json: { status: r.status, message: r.message } }).catch(() => {});
    setReply(r.message);
    setCard(null);
  }

  const BIG = [
    { label: "Call", icon: "☎", text: "Open the dialer" },
    { label: "Message", icon: "✉", text: "Open WhatsApp" },
    { label: "Navigate", icon: "➤", text: "Open maps" },
    { label: "Music", icon: "♫", text: "Open Spotify" },
  ];

  return (
    <div className="flex min-h-dvh flex-col px-5 py-6">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold tracking-[0.3em] text-gold">DRIVING MODE</span>
        <button className="btn-ghost px-4 py-2 text-sm" onClick={back}>Exit</button>
      </div>
      <div className="flex flex-1 flex-col items-center justify-center gap-6 text-center">
        <Orb size={1.2} state={busy ? "processing" : voice.state} onClick={() => (voice.state === "listening" ? voice.stop() : voice.listen())} />
        <p className="mt-6 max-w-sm text-2xl font-medium leading-snug text-white">{reply}</p>
        {voice.error && <p className="text-sm text-amber-300">{voice.error}</p>}
        {!voice.supported && <p className="text-sm text-amber-300">Voice isn&apos;t supported in this browser, so use the big buttons below.</p>}
        {card && (
          <div className="glass w-full max-w-sm space-y-3 p-4">
            <p className="text-lg text-white">{card.label}</p>
            <div className="grid grid-cols-2 gap-3">
              <button className="btn-ghost py-5 text-lg" onClick={() => resolve("cancel")}>Cancel</button>
              <button className="btn-gold py-5 text-lg" onClick={() => resolve("run")}>{card.requiresConfirmation ? "Confirm" : "Run"}</button>
            </div>
          </div>
        )}
      </div>
      <div className="grid grid-cols-2 gap-3">
        {BIG.map((b) => (
          <button key={b.label} disabled={busy} onClick={() => ask(b.text, false)} className="glass flex flex-col items-center gap-1 py-6 text-lg text-white transition hover:border-gold/50 disabled:opacity-50">
            <span className="text-3xl text-gold">{b.icon}</span>
            {b.label}
          </button>
        ))}
      </div>
      <p className="mt-4 text-center text-xs text-white/35">Keep your eyes on the road. MAX never calls or sends anything without your confirmation.</p>
    </div>
  );
}
