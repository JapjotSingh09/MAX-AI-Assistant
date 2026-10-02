import { z } from "zod";
import { and, eq } from "drizzle-orm";
import { db } from "@/db";
import { automations } from "@/db/schema";
import { logActivity } from "@/lib/activity";
import { automationInput, automationPatch } from "@/lib/automationSchema";
import { api, ApiError, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

const uuid = (v: string) => {
  const r = z.string().uuid().safeParse(v);
  if (!r.success) throw new ApiError(404, "not_found", "Automation not found.");
  return r.data;
};

export const PATCH = api({ auth: true }, async ({ req, user, params }) => {
  const id = uuid(params.id);
  const patch = await readJson(req, automationPatch);
  // Every query includes user_id: you can only touch your own automations.
  const [current] = await db.select().from(automations).where(and(eq(automations.id, id), eq(automations.userId, user.id))).limit(1);
  if (!current) throw new ApiError(404, "not_found", "Automation not found.");

  // Merge then validate the final result with the same schema as creation.
  const merged = automationInput.safeParse({
    name: patch.name ?? current.name,
    triggerType: patch.triggerType ?? current.triggerType,
    triggerConfig: patch.triggerConfig ?? current.triggerConfig,
    actionType: patch.actionType ?? current.actionType,
    actionConfig: patch.actionConfig ?? current.actionConfig,
    enabled: patch.enabled ?? current.enabled,
  });
  if (!merged.success) throw new ApiError(400, "validation", merged.error.issues[0]?.message || "Invalid automation.");
  const [row] = await db
    .update(automations)
    .set({ ...merged.data, updatedAt: new Date() })
    .where(and(eq(automations.id, id), eq(automations.userId, user.id)))
    .returning();
  return json(row);
});

export const DELETE = api({ auth: true }, async ({ user, params }) => {
  const id = uuid(params.id);
  const rows = await db.delete(automations).where(and(eq(automations.id, id), eq(automations.userId, user.id))).returning({ name: automations.name });
  if (!rows[0]) throw new ApiError(404, "not_found", "Automation not found.");
  await logActivity(user.id, "AUTOMATION_DELETED", `Deleted automation "${rows[0].name}"`);
  return json({ ok: true });
});
