// AUTH REGRESSION SUITE - runs with no database and no network.
//
// Regression guard for the "Request Timed Out" login bug: the pg Pool was
// created without a connection timeout, so a database that could not be reached
// blocked every auth request (the rate-limit UPSERT, the user lookup, the
// session insert) until the OS gave up retrying the TCP handshake. That is
// ~21s on Windows and ~127s on Linux - both at or beyond the browser's 30s
// timeout - so the user saw "The request timed out." with no explanation.
//
// These tests do not need a live database: they assert the invariants that make
// that failure impossible again, plus the error mapping around it.
//
// A live end-to-end login is still required and is NOT covered here - run
// `npm run smoke:login` (scripts/login-smoke.mjs) against a running backend.

import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";

// The schema check and the per-request queries are stubbed so no database is
// touched. Everything else under test is pure logic.
vi.mock("@/db/migrate", () => ({
  ensureDatabaseSchema: vi.fn(async () => {}),
  schemaState: () => ({ ready: true, appliedAt: null, migrationsFolder: "drizzle", lastError: null }),
  REQUIRED_TABLES: [] as string[],
}));
vi.mock("@/lib/rateLimit", () => ({
  rateLimit: vi.fn(async () => ({ ok: true, remaining: 99, retryAfterSeconds: 1 })),
  windowStartFor: vi.fn(() => 0),
}));
vi.mock("@/lib/auth", () => ({
  getUserFromRequest: vi.fn(async () => null),
}));

import { pool } from "@/db";
import { ensureDatabaseSchema } from "@/db/migrate";
import { api, ApiError, DB_UNAVAILABLE, DB_NOT_INITIALIZED, isPoolTimeout, redact } from "@/lib/http";
import { REQUEST_TIMEOUT_MS, call } from "@/components/client";

// Exactly the shape pg produces when connectionTimeoutMillis fires: a bare
// Error with no `code`, wrapped the way drizzle/driver code wraps it.
const poolTimeoutError = () => {
  const driver: Error & { code?: string } = new Error("Connection terminated due to connection timeout");
  return Object.assign(new Error("Failed query"), { cause: driver });
};

const loginRequest = () =>
  new Request("https://max.test/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: "user@example.com", password: "password123" }),
  });

let spy: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  vi.mocked(ensureDatabaseSchema).mockReset().mockResolvedValue(undefined);
  // The error branch logs a structured line; silence it so test output stays clean.
  spy = vi.spyOn(console, "error").mockImplementation(() => {});
});
afterEach(() => spy.mockRestore());

describe("database timeouts (root cause of 'Request Timed Out')", () => {
  it("sets a connection timeout on the pool", () => {
    // Without this, pg arms NO timer and the connect only ends when the OS stops
    // retrying the SYN (~21s Windows / ~127s Linux).
    expect(pool.options.connectionTimeoutMillis).toBeGreaterThan(0);
  });

  it("sets a statement and query timeout", () => {
    expect(pool.options.statement_timeout).toBeGreaterThan(0);
    expect(pool.options.query_timeout).toBeGreaterThan(0);
  });

  it("keeps every database timeout BELOW the client timeout", () => {
    // The invariant that guarantees the user gets a real reason instead of a
    // timeout: the server must always lose the race on purpose, never by hang.
    const { connectionTimeoutMillis, statement_timeout, query_timeout } = pool.options;
    for (const [name, value] of Object.entries({ connectionTimeoutMillis, statement_timeout, query_timeout })) {
      expect(Number(value), name).toBeGreaterThan(0);
      expect(Number(value), name).toBeLessThan(REQUEST_TIMEOUT_MS);
    }
  });
});

describe("pool timeout classification", () => {
  it("recognises pg's connection-timeout error even though it has no code", () => {
    expect(isPoolTimeout(poolTimeoutError())).toBe(true);
  });

  it("recognises the bare driver message", () => {
    expect(isPoolTimeout(new Error("Connection terminated due to connection timeout"))).toBe(true);
    expect(isPoolTimeout(new Error("timeout exceeded when trying to connect"))).toBe(true);
  });

  it("does not misclassify unrelated failures", () => {
    expect(isPoolTimeout(new Error('relation "public.users" does not exist'))).toBe(false);
    expect(isPoolTimeout(new Error("duplicate key value violates unique constraint"))).toBe(false);
    expect(isPoolTimeout(null)).toBe(false);
  });
});


