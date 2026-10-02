import { z } from "zod";
import type { AITool } from "@/lib/ai/types";
import { TOOL_REGISTRY, type ToolName } from "@/lib/tools/registry";

/**
 * Bridges MAX's tool registry to whatever the AI provider speaks.
 *
 * WHY this file exists: the registry is written once, in our own vocabulary
 * (name + description + zod schema). The OpenAI-compatible wire format wants a
 * JSON Schema per tool. Rather than hand-writing JSON twice (which silently
 * drifts), we DERIVE the JSON Schema from the same zod schema we validate with.
 * If a parameter changes in the registry, the AI's view changes in the same
 * commit - and `tests/tools.test.ts` fails loudly if that derivation breaks.
 */

/**
 * zod -> JSON Schema, then strip the bits an API provider rejects.
 *
 * `$schema` is a document identifier that providers do not want in a function
 * definition, and JSON-Schema `minLength`/`maxLength` keywords are not always
 * implemented, so they are folded into the description text instead. We also
 * force `additionalProperties: false`, which is what stops the model from
 * inventing parameter names our validation would then reject.
 */
function toFunctionParameters(schema: z.ZodType<Record<string, unknown>>): Record<string, unknown> {
  let json: Record<string, unknown>;
  try {
    json = z.toJSONSchema(schema, { io: "input" }) as Record<string, unknown>;
  } catch {
    // A schema we cannot describe to the model is a bug, not a runtime
    // condition. Surfacing it loudly beats silently offering a broken tool.
    throw new Error(`Tool schema could not be converted to JSON Schema: ${schema.description ?? "(unnamed)"}`);
  }
  delete json.$schema;

  const properties = (json.properties ?? {}) as Record<string, Record<string, unknown>>;
  for (const prop of Object.values(properties)) {
    const min = typeof prop.minLength === "number" ? prop.minLength : undefined;
    const max = typeof prop.maxLength === "number" ? prop.maxLength : undefined;
    // The keywords stay (they help strict providers) but are also spelled out,
    // because several providers silently ignore unknown JSON-Schema keywords.
    if (min !== undefined || max !== undefined) {
      const range = [min !== undefined ? `${min}-${max ?? "many"}` : `up to ${max} characters`][0];
      prop.description = `${prop.description ? `${prop.description} ` : ""}(Accepts ${range} characters.)`;
    }
  }

  return { type: "object", properties, required: json.required ?? [], additionalProperties: false };
}

/**
 * Memoised JSON Schema per tool name.
 *
 * `z.toJSONSchema()` walks the whole schema tree, so doing it on every request
 * for every tool is pure waste. The registry is a module-level constant, so the
 * conversion is too: compute once, reuse forever.
 */
const PARAM_JSON_CACHE = new Map<ToolName, Record<string, unknown>>();

function functionParametersFor(tool: (typeof TOOL_REGISTRY)[number]): Record<string, unknown> {
  const cached = PARAM_JSON_CACHE.get(tool.name);
  if (cached) return cached;
  let json: Record<string, unknown>;
  try {
    json = z.toJSONSchema(tool.parameters, { io: "input" }) as Record<string, unknown>;
  } catch {
    // A schema we cannot describe to the model is a bug, not a runtime
    // condition. Surfacing it loudly beats silently offering a broken tool.
    throw new Error(`Tool schema for ${tool.name} could not be converted to JSON Schema.`);
  }
  delete json.$schema;

  const properties = (json.properties ?? {}) as Record<string, Record<string, unknown>>;
  for (const prop of Object.values(properties)) {
    const min = typeof prop.minLength === "number" ? prop.minLength : undefined;
    const max = typeof prop.maxLength === "number" ? prop.maxLength : undefined;
    // The keywords stay (they help strict providers) but are also spelled out,
    // because several providers silently ignore unknown JSON-Schema keywords.
    if (min !== undefined || max !== undefined) {
      prop.description = `${prop.description ? `${prop.description} ` : ""}(Accepts ${min}-${max ?? "many"} characters.)`;
    }
  }

  const out = { type: "object", properties, required: json.required ?? [], additionalProperties: false };
  PARAM_JSON_CACHE.set(tool.name, out);
  return out;
}

/**
 * The full tool list the model is offered, in registry order.
 *
 * Built once at module load: it is a pure function of the registry, and
 * rebuilding it per request would be wasted work on the hot path.
 */
export const AI_TOOLS: readonly AITool[] = TOOL_REGISTRY.map((tool) => ({
  type: "function" as const,
  function: {
    name: tool.name,
    description: tool.description,
    parameters: functionParametersFor(tool),
  },
}));

/** Only the tools a given execution site may use. */
export function aiToolsFor(site: "device" | "server"): AITool[] {
  return TOOL_REGISTRY.filter((t) => t.site === site).map((tool) => ({
    type: "function" as const,
    function: {
      name: tool.name,
      description: tool.description,
      parameters: functionParametersFor(tool),
    },
  }));
}

/**
 * Fallback for providers that do NOT support native tool calling: the same
 * registry, flattened into one instruction block in the system prompt.
 * Keeping this derived from the registry means a new tool is described in the
 * fallback too, with no second list to forget to update.
 */
export function toolCatalogForPrompt(names: readonly ToolName[] = TOOL_REGISTRY.map((t) => t.name)): string {
  return names
    .map((name) => {
      const tool = TOOL_REGISTRY.find((t) => t.name === name)!;
      const params = Object.keys((functionParametersFor(tool).properties as Record<string, unknown>) ?? {});
      return `- ${name}(${params.join(", ") || "no parameters"}): ${tool.description}`;
    })
    .join("\n");
}
