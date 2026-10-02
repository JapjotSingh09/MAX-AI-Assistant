import { z } from "zod";
import { and, desc, eq, lt, or } from "drizzle-orm";
import { db } from "@/db";
import { conversations, messages } from "@/db/schema";
import { api, ApiError, decodeCursor, encodeCursor, json, pageSize, readJson } from "@/lib/http";
import { logActivity } from "@/lib/activity";

export const dynamic = "force-dynamic";

// One conversation: read it, rename it, or delete it.
//
// USER ISOLATION: every statement below is `WHERE id = ? AND user_id = ?`, and
// an unknown-or-not-yours id returns 404 rather than 403. That way a caller
// cannot even learn that some other user's conversation exists.

const idOf = (raw: string): string => {
  const parsed = z.string().uuid().safeParse(raw);
  if (!parsed.success) throw new ApiError(404, "not_found", "Conversation not found.");
  return parsed.data;
};

const CONCISE = {
  id: messages.id,
  role: messages.role,
  content: messages.content,
  createdAt: messages.createdAt,
};

/** Read one conversation's newest messages, newest first (cursor paginated). */
export const GET = api({ auth: true }, async ({ req, user, params }) => {
  const id = idOf(params.id);
  const [conv] = await db
    .select({ id: conversations.id, title: conversations.title, createdAt: conversations.createdAt, updatedAt: conversations.updatedAt })
    .from(conversations)
    .where(and(eq(conversations.id, id), eq(conversations.userId, user.id)))
    .limit(1);
  if (!conv) throw new ApiError(404, "not_found", "Conversation not found.");

  const limit = pageSize(req, 50, 100);
  const cursor = decodeCursor(new URL(req.url).searchParams.get("cursor"));
  const rows = await db
    .select(CONCISE)
    .from(messages)
    .where(
      and(
        eq(messages.conversationId, id),
        eq(messages.userId, user.id),
        cursor ? or(lt(messages.createdAt, cursor.date), and(eq(messages.createdAt, cursor.date), lt(messages.id, cursor.id))) : undefined,
      ),
    )
    .orderBy(desc(messages.createdAt), desc(messages.id))
    .limit(limit + 1);
  const items = rows.slice(0, limit);
  const last = items[items.length - 1];
  return json({ ...conv, items, nextCursor: rows.length > limit && last ? encodeCursor(last.createdAt, last.id) : null });
});

/** Rename a conversation. */
export const PATCH = api({ auth: true }, async ({ req, user, params }) => {
  const id = idOf(params.id);
  const { title } = await readJson(req, z.object({ title: z.string().trim().min(1, "Please give it a name.").max(100) }));
  const rows = await db
    .update(conversations)
    .set({ title, updatedAt: new Date() })
    .where(and(eq(conversations.id, id), eq(conversations.userId, user.id)))
    .returning();
  if (!rows[0]) throw new ApiError(404, "not_found", "Conversation not found.");
  await logActivity(user.id, "CONVERSATION_RENAMED", `Renamed a conversation to "${title}"`);
  return json(rows[0]);
});

/**
 * Delete one conversation. Messages cascade in the schema, so this removes the
 * whole thread in one statement. The caller is responsible for confirming with
 * the user first - the UI asks, and the tool registry marks this destructive.
 */
export const DELETE = api({ auth: true }, async ({ user, params }) => {
  const id = idOf(params.id);
  const rows = await db
    .delete(conversations)
    .where(and(eq(conversations.id, id), eq(conversations.userId, user.id)))
    .returning({ id: conversations.id });
  if (!rows[0]) throw new ApiError(404, "not_found", "Conversation not found.");
  await logActivity(user.id, "CONVERSATION_DELETED", "Deleted a conversation");
  return json({ ok: true });
});