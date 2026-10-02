import { z } from "zod";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { sessions, users } from "@/db/schema";
import { hashPassword } from "@/lib/auth";
import { consumeAuthToken } from "@/lib/authFlow";
import { api, ApiError, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

const schema = z.object({ token: z.string().min(20).max(200), password: z.string().min(8, "Password must be at least 8 characters.").max(128) });

export const POST = api({ auth: false, authBucket: "reset" }, async ({ req }) => {
  const body = await readJson(req, schema);
  const userId = await consumeAuthToken(body.token, "reset_password");
  if (!userId) throw new ApiError(400, "invalid_token", "This reset link is invalid or has expired.");
  await db.update(users).set({ passwordHash: await hashPassword(body.password) }).where(eq(users.id, userId));
  // Sign out every existing session after a password reset.
  await db.delete(sessions).where(eq(sessions.userId, userId));
  return json({ ok: true, message: "Password updated. Please sign in." });
});
