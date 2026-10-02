import { and, desc, eq, lt, or } from "drizzle-orm";
import { db } from "@/db";
import { activityLogs } from "@/db/schema";
import { z } from "zod";
import { logActivity } from "@/lib/activity";
import { ACTIONS } from "@/lib/commands/intent";
import { api, decodeCursor, encodeCursor, json, pageSize, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

// Cursor pagination (newest first). Never returns the whole history at once.
export const GET = api({ auth: true }, async ({ req, user }) => {
  const limit = pageSize(req, 30);
  const cursor = decodeCursor(new URL(req.url).searchParams.get("cursor"));
  const rows = await db
    .select()
    .from(activityLogs)
    .where(
      and(
        eq(activityLogs.userId, user.id),
        cursor ? or(lt(activityLogs.createdAt, cursor.date), and(eq(activityLogs.createdAt, cursor.date), lt(activityLogs.id, cursor.id))) : undefined,
      ),
    )
    .orderBy(desc(activityLogs.createdAt), desc(activityLogs.id))
    .limit(limit + 1);
  const items = rows.slice(0, limit);
  const last = items[items.length - 1];
  return json({ items, nextCursor: rows.length > limit && last ? encodeCursor(last.createdAt, last.id) : null });
});

// The Android app executes local-first commands on the device and records the REAL
// outcome here (so the Activity screen is complete). actionType must be whitelisted.
const logSchema = z.object({
  actionType: z.enum(ACTIONS),
  status: z.enum(["completed", "prepared", "unsupported", "failed", "cancelled"]),
  summary: z.string().trim().min(1).max(200),
});

export const POST = api({ auth: true }, async ({ req, user }) => {
  const b = await readJson(req, logSchema);
  await logActivity(user.id, `ACTION_${b.status.toUpperCase()}`, b.summary, { action: b.actionType, source: "device" });
  return json({ ok: true }, 201);
});

export const DELETE = api({ auth: true }, async ({ user }) => {
  await db.delete(activityLogs).where(eq(activityLogs.userId, user.id));
  return json({ ok: true });
});
