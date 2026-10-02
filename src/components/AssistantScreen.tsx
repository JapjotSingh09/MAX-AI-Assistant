"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { Orb } from "@/components/Orb";
import { StatusChip } from "@/components/StatusChip";
import { call, errMsg } from "@/components/client";
import type { Seed, ScreenProps } from "@/components/types";
import { useVoice } from "@/components/useVoice";
import { runWebAction } from "@/components/webActions";

type Card = { id: string; action: string; label: string; parameters: Record<string, unknown>; status: string; requiresConfirmation: boolean; result?: string };
type Msg = { id: string; role: "user" | "assistant"; content: string; card?: Card | null; failed?: boolean };
type Conv = { id: string; title: string; updatedAt: string };
type ChatResponse = {
  conversationId: string;
  userMessage: { id: string };
  assistantMessage: { id: string; content: string };
  action: Card | null;
};

const STATUS_TEXT: Record<string, string> = { completed: "Done", prepared: "Prepared — not sent", unsupported: "Not supported here", failed: "Failed", cancelled: "Cancelled" };

export function AssistantScreen({ prefs, status, showToast, seed }: ScreenProps & { seed: Seed }) {
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [convId, setConvId] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [convs, setConvs] = useState<Conv[]>([]);
  const [convCursor, setConvCursor] = useState<string | null>(null);
  const [olderCursor, setOlderCursor] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const spokeRef = useRef(false);
  const lastSeed = useRef<number | null>(null);

  const speakIfVoice = useRef<(t: string) => void>(() => {});

  const send = useCallback(
    async (text: string, opts: { fromVoice?: boolean; retryId?: string } = {}) => {
      const clean = text.trim();
      if (!clean || sending) return;
      spokeRef.current = !!opts.fromVoice;
      const tempId = opts.retryId ?? `tmp-${Date.now()}`;
      setMsgs((m) => (opts.retryId ? m.map((x) => (x.id === tempId ? { ...x, failed: false } : x)) : [...m, { id: tempId, role: "user", content: clean }]));
      setInput("");
      setSending(true);
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      try {
        const r = await call<ChatResponse>("/api/chat", { method: "POST", json: { conversationId: convId, message: clean }, signal: ctrl.signal });
        setConvId(r.conversationId);
        setMsgs((m) => [
          ...m.map((x) => (x.id === tempId ? { ...x, id: r.userMessage.id } : x)),
          { id: r.assistantMessage.id, role: "assistant", content: r.assistantMessage.content, card: r.action },
        ]);
        if (opts.fromVoice && prefs.voiceEnabled) speakIfVoice.current(r.assistantMessage.content);
        else voice.setState("idle");
      } catch (e) {
        if ((e as Error).name === "AbortError") {
          setMsgs((m) => m.map((x) => (x.id === tempId ? { ...x, failed: true } : x)));
          showToast("Stopped.");
        } else {
          setMsgs((m) => m.map((x) => (x.id === tempId ? { ...x, failed: true } : x)));
          showToast(errMsg(e), "error");
        }
        voice.setState("idle");
      } finally {
        setSending(false);
        abortRef.current = null;
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [convId, sending, prefs.voiceEnabled, showToast],
  );

  const voice = useVoice(prefs.language, (t) => send(t, { fromVoice: true }));
  useEffect(() => {
    speakIfVoice.current = (t) => voice.speak(t, prefs.voiceName);
  });

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [msgs.length, sending]);

  // Seeds come from Home quick actions.
  useEffect(() => {
    if (!seed || lastSeed.current === seed.nonce) return;
    lastSeed.current = seed.nonce;
    if (seed.text && seed.send) send(seed.text);
    else if (seed.text) setInput(seed.text);
    if (seed.listen) voice.listen();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seed]);

  async function loadConvs(more = false) {
    try {
      const r = await call<{ items: Conv[]; nextCursor: string | null }>(`/api/conversations?limit=15${more && convCursor ? `&cursor=${convCursor}` : ""}`);
      setConvs((c) => (more ? [...c, ...r.items] : r.items));
      setConvCursor(r.nextCursor);
    } catch (e) {
      showToast(errMsg(e), "error");
    }
  }

  async function openConv(id: string, older = false) {
    try {
      const r = await call<{ items: { id: string; role: "user" | "assistant"; content: string }[]; nextCursor: string | null }>(
        `/api/conversations/${id}/messages?limit=30${older && olderCursor ? `&cursor=${olderCursor}` : ""}`,
      );
      const page = r.items.reverse().map((m) => ({ id: m.id, role: m.role, content: m.content }) as Msg);
      setMsgs((cur) => (older ? [...page, ...cur] : page));
      setOlderCursor(r.nextCursor);
      setConvId(id);
      setShowHistory(false);
    } catch (e) {
      showToast(errMsg(e), "error");
    }
  }

  function newChat() {
    setMsgs([]);
    setConvId(null);
    setOlderCursor(null);
    setShowHistory(false);
  }

  async function resolve(msgId: string, card: Card, decision: "run" | "cancel") {
    let result: { status: "completed" | "prepared" | "unsupported" | "failed" | "cancelled"; message: string };
    if (decision === "cancel") result = { status: "cancelled", message: "Cancelled." };
    else {
      voice.setState("executing");
      result = runWebAction(card.action, card.parameters, (t) => voice.speak(t, prefs.voiceName));
      voice.setState("idle");
    }
    try {
      await call(`/api/actions/${card.id}`, { method: "PATCH", json: { status: result.status, message: result.message } });
    } catch (e) {
      showToast(errMsg(e), "error");
    }
    setMsgs((m) => m.map((x) => (x.id === msgId && x.card ? { ...x, card: { ...x.card, status: result.status, result: result.message } } : x)));
  }

  const lastUser = [...msgs].reverse().find((m) => m.role === "user");

  return (
    <div className="flex h-[calc(100dvh-5.5rem)] flex-col">
      <header className="flex items-center justify-between px-5 pt-5 pb-3">
        <div className="flex items-center gap-3">
          <button className="btn-ghost px-3 py-1.5 text-sm" onClick={() => { setShowHistory((s) => !s); if (!showHistory) loadConvs(); }}>☰ History</button>
          <button className="btn-ghost px-3 py-1.5 text-sm" onClick={newChat}>＋ New</button>
        </div>
        <StatusChip status={status} />
      </header>

      {showHistory ? (
        <div className="flex-1 space-y-2 overflow-y-auto px-5 pb-4">
          {convs.length === 0 && <p className="py-10 text-center text-sm text-white/40">No conversations yet.</p>}
          {convs.map((c) => (
            <button key={c.id} onClick={() => openConv(c.id)} className="glass block w-full px-4 py-3 text-left text-sm hover:border-gold/40">
              <span className="block truncate text-white">{c.title}</span>
              <span className="text-xs text-white/40">{new Date(c.updatedAt).toLocaleString()}</span>
            </button>
          ))}
          {convCursor && <button className="btn-ghost w-full py-2 text-sm" onClick={() => loadConvs(true)}>Load more</button>}
        </div>
      ) : (
        <div className="flex-1 space-y-4 overflow-y-auto px-5 pb-4">
          {olderCursor && convId && <button className="btn-ghost mx-auto block px-4 py-1.5 text-xs" onClick={() => openConv(convId, true)}>Load earlier messages</button>}
          {msgs.length === 0 && (
            <div className="flex flex-col items-center gap-3 pt-10 text-center">
              <Orb size={0.6} state={voice.state} />
              <p className="-mt-4 text-white/70">Ask me anything, or try:</p>
              <div className="flex flex-wrap justify-center gap-2">
                {["Open YouTube", "Set a timer for 10 minutes", "Remember that my exam is on Monday", "Explain black holes simply"].map((s) => (
                  <button key={s} className="btn-ghost px-3 py-1.5 text-xs text-white/70" onClick={() => send(s)}>{s}</button>
                ))}
              </div>
            </div>
          )}
          {msgs.map((m) => (
            <div key={m.id} className={`fade-up flex flex-col ${m.role === "user" ? "items-end" : "items-start"}`}>
              <div className={`max-w-[88%] whitespace-pre-wrap rounded-2xl px-4 py-2.5 text-[15px] leading-relaxed ${m.role === "user" ? "bg-gradient-to-br from-gold/90 to-amber/90 text-[#1a0e00]" : "glass text-white/90"}`}>
                {m.content}
              </div>
              {m.failed && (
                <button className="mt-1 text-xs text-red-300 underline" onClick={() => send(m.content, { retryId: m.id })}>Failed to send. Retry</button>
              )}
              {m.role === "assistant" && (
                <div className="mt-1 flex gap-3 text-[11px] text-white/35">
                  <button className="hover:text-white/70" onClick={() => navigator.clipboard?.writeText(m.content).then(() => showToast("Copied."))}>Copy</button>
                  {m.id === msgs[msgs.length - 1].id && lastUser && !sending && (
                    <button className="hover:text-white/70" onClick={() => send(lastUser.content)}>Regenerate</button>
                  )}
                </div>
              )}
              {m.card && (
                <div className="glass mt-2 w-full max-w-[88%] border-gold/30 p-4">
                  <p className="text-[11px] font-semibold tracking-widest text-gold">{m.card.requiresConfirmation ? "MAX WANTS TO" : "READY TO RUN"}</p>
                  <p className="mt-1 text-white">{m.card.label}</p>
                  {["awaiting_confirmation", "ready"].includes(m.card.status) ? (
                    <div className="mt-3 grid grid-cols-2 gap-2">
                      <button className="btn-ghost py-2 text-sm" onClick={() => resolve(m.id, m.card!, "cancel")}>Cancel</button>
                      <button className="btn-gold py-2 text-sm" onClick={() => resolve(m.id, m.card!, "run")}>{m.card.requiresConfirmation ? "Confirm" : "Run"}</button>
                    </div>
                  ) : (
                    <p className={`mt-2 text-sm ${m.card.status === "completed" ? "text-emerald-300" : m.card.status === "prepared" ? "text-blue-300" : "text-amber-300"}`}>
                      {STATUS_TEXT[m.card.status] ?? m.card.status}
                      {m.card.result ? ` — ${m.card.result}` : ""}
                    </p>
                  )}
                </div>
              )}
            </div>
          ))}
          {sending && <div className="glass inline-block px-4 py-2 text-sm text-white/50">MAX is thinking…</div>}
          <div ref={endRef} />
        </div>
      )}

      {voice.error && <p role="alert" className="px-5 pb-2 text-xs text-amber-300">{voice.error}</p>}
      <form
        className="flex items-center gap-2 border-t border-white/5 px-4 py-3"
        onSubmit={(e) => {
          e.preventDefault();
          send(input);
        }}
      >
        <button
          type="button"
          aria-label={voice.state === "listening" ? "Stop listening" : "Speak"}
          onClick={() => (voice.state === "listening" || voice.state === "speaking" ? voice.stop() : voice.listen())}
          disabled={!voice.supported}
          title={voice.supported ? "Tap to speak" : "Voice isn't supported in this browser"}
          className={`grid h-11 w-11 shrink-0 place-items-center rounded-full border transition ${voice.state === "listening" ? "border-azure bg-azure/20 text-blue-200" : "border-white/10 bg-white/5 text-gold"} disabled:opacity-30`}
        >
          🎙
        </button>
        <input className="field" placeholder={voice.state === "listening" ? "Listening…" : "Message MAX"} value={input} onChange={(e) => setInput(e.target.value)} maxLength={2000} />
        {sending ? (
          <button type="button" className="btn-ghost h-11 shrink-0 px-4 text-sm" onClick={() => abortRef.current?.abort()}>Stop</button>
        ) : (
          <button className="btn-gold h-11 shrink-0 px-5 text-sm" disabled={!input.trim()}>Send</button>
        )}
      </form>
    </div>
  );
}
