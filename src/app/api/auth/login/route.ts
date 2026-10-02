import { z } from "zod";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { users } from "@/db/schema";
import { verifyPassword } from "@/lib/auth";
import { sessionResponse } from "@/lib/authFlow";
import { config } from "@/lib/config";
import { api, ApiError, enforceLimit, readJson } from "@/lib/http";
import { logActivity } from "@/lib/activity";

export const dynamic = "force-dynamic";

const schema = z.object({ email: z.string().trim().toLowerCase().email().max(254), password: z.string().min(1).max(128) });

export const POST = api({ auth: false, authBucket: "login" }, async ({ req }) => {
  const body = await readJson(req, schema);
  // Per-account limit slows down password guessing across many IPs.
  await enforceLimit(`login:email:${body.email}`, 8, 300);

  const [u] = await db.select().from(users).where(eq(users.email, body.email)).limit(1);
  // Same message for "no such user" and "wrong password" so emails can't be probed.
  const ok = u ? await verifyPassword(body.password, u.passwordHash) : false;
  if (!u || !ok) throw new ApiError(401, "invalid_credentials", "Incorrect email or password.");
  if (config.email.requireVerification && !u.emailVerifiedAt) {
    throw new ApiError(403, "email_not_verified", "Please verify your email before signing in.");
  }
  await logActivity(u.id, "LOGIN", "Signed in");
  return sessionResponse(req, u.id, { ok: true });
});
