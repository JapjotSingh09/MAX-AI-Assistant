import { defineConfig } from "drizzle-kit";

// Migration config. Reads DATABASE_URL from the environment so no host, IP or
// credential is hard-coded here. Local default matches .env.example; on Render
// set DATABASE_URL to your Postgres URL in the dashboard and run:
//   npx drizzle-kit push --config drizzle.config.ts
export default defineConfig({
  dialect: "postgresql",
  schema: "./src/db/schema.ts",
  dbCredentials: {
    url: process.env.DATABASE_URL || "postgresql://postgres:postgres@127.0.0.1:5432/app_db",
  },
});
