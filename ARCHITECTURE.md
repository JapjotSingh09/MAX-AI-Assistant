# MAX — Architecture

This document explains how MAX actually works, where each decision was made, and where Android's rules stop
it from doing what a user might expect. Everything here describes code that exists in this repository.

---

## 1. The whole system

```
┌──────────────────┐      HTTPS/JSON       ┌────────────────────────┐
│  ANDROID APP     │ ────────────────────► │  BACKEND (Next.js 16)  │
│  Kotlin + Compose│   Authorization:     │  route handlers        │
│                  │   Bearer <session>   │  stateless             │
│  tools/          │ ◄──────────────────── │                        │
│  speech/         │                       │  • auth (scrypt+sessions)
│  services/       │                       │  • tool registry       │
│  actions/        │                       │  • AI gateway          │
└──────────────────┘                       │  • rate limiting       │
                                           └───────┬────────────────┘
                                                   │
┌──────────────────┐      HTTPS/JSON               │
│  WEB CONSOLE     │ ─────────────────────────────►├──► PostgreSQL (Drizzle)
│  React 19        │                              ├──► AI provider
│  (src/components)│                              └──► SMTP
└──────────────────┘
```

Three clients, one backend. The Android app and the web console call exactly the same API, so a feature
added to the backend is available to both without duplicating business logic.

---

## 2. Frontend (web console)

* **Stack:** React 19, Tailwind CSS 4, Next.js 16 App Router.
* **Where:** `src/app/` (routes), `src/components/` (screens).
* **Role:** a full console for the same backend — chat, automations, memory, activity, profile.
* **Honesty rule:** the browser cannot control a phone. `src/components/webActions.ts` returns
  `unsupported` for anything it genuinely cannot do, rather than pretending.

---

## 3. Android app

### 3.1 Structure

```
com.max.assistant
├── MaxApplication.kt          AppContainer - a tiny service locator, built once
├── MainActivity.kt            the single Activity; handles "Hey MAX" intents
├── ai/                        AIProvider interface + BackendAIProvider
├── actions/                   AndroidActionExecutor + one class per action family
│                              (the ONLY code that touches Android to DO something)
├── assistant/                 CommandIntent (the whitelist), CommandParser,
│                              AssistantViewModel (the pipeline + state)
├── data/local/TokenStore.kt   encrypted session token (AndroidX Security-Crypto)
├── data/remote/               MaxApiClient (OkHttp) + friendly error mapping
├── navigation/                MaxNavigation - tabs, ViewModel wiring, wake-word hand-off
├── permissions/               PermissionManager - checks and explains, never decides policy
├── services/                  WakeWordService, AssistantNotifications, ReminderScheduler,
│                              MaxNotificationListenerService
├── settings/VoiceSettings.kt  persisted voice preferences
├── speech/                    SpeechInput (STT) and SpeechOutput (TTS)
├── tools/                     ToolRegistry (device side) + LocalStore (notes/tasks)
└── ui/                        Compose screens, the MAX orb, theme
```

### 3.2 The two registries

MAX has one registry **per platform**, and they must agree on names:

| | Backend | Android |
| --- | --- | --- |
| Names, descriptions, schemas | `src/lib/tools/registry.ts` | `tools/ToolRegistry.kt` |
| Confirmation policy | `confirm: "always"` | `IntentValidator.alwaysConfirm` |
| Where it runs | `site: "device" \| "server"` | (implicit: everything not server-side) |
| Runtime permissions | — | `ToolRegistry.permissionsFor` |
| Android restrictions | described in the AI's tool description | `ToolRegistry.limitFor` |

A test on each side asserts its own list is complete (`tests/tools.test.ts`,
`ToolRegistryTest.every action type has a spec`). If a tool is added on one side only, the build breaks
rather than the feature quietly failing at runtime.

### 3.3 Gradle / Kotlin / Compose setup

| | |
| --- | --- |
| Gradle | 8.7 (wrapper), Android Gradle Plugin 8.5.2 |
| Kotlin | 1.9.24, JVM target 17 |
| Compose | BOM 2024.06, Material 3 |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 34 / 34 |
| Networking | OkHttp 4.12 |
| Storage | AndroidX Security-Crypto 1.1.0-alpha06, plain SharedPreferences for settings |
| Concurrency | kotlinx-coroutines 1.8.1 |
| Tests | JUnit 4.13.2 + `kotlinx-coroutines-test` |

