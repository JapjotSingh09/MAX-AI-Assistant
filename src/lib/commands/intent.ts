import {
  TOOL_LABELS,
  TOOL_NAMES,
  TOOL_PARAMETERS,
  formatSeconds,
  getTool,
  requiresConfirmation,
  type ToolName,
} from "@/lib/tools/registry";

/**
 * COMPATIBILITY LAYER.
 *
 * The real definitions now live in `src/lib/tools/registry.ts` (one entry per
 * capability). This file keeps the old export names so every existing caller -
 * routes, the parser, automations, the web console and the existing tests -
 * keeps working unchanged.
 *
 * Nothing here re-declares a schema. If the two ever disagree, the registry wins.
 */

// CLOSED WHITELIST. The AI can only *suggest* one of these actions; anything
// else is rejected. No shell commands or arbitrary code are ever executed.
export const ACTIONS = TOOL_NAMES;
export type ActionType = ToolName;

export const PARAM_SCHEMAS = TOOL_PARAMETERS;
export const ACTION_LABELS = TOOL_LABELS;

export { formatSeconds };

export type CommandIntent = {
  action: ActionType;
  parameters: Record<string, unknown>;
  requiresConfirmation: boolean;
};

export type IntentValidation = { ok: true; intent: CommandIntent } | { ok: false; reason: string };

// Validates ANY raw intent, whether it came from the local parser or from AI
// output. Unknown action names, unknown parameter keys and out-of-range values
// are all rejected here, BEFORE anything is ever executed.
export function validateIntent(raw: unknown): IntentValidation {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return { ok: false, reason: "Not an object" };
  const r = raw as { action?: unknown; parameters?: unknown; requiresConfirmation?: unknown };
  if (typeof r.action !== "string") return { ok: false, reason: "Unknown action" };
  const tool = getTool(r.action);
  if (!tool) return { ok: false, reason: "Unknown action" };
  if (r.parameters !== undefined && (typeof r.parameters !== "object" || r.parameters === null || Array.isArray(r.parameters))) {
    return { ok: false, reason: "Invalid parameters" };
  }
  const parsed = tool.parameters.safeParse(r.parameters ?? {});
  if (!parsed.success) return { ok: false, reason: "Invalid parameters" };
  return {
    ok: true,
    intent: {
      // `tool.name` is the literal union member, so this stays type-safe.
      action: tool.name,
      parameters: parsed.data,
      // The registry decides what always needs a tap. The model's opinion
      // (`requiresConfirmation`) can only ever ADD a confirmation, never remove one.
      requiresConfirmation: requiresConfirmation(tool.name) || r.requiresConfirmation === true,
    },
  };
}
