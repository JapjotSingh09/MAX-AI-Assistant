import { z } from "zod";

// CLOSED WHITELIST. The AI can only *suggest* one of these actions; anything else
// is rejected. No shell commands or arbitrary code are ever executed.
export const ACTIONS = [
  "OPEN_APP",
  "CALL_CONTACT",
  "OPEN_DIALER",
  "SEND_SMS",
  "OPEN_WHATSAPP",
  "OPEN_MAPS",
  "NAVIGATE",
  "SET_ALARM",
  "SET_TIMER",
  "CREATE_REMINDER",
  "OPEN_CAMERA",
  "OPEN_BROWSER",
  "ADJUST_VOLUME",
  "TOGGLE_FLASHLIGHT",
  "OPEN_SETTINGS",
  "SHOW_NOTIFICATIONS",
] as const;
export type ActionType = (typeof ACTIONS)[number];

const text = (max = 200) => z.string().trim().min(1).max(max);
const none = z.object({}).strict();

// One small schema per action: every parameter is validated before use.
export const PARAM_SCHEMAS: Record<ActionType, z.ZodType<Record<string, unknown>>> = {
  OPEN_APP: z.object({ appName: text(60) }).strict(),
  CALL_CONTACT: z.object({ contactName: text(80) }).strict(),
  OPEN_DIALER: z.object({ number: z.string().trim().max(30).optional() }).strict(),
  SEND_SMS: z.object({ contactName: text(80), message: text(500) }).strict(),
  OPEN_WHATSAPP: z.object({ contactName: text(80).optional(), message: text(500).optional() }).strict(),
  OPEN_MAPS: z.object({ query: text(120).optional() }).strict(),
  NAVIGATE: z.object({ destination: text(160) }).strict(),
  SET_ALARM: z.object({ hour: z.number().int().min(0).max(23), minute: z.number().int().min(0).max(59), label: text(80).optional() }).strict(),
  SET_TIMER: z.object({ seconds: z.number().int().min(1).max(86400) }).strict(),
  CREATE_REMINDER: z.object({ text: text(200), when: text(80).optional() }).strict(),
  OPEN_CAMERA: none,
  OPEN_BROWSER: z
    .object({
      url: z.string().trim().max(500).optional(),
      query: text(200).optional(),
    })
    .strict()
    .refine((p) => p.url || p.query, "url or query required")
    .refine((p) => !p.url || /^https?:\/\//i.test(p.url), "only http(s) URLs are allowed"),
  ADJUST_VOLUME: z.object({ direction: z.enum(["up", "down", "mute"]) }).strict(),
  TOGGLE_FLASHLIGHT: z.object({ state: z.enum(["on", "off", "toggle"]) }).strict(),
  OPEN_SETTINGS: z.object({ section: text(40).optional() }).strict(),
  SHOW_NOTIFICATIONS: none,
};

// Actions that touch other people or cost money ALWAYS need confirmation,
// no matter what the AI says.
const ALWAYS_CONFIRM = new Set<ActionType>(["CALL_CONTACT", "SEND_SMS"]);

export type CommandIntent = {
  action: ActionType;
  parameters: Record<string, unknown>;
  requiresConfirmation: boolean;
};

export type IntentValidation = { ok: true; intent: CommandIntent } | { ok: false; reason: string };

// Validates ANY raw intent (from the local parser or from AI output).
export function validateIntent(raw: unknown): IntentValidation {
  if (!raw || typeof raw !== "object") return { ok: false, reason: "Not an object" };
  const r = raw as { action?: unknown; parameters?: unknown; requiresConfirmation?: unknown };
  if (typeof r.action !== "string" || !(ACTIONS as readonly string[]).includes(r.action)) {
    return { ok: false, reason: "Unknown action" };
  }
  const action = r.action as ActionType;
  const parsed = PARAM_SCHEMAS[action].safeParse(r.parameters ?? {});
  if (!parsed.success) return { ok: false, reason: "Invalid parameters" };
  return {
    ok: true,
    intent: {
      action,
      parameters: parsed.data,
      requiresConfirmation: ALWAYS_CONFIRM.has(action) || r.requiresConfirmation === true,
    },
  };
}

export const ACTION_LABELS: Record<ActionType, (p: Record<string, unknown>) => string> = {
  OPEN_APP: (p) => `Open ${p.appName}`,
  CALL_CONTACT: (p) => `Call ${p.contactName}`,
  OPEN_DIALER: (p) => (p.number ? `Open dialer with ${p.number}` : "Open the dialer"),
  SEND_SMS: (p) => `Message ${p.contactName}: "${p.message}"`,
  OPEN_WHATSAPP: (p) => (p.contactName ? `Open WhatsApp for ${p.contactName}` : "Open WhatsApp"),
  OPEN_MAPS: (p) => (p.query ? `Search maps for ${p.query}` : "Open maps"),
  NAVIGATE: (p) => `Navigate to ${p.destination}`,
  SET_ALARM: (p) => `Set an alarm for ${String(p.hour).padStart(2, "0")}:${String(p.minute).padStart(2, "0")}`,
  SET_TIMER: (p) => `Start a timer for ${formatSeconds(Number(p.seconds))}`,
  CREATE_REMINDER: (p) => `Create a reminder: ${p.text}${p.when ? ` (${p.when})` : ""}`,
  OPEN_CAMERA: () => "Open the camera",
  OPEN_BROWSER: (p) => (p.url ? `Open ${p.url}` : `Search the web for "${p.query}"`),
  ADJUST_VOLUME: (p) => `Turn volume ${p.direction === "mute" ? "to mute" : p.direction}`,
  TOGGLE_FLASHLIGHT: (p) => `Turn flashlight ${p.state}`,
  OPEN_SETTINGS: (p) => (p.section ? `Open ${p.section} settings` : "Open settings"),
  SHOW_NOTIFICATIONS: () => "Show your notifications",
};

export function formatSeconds(s: number) {
  if (s % 3600 === 0) return `${s / 3600} hour${s === 3600 ? "" : "s"}`;
  if (s % 60 === 0) return `${s / 60} minute${s === 60 ? "" : "s"}`;
  return `${s} seconds`;
}
