import { randomUUID } from "node:crypto";
import { ZodError, type ZodType } from "zod";
import { getUserFromRequest, type AuthUser } from "@/lib/auth";
import { config } from "@/lib/config";
import { rateLimit } from "@/lib/rateLimit";

// One error type for every expected failure. Messages are user-friendly: raw
// exceptions are never sent to the client.
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public retryAfter?: number,
  ) {
    super(message);
  }
}

export const TOO_MANY = "Too many requests. Please wait a moment and try again.";

export function getIp(req: Request) {
  return req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || req.headers.get("x-real-ip") || "unknown";
}

export const isSecure = (req: Request) =>
  new URL(req.url).protocol === "https:" || req.headers.get("x-forwarded-proto") === "https";

export async function enforceLimit(key: string, limit: number, windowSeconds: number, message = TOO_MANY) {
  const r = await rateLimit(key, limit, windowSeconds);
  if (!r.ok) throw new ApiError(429, "rate_limited", message, r.retryAfterSeconds);
}

export async function readJson<T>(req: Request, schema: ZodType<T>): Promise<T> {
  const text = await req.text();
  if (text.length > 100_000) throw new ApiError(413, "too_large", "That request is too large.");
  let raw: unknown;
  try {
    raw = text ? JSON.parse(text) : {};
  } catch {
    throw new ApiError(400, "bad_json", "Invalid request.");
  }
  return schema.parse(raw);
}

export type Ctx<Auth extends boolean> = {
  req: Request;
  user: Auth extends true ? AuthUser : AuthUser | null;
  params: Record<string, string>;
  ip: string;
  requestId: string;
};

type Opts<Auth extends boolean> = {
  auth: Auth;
  // Per-IP limit used for login/signup/etc. Authenticated routes get a per-user limit.
  authBucket?: string;
};

type Handler<Auth extends boolean> = (ctx: Ctx<Auth>) => Promise<Response>;

// Wrapper used by every route. It gives each request: a request ID, session auth,
// rate limiting, friendly error mapping and one structured log line.
export function api<Auth extends boolean>(opts: Opts<Auth>, handler: Handler<Auth>) {
  return async (req: Request, routeCtx?: { params: Promise<Record<string, string>> }): Promise<Response> => {
    const started = Date.now();
    const requestId = randomUUID();
    const url = new URL(req.url);
    let userId: string | undefined;
    let status = 500;
    let category: string | undefined;
    let res: Response;

    try {
      const ip = getIp(req);
      const params = routeCtx?.params ? await routeCtx.params : {};
      if (opts.authBucket) {
        await enforceLimit(`${opts.authBucket}:ip:${ip}`, config.limits.authPerMinutePerIp, 60);
      }
      const user = await getUserFromRequest(req);
      if (opts.auth && !user) throw new ApiError(401, "unauthorized", "Your session has expired. Please sign in again.");
      userId = user?.id;
      if (user) await enforceLimit(`api:user:${user.id}`, config.limits.apiPerMinutePerUser, 60);
      res = await handler({ req, user: user as Ctx<Auth>["user"], params, ip, requestId });
      status = res.status;
    } catch (err) {
      if (err instanceof ApiError) {
        status = err.status;
        category = err.code;
        res = Response.json({ error: { code: err.code, message: err.message } }, { status });
        if (err.retryAfter) res.headers.set("Retry-After", String(err.retryAfter));
      } else if (err instanceof ZodError) {
        status = 400;
        category = "validation";
        res = Response.json({ error: { code: "validation", message: err.issues[0]?.message || "Invalid request." } }, { status });
      } else {
        status = 500;
        category = "internal";
        // Log only the error NAME, never the message (it may contain private data).
        console.error(JSON.stringify({ level: "error", requestId, error: (err as Error)?.name }));
        res = Response.json({ error: { code: "internal", message: "Something went wrong. Please try again." } }, { status });
      }
    }

    res.headers.set("X-Request-Id", requestId);
    // Structured log: no passwords, tokens or message bodies.
    console.log(
      JSON.stringify({
        level: status >= 500 ? "error" : "info",
        requestId,
        userId,
        endpoint: `${req.method} ${url.pathname}`,
        status,
        latencyMs: Date.now() - started,
        errorCategory: category,
      }),
    );
    return res;
  };
}

export const json = (data: unknown, status = 200) => Response.json(data, { status });

// Cursor pagination helpers: cursor = base64("<iso timestamp>|<id>").
export function encodeCursor(date: Date, id: string) {
  return Buffer.from(`${date.toISOString()}|${id}`).toString("base64url");
}
export function decodeCursor(cursor: string | null): { date: Date; id: string } | null {
  if (!cursor) return null;
  try {
    const [iso, id] = Buffer.from(cursor, "base64url").toString().split("|");
    const date = new Date(iso);
    if (!id || Number.isNaN(date.getTime())) return null;
    return { date, id };
  } catch {
    return null;
  }
}
export function pageSize(req: Request, def = 30, max = 50) {
  const n = Number(new URL(req.url).searchParams.get("limit"));
  return Number.isFinite(n) && n > 0 ? Math.min(Math.floor(n), max) : def;
}
