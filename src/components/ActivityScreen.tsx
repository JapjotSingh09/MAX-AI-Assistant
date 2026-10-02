"use client";
import { useCallback, useEffect, useState } from "react";
import { call, errMsg } from "@/components/client";
import type { Toast } from "@/components/types";

type Item = { id: string; actionType: string; metadata: { summary?: string }; createdAt: string };

function dayLabel(d: Date) {
  const today = new Date();
  const y = new Date();
  y.setDate(today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return "Today";
  if (d.toDateString() === y.toDateString()) return "Yesterday";
  return d.toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" });
}

export function ActivityScreen({ showToast }: { showToast: Toast }) {
  const [items, setItems] = useState<Item[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(
    async (from?: string | null) => {
      try {
        const r = await call<{ items: Item[]; nextCursor: string | null }>(`/api/activity?limit=25${from ? `&cursor=${from}` : ""}`);
        setItems((cur) => (from ? [...cur, ...r.items] : r.items));
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

  // Group the loaded page by day (Today / Yesterday / date).
  const groups: { label: string; rows: Item[] }[] = [];
  for (const it of items) {
    const label = dayLabel(new Date(it.createdAt));
    const g = groups[groups.length - 1];
    if (g && g.label === label) g.rows.push(it);
    else groups.push({ label, rows: [it] });
  }

  return (
    <div className="px-5 pt-6">
      <h1 className="text-2xl font-semibold text-white">Activity</h1>
      {loading && <p className="py-10 text-center text-sm text-white/40">Loading…</p>}
      {!loading && items.length === 0 && <p className="glass mt-5 p-6 text-center text-sm text-white/50">Nothing yet. Ask MAX to do something and it will show up here.</p>}
      {groups.map((g) => (
        <section key={g.label} className="mt-6">
          <h2 className="mb-2 text-xs font-semibold tracking-widest text-gold">{g.label.toUpperCase()}</h2>
          <ul className="glass divide-y divide-white/5">
            {g.rows.map((r) => (
              <li key={r.id} className="flex gap-4 px-4 py-3">
                <time className="w-12 shrink-0 pt-0.5 text-xs text-white/40">{new Date(r.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false })}</time>
                <span className="text-sm text-white/85">{r.metadata?.summary ?? r.actionType}</span>
              </li>
            ))}
          </ul>
        </section>
      ))}
      {cursor && <button className="btn-ghost mt-5 w-full py-2.5 text-sm" onClick={() => load(cursor)}>Load older activity</button>}
    </div>
  );
}
