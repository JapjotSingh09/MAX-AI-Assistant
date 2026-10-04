import { drizzle } from "drizzle-orm/node-postgres";
import { Pool } from "pg";

const databaseUrl = process.env.DATABASE_URL;

if (!databaseUrl) {
  throw new Error("DATABASE_URL is required");
}

// Hosted Postgres (Render, Neon, Supabase, ...) requires TLS, while a local
// dev database does not. Detect it from the URL so the SAME code works in both
// places with no extra env vars: localhost stays plain, everything else gets
// TLS in production (or whenever the URL asks for it with sslmode=require).
// rejectUnauthorized:false is needed because managed pools use shared certs
// that Node cannot verify against its built-in CA list.
const isLocalDb = databaseUrl.includes("@localhost") || databaseUrl.includes("@127.0.0.1");
const needsSsl =
  !isLocalDb && (process.env.NODE_ENV === "production" || databaseUrl.includes("sslmode=require"));

const int = (key: string, fallback: number) => {
  const v = Number(process.env[key]);
  return Number.isFinite(v) && v > 0 ? Math.floor(v) : fallback;
};

// WHY THESE EXIST - the cause of "Request Timed Out" during login.
//
// pg's Pool does NOT time out a connection unless connectionTimeoutMillis is
// set: without it there is no timer at all, so a database that accepts the TCP
// connection but never finishes the handshake (or silently drops the packets -
// a sleeping/suspended/expired free-tier Postgres, a firewall, a NAT timeout)
// blocks the awaiting request for as long as the OS keeps retrying the SYN.
// That is ~21s on Windows and ~127s on Linux, i.e. the production host.
//
// Every auth request touches the database before it can answer: the rate limiter
// UPSERT, the user lookup, the session insert. So one unreachable database made
// POST /api/auth/login hang far past the browser's 30s timeout, and the user saw
// "The request timed out." instead of any explanation.
//
// Failing fast turns that hang into a prompt, classified 503 ("MAX can't reach
// its database right now") which the existing error mapping already shows.
//
// Keep these comfortably BELOW the web client's 30s timeout
// (REQUEST_TIMEOUT_MS in src/components/client.ts) so the server always wins
// the race and the user gets the real reason.
const connectTimeoutMs = int("DB_CONNECT_TIMEOUT_MS", 8000);
// Server-side guard against a query that never returns (for example a lock wait).
const statementTimeoutMs = int("DB_STATEMENT_TIMEOUT_MS", 20000);
// Client-side backstop, slightly above statement_timeout so the server's own
// (more precise) error is the one that normally surfaces.
const queryTimeoutMs = int("DB_QUERY_TIMEOUT_MS", 25000);

const globalForDb = globalThis as typeof globalThis & {
  __arenaNextJsPostgresqlPool?: Pool;
};

export const pool =
  globalForDb.__arenaNextJsPostgresqlPool ??
  new Pool({
    connectionString: databaseUrl,
    ...(needsSsl ? { ssl: { rejectUnauthorized: false } } : {}),
    connectionTimeoutMillis: connectTimeoutMs,
    statement_timeout: statementTimeoutMs,
    query_timeout: queryTimeoutMs,
  });

if (process.env.NODE_ENV !== "production") {
  globalForDb.__arenaNextJsPostgresqlPool = pool;
}

// An idle client can be dropped by the server/network while it sits in the pool.
// Without this listener node-postgres throws an unhandled 'error' event and can
// take the whole process down; here it is logged (scrubbed) and the client is
// simply discarded so the next request opens a fresh connection.
pool.on("error", (err: Error) => {
  console.error(
    JSON.stringify({ level: "error", msg: "db.pool.idle_client_error", code: (err as { code?: string }).code, name: err.name }),
  );
});

export const db = drizzle(pool);
