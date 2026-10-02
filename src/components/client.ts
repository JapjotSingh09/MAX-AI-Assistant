// Small fetch wrapper for the browser. It turns every failure into a friendly message:
// raw backend/network errors are never shown to the user.
export class ApiFail extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}

let onUnauthorized: (() => void) | null = null;
export const setUnauthorizedHandler = (fn: (() => void) | null) => {
  onUnauthorized = fn;
};

const FRIENDLY: Record<number, string> = {
  401: "Your session has expired. Please sign in again.",
  429: "Too many requests. Please wait a moment and try again.",
  503: "MAX's AI service is temporarily unavailable.",
};

// Distinct from OFFLINE: the device has network access but the backend itself
// could not be reached (server down, wrong URL, CORS/DNS/HTTPS failure).
export const SERVER_UNREACHABLE = "Can't reach the MAX server. Check your connection and try again.";
const OFFLINE = "You're offline.";
const TIMEOUT = "The request timed out. Please try again.";

const REQUEST_TIMEOUT_MS = 30000;

// True only when the browser itself reports no network. Any other fetch
// failure (server down, CORS, DNS, HTTPS/mixed-content, refused connection)
// must NOT be reported as offline.
function browserReportsOffline(): boolean {
  return typeof navigator !== "undefined" && navigator.onLine === false;
}

function logDev(...args: unknown[]) {
  if (process.env.NODE_ENV !== "production") {
    // Visible in the browser devtools console and the Next.js dev overlay.
    // Never includes passwords/tokens: only path, status and error name.
    console.error("[MAX api]", ...args);
  }
}

export async function call<T = unknown>(path: string, init: RequestInit & { json?: unknown; quiet401?: boolean } = {}): Promise<T> {
  const { json, quiet401, ...rest } = init;
  let res: Response;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  // Preserve a caller-provided signal (e.g. React strict-mode cleanup) while
  // still enforcing our own timeout.
  const callerSignal = rest.signal as AbortSignal | undefined;
  if (callerSignal?.aborted) controller.abort(callerSignal.reason);
  else callerSignal?.addEventListener("abort", () => controller.abort(callerSignal.reason), { once: true });
  try {
    res = await fetch(path, {
      ...rest,
      signal: controller.signal,
      headers: { ...(json !== undefined ? { "Content-Type": "application/json" } : {}), ...rest.headers },
      body: json !== undefined ? JSON.stringify(json) : rest.body,
      credentials: "same-origin",
    });
  } catch (err) {
    const name = (err as Error)?.name;
    const detail = (err as Error)?.message;
    if (name === "AbortError" && !callerSignal?.aborted) {
      logDev("timeout", { path, timeoutMs: REQUEST_TIMEOUT_MS, error: name, detail });
      throw new ApiFail(TIMEOUT, 0);
    }
    if (name === "AbortError") throw err;
    // Diagnose the real cause: log the fetch error name (TypeError on CORS/DNS/
    // HTTPS failures) plus whether the browser thinks it is offline.
    logDev("network failure", { path, error: name, detail, onLine: typeof navigator !== "undefined" ? navigator.onLine : "unknown" });
    if (browserReportsOffline()) throw new ApiFail(OFFLINE, 0);
    throw new ApiFail(SERVER_UNREACHABLE, 0);
  } finally {
    clearTimeout(timer);
  }
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    if (res.status === 401 && !quiet401) onUnauthorized?.();
    // 429 always uses the standard message; other errors use the server's friendly text.
    const msg = res.status === 429 ? FRIENDLY[429] : data?.error?.message || FRIENDLY[res.status] || "Something went wrong. Please try again.";
    // Log backend rejections in dev so the exact failure (status + code) can be
    // diagnosed without exposing internals to the user.
    logDev("backend error", { path, status: res.status, code: data?.error?.code, message: data?.error?.message });
    throw new ApiFail(msg, res.status);
  }
  return data as T;
}

export const errMsg = (e: unknown) => (e instanceof ApiFail ? e.message : "Something went wrong. Please try again.");
