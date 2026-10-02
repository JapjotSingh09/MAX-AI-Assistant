import { z } from "zod";
import { count, desc, eq } from "drizzle-orm";
import { db } from "@/db";
import { memories } from "@/db/schema";
import { api, ApiError, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

// Memory is per-user, editable and deletable. Capped so one user can't store unbounded data.
export const GET = api({ auth: true }, async ({ user }) => {
  const items = await db.select().from(memories).where(eq(memories.userId, user.id)).orderBy(desc(memories.createdAt)).limit(200);
  return json({ items });
});

export const POST = api({ auth: true }, async ({ req, user }) => {
  const { content } = await readJson(req, z.object({ content: z.string().trim().min(1, "Memory can't be empty.").max(300) }));
  const [{ n }] = await db.select({ n: count() }).from(memories).where(eq(memories.userId, user.id));
  if (n >= 200) throw new ApiError(400, "limit_reached", "Memory is full. Please delete a few items first.");
  const [row] = await db.insert(memories).values({ userId: user.id, content }).returning();
  return json(row, 201);
});

export const DELETE = api({ auth: true }, async ({ user }) => {
  await db.delete(memories).where(eq(memories.userId, user.id));
  return json({ ok: true });
});
