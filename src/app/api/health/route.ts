import { db } from "@/db";
import { sql } from "drizzle-orm";
import { ensureDatabaseSchema, schemaState } from "@/db/migrate";
import { aiConfigured } from "@/lib/config";
import { describeDbError } from "@/lib/http";

export const dynamic = "force-dynamic";

// Service status plus a REAL schema check. Exposes booleans, table names and
// SQLSTATEs only — never DATABASE_URL, passwords or tokens.
//
// This is also what keeps the database self-initialising on the Render free
// plan (no Shell): every call applies the idempotent migrations in drizzle/ if
// they have not been applied yet, and then verifies that every table the app
// needs actually exists. It reports `schema: "ready"` only when that
// verification passes, and otherwise reports the SQLSTATE that stopped it.
export async function GET() {
  const started = Date.now();
  const ai = aiConfigured() ? "configured" : "not_configured";

  // 1. Can we reach Postgres at all?
  try {
    await db.execute(sql`select 1`);
  } catch (err) {
    console.error(JSON.stringify({ level: "error", msg: "db.connect.failed", ...describeDbError(err) }));
    return Response.json(
      {
        ok: false,
        service: "max-api",
        database: "down",
        schema: "unknown",
        ai,
        databaseError: describeDbError(err),
        hint: "DATABASE_URL could not be reached. Check that it is set, valid and allows connections from Render.",
      },
      { status: 500 },
    );
  }
  const dbLatencyMs = Date.now() - started;

  // 2. Apply (idempotently) and verify the schema. ensureDatabaseSchema() throws
  //    with the driver's SQLSTATE attached when it cannot finish.
  try {
    await ensureDatabaseSchema();
  } catch (err) {
    const detail = describeDbError(err);
    console.error(JSON.stringify({ level: "error", msg: "db.schema.failed", ...detail }));
    return Response.json(
      {
        ok: false,
        service: "max-api",
        database: "up",
        schema: "failed",
        ai,
        dbLatencyMs,
        // A boolean, not the absolute server path: the folder is only useful as
        // "was it found", and a public endpoint should not disclose the layout.
        migrationsFolderFound: schemaState().migrationsFolder !== null,
        schemaError: detail,
        hint:
          detail.stage === "folder"
            ? "The drizzle/ migrations folder is missing from the deployed build."
            : detail.stage === "verify"
              ? "Migrations ran but the expected tables are absent. Inspect schemaError.detail."
              : "Migrations could not be applied. schemaError.sqlState is the PostgreSQL error code.",
      },
      { status: 500 },
    );
  }

  // 3. Verified: migrations applied and every required table exists.
  return Response.json({
    ok: true,
    service: "max-api",
    database: "up",
    schema: "ready",
    ai,
    dbLatencyMs,
  });
}