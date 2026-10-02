import { validateIntent, type CommandIntent } from "@/lib/commands/intent";

// LOCAL FIRST, CLOUD WHEN NEEDED.
// Simple deterministic commands are recognised here with plain rules, so they are
// instant, free, and do not need an AI call or the internet.
export type ParsedCommand =
  | { kind: "intent"; intent: CommandIntent }
  | { kind: "memory"; text: string };

// Normalisation: lowercase, strip polite filler and trailing punctuation.
export function normalize(input: string) {
  return input
    .trim()
    .replace(/[.!?]+$/g, "")
    .replace(/^(hey |hi |ok |okay )?max[, ]+/i, "")
    .replace(/^(please |can you |could you |would you )+/i, "")
    .replace(/\s+please$/i, "")
    .replace(/\s+/g, " ")
    .trim();
}

const NUMBER_WORDS: Record<string, number> = {
  a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10,
  fifteen: 15, twenty: 20, thirty: 30, forty: 40, sixty: 60,
};

function toNumber(word: string) {
  const n = Number(word);
  return Number.isFinite(n) ? n : NUMBER_WORDS[word.toLowerCase()];
}

const make = (action: string, parameters: Record<string, unknown>): ParsedCommand | null => {
  const v = validateIntent({ action, parameters });
  return v.ok ? { kind: "intent", intent: v.intent } : null;
};

const cap = (s: string) => s.trim();

