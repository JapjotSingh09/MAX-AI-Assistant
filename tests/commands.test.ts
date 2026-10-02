import { describe, expect, it } from "vitest";
import { parseCommand } from "@/lib/commands/parser";
import { validateIntent } from "@/lib/commands/intent";

const intentOf = (text: string) => {
  const r = parseCommand(text);
  return r?.kind === "intent" ? r.intent : null;
};

describe("CommandParser (local-first)", () => {
  it("opens apps", () => {
    expect(intentOf("Open YouTube")).toMatchObject({ action: "OPEN_APP", parameters: { appName: "YouTube" } });
    expect(intentOf("hey max, please open Google Maps app")?.action).toBe("OPEN_APP");
  });
  it("controls flashlight", () => {
    expect(intentOf("Turn flashlight on")?.parameters).toEqual({ state: "on" });
    expect(intentOf("turn off the torch")?.parameters).toEqual({ state: "off" });
  });
  it("sets timers and alarms", () => {
    expect(intentOf("Set timer for 10 minutes")?.parameters).toEqual({ seconds: 600 });
    expect(intentOf("set a timer for two hours")?.parameters).toEqual({ seconds: 7200 });
    expect(intentOf("Set an alarm for 7 AM")?.parameters).toEqual({ hour: 7, minute: 0 });
    expect(intentOf("alarm at 6:30 pm")?.parameters).toEqual({ hour: 18, minute: 30 });
    expect(intentOf("set an alarm for 12 am")?.parameters).toEqual({ hour: 0, minute: 0 });
  });
  it("calls and messages always require confirmation", () => {
    const call = intentOf("Call Dad");
    expect(call).toMatchObject({ action: "CALL_CONTACT", parameters: { contactName: "Dad" }, requiresConfirmation: true });
    const sms = intentOf("Send Rahul a message saying I'll reach in 10 minutes");
    expect(sms).toMatchObject({ action: "SEND_SMS", parameters: { contactName: "Rahul", message: "I'll reach in 10 minutes" }, requiresConfirmation: true });
  });
  it("opens a dialer for raw numbers instead of calling", () => {
    expect(intentOf("call +91 98765 43210")?.action).toBe("OPEN_DIALER");
  });
  it("handles whatsapp, maps, browser, notifications", () => {
    expect(intentOf("Open WhatsApp")?.action).toBe("OPEN_WHATSAPP");
    expect(intentOf("WhatsApp Rahul saying hello")).toMatchObject({ action: "OPEN_WHATSAPP", parameters: { contactName: "Rahul", message: "hello" } });
    expect(intentOf("Search for restaurants near me")?.action).toBe("OPEN_MAPS");
    expect(intentOf("navigate to the airport")?.action).toBe("NAVIGATE");
    expect(intentOf("open example.com")?.parameters).toEqual({ url: "https://example.com" });
    expect(intentOf("What notifications did I miss?")?.action).toBe("SHOW_NOTIFICATIONS");
  });
  it("creates reminders and memories", () => {
    expect(intentOf("Create a reminder for tomorrow")?.action).toBe("CREATE_REMINDER");
    expect(parseCommand("Remember that my exam is on Monday")).toEqual({ kind: "memory", text: "my exam is on Monday" });
  });
  it("leaves questions for the cloud AI", () => {
    expect(parseCommand("Explain this topic to me")).toBeNull();
    expect(parseCommand("What is the capital of France?")).toBeNull();
    expect(parseCommand("open the pod bay doors and tell me a story")).toBeNull();
  });
});

describe("CommandIntent validation (closed whitelist)", () => {
  it("accepts a valid action", () => {
    const v = validateIntent({ action: "SET_ALARM", parameters: { hour: 7, minute: 0 } });
    expect(v.ok).toBe(true);
  });
  it("rejects unknown actions (no arbitrary execution)", () => {
    expect(validateIntent({ action: "RUN_SHELL", parameters: { cmd: "rm -rf /" } }).ok).toBe(false);
    expect(validateIntent({ action: "FORMAT_PHONE" }).ok).toBe(false);
  });
  it("rejects invalid parameters", () => {
    expect(validateIntent({ action: "SET_ALARM", parameters: { hour: 99, minute: 0 } }).ok).toBe(false);
    expect(validateIntent({ action: "OPEN_APP", parameters: { appName: "x", extra: "y" } }).ok).toBe(false);
    expect(validateIntent({ action: "OPEN_BROWSER", parameters: { url: "javascript:alert(1)" } }).ok).toBe(false);
    expect(validateIntent("nonsense").ok).toBe(false);
  });
  it("forces confirmation for calls even if the AI says false", () => {
    const v = validateIntent({ action: "CALL_CONTACT", parameters: { contactName: "Dad" }, requiresConfirmation: false });
    expect(v.ok && v.intent.requiresConfirmation).toBe(true);
  });
});
