#!/usr/bin/env node
// Repeatable LOGIN SMOKE TEST against a running MAX backend.
//
// Run this after every change to this project before calling the task complete.
// It exercises the real HTTP auth flow end to end; unlike a build or a
// type-check, it proves a session actually works.
//
// Usage:
//   node scripts/login-smoke.mjs                        # defaults to http://localhost:3000
//   SMOKE_BASE_URL=https://your-app.onrender.com node scripts/login-smoke.mjs
//   SMOKE_EMAIL=me@example.com SMOKE_PASSWORD=... node scripts/login-smoke.mjs
//
// SAFETY: it only CREATES a throwaway account (unique email) and signs in with
// it. It never deletes, edits or reads any existing user's data, and it never
// prints passwords, tokens or cookies.
//
// Exit 0 = PASS, 1 = FAIL/BLOCKED. A "BLOCKED" result is an infrastructure
// problem and must be reported as NOT VERIFIED, never as a passing login test.

const BASE = (process.env.SMOKE_BASE_URL || "http://localhost:3000").replace(/\/+$/, "");
const CLIENT_TIMEOUT_MS = Number(process.env.SMOKE_TIMEOUT_MS || 30000);

let failures = 0;
let blocked = false;

const pass = (name, detail = "") => console.log(`  PASS  ${name}${detail ? ` — ${detail}` : ""}`);
const fail = (name, detail) => {
  failures++;
  console.log(`  FAIL  ${name} — ${detail}`);
};
const block = (name, detail) => {
  blocked = true;
  console.log(`  BLOCKED  ${name} — ${detail}`);
};

async function timedReq(path, { method = "GET", body, cookie } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), CLIENT_TIMEOUT_MS);
  const started = Date.now();
  try {
    const res = await fetch(`${BASE}${path}`, {
      method,
      headers: { "Content-Type": "application/json", ...(cookie ? { Cookie: cookie } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });
    const data = await res.json().catch(() => null);
    return { status: res.status, data, cookie: res.headers.get("set-cookie")?.split(";")[0], totalMs: Date.now() - started };
  } catch (err) {
    // AbortError === our own timer fired === the request hung past the timeout.
    const timedOut = err.name === "AbortError";
    return { timedOut, unreachable: !timedOut, name: err.name, totalMs: Date.now() - started };
  } finally {
    clearTimeout(timer);
  }
}

console.log(`MAX login smoke test -> ${BASE}`);
console.log(`Client timeout: ${CLIENT_TIMEOUT_MS}ms\n`);

console.log("[1] Backend reachable / database healthy");
const health = await timedReq("/api/health");
if (health.unreachable || health.timedOut) {
  console.log(`\nRESULT: BLOCKED — backend not reachable (${health.name} after ${health.totalMs}ms). This is NOT a pass.`);
  process.exit(1);
}
if (health.status !== 200) {
  console.log(`\nRESULT: BLOCKED — /api/health returned ${health.status}: ${brief(health.data)}. This is NOT a pass.`);
  process.exit(1);
}
pass("health", `database=${health.data.database} schema=${health.data.schema} (${health.totalMs}ms)`);
if (health.totalMs > CLIENT_TIMEOUT_MS) {
  fail("health latency", `${health.totalMs}ms exceeds the client timeout — the hang behind "Request Timed Out"`);
}

console.log("\n[2] Anonymous access is denied");
for (const p of ["/api/auth/me", "/api/profile", "/api/conversations", "/api/memories"]) {
  const r = await timedReq(p);
  if (r.status === 401) pass(`401 for ${p}`);
  else fail(`401 for ${p}`, `got ${r.status}`);
}

const usingExistingAccount = Boolean(process.env.SMOKE_EMAIL && process.env.SMOKE_PASSWORD);
const loginEmail = process.env.SMOKE_EMAIL || `login-smoke-${Date.now()}-${Math.floor(Math.random() * 1e6)}@test.dev`;
const loginPassword = process.env.SMOKE_PASSWORD || "SmokeTest-" + Math.random().toString(36).slice(2, 10) + "!";

console.log("\n[3] Obtain a usable account");
if (usingExistingAccount) {
  console.log(`  ....  using the authorized SMOKE_EMAIL account (${loginEmail})`);
} else {
  const su = await timedReq("/api/auth/signup", {
    method: "POST",
    body: { email: loginEmail, password: loginPassword, fullName: "Login Smoke" },
  });
  if (su.status === 201) pass("signup throwaway account", `${su.totalMs}ms`);
  else {
    console.log(`\nRESULT: BLOCKED — could not create a test account (${su.status}: ${brief(su.data)}). This is NOT a pass.`);
    process.exit(1);
  }
}

console.log("\n[4] Invalid credentials get a real error, never a timeout");
const bad = await timedReq("/api/auth/login", {
  method: "POST",
  body: { email: loginEmail, password: "definitely-not-the-password" },
});
if (bad.timedOut) {
  fail("invalid login does not hang", `client timed out after ${bad.totalMs}ms — login still hangs`);
} else if (bad.status === 401 && /incorrect email or password/i.test(bad.data?.error?.message || "")) {
  pass("invalid password -> 401 friendly message", `${bad.totalMs}ms`);
} else {
  fail("invalid password", `got ${bad.status}: ${brief(bad.data)}`);
}

console.log("\n[5] Valid credentials log in");
const login = await timedReq("/api/auth/login", { method: "POST", body: { email: loginEmail, password: loginPassword } });
if (login.timedOut) {
  fail("login succeeds", `CLIENT TIMED OUT after ${login.totalMs}ms — this IS the "Request Timed Out" bug`);
} else if (login.status === 200 && login.cookie) {
  pass("login -> 200 + session cookie", `${login.totalMs}ms`);
} else {
  fail("login succeeds", `got ${login.status}: ${brief(login.data)}`);
}

console.log("\n[6] Session restores from the cookie (new tab / after restart)");
const me = await timedReq("/api/auth/me", { cookie: login.cookie });
if (me.status === 200 && me.data?.user?.email === loginEmail) pass("session restored", `${me.totalMs}ms`);
else fail("session restore", `got ${me.status}: ${brief(me.data)}`);

console.log("\n[7] User data loads with that session");
const prof = await timedReq("/api/profile", { cookie: login.cookie });
if (prof.status === 200) pass("profile loads", `${prof.totalMs}ms`);
else fail("profile", `got ${prof.status}`);

console.log("\n[8] Logout invalidates the session");
const out = await timedReq("/api/auth/logout", { method: "POST", cookie: login.cookie });
if (out.status === 200) pass("logout -> 200");
else fail("logout", `got ${out.status}`);
const after = await timedReq("/api/auth/me", { cookie: login.cookie });
if (after.status === 401) pass("session rejected after logout");
else fail("session rejected after logout", `got ${after.status} — logout did not invalidate the session`);

console.log("\n[9] Can log in again after logout");
const again = await timedReq("/api/auth/login", { method: "POST", body: { email: loginEmail, password: loginPassword } });
if (again.status === 200 && again.cookie) pass("re-login -> 200", `${again.totalMs}ms`);
else fail("re-login", `got ${again.status}: ${brief(again.data)}`);

console.log(`\n${"-".repeat(62)}`);
if (failures === 0) {
  console.log("RESULT: PASS — real login verified against a live backend.");
  process.exit(0);
}
console.log(`RESULT: FAIL — ${failures} check(s) failed.`);
process.exit(1);


const brief = (v) => JSON.stringify(v)?.slice(0, 160);
