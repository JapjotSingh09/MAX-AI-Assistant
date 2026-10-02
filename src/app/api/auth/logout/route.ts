import { clearCookie, destroySession, readToken } from "@/lib/auth";
import { api, isSecure } from "@/lib/http";

export const dynamic = "force-dynamic";

export const POST = api({ auth: false }, async ({ req }) => {
  const token = readToken(req);
  if (token) await destroySession(token);
  const res = Response.json({ ok: true });
  res.headers.append("Set-Cookie", clearCookie(isSecure(req)));
  return res;
});
