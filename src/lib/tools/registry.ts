import { z } from "zod";

/**
 * MAX TOOL REGISTRY - the single source of truth for everything MAX may do.
 *
 * WHY a registry instead of one big if/else:
 *  - Each capability is ONE entry carrying its own description (shown to the
 *    AI), typed parameters, strict validation and confirmation policy.
 *  - Adding a capability means adding an entry. Nothing else has to change.
 *  - The same registry feeds three consumers: the AI tool list, the local
 *    command parser, and the device-side whitelist check.
 *
 * SECURITY MODEL:
 *  - This is a CLOSED whitelist. The model can only *suggest* a name from this
 *    list. `validateIntent()` rejects everything else, including unknown
 *    parameter keys (every schema is `.strict()`).
 *  - No tool runs a model-supplied string as code, a shell command or an
 *    arbitrary Intent URI.
 *  - `confirm: "always"` tools require a user tap even when the model claims
 *    the user already agreed. The model can never lower this.
 */

/** Where a tool actually runs. */
export type ExecutionSite =
  /** Performed on the Android phone using public Android APIs. */
  | "device"
  /** Performed by the backend, because it needs the user's stored data. */
  | "server";

/**
 * Confirmation policy.
 *  - "always": destructive or outward-facing. The user MUST confirm.
 *  - "model": the model may set `requiresConfirmation`, and so may the device.
 */
export type ConfirmationPolicy = "always" | "model";

export type ToolDefinition = {
  /**
   * Stable wire name. MUST match the Android `ActionType` enum entry exactly -
   * `tests/tools.test.ts` fails if the two lists ever drift apart.
   */
  readonly name: ToolName;
  /** Sent to the AI as the tool description: what it does AND when to use it. */
  readonly description: string;
  /** Strict schema. Unknown keys are rejected, so nothing can be smuggled in. */
  readonly parameters: z.ZodType<Record<string, unknown>>;
  readonly confirm: ConfirmationPolicy;
  readonly site: ExecutionSite;
  /** Short human sentence for the confirmation card, e.g. "Call Dad". */
  readonly label: (p: Record<string, unknown>) => string;
};

/**
 * The closed list of tool names. Kept as a literal tuple (not derived at
 * runtime) so TypeScript still gives `z.enum(ACTIONS)` its literal types.
 */
export const TOOL_NAMES = [
  // --- Device: launch and navigation -----------------------------------
  "OPEN_APP",
  "OPEN_BROWSER",
  "WEB_SEARCH",
  "OPEN_MAPS",
  "NAVIGATE",
  "OPEN_CAMERA",
  "OPEN_SETTINGS",
  // --- Device: people (outward-facing) ---------------------------------
  "CALL_CONTACT",
  "OPEN_DIALER",
  "SEND_SMS",
  "OPEN_WHATSAPP",
  "SHARE_TEXT",
  // --- Device: time ----------------------------------------------------
  "SET_ALARM",
  "SET_TIMER",
  "CREATE_REMINDER",
  // --- Device: device controls -----------------------------------------
  "ADJUST_VOLUME",
  "TOGGLE_FLASHLIGHT",
  "SHOW_NOTIFICATIONS",
  "SET_BLUETOOTH",
  "OPEN_WEATHER",
  "READ_CALENDAR",
  // --- Device: personal data kept on the phone -------------------------
  "CREATE_NOTE",
  "LIST_NOTES",
  "DELETE_NOTE",
  "CREATE_TASK",
  "LIST_TASKS",
  "COMPLETE_TASK",
  // --- Destructive: always a confirmation tap ---------------------------
  "CLEAR_CONVERSATIONS",
  "CLEAR_MEMORY",
  // --- Server: needs the user's stored data ----------------------------
  "SEARCH_CONVERSATIONS",
] as const;

export type ToolName = (typeof TOOL_NAMES)[number];

// --- Schema helpers -------------------------------------------------------
// Every field carries `.describe()`: that text becomes the JSON-schema
// description the AI reads, which is what makes tool calling pick the right
// parameter instead of guessing.

const str = (max: number, description: string) => z.string().trim().min(1).max(max).describe(description);
const optStr = (max: number, description: string) => str(max, description).optional();
const none = z.object({}).strict();


