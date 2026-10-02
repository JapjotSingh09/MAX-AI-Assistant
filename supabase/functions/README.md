# Supabase Edge Functions

The MAX API logic (JWT validation, rate limiting, AI gateway, command validation, usage tracking)
is implemented once in the TypeScript backend under `src/lib` and `src/app/api` of this repository.

To run MAX on Supabase Edge Functions instead of the Next.js host, port the route handlers:

| Route (src/app/api/...) | Edge Function name |
| --- | --- |
| `chat` | `chat` |
| `assistant/command` | `assistant-command` |
| `activity` | `activity` |
| `automations` | `automations` |

Rules when porting:

1. Verify the caller with `supabase.auth.getUser(jwt)`. Derive `user_id` from that, never from the body.
2. Use the **service-role key only inside the function** (`Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")`).
   Never ship it to Android.
3. Keep `AI_API_KEY`, `SMTP_*` as function secrets: `supabase secrets set AI_API_KEY=...`.
4. Reuse `src/lib/commands/*` and `src/lib/ai/*` unchanged (they have no framework dependencies).

SMTP for Auth emails is configured in the Supabase dashboard (Authentication -> SMTP Settings) with a
transactional provider such as Brevo, Amazon SES, Postmark or Mailgun. Do **not** rely on the built-in
Supabase email sender in production: it is heavily rate limited.
