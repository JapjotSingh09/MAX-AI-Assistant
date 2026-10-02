export type Me = {
  user: { id: string; email: string; fullName: string | null; avatarUrl: string | null; emailVerified: boolean; joinedAt: string };
  aiAvailable: boolean;
};

export type Prefs = { theme: string; voiceEnabled: boolean; voiceName: string | null; assistantName: string; language: string };

export type Toast = (message: string, kind?: "ok" | "error") => void;

export type Seed = { text?: string; send?: boolean; listen?: boolean; nonce: number } | null;

export type ConnStatus = "online" | "offline" | "ai_unavailable";

export type ScreenProps = {
  me: Me;
  prefs: Prefs;
  status: ConnStatus;
  showToast: Toast;
  go: (s: Screen, seed?: Omit<NonNullable<Seed>, "nonce">) => void;
};

export type Screen = "home" | "assistant" | "automations" | "activity" | "settings" | "memory" | "driving";
