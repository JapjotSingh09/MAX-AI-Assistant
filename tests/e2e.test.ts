import { describe, expect, it } from "vitest";

// End-to-end API tests. They need a RUNNING server and database:
//   E2E_BASE_URL=http://localhost:3000 npx vitest run tests/e2e.test.ts
const BASE = process.env.E2E_BASE_URL;
const d = BASE ? describe : describe.skip;

async function req(path: string, opts: { method?: string; body?: unknown; cookie?: string } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method: opts.method ?? "GET",
    headers: { "Content-Type": "application/json", ...(opts.cookie ? { Cookie: opts.cookie } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => null);
  return { res, data, cookie: res.headers.get("set-cookie")?.split(";")[0] };
}

async function signup(tag: string) {
  const email = `${tag}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@test.dev`;
  const r = await req("/api/auth/signup", { method: "POST", body: { email, password: "password123", fullName: tag } });
  expect(r.res.status).toBe(201);
  return { email, cookie: r.cookie! };
}

d("MAX API", () => {
  it("health endpoint works", async () => {
    const r = await req("/api/health");
    expect(r.data.ok).toBe(true);
  });

  it("unauthenticated users cannot reach private endpoints", async () => {
    for (const p of ["/api/conversations", "/api/activity", "/api/automations", "/api/memories", "/api/profile", "/api/auth/me"]) {
      expect((await req(p)).res.status, p).toBe(401);
    }
    expect((await req("/api/chat", { method: "POST", body: { message: "hi" } })).res.status).toBe(401);
  });

  it("duplicate signup is rejected cleanly", async () => {
    const a = await signup("dup");
    const again = await req("/api/auth/signup", { method: "POST", body: { email: a.email, password: "password123" } });
    expect(again.res.status).toBe(409);
  });

  it("wrong password gives a generic 401", async () => {
    const a = await signup("login");
    const r = await req("/api/auth/login", { method: "POST", body: { email: a.email, password: "nope-nope" } });
    expect(r.res.status).toBe(401);
    expect(r.data.error.message).toBe("Incorrect email or password.");
  });

  it("user A cannot read or modify user B's data", async () => {
    const a = await signup("alice");
    const b = await signup("bob");

    // A creates data
    const chat = await req("/api/chat", { method: "POST", cookie: a.cookie, body: { message: "Open YouTube" } });
    expect(chat.res.status).toBe(200);
    const convId = chat.data.conversationId as string;
    const actionId = chat.data.action.id as string;
    const mem = await req("/api/memories", { method: "POST", cookie: a.cookie, body: { content: "alice secret" } });
    const auto = await req("/api/automations", {
      method: "POST",
      cookie: a.cookie,
      body: { name: "A", triggerType: "TIME", triggerConfig: { time: "08:00" }, actionType: "OPEN_APP", actionConfig: { appName: "Spotify" } },
    });
    expect(auto.res.status).toBe(201);

    // B cannot see A's conversation, messages, memory, automation, or action
    expect((await req(`/api/conversations/${convId}/messages`, { cookie: b.cookie })).res.status).toBe(404);
    expect((await req("/api/chat", { method: "POST", cookie: b.cookie, body: { conversationId: convId, message: "hi" } })).res.status).toBe(404);
    expect((await req(`/api/memories/${mem.data.id}`, { method: "PATCH", cookie: b.cookie, body: { content: "hacked" } })).res.status).toBe(404);
    expect((await req(`/api/memories/${mem.data.id}`, { method: "DELETE", cookie: b.cookie })).res.status).toBe(404);
    expect((await req(`/api/automations/${auto.data.id}`, { method: "PATCH", cookie: b.cookie, body: { enabled: false } })).res.status).toBe(404);
    expect((await req(`/api/automations/${auto.data.id}`, { method: "DELETE", cookie: b.cookie })).res.status).toBe(404);
    expect((await req(`/api/actions/${actionId}`, { method: "PATCH", cookie: b.cookie, body: { status: "completed" } })).res.status).toBe(404);
    expect((await req("/api/memories", { cookie: b.cookie })).data.items).toHaveLength(0);
    expect((await req("/api/conversations", { cookie: b.cookie })).data.items).toHaveLength(0);
    expect((await req("/api/automations", { cookie: b.cookie })).data.items).toHaveLength(0);
    const act = await req("/api/activity", { cookie: b.cookie });
    expect(JSON.stringify(act.data.items)).not.toContain("YouTube");

    // A's data is untouched
    expect((await req("/api/memories", { cookie: a.cookie })).data.items).toHaveLength(1);
  });

  it("rejects invalid actions in automations and in command validation", async () => {
    const a = await signup("inv");
    const bad = await req("/api/automations", { method: "POST", cookie: a.cookie, body: { name: "x", triggerType: "TIME", triggerConfig: { time: "08:00" }, actionType: "RUN_SHELL", actionConfig: {} } });
    expect(bad.res.status).toBe(400);
    const v = await req("/api/assistant/command", { method: "POST", cookie: a.cookie, body: { intent: { action: "RUN_SHELL", parameters: {} } } });
    expect(v.res.status).toBe(422);
  });

  it("action results can only be reported once, and are never faked", async () => {
    const a = await signup("act");
    const chat = await req("/api/chat", { method: "POST", cookie: a.cookie, body: { message: "Call Dad" } });
    expect(chat.data.action.status).toBe("awaiting_confirmation");
    const id = chat.data.action.id;
    expect((await req(`/api/actions/${id}`, { method: "PATCH", cookie: a.cookie, body: { status: "unsupported", message: "no contacts" } })).res.status).toBe(200);
    expect((await req(`/api/actions/${id}`, { method: "PATCH", cookie: a.cookie, body: { status: "completed" } })).res.status).toBe(409);
  });

  it("cursor pagination returns pages without overlap", async () => {
    const a = await signup("page");
    for (let i = 0; i < 5; i++) await req("/api/memories", { method: "POST", cookie: a.cookie, body: { content: `m${i}` } });
    for (let i = 0; i < 4; i++) await req("/api/chat", { method: "POST", cookie: a.cookie, body: { message: `turn on flashlight` } });
    const p1 = await req("/api/activity?limit=2", { cookie: a.cookie });
    expect(p1.data.items).toHaveLength(2);
    expect(p1.data.nextCursor).toBeTruthy();
    const p2 = await req(`/api/activity?limit=2&cursor=${p1.data.nextCursor}`, { cookie: a.cookie });
    const ids = new Set([...p1.data.items, ...p2.data.items].map((x: { id: string }) => x.id));
    expect(ids.size).toBe(p1.data.items.length + p2.data.items.length);
  });

  it("AI is unavailable gracefully when not configured, and rate limits return 429", async () => {
    const a = await signup("rl");
    const r = await req("/api/chat", { method: "POST", cookie: a.cookie, body: { message: "Explain black holes" } });
    expect(r.res.status).toBe(200);
    if (r.data.aiUnavailable) expect(r.data.assistantMessage.content).toBe("MAX's AI service is temporarily unavailable.");
    // Auth endpoints are rate limited per IP: hammer login until 429.
    let got429 = false;
    for (let i = 0; i < 80 && !got429; i++) {
      const x = await req("/api/auth/login", { method: "POST", body: { email: "nobody@test.dev", password: "wrongwrong" } });
      got429 = x.res.status === 429;
      if (got429) expect(x.data.error.message).toBe("Too many requests. Please wait a moment and try again.");
    }
    expect(got429).toBe(true);
  });
});
