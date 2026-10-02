import { and, desc, eq, lt, or, count } from "drizzle-orm";
import { db } from "@/db";
import { automations } from "@/db/schema";
import { logActivity } from "@/lib/activity";
import { automationInput } from "@/lib/automationSchema";
import { config } from "@/lib/config";
import { api, ApiError, decodeCursor, encodeCursor, enforceLimit, json, pageSize, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

export const GET = api({ auth: true }, async ({ req, user }) => {
  const limit = pageSize(req, 30);
  const cursor = decodeCursor(new URL(req.url).searchParams.get("cursor"));
  const rows = await db
    .select()
    .from(automations)
    .where(
      and(
        eq(automations.userId, user.id),
        cursor ? or(lt(automations.createdAt, cursor.date), and(eq(automations.createdAt, cursor.date), lt(automations.id, cursor.id))) : undefined,
      ),
    )
    .orderBy(desc(automations.createdAt), desc(automations.id))
    .limit(limit + 1);
  const items = rows.slice(0, limit);
  const last = items[items.length - 1];
  return json({ items, nextCursor: rows.length > limit && last ? encodeCursor(last.createdAt, last.id) : null });
});

export const POST = api({ auth: true }, async ({ req, user }) => {
  await enforceLimit(`automation:day:${user.id}`, config.limits.automationCreatesPerDay, 86400, "You've created many automations today. Please try again tomorrow.");
  const body = await readJson(req, automationInput);
  const [{ n }] = await db.select({ n: count() }).from(automations).where(eq(automations.userId, user.id));
  if (n >= 100) throw new ApiError(400, "limit_reached", "You've reached the maximum number of automations.");
  const [row] = await db.insert(automations).values({ ...body, userId: user.id }).returning();
  await logActivity(user.id, "AUTOMATION_CREATED", `Created automation "${row.name}"`);
  return json(row, 201);
});
