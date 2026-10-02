# MAX — Your Personal AI Assistant

MAX is a futuristic personal AI assistant. You talk or type; MAX understands, answers, and performs a
**closed list of safe device actions** (open apps, set timers/alarms, start calls after confirmation, …).

> **Honesty first.** MAX never pretends an action worked. Every action reports its real outcome:
> `completed`, `prepared` (e.g. an SMS composer was opened but nothing was sent), `unsupported`,
> `failed` or `cancelled`.

## What is in this repository

| Part | Where | Status |
| --- | --- | --- |
| **Backend API + web console** (Next.js, PostgreSQL, Drizzle) | `src/` | ✅ Built, type-checked, production-built, unit + end-to-end tested |
| **Android app** (Kotlin, Compose, Material 3) | `android/` | ✅ Compiles (`assembleDebug`/`assembleRelease`), 28 unit tests passing. **Not yet run on a physical device** — see ARCHITECTURE.md §9 |
| **Supabase variant** (SQL migration with RLS) | `supabase/` | ✅ SQL written; not executed against a live Supabase project |
| Load test | `loadtest/k6-load.js`, `docs/LOAD_TESTING.md` | Plan + script; **has not been run** |
| CI | `.github/workflows/ci.yml` | Written; not yet run on GitHub |

## Architecture

```
                     ┌─────────────────────┐
                     │     ANDROID APP     │   Kotlin + Compose
                     │  local-first engine │   (also: web console in this repo)
                     └──────────┬──────────┘
                           HTTPS / REST
                                ▼
                     ┌─────────────────────┐
                     │   BACKEND / API     │   Next.js route handlers (stateless)
                     │ session auth        │   src/app/api/**
                     │ rate limiting       │   src/lib/rateLimit.ts
                     │ AI gateway          │   src/lib/ai/**
                     │ command validation  │   src/lib/commands/**
                     │ usage tracking      │   src/lib/activity.ts
                     └──────────┬──────────┘
              ┌─────────────────┼──────────────────┐
              ▼                 ▼                  ▼
        PostgreSQL        AI provider(s)       SMTP provider
   (Drizzle / Supabase)  OpenAI · Gemini ·    (transactional)
                         OpenRouter · custom
```

**Local first, cloud when needed.** `"Open YouTube"`, `"Turn flashlight on"`, `"Set timer for 10 minutes"`, `"Create a note"` are
recognised by a rule-based parser (on the device **and** on the server) — instant, free, offline-capable.
Questions, ambiguity and conversation go to the cloud AI through the backend.

### The tool registry

Everything MAX can do is ONE entry in a registry — `src/lib/tools/registry.ts` (30 tools). Each entry declares a
name, a description for the AI, a strict zod schema, a confirmation policy, and whether it runs on the device
or in the backend. That single list drives the AI's tool menu, the local command parser, the validation layer
and the Android executor. There is no second list to keep in sync, and nothing the model says is ever executed
without passing that same validation.

```
User input
   ↓  normalise; strip "hey max" / "please"
Local parser (device + server, same rules)  ──→  handled offline, no AI call
   ↓ not recognised
Backend: AI sees the registry as real function definitions
   ↓ model calls a tool
validateIntent()  ← closed whitelist + strict schema + confirmation policy
   ↓
device tool → confirmation card → permission check → AndroidActionExecutor → REAL result
server tool → run against this user's own rows → reply
   ↓
reply → activity log → TTS
```

Calls, messages, note deletion and whole-store deletes **always** require a confirmation tap, even if the model
claims otherwise. No shell commands, arbitrary code or model-supplied Intent URIs are ever executed.

### Command pipeline

```
text/voice → normalize → local parser ─┐
                                       ├→ CommandIntent → schema validation → permission check
             cloud AI (if needed) ─────┘        → confirmation (if required) → AndroidActionExecutor
                                                → real result → MAX reply → activity log
```

The AI only *suggests* a tool call. The tool must be in the registry whitelist and its parameters must pass a
strict schema, otherwise it is **rejected and logged as `REJECTED`**. Calls, messages and deletions **always**
require confirmation, even if the AI says otherwise. No shell commands or code are ever executed.