Dependencies were deliberately **not** expanded. Notes and tasks use a small JSON file rather than Room,
settings use SharedPreferences rather than DataStore: at this data volume, a database and an async
preferences API would add a dependency, a schema and a migration story for no real benefit.

---
## 4. Backend

### 4.1 API surface

| Route | Methods | Purpose |
| --- | --- | --- |
| `/api/auth/signup` `/login` `/logout` `/me` `/forgot` `/reset` `/verify` | POST/GET | Session auth |
| `/api/chat` | POST | The full command pipeline for one message |
| `/api/assistant/command` | POST | Parse/validate a command without an AI call |
| `/api/conversations` | GET (`?q=`), POST, DELETE | List / search / create / clear |
| `/api/conversations/:id` | GET, PATCH, DELETE | Read / rename / delete one conversation |
| `/api/conversations/:id/messages` | GET | Cursor-paginated messages + action cards |
| `/api/memories`, `/api/memories/:id` | CRUD | "Remember that…", "What do you remember?", "Forget that…" |
| `/api/actions/:id` | PATCH | The device reports an action's REAL outcome |
| `/api/activity` | GET, POST, DELETE | History; the device logs local actions here |
| `/api/automations` | CRUD | Scheduled actions |
| `/api/profile`, `/api/usage`, `/api/devices` | GET/POST | Profile, quota, device registration |
| `/api/health` (alias `/health`) | GET | Liveness + schema state |

Every route goes through one wrapper, `api()` in `src/lib/http.ts`, which supplies: request id, session
auth, rate limiting, zod validation, friendly error mapping, one structured log line, and secret redaction
in log output.

### 4.2 API flow for one message

```
POST /api/chat { message, conversationId? }
  │
  ├─ Verify the conversation belongs to THIS user (404 otherwise), and enforce
  │  the per-conversation message cap.
  ├─ Store the user's message.
  │
  ├─ parseCommand(text)                      ← local, free, offline
  │    ├─ memory / memory_list / memory_forget  → handled directly, scoped to userId
  │    ├─ intent                                 → save an action card, reply
  │    └─ null                                   → fall through to the AI
  │
  ├─ AI path (src/lib/assistant.ts)
  │    ├─ rate limit per minute and per day
  │    ├─ load this user's memories + last 12 messages
  │    ├─ chat(messages, tools = AI_TOOLS)
  │    │     └─ the model returns either text, or tool_calls[]
  │    │           tool_calls → validateIntent()  ← the closed whitelist, always
  │    │             ├─ server tool  → run it against this user's rows
  │    │             ├─ device tool  → save an action card for the phone to run
  │    │             └─ rejected     → log ACTION_REJECTED, explain, never execute
  │    └─ fallback: if the provider rejects the `tools` field, retry once in a
  │       plain-JSON mode with the same registry rendered into the prompt.
  │
  ├─ Store the assistant message, touch the conversation, log activity.
  └─ { conversationId, userMessage, assistantMessage, action?, aiUnavailable }
```

### 4.3 Database

PostgreSQL via Drizzle (`src/db/schema.ts`). The SQL in `drizzle/` is idempotent, and the server applies
and **verifies** it itself at startup (`src/instrumentation.ts`) and on demand from `/api/health`, so a
fresh managed database needs no manual migration step.

Tables: `users`, `sessions`, `auth_tokens`, `profiles`, `user_preferences`, `conversations`, `messages`,
`assistant_actions`, `automations`, `activity_logs`, `usage_events`, `devices`, `memories`, `rate_limits`.

**User isolation** is structural, not incidental: every user-owned table has a `user_id` column with an
index on it, and every query in every route is `WHERE user_id = <the id from the session>`. The session
token is the only source of that id — it is never read from a request body. `tests/e2e.test.ts` includes
an explicit "user A cannot read or modify user B's data" test.

---

## 5. AI provider

### 5.1 Configuration and the provider abstraction

`src/lib/ai/router.ts` turns environment variables into a provider:

```
AI_PROVIDER = openai | openrouter | gemini | custom
AI_MODEL, AI_API_KEY, AI_BASE_URL
AI_FALLBACK_PROVIDER / _MODEL / _API_KEY   (tried when the primary fails)
```

All four are reachable through one class, `OpenAICompatibleProvider`, because they share the
`/chat/completions` shape. Adding a provider that is not OpenAI-compatible means adding one new class
behind the same `AIProvider` interface — nothing else changes. When no provider is configured the router
reports `configured === false`, and the API returns the friendly
`"MAX's AI service is temporarily unavailable."` plus `aiUnavailable: true` rather than a generic 500.

