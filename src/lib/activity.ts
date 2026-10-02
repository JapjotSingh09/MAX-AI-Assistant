import { db } from "@/db";
import { activityLogs, usageEvents } from "@/db/schema";

// Writes one line to the user's activity history. Never throws: logging must not
// break the main request. Metadata should stay small and must not hold secrets.
export async function logActivity(userId: string, actionType: string, summary: string, extra: Record<string, unknown> = {}) {
  try {
    await db.insert(activityLogs).values({ userId, actionType, metadata: { summary, ...extra } });
  } catch {
    /* ignore */
  }
}

export async function trackUsage(
  userId: string,
  e: { eventType: string; provider?: string; model?: string; tokensUsed?: number; latencyMs?: number; success: boolean; errorCategory?: string },
) {
  try {
    await db.insert(usageEvents).values({
      userId,
      eventType: e.eventType,
      provider: e.provider,
      model: e.model,
      tokensUsed: e.tokensUsed ?? 0,
      latencyMs: e.latencyMs,
      success: e.success,
      errorCategory: e.errorCategory,
    });
  } catch {
    /* ignore */
  }
}