## Documentation

* **[ARCHITECTURE.md](ARCHITECTURE.md)** — full architecture: frontend, Android app, backend, database,
  AI provider, authentication, API flow, voice flow, security model, every Android permission and its reason,
  and every Android limitation with the closest legitimate workaround.
* **[DEVELOPMENT_SETUP.md](DEVELOPMENT_SETUP.md)** — local setup and the signed-APK build steps.

## Tech stack

* **Backend:** Next.js 16 (route handlers), TypeScript, Zod, PostgreSQL, Drizzle ORM, Node `crypto` (scrypt), Nodemailer (SMTP)
* **Web console:** React 19, Tailwind CSS 4, Web Speech API
* **Android:** Kotlin, Jetpack Compose, Material 3, Coroutines/StateFlow, ViewModel, Navigation Compose, OkHttp, EncryptedSharedPreferences, Gradle Kotlin DSL, JDK 17
* **Tests:** Vitest (unit + HTTP end-to-end), JUnit (Android parser/validator)

## Database

Tables (UUID keys, timestamps, FKs, indexes): `profiles`, `user_preferences`, `conversations`, `messages`,
`assistant_actions`, `automations`, `activity_logs`, `usage_events`, `devices`, plus `memories`, and for the
reference backend `users`, `sessions`, `auth_tokens`, `rate_limits`. Schema: `src/db/schema.ts`.
Supabase equivalent with RLS policies: `supabase/migrations/`.

Indexes: `(user_id, created_at)` on every user-owned table, `(conversation_id, created_at)` on messages,
`(user_id, created_at, id)` on activity for cursor pagination.

**Pagination:** conversations, messages, activity and automations use **cursor pagination**
(`?limit=25&cursor=...`, ordered by `(created_at, id)`). No endpoint loads unbounded history.

## Security model

* **Never trust the client's `user_id`.** The user id always comes from the verified session token.
  Every query is filtered with `WHERE user_id = <session user>`; foreign ids return 404.
* **RLS:** the Supabase migration enables Row Level Security with `user_id = auth.uid()` policies on every
  user-owned table. In the reference Postgres backend the equivalent isolation is enforced in the API layer
  (a single DB role cannot use RLS without per-request role switching) and is covered by an automated
  end-to-end test (`user A cannot read or modify user B's data`).
* **No secrets in the APK.** AI keys, service-role key, SMTP password and DB password live only in backend env vars.
  Android holds only the public backend URL and the user's session token (encrypted via Android Keystore).
* Passwords: salted scrypt. Sessions/reset/verify tokens: random 256-bit, only SHA-256 hashes stored.
* Login errors are generic (no account enumeration on login/forgot-password). Password reset signs out all sessions.
* Validation of every request body (Zod), strict AI-action schema, closed whitelist.
* Logs are structured JSON (`requestId, userId, endpoint, status, latencyMs, errorCategory`) and never contain
  passwords, tokens, API keys, message bodies or notification content.
* Security headers, HTTPS-only network config in Android (cleartext only for emulator/localhost).
* Notifications stay **on the phone** (in memory, titles only) and are never uploaded.

## AI architecture

`AIProvider` interface → `OpenAICompatibleProvider` (covers OpenAI, OpenRouter, Gemini's OpenAI-compatible endpoint,
and any custom compatible server) → `createProvider` (factory) → `AIProviderRouter` (primary → fallback → graceful error).
Per request: timeout (`AI_TIMEOUT_MS`), at most `AI_MAX_RETRIES` retries with exponential backoff **only** for 429/5xx/network,
no retry on timeouts or auth errors. If every provider fails the user sees *"MAX's AI service is temporarily unavailable."*
If no key is configured MAX still handles all local commands.

Quotas: per-user per-minute and per-day (`RATE_LIMIT_AI_PER_MINUTE`, `RATE_LIMIT_AI_PER_DAY`), message length and
conversation length limits. Usage (provider, model, tokens, latency, success) is stored in `usage_events`.

**Not implemented:** token streaming. The web console has a Stop button that cancels the in-flight request.

