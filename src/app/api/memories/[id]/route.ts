import { z } from "zod";
import { and, eq } from "drizzle-orm";
import { db } from "@/db";
import { memories } from "@/db/schema";
import { api, ApiError, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

const getId = (v: string) => {
  const r = z.string().uuid().safeParse(v);
  if (!r.success) throw new ApiError(404, "not_found", "Memory not found.");
  return r.data;
};

export const PATCH = api({ auth: true }, async ({ req, user, params }) => {
  const id = getId(params.id);
  const { content } = await readJson(req, z.object({ content: z.string().trim().min(1).max(300) }));
  const rows = await db.update(memories).set({ content, updatedAt: new Date() }).where(and(eq(memories.id, id), eq(memories.userId, user.id))).returning();
  if (!rows[0]) throw new ApiError(404, "not_found", "Memory not found.");
  return json(rows[0]);
});

export const DELETE = api({ auth: true }, async ({ user, params }) => {
  const id = getId(params.id);
  const rows = await db.delete(memories).where(and(eq(memories.id, id), eq(memories.userId, user.id))).returning({ id: memories.id });
  if (!rows[0]) throw new ApiError(404, "not_found", "Memory not found.");
  return json({ ok: true });
});