### 5.2 Tool calling

`src/lib/ai/toolCatalog.ts` derives the model's function definitions from the zod schemas in the tool
registry using `z.toJSONSchema()`. The derivation:

* strips `$schema` (providers reject it inside a function definition);
* folds `minLength`/`maxLength` into the field description, because several providers silently ignore
  those JSON-Schema keywords;
* forces `additionalProperties: false`, which is what stops the model inventing parameter names.

There is also a **fallback**: the same registry rendered as plain text in the system prompt, for
providers that reject the `tools` field. Both paths run the output through the identical
`validateIntent()`, so the fallback is exactly as safe as the primary one.

### 5.3 Keys

AI keys are read from environment variables on the server and never sent to any client. The APK contains
only `BuildConfig.API_BASE_URL` — a public HTTPS URL. The release build **fails fast** if that URL is
missing or is not `https://`, so a broken or LAN-targeted release cannot ship.

---

## 6. Android permissions, and why each one exists

MAX requests a permission **at the moment of use**, never at startup, and always shows a plain-language
reason before the system dialog.

| Permission | Requested when | Why MAX needs it | What happens without it |
| --- | --- | --- | --- |
| `RECORD_AUDIO` | The user taps the mic, or turns on "Hey MAX" | Speech-to-text | Voice input is unavailable |
| `READ_CONTACTS` | The user asks to call/message someone by name | Look up a number | MAX says it needs Contacts |
| `CALL_PHONE` | Only to place a *confirmed* call | Place a real call | The dialer opens instead; MAX says so |
| `POST_NOTIFICATIONS` | First reminder, or "Hey MAX" | Android 13+ runtime permission | MAX works; reminders are not drawn |
| `FOREGROUND_SERVICE` | Turning on "Hey MAX" | Base permission for any foreground service | "Hey MAX" cannot run |
| `FOREGROUND_SERVICE_MICROPHONE` | Turning on "Hey MAX" | Android 14+ per-type permission for a microphone service | `startForeground()` throws |
| `SET_ALARM` (`com.android.alarm`) | Setting an alarm or timer | Hand the alarm to the system Clock app | No system alarm/timer |

### Deliberately **not** requested

| Not requested | Why |
| --- | --- |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Special access the user must grant in Settings, and Google Play restricts it to alarm/calendar apps. MAX uses `setAndAllowWhileIdle`, which needs **no** permission and still fires in Doze. Cost: a reminder can be a few minutes late. |
| `READ_CALENDAR` | Overly broad for "what's on my calendar". MAX opens the calendar app instead. |
| `READ_SMS` / `READ_CALL_LOG` | MAX never reads existing messages or call history. |
| `SYSTEM_ALERT_WINDOW` | Not needed, and overlay access is a common abuse vector. |
| `BIND_ACCESSIBILITY_SERVICE` | Would let MAX observe and act inside every app. Not needed for anything MAX does. |
| `RECEIVE_BOOT_COMPLETED` | A reboot clears pending alarms. Rather than take another permission, MAX documents the limitation (§8). |

### Notification access (optional)

`MaxNotificationListenerService` lets MAX read notification *titles*. It is never requested as a
permission — the user must enable it manually in Android Settings — and MAX is explicit in the UI that the
titles stay on the phone.

---

## 7. Voice

### 7.1 Speech in (`speech/SpeechInput.kt`)

* Built on Android's `SpeechRecognizer`, with `EXTRA_PARTIAL_RESULTS` on, so the transcript appears
  **while the user is still speaking**.
* `EXTRA_PREFER_OFFLINE` prefers the on-device model. It is a *preference*, not a guarantee, so the
  network failure paths are still handled and explained.
* `onRmsChanged` is mapped onto a 0..1 amplitude and drives the orb, so the orb visibly reacts to the
  voice instead of merely spinning.
* Silence: `EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS = 1500` gives the user time to finish a sentence;
  `ERROR_NO_MATCH` with a non-empty partial transcript reuses what was heard rather than making the user
  repeat themselves.
* Every platform error code is mapped to a sentence a person can act on.
* The microphone closes the instant a session ends, the user taps again, or a new turn starts. It is
  never held open unnecessarily.

### 7.2 Speech out (`speech/SpeechOutput.kt`)

* The engine is created in `Application.onCreate`, **not** lazily. The old lazy version silently dropped
  the first reply while TextToSpeech was still booting — this is the fix.
