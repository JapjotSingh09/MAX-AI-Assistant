// All tunable limits and provider settings live here and come from environment
// variables, so production limits are never hard-coded across the app.
const int = (key: string, fallback: number) => {
  const v = Number(process.env[key]);
  return Number.isFinite(v) && v > 0 ? Math.floor(v) : fallback;
};

export const config = {
  appUrl: process.env.APP_URL || "",
  isProd: process.env.NODE_ENV === "production",

  limits: {
    authPerMinutePerIp: int("RATE_LIMIT_AUTH_PER_MINUTE", 10),
    apiPerMinutePerUser: int("RATE_LIMIT_API_PER_MINUTE", 120),
    aiPerMinutePerUser: int("RATE_LIMIT_AI_PER_MINUTE", 10),
    aiPerDayPerUser: int("RATE_LIMIT_AI_PER_DAY", 200),
    voicePerMinutePerUser: int("RATE_LIMIT_VOICE_PER_MINUTE", 20),
    automationCreatesPerDay: int("RATE_LIMIT_AUTOMATION_CREATES_PER_DAY", 50),
    expensivePerMinutePerUser: int("RATE_LIMIT_EXPENSIVE_PER_MINUTE", 5),
    maxMessageLength: int("MAX_MESSAGE_LENGTH", 2000),
    maxConversationLength: int("MAX_CONVERSATION_LENGTH", 200),
  },

  ai: {
    provider: (process.env.AI_PROVIDER || "").toLowerCase(), // openai | openrouter | gemini | custom
    model: process.env.AI_MODEL || "",
    apiKey: process.env.AI_API_KEY || "",
    baseUrl: process.env.AI_BASE_URL || "",
    fallbackProvider: (process.env.AI_FALLBACK_PROVIDER || "").toLowerCase(),
    fallbackModel: process.env.AI_FALLBACK_MODEL || "",
    fallbackApiKey: process.env.AI_FALLBACK_API_KEY || "",
    timeoutMs: int("AI_TIMEOUT_MS", 20000),
    maxRetries: int("AI_MAX_RETRIES", 2),
    // How many extra model round-trips a single request may spend on tool
    // calling. Each round costs money, so this is a hard ceiling rather than
    // "keep going until the model stops asking".
    maxToolRounds: int("AI_MAX_TOOL_ROUNDS", 2),
  },

  email: {
    // console = prints links to server logs (development only).
    // smtp    = any transactional SMTP provider (Brevo, SES, Postmark, Mailgun...).
    provider: (process.env.EMAIL_PROVIDER || "console").toLowerCase(),
    smtpHost: process.env.SMTP_HOST || "",
    smtpPort: int("SMTP_PORT", 587),
    smtpUser: process.env.SMTP_USER || "",
    smtpPassword: process.env.SMTP_PASSWORD || "",
    from: process.env.SMTP_FROM || "MAX <no-reply@example.com>",
    requireVerification: process.env.REQUIRE_EMAIL_VERIFICATION === "true",
  },
};

export function aiConfigured() {
  return Boolean(config.ai.provider && config.ai.apiKey && config.ai.model);
}
