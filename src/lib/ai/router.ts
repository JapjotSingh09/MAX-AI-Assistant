import { config } from "@/lib/config";
import { OpenAICompatibleProvider } from "@/lib/ai/openaiCompatible";
import {
  AIUnavailableError,
  type AIProvider,
  type AIRequest,
  type AIResponse,
} from "@/lib/ai/types";

const BASE_URLS: Record<string, string> = {
  openai: "https://api.openai.com/v1",
  openrouter: "https://openrouter.ai/api/v1",
  gemini: "https://generativelanguage.googleapis.com/v1beta/openai",
};

// AIProviderFactory: turns a provider name from the environment into a provider object.
export function createProvider(name: string, model: string, apiKey: string): AIProvider | null {
  const baseUrl = name === "custom" ? config.ai.baseUrl : BASE_URLS[name];
  if (!baseUrl || !apiKey || !model) return null;
  return new OpenAICompatibleProvider({
    name,
    baseUrl,
    apiKey,
    model,
    timeoutMs: config.ai.timeoutMs,
    maxRetries: config.ai.maxRetries,
  });
}

// AIProviderRouter: primary provider first, then the fallback, then a graceful error.
export class AIProviderRouter {
  constructor(private providers: AIProvider[]) {}

  get configured() {
    return this.providers.length > 0;
  }

  async chat(request: AIRequest): Promise<AIResponse> {
    if (this.providers.length === 0) throw new AIUnavailableError("not_configured");
    for (const provider of this.providers) {
      try {
        return await provider.chat(request);
      } catch {
        // Try the next provider. Each provider already limits its own retries.
      }
    }
    throw new AIUnavailableError("all_failed");
  }
}

export function getRouter() {
  const { provider, model, apiKey, fallbackProvider, fallbackModel, fallbackApiKey } = config.ai;
  const list = [
    createProvider(provider, model, apiKey),
    createProvider(fallbackProvider, fallbackModel, fallbackApiKey),
  ].filter((p): p is AIProvider => p !== null);
  return new AIProviderRouter(list);
}