export function parseCommand(input: string): ParsedCommand | null {
  const raw = input.trim();
  const t = normalize(raw);
  const lower = t.toLowerCase();
  let m: RegExpMatchArray | null;

  // Memory: "remember that my exam is on Monday"
  if ((m = t.match(/^remember(?: that)? (.{3,300})$/i))) return { kind: "memory", text: cap(m[1]) };

  // Flashlight
  if ((m = lower.match(/^(?:turn |switch )?(on|off) (?:the )?(?:flash ?light|torch)$/))) return make("TOGGLE_FLASHLIGHT", { state: m[1] });
  if ((m = lower.match(/^(?:turn |switch )?(?:the )?(?:flash ?light|torch) (on|off)$/))) return make("TOGGLE_FLASHLIGHT", { state: m[1] });
  if (/^(?:toggle )?(?:the )?(?:flash ?light|torch)$/.test(lower)) return make("TOGGLE_FLASHLIGHT", { state: "toggle" });

  // Timer: "set a timer for 10 minutes"
  if ((m = lower.match(/^(?:set |start )?(?:a )?timer (?:for )?(\w+) (second|sec|minute|min|hour|hr)s?$/))) {
    const n = toNumber(m[1]);
    if (n) {
      const mult = m[2].startsWith("h") ? 3600 : m[2].startsWith("m") ? 60 : 1;
      return make("SET_TIMER", { seconds: n * mult });
    }
  }

  // Alarm: "set an alarm for 7 AM", "alarm at 6:30 pm", "wake me up at 7"
  if ((m = lower.match(/^(?:set |create )?(?:an? )?(?:alarm|wake me up) (?:for |at )(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?$/))) {
    let hour = Number(m[1]);
    const minute = m[2] ? Number(m[2]) : 0;
    const mer = m[3]?.replace(/\./g, "");
    if (mer === "pm" && hour < 12) hour += 12;
    if (mer === "am" && hour === 12) hour = 0;
    return make("SET_ALARM", { hour, minute });
  }

  // Reminder: "remind me to call mom tomorrow", "create a reminder for tomorrow"
  if ((m = t.match(/^(?:create a |set a |add a )?remind(?:er)?(?: me)?(?: to| for| about)? (.{2,200})$/i))) {
    const body = cap(m[1]);
    const when = body.match(/\b(tomorrow|tonight|today|next week|at \d{1,2}(?::\d{2})? ?(?:am|pm)?)\b/i)?.[1];
    return make("CREATE_REMINDER", { text: body, ...(when ? { when } : {}) });
  }

  // Camera, notifications
  if (/^(?:open |launch |start )(?:the )?camera$/.test(lower) || lower === "take a photo") return make("OPEN_CAMERA", {});
  if (/(?:what|which) notifications (?:did i miss|do i have)|^show (?:my )?notifications$|^(?:read|check) (?:my )?notifications$/.test(lower)) {
    return make("SHOW_NOTIFICATIONS", {});
  }

  // Volume
  if (/^(?:turn |volume )?(?:the )?volume up$|^(?:increase|raise) (?:the )?volume$/.test(lower)) return make("ADJUST_VOLUME", { direction: "up" });
  if (/^(?:turn |volume )?(?:the )?volume down$|^(?:decrease|lower|reduce) (?:the )?volume$/.test(lower)) return make("ADJUST_VOLUME", { direction: "down" });
  if (/^(?:mute|silence)(?: (?:the )?(?:phone|volume|sound))?$/.test(lower)) return make("ADJUST_VOLUME", { direction: "mute" });

  // Settings
  if ((m = lower.match(/^open (?:the )?(?:(wi-?fi|bluetooth|sound|display|battery|location|apps?) )?settings$/))) {
    return make("OPEN_SETTINGS", m[1] ? { section: m[1].replace("-", "") } : {});
  }

  // WhatsApp
  if (/^(?:open|launch) whats ?app$/.test(lower)) return make("OPEN_WHATSAPP", {});
  if ((m = t.match(/^(?:whatsapp|message|text|send (?:a )?whatsapp(?: message)? to) (.+?) (?:on whatsapp )?(?:saying|that says|:) (.+)$/i)) && /whats/i.test(t)) {
    return make("OPEN_WHATSAPP", { contactName: cap(m[1]), message: cap(m[2]) });
  }

  // SMS: "send Rahul a message saying I'll reach in 10 minutes", "text Rahul saying hi"
  if (
    (m = t.match(/^send (.+?) (?:a |an )?(?:message|text|sms)(?: saying| that says| with| :|:)? (.+)$/i)) ||
    (m = t.match(/^(?:message|text|sms) (.+?) (?:saying|that says|:) (.+)$/i))
  ) {
    return make("SEND_SMS", { contactName: cap(m[1]), message: cap(m[2]) });
  }

  // Calls
  if ((m = lower.match(/^(?:call|dial|phone|ring) ([+\d][\d\s-]{4,})$/))) return make("OPEN_DIALER", { number: m[1].replace(/[\s-]/g, "") });
  if (/^(?:open (?:the )?(?:dialer|phone)|dial)$/.test(lower)) return make("OPEN_DIALER", {});
  if ((m = t.match(/^(?:call|phone|ring) (.{1,80})$/i))) return make("CALL_CONTACT", { contactName: cap(m[1]) });

  // Navigation / maps
  if ((m = t.match(/^(?:navigate|directions|drive|take me|go) (?:to |me to )(.{2,160})$/i))) return make("NAVIGATE", { destination: cap(m[1]) });
  if (/^(?:open|launch) (?:google )?maps$/.test(lower)) return make("OPEN_MAPS", {});
  if ((m = t.match(/^(?:find|search(?: for)?|show me|look for) (.{2,100}? (?:near me|nearby|around me))$/i))) return make("OPEN_MAPS", { query: cap(m[1]) });
  if ((m = t.match(/^(?:search|find) (?:maps |map )?(?:for )?(.{2,100}) (?:on|in) (?:google )?maps$/i))) return make("OPEN_MAPS", { query: cap(m[1]) });

  // Browser
  if ((m = t.match(/^(?:open|go to|visit) ((?:https?:\/\/)?[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:\/\S*)?)$/i))) {
    const url = /^https?:\/\//i.test(m[1]) ? m[1] : `https://${m[1]}`;
    return make("OPEN_BROWSER", { url });
  }
  if ((m = t.match(/^(?:google|search(?: the web| online)?(?: for)?|look up) (.{2,200})$/i))) return make("OPEN_BROWSER", { query: cap(m[1]) });

  // Open an app: "open YouTube" (keep last so specific rules win)
  if ((m = t.match(/^(?:open|launch|start) (.{2,40})$/i))) {
    const app = cap(m[1].replace(/^the /i, "").replace(/ app$/i, ""));
    // A multi-sentence request is probably not just an app name: let the AI handle it.
    if (app.split(" ").length <= 3) return make("OPEN_APP", { appName: app });
  }

  return null;
}
