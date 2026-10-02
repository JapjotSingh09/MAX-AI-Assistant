// Verifies the web auth error mapping without needing a database.
// Mocks global fetch + navigator.onLine and exercises src/components/client.ts.
import { describe, expect, it, vi, beforeEach } from "vitest";
import { call } from "@/components/client";

const setOnLine = (v: boolean) => {
  Object.defineProperty(globalThis, "navigator", { value: { onLine: v }, configurable: true });
};

beforeEach(() => {
  vi.unstubAllGlobals();
  setOnLine(true);
});

describe("auth error mapping (offline root-cause fix)", () => {
  it("shows the real backend message on 409 (email taken), not offline", async () => {
    vi.stubGlobal("fetch", async () =>
      new Response(JSON.stringify({ error: { code: "email_taken", message: "An account with this email already exists. Try signing in." } }), { status: 409 }),
    );
    await expect(call("/api/auth/signup", { method: "POST", json: {} })).rejects.toThrow("An account with this email already exists. Try signing in.");
  });

  it("shows the real backend message on 401 (wrong password), not offline", async () => {
    vi.stubGlobal("fetch", async () =>
      new Response(JSON.stringify({ error: { code: "invalid_credentials", message: "Incorrect email or password." } }), { status: 401 }),
    );
    await expect(call("/api/auth/login", { method: "POST", json: {}, quiet401: true })).rejects.toThrow("Incorrect email or password.");
  });

  it("keeps a real offline message when the browser reports offline", async () => {
    setOnLine(false);
    vi.stubGlobal("fetch", async () => { throw new TypeError("fetch failed"); });
    await expect(call("/api/auth/login", { method: "POST", json: {} })).rejects.toThrow("You're offline.");
  });

  it("reports server-unreachable (not offline) when online but fetch throws (server down/CORS/DNS)", async () => {
    setOnLine(true);
    vi.stubGlobal("fetch", async () => { throw new TypeError("fetch failed"); });
    await expect(call("/api/auth/signup", { method: "POST", json: {} })).rejects.toThrow("Can't reach the MAX server. Check your connection and try again.");
  });

  it("reports a timeout distinctly instead of offline", async () => {
    setOnLine(true);
    vi.stubGlobal("fetch", async (_url: unknown, opts: { signal?: AbortSignal }) => {
      return new Promise((_res, rej) => {
        opts.signal?.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError")));
      });
    });
    await expect(call("/api/auth/login", { method: "POST", json: {} })).rejects.toThrow("The request timed out. Please try again.");
  }, 40000);
});
