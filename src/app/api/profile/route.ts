import { z } from "zod";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { profiles, userPreferences, users } from "@/db/schema";
import { verifyPassword } from "@/lib/auth";
import { api, ApiError, enforceLimit, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

const DEFAULT_PREFS = { theme: "dark", voiceEnabled: true, voiceName: null as string | null, assistantName: "MAX", language: "en-US" };

// GET: profile + preferences. PATCH: update name/avatar/preferences.
export const GET = api({ auth: true }, async ({ user }) => {
  const [p] = await db.select().from(userPreferences).where(eq(userPreferences.userId, user.id)).limit(1);
  return json({
    profile: { fullName: user.fullName, avatarUrl: user.avatarUrl, email: user.email, joinedAt: user.createdAt, emailVerified: user.emailVerified, plan: "Free" },
    preferences: p
      ? { theme: p.theme, voiceEnabled: p.voiceEnabled, voiceName: p.voiceName, assistantName: p.assistantName, language: p.language }
      : DEFAULT_PREFS,
  });
});

const patchSchema = z.object({
  fullName: z.string().trim().min(1).max(80).optional(),
  avatarUrl: z.string().url().max(500).nullable().optional(),
  preferences: z
    .object({
      voiceEnabled: z.boolean().optional(),
      voiceName: z.string().max(120).nullable().optional(),
      language: z.string().regex(/^[a-z]{2,3}(-[A-Za-z]{2,4})?$/).optional(),
      assistantName: z.string().trim().min(1).max(30).optional(),
      theme: z.enum(["dark", "light"]).optional(),
    })
    .optional(),
});

export const PATCH = api({ auth: true }, async ({ req, user }) => {
  const body = await readJson(req, patchSchema);
  if (body.fullName !== undefined || body.avatarUrl !== undefined) {
    await db
      .insert(profiles)
      .values({ id: user.id, fullName: body.fullName, avatarUrl: body.avatarUrl })
      .onConflictDoUpdate({ target: profiles.id, set: { ...(body.fullName !== undefined && { fullName: body.fullName }), ...(body.avatarUrl !== undefined && { avatarUrl: body.avatarUrl }), updatedAt: new Date() } });
  }
  if (body.preferences && Object.keys(body.preferences).length) {
    await db
      .insert(userPreferences)
      .values({ userId: user.id, ...body.preferences })
      .onConflictDoUpdate({ target: userPreferences.userId, set: { ...body.preferences, updatedAt: new Date() } });
  }
  return json({ ok: true });
});

// Delete account: requires the password again, then cascades through every table.
export const DELETE = api({ auth: true }, async ({ req, user }) => {
  await enforceLimit(`delete-account:${user.id}`, 3, 3600);
  const { password } = await readJson(req, z.object({ password: z.string().min(1).max(128) }));
  const [u] = await db.select().from(users).where(eq(users.id, user.id)).limit(1);
  if (!u || !(await verifyPassword(password, u.passwordHash))) throw new ApiError(401, "invalid_credentials", "Incorrect password.");
  await db.delete(users).where(eq(users.id, user.id));
  const res = Response.json({ ok: true });
  res.headers.append("Set-Cookie", "max_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
  return res;
});
