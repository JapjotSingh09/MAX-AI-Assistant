# MAX — Load & Scale Testing Plan

**Status: the plan and script exist; the tests have NOT been run.** Do not claim 2,000-user capacity until you have run them
against infrastructure that matches production. Numbers below are what to *measure*, not results.

## Tooling
[k6](https://k6.io) script: `loadtest/k6-load.js`.

```bash
k6 run -e BASE_URL=https://staging.example.com -e USERS=100  loadtest/k6-load.js
k6 run -e BASE_URL=https://staging.example.com -e USERS=500  loadtest/k6-load.js
k6 run -e BASE_URL=https://staging.example.com -e USERS=1000 loadtest/k6-load.js
k6 run -e BASE_URL=https://staging.example.com -e USERS=2000 loadtest/k6-load.js
```

## Before you start
* Use a **staging** environment with the same DB plan, instance size and connection pooler as production.
* k6 sends everything from one IP, so raise `RATE_LIMIT_AUTH_PER_MINUTE` and `RATE_LIMIT_API_PER_MINUTE` on staging only.
* **Do not hit a paid AI provider** at scale unless you accept the bill. Use a mock/fake provider (`AI_PROVIDER=custom` + `AI_BASE_URL`
  pointing at a stub that answers `/chat/completions`) to test the AI path without cost, and run a small separate test with the real provider.
* Watch DB connections (`select count(*) from pg_stat_activity`), CPU, memory, and server logs while testing.

## Scenarios
| Stage | Virtual users | Purpose |
| --- | --- | --- |
| 1 | 100 | Baseline, sanity check |
| 2 | 500 | Typical busy period |
| 3 | 1000 | Stress: find the first bottleneck |
| 4 | 2000 | Target ceiling; observe graceful degradation (429s, not crashes) |

Each VU: sign up (password hashing), then 5× { local-first chat command (DB write), paginated activity read (DB read) } with think time.

## Metrics to record

| Metric | k6 source | Target (suggested) |
| --- | --- | --- |
| Login/sign-up latency | `login_latency` p95 | < 1000 ms |
| Database read latency | `db_read_latency` p95 | < 300 ms |
| Local command latency | `local_command_latency` p95 | < 500 ms |
| AI request latency | separate AI trend (add when testing a real/mocked provider) | provider-dependent |
| Error rate | `error_rate` | < 1 % (excluding intentional 429s) |
| Throughput | `http_reqs` rate | record |
| DB connections | `pg_stat_activity` | below plan limit |

## Known bottlenecks to watch
1. **scrypt password hashing is CPU-heavy** by design: sign-up/login throughput scales with CPU cores. Spread across instances.
2. **Postgres connections:** each server instance has its own pool (default 10). Use a pooler for many instances.
3. **Rate-limit table writes:** one UPSERT per request per bucket. Fine for thousands of req/s on a modest DB; if it becomes hot, move counters to Redis.
4. **AI provider quotas/tokens-per-minute** are usually the first real limit; per-user quotas protect your budget.
5. **Email provider** limits for sign-up verification bursts.

## Reporting template
```
Date / commit:
Infrastructure (DB plan, instances, pooler, AI provider/plan, email plan):
| Users | Login p95 | DB read p95 | Command p95 | AI p95 | Error rate | req/s | Notes |
```
Conclude with a sentence of the form: *"On <infrastructure>, MAX sustained <N> concurrent users with p95 < X ms and < Y % errors."*
Only after that may you state a supported capacity.
