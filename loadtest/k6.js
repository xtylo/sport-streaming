// Load tests for streaming-service (k6 2.x).
//
// Run from the repo root inside the dev container (k6 runs in Docker, no install needed):
//
//   docker run --rm -i --network host --user "$(id -u)" -v "$PWD/loadtest:/loadtest" \
//     -e PROFILE=baseline grafana/k6:2.3.0 run /loadtest/k6.js
//
// Environment variables:
//   PROFILE   smoke | baseline | stress        (default: smoke)
//   BASE_URL  service URL                       (default: http://localhost:8080)
//   MATCHES   matches seeded in setup()         (default: 20)
//
// Open model on purpose: the "constant/ramping-arrival-rate" executors start requests at a fixed
// rate whether or not the previous ones finished. A closed model (VUs + sleep) slows down when
// the server slows down and hides latency ("coordinated omission").

import http from 'k6/http';
import { check } from 'k6';

const PROFILE = __ENV.PROFILE || 'smoke';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MATCHES = parseInt(__ENV.MATCHES || '20', 10);
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

// ---------------------------------------------------------------------------
// Scenarios
// ---------------------------------------------------------------------------

// Warm-up: JIT, connection pools and Kafka producer metadata. Excluded from the thresholds.
const warmup = {
  executor: 'constant-arrival-rate',
  exec: 'snapshot',
  rate: 50, timeUnit: '1s', duration: '30s',
  preAllocatedVUs: 20, maxVUs: 50,
};

const profiles = {
  // Quick sanity check of the script and the endpoints.
  smoke: {
    snapshot: {
      executor: 'constant-arrival-rate', exec: 'snapshot',
      rate: 10, timeUnit: '1s', duration: '30s',
      preAllocatedVUs: 5, maxVUs: 20,
    },
    ingest: {
      executor: 'constant-arrival-rate', exec: 'ingest',
      rate: 2, timeUnit: '1s', duration: '30s',
      preAllocatedVUs: 2, maxVUs: 10,
    },
  },

  // Steady, realistic load: reads dominate, a trickle of match events.
  baseline: {
    warmup,
    snapshot: {
      executor: 'constant-arrival-rate', exec: 'snapshot', startTime: '30s',
      rate: 300, timeUnit: '1s', duration: '3m',
      preAllocatedVUs: 100, maxVUs: 300,
    },
    ingest: {
      executor: 'constant-arrival-rate', exec: 'ingest', startTime: '30s',
      rate: 20, timeUnit: '1s', duration: '3m',
      preAllocatedVUs: 10, maxVUs: 50,
    },
  },

  // Ramp the read rate until the service saturates (latency or errors climb).
  stress: {
    warmup,
    snapshot: {
      executor: 'ramping-arrival-rate', exec: 'snapshot', startTime: '30s',
      startRate: 200, timeUnit: '1s',
      stages: [
        { target: 500, duration: '1m' },
        { target: 1000, duration: '1m' },
        { target: 2000, duration: '1m' },
        { target: 3000, duration: '1m' },
        { target: 4000, duration: '1m' },
      ],
      preAllocatedVUs: 500, maxVUs: 3000,
    },
    ingest: {
      executor: 'constant-arrival-rate', exec: 'ingest', startTime: '30s',
      rate: 20, timeUnit: '1s', duration: '5m',
      preAllocatedVUs: 10, maxVUs: 50,
    },
  },
};

if (!profiles[PROFILE]) {
  throw new Error(`Unknown PROFILE "${PROFILE}". Use one of: ${Object.keys(profiles).join(', ')}`);
}

// Planned iterations per profile (approx.), used for the dropped_iterations threshold (0.1%).
const PLANNED = { smoke: 360, baseline: 59000, stress: 520000 };
const DROPPED_LIMIT = Math.max(1, Math.ceil(PLANNED[PROFILE] * 0.001));

