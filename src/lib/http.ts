import { randomUUID } from "node:crypto";
import { ZodError, type ZodType } from "zod";
import { ensureDatabaseSchema } from "@/db/migrate";
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

// Unexpected errors used to be flattened into one generic 500, which made a
// broken deployment (wrong DATABASE_URL, database with no schema, ...) look like
// a random bug and reduced the log line to just `error: "Error"`. These tables
// let us name the real problem instead.
// SQLSTATE 42P01/42703/42883/3F000/3D000/42P07 = table / column / function /
// schema / database missing, i.e. MAX's schema was never applied.
const DB_SCHEMA_ERRORS = new Set(["42P01", "42703", "42883", "42P07", "3F000", "3D000", "42P06", "42704"]);
// Could not connect to, or authenticate against, Postgres (libuv codes + SQLSTATE).
const DB_UNAVAILABLE_ERRORS = new Set(["ECONNREFUSED", "ENOTFOUND", "ETIMEDOUT", "EHOSTUNREACH", "ECONNRESET", "EPIPE", "28P01", "28000", "28P02", "53300", "57P01", "57P02", "57P03", "55P03", "08000", "08001", "08003", "08004", "08006", "08007", "08008"]);

// A SQLSTATE is exactly five upper-case alphanumerics (42P01). Anything else in
// `code` is a libuv/socket code. This is what lets us tell "the database said no"
// apart from "the driver never reached the database".
const isSqlState = (code: string | undefined): boolean => Boolean(code) && /^[0-9A-Z]{5}$/.test(code as string);

// drizzle wraps driver errors (DrizzleQueryError), so the SQLSTATE/libuv code we
// need to classify a failure lives somewhere on the `cause` chain.
function errorCode(err: unknown): string | undefined {
  let cur: unknown = err;
  for (let i = 0; i < 6 && cur; i++) {
    const code = (cur as { code?: unknown }).code;
    if (typeof code === "string" && code) return code;
    cur = (cur as { cause?: unknown }).cause;
  }
  return undefined;
}

// Nothing that reaches a log line or an HTTP body may contain the connection
// string, a password or a key. Drizzle's own error message embeds the statement
// AND its bound params (which include the password hash), so we only ever use
// the deepest driver message and scrub it.
const SECRET_PATTERNS: RegExp[] = [
  /postgres(?:ql)?:\/\/[^\s'"]+/gi,
  /\b(password|passwd|pwd|secret|token|api[_-]?key|apikey)\b\s*[=:]\s*\S+/gi,
  /:\/\/[^@\s/]+:[^@\s]+@/g,
];

export function redact(text: string): string {
  let out = text;
  for (const pattern of SECRET_PATTERNS) out = out.replace(pattern, "[redacted]");
  return out;
}

/**
 * The deepest cause is the one that actually explains the failure: pg's error
 * (`relation "public.users" does not exist`), while drizzle's wrapper only says
 * which statement failed. Returns the class, the driver code, the SQLSTATE, the
 * constraint/table the database named, and a scrubbed message — never params,
 * never the connection string.
 */
export function describeDbError(err: unknown) {
  let root = err as { name?: string; message?: string; constraint?: string; table?: string; cause?: unknown };
  for (let i = 0; i < 6 && root?.cause; i++) root = root.cause as typeof root;
  const code = errorCode(err);
  const stage = (err as { stage?: unknown })?.stage;
  return {
    error: root?.name ?? (err as { name?: string })?.name,
    code,
    // The database's own error code, which is what a DBA needs to act.
    sqlState: isSqlState(code) ? code : undefined,
    stage: typeof stage === "string" ? stage : undefined,
    constraint: typeof root?.constraint === "string" ? root.constraint : undefined,
    relation: typeof root?.table === "string" ? root.table : undefined,
    detail: typeof root?.message === "string" ? redact(root.message).slice(0, 300) : undefined,
  };
}

// Backwards-compatible alias used by /api/health.
export const errorDetail = describeDbError;

// Only a unique violation actually means "this email already exists"; signup uses
// it to answer 409 and must let every other database failure through so it gets
// classified and logged instead of being reported as a duplicate account.
export const isUniqueViolation = (err: unknown) => errorCode(err) === "23505";

export const DB_NOT_INITIALIZED = "MAX's database isn't set up yet. Please try again in a moment.";

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
      // The tables MAX needs must exist before the first query (the rate limiter
      // writes to `rate_limits`). On an empty database this applies the idempotent
      // migrations in drizzle/ once; afterwards it is a cached no-op. If it cannot
      // be applied, say so plainly instead of hiding it behind a generic error.
      try {
        await ensureDatabaseSchema();
      } catch (err) {
        // The SQLSTATE and the driver's message go to the log (scrubbed); the
        // client only learns that the database is not ready yet.
        console.error(JSON.stringify({ level: "error", requestId, msg: "db.migrate.failed", ...describeDbError(err) }));
        throw new ApiError(503, "database_not_initialized", DB_NOT_INITIALIZED);
      }
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
        const code = errorCode(err);
        // Log the real cause (error class + driver code + SQLSTATE + driver
        // message) tied to the request id, so the exact failure is findable in
        // the server logs without revealing internals to the user.
        console.error(JSON.stringify({ level: "error", requestId, ...describeDbError(err) }));
        if (code && DB_SCHEMA_ERRORS.has(code)) {
          // The database answered, but MAX's tables are missing: DATABASE_URL points
          // at a database that has never had the schema applied.
          status = 503;
          category = "database_not_initialized";
          res = Response.json({ error: { code: category, message: DB_NOT_INITIALIZED, requestId } }, { status });
        } else if (code && DB_UNAVAILABLE_ERRORS.has(code)) {
          status = 503;
          category = "database_unavailable";
          res = Response.json(
            { error: { code: category, message: "MAX can't reach its database right now. Please try again in a moment.", requestId } },
            { status },
          );
        } else if (isSqlState(code)) {
          // The database itself rejected the statement (constraint, permission,
          // bad input). That is a server-side problem, not the caller's fault,
          // and saying "internal" hid the fact that Postgres had already told us
          // what went wrong. Classified, but still without internals.
          status = 503;
          category = "database_error";
          res = Response.json(
            {
              error: {
                code: category,
                message: "MAX's database rejected that request. Please try again in a moment.",
                requestId,
              },
            },
            { status },
          );
        } else {
          status = 500;
          category = "internal";
          res = Response.json(
            { error: { code: "internal", message: `Something went wrong. Please try again. (Reference: ${requestId})`, requestId } },
            { status },
          );
        }
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
