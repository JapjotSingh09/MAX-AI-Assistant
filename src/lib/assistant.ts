import { and, count, desc, eq, ilike } from "drizzle-orm";
import { db } from "@/db";
import { assistantActions, conversations, memories, messages } from "@/db/schema";
import { logActivity, trackUsage } from "@/lib/activity";
import { getRouter } from "@/lib/ai/router";
import { AI_TOOLS, toolCatalogForPrompt } from "@/lib/ai/toolCatalog";
import { AIUnavailableError, type AIMessage, type AITool } from "@/lib/ai/types";
import { config } from "@/lib/config";
import { enforceLimit, ApiError } from "@/lib/http";
import { ACTION_LABELS, validateIntent, type CommandIntent } from "@/lib/commands/intent";
import { parseCommand } from "@/lib/commands/parser";
import { getTool, toolLabel } from "@/lib/tools/registry";

export const AI_UNAVAILABLE = "MAX's AI service is temporarily unavailable.";

// MAX's persona and its hard rules. The tool LIST itself is not written here:
// it is generated from the registry (see `src/lib/ai/toolCatalog.ts`), so a new
// capability shows up in the prompt automatically.
const BASE_SYSTEM_PROMPT = `You are MAX, a concise, friendly personal AI assistant on the user's phone.

HOW TO ACT:
- Answer questions, explanations, summaries, translations and drafts yourself, in plain text, in your own words. Keep them short unless the user asks for detail.
- When the user wants something DONE on their device, call exactly one tool instead of describing it.
- Call a tool only when the user clearly asked for that action. Never invent a request.
- You cannot run tools yourself: MAX shows the user a confirmation card, the phone performs the action, and the real result comes back. Never claim an action already happened.
- If a tool is unavailable on the phone, say so plainly rather than pretending.`;


// Pulls {reply, action} out of the model output. Tolerates ```json fences and plain text.
export function parseAIOutput(content: string): { reply: string; rawAction: unknown } {
  const cleaned = content.replace(/```(?:json)?/gi, "").trim();
  const candidates = [cleaned];
  const first = cleaned.indexOf("{");
  const last = cleaned.lastIndexOf("}");
  if (first >= 0 && last > first) candidates.push(cleaned.slice(first, last + 1));
  for (const c of candidates) {
    try {
      const obj = JSON.parse(c) as { reply?: unknown; action?: unknown };
      if (obj && typeof obj === "object" && typeof obj.reply === "string") {
        return { reply: obj.reply.slice(0, 4000), rawAction: obj.action ?? null };
      }
    } catch {
      /* try next */
    }
  }
  return { reply: content.slice(0, 4000), rawAction: null };
}

export type ActionCard = {
  id: string;
  action: string;
  label: string;
  parameters: Record<string, unknown>;
  status: string;
  requiresConfirmation: boolean;
};

async function saveAction(userId: string, conversationId: string, intent: CommandIntent): Promise<ActionCard> {
  const status = intent.requiresConfirmation ? "awaiting_confirmation" : "ready";
  const [row] = await db
    .insert(assistantActions)
    .values({
      userId,
      conversationId,
      actionType: intent.action,
      actionPayload: { parameters: intent.parameters, requiresConfirmation: intent.requiresConfirmation },
      status,
    })
    .returning({ id: assistantActions.id });
  return {
    id: row.id,
    action: intent.action,
    label: ACTION_LABELS[intent.action](intent.parameters),
    parameters: intent.parameters,
    status,
    requiresConfirmation: intent.requiresConfirmation,
  };
}

// ---------------------------------------------------------------------------
// Server-side tools
// ---------------------------------------------------------------------------
// Most tools run on the phone. These few need the user's stored rows, so the
// backend runs them itself. They still arrive through the same validated
// `CommandIntent`, and every query below is scoped to `userId`, so one user can
// never reach another user's data even if the model asks them to.

/**
 * Escapes the LIKE wildcards `%` and `_` in user input.
 *
 * Without this, the phrase "Forget that 100% of exams matter" would turn into a
 * wildcard pattern and delete unrelated memories. The `\` escape has to be added
 * after escaping, so backslashes are doubled first.
 */
export function escapeLike(input: string): string {
  return input.replace(/[\\%_]/g, (c) => `\\${c}`);
}

async function runServerTool(userId: string, intent: CommandIntent): Promise<{ reply: string; action: ActionCard | null }> {
  switch (intent.action) {
    case "SEARCH_CONVERSATIONS": {
      const query = String(intent.parameters.query ?? "").trim();
      // ILIKE on content, always ANDed with the user id. The `escape()` in
      // drizzle turns our `%...%` into a proper parameter, so the value cannot
      // be injected into the SQL string.
      const rows = await db
        .select({ content: messages.content, role: messages.role, at: messages.createdAt })
        .from(messages)
        .where(and(eq(messages.userId, userId), ilike(messages.content, `%${escapeLike(query)}%`)))
        .orderBy(desc(messages.createdAt))
        .limit(5);
      const reply = rows.length
        ? `Here are the matches for "${query}":\n${rows.map((r) => `- (${r.role}) ${r.content.slice(0, 120)}`).join("\n")}`
        : `I couldn't find "${query}" in any of your conversations.`;
      return { reply, action: null };
    }
    case "CLEAR_CONVERSATIONS": {
      // Destructive: the registry marks this confirm:"always", so by the time we
      // get here the user has already tapped Confirm.
      const deleted = await db.delete(conversations).where(eq(conversations.userId, userId)).returning({ id: conversations.id });
      await logActivity(userId, "CONVERSATIONS_CLEARED", `Deleted ${deleted.length} conversation(s)`);
      return { reply: `Deleted ${deleted.length} conversation(s). Let's start fresh.`, action: null };
    }
    case "CLEAR_MEMORY": {
      const deleted = await db.delete(memories).where(eq(memories.userId, userId)).returning({ id: memories.id });
      await logActivity(userId, "MEMORY_CLEARED", `Deleted ${deleted.length} memory item(s)`);
      return { reply: `Forgotten. I deleted ${deleted.length} thing(s) I'd been asked to remember.`, action: null };
    }
    default:
      // Defence in depth: the caller only routes here when the registry says
      // site === "server", so reaching this means the two tables disagree.
      throw new ApiError(500, "tool_not_runnable", "MAX couldn't run that.");
  }
}

