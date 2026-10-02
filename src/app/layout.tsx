import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = {
  title: "MAX — Your Personal AI Assistant",
  description: "MAX is a futuristic personal AI assistant: talk or type, and MAX gets things done.",
};

export const viewport: Viewport = { themeColor: "#07080c", width: "device-width", initialScale: 1 };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body className="antialiased">{children}</body>
    </html>
  );
}
