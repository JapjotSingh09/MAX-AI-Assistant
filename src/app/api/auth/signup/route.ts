import { z } from "zod";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { profiles, userPreferences, users } from "@/db/schema";
import { hashPassword } from "@/lib/auth";
import { sendVerificationEmail, sessionResponse } from "@/lib/authFlow";
import { api, ApiError, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

const schema = z.object({
  email: z.string().trim().toLowerCase().email("Please enter a valid email.").max(254),
  password: z.string().min(8, "Password must be at least 8 characters.").max(128),
  fullName: z.string().trim().max(80).optional(),
});

export const POST = api({ auth: false, authBucket: "signup" }, async ({ req }) => {
  const body = await readJson(req, schema);
  const [existing] = await db.select({ id: users.id }).from(users).where(eq(users.email, body.email)).limit(1);
  if (existing) throw new ApiError(409, "email_taken", "An account with this email already exists. Try signing in.");

  const passwordHash = await hashPassword(body.password);
  let userId: string;
  try {
    const [u] = await db.insert(users).values({ email: body.email, passwordHash }).returning({ id: users.id });
    userId = u.id;
  } catch {
    // Unique constraint: two signup requests raced. The database is the source of truth.
    throw new ApiError(409, "email_taken", "An account with this email already exists. Try signing in.");
  }
  await db.insert(profiles).values({ id: userId, fullName: body.fullName || body.email.split("@")[0] });
  await db.insert(userPreferences).values({ userId });

  const verification = await sendVerificationEmail(req, userId, body.email);
  return sessionResponse(
    req,
    userId,
    {
      ok: true,
      emailSent: verification.sent,
      devVerifyLink: verification.devLink,
      message: verification.sent
        ? "Account created. Please check your email to verify your address."
        : "Account created, but we couldn't send the verification email. You can resend it from Settings.",
    },
    201,
  );
});
