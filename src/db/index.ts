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

const globalForDb = globalThis as typeof globalThis & {
  __arenaNextJsPostgresqlPool?: Pool;
};

export const pool =
  globalForDb.__arenaNextJsPostgresqlPool ??
  new Pool({
    connectionString: databaseUrl,
    ...(needsSsl ? { ssl: { rejectUnauthorized: false } } : {}),
  });

if (process.env.NODE_ENV !== "production") {
  globalForDb.__arenaNextJsPostgresqlPool = pool;
}

export const db = drizzle(pool);