## Authentication & email

Sign up, login, logout, session restoration, forgot/reset password, email verification (`REQUIRE_EMAIL_VERIFICATION`
makes it mandatory). Duplicate-signup protection: UI flags (`isSigningUp`, `isLoggingIn`), cooldown after HTTP 429, and a unique
email constraint server-side. Emails go through `EMAIL_PROVIDER`:

* `console` — development only: links are printed to the server log **and echoed in the API response** so the flow is testable.
* `smtp` — production: configure `SMTP_HOST/PORT/USER/PASSWORD/FROM` for a transactional provider (Brevo, SES, Postmark, Mailgun…).
  Do not rely on Supabase's built-in email sender for thousands of users.

## API

| Method & path | Purpose |
| --- | --- |
| `POST /api/auth/signup · login · logout · forgot · reset · verify`, `GET /api/auth/me` | Authentication |
| `GET/PATCH/DELETE /api/profile` | Profile, preferences, delete account |
| `GET/POST/DELETE /api/conversations`, `GET /api/conversations/:id/messages` | History (cursor paginated) |
| `POST /api/chat` | Full pipeline (local parser → AI → validation → saved action) |
| `POST /api/assistant/command` | Parse/validate only |
| `PATCH /api/actions/:id` | Device reports the real outcome of an action |
| `GET/POST/DELETE /api/activity` | Activity feed (cursor paginated) / log a device-side action |
| `GET/POST /api/automations`, `PATCH/DELETE /api/automations/:id` | Automations |
| `GET/POST/DELETE /api/memories`, `PATCH/DELETE /api/memories/:id` | Memory |
| `GET /api/conversations?q=`, `GET/PATCH/DELETE /api/conversations/:id` | Search, and one conversation: read / rename / delete |
| `GET /api/usage`, `POST /api/devices` | Usage, device registration |
| `GET /api/health` (alias `/health`) | Health |

Errors are `{ "error": { "code", "message" } }` with friendly messages; 429 includes `Retry-After`.

## Troubleshooting

**Signup (or login) answers `500` with "Something went wrong. Please try again."**

Every 5xx now carries a `requestId` and the backend logs the real cause for that id
(`{"level":"error","requestId":"...","error":"...","code":"...","detail":"..."}`).
The two common causes are:

* `database_not_initialized` — `DATABASE_URL` points at a database that has no MAX tables
  (a fresh Supabase/Neon/Render Postgres, or one where the schema was never applied).
  The server creates them itself on the first request (`drizzle/` migrations, applied once
  and recorded in `drizzle.__drizzle_migrations`); if that fails, look for a
  `db.migrate.failed` line in the log. Fix manually with `npm run db:migrate` (or
  `npx drizzle-kit push`) against the same `DATABASE_URL`.
* `database_unavailable` — wrong/unreachable `DATABASE_URL`, bad credentials, or the
  database is refusing connections.
* `database_error` — PostgreSQL rejected the statement itself (constraint violation,
  missing permission, bad input). The `sqlState` in the log identifies which.

`GET /api/health` reports `schema: "ready"` when MAX's tables are in place and
`schema: "failed"` when they are not, so a deployment problem is visible before users hit it.

## Android permissions