* Settings persisted in `VoiceSettings`: enabled, speed (0.5×–2.0×), pitch, and a preferred voice. If the
  stored voice has disappeared (a language pack was removed), MAX falls back to an on-device default
  instead of failing.
* Long replies are truncated to a **complete sentence** at ~600 characters. Reading a 2,000-character
  article aloud is never what a voice assistant should do, and it would block the next turn.
* A device with no TTS engine is detected and reported honestly rather than pretending to speak.
* By default MAX speaks only after a **voice** turn; typed conversations stay quiet. This is a setting.

### 7.3 "Hey MAX" — what Android actually allows

This is the part most "assistant" apps overstate, so here is the exact position:

1. **Android has no public always-on hotword API for third-party apps.** `SpeechRecognizer` is the only
   speech engine a normal app can reach, and it is built for short, push-to-talk sessions.
2. **Background microphone access requires a foreground service** declared with
   `android:foregroundServiceType="microphone"`, which on Android 14+ also requires
   `FOREGROUND_SERVICE_MICROPHONE`. That permission is *while-in-use*: the service can only be **started**
   while the app is in the foreground. MAX's Settings toggle is exactly that.
3. **A permanently running recogniser would be accurate but would drain the battery**, and would very
   likely be rejected under Google Play's microphone foreground-service policy.

What MAX actually ships (`services/WakeWordService.kt`):

* A user-enabled microphone foreground service with a **permanent, silent** notification whose "Stop"
  action genuinely shuts it down — that is the user's guarantee the microphone has stopped.
* A **duty-cycled** detection loop: a ~6-second listening window, then a ~2.5-second idle gap, then
  repeat. The wake word is checked against partial results, so "hey ma…" can trigger before the
  recogniser finishes.
* When the wake word is heard, the microphone closes **immediately** and the app opens with the
  remainder of the sentence as the command.
* Battery: the idle gap is the dial. The handler sleeps between windows, so an idle cycle is close to
  free, but the service does use power while it is on. The setting is **off by default**.

**MAX does not claim to work while the device is off, or in states where Android forbids background
microphone access.** There is no always-on mode, and no part of the UI implies one.

---

## 8. Android limitations, and what MAX does instead

| Request | The Android rule | What MAX does |
| --- | --- | --- |
| "Send Rahul a message" | No public API to send SMS silently; Google Play restricts SMS permissions | Opens the composer with the text and reports **"prepared — not sent"** |
| "Send on WhatsApp" | No public WhatsApp send API | Opens a `wa.me` chat pre-filled; reports **"prepared — not sent"** |
| "Turn on Bluetooth" | `BluetoothAdapter.enable()` is deprecated and throws for non-privileged apps | Opens Bluetooth settings and says exactly that |
| "What's on my calendar?" | Reading events needs the sensitive `READ_CALENDAR` permission | Opens the calendar app |
| "Remind me tomorrow at 9" | No public reminders API | Schedules its **own** alarm via `AlarmManager.setAndAllowWhileIdle`, so it fires with the screen off. If the phrase is too vague to resolve, it opens the calendar rather than guessing a date |
| Reminder exactness | `SCHEDULE_EXACT_ALARM` needs special access and is Play-restricted | Accepted delay of a few minutes, in exchange for no special permission |
| Reminders after a reboot | Android drops all pending alarms on reboot, and `RECEIVE_BOOT_COMPLETED` is not requested | Documented limitation; MAX re-schedules saved reminders the next time the app starts |
| "Remember that…" | — | Stored on the MAX server (cross-device), scoped to the user |
| "Create a note" / tasks | — | Stored **on the phone** in a JSON file, so they work offline and never leave the device |
| Hotword while locked | No public always-on API (§7.3) | Duty-cycled foreground service; opens the app with the command |
| Running an action from the service | No screen available to confirm on | The wake word opens the app; the confirmation card is shown there |
| "Turn flashlight on" | Not every device has a torch | Reports "This phone doesn't have a flashlight" when there is none |

---

## 9. Security model

### What the client holds

* **The session token only**, in `EncryptedSharedPreferences` with a key in the Android Keystore.
* The backend URL, which is public.
* **No AI key, no service-role key, no SMTP password, no database URL** — none of these exist in the APK.

### The chain of trust

1. Passwords are hashed with **salted scrypt** (Node built-in, no extra dependency); the hash is never
   logged. Login returns the same message for "no such user" and "wrong password", so emails cannot be
   probed.
