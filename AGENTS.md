# AGENTS.md — MAX AI Assistant

Mandatory rules for anyone (human or AI agent) changing this repository.

---

## 1. Authentication is a critical, protected workflow

Login, signup, session restoration, logout and per-user data isolation are the
most important behaviour in this product. They are protected: a change that
breaks them is a release blocker, even if the rest of the app works perfectly.

### Non-negotiable rules

1. **Never break** login, signup, session restoration, logout, or user-data
   isolation. There are no exceptions for "unrelated" work.
2. **Do not refactor authentication code during unrelated feature work.** If a
   task does not touch auth, leave `src/lib/auth.ts`, `src/lib/authFlow.ts`,
   `src/app/api/auth/**`, `src/components/client.ts` and the Android auth client
   alone. Working auth code is not the place to "clean things up".
3. **Fix any regression your change introduced before calling the task done.**
   A broken login means the task is not complete, regardless of how well the
   requested feature works.
4. Keep tests repeatable and safe. **Never delete production users, documents or
   data.** Tests may only create throwaway rows with unique identifiers.
5. Never log or print passwords, access tokens, API keys, session cookies or
   `DATABASE_URL`. Use `redact()` from `src/lib/http.ts` for anything that
   reaches a log line.

---

## 2. Required verification before calling ANY task complete

Every change — UI, backend, database, auth, navigation, dependencies, build
config — must pass all of the following:

```bash
npm run typecheck     # tsc --noEmit
npm run test          # vitest run (no database needed)
npm run smoke:login   # LIVE login against a running backend
```

`npm run verify:login` runs the first two plus the smoke test in one go.

### Which auth tests to run

| What you changed | Minimum required |
| --- | --- |
| Anything at all | `npm run typecheck && npm run test` |
| UI, styling, layout | + login smoke test (a CSS/React change can hide the auth screen) |
| Auth, database, API routes, config, dependencies, build | + **full** auth regression: `npm run test:auth` **and** `npm run smoke:login` |

### Running the live login check

```bash
# terminal 1 — the backend
npm run dev

# terminal 2 — the login smoke test
npm run smoke:login
# or against a deployed backend:
SMOKE_BASE_URL=https://your-app.onrender.com npm run smoke:login
```

`scripts/login-smoke.mjs` creates a **throwaway** account, then verifies:
valid login, invalid-credential error, session restoration, profile load,
logout invalidation, and re-login. It never deletes or edits existing data and
never prints secrets.

To verify a specific authorised account instead of creating one:

```bash
SMOKE_EMAIL=you@example.com SMOKE_PASSWORD='...' npm run smoke:login
```

---

## 3. Reporting rules — read this carefully

- **A successful build, a clean `tsc`, or passing mocked tests are NOT proof
  that login works.** They must be reported as what they are.
- If a real backend login test was performed and passed, say so explicitly and
  quote the check list from `scripts/login-smoke.mjs`.
- If the live backend was unavailable, report the result as **BLOCKED** or
  **NOT VERIFIED**. Never report a passing login test based only on syntax
  checks, a successful build, or mocked tests.
- Every task completion summary must list:
  1. the exact tests executed and their results,
  2. whether a real backend login was verified,
  3. any remaining limitation or blocker, and
  4. anything the user must do.
- Be honest about scope. A skipped, blocked or partially run check must be named

---

## 4. Where the login code lives

| Concern | File |
| --- | --- |
| Password hashing, sessions, token reading | `src/lib/auth.ts` |
| Session creation, cookies, email tokens | `src/lib/authFlow.ts` |
| `POST /api/auth/login` | `src/app/api/auth/login/route.ts` |
| Signup / logout / me / forgot / reset / verify | `src/app/api/auth/**/route.ts` |
| Browser fetch wrapper, timeouts, friendly errors | `src/components/client.ts` |
| Login/signup form UI | `src/components/AuthScreen.tsx` |
| Session boot / restore | `src/components/AppShell.tsx` |
| Request wrapper, error classification | `src/lib/http.ts` |
| Rate limiting (hits the DB on every auth call) | `src/lib/rateLimit.ts` |
| Database pool and its timeouts | `src/db/index.ts` |
| Android HTTP client and timeouts | `android/.../data/remote/MaxApiClient.kt` |
| Android login screen | `android/.../ui/screens/LoginScreen.kt` |

---

## 5. "Request Timed Out" during login — do not regress this

This bug happened, was fixed, and came back. The root cause was in
`src/db/index.ts`: the `pg.Pool` was created **without**
`connectionTimeoutMillis`, so pg armed no timer at all. A database that could
not be reached blocked every auth request until the OS stopped retrying the TCP
handshake — ~21s on Windows and ~127s on Linux (the production host). The
browser gives up at 30s, so the user saw "The request timed out." with no clue
about the real problem.

Three invariants now prevent it, all asserted by `tests/authRegression.test.ts`:

1. The pool sets `connectionTimeoutMillis`, `statement_timeout` and
   `query_timeout`.
2. **Every one of those timeouts is strictly less than
   `REQUEST_TIMEOUT_MS`** in `src/components/client.ts`, so the server always
   answers with a real reason before the client gives up.
3. A pool timeout is classified as `503 database_unavailable` with a real
   message — never a generic 500, never a hang.

Rules that follow from this:

- **Do not raise `REQUEST_TIMEOUT_MS` to paper over a slow or unreachable
  backend.** That hides the fault and reintroduces the symptom.
- **Do not remove the database timeouts.** If a query legitimately needs longer,
  raise `DB_STATEMENT_TIMEOUT_MS` in `.env` — and keep it under the client
  timeout.
- Any new outbound HTTP call to the database must carry a bounded timeout.

---

## 6. Environment notes

- `DATABASE_URL` is required; the app throws at import without it.
- Production runs on Render (Linux). Prefer reasoning about Linux timeouts when
  choosing a default — Windows timeouts are far shorter and will hide bugs.
- Backend timeouts are tunable: `DB_CONNECT_TIMEOUT_MS` (default 8000),
  `DB_STATEMENT_TIMEOUT_MS` (default 20000), `DB_QUERY_TIMEOUT_MS` (default
  25000).

  as such, never quietly omitted.
