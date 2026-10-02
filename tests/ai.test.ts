import { describe, expect, it, vi } from "vitest";
import { OpenAICompatibleProvider } from "@/lib/ai/openaiCompatible";
import { AIProviderRouter } from "@/lib/ai/router";
import { AIProviderError, AIUnavailableError, type AIProvider } from "@/lib/ai/types";
import { parseAIOutput } from "@/lib/assistant";
import { windowStartFor } from "@/lib/rateLimit";

const ok = (content: string) => new Response(JSON.stringify({ choices: [{ message: { content } }], usage: { total_tokens: 12 } }), { status: 200 });
const make = (fetchImpl: typeof fetch, maxRetries = 2) =>
  new OpenAICompatibleProvider({ name: "test", baseUrl: "http://x", apiKey: "k", model: "m", timeoutMs: 1000, maxRetries, fetchImpl, sleep: async () => {} });
const req = { messages: [{ role: "user" as const, content: "hi" }] };

describe("AIProvider", () => {
  it("returns content and token usage", async () => {
    const r = await make(vi.fn().mockResolvedValue(ok("hello")) as unknown as typeof fetch).chat(req);
    expect(r).toMatchObject({ content: "hello", tokensUsed: 12, provider: "test" });
  });
  it("retries transient 429/5xx with backoff, then succeeds", async () => {
    const f = vi.fn().mockResolvedValueOnce(new Response("", { status: 429 })).mockResolvedValueOnce(new Response("", { status: 503 })).mockResolvedValueOnce(ok("fine"));
    expect((await make(f as unknown as typeof fetch).chat(req)).content).toBe("fine");
    expect(f).toHaveBeenCalledTimes(3);
  });
  it("gives up after maxRetries (never retries forever)", async () => {
    const f = vi.fn().mockResolvedValue(new Response("", { status: 429 }));
    await expect(make(f as unknown as typeof fetch, 1).chat(req)).rejects.toMatchObject({ category: "rate_limited" });
    expect(f).toHaveBeenCalledTimes(2);
  });
  it("handles a provider timeout without retrying", async () => {
    const f = vi.fn().mockRejectedValue(Object.assign(new Error("t"), { name: "TimeoutError" }));
    await expect(make(f as unknown as typeof fetch).chat(req)).rejects.toMatchObject({ category: "timeout" });
    expect(f).toHaveBeenCalledTimes(1);
  });
  it("does not retry auth errors", async () => {
    const f = vi.fn().mockResolvedValue(new Response("", { status: 401 }));
    await expect(make(f as unknown as typeof fetch).chat(req)).rejects.toBeInstanceOf(AIProviderError);
    expect(f).toHaveBeenCalledTimes(1);
  });
});

describe("AIProviderRouter fallback", () => {
  const good: AIProvider = { name: "b", model: "m", chat: async () => ({ content: "ok", provider: "b", model: "m", tokensUsed: 1, latencyMs: 1 }) };
  const bad: AIProvider = { name: "a", model: "m", chat: async () => { throw new AIProviderError("x", false, "server"); } };
  it("uses the fallback when the primary fails", async () => {
    expect((await new AIProviderRouter([bad, good]).chat(req)).provider).toBe("b");
  });
  it("returns a graceful error when every provider fails", async () => {
    await expect(new AIProviderRouter([bad, bad]).chat(req)).rejects.toBeInstanceOf(AIUnavailableError);
  });
  it("reports not_configured with no providers", async () => {
    await expect(new AIProviderRouter([]).chat(req)).rejects.toMatchObject({ reason: "not_configured" });
  });
});

describe("AI output parsing", () => {
  it("parses JSON replies with an action", () => {
    const o = parseAIOutput('```json\n{"reply":"Opening","action":{"action":"OPEN_APP","parameters":{"appName":"Spotify"}}}\n```');
    expect(o.reply).toBe("Opening");
    expect(o.rawAction).toMatchObject({ action: "OPEN_APP" });
  });
  it("falls back to plain text", () => {
    expect(parseAIOutput("Just text")).toEqual({ reply: "Just text", rawAction: null });
  });
});

describe("Rate limit windows", () => {
  it("buckets timestamps into fixed windows", () => {
    expect(windowStartFor(61_000, 60)).toBe(60_000);
    expect(windowStartFor(119_999, 60)).toBe(60_000);
    expect(windowStartFor(120_000, 60)).toBe(120_000);
  });
});