describe("login fails fast and says why", () => {
  it("returns 503 database_unavailable when the database cannot be reached", async () => {
    vi.mocked(ensureDatabaseSchema).mockRejectedValueOnce(poolTimeoutError());
    const handler = api({ auth: false, authBucket: "login" }, async () => new Response("never reached"));
    const res = await handler(loginRequest());
    const body = await res.json();
    expect(res.status).toBe(503);
    expect(body.error.code).toBe("database_unavailable");
    expect(body.error.message).toBe(DB_UNAVAILABLE);
  });

  it("still reports database_not_initialized when the schema is genuinely missing", async () => {
    const err: Error & { code?: string } = new Error('relation "public.users" does not exist');
    err.code = "42P01";
    vi.mocked(ensureDatabaseSchema).mockRejectedValueOnce(err);
    const handler = api({ auth: false, authBucket: "login" }, async () => new Response("never reached"));
    const res = await handler(loginRequest());
    const body = await res.json();
    expect(res.status).toBe(503);
    expect(body.error.code).toBe("database_not_initialized");
    expect(body.error.message).toBe(DB_NOT_INITIALIZED);
  });

  it("classifies a timeout raised inside the route handler as 503, not a 500", async () => {
    const handler = api({ auth: false, authBucket: "login" }, async () => {
      throw poolTimeoutError();
    });
    const res = await handler(loginRequest());
    const body = await res.json();
    expect(res.status).toBe(503);
    expect(body.error.code).toBe("database_unavailable");
  });

  it("keeps invalid credentials a clean 401 (no regression)", async () => {
    const handler = api({ auth: false, authBucket: "login" }, async () => {
      throw new ApiError(401, "invalid_credentials", "Incorrect email or password.");
    });
    const res = await handler(loginRequest());
    const body = await res.json();
    expect(res.status).toBe(401);
    expect(body.error.message).toBe("Incorrect email or password.");
  });

  it("uses two different messages for 'unreachable' and 'not set up'", async () => {
    // Telling a user to wait for a schema that can never be applied is what made
    // this failure feel unfixable.
    expect(DB_UNAVAILABLE).not.toBe(DB_NOT_INITIALIZED);
    expect(DB_UNAVAILABLE).toMatch(/database/i);
  });
});

describe("client error mapping", () => {
  const setOnLine = (v: boolean) =>
    Object.defineProperty(globalThis, "navigator", { value: { onLine: v }, configurable: true });

  afterEach(() => vi.unstubAllGlobals());

  it("shows the real database message on 503 instead of the AI wording", async () => {
    setOnLine(true);
    vi.stubGlobal(
      "fetch",
      async () =>
        new Response(JSON.stringify({ error: { code: "database_unavailable", message: DB_UNAVAILABLE } }), {
          status: 503,
        }),
    );
    await expect(call("/api/auth/login", { method: "POST", json: {}, quiet401: true })).rejects.toThrow(DB_UNAVAILABLE);
  });

  it("still reports a genuine client-side timeout distinctly", async () => {
    setOnLine(true);
    vi.stubGlobal("fetch", async (_url: unknown, opts: { signal?: AbortSignal }) => {
      return new Promise((_res, rej) => {
        opts.signal?.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError")));
      });
    });
    await expect(call("/api/auth/login", { method: "POST", json: {} })).rejects.toThrow(
      "The request timed out. Please try again.",
    );
  }, 40000);
});

describe("logging never leaks credentials", () => {
  it("redacts a postgres connection string", () => {
    const out = redact("could not connect to postgresql://user:hunter2@db.internal:5432/app_db");
    expect(out).not.toContain("hunter2");
    expect(out).toContain("[redacted]");
  });

  it("redacts passwords embedded in driver messages", () => {
    expect(redact("insert into users (password) values ($1) password=hunter2")).not.toContain("hunter2");
  });
});

