import { and, count, eq, gte, sql } from "drizzle-orm";
import { db } from "@/db";
import { usageEvents } from "@/db/schema";
import { aiConfigured, config } from "@/lib/config";
import { api, json } from "@/lib/http";

export const dynamic = "force-dynamic";

// Today's AI usage for the signed-in user (the numbers behind the quota).
export const GET = api({ auth: true }, async ({ user }) => {
  const since = new Date(Date.now() - 24 * 3600 * 1000);
  const [row] = await db
    .select({ requests: count(), tokens: sql<number>`coalesce(sum(${usageEvents.tokensUsed}), 0)::int` })
    .from(usageEvents)
    .where(and(eq(usageEvents.userId, user.id), eq(usageEvents.eventType, "ai_chat"), eq(usageEvents.success, true), gte(usageEvents.createdAt, since)));
  return json({
    last24h: { aiRequests: row.requests, tokens: row.tokens },
    limits: { aiPerDay: config.limits.aiPerDayPerUser, aiPerMinute: config.limits.aiPerMinutePerUser },
    ai: { configured: aiConfigured(), provider: config.ai.provider || null, model: config.ai.model || null },
  });
});
