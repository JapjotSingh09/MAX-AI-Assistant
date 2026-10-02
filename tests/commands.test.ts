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

// The "Hey MAX, remind me tomorrow at 9 AM to call dad" example from the spec.
// Speech recognition adds noise, so the local parser must tolerate the wake
// phrase, the politeness fillers and the trailing punctuation.
describe("CommandParser - spoken-style phrasing", () => {
  it("strips the wake phrase and still finds the reminder", () => {
    expect(intentOf("Hey MAX, remind me tomorrow at 9 AM to call dad")?.action).toBe("CREATE_REMINDER");
  });
  it("understands 'tomorrow at 9' as 09:00 and 'at 7 pm' as 19:00", () => {
    expect(intentOf("set an alarm for tomorrow at 9")?.parameters).toEqual({ hour: 9, minute: 0 });
    expect(intentOf("remind me at 7 pm to take out the bins")?.parameters?.when).toBeTruthy();
    expect(intentOf("set an alarm for 7 pm")?.parameters).toEqual({ hour: 19, minute: 0 });
  });
  it("understands spoken durations like ninety and 'one and a half'", () => {
    expect(intentOf("set a timer for ninety minutes")?.parameters).toEqual({ seconds: 5400 });
    expect(intentOf("set a timer for one and a half hours")?.parameters).toEqual({ seconds: 5400 });
    expect(intentOf("set a timer for 10 minutes")?.parameters).toEqual({ seconds: 600 });
  });
  it("splits a spoken reminder into what and when", () => {
    // "Hey MAX, remind me tomorrow at 9 AM to call dad"
    const r = intentOf("Hey MAX, remind me tomorrow at 9 AM to call dad");
    expect(r?.action).toBe("CREATE_REMINDER");
    expect(r?.parameters.text).toBe("to call dad");
    expect(r?.parameters.when).toBe("tomorrow at 9 am");
  });
});

describe("CommandParser - notes and tasks", () => {
  it("creates notes from several phrasings", () => {
    expect(intentOf("create a note saying buy milk")).toMatchObject({
      action: "CREATE_NOTE",
      parameters: { content: "buy milk" },
    });
    expect(intentOf("note: parking spot is B4")?.action).toBe("CREATE_NOTE");
    expect(intentOf("write down the wifi password")?.action).toBe("CREATE_NOTE");
  });
  it("lists and deletes notes, with deletion always confirmed", () => {
    expect(intentOf("show my notes")?.action).toBe("LIST_NOTES");
    expect(intentOf("what are my notes")?.action).toBe("LIST_NOTES");
    const del = intentOf("delete the note about milk");
    expect(del).toMatchObject({ action: "DELETE_NOTE", parameters: { query: "milk" }, requiresConfirmation: true });
  });
  it("creates tasks, pulling the due date out of the title", () => {
    const task = intentOf("add a task to submit the assignment tomorrow");
    expect(task?.action).toBe("CREATE_TASK");
    expect(task?.parameters.title).toBe("submit the assignment");
    expect(task?.parameters.due).toBe("tomorrow");
    expect(intentOf("add this to my todo list book flights")?.action).toBe("CREATE_TASK");
  });
  it("lists and completes tasks", () => {
    expect(intentOf("show my tasks")?.action).toBe("LIST_TASKS");
    expect(intentOf("mark the task about milk as done")?.action).toBe("COMPLETE_TASK");
  });
});

describe("CommandParser - search, weather, calendar, bluetooth, share", () => {
  it("routes a plain 'search for X' to the web, not to maps", () => {
    expect(intentOf("search for train times to Delhi")).toMatchObject({ action: "WEB_SEARCH", parameters: { query: "train times to Delhi" } });
    // The maps rules still win for local searches.
    expect(intentOf("search for restaurants near me")?.action).toBe("OPEN_MAPS");
    expect(intentOf("search for pizza on maps")?.action).toBe("OPEN_MAPS");
  });
  it("handles weather with and without a location", () => {
    expect(intentOf("what's the weather in Tokyo")?.parameters).toEqual({ location: "Tokyo" });
    expect(intentOf("show weather")?.action).toBe("OPEN_WEATHER");
  });
  it("opens the calendar", () => {
    expect(intentOf("what's on my calendar")?.action).toBe("READ_CALENDAR");
    expect(intentOf("open my agenda")?.action).toBe("READ_CALENDAR");
  });
  it("maps bluetooth requests onto the honest SET_BLUETOOTH tool", () => {
    expect(intentOf("turn on bluetooth")?.parameters).toEqual({ state: "on" });
    expect(intentOf("turn off bluetooth")?.parameters).toEqual({ state: "off" });
    // "open bluetooth settings" must still reach the settings tool.
    expect(intentOf("open bluetooth settings")?.action).toBe("OPEN_SETTINGS");
  });
  it("shares text via the Android share sheet", () => {
    expect(intentOf("share my location with the team")?.parameters).toEqual({ text: "my location with the team" });
  });
});

describe("CommandParser - destructive commands need confirmation", () => {
  it("routes 'clear conversations' to the destructive server tool", () => {
    expect(intentOf("clear all my conversations")).toMatchObject({
      action: "CLEAR_CONVERSATIONS",
      requiresConfirmation: true,
    });
  });
});

describe("CommandParser - memory read-back and forgetting", () => {
  it("lists memories", () => {
    expect(parseCommand("what did I ask you to remember?")).toEqual({ kind: "memory_list" });
    expect(parseCommand("what do you remember about me?")).toEqual({ kind: "memory_list" });
    expect(parseCommand("show my memories")).toEqual({ kind: "memory_list" });
  });
  it("forgets a specific memory", () => {
    expect(parseCommand("forget that my exam is on Monday")).toEqual({ kind: "memory_forget", query: "my exam is on Monday" });
  });
  it("does not treat an ordinary sentence that mentions forgetting as a delete", () => {
    // "forget" in the middle of a request must reach the AI, not delete rows.
    expect(parseCommand("write an email to my boss and forget nothing")).toBeNull();
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
