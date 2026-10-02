import { and, count, desc, eq } from "drizzle-orm";
import { db } from "@/db";
import { assistantActions, conversations, memories, messages } from "@/db/schema";
import { logActivity, trackUsage } from "@/lib/activity";
import { getRouter } from "@/lib/ai/router";
import { AIUnavailableError, type AIMessage } from "@/lib/ai/types";
import { config } from "@/lib/config";
import { enforceLimit, ApiError } from "@/lib/http";
import { ACTIONS, ACTION_LABELS, validateIntent, type CommandIntent } from "@/lib/commands/intent";
import { parseCommand } from "@/lib/commands/parser";

export const AI_UNAVAILABLE = "MAX's AI service is temporarily unavailable.";

const SYSTEM_PROMPT = `You are MAX, a concise, friendly personal AI assistant.
Reply ONLY with a JSON object: {"reply": string, "action": null | {"action": string, "parameters": object, "requiresConfirmation": boolean}}
Only include an action when the user clearly asks for a device action. Allowed actions: ${ACTIONS.join(", ")}.
Parameter names: OPEN_APP{appName}, CALL_CONTACT{contactName}, OPEN_DIALER{number?}, SEND_SMS{contactName,message}, OPEN_WHATSAPP{contactName?,message?}, OPEN_MAPS{query?}, NAVIGATE{destination}, SET_ALARM{hour,minute,label?}, SET_TIMER{seconds}, CREATE_REMINDER{text,when?}, OPEN_BROWSER{url?|query?}, ADJUST_VOLUME{direction:up|down|mute}, TOGGLE_FLASHLIGHT{state:on|off|toggle}, OPEN_SETTINGS{section?}.
You cannot run actions yourself: the app asks the user to confirm and reports the real result. Never claim an action already succeeded. Keep answers short unless asked for detail.`;

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
        ? `${SYSTEM_PROMPT}\nThings the user asked you to remember:\n${mems.map((m) => `- ${m.content}`).join("\n")}`
        : SYSTEM_PROMPT;
      const chatMessages: AIMessage[] = [
        { role: "system", content: system },
        ...history.reverse().map((h) => ({ role: h.role === "assistant" ? ("assistant" as const) : ("user" as const), content: h.content })),
      ];
      const ai = await router.chat({ messages: chatMessages });
      await trackUsage(userId, { eventType: "ai_chat", provider: ai.provider, model: ai.model, tokensUsed: ai.tokensUsed, latencyMs: ai.latencyMs, success: true });

      const out = parseAIOutput(ai.content);
      reply = out.reply;
      if (out.rawAction) {
        // The AI's action is NEVER trusted: it must pass the whitelist + schema.
        const v = validateIntent(out.rawAction);
        if (v.ok) {
          action = await saveAction(userId, convId, v.intent);
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
  }

  const [assistantMsg] = await db.insert(messages).values({ conversationId: convId, userId, role: "assistant", content: reply }).returning();
  await db.update(conversations).set({ updatedAt: new Date() }).where(and(eq(conversations.id, convId), eq(conversations.userId, userId)));
  if (!aiUnavailable && !action && !local) await logActivity(userId, "AI_ANSWER", "MAX answered a question");
  if (action) await logActivity(userId, "ACTION_PROPOSED", `Proposed: ${action.label}`, { action: action.action });

  return { conversationId: convId, userMessage: userMsg, assistantMessage: assistantMsg, action, aiUnavailable };
}
