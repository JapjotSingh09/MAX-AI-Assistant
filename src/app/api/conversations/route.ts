import { z } from "zod";
import { and, desc, eq, lt, or } from "drizzle-orm";
import { db } from "@/db";
import { conversations } from "@/db/schema";
import { api, decodeCursor, encodeCursor, json, pageSize, readJson } from "@/lib/http";
import { logActivity } from "@/lib/activity";

export const dynamic = "force-dynamic";

export const GET = api({ auth: true }, async ({ req, user }) => {
  const limit = pageSize(req, 20);
  const cursor = decodeCursor(new URL(req.url).searchParams.get("cursor"));
  const rows = await db
    .select()
    .from(conversations)
    .where(
      and(
        eq(conversations.userId, user.id),
        cursor ? or(lt(conversations.updatedAt, cursor.date), and(eq(conversations.updatedAt, cursor.date), lt(conversations.id, cursor.id))) : undefined,
      ),
    )
    .orderBy(desc(conversations.updatedAt), desc(conversations.id))
    .limit(limit + 1);
  const items = rows.slice(0, limit);
  const last = items[items.length - 1];
  return json({ items, nextCursor: rows.length > limit && last ? encodeCursor(last.updatedAt, last.id) : null });
});

export const POST = api({ auth: true }, async ({ req, user }) => {
  const { title } = await readJson(req, z.object({ title: z.string().trim().min(1).max(100).optional() }));
  const [c] = await db.insert(conversations).values({ userId: user.id, title: title || "New conversation" }).returning();
  return json(c, 201);
});

// Privacy: delete ALL of the signed-in user's conversations (messages cascade).
export const DELETE = api({ auth: true }, async ({ user }) => {
  await db.delete(conversations).where(eq(conversations.userId, user.id));
  await logActivity(user.id, "CONVERSATIONS_CLEARED", "Cleared conversations");
  return json({ ok: true });
});