// Thresholds never abort the run (abortOnFail: false): we want the full curve, especially in
// "stress". A failed threshold only makes k6 exit with code 99 at the end, which is expected there.
export const options = {
  scenarios: profiles[PROFILE],
  thresholds: {
    // Measured scenarios only (the warm-up has its own scenario tag)
    'http_req_duration{scenario:snapshot}': [
      { threshold: 'p(95)<200', abortOnFail: false },
      { threshold: 'p(99)<300', abortOnFail: false },
    ],
    'http_req_duration{scenario:ingest}': [
      { threshold: 'p(95)<200', abortOnFail: false },
      { threshold: 'p(99)<300', abortOnFail: false },
    ],
    'http_req_failed{scenario:snapshot}': [{ threshold: 'rate<0.01', abortOnFail: false }],
    'http_req_failed{scenario:ingest}': [{ threshold: 'rate<0.01', abortOnFail: false }],
    // Requests k6 wanted to start but could not (no free VU). A handful happen when k6 has to
    // create VUs mid-test; many means the load generator, not the service, was the bottleneck.
    // Limit: < 0.1% of the planned iterations of the profile.
    dropped_iterations: [`count<${DROPPED_LIMIT}`],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// ---------------------------------------------------------------------------
// Test lifecycle
// ---------------------------------------------------------------------------

// Seed some live matches so GET /stream returns a realistic payload.
export function setup() {
  const health = http.get(`${BASE_URL}/actuator/health`);
  if (health.status !== 200) {
    throw new Error(`Service not healthy at ${BASE_URL} (status ${health.status})`);
  }
  for (let i = 1; i <= MATCHES; i++) {
    http.post(`${BASE_URL}/events`,
      JSON.stringify({ matchId: `load-${i}`, type: 'KICKOFF', minute: 0 }), JSON_HEADERS);
  }
  return { matches: MATCHES };
}

// Read path: what most clients do (and what the original README test measured).
// Accept-Encoding like a browser; k6 decompresses gzip responses transparently, and
// data_received counts the bytes actually transferred (compressed).
export function snapshot() {
  const res = http.get(`${BASE_URL}/stream`, {
    tags: { name: 'GET /stream' },
    headers: { 'Accept-Encoding': 'gzip, deflate, br' },
  });
  check(res, { 'snapshot 200': (r) => r.status === 200 });
}

// Write path: a goal in a random seeded match (goes through Kafka with acks=all).
export function ingest(data) {
  const matchId = `load-${1 + Math.floor(Math.random() * data.matches)}`;
  const team = Math.random() < 0.5 ? 'Home' : 'Away';
  const res = http.post(`${BASE_URL}/events`,
    JSON.stringify({ matchId, type: 'GOAL', minute: 1 + Math.floor(Math.random() * 90), team }),
    Object.assign({ tags: { name: 'POST /events' } }, JSON_HEADERS));
  check(res, { 'ingest 202': (r) => r.status === 202 });
}

// Save the full summary as JSON (loadtest/results/ is git-ignored) and keep the console summary.
export function handleSummary(data) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    [`/loadtest/results/${PROFILE}-${stamp}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

// Compact console summary of the numbers that go into docs/RESULTS.md.
function textSummary(data) {
  const m = data.metrics;
  const fmt = (v) => (v === undefined ? 'n/a' : Number(v).toFixed(1));
  const line = (label, metric) => {
    const v = m[metric] && m[metric].values;
    return v
      ? `${label.padEnd(22)} p50=${fmt(v.med)}ms  p95=${fmt(v['p(95)'])}ms  p99=${fmt(v['p(99)'])}ms  max=${fmt(v.max)}ms`
      : `${label.padEnd(22)} n/a`;
  };
  const rate = (metric) => (m[metric] && m[metric].values ? m[metric].values.rate : undefined);
  const count = (metric) => (m[metric] && m[metric].values ? m[metric].values.count : 0);

  const thresholds = Object.entries(m)
    .filter(([, v]) => v.thresholds)
    .map(([name, v]) => Object.entries(v.thresholds)
      .map(([t, r]) => `  ${r.ok ? 'OK  ' : 'FAIL'} ${name} ${t}`).join('\n'))
    .join('\n');

  return [
    '',
    `=== streaming-service load test: PROFILE=${PROFILE} BASE_URL=${BASE_URL} ===`,
    line('GET /stream', 'http_req_duration{scenario:snapshot}'),
    line('POST /events', 'http_req_duration{scenario:ingest}'),
    `Throughput (all)       ${fmt(rate('http_reqs'))} req/s   requests=${count('http_reqs')}`,
    `Errors snapshot/ingest ${fmt(100 * (rate('http_req_failed{scenario:snapshot}') || 0))}% / ${fmt(100 * (rate('http_req_failed{scenario:ingest}') || 0))}%`,
    `Dropped iterations     ${count('dropped_iterations')}`,
    'Thresholds:',
    thresholds,
    '',
  ].join('\n');
}
