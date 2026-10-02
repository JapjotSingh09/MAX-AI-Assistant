import { validateIntent, type CommandIntent } from "@/lib/commands/intent";

// LOCAL FIRST, CLOUD WHEN NEEDED.
// Simple deterministic commands are recognised here with plain rules, so they are
// instant, free, and do not need an AI call or the internet.
export type ParsedCommand =
  | { kind: "intent"; intent: CommandIntent }
  /** "remember that ..." -> store a memory. */
  | { kind: "memory"; text: string }
  /** "what did I ask you to remember?" -> list this user's memories. */
  | { kind: "memory_list" }
  /** "forget that ..." -> delete matching memories of this user only. */
  | { kind: "memory_forget"; query: string };

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
  eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17,
  eighteen: 18, nineteen: 19, twenty: 20, thirty: 30, forty: 40, fourty: 40, fifty: 50,
  sixty: 60, seventy: 70, eighty: 80, ninety: 90, hundred: 100,
};

// "one and a half hours" / "an hour and a half": the unit has already been
// matched separately, so this pattern only has to understand "<count> and a <fraction>".
const COMPOUND = /^(\w+)\s+and\s+(?:an?\s+)?(half|quarter)$/i;

function toNumber(word: string) {
  const n = Number(word);
  return Number.isFinite(n) ? n : NUMBER_WORDS[word.toLowerCase()];
}

/** "half"/"quarter" fractions, so "90 and a half minutes" works too. */
const FRACTION: Record<string, number> = { half: 0.5, quarter: 0.25 };

/** Multiplies a count by a unit, or null when either part is unknown. */
function durationSeconds(count: number | undefined, unit: string): number | null {
  if (count === undefined || !Number.isFinite(count)) return null;
  const u = unit.toLowerCase();
  const mult = u.startsWith("h") ? 3600 : u.startsWith("m") ? 60 : 1;
  return Math.round(count * mult);
}

/**
 * A spoken time phrase, e.g. "tomorrow at 9 am", "at 7", "tonight 8:30 pm".
 * Group 1 is the whole phrase (used as the reminder's `when`), and the pattern
 * is reused to strip it out of the reminder text.
 */
const TIME_PHRASE = /\b((?:today |tomorrow |tonight )?(?:at )?\d{1,2}(?::\d{2})?\s*(?:am|pm|a\.m\.|p\.m\.)?)\b/i;

