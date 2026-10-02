"use client";
import { useState } from "react";
import { Orb } from "@/components/Orb";

const SLIDES = [
  { kicker: "MEET MAX", title: "Your personal AI assistant.", body: "Calls, apps, reminders and answers — all from one calm, futuristic place." },
  { kicker: "TALK NATURALLY", title: "Use your voice or type commands.", body: "Say \"Open YouTube\" or \"Set a timer for 10 minutes\". Simple commands work instantly, even without AI." },
  { kicker: "YOUR PRIVACY MATTERS", title: "MAX only requests permissions for features you enable.", body: "No hidden listening. Microphone only opens when you tap. You can delete your data any time." },
];

export function Onboarding({ onDone }: { onDone: () => void }) {
  const [i, setI] = useState(0);
  const s = SLIDES[i];
  const last = i === SLIDES.length - 1;
  return (
    <div className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-between px-6 py-10 text-center">
      <button className="self-end text-sm text-white/40 hover:text-white/70" onClick={onDone}>Skip</button>
      <div key={i} className="fade-up flex flex-col items-center gap-8">
        <Orb state={i === 1 ? "listening" : "idle"} />
        <div>
          <p className="mb-3 text-xs font-semibold tracking-[0.3em] text-gold">{s.kicker}</p>
          <h1 className="text-3xl font-semibold leading-tight text-white">{s.title}</h1>
          <p className="mt-4 text-white/60">{s.body}</p>
        </div>
      </div>
      <div className="w-full space-y-5">
        <div className="flex justify-center gap-2">
          {SLIDES.map((_, n) => (
            <span key={n} className={`h-1.5 rounded-full transition-all ${n === i ? "w-8 bg-gold" : "w-1.5 bg-white/20"}`} />
          ))}
        </div>
        <button className="btn-gold w-full py-3.5" onClick={() => (last ? onDone() : setI(i + 1))}>
          {last ? "GET STARTED" : "Next"}
        </button>
      </div>
    </div>
  );
}
