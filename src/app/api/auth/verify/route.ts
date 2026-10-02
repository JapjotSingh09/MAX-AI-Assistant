import { z } from "zod";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { users } from "@/db/schema";
import { consumeAuthToken, sendVerificationEmail } from "@/lib/authFlow";
import { api, ApiError, enforceLimit, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

// POST { token } verifies the email. POST {} (signed in) re-sends the verification email.
const schema = z.object({ token: z.string().min(20).max(200).optional() });

export const POST = api({ auth: false, authBucket: "verify" }, async ({ req, user }) => {
  const { token } = await readJson(req, schema);
  if (token) {
    const userId = await consumeAuthToken(token, "verify_email");
    if (!userId) throw new ApiError(400, "invalid_token", "This verification link is invalid or has expired.");
    await db.update(users).set({ emailVerifiedAt: new Date() }).where(eq(users.id, userId));
    return json({ ok: true, message: "Email verified. Thank you!" });
  }
  if (!user) throw new ApiError(401, "unauthorized", "Your session has expired. Please sign in again.");
  if (user.emailVerified) return json({ ok: true, message: "Your email is already verified." });
  await enforceLimit(`resend:user:${user.id}`, 3, 3600);
  const r = await sendVerificationEmail(req, user.id, user.email);
  return json({ ok: r.sent, devLink: r.devLink, message: r.sent ? "Verification email sent." : "We couldn't send the email right now. Please try again later." });
});
