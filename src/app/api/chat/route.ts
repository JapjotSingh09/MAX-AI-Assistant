import { z } from "zod";
import { config } from "@/lib/config";
import { runAssistant } from "@/lib/assistant";
import { api, json, readJson } from "@/lib/http";

export const dynamic = "force-dynamic";

// POST /api/chat: runs the full command pipeline for one user message.
export const POST = api({ auth: true }, async ({ req, user }) => {
  const body = await readJson(
    req,
    z.object({
      conversationId: z.string().uuid().nullish(),
      message: z.string().trim().min(1, "Please type a message.").max(config.limits.maxMessageLength, "That message is too long."),
    }),
  );
  // user.id comes from the verified session, never from the request body.
  return json(await runAssistant(user.id, body.message, body.conversationId));
});
