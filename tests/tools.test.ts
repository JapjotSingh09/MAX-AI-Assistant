import { describe, expect, it } from "vitest";
import {
  TOOL_LABELS,
  TOOL_NAMES,
  TOOL_PARAMETERS,
  TOOL_REGISTRY,
  TOOLS_BY_NAME,
  deviceToolNames,
  formatSeconds,
  getTool,
  requiresConfirmation,
} from "@/lib/tools/registry";
import { validateIntent, ACTIONS, ACTION_LABELS, PARAM_SCHEMAS } from "@/lib/commands/intent";
import { AI_TOOLS, aiToolsFor, toolCatalogForPrompt } from "@/lib/ai/toolCatalog";
import { parseToolCalls } from "@/lib/ai/openaiCompatible";

describe("Tool registry is internally consistent", () => {
  it("every name has exactly one definition, schema and label", () => {
    for (const name of TOOL_NAMES) {
      expect(getTool(name), name).toBeDefined();
      expect(TOOL_PARAMETERS[name], name).toBeDefined();
      expect(TOOL_LABELS[name], name).toBeTypeOf("function");
    }
    expect(TOOL_REGISTRY).toHaveLength(TOOL_NAMES.length);
    expect(new Set(TOOL_REGISTRY.map((t) => t.name)).size).toBe(TOOL_NAMES.length);
  });

  it("every tool has a description that tells the AI when to use it", () => {
    for (const t of TOOL_REGISTRY) {
      expect(t.description.length, t.name).toBeGreaterThanOrEqual(20);
      expect(t.site, t.name).toMatch(/^(device|server)$/);
      expect(t.confirm, t.name).toMatch(/^(always|model)$/);
    }
  });

  it("the compatibility layer exposes exactly the registry", () => {
    // These old names are used by routes, automations and the web console.
    expect([...ACTIONS]).toEqual([...TOOL_NAMES]);
    expect(ACTION_LABELS).toBe(TOOL_LABELS);
    expect(PARAM_SCHEMAS).toBe(TOOL_PARAMETERS);
  });
});

describe("Destructive tools can never skip confirmation", () => {
  it("marks calls, messages, note deletion and whole-store clears as always-confirm", () => {
    for (const name of ["CALL_CONTACT", "SEND_SMS", "DELETE_NOTE", "CLEAR_CONVERSATIONS", "CLEAR_MEMORY"] as const) {
      expect(requiresConfirmation(name), name).toBe(true);
    }
  });

  it("the model cannot talk its way out of a confirmation", () => {
    const v = validateIntent({ action: "DELETE_NOTE", parameters: { query: "milk" }, requiresConfirmation: false });
    expect(v.ok && v.intent.requiresConfirmation).toBe(true);
  });

  it("the model can still ADD a confirmation to a safe tool", () => {
    const v = validateIntent({ action: "OPEN_MAPS", parameters: {}, requiresConfirmation: true });
    expect(v.ok && v.intent.requiresConfirmation).toBe(true);
  });
});

describe("Registry is a closed whitelist", () => {
  it("rejects tools that are not in it", () => {
    for (const bad of ["RUN_SHELL", "EXEC", "READ_SMS", "FORMAT_PHONE", "SEND_EMAIL", ""]) {
      expect(validateIntent({ action: bad, parameters: {} }).ok, bad).toBe(false);
    }
  });

  it("rejects unknown parameter keys on every tool", () => {
    // `cmd` would be the smuggling vector if any schema were not `.strict()`.
    expect(validateIntent({ action: "OPEN_APP", parameters: { appName: "X", cmd: "rm -rf /" } }).ok).toBe(false);
    expect(validateIntent({ action: "CREATE_NOTE", parameters: { content: "x", path: "/etc/passwd" } }).ok).toBe(false);
    expect(validateIntent({ action: "SHARE_TEXT", parameters: { text: "x", extras: 1 } }).ok).toBe(false);
  });

  it("rejects malformed parameter bags outright", () => {
    for (const bad of [null, "string", 42, [], { action: "OPEN_APP" }, { action: 5 }]) {
      expect(validateIntent(bad).ok).toBe(false);
    }
  });

  it("keeps only http(s) urls for the browser", () => {
    for (const url of ["javascript:alert(1)", "file:///etc/passwd", "content://x", "intent://x"]) {
      expect(validateIntent({ action: "OPEN_BROWSER", parameters: { url } }).ok, url).toBe(false);
    }
    expect(validateIntent({ action: "OPEN_BROWSER", parameters: { url: "https://example.com" } }).ok).toBe(true);
  });

  it("keeps numeric ranges tight", () => {
    expect(validateIntent({ action: "SET_ALARM", parameters: { hour: 24, minute: 0 } }).ok).toBe(false);
    expect(validateIntent({ action: "SET_ALARM", parameters: { hour: 7, minute: 60 } }).ok).toBe(false);
    expect(validateIntent({ action: "SET_TIMER", parameters: { seconds: 0 } }).ok).toBe(false);
    expect(validateIntent({ action: "SET_TIMER", parameters: { seconds: 90000 } }).ok).toBe(false);
  });

  it("only accepts the documented enum values", () => {
    expect(validateIntent({ action: "ADJUST_VOLUME", parameters: { direction: "sideways" } }).ok).toBe(false);
    expect(validateIntent({ action: "TOGGLE_FLASHLIGHT", parameters: { state: "maybe" } }).ok).toBe(false);
    expect(validateIntent({ action: "ADJUST_VOLUME", parameters: { direction: "up" } }).ok).toBe(true);
  });
});
describe("Execution sites", () => {
  it("routes note/task/reminder work to the device and data reads to the server", () => {
    const device = deviceToolNames();
    for (const n of ["CREATE_NOTE", "CREATE_TASK", "SET_ALARM", "SHARE_TEXT"]) expect(device).toContain(n);
    for (const n of ["SEARCH_CONVERSATIONS", "CLEAR_CONVERSATIONS", "CLEAR_MEMORY"]) expect(device).not.toContain(n);
  });

  it("splits the AI tool list by site without losing any tool", () => {
    expect(aiToolsFor("device").length + aiToolsFor("server").length).toBe(AI_TOOLS.length);
  });
});

