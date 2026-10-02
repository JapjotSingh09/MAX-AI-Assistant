import { z } from "zod";
import { eq } from "drizzle-orm";
import { db } from "@/db";
import { users } from "@/db/schema";
import { emailEchoEnabled, sendEmail } from "@/lib/email";
import { issueAuthToken, origin } from "@/lib/authFlow";
import { api, enforceLimit, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

const schema = z.object({ email: z.string().trim().toLowerCase().email().max(254) });

export const POST = api({ auth: false, authBucket: "forgot" }, async ({ req }) => {
  const { email } = await readJson(req, schema);
  await enforceLimit(`forgot:email:${email}`, 3, 3600);
  const [u] = await db.select({ id: users.id }).from(users).where(eq(users.email, email)).limit(1);
  let devLink: string | undefined;
  if (u) {
    const token = await issueAuthToken(u.id, "reset_password", 1);
    const link = `${origin(req)}/?reset=${token}`;
    await sendEmail({ to: email, subject: "Reset your MAX password", text: `Reset your password: ${link}\n\nThis link expires in 1 hour. If you didn't ask for this, ignore this email.` });
    if (emailEchoEnabled()) devLink = link;
  }
  // Always the same answer so nobody can discover which emails are registered.
  return json({ ok: true, message: "If that email has an account, we've sent a reset link.", devLink });
});
