import { z } from "zod";
import { validateIntent } from "@/lib/commands/intent";

// Only triggers Android genuinely allows apps to react to (via WorkManager/AlarmManager
// and broadcast receivers). "Do Not Disturb at 10 PM" is NOT included because it needs
// special Notification Policy access.
export const TRIGGERS = ["TIME", "BLUETOOTH_CONNECTED", "CHARGER_CONNECTED"] as const;

export const automationInput = z
  .object({
    name: z.string().trim().min(1, "Please name your automation.").max(80),
    triggerType: z.enum(TRIGGERS),
    triggerConfig: z.object({ time: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/).optional(), deviceName: z.string().max(80).optional() }).strict().default({}),
    actionType: z.string(),
    actionConfig: z.record(z.string(), z.unknown()).default({}),
    enabled: z.boolean().default(true),
  })
  .superRefine((v, ctx) => {
    if (v.triggerType === "TIME" && !v.triggerConfig.time) {
      ctx.addIssue({ code: "custom", message: "Pick a time for this automation.", path: ["triggerConfig"] });
    }
    // The action is validated with the SAME whitelist and schemas as live commands.
    const r = validateIntent({ action: v.actionType, parameters: v.actionConfig });
    if (!r.ok) ctx.addIssue({ code: "custom", message: "That action isn't allowed or is missing details.", path: ["actionType"] });
  });

export const automationPatch = z.object({
  name: z.string().trim().min(1).max(80).optional(),
  enabled: z.boolean().optional(),
  triggerType: z.enum(TRIGGERS).optional(),
  triggerConfig: z.object({ time: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/).optional(), deviceName: z.string().max(80).optional() }).strict().optional(),
  actionType: z.string().optional(),
  actionConfig: z.record(z.string(), z.unknown()).optional(),
});