describe("Registry -> AI JSON Schema", () => {
  it("produces a usable function definition for every tool", () => {
    expect(AI_TOOLS).toHaveLength(TOOL_NAMES.length);
    for (const tool of AI_TOOLS) {
      expect(tool.type).toBe("function");
      expect(TOOLS_BY_NAME.has(tool.function.name as never)).toBe(true);
      const params = tool.function.parameters as {
        type: string;
        properties: Record<string, unknown>;
        required?: unknown[];
        additionalProperties: boolean;
        $schema?: string;
      };
      expect(params.type, tool.function.name).toBe("object");
      // `additionalProperties: false` is what stops the model inventing keys.
      expect(params.additionalProperties, tool.function.name).toBe(false);
      // Providers reject a `$schema` document id inside a function definition.
      expect(params.$schema, tool.function.name).toBeUndefined();
      expect(Array.isArray(params.required)).toBe(true);
    }
  });

  it("describes required fields and marks optional ones as not required", () => {
    const alarm = AI_TOOLS.find((t) => t.function.name === "SET_ALARM")!.function.parameters as {
      properties: Record<string, { description?: string }>;
      required: string[];
    };
    expect([...alarm.required].sort()).toEqual(["hour", "minute"]);
    expect(alarm.properties.hour.description).toContain("24-hour");
    expect(alarm.properties.label.description).toBeTruthy();
  });

  it("renders a prompt catalog that names every tool and its parameters", () => {
    const catalog = toolCatalogForPrompt();
    for (const name of TOOL_NAMES) expect(catalog, name).toContain(name);
    expect(catalog).toContain("SET_ALARM(hour, minute, label)");
    expect(catalog).toContain("LIST_NOTES(no parameters)");
  });
});

describe("Parsing untrusted model tool_calls", () => {
  it("parses a well-formed call", () => {
    const calls = parseToolCalls([{ id: "c1", function: { name: "SET_ALARM", arguments: '{"hour":7,"minute":0}' } }]);
    expect(calls).toEqual([{ name: "SET_ALARM", arguments: { hour: 7, minute: 0 }, id: "c1" }]);
  });

  it("survives malformed JSON, arrays and missing ids without throwing", () => {
    expect(parseToolCalls(undefined)).toEqual([]);
    expect(parseToolCalls("nope")).toEqual([]);
    expect(parseToolCalls([{ function: { name: "OPEN_APP", arguments: "{not json" } }])).toEqual([
      { name: "OPEN_APP", arguments: {}, id: "call_0" },
    ]);
    expect(parseToolCalls([{ function: { name: "OPEN_APP", arguments: "[1,2]" } }])[0].arguments).toEqual({});
    expect(parseToolCalls([{ function: {} }])).toEqual([]);
    expect(parseToolCalls([{ function: { name: "", arguments: "{}" } }])).toEqual([]);
  });

  it("a parsed call still has to pass the whitelist before anything runs", () => {
    const [call] = parseToolCalls([{ function: { name: "RUN_SHELL", arguments: '{"cmd":"rm -rf /"}' } }]);
    expect(validateIntent({ action: call.name, parameters: call.arguments }).ok).toBe(false);
  });
});

describe("Labels and duration formatting never crash", () => {
  it("produces a readable sentence even with missing parameters", () => {
    // A label lands on the confirmation card, so it must never render
    // "undefined" or throw, whatever the model happened to send.
    for (const name of TOOL_NAMES) {
      const label = TOOL_LABELS[name]({});
      expect(typeof label, name).toBe("string");
      expect(label.length, name).toBeGreaterThan(0);
      expect(label, name).not.toContain("undefined");
      expect(label, name).not.toContain("NaN");
    }
  });

  it("formats durations", () => {
    expect(formatSeconds(0)).toBe("0 seconds");
    expect(formatSeconds(60)).toBe("1 minute");
    expect(formatSeconds(600)).toBe("10 minutes");
    expect(formatSeconds(3600)).toBe("1 hour");
    expect(formatSeconds(7200)).toBe("2 hours");
    expect(formatSeconds(45)).toBe("45 seconds");
  });
});
