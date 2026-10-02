import { db } from "@/db";
import { sql } from "drizzle-orm";
import { aiConfigured } from "@/lib/config";

export const dynamic = "force-dynamic";

// Basic service status. Exposes no secrets, only booleans.
export async function GET() {
  const started = Date.now();
  try {
    await db.execute(sql`select 1`);
    return Response.json({ ok: true, service: "max-api", database: "up", ai: aiConfigured() ? "configured" : "not_configured", dbLatencyMs: Date.now() - started });
  } catch {
    return Response.json({ ok: false, service: "max-api", database: "down" }, { status: 500 });
  }
}