Requested only when needed, with an explanation shown first. Full table with reasons in
[ARCHITECTURE.md §6](ARCHITECTURE.md#6-android-permissions-and-why-each-one-exists).

| Permission | Why |
| --- | --- |
| `RECORD_AUDIO` | Push-to-talk voice, and the optional "Hey MAX" service. Opens **only** while listening. |
| `READ_CONTACTS` | Find a number by name for "Call Dad". Never uploaded. |
| `CALL_PHONE` | Place a real call after confirmation; without it the dialer opens instead. |
| `POST_NOTIFICATIONS` | Android 13+: reminders and the persistent "listening" notice. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Required for "Hey MAX" to work with the screen off. |
| `SET_ALARM` (com.android.alarm) | Hands alarms to the system Clock app. |

Optional, enabled by the user in Android Settings: notification access.
**Deliberately not requested:** `SCHEDULE_EXACT_ALARM`, `READ_CALENDAR`, `READ_SMS`, `READ_CALL_LOG`,
`SYSTEM_ALERT_WINDOW`, any accessibility service — see ARCHITECTURE.md for the reasoning on each.

## Setup, build, test

See **[DEVELOPMENT_SETUP.md](DEVELOPMENT_SETUP.md)**. Quick start for the backend/web console:

```bash
cp .env.example .env        # fill in DATABASE_URL etc.
npm install
npm run build && npm start  # or: npm run dev
# The server applies its own migrations (drizzle/) on the first request, so MAX's
# tables are created automatically — even on a brand-new/empty database.
# Manual equivalents (optional): npm run db:migrate | npx drizzle-kit push
```

Tests:

```bash
npx vitest run tests/commands.test.ts tests/tools.test.ts tests/ai.test.ts   # unit tests (no server needed)
E2E_BASE_URL=http://localhost:3000 npx vitest run tests/e2e.test.ts          # needs a running server + DB
cd android && ./gradlew testDebugUnitTest                                    # Android unit tests
cd android && ./gradlew assembleDebug                                        # debug APK
```

## Environment variables

See `.env.example` (all documented). Never commit `.env`; it is git-ignored. Key groups: `DATABASE_URL`, `AI_*`,
`AI_FALLBACK_*`, `EMAIL_PROVIDER` + `SMTP_*`, `RATE_LIMIT_*`, `MAX_MESSAGE_LENGTH`, `MAX_CONVERSATION_LENGTH`.

## Scaling

Designed so that 2,000+ registered users is an *infrastructure sizing* question, not a rewrite: stateless API (any
number of instances), Postgres-backed rate limits, indexed + cursor-paginated queries, per-user quotas, request timeouts,
retry with backoff, provider fallback, usage tracking. **This is not a claim that 2,000 concurrent users are supported.**
Real capacity depends on your database plan/connection limits, AI provider quotas, and email provider plan, and must be
measured with `docs/LOAD_TESTING.md` first. Use a connection pooler (e.g. Supabase pooler / PgBouncer) in production.

## Known limitations

The complete list, with the exact Android rule behind each one, is in
**[ARCHITECTURE.md §8](ARCHITECTURE.md#8-android-limitations-and-what-max-does-instead)**. The short version:

* **"Always listening" is not claimed.** MAX ships a *duty-cycled* microphone foreground service for "Hey MAX":
  short listening windows separated by idle gaps. It is a real, working implementation of the closest thing
  Android allows a third-party app, and it costs battery while it is on.
* SMS and WhatsApp cannot be sent silently through public APIs, so MAX opens the composer with the text filled in
  and reports **"prepared — not sent"**.
* Bluetooth cannot be switched by a normal app, so `SET_BLUETOOTH` opens Bluetooth settings and says so.
* Calendar events cannot be read silently, so `READ_CALENDAR` opens the calendar app instead.
* Reminders use `setAndAllowWhileIdle` rather than `SCHEDULE_EXACT_ALARM`, so a reminder can arrive a few minutes
  late — but it needs no special-access permission and still fires with the screen off.
* The Android app has been compiled and unit-tested, but not yet run on a physical device.
* Android screens implemented: Login/Sign-up, Home (orb + quick actions), Assistant (chat, voice, confirmation),
  History (list / search / rename / delete), Settings (voice, wake word, permissions).
  **Not yet on Android:** Automations, Activity and Memory *screens* (all three APIs work and are reachable by
  voice/command), onboarding, Driving Mode, floating bubble (`SYSTEM_ALERT_WINDOW`), WorkManager automation runner.
* The web console cannot control a phone: browser actions are limited (open site/maps, dialer/SMS links) and
  everything else reports "unsupported" honestly.
* The Supabase Edge Function port is documented, not implemented. No token streaming. No payment system.

## Roadmap

Android Automations/Activity/Memory/Notification Center UI · WorkManager automation runner · floating bubble · optional
accessibility service · wake-word engine behind a `WakeWordEngine` interface · streaming responses · Supabase Edge Function port ·
load-test results and published capacity numbers.