// The command pipeline:
// normalize -> local parser -> (AI if needed) -> validate -> save action -> reply.
export async function runAssistant(userId: string, text: string, conversationId?: string | null) {
  // 1. Make sure the conversation belongs to THIS user (or create a new one).
  let convId = conversationId ?? null;
  if (convId) {
    const [c] = await db
      .select({ id: conversations.id })
      .from(conversations)
      .where(and(eq(conversations.id, convId), eq(conversations.userId, userId)))
      .limit(1);
    if (!c) throw new ApiError(404, "not_found", "Conversation not found.");
    const [n] = await db.select({ n: count() }).from(messages).where(eq(messages.conversationId, convId));
    if (n.n >= config.limits.maxConversationLength) {
      throw new ApiError(400, "conversation_full", "This conversation is full. Please start a new one.");
    }
  } else {
    const title = text.length > 48 ? `${text.slice(0, 45)}...` : text;
    const [c] = await db.insert(conversations).values({ userId, title }).returning({ id: conversations.id });
    convId = c.id;
  }

  const [userMsg] = await db.insert(messages).values({ conversationId: convId, userId, role: "user", content: text }).returning();

  let reply = "";
  let action: ActionCard | null = null;
  let aiUnavailable = false;
  // Filled in by the cloud branch, then copied into `reply` at the end. Kept
  // separate so the local branches above can set `reply` directly.
  const outcome: { reply: string; provider: string | null } = { reply: "", provider: null };

  const local = parseCommand(text);

  if (local?.kind === "memory") {
    const [{ n }] = await db.select({ n: count() }).from(memories).where(eq(memories.userId, userId));
    if (n >= 200) {
      reply = "My memory is full. Please delete a few memories first.";
    } else {
      await db.insert(memories).values({ userId, content: local.text });
      reply = `Got it. I'll remember: "${local.text}". You can edit or delete this in Memory.`;
      await logActivity(userId, "MEMORY_SAVED", "Saved a memory");
    }
    await trackUsage(userId, { eventType: "local_command", success: true });
  } else if (local?.kind === "memory_list") {
    // "What did I ask you to remember?" - answered from this user's own rows.
    const items = await db
      .select({ content: memories.content })
      .from(memories)
      .where(eq(memories.userId, userId))
      .orderBy(desc(memories.createdAt))
      .limit(20);
    reply = items.length
      ? `Here's what you asked me to remember:\n${items.map((m) => `- ${m.content}`).join("\n")}`
      : "I haven't been asked to remember anything yet. Say \"remember that ...\" and I will.";
    await trackUsage(userId, { eventType: "local_command", success: true });
  } else if (local?.kind === "memory_forget") {
    // "Forget that my exam is on Monday." Matches on content, scoped to the user.
    const rows = await db
      .delete(memories)
      .where(and(eq(memories.userId, userId), ilike(memories.content, `%${escapeLike(local.query)}%`)))
      .returning({ id: memories.id, content: memories.content });
    reply = rows.length
      ? `Forgotten: ${rows.map((r) => `"${r.content}"`).join(", ")}.`
      : `I couldn't find anything remembered that matches "${local.query}".`;
    if (rows.length) await logActivity(userId, "MEMORY_FORGOTTEN", `Forgot ${rows.length} memory item(s)`);
    await trackUsage(userId, { eventType: "local_command", success: true });
  } else if (local?.kind === "intent") {
    action = await saveAction(userId, convId, local.intent);
    const label = action.label;
    reply = local.intent.requiresConfirmation ? `I can do this once you confirm: ${label}.` : `Ready: ${label}.`;
    await trackUsage(userId, { eventType: "local_command", success: true });
  } else {
    // Cloud AI is only used when local rules cannot handle the request.
    const router = getRouter();
    try {
      if (!router.configured) throw new AIUnavailableError("not_configured");
      await enforceLimit(`ai:min:${userId}`, config.limits.aiPerMinutePerUser, 60, "You're sending messages too quickly. Please wait a moment.");
      await enforceLimit(`ai:day:${userId}`, config.limits.aiPerDayPerUser, 86400, "You've reached today's AI limit. It resets tomorrow.");

      const [mems, history] = await Promise.all([
        db.select({ content: memories.content }).from(memories).where(eq(memories.userId, userId)).orderBy(desc(memories.createdAt)).limit(20),
        db
          .select({ role: messages.role, content: messages.content })
          .from(messages)
          .where(eq(messages.conversationId, convId))
          .orderBy(desc(messages.createdAt))
          .limit(12),
      ]);
      const system = mems.length
        ? `${BASE_SYSTEM_PROMPT}\nThings the user asked you to remember:\n${mems.map((m) => `- ${m.content}`).join("\n")}`
        : BASE_SYSTEM_PROMPT;
      const chatMessages: AIMessage[] = [
        { role: "system", content: system },
        ...history.reverse().map((h) => ({ role: h.role === "assistant" ? ("assistant" as const) : ("user" as const), content: h.content })),
      ];

      // PRIMARY PATH: native tool calling. The model receives the registry as
      // real function definitions, so its choice of tool and its arguments are
      // structured data rather than prose we have to scrape out of a sentence.
      let toolRounds = 0;
      for (;;) {
        const ai = await router.chat({ messages: chatMessages, tools: AI_TOOLS as AITool[], maxTokens: 700 });
        await trackUsage(userId, {
          eventType: "ai_chat",
          provider: ai.provider,
          model: ai.model,
          tokensUsed: ai.tokensUsed,
          latencyMs: ai.latencyMs,
          success: true,
        });
        outcome.provider = ai.provider;

        const call = ai.toolCalls?.[0];
        if (!call || toolRounds >= config.ai.maxToolRounds) {
          outcome.reply = ai.content.trim() || "I didn't quite catch that. Could you rephrase?";
          break;
        }
        toolRounds += 1;

        // The tool the model picked is UNTRUSTED. It must survive the same
        // closed whitelist and the same strict schema as a local command,
        // otherwise it is recorded as rejected and never reaches a device.
        const v = validateIntent({ action: call.name, parameters: call.arguments });
        if (!v.ok) {
          await db.insert(assistantActions).values({
            userId,
            conversationId: convId,
            actionType: "REJECTED",
            actionPayload: { requested: call.name },
            status: "rejected",
            errorMessage: v.reason,
          });
          await logActivity(userId, "ACTION_REJECTED", "Rejected an invalid AI action");
          outcome.reply = `I tried to use "${call.name}", but that isn't something I'm allowed to do.`;
          break;
        }

        // Server-side tools run right here, against this user's own rows.
        // Device tools become an action card the phone (or web console) runs.
        if (getTool(v.intent.action)?.site === "server") {
          const executed = await runServerTool(userId, v.intent);
          outcome.reply = executed.reply;
          if (executed.action) action = executed.action;
          break;
        }

        action = await saveAction(userId, convId, v.intent);
        outcome.reply = v.intent.requiresConfirmation
          ? `Sure - tap confirm and I'll ${toolLabel(v.intent.action, v.intent.parameters).toLowerCase()}.`
          : `On it: ${toolLabel(v.intent.action, v.intent.parameters)}.`;
        break;
      }

      // FALLBACK: some providers reject a request that carries `tools`
      // (HTTP 400). Rather than showing "AI unavailable" for a request the
      // model could have answered, retry once in the plain-JSON mode with the
      // SAME registry rendered into the prompt. Same validator afterwards, so
      // this path is exactly as safe as the native one.
      if (!outcome.reply) {
        const ai = await router.chat({
          messages: [
            { role: "system", content: `${system}\n\nTOOLS YOU MAY CALL:\n${toolCatalogForPrompt()}\nReply ONLY with a JSON object: {"reply": string, "action": null | {"action": string, "parameters": object}}` },
            ...chatMessages.slice(1),
          ],
          maxTokens: 700,
        });
        await trackUsage(userId, {
          eventType: "ai_chat",
          provider: ai.provider,
          model: ai.model,
          tokensUsed: ai.tokensUsed,
          latencyMs: ai.latencyMs,
          success: true,
        });
        outcome.provider = ai.provider;
        const out = parseAIOutput(ai.content);
        outcome.reply = out.reply;
        if (out.rawAction) {
          // The AI's action is NEVER trusted: it must pass the whitelist + schema.
          const v = validateIntent(out.rawAction);
          if (v.ok) {
            if (getTool(v.intent.action)?.site === "server") {
              const executed = await runServerTool(userId, v.intent);
              outcome.reply = executed.reply;
              if (executed.action) action = executed.action;
            } else {
              action = await saveAction(userId, convId, v.intent);
            }
          } else {
            await db.insert(assistantActions).values({
              userId,
              conversationId: convId,
              actionType: "REJECTED",
              actionPayload: {},
              status: "rejected",
              errorMessage: v.reason,
            });
            await logActivity(userId, "ACTION_REJECTED", "Rejected an invalid AI action");
          }
        }
      }
    } catch (err) {
      if (err instanceof ApiError) throw err;
      aiUnavailable = true;
      reply = AI_UNAVAILABLE;
      await trackUsage(userId, {
        eventType: "ai_chat",
        success: false,
        errorCategory: err instanceof AIUnavailableError ? err.reason : "unknown",
      });
    }
    // The cloud branch owns the reply from here on.
    if (!aiUnavailable) reply = outcome.reply || AI_UNAVAILABLE;
  }

  const [assistantMsg] = await db.insert(messages).values({ conversationId: convId, userId, role: "assistant", content: reply }).returning();
  await db.update(conversations).set({ updatedAt: new Date() }).where(and(eq(conversations.id, convId), eq(conversations.userId, userId)));
  if (!aiUnavailable && !action && !local) await logActivity(userId, "AI_ANSWER", "MAX answered a question");
  if (action) await logActivity(userId, "ACTION_PROPOSED", `Proposed: ${action.label}`, { action: action.action });

  return { conversationId: convId, userMessage: userMsg, assistantMessage: assistantMsg, action, aiUnavailable };
}
