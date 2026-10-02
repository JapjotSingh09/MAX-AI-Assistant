import { config } from "@/lib/config";

// Email provider abstraction (EMAIL_PROVIDER).
// WHY: development uses the console, production must use a real transactional
// SMTP provider. Nothing here depends on any built-in/default email sender.
export type Mail = { to: string; subject: string; text: string };

export async function sendEmail(mail: Mail): Promise<{ sent: boolean }> {
  const { provider } = config.email;
  try {
    if (provider === "smtp") {
      const { smtpHost, smtpPort, smtpUser, smtpPassword, from } = config.email;
      if (!smtpHost) throw new Error("SMTP not configured");
      const nodemailer = await import("nodemailer");
      const transport = nodemailer.createTransport({
        host: smtpHost,
        port: smtpPort,
        secure: smtpPort === 465,
        auth: smtpUser ? { user: smtpUser, pass: smtpPassword } : undefined,
        connectionTimeout: 10000,
      });
      await transport.sendMail({ from, to: mail.to, subject: mail.subject, text: mail.text });
      return { sent: true };
    }
    // console provider: development only. We never log the full email body in production.
    console.log(JSON.stringify({ level: "info", msg: "email.console", to: mail.to, subject: mail.subject, body: mail.text }));
    return { sent: true };
  } catch (err) {
    // Graceful failure: the caller decides what to tell the user.
    console.error(JSON.stringify({ level: "error", msg: "email.failed", category: "email_provider", error: (err as Error).name }));
    return { sent: false };
  }
}

// In console mode there is no inbox, so the API echoes the link to make the flow testable.
export const emailEchoEnabled = () => config.email.provider === "console";
