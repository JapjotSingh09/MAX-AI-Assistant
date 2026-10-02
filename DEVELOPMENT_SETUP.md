# MAX — Development Setup

Follow the steps in order. Commands are given for **Windows PowerShell** (with CMD alternatives) and **macOS/Linux**.

> The Android app in `android/` has **not been compiled yet** (the authoring environment had no JDK or Android SDK).
> The first Gradle sync is the first real compile; small fixes may be needed.

## 1. Install Android Studio
Download from <https://developer.android.com/studio> (Koala 2024.1 or newer) and install with defaults.

## 2. Install JDK 17
Android Studio bundles JDK 17 (**Settings → Build → Gradle → Gradle JDK = jbr-17**). For command-line builds install it separately:

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
```
```bash
# macOS: brew install --cask temurin@17      Ubuntu: sudo apt install openjdk-17-jdk
```
Verify: `java -version` must say **17**. If not, set `JAVA_HOME` (PowerShell: `setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17..."`, then open a new terminal).

## 3–5. Android SDK, platform and build tools
Android Studio → **Settings → Languages & Frameworks → Android SDK**. Install:
* **SDK Platform: Android 14 (API 34)**
* **SDK Tools: Android SDK Build-Tools 34.0.0, Platform-Tools, Command-line Tools**

Check what is installed:
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --list_installed
```
```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --list_installed
```
Install missing pieces (accept licenses first):
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

## 6–8. Phone, USB debugging, adb
1. Phone → Settings → About phone → tap **Build number** 7 times.
2. Settings → System → Developer options → enable **USB debugging**.
3. Connect via USB and accept the "Allow USB debugging" prompt.
4. Verify: `adb devices` must list your device as `device` (add `...\Android\Sdk\platform-tools` to PATH if `adb` isn't found).

## 9. Get the project
```bash
git clone <your-repo-url> max && cd max
```

## 10. Configure `android/local.properties`
Create `android/local.properties` (git-ignored; Android Studio creates `sdk.dir` for you).
See `android/local.properties.example` for a copy-paste template.

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
# Backend URL. Emulator -> your PC is 10.0.2.2. A real phone needs your PC's LAN IP or a public HTTPS URL.
MAX_API_BASE_URL=http://10.0.2.2:3000/
```
For a **real phone on the same Wi-Fi** use e.g. `http://192.168.1.20:3000/` **and** add that IP to
`android/app/src/main/res/xml/network_security_config.xml` (cleartext is allowed only for listed hosts), or use an HTTPS tunnel.
Production must use HTTPS.

> The URL can also come from the environment or a Gradle property instead of
> `local.properties` (useful for CI): `MAX_API_BASE_URL=...` as an env var or
> `-PMAX_API_BASE_URL=...` on the Gradle command line. Priority is
> env var > `-P` property > `local.properties` > emulator default (debug only).
>
> **Final APK:** `assembleRelease` *requires* an explicit `https://` URL and
> fails with instructions if it is missing, so a dev URL can never leak into a
> release. After deploying the backend (see "Deploying the backend" below), build with e.g.
> `MAX_API_BASE_URL=https://<your-app>.onrender.com/ ./gradlew assembleRelease`.

## 11. Configure the backend database
Two supported options:

**A) Reference backend (PostgreSQL + Drizzle)** — works out of the box. Install PostgreSQL 15+, create a database, set `DATABASE_URL`.

**B) Supabase** — create a project at <https://supabase.com>, then in **Authentication → SMTP Settings** configure a transactional SMTP
provider (do **not** use the built-in sender in production). Apply `supabase/migrations/20260101000000_max_schema.sql`
(`supabase db push` or the SQL editor). Keep `SUPABASE_SERVICE_ROLE_KEY` on the server only. See `supabase/functions/README.md`.

## 12. Configure the backend `.env`
```powershell
Copy-Item .env.example .env
```
```bash
cp .env.example .env
```
Edit `.env`:
* `DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:5432/app_db`
* AI (optional for local commands, required for conversation): `AI_PROVIDER=gemini` (or `openai`/`openrouter`/`custom`), `AI_MODEL=...`, `AI_API_KEY=...`
* Email: leave `EMAIL_PROVIDER=console` for development. For production set `EMAIL_PROVIDER=smtp` and the `SMTP_*` values.

