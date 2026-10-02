// The AI abstraction. The rest of the app only knows this interface, so a
// provider (Gemini, OpenAI, OpenRouter...) can be swapped without other changes.
export type AIMessage = { role: "system" | "user" | "assistant"; content: string };

/**
 * One tool offered to the model, in the OpenAI `tools` wire format.
 * `parameters` is a JSON Schema object, derived from our own zod schemas in
 * `src/lib/tools/registry.ts`, so the model can only ever pick a name that is
 * on the whitelist and can only ever send parameter names we declared.
 */
export type AITool = {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
};

/** A tool the model decided to run. Still untrusted until `validateIntent`. */
export type AIToolCall = {
  /** Registry name the model asked for. */
  name: string;
  /** Raw JSON arguments as sent by the model. Never trusted directly. */
  arguments: Record<string, unknown>;
  /** OpenAI's call id, echoed back in the follow-up message. */
  id: string;
};

export type AIRequest = {
  messages: AIMessage[];
  maxTokens?: number;
  temperature?: number;
  /**
   * When present, the provider sends native tool/function calling. Providers
   * that do not support it simply ignore this and the caller falls back to the
   * JSON-prompt path - see `src/lib/assistant.ts`.
   */
  tools?: AITool[];
  /** How many model round-trips MAX is allowed for tool calling (bounds cost). */
  maxToolRounds?: number;
};

export type AIResponse = {
  content: string;
  provider: string;
  model: string;
  tokensUsed: number;
  latencyMs: number;
  /** Set when the model asked for a tool instead of answering. */
  toolCalls?: AIToolCall[];
};

export interface AIProvider {
  readonly name: string;
  readonly model: string;
  chat(request: AIRequest): Promise<AIResponse>;
}

export class AIProviderError extends Error {
  constructor(
    message: string,
    public retryable: boolean,
    public category: "timeout" | "rate_limited" | "server" | "auth" | "bad_response" | "network",
  ) {
    super(message);
    this.name = "AIProviderError";
  }
}

// Thrown when no provider could answer. The API turns this into a friendly 503.
export class AIUnavailableError extends Error {
  constructor(public reason: "not_configured" | "all_failed") {
    super("AI unavailable");
    this.name = "AIUnavailableError";
  }
}