// Builds a validated intent, or null when the tool or its parameters are wrong.
// `parameters` defaults to `{}` so parameterless tools read cleanly.
const make = (action: string, parameters: Record<string, unknown> = {}): ParsedCommand | null => {
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

  // Memory listing: "what did I ask you to remember", "what do you remember about me".
  // Tested against `t`, which no longer carries the "?" that normalize() strips.
  if (
    /^(what|which)\b.*\b(remember|memor(?:y|ies))\b/i.test(t) ||
    /^(what do you remember|show (?:me |my )?memor(?:y|ies)|list (?:my )?memor(?:y|ies)|recall (?:my )?memor(?:y|ies))\b/i.test(t)
  ) {
    return { kind: "memory_list" };
  }

  // Memory deletion: "forget that my exam is on Monday".
  // Only a leading "forget ..." is a delete command, so an ordinary sentence
  // that happens to contain the word "forget" still goes to the AI.
  if ((m = t.match(/^forget (?:that )?(.{3,300})$/i))) return { kind: "memory_forget", query: cap(m[1]) };
  if (/^(?:forget|delete|clear) (?:all |everything |every )?(?:of )?(?:my |your )?(?:memor(?:y|ies)|saved things)\b/i.test(t)) {
    return { kind: "memory_forget", query: "" };
  }

  // Flashlight
  if ((m = lower.match(/^(?:turn |switch )?(on|off) (?:the )?(?:flash ?light|torch)$/))) return make("TOGGLE_FLASHLIGHT", { state: m[1] });
  if ((m = lower.match(/^(?:turn |switch )?(?:the )?(?:flash ?light|torch) (on|off)$/))) return make("TOGGLE_FLASHLIGHT", { state: m[1] });
  if (/^(?:toggle )?(?:the )?(?:flash ?light|torch)$/.test(lower)) return make("TOGGLE_FLASHLIGHT", { state: "toggle" });

  // Timer: "set a timer for 10 minutes", "start a timer for ninety minutes",
  // "remind me in an hour and a half".
  if ((m = lower.match(/^(?:set |start )?(?:a )?timer (?:for )?(\w+) (second|sec|minute|min|hour|hr)s?$/))) {
    const secs = durationSeconds(toNumber(m[1]), m[2]);
    if (secs) return make("SET_TIMER", { seconds: secs });
  }
  if ((m = t.match(/^(?:set |start )?(?:a )?timer (?:for )?(.{2,40}?)(second|sec|minute|min|hour|hr)s?$/i))) {
    const phrase = m[1].trim();
    const unit = m[2].toLowerCase();
    // "one and a half hours" -> whole + fraction.
    const compound = phrase.match(COMPOUND);
    if (compound) {
      const whole = toNumber(compound[1]);
      const frac = FRACTION[compound[2].toLowerCase()];
      if (whole !== undefined && frac !== undefined) {
        const secs = durationSeconds(whole + frac, unit);
        if (secs) return make("SET_TIMER", { seconds: secs });
      }
    }
    const plain = durationSeconds(toNumber(phrase), unit);
    if (plain) return make("SET_TIMER", { seconds: plain });
  }

  // Alarm: "set an alarm for 7 AM", "alarm at 6:30 pm", "wake me up at 7".
  // The optional day prefix is what makes the spoken "for tomorrow at 9 am" work.
  if ((m = lower.match(/^(?:set |create )?(?:an? )?(?:alarm|wake me up) (?:for |at )?(?:(?:today|tomorrow|tonight) )?(?:at )?(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?$/))) {
    let hour = Number(m[1]);
    const minute = m[2] ? Number(m[2]) : 0;
    const mer = m[3]?.replace(/\./g, "");
    if (mer === "pm" && hour < 12) hour += 12;
    if (mer === "am" && hour === 12) hour = 0;
    return make("SET_ALARM", { hour, minute });
  }

  // Reminder: "remind me to call mom tomorrow", "remind me tomorrow at 9 AM to
  // call dad". The time phrase is lifted out into `when` so the reminder text
  // stays readable. TIME_PHRASE is reused for both steps, so the text removed
  // from `body` is exactly the text that was captured, whatever the casing.
  if ((m = t.match(/^(?:create a |set a |add a )?remind(?:er)?(?: me)?(?: to| for| about)? (.{2,200})$/i))) {
    let body = cap(m[1]);
    const timePart = body.match(TIME_PHRASE)?.[1];
    // Lower-cased so "9 AM" and "9 am" give the same `when` value.
    const when = timePart?.toLowerCase() ?? body.match(/\b(tomorrow|tonight|today|next week)\b/i)?.[1]?.toLowerCase();
    if (timePart) {
      const withoutTime = cap(body.replace(TIME_PHRASE, " "));
      if (withoutTime) body = withoutTime;
    }
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

  // Browser. NOTE: a bare "search for X" is a WEB search, not a page open, so
  // that rule has to run first - both start with the word "search".
  if ((m = t.match(/^search (?:for )?(.{2,200})$/i)) && !/near me|nearby|around me|on (?:google )?maps/i.test(m[1])) {
    return make("WEB_SEARCH", { query: cap(m[1]) });
  }
  if ((m = t.match(/^(?:open|go to|visit) ((?:https?:\/\/)?[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:\/\S*)?)$/i))) {
    const url = /^https?:\/\//i.test(m[1]) ? m[1] : `https://${m[1]}`;
    return make("OPEN_BROWSER", { url });
  }
  if ((m = t.match(/^(?:google|search(?: the web| online)?(?: for)?|look up) (.{2,200})$/i))) return make("OPEN_BROWSER", { query: cap(m[1]) });

  // --- Notes (kept on the phone, work offline) --------------------------
  // "create a note saying milk", "note: buy milk", "write down ..."
  if ((m = t.match(/^(?:create|make|add|write|save)(?: a| an)? note (?:saying |that says |with |about |: )(.{1,2000})$/i))) {
    return make("CREATE_NOTE", { title: cap(m[1]).slice(0, 80), content: cap(m[1]) });
  }
  if ((m = t.match(/^(?:note|write down)(?::| that| to| the)(.{1,2000})$/i))) {
    return make("CREATE_NOTE", { title: cap(m[1]).trim().slice(0, 80), content: cap(m[1]).trim() });
  }
  // "show my notes", "what are my notes"
  if (/^(?:show|list|read|what are|what's|what is) (?:me )?(?:my |the )?notes\b/i.test(t)) return make("LIST_NOTES");
  // "delete the note about milk" -> the registry makes this always confirm.
  if ((m = t.match(/^(?:delete|remove) (?:the |my |a )?note (?:about |called |named |saying |with |: )(.{1,80})$/i))) {
    return make("DELETE_NOTE", { query: cap(m[1]) });
  }

  // --- Tasks (kept on the phone, work offline) --------------------------
  if ((m = t.match(/^(?:create|add|make)? ?(?:a )?task (?:to |saying |that says |called |: )(.{1,120})$/i))) {
    const body = cap(m[1]);
    const due = body.match(/\b(today|tomorrow|tonight|next week|on \w+day|by \w+day)\b/i)?.[1];
    // Strip the "due" phrase out of the title so the task itself reads cleanly.
    const title = cap(body.replace(new RegExp(due ?? "$^", "i"), "")).slice(0, 120);
    if (title) return make("CREATE_TASK", { title, ...(due ? { due } : {}) });
  }
  if ((m = t.match(/^(?:add|create) (?:this |the )?(?:to my |a )?(?:todo|to-do)(?: list)? (?:task )?(?:to |: )?(.{1,120})$/i))) {
    if (m[1]) return make("CREATE_TASK", { title: cap(m[1]).slice(0, 120) });
  }
  if (/^(?:show|list|read|what are|what's|what is) (?:me )?(?:my |the |open )?(?:tasks|todos|to-?dos)\b/i.test(t)) {
    return make("LIST_TASKS");
  }
  if ((m = t.match(/^(?:mark|complete|finish|check off)(?: the)? (?:task |todo )?(?:about |called |named |: )?(.{1,80})$/i))) {
    if (!/^(?:off|done)$/i.test(cap(m[1]))) return make("COMPLETE_TASK", { query: cap(m[1]) });
  }

  // --- Search, weather, calendar, bluetooth, share ----------------------
  if (/weather/i.test(t)) {
    const loc = t.match(/^(?:what(?:'s| is) the |open |show )?weather(?: like)?(?: (?:in|at|for))? ?(.{2,80})?$/i)?.[1]?.trim();
    return make("OPEN_WEATHER", loc ? { location: cap(loc) } : {});
  }
  if (/^(?:open |show |what(?:'s| is) on )?(?:my |the )?(?:calendar|agenda|schedule)(?: for today)?$/i.test(t)) {
    return make("READ_CALENDAR");
  }
  if ((m = lower.match(/^(?:turn |switch )?(on|off|toggle)? ?bluetooth$/))) {
    return make("SET_BLUETOOTH", { state: m[1] ?? "toggle" });
  }
  if ((m = t.match(/^share (?:this )?(.{2,1000})$/i))) return make("SHARE_TEXT", { text: cap(m[1]) });

  // --- Destructive whole-store clears: the registry always confirms these -
  // `(?:all |my |the )*` allows any order of the optional qualifiers, so both
  // "clear conversations" and "clear all my conversations" match.
  if (/^(?:clear|delete|erase) (?:(?:all|my|the|of|our)\s+)*(?:conversation|chat)(?:s| history)?$/i.test(t)) {
    return make("CLEAR_CONVERSATIONS");
  }

  // Open an app: "open YouTube" (keep last so specific rules win)
  if ((m = t.match(/^(?:open|launch|start) (.{2,40})$/i))) {
    const app = cap(m[1].replace(/^the /i, "").replace(/ app$/i, ""));
    // A multi-sentence request is probably not just an app name: let the AI handle it.
    if (app.split(" ").length <= 3) return make("OPEN_APP", { appName: app });
  }

  return null;
}
