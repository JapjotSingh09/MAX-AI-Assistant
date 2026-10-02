import { AIProviderError, type AIProvider, type AIRequest, type AIResponse } from "@/lib/ai/types";

export type OpenAICompatibleOptions = {
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  timeoutMs: number;
  maxRetries: number;
  // Injectable for tests.
  fetchImpl?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
};

// OpenAI, OpenRouter and Gemini (OpenAI-compatible endpoint) all speak this same
// "chat/completions" format, so a single class covers all of them.
export class OpenAICompatibleProvider implements AIProvider {
  readonly name: string;
  readonly model: string;
  constructor(private o: OpenAICompatibleOptions) {
    this.name = o.name;
    this.model = o.model;
  }

  async chat(request: AIRequest): Promise<AIResponse> {
    const sleep = this.o.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
    let lastError: AIProviderError | undefined;
    // Retry with exponential backoff, but ONLY for transient errors and a small
    // number of times: expensive requests are never retried forever.
    for (let attempt = 0; attempt <= this.o.maxRetries; attempt++) {
      try {
        return await this.once(request);
      } catch (err) {
        lastError = err instanceof AIProviderError ? err : new AIProviderError("network", true, "network");
        if (!lastError.retryable || attempt === this.o.maxRetries) break;
        await sleep(400 * 2 ** attempt + Math.floor(Math.random() * 150));
      }
    }
    throw lastError!;
  }

  private async once(request: AIRequest): Promise<AIResponse> {
    const doFetch = this.o.fetchImpl ?? fetch;
    const started = Date.now();
    let res: Response;
    try {
      res = await doFetch(`${this.o.baseUrl.replace(/\/$/, "")}/chat/completions`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${this.o.apiKey}` },
        body: JSON.stringify({
          model: this.o.model,
          messages: request.messages,
          max_tokens: request.maxTokens ?? 600,
          temperature: request.temperature ?? 0.4,
        }),
        signal: AbortSignal.timeout(this.o.timeoutMs),
      });
    } catch (err) {
      const name = (err as Error)?.name;
      if (name === "TimeoutError" || name === "AbortError") throw new AIProviderError("timeout", false, "timeout");
      throw new AIProviderError("network", true, "network");
    }
    if (res.status === 429) throw new AIProviderError("rate limited", true, "rate_limited");
    if (res.status === 401 || res.status === 403) throw new AIProviderError("auth", false, "auth");
    if (res.status >= 500) throw new AIProviderError("server error", true, "server");
    if (!res.ok) throw new AIProviderError("bad request", false, "bad_response");

    const data = (await res.json().catch(() => null)) as {
      choices?: { message?: { content?: string } }[];
      usage?: { total_tokens?: number };
    } | null;
    const content = data?.choices?.[0]?.message?.content;
    if (typeof content !== "string" || !content.trim()) throw new AIProviderError("empty", false, "bad_response");
    return {
      content,
      provider: this.name,
      model: this.model,
      tokensUsed: data?.usage?.total_tokens ?? 0,
      latencyMs: Date.now() - started,
    };
  }
}
