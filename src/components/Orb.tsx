"use client";

export type VoiceState = "idle" | "listening" | "processing" | "executing" | "speaking" | "error";

// The animated MAX orb. Its look changes with the voice state.
export function Orb({ state = "idle", onClick, size = 1 }: { state?: VoiceState; onClick?: () => void; size?: number }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={`MAX orb, ${state}`}
      className="orb-wrap outline-none"
      data-state={state}
      style={{ transform: `scale(${size})` }}
    >
      <span className="orb-ring r3" />
      <span className="orb-ring" />
      <span className="orb-ring r2" />
      <span className="orb-core" />
    </button>
  );
}