/** One small strict schema per capability. Unknown keys are always rejected. */
export const TOOL_PARAMETERS: Record<ToolName, z.ZodType<Record<string, unknown>>> = {
  OPEN_APP: z.object({ appName: str(60, "Visible name of the installed app, e.g. 'YouTube'.") }).strict(),
  OPEN_BROWSER: z
    .object({
      url: optStr(500, "Full http(s) URL to open. Only http and https are allowed."),
      query: optStr(200, "Search text, used when no url is given."),
    })
    .strict()
    .refine((p) => Boolean(p.url || p.query), "url or query required")
    .refine((p) => !p.url || /^https?:\/\//i.test(p.url), "only http(s) URLs are allowed"),
  WEB_SEARCH: z.object({ query: str(200, "What to search the web for.") }).strict(),
  OPEN_MAPS: z.object({ query: optStr(120, "Place or address to look up on a map.") }).strict(),
  NAVIGATE: z.object({ destination: str(160, "Street address or place to drive to.") }).strict(),
  OPEN_CAMERA: none,
  OPEN_SETTINGS: z.object({
    section: optStr(40, "One of: wifi, bluetooth, sound, display, battery, location, apps. Omit for the settings home screen."),
  }).strict(),
  CALL_CONTACT: z.object({ contactName: str(80, "Saved contact's name, e.g. 'Dad'.") }).strict(),
  OPEN_DIALER: z.object({ number: optStr(30, "Phone number to pre-fill. Omit to just open the dialer.") }).strict(),
  SEND_SMS: z
    .object({ contactName: str(80, "Saved contact's name."), message: str(500, "Exact text for the message composer.") })
    .strict(),
  OPEN_WHATSAPP: z
    .object({
      contactName: optStr(80, "Saved contact's name. Omit to just open WhatsApp."),
      message: optStr(500, "Text to pre-fill in the WhatsApp chat."),
    })
    .strict(),
  SHARE_TEXT: z.object({ text: str(1000, "Text to put on the Android share sheet.") }).strict(),

  SET_ALARM: z
    .object({
      hour: z.number().int().min(0).max(23).describe("Hour in 24-hour form, 0-23."),
      minute: z.number().int().min(0).max(59).describe("Minute, 0-59."),
      label: optStr(80, "Optional alarm label."),
    })
    .strict(),
  SET_TIMER: z.object({ seconds: z.number().int().min(1).max(86400).describe("Timer length in seconds.") }).strict(),
  CREATE_REMINDER: z
    .object({ text: str(200, "What the reminder is about."), when: optStr(80, "When, in plain words, e.g. 'tomorrow 9am'.") })
    .strict(),

  ADJUST_VOLUME: z.object({ direction: z.enum(["up", "down", "mute"]).describe("Which way to move the volume.") }).strict(),
  TOGGLE_FLASHLIGHT: z.object({ state: z.enum(["on", "off", "toggle"]).describe("Target torch state.") }).strict(),
  SHOW_NOTIFICATIONS: none,
  SET_BLUETOOTH: z.object({ state: z.enum(["on", "off", "toggle"]).describe("Requested Bluetooth state.") }).strict(),
  OPEN_WEATHER: z.object({ location: optStr(80, "City or place. Omit for the current location.") }).strict(),
  READ_CALENDAR: none,

  CREATE_NOTE: z.object({ title: optStr(80, "Short note title."), content: str(2000, "The note body.") }).strict(),
  LIST_NOTES: none,
  DELETE_NOTE: z.object({ query: str(80, "Text that identifies which note to delete.") }).strict(),
  CREATE_TASK: z.object({ title: str(120, "What needs doing."), due: optStr(40, "Due, in plain words, e.g. 'tomorrow'.") }).strict(),
  LIST_TASKS: none,
  COMPLETE_TASK: z.object({ query: str(80, "Text that identifies which task to mark done.") }).strict(),

  CLEAR_CONVERSATIONS: none,
  CLEAR_MEMORY: none,

  SEARCH_CONVERSATIONS: z.object({ query: str(120, "Text to look for in past conversations.") }).strict(),
};

const s = (p: Record<string, unknown>, key: string): string | undefined => {
  const v = p[key];
  return typeof v === "string" && v.trim() ? v.trim() : undefined;
};
const n = (p: Record<string, unknown>, key: string): number | undefined => {
  const v = p[key];
  return typeof v === "number" && Number.isFinite(v) ? v : undefined;
};

export function formatSeconds(total: number): string {
  const sec = Math.max(0, Math.round(total));
  if (sec === 0) return "0 seconds";
  if (sec % 3600 === 0) return `${sec / 3600} hour${sec === 3600 ? "" : "s"}`;
  if (sec % 60 === 0) return `${sec / 60} minute${sec === 60 ? "" : "s"}`;
  return `${sec} second${sec === 1 ? "" : "s"}`;
}

/** Human sentence for the confirmation card and the activity log. */
export const TOOL_LABELS: Record<ToolName, (p: Record<string, unknown>) => string> = {
  OPEN_APP: (p) => `Open ${s(p, "appName") ?? "the app"}`,
  OPEN_BROWSER: (p) => (s(p, "url") ? `Open ${s(p, "url")}` : `Search the web for "${s(p, "query") ?? ""}"`),
  WEB_SEARCH: (p) => `Search the web for "${s(p, "query") ?? ""}"`,
  OPEN_MAPS: (p) => (s(p, "query") ? `Look up "${s(p, "query")}" on maps` : "Open maps"),
  NAVIGATE: (p) => `Navigate to ${s(p, "destination") ?? "your destination"}`,
  OPEN_CAMERA: () => "Open the camera",
  OPEN_SETTINGS: (p) => (s(p, "section") ? `Open ${s(p, "section")} settings` : "Open settings"),

  CALL_CONTACT: (p) => `Call ${s(p, "contactName") ?? "the contact"}`,
  OPEN_DIALER: (p) => (s(p, "number") ? `Open the dialer with ${s(p, "number")}` : "Open the dialer"),
  SEND_SMS: (p) => `Message ${s(p, "contactName") ?? "the contact"}: "${s(p, "message") ?? ""}"`,
  OPEN_WHATSAPP: (p) => `Open WhatsApp${s(p, "contactName") ? ` for ${s(p, "contactName")}` : ""}`,
  SHARE_TEXT: () => "Share a text on the share sheet",

  SET_ALARM: (p) =>
    `Set an alarm for ${String(n(p, "hour") ?? 0).padStart(2, "0")}:${String(n(p, "minute") ?? 0).padStart(2, "0")}`,
  SET_TIMER: (p) => `Start a timer for ${formatSeconds(n(p, "seconds") ?? 0)}`,
  CREATE_REMINDER: (p) => `Create a reminder: ${s(p, "text") ?? ""}${s(p, "when") ? ` (${s(p, "when")})` : ""}`,

  ADJUST_VOLUME: (p) => `Turn volume ${s(p, "direction") === "mute" ? "to mute" : (s(p, "direction") ?? "")}`,
  TOGGLE_FLASHLIGHT: (p) => `Turn flashlight ${s(p, "state") ?? "toggle"}`,
  SHOW_NOTIFICATIONS: () => "Show your recent notifications",
  SET_BLUETOOTH: (p) => `Turn Bluetooth ${s(p, "state") ?? "toggle"}`,
  OPEN_WEATHER: (p) => `Open the weather${s(p, "location") ? ` for ${s(p, "location")}` : ""}`,
  READ_CALENDAR: () => "Open your calendar",

  CREATE_NOTE: (p) => `Create a note: ${s(p, "title") ?? s(p, "content") ?? ""}`,
  LIST_NOTES: () => "Read back your notes",
  DELETE_NOTE: (p) => `Delete the note matching "${s(p, "query") ?? ""}"`,
  CREATE_TASK: (p) => `Create a task: ${s(p, "title") ?? ""}`,
  LIST_TASKS: () => "Read back your tasks",
  COMPLETE_TASK: (p) => `Mark the task matching "${s(p, "query") ?? ""}" as done`,

  CLEAR_CONVERSATIONS: () => "Delete ALL of your conversations",
  CLEAR_MEMORY: () => "Delete ALL of your memories",

  SEARCH_CONVERSATIONS: (p) => `Search your conversations for "${s(p, "query") ?? ""}"`,
};

/**
 * The registry itself. One entry per capability - this is the whole "what can
 * MAX do?" answer, and the order here is the order the AI sees the tools in.
 * Server-side tools (site: "server") are the ones the backend runs itself;
 * everything else is handed to the Android device.
 */
export const TOOL_REGISTRY: readonly ToolDefinition[] = [
  {
    name: "OPEN_APP",
    description: "Launch an app installed on the user's phone by its visible name, e.g. 'open YouTube'.",
    parameters: TOOL_PARAMETERS.OPEN_APP,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.OPEN_APP,
  },
  {
    name: "OPEN_BROWSER",
    description: "Open a web page in the browser. Needs a full http(s) url. For a plain search use WEB_SEARCH instead.",
    parameters: TOOL_PARAMETERS.OPEN_BROWSER,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.OPEN_BROWSER,
  },
  {
    name: "WEB_SEARCH",
    description: "Search the web for a phrase and show the results, e.g. 'search the web for train times'.",
    parameters: TOOL_PARAMETERS.WEB_SEARCH,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.WEB_SEARCH,
  },
  {
    name: "OPEN_MAPS",
    description: "Open a map, optionally looking up a place or address.",
    parameters: TOOL_PARAMETERS.OPEN_MAPS,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.OPEN_MAPS,
  },
  {
    name: "NAVIGATE",
    description: "Start turn-by-turn navigation to an address or place.",
    parameters: TOOL_PARAMETERS.NAVIGATE,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.NAVIGATE,
  },
  {
    name: "OPEN_CAMERA",
    description: "Open the phone's camera app so the user can take a photo or a video.",
    parameters: TOOL_PARAMETERS.OPEN_CAMERA,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.OPEN_CAMERA,
  },
  {
    name: "OPEN_SETTINGS",
    description:
      "Open an Android settings screen, optionally one section (wifi, bluetooth, sound, display, battery, location, apps).",
    parameters: TOOL_PARAMETERS.OPEN_SETTINGS,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.OPEN_SETTINGS,
  },
  {
    name: "CALL_CONTACT",
    description:
      "Call a saved contact by name. Android needs the user's permission, so this ALWAYS asks the user to confirm first.",
    parameters: TOOL_PARAMETERS.CALL_CONTACT,
    confirm: "always",
    site: "device",
    label: TOOL_LABELS.CALL_CONTACT,
  },
  {
    name: "OPEN_DIALER",
    description: "Open the phone dialer, optionally pre-filled with a number. It never places the call itself.",
    parameters: TOOL_PARAMETERS.OPEN_DIALER,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.OPEN_DIALER,
  },
  {
    name: "SEND_SMS",
    description:
      "Put a text message in the SMS composer for a contact. Android has no public API to send it silently, so this ALWAYS asks the user to confirm and the user still presses send.",
    parameters: TOOL_PARAMETERS.SEND_SMS,
    confirm: "always",
    site: "device",
    label: TOOL_LABELS.SEND_SMS,
  },
  {
    name: "OPEN_WHATSAPP",
    description: "Open a WhatsApp chat, optionally pre-filled with text. Nothing is ever sent automatically.",
    parameters: TOOL_PARAMETERS.OPEN_WHATSAPP,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.OPEN_WHATSAPP,
  },
  {
    name: "SHARE_TEXT",
    description: "Put a piece of text on the Android share sheet so the user can send it anywhere they like.",
    parameters: TOOL_PARAMETERS.SHARE_TEXT,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.SHARE_TEXT,
  },
  {
    name: "SET_ALARM",
    description: "Set a clock alarm for an exact 24-hour time.",
    parameters: TOOL_PARAMETERS.SET_ALARM,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.SET_ALARM,
  },
  {
    name: "SET_TIMER",
    description: "Start a countdown timer for a number of seconds.",
    parameters: TOOL_PARAMETERS.SET_TIMER,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.SET_TIMER,
  },
  {
    name: "CREATE_REMINDER",
    description:
      "Create a reminder. On a phone MAX schedules its own alarm so it still fires with the screen off; on the web it opens the calendar's new-event screen.",
    parameters: TOOL_PARAMETERS.CREATE_REMINDER,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.CREATE_REMINDER,
  },
  {
    name: "ADJUST_VOLUME",
    description: "Raise, lower or mute the media volume.",
    parameters: TOOL_PARAMETERS.ADJUST_VOLUME,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.ADJUST_VOLUME,
  },
  {
    name: "TOGGLE_FLASHLIGHT",
    description: "Turn the phone's torch on, off, or toggle it.",
    parameters: TOOL_PARAMETERS.TOGGLE_FLASHLIGHT,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.TOGGLE_FLASHLIGHT,
  },
  {
    name: "SHOW_NOTIFICATIONS",
    description:
      "Read back notifications MAX has seen on this phone. Needs notification access, which the user grants in Android Settings.",
    parameters: TOOL_PARAMETERS.SHOW_NOTIFICATIONS,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.SHOW_NOTIFICATIONS,
  },
  {
    name: "SET_BLUETOOTH",
    description:
      "Turn Bluetooth on or off. Android only lets a normal app open the Bluetooth settings screen, never switch it silently, so MAX opens settings and says so.",
    parameters: TOOL_PARAMETERS.SET_BLUETOOTH,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.SET_BLUETOOTH,
  },
  {
    name: "OPEN_WEATHER",
    description: "Open the weather forecast, optionally for a named place.",
    parameters: TOOL_PARAMETERS.OPEN_WEATHER,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.OPEN_WEATHER,
  },
  {
    name: "READ_CALENDAR",
    description: "Open the phone's calendar agenda. Android never lets a normal app read event details silently.",
    parameters: TOOL_PARAMETERS.READ_CALENDAR,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.READ_CALENDAR,
  },
  {
    name: "CREATE_NOTE",
    description: "Save a note on the user's phone. Works with no internet connection.",
    parameters: TOOL_PARAMETERS.CREATE_NOTE,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.CREATE_NOTE,
  },
  {
    name: "LIST_NOTES",
    description: "Read back the notes saved on the user's phone.",
    parameters: TOOL_PARAMETERS.LIST_NOTES,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.LIST_NOTES,
  },
  {
    name: "DELETE_NOTE",
    description:
      "Delete one of the user's notes. Deleting data is irreversible, so this ALWAYS asks the user to confirm first.",
    parameters: TOOL_PARAMETERS.DELETE_NOTE,
    confirm: "always",
    site: "device",
    label: TOOL_LABELS.DELETE_NOTE,
  },
  {
    name: "CREATE_TASK",
    description: "Add a task to the user's to-do list on their phone. Works with no internet connection.",
    parameters: TOOL_PARAMETERS.CREATE_TASK,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.CREATE_TASK,
  },
  {
    name: "LIST_TASKS",
    description: "Read back the user's open tasks.",
    parameters: TOOL_PARAMETERS.LIST_TASKS,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.LIST_TASKS,
  },
  {
    name: "COMPLETE_TASK",
    description: "Mark one of the user's tasks as done.",
    parameters: TOOL_PARAMETERS.COMPLETE_TASK,
    confirm: "model",
    site: "device",
    label: TOOL_LABELS.COMPLETE_TASK,
  },
  {
    name: "CLEAR_CONVERSATIONS",
    description:
      "Delete every conversation the user has. Irreversible, so this ALWAYS asks the user to confirm first.",
    parameters: TOOL_PARAMETERS.CLEAR_CONVERSATIONS,
    confirm: "always",
    site: "server",
    label: TOOL_LABELS.CLEAR_CONVERSATIONS,
  },
  {
    name: "CLEAR_MEMORY",
    description:
      "Delete everything MAX has been asked to remember. Irreversible, so this ALWAYS asks the user to confirm first.",
    parameters: TOOL_PARAMETERS.CLEAR_MEMORY,
    confirm: "always",
    site: "server",
    label: TOOL_LABELS.CLEAR_MEMORY,
  },
  {
    name: "SEARCH_CONVERSATIONS",
    description: "Search the user's past conversations for a phrase and return matching snippets.",
    parameters: TOOL_PARAMETERS.SEARCH_CONVERSATIONS,
    confirm: "model",
    site: "server",
    label: TOOL_LABELS.SEARCH_CONVERSATIONS,
  },
];

/** Fast name -> definition lookup. Built once at module load. */
export const TOOLS_BY_NAME: ReadonlyMap<ToolName, ToolDefinition> = new Map(TOOL_REGISTRY.map((t) => [t.name, t]));

export function getTool(name: string): ToolDefinition | undefined {
  return TOOLS_BY_NAME.get(name as ToolName);
}

/** True when the user must be asked to confirm, whatever the model claims. */
export function requiresConfirmation(name: ToolName): boolean {
  return TOOLS_BY_NAME.get(name)?.confirm === "always";
}

export function toolLabel(name: ToolName, parameters: Record<string, unknown>): string {
  return TOOL_LABELS[name](parameters);
}

/** Tool names that run on the user's phone (the rest run in the backend). */
export function deviceToolNames(): ToolName[] {
  return TOOL_REGISTRY.filter((t) => t.site === "device").map((t) => t.name);
}

