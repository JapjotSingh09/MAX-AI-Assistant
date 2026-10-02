"use client";
import { useCallback, useEffect, useState } from "react";
import { call, errMsg } from "@/components/client";
import type { Toast } from "@/components/types";

type Mem = { id: string; content: string; createdAt: string };

export function MemoryScreen({ showToast, back }: { showToast: Toast; back: () => void }) {
  const [items, setItems] = useState<Mem[]>([]);
  const [loading, setLoading] = useState(true);
  const [text, setText] = useState("");
  const [editId, setEditId] = useState<string | null>(null);
  const [editText, setEditText] = useState("");

  const load = useCallback(async () => {
    try {
      setItems((await call<{ items: Mem[] }>("/api/memories")).items);
    } catch (e) {
      showToast(errMsg(e), "error");
    } finally {
      setLoading(false);
    }
  }, [showToast]);
  useEffect(() => {
    load();
  }, [load]);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    if (!text.trim()) return;
    try {
      await call("/api/memories", { method: "POST", json: { content: text } });
      setText("");
      load();
    } catch (err) {
      showToast(errMsg(err), "error");
    }
  }
  async function save(id: string) {
    try {
      await call(`/api/memories/${id}`, { method: "PATCH", json: { content: editText } });
      setEditId(null);
      load();
    } catch (err) {
      showToast(errMsg(err), "error");
    }
  }
  async function del(id: string) {
    try {
      await call(`/api/memories/${id}`, { method: "DELETE" });
      setItems((l) => l.filter((m) => m.id !== id));
    } catch (err) {
      showToast(errMsg(err), "error");
    }
  }
  async function clearAll() {
    if (!confirm("Delete everything MAX remembers about you?")) return;
    try {
      await call("/api/memories", { method: "DELETE" });
      setItems([]);
      showToast("Memory cleared.");
    } catch (err) {
      showToast(errMsg(err), "error");
    }
  }

  return (
    <div className="px-5 pt-6">
      <button className="text-sm text-white/50 hover:text-gold" onClick={back}>← Settings</button>
      <h1 className="mt-2 text-2xl font-semibold text-white">Memory</h1>
      <p className="mt-2 text-sm text-white/50">Things you ask MAX to remember (&quot;Remember that I like concise answers&quot;). They&apos;re private to your account, sent to the AI only to personalise replies, and you can edit or delete them any time. Please don&apos;t store passwords or sensitive data here.</p>
      <form onSubmit={add} className="mt-5 flex gap-2">
        <input className="field" placeholder="Remember that…" maxLength={300} value={text} onChange={(e) => setText(e.target.value)} />
        <button className="btn-gold shrink-0 px-4 text-sm" disabled={!text.trim()}>Add</button>
      </form>
      <ul className="mt-5 space-y-2">
        {loading && <li className="py-8 text-center text-sm text-white/40">Loading…</li>}
        {!loading && items.length === 0 && <li className="glass p-6 text-center text-sm text-white/50">MAX doesn&apos;t remember anything yet.</li>}
        {items.map((m) => (
          <li key={m.id} className="glass p-4">
            {editId === m.id ? (
              <div className="flex gap-2">
                <input className="field" value={editText} maxLength={300} onChange={(e) => setEditText(e.target.value)} />
                <button className="btn-gold px-3 text-sm" onClick={() => save(m.id)}>Save</button>
              </div>
            ) : (
              <>
                <p className="text-white/90">{m.content}</p>
                <div className="mt-2 flex gap-4 text-xs text-white/50">
                  <button className="hover:text-gold" onClick={() => { setEditId(m.id); setEditText(m.content); }}>Edit</button>
                  <button className="hover:text-red-300" onClick={() => del(m.id)}>Delete</button>
                </div>
              </>
            )}
          </li>
        ))}
      </ul>
      {items.length > 0 && <button className="mt-6 w-full rounded-xl border border-red-500/30 py-2.5 text-sm text-red-300 hover:bg-red-500/10" onClick={clearAll}>Clear all memory</button>}
    </div>
  );
}
