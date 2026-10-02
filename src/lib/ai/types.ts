// The AI abstraction. The rest of the app only knows this interface, so a
// provider (Gemini, OpenAI, OpenRouter...) can be swapped without other changes.
export type AIMessage = { role: "system" | "user" | "assistant"; content: string };

export type AIRequest = { messages: AIMessage[]; maxTokens?: number; temperature?: number };

export type AIResponse = {
  content: string;
  provider: string;
  model: string;
  tokensUsed: number;
  latencyMs: number;
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
