import { AIProviderError, type AIToolCall, type AIProvider, type AIRequest, type AIResponse } from "@/lib/ai/types";

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
      // Only send `tools` when the caller asked for them. Some providers reject
      // an empty or unknown tool set, so we never send one we did not build.
      const body: Record<string, unknown> = {
        model: this.o.model,
        messages: request.messages,
        max_tokens: request.maxTokens ?? 600,
        temperature: request.temperature ?? 0.4,
      };
      if (request.tools?.length) {
        body.tools = request.tools;
        body.tool_choice = "auto";
      }
      res = await doFetch(`${this.o.baseUrl.replace(/\/$/, "")}/chat/completions`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${this.o.apiKey}` },
        body: JSON.stringify(body),
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
      choices?: {
        message?: { content?: string | null; tool_calls?: RawToolCall[] };
        finish_reason?: string | null;
      }[];
      usage?: { total_tokens?: number };
    } | null;

    const message = data?.choices?.[0]?.message;
    const toolCalls = parseToolCalls(message?.tool_calls);
    // A tool call legitimately has no text content, so "empty" is only an error
    // when the model returned neither words nor a tool.
    const content = typeof message?.content === "string" ? message.content : "";
    if (!content.trim() && toolCalls.length === 0) throw new AIProviderError("empty", false, "bad_response");

    return {
      content,
      provider: this.name,
      model: this.model,
      tokensUsed: data?.usage?.total_tokens ?? 0,
      latencyMs: Date.now() - started,
      ...(toolCalls.length ? { toolCalls } : {}),
    };
  }
}

// Wire shape of one `tool_calls[]` entry. Kept private to this file.
type RawToolCall = {
  id?: unknown;
  type?: unknown;
  function?: { name?: unknown; arguments?: unknown };
};

/**
 * Model output is UNTRUSTED. `function.arguments` is a JSON *string* that the
 * model generated, so it is parsed defensively: a malformed or non-object
 * payload becomes an empty object, and only real strings become tool names.
 * Nothing here executes anything - the name still has to survive
 * `validateIntent()` against the closed whitelist before it can run.
 */
export function parseToolCalls(raw: unknown): AIToolCall[] {
  if (!Array.isArray(raw)) return [];
  const out: AIToolCall[] = [];
  for (const item of raw) {
    const name = (item as RawToolCall)?.function?.name;
    if (typeof name !== "string" || !name) continue;
    let args: Record<string, unknown> = {};
    const argText = (item as RawToolCall)?.function?.arguments;
    if (typeof argText === "string" && argText.trim()) {
      try {
        const parsed = JSON.parse(argText);
        // Arrays are objects in JS but are never a valid parameter bag.
        if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) args = parsed as Record<string, unknown>;
      } catch {
        /* malformed JSON from the model: fall through with no arguments */
      }
    }
    const id = (item as RawToolCall)?.id;
    out.push({ name, arguments: args, id: typeof id === "string" && id ? id : `call_${out.length}` });
  }
  return out;
}
