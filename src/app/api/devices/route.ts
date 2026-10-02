import { z } from "zod";
import { and, eq } from "drizzle-orm";
import { db } from "@/db";
import { devices } from "@/db/schema";
import { api, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

// The Android app registers/refreshes itself here (one row per device name per user).
export const POST = api({ auth: true }, async ({ req, user }) => {
  const b = await readJson(req, z.object({ deviceName: z.string().trim().min(1).max(80), platform: z.enum(["android", "web"]), appVersion: z.string().max(30).optional() }));
  const [existing] = await db.select({ id: devices.id }).from(devices).where(and(eq(devices.userId, user.id), eq(devices.deviceName, b.deviceName), eq(devices.platform, b.platform))).limit(1);
  if (existing) {
    await db.update(devices).set({ lastSeenAt: new Date(), appVersion: b.appVersion }).where(and(eq(devices.id, existing.id), eq(devices.userId, user.id)));
    return json({ ok: true });
  }
  await db.insert(devices).values({ ...b, userId: user.id });
  return json({ ok: true }, 201);
});
