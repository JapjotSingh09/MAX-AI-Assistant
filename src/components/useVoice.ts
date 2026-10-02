"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import type { VoiceState } from "@/components/Orb";

// Browser version of MAX's voice layer (Web Speech API).
// - The microphone is only opened when the user taps; it closes right after one phrase.
// - Audio is handled by the browser's speech service; MAX only receives the final text.
type SR = {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  start(): void;
  stop(): void;
  onresult: ((e: { results: { 0: { transcript: string } }[] }) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
};

export function useVoice(language: string, onText: (text: string) => void) {
  const [state, setState] = useState<VoiceState>("idle");
  const [error, setError] = useState<string | null>(null);
  const recRef = useRef<SR | null>(null);
  const cb = useRef(onText);
  useEffect(() => {
    cb.current = onText;
  });

  const supported = typeof window !== "undefined" && ("SpeechRecognition" in window || "webkitSpeechRecognition" in window);
  const canSpeak = typeof window !== "undefined" && "speechSynthesis" in window;

  const listen = useCallback(() => {
    setError(null);
    const w = window as unknown as { SpeechRecognition?: new () => SR; webkitSpeechRecognition?: new () => SR };
    const Ctor = w.SpeechRecognition || w.webkitSpeechRecognition;
    if (!Ctor) {
      setError("Voice input isn't supported in this browser. You can type instead.");
      setState("error");
      return;
    }
    const rec = new Ctor();
    rec.lang = language;
    rec.interimResults = false;
    rec.continuous = false;
    let got = false;
    rec.onresult = (e) => {
      const text = e.results[0]?.[0]?.transcript?.trim();
      if (text) {
        got = true;
        cb.current(text);
      }
    };
    rec.onerror = (e) => {
      setError(e.error === "not-allowed" ? "Microphone permission was denied. Allow it in your browser settings to use voice." : "I couldn't hear that. Please try again.");
      setState("error");
    };
    rec.onend = () => setState((s) => (s === "error" ? s : got ? "processing" : "idle"));
    recRef.current = rec;
    setState("listening");
    try {
      rec.start();
    } catch {
      setState("idle");
    }
  }, [language]);

  const stop = useCallback(() => {
    recRef.current?.stop();
    window.speechSynthesis?.cancel();
    setState("idle");
  }, []);

  const speak = useCallback(
    (text: string, voiceName?: string | null) => {
      if (!canSpeak) return setState("idle");
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text);
      u.lang = language;
      const v = voiceName ? window.speechSynthesis.getVoices().find((x) => x.name === voiceName) : undefined;
      if (v) u.voice = v;
      u.onstart = () => setState("speaking");
      u.onend = () => setState("idle");
      u.onerror = () => setState("idle");
      window.speechSynthesis.speak(u);
    },
    [canSpeak, language],
  );

  useEffect(() => () => {
    recRef.current?.stop();
    if (typeof window !== "undefined") window.speechSynthesis?.cancel();
  }, []);

  return { state, setState, error, listen, stop, speak, supported, canSpeak };
}
