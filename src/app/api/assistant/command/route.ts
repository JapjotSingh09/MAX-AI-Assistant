import { z } from "zod";
import { config } from "@/lib/config";
import { ACTION_LABELS, validateIntent } from "@/lib/commands/intent";
import { parseCommand } from "@/lib/commands/parser";
import { api, enforceLimit, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

// POST /api/assistant/command: parse and validate only (no AI call, nothing is saved).
// Send { text } to parse a spoken/typed command, or { intent } to validate a raw action.
// Clients (e.g. the Android app) use this to check an action before executing it.
export const POST = api({ auth: true }, async ({ req, user }) => {
  await enforceLimit(`voice:user:${user.id}`, config.limits.voicePerMinutePerUser, 60);
  const body = await readJson(
    req,
    z.object({ text: z.string().trim().min(1).max(config.limits.maxMessageLength).optional(), intent: z.unknown().optional() }),
  );
  if (body.intent !== undefined) {
    const v = validateIntent(body.intent);
    if (!v.ok) return json({ valid: false, reason: v.reason }, 422);
    return json({ valid: true, intent: v.intent, label: ACTION_LABELS[v.intent.action](v.intent.parameters) });
  }
  const parsed = body.text ? parseCommand(body.text) : null;
  if (parsed?.kind === "intent") {
    return json({ handled: "local", intent: parsed.intent, label: ACTION_LABELS[parsed.intent.action](parsed.intent.parameters) });
  }
  return json({ handled: parsed?.kind === "memory" ? "memory" : "needs_ai" });
});
