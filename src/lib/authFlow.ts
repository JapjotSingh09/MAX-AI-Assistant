import { and, eq, gt, isNull } from "drizzle-orm";
import { db } from "@/db";
import { authTokens } from "@/db/schema";
import { newToken, sessionCookie, createSession, sha256 } from "@/lib/auth";
import { config } from "@/lib/config";
import { emailEchoEnabled, sendEmail } from "@/lib/email";
import { isSecure } from "@/lib/http";

export const origin = (req: Request) => config.appUrl || new URL(req.url).origin;

type TokenType = "verify_email" | "reset_password";

export async function issueAuthToken(userId: string, type: TokenType, hours: number) {
  const token = newToken();
  await db.insert(authTokens).values({
    userId,
    type,
    tokenHash: sha256(token),
    expiresAt: new Date(Date.now() + hours * 3600 * 1000),
  });
  return token;
}

// Marks a token as used and returns its owner, or null if invalid/expired/used.
export async function consumeAuthToken(token: string, type: TokenType) {
  const hash = sha256(token);
  const [row] = await db
    .update(authTokens)
    .set({ usedAt: new Date() })
    .where(and(eq(authTokens.tokenHash, hash), eq(authTokens.type, type), isNull(authTokens.usedAt), gt(authTokens.expiresAt, new Date())))
    .returning({ userId: authTokens.userId });
  return row?.userId ?? null;
}

export async function sendVerificationEmail(req: Request, userId: string, email: string) {
  const token = await issueAuthToken(userId, "verify_email", 48);
  const link = `${origin(req)}/?verify=${token}`;
  const r = await sendEmail({ to: email, subject: "Verify your MAX account", text: `Welcome to MAX!\n\nVerify your email: ${link}\n\nThis link expires in 48 hours.` });
  return { sent: r.sent, devLink: emailEchoEnabled() ? link : undefined };
}

// Creates a session and builds the response. Android clients (header X-MAX-Client)
// also get the token in the JSON body because they store it in encrypted storage.
export async function sessionResponse(req: Request, userId: string, body: Record<string, unknown>, status = 200) {
  const session = await createSession(userId, req.headers.get("user-agent"));
  const payload: Record<string, unknown> = { ...body };
  if (req.headers.get("x-max-client")) payload.token = session.token;
  const res = Response.json(payload, { status });
  res.headers.append("Set-Cookie", sessionCookie(session.token, session.expiresAt, isSecure(req)));
  return res;
}