2. A session stores only the **SHA-256 hash** of its token, never the token itself.
3. The user id for every request comes from the **verified session**, never from the body or a query
   parameter.
4. Model output is untrusted at every step: `parseToolCalls()` tolerates malformed JSON, then
   `validateIntent()` rejects any name that is not on the whitelist, any parameter key we did not declare,
   any out-of-range value, and any non-`http(s)` URL.
5. The confirmation policy lives in the **registry**, not in the prompt. The model can only *add* a
   confirmation; it can never remove one.
6. `LIKE` patterns built from user text are escaped (`escapeLike`) before being passed to a parameterised
   query, so "forget that 100% of…" cannot become a wildcard that deletes unrelated rows.
7. Error bodies carry friendly messages and a `requestId`; the server logs the real cause with
   credentials, connection strings and tokens scrubbed.

### What MAX deliberately cannot do

Run a shell command. Execute model-provided code. Construct an Intent from a model-supplied URI. Read the
user's SMS, call log or calendar without an explicit grant. Send a message or place a call without a
confirmation tap. Access another user's data by any path.

---

## 10. Offline and failure behaviour

| Situation | What the user sees |
| --- | --- |
| No connection | A banner that says offline **and** lists what still works. Local commands, notes, tasks, timers and reminders are all local. |
| Backend unreachable | "Can't reach the MAX server. Check the backend URL and try again." — distinguished from being offline by a real connectivity check, so a wrong URL never masquerades as no signal. |
| AI provider down / not configured | `"MAX's AI service is temporarily unavailable."` plus `aiUnavailable: true`. Local commands still work. |
| Microphone permission denied | A plain sentence explaining why voice needs it, and no crash. |
| Speech recognition error | The specific, actionable message for that error code. |
| Corrupt local notes file | Moved aside; the app continues with an empty store instead of crashing. |
| No TTS engine | The toggle disables itself and says why. |
| Request cancelled | The thinking indicator clears; cancellation is not reported as an error. |

---

## 11. How to build a signed APK

```bash
# 1. The public HTTPS backend URL. The release build FAILS without it, and fails
#    if it is not https:// - deliberate, so a LAN URL cannot ship.
export MAX_API_BASE_URL="https://<your-app>.onrender.com/"

# 2. The release keystore. Create it ONCE and back it up; losing it means you can
#    never update the app on Play.
keytool -genkey -v -keystore max-release-key.jks -alias max-key \
        -keyalg RSA -keysize 2048 -validity 10000

# 3. android/keystore.properties (git-ignored, never commit it):
#    storeFile=max-release-key.jks
#    storePassword=...
#    keyAlias=max-key
#    keyPassword=...

# 4. Build. Both of these should pass first:
cd android
./gradlew testDebugUnitTest     # 28 unit tests
./gradlew lintDebug             # Android lint
./gradlew assembleRelease       # signed APK -> app/build/outputs/apk/release/
```

For Play Store distribution, build an App Bundle instead:

```bash
cd android && ./gradlew bundleRelease
# -> android/app/build/outputs/bundle/release/app-release.aab
```

Verify the artefact before shipping:

```bash
# The baked-in URL must be your public HTTPS one:
grep API_BASE_URL android/app/build/generated/source/buildConfig/release/com/max/assistant/BuildConfig.java
# No secrets inside the APK:
unzip -p app/build/outputs/apk/release/app-release.apk classes.dex | strings | grep -i "sk-\|SERVICE_ROLE"
```

---

## 12. What has been tested, and what has not

**Verified in this repository**

* Backend — `npx tsc --noEmit` clean; **62 vitest tests passing**: tool-registry completeness, closed-whitelist
  rejection, confirmation policy, registry → JSON-Schema derivation, untrusted `tool_calls` parsing, command
  parsing (including spoken phrasing and destructive commands), AI provider error handling.
* Android — `compileDebugKotlin` clean; **28 JUnit tests passing**: parser (incl. spoken "Hey MAX, remind me
  tomorrow at 9 AM to call dad"), reminder time resolution, TTS truncation, registry completeness;
  `assembleDebug` succeeds.

**Not verified here** — anything needing hardware or a live service

* The end-to-end API suite (`tests/e2e.test.ts`) needs a running server and a database.
* Speech recognition and text-to-speech on a real device; the "Hey MAX" foreground service; notification
  delivery; locked-screen behaviour; a physical-device permission-grant flow.
* The signed release build needs the production URL and keystore.

