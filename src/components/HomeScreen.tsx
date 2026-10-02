"use client";
import { Orb } from "@/components/Orb";
import { StatusChip } from "@/components/StatusChip";
import type { ScreenProps } from "@/components/types";

const QUICK = [
  { label: "Call", icon: "☎", seed: { text: "Call " } },
  { label: "Message", icon: "✉", seed: { text: "Send  a message saying " } },
  { label: "Open App", icon: "▦", seed: { text: "Open " } },
  { label: "Driving Mode", icon: "◎", screen: "driving" as const },
  { label: "Notifications", icon: "🔔", seed: { text: "What notifications did I miss?", send: true } },
  { label: "Automations", icon: "⚡", screen: "automations" as const },
];

export function HomeScreen({ me, prefs, status, go }: ScreenProps) {
  const hour = new Date().getHours();
  const greeting = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
  const name = me.user.fullName?.split(" ")[0] || "there";

  return (
    <div className="flex flex-col items-center px-5 pt-8">
      <header className="flex w-full items-start justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-white">{greeting}, {name}</h1>
          <p className="mt-1 text-sm text-gold">{prefs.assistantName} is ready.</p>
        </div>
        <StatusChip status={status} />
      </header>

      <div className="mt-10 mb-4">
        <Orb onClick={() => go("assistant", { listen: true })} />
      </div>
      <h2 className="text-xl font-medium text-white">How can I help?</h2>

      <div className="mt-6 grid w-full grid-cols-2 gap-3">
        <button className="btn-gold py-3.5" onClick={() => go("assistant", { listen: true })}>🎙 Speak</button>
        <button className="btn-ghost py-3.5 font-medium" onClick={() => go("assistant", {})}>⌨ Type</button>
      </div>

      <div className="mt-8 grid w-full grid-cols-3 gap-3">
        {QUICK.map((q) => (
          <button
            key={q.label}
            onClick={() => ("screen" in q && q.screen ? go(q.screen) : go("assistant", q.seed))}
            className="glass fade-up flex flex-col items-center gap-2 px-2 py-4 text-xs text-white/80 transition hover:border-gold/40 hover:text-white"
          >
            <span className="text-xl text-gold">{q.icon}</span>
            {q.label}
          </button>
        ))}
      </div>

      {status !== "online" && (
        <p className="mt-6 text-center text-xs text-white/45">
          {status === "offline" ? "You're offline. Simple commands still work; conversation needs internet." : "AI conversation isn't configured on the server. Simple commands still work."}
        </p>
      )}
    </div>
  );
}
