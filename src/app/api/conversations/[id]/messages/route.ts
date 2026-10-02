import { and, desc, eq, lt, or } from "drizzle-orm";
import { z } from "zod";
import { db } from "@/db";
import { assistantActions, conversations, messages } from "@/db/schema";
import { api, ApiError, decodeCursor, encodeCursor, json, pageSize } from "@/lib/http";

export const dynamic = "force-dynamic";

// Cursor pagination: returns the NEWEST messages first; pass nextCursor to load older ones.
export const GET = api({ auth: true }, async ({ req, user, params }) => {
  const id = z.string().uuid().safeParse(params.id);
  if (!id.success) throw new ApiError(404, "not_found", "Conversation not found.");
  const [conv] = await db
    .select({ id: conversations.id })
    .from(conversations)
    .where(and(eq(conversations.id, id.data), eq(conversations.userId, user.id)))
    .limit(1);
  if (!conv) throw new ApiError(404, "not_found", "Conversation not found.");

  const limit = pageSize(req, 30);
  const cursor = decodeCursor(new URL(req.url).searchParams.get("cursor"));
  const rows = await db
    .select()
    .from(messages)
    .where(
      and(
        eq(messages.conversationId, conv.id),
        eq(messages.userId, user.id),
        cursor ? or(lt(messages.createdAt, cursor.date), and(eq(messages.createdAt, cursor.date), lt(messages.id, cursor.id))) : undefined,
      ),
    )
    .orderBy(desc(messages.createdAt), desc(messages.id))
    .limit(limit + 1);
  const items = rows.slice(0, limit);
  const last = items[items.length - 1];

  // Latest action cards for this conversation (bounded) so history shows their status.
  const actions = await db
    .select({ id: assistantActions.id, actionType: assistantActions.actionType, actionPayload: assistantActions.actionPayload, status: assistantActions.status, createdAt: assistantActions.createdAt })
    .from(assistantActions)
    .where(and(eq(assistantActions.conversationId, conv.id), eq(assistantActions.userId, user.id)))
    .orderBy(desc(assistantActions.createdAt))
    .limit(50);

  return json({ items, actions, nextCursor: rows.length > limit && last ? encodeCursor(last.createdAt, last.id) : null });
});
