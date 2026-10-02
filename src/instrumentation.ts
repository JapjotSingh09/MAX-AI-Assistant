// Next.js runs this once when the Node.js server starts, before it serves any
// request. It is what makes the production database self-initialising: on the
// Render free plan there is no Shell, so nobody can run `drizzle-kit migrate` by
// hand. If this is skipped the first request still applies the schema (the
// `api()` wrapper and /api/health both call ensureDatabaseSchema), so a failure
// here is logged and reported by /api/health rather than crashing the deploy.
export async function register() {
  // The Edge runtime has no `pg`; only the Node runtime owns the database.
  if (process.env.NEXT_RUNTIME !== "nodejs") return;

  try {
    const { ensureDatabaseSchema, schemaState } = await import("./db/migrate");
    const started = Date.now();
    await ensureDatabaseSchema();
    console.log(
      JSON.stringify({
        level: "info",
        msg: "db.schema.ready",
        migrationsFolder: schemaState().migrationsFolder,
        durationMs: Date.now() - started,
      }),
    );
  } catch (err) {
    // Importing the diagnostic helper can itself fail (e.g. DATABASE_URL is
    // missing, which makes src/db throw on load), so it is imported separately
    // and the raw error name is used as a last resort.
    let detail: Record<string, unknown> = { error: (err as { name?: string })?.name };
    try {
      const { describeDbError } = await import("./lib/http");
      detail = describeDbError(err);
    } catch {
      // Keep the fallback above.
    }
    console.error(JSON.stringify({ level: "error", msg: "db.schema.startup_failed", ...detail }));
  }
}