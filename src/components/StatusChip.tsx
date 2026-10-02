import type { ConnStatus } from "@/components/types";

const MAP: Record<ConnStatus, { label: string; dot: string }> = {
  online: { label: "ONLINE", dot: "bg-emerald-400" },
  offline: { label: "OFFLINE", dot: "bg-red-400" },
  ai_unavailable: { label: "AI UNAVAILABLE", dot: "bg-amber-400" },
};

// Shows ONLINE / OFFLINE / AI UNAVAILABLE. Local commands still work in the last two.
export function StatusChip({ status }: { status: ConnStatus }) {
  const s = MAP[status];
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-2.5 py-1 text-[10px] font-semibold tracking-widest text-white/70">
      <span className={`h-1.5 w-1.5 rounded-full ${s.dot}`} />
      {s.label}
    </span>
  );
}
