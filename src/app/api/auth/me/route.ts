import { aiConfigured } from "@/lib/config";
import { api, json } from "@/lib/http";

export const dynamic = "force-dynamic";

export const GET = api({ auth: true }, async ({ user }) => {
  return json({
    user: {
      id: user.id,
      email: user.email,
      fullName: user.fullName,
      avatarUrl: user.avatarUrl,
      emailVerified: user.emailVerified,
      joinedAt: user.createdAt,
    },
    aiAvailable: aiConfigured(),
  });
});
