// Executes an ALREADY VALIDATED action in the browser, and reports the TRUTH.
// A browser cannot do everything an Android phone can, so many actions return
// "unsupported" instead of pretending to work.
export type ActionResult = { status: "completed" | "prepared" | "unsupported" | "failed"; message: string };

const WEB_APPS: Record<string, string> = {
  youtube: "https://www.youtube.com",
  spotify: "https://open.spotify.com",
  gmail: "https://mail.google.com",
  maps: "https://www.google.com/maps",
  "google maps": "https://www.google.com/maps",
  whatsapp: "https://web.whatsapp.com",
  instagram: "https://www.instagram.com",
  facebook: "https://www.facebook.com",
  twitter: "https://x.com",
  x: "https://x.com",
  netflix: "https://www.netflix.com",
  chrome: "https://www.google.com",
  google: "https://www.google.com",
  reddit: "https://www.reddit.com",
  linkedin: "https://www.linkedin.com",
};

const isNumber = (s: string) => /^\+?[\d\s-]{5,}$/.test(s);
const digits = (s: string) => s.replace(/[^\d+]/g, "");

function open(url: string): ActionResult {
  const w = window.open(url, "_blank", "noopener,noreferrer");
  return w ? { status: "completed", message: "Opened in a new tab." } : { status: "failed", message: "Your browser blocked the new tab. Allow pop-ups and try again." };
}

function link(url: string, message: string): ActionResult {
  window.location.href = url;
  return { status: "prepared", message };
}

export function runWebAction(action: string, p: Record<string, unknown>, speak?: (t: string) => void): ActionResult {
  const s = (k: string) => (typeof p[k] === "string" ? (p[k] as string) : "");
  switch (action) {
    case "OPEN_APP": {
      const url = WEB_APPS[s("appName").toLowerCase()];
      return url ? open(url) : { status: "unsupported", message: `I can't open ${s("appName")} from a browser. The Android app can open installed apps.` };
    }
    case "OPEN_BROWSER":
      return open(s("url") || `https://www.google.com/search?q=${encodeURIComponent(s("query"))}`);
    case "OPEN_MAPS":
      return open(`https://www.google.com/maps/search/${encodeURIComponent(s("query"))}`);
    case "NAVIGATE":
      return open(`https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(s("destination"))}`);
    case "OPEN_DIALER":
      return link(`tel:${digits(s("number"))}`, "Opened your dialer. No call was placed.");
    case "CALL_CONTACT":
      return isNumber(s("contactName"))
        ? link(`tel:${digits(s("contactName"))}`, "Opened your dialer. No call was placed.")
        : { status: "unsupported", message: "A browser can't look up your contacts. Use the Android app to call by name." };
    case "SEND_SMS":
      return isNumber(s("contactName"))
        ? link(`sms:${digits(s("contactName"))}?body=${encodeURIComponent(s("message"))}`, "Opened your messaging app with the text filled in. Nothing was sent yet.")
        : { status: "unsupported", message: "A browser can't look up your contacts. Use the Android app to message by name." };
    case "OPEN_WHATSAPP": {
      if (isNumber(s("contactName"))) {
        return open(`https://wa.me/${digits(s("contactName")).replace("+", "")}${s("message") ? `?text=${encodeURIComponent(s("message"))}` : ""}`).status === "completed"
          ? { status: "prepared", message: s("message") ? "Opened WhatsApp with your message filled in. It was NOT sent." : "Opened WhatsApp." }
          : { status: "failed", message: "Your browser blocked the new tab." };
      }
      const r = open(s("message") ? `https://wa.me/?text=${encodeURIComponent(s("message"))}` : "https://web.whatsapp.com");
      return r.status === "completed" && s("message") ? { status: "prepared", message: "Opened WhatsApp so you can choose who to send it to. It was NOT sent." } : r;
    }
    case "SET_TIMER": {
      const secs = Number(p.seconds);
      if (secs > 6 * 3600) return { status: "unsupported", message: "Browser timers only work while this tab stays open, so I keep them under 6 hours." };
      setTimeout(() => {
        speak?.("Your timer is done.");
        alert("MAX: your timer is done.");
      }, secs * 1000);
      return { status: "completed", message: "Timer started in this tab. Keep the tab open; the Android app uses a real system timer." };
    }
    case "CREATE_REMINDER":
      return { status: "unsupported", message: "Browsers can't create system reminders. I'll do this in the Android app." };
    default:
      return { status: "unsupported", message: "This needs the MAX Android app. Browsers can't control the device this way." };
  }
}
