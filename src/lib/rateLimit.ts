import { sql } from "drizzle-orm";
import { db } from "@/db";
import { rateLimits } from "@/db/schema";

// Pure helper (unit-tested): which fixed window does "now" fall into?
export function windowStartFor(nowMs: number, windowSeconds: number) {
  const size = windowSeconds * 1000;
  return Math.floor(nowMs / size) * size;
}

export type RateLimitResult = { ok: boolean; remaining: number; retryAfterSeconds: number };

// Fixed-window counter stored in Postgres. WHY: the API stays stateless, and the
// counter works across many server instances. One atomic UPSERT per check.
export async function rateLimit(key: string, limit: number, windowSeconds: number): Promise<RateLimitResult> {
  const now = Date.now();
  const start = windowStartFor(now, windowSeconds);
  const rows = await db
    .insert(rateLimits)
    .values({ key, windowStart: new Date(start), count: 1 })
    .onConflictDoUpdate({
      target: [rateLimits.key, rateLimits.windowStart],
      set: { count: sql`${rateLimits.count} + 1` },
    })
    .returning({ count: rateLimits.count });

  // Opportunistic cleanup so the table does not grow forever (about 1% of calls).
  if (Math.random() < 0.01) {
    const cutoff = new Date(now - 2 * 24 * 3600 * 1000);
    await db.delete(rateLimits).where(sql`${rateLimits.windowStart} < ${cutoff}`).catch(() => {});
  }

  const count = rows[0]?.count ?? 1;
  const retryAfterSeconds = Math.max(1, Math.ceil((start + windowSeconds * 1000 - now) / 1000));
  return { ok: count <= limit, remaining: Math.max(0, limit - count), retryAfterSeconds };
}