**Never commit `.env`** (it is in `.gitignore`).

## 13. Database schema
The server applies its own migrations at start-up, so there is no mandatory manual step:
just point `DATABASE_URL` at any empty PostgreSQL database and run the backend — MAX's
tables (`users`, `sessions`, `auth_tokens`, `rate_limits`, ...) are created on the first
request. Applied migrations are tracked in `drizzle.__drizzle_migrations`, so it is a no-op
afterwards and it never touches existing rows.
```bash
npm install
npm run build && npm start   # tables are created automatically on first use
```
Do it by hand if you prefer (same `DATABASE_URL`, same result):
```bash
npm run db:migrate     # applies drizzle/*.sql
# or, to sync directly from src/db/schema.ts:
npx drizzle-kit push
```
`GET /api/health` answers `schema: "ready"` once the tables exist (and `schema: "failed"`
if they could not be created), so a misconfigured deployment is obvious immediately.

## 14. Start the backend
```bash
npm run dev          # http://localhost:3000   (web console + API)
# production style:  npm run build && npm start
```
Check `http://localhost:3000/api/health` → `{"ok":true,...}`.

PowerShell blocked scripts (`npm.ps1 cannot be loaded`)? Run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`, or just use **CMD**:
```bat
cd max && npm install && npm run dev
```

## 15–16. Open the Android project and sync
Android Studio → **Open** → select the `android/` folder → wait for **Gradle Sync**.

The Gradle wrapper script/jar are not committed (they could not be generated here). Generate them once, either automatically
(Android Studio does it on sync) or manually with an installed Gradle ≥ 8.7:
```bash
cd android && gradle wrapper --gradle-version 8.7
```

## 17. Run the debug build
```powershell
cd android
.\gradlew.bat assembleDebug
```
```cmd
cd android
gradlew.bat assembleDebug
```
```bash
cd android && ./gradlew assembleDebug
```
APK output: `android/app/build/outputs/apk/debug/app-debug.apk`.

Unit tests: `gradlew.bat testDebugUnitTest` (Windows) / `./gradlew testDebugUnitTest`.

## 18. Install the APK
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```
(or press ▶ Run in Android Studio).

## 19. Test login
Open MAX → **Create an account** → you land on Home. Kill and reopen the app: you stay signed in (session restoration).
In `console` email mode, the verification link is printed in the backend log.

## 20. Test AI
Assistant tab → ask *"Explain gravity simply"*. With no `AI_API_KEY` you will see *"MAX's AI service is temporarily unavailable."* — that's the graceful fallback.
Add a key to `.env`, restart the backend, try again.

## 21. Test Android actions
Try (confirmation cards appear for calls/messages):
`Open YouTube` · `Turn flashlight on` · `Set timer for 10 minutes` · `Set an alarm for 7 AM` · `Call Dad` (asks for Contacts permission first) ·
`Send Rahul a message saying I'll be late` (opens the composer — **not sent**) · `Open Google Maps` · tap 🎙 to use voice.
Turn on airplane mode: local commands keep working; AI replies show "You're offline."

---

# Release builds

```powershell
cd android
.\gradlew.bat assembleRelease
```
```bash
cd android && ./gradlew assembleRelease
```

**Signing.** Create a keystore once (keep it and its passwords safe — losing it means you can't update your app):
```bash
keytool -genkeypair -v -keystore max-release.jks -alias max -keyalg RSA -keysize 2048 -validity 10000
```
Create `android/keystore.properties` (git-ignored):
```properties
storeFile=../max-release.jks
storePassword=...
keyAlias=max
keyPassword=...
```
`app/build.gradle.kts` reads that file automatically. **Never commit `.jks`/`.keystore` or `keystore.properties`** (all in `.gitignore`).
In CI, store them as GitHub repository secrets and write the files at build time.
For Google Play use an Android App Bundle: `gradlew bundleRelease`.

# Tests (backend)

```bash
npx vitest run tests/commands.test.ts tests/ai.test.ts
npx next build && npx next start -p 3100 &      # then:
E2E_BASE_URL=http://localhost:3100 RATE_LIMIT_AUTH_PER_MINUTE=30 npx vitest run tests/e2e.test.ts
```
(The server should be started with `RATE_LIMIT_AUTH_PER_MINUTE=30` so the suite's many sign-ups don't hit the limiter before the 429 test.)
