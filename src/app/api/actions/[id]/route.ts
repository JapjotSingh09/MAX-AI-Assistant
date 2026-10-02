import { z } from "zod";
import { and, eq, inArray } from "drizzle-orm";
import { db } from "@/db";
import { assistantActions } from "@/db/schema";
import { logActivity } from "@/lib/activity";
import { ACTION_LABELS, validateIntent } from "@/lib/commands/intent";
import { api, ApiError, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

// The device reports the REAL outcome of an action. MAX never claims success on its own.
//   completed   = the action was carried out
//   prepared    = e.g. the SMS/WhatsApp composer was opened but nothing was sent
//   unsupported = this device/platform cannot do it
//   failed / cancelled
const schema = z.object({
  status: z.enum(["completed", "prepared", "unsupported", "failed", "cancelled"]),
  message: z.string().max(300).optional(),
});

const WORDS: Record<string, string> = {
  completed: "Done",
  prepared: "Prepared (not sent)",
  unsupported: "Not supported here",
  failed: "Failed",
  cancelled: "Cancelled",
};

export const PATCH = api({ auth: true }, async ({ req, user, params }) => {
  const id = z.string().uuid().safeParse(params.id);
  if (!id.success) throw new ApiError(404, "not_found", "Action not found.");
  const body = await readJson(req, schema);

  const [row] = await db
    .select()
    .from(assistantActions)
    .where(and(eq(assistantActions.id, id.data), eq(assistantActions.userId, user.id)))
    .limit(1);
  if (!row) throw new ApiError(404, "not_found", "Action not found.");
  if (!["awaiting_confirmation", "ready"].includes(row.status)) {
    throw new ApiError(409, "already_resolved", "This action was already handled.");
  }
  // Re-validate the stored payload before the result is accepted.
  const payload = row.actionPayload as { parameters?: Record<string, unknown> };
  const v = validateIntent({ action: row.actionType, parameters: payload.parameters });

  const [updated] = await db
    .update(assistantActions)
    .set({ status: body.status, errorMessage: body.status === "failed" || body.status === "unsupported" ? (body.message ?? null) : null, updatedAt: new Date() })
    .where(and(eq(assistantActions.id, row.id), eq(assistantActions.userId, user.id), inArray(assistantActions.status, ["awaiting_confirmation", "ready"])))
    .returning();
  if (!updated) throw new ApiError(409, "already_resolved", "This action was already handled.");

  const label = v.ok ? ACTION_LABELS[v.intent.action](v.intent.parameters) : row.actionType;
  await logActivity(user.id, `ACTION_${body.status.toUpperCase()}`, `${WORDS[body.status]}: ${label}`, { action: row.actionType });
  return json({ ok: true, status: updated.status });
});
