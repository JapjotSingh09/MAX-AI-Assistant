import path from "node:path";
import { existsSync } from "node:fs";
import { sql } from "drizzle-orm";
import { migrate } from "drizzle-orm/node-postgres/migrator";
import { db } from "@/db";

// WHY this exists: the backend owns its own tables (users, sessions, auth_tokens,
// ...) and every route talks to them directly. If the database that DATABASE_URL
// points at has never had the schema applied — a brand-new Render Postgres, a
// Neon/Supabase database, or one where only `drizzle-kit push` was run on a
// laptop — then the FIRST query fails and every request (signup AND login)
// answers 500 with no usable clue.
//
// So the server applies and then VERIFIES its own migrations, automatically:
//   * once at startup (src/instrumentation.ts)
//   * and again on demand from the request wrapper and /api/health
// Nothing has to be run by hand on the server, which is what the Render free
// plan requires (no Shell, no manual `drizzle-kit migrate`).
//
// The SQL in drizzle/ is idempotent (CREATE TABLE IF NOT EXISTS plus guarded
// constraints) and applied files are recorded in drizzle.__drizzle_migrations,
// so this is a no-op (one cheap SELECT) on an up-to-date database and it never
// touches existing data.

// Every table the running code depends on. After applying migrations we check
// these actually exist, so `schema: "ready"` can never be a lie.
export const REQUIRED_TABLES = [
  "users",
  "sessions",
  "auth_tokens",
  "profiles",
  "user_preferences",
  "conversations",
  "messages",
  "assistant_actions",
  "automations",
  "activity_logs",
  "usage_events",
  "devices",
  "memories",
  "rate_limits",
] as const;

// A SQLSTATE is exactly five upper-case alphanumerics, e.g. 42P01. Driver
// errors (libuv codes like ECONNREFUSED) are the only other shape we see.
export function isSqlState(code: string | undefined): code is string {
  return Boolean(code) && /^[0-9A-Z]{5}$/.test(code as string);
}

// Walk the `cause` chain (drizzle wraps driver errors in DrizzleQueryError)
// looking for the most specific code we can find.
function findCode(err: unknown): string | undefined {
  let cur: unknown = err;
  for (let i = 0; i < 6 && cur; i++) {
    const code = (cur as { code?: unknown }).code;
    if (typeof code === "string" && code) return code;
    cur = (cur as { cause?: unknown }).cause;
  }
  return undefined;
}

/**
 * A schema failure with the driver's own code/SQLSTATE attached, so callers can
 * log the real reason instead of "Error". Never carries the connection string:
 * only the SQLSTATE and a fixed, hand-written description.
 */
export class SchemaError extends Error {
  readonly stage: "folder" | "migrate" | "verify";
  readonly code: string | undefined;
  readonly sqlState: string | undefined;

  constructor(stage: SchemaError["stage"], message: string, cause?: unknown) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = "SchemaError";
    this.stage = stage;
    this.code = findCode(cause);
    this.sqlState = isSqlState(this.code) ? this.code : undefined;
  }
}

// drizzle's migrator reads the folder from disk at runtime, so it only works if
// the folder actually exists next to the running process. Under `next build` /
// `next start` the working directory is the app root, but resolve a couple of
// fallbacks so a monorepo or a different start directory cannot break it.
export function resolveMigrationsFolder(): string | null {
  const candidates = [
    process.env.MIGRATIONS_FOLDER,
    path.join(process.cwd(), "drizzle"),
    path.join(process.cwd(), "src", "drizzle"),
    path.join(process.cwd(), "..", "drizzle"),
  ].filter((p): p is string => Boolean(p));

  for (const dir of candidates) {
    try {
      // The journal is what makes a directory a valid migrations folder.
      if (existsSync(path.join(dir, "meta", "_journal.json"))) return dir;
    } catch {
      // Unreadable candidate: keep looking.
    }
  }
  return null;
}

/** MAX tables that are still missing. Empty means the schema is complete. */
export async function verifySchema(): Promise<string[]> {
  const res = (await db.execute(
    sql`select table_name from information_schema.tables where table_schema = 'public'`,
  )) as { rows?: { table_name?: string }[] };
  const present = new Set((res?.rows ?? []).map((r) => r.table_name));
  return REQUIRED_TABLES.filter((t) => !present.has(t));
}

let ready: Promise<void> | null = null;
let appliedAt: string | null = null;
let lastFailure: SchemaError | null = null;
let lastFailureAt = 0;
// A permanently broken database (no CREATE privilege, wrong DSN) must not make
// every single request re-run and re-log a migration attempt. Short cooldown,
// then retry — a transient failure still heals on its own.
const RETRY_AFTER_MS = 10_000;

/**
 * Applies the migrations (idempotent) and verifies the result. Resolves only
 * when every table the app needs exists; otherwise rejects with a SchemaError
 * carrying the SQLSTATE.
 */
export function ensureDatabaseSchema(): Promise<void> {
  // Re-use the recent failure for a few seconds instead of hammering a database
  // that is known to be broken (wrong DSN, no CREATE privilege). A transient
  // failure still heals on its own, because the cooldown expires.
  if (!ready && lastFailure && Date.now() - lastFailureAt < RETRY_AFTER_MS) {
    return Promise.reject(lastFailure);
  }
  if (!ready) {
    const attempt = (async () => {
      const folder = resolveMigrationsFolder();
      if (!folder) {
        throw new SchemaError(
          "folder",
          "No Drizzle migrations folder found. Expected a 'drizzle/meta/_journal.json' next to the application.",
        );
      }
      try {
        await migrate(db, { migrationsFolder: folder });
      } catch (err) {
        throw new SchemaError("migrate", "Applying database migrations failed.", err);
      }
      const missing = await verifySchema();
      if (missing.length) {
        throw new SchemaError("verify", `Migration ran but MAX tables are still missing: ${missing.join(", ")}`);
      }
      appliedAt = new Date().toISOString();
      lastFailure = null;
    })().catch((err: unknown) => {
      ready = null;
      lastFailure = err instanceof SchemaError ? err : new SchemaError("migrate", "Applying database migrations failed.", err);
      lastFailureAt = Date.now();
      throw err;
    });
    ready = attempt;
  }
  return ready;
}

export type SchemaState = {
  /** True when migrations applied and every required table was verified. */
  ready: boolean;
  /** Set once a successful verification has happened in this process. */
  appliedAt: string | null;
  migrationsFolder: string | null;
  /** The most recent failure, so /api/health can report why without re-running. */
  lastError: SchemaError | null;
};

/** Diagnostics for /api/health. Performs no writes. */
export function schemaState(): SchemaState {
  return {
    ready: ready !== null,
    appliedAt,
    migrationsFolder: resolveMigrationsFolder(),
    lastError: lastFailure,
  };
}