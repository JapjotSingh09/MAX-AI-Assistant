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
| **Android app** (Kotlin, Compose, Material 3) | `android/` | ⚠️ Source written, **not compiled or run** — the authoring environment had no JDK/Android SDK. Build it with the steps in `DEVELOPMENT_SETUP.md` and expect to fix small compile errors on first sync. |
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

**Local first, cloud when needed.** `"Open YouTube"`, `"Turn flashlight on"`, `"Set timer for 10 minutes"` are
recognised by a rule-based parser (on the device and on the server) — instant, free, offline-capable.
Questions, ambiguity and conversation go to the cloud AI through the backend.

### Command pipeline

```
text/voice → normalize → local parser ─┐
                                       ├→ CommandIntent → schema validation → permission check
             cloud AI (if needed) ─────┘        → confirmation (if required) → AndroidActionExecutor
                                                → real result → MAX reply → activity log
```

The AI only *suggests* `{ "action": "OPEN_APP", "parameters": {...} }`. The action must be in the whitelist
(`OPEN_APP, CALL_CONTACT, OPEN_DIALER, SEND_SMS, OPEN_WHATSAPP, OPEN_MAPS, NAVIGATE, SET_ALARM, SET_TIMER,
CREATE_REMINDER, OPEN_CAMERA, OPEN_BROWSER, ADJUST_VOLUME, TOGGLE_FLASHLIGHT, OPEN_SETTINGS, SHOW_NOTIFICATIONS`)
and its parameters must pass a strict schema, otherwise it is **rejected**. Calls and messages **always** require
confirmation, even if the AI says otherwise. No shell commands or code are ever executed.

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
| `GET /api/usage`, `POST /api/devices` | Usage, device registration |
| `GET /api/health` (alias `/health`) | Health |

Errors are `{ "error": { "code", "message" } }` with friendly messages; 429 includes `Retry-After`.

## Android permissions

Requested only when needed, with an explanation first: `RECORD_AUDIO` (tap-to-talk only), `READ_CONTACTS` ("Call Dad"),
`CALL_PHONE` (real calls after confirmation; otherwise the dialer opens), `POST_NOTIFICATIONS`, `SET_ALARM`.
Optional, user-enabled in Android Settings: Notification access, Accessibility (never used for spying/credentials), Overlay.

## Setup, build, test

See **[DEVELOPMENT_SETUP.md](DEVELOPMENT_SETUP.md)**. Quick start for the backend/web console:

```bash
cp .env.example .env        # fill in DATABASE_URL etc.
npm install
npx drizzle-kit push        # creates the tables
npm run build && npm start  # or: npm run dev
```

Tests:

```bash
npx vitest run tests/commands.test.ts tests/ai.test.ts         # unit tests (no server needed)
E2E_BASE_URL=http://localhost:3000 npx vitest run tests/e2e.test.ts   # needs a running server + DB
gradle -p android testDebugUnitTest                              # Android unit tests
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

* The Android app was **not compiled** in the authoring environment. Run the build and fix any first-sync issues.
* Android screens implemented: Login/Sign-up, Home (animated orb), Assistant (chat, voice, confirmation cards), Settings (permissions).
  **Not yet on Android:** Automations/Activity/Memory/Notification Center screens, onboarding, Driving Mode, floating bubble
  (`SYSTEM_ALERT_WINDOW`), accessibility service, Room/DataStore, WorkManager automation runner, wake word. They exist
  (except bubble/accessibility/wake word) in the web console and backend.
* WhatsApp and SMS cannot be sent silently via public APIs: MAX opens the composer with the text filled in and reports
  **"prepared — not sent"**. Reminders open the calendar's new-event screen (Android has no public reminders API).
  "Do Not Disturb at 10 PM" needs Notification Policy access and is not offered as an automation.
* Android blocks background microphone use and restricts background starts; v1 is tap-to-talk only.
* The web console cannot control a phone: browser actions are limited (open site/maps, dialer/SMS links) and everything else
  reports "unsupported" honestly. Automations created on the web run on the Android app.
* The Supabase Edge Function port is documented, not implemented. No token streaming. No payment/plan system (everyone is "Free").

## Roadmap

Android Automations/Activity/Memory/Notification Center UI · WorkManager automation runner · floating bubble · optional
accessibility service · wake-word engine behind a `WakeWordEngine` interface · streaming responses · Supabase Edge Function port ·
load-test results and published capacity numbers.
