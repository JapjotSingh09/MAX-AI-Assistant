// Basic load test for the MAX API using k6 (https://k6.io).
//
//   k6 run -e BASE_URL=https://your-staging-host -e USERS=100 loadtest/k6-load.js
//
// Run it with USERS=100, 500, 1000, then 2000 against a STAGING environment that mirrors
// production (same database plan, same AI provider limits). Never load-test production
// AI providers with real keys unless you accept the cost.
//
// Set LOAD_TEST_RATE_LIMIT_BYPASS only on staging by raising RATE_LIMIT_* env vars on the
// server; each virtual user uses its own account, but all share one source IP in k6, so
// RATE_LIMIT_AUTH_PER_MINUTE must be raised on the staging server.
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://localhost:3000";
const USERS = Number(__ENV.USERS || 100);

const loginLatency = new Trend("login_latency", true);
const dbLatency = new Trend("db_read_latency", true);
const chatLatency = new Trend("local_command_latency", true);
const errors = new Rate("error_rate");

export const options = {
  scenarios: {
    ramp: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "1m", target: USERS },
        { duration: "3m", target: USERS },
        { duration: "30s", target: 0 },
      ],
    },
  },
  thresholds: {
    error_rate: ["rate<0.01"],
    http_req_duration: ["p(95)<1500"],
  },
};

const json = { headers: { "Content-Type": "application/json" } };

export default function () {
  const email = `load-${__VU}-${__ITER}-${Date.now()}@loadtest.dev`;

  // 1. Sign up (also measures password hashing cost)
  let res = http.post(`${BASE}/api/auth/signup`, JSON.stringify({ email, password: "loadtest-pass-1" }), json);
  errors.add(res.status !== 201);
  loginLatency.add(res.timings.duration);
  if (res.status !== 201) return sleep(1);
  const cookie = res.headers["Set-Cookie"].split(";")[0];
  const auth = { headers: { "Content-Type": "application/json", Cookie: cookie } };

  for (let i = 0; i < 5; i++) {
    // 2. Local-first command: DB write path, no AI call
    res = http.post(`${BASE}/api/chat`, JSON.stringify({ message: "set timer for 5 minutes" }), auth);
    check(res, { "chat 200": (r) => r.status === 200 });
    errors.add(res.status !== 200);
    chatLatency.add(res.timings.duration);

    // 3. Paginated reads: DB read path
    res = http.get(`${BASE}/api/activity?limit=25`, auth);
    check(res, { "activity 200": (r) => r.status === 200 });
    errors.add(res.status !== 200);
    dbLatency.add(res.timings.duration);

    sleep(Math.random() * 3 + 1);
  }
  // AI latency: only meaningful when AI_API_KEY is set on staging. Add a cloud question here
  // (e.g. "Explain gravity") and track a separate Trend, but mind provider cost/quotas.
}
