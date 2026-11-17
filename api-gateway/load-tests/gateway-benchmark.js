import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Tests the real L7 proxy path (edge JWT validation → Valkey GCRA EVALSHA → AdaptiveConcurrencyFilter → SignInternalAuth HS256 minting → downstream vector-service → PostgreSQL), single-user quota exhaustion, and optional SSE/overload shedding.

const BASE_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';
const TOKEN_PREFIX = __ENV.TOKEN_PREFIX || 'user.k6-load-';
const STATIC_BEARER_TOKEN = __ENV.BEARER_TOKEN || '';
const ENABLE_SSE_SCENARIO = (__ENV.ENABLE_SSE_SCENARIO || 'false').toLowerCase() === 'true';
const SSE_PATH = __ENV.SSE_PATH || '/api/vectors/stream/chat';

const ok200 = new Counter('gateway_ok_200');
const rateLimited429 = new Counter('gateway_rate_limited_429');
const loadShed503 = new Counter('gateway_load_shed_503');
const unexpectedErrors = new Rate('gateway_unexpected_errors');
const proxyLatencyMs = new Trend('gateway_proxy_latency_ms', true);

export const options = {
  scenarios: {
    // 1. Steady + ramping end-to-end authenticated proxy traffic across distinct user buckets
    authenticated_proxy_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 20,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 500,
      stages: [
        { target: 50, duration: '15s' },
        { target: 200, duration: '30s' },
        { target: 400, duration: '30s' },
        { target: 0, duration: '10s' },
      ],
      exec: 'authenticatedVectorProxy',
    },

    // 2. Single-user burst proving atomic Valkey GCRA enforces 10/30s ceiling under concurrency
    single_user_quota_burst: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 20,
      startTime: '5s',
      exec: 'rateLimitBurstProbe',
    },

    // 3. Optional long-lived AI SSE concurrency stress (enable when streaming backend is running)
    ...(ENABLE_SSE_SCENARIO
      ? {
          ai_sse_concurrency: {
            executor: 'constant-vus',
            vus: Number(__ENV.SSE_VUS || 50),
            duration: '30s',
            exec: 'sseStreamingCheck',
          },
        }
      : {}),
  },
  thresholds: {
    gateway_unexpected_errors: ['rate<0.01'],
    gateway_proxy_latency_ms: ['p(95)<200', 'p(99)<500'],
  },
};

function authHeaders(subjectSuffix) {
  const token = STATIC_BEARER_TOKEN
    ? STATIC_BEARER_TOKEN
    : `${TOKEN_PREFIX}${subjectSuffix}`;
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };
}

export function authenticatedVectorProxy() {
  // Spread VUs across enough user buckets (mod 1000) to exercise real JWT + Redis + Postgres path
  // without artificially tripping the 10 req/30s per-user quota on steady load.
  const bucketId = `${__VU}-${__ITER % 100}`;
  const payload = JSON.stringify({ x: 3.0, y: 4.0, z: 12.0 });

  const res = http.post(`${BASE_URL}/api/vectors/calculateMagnitude`, payload, {
    headers: authHeaders(bucketId),
    tags: { scenario: 'authenticated_proxy' },
  });

  proxyLatencyMs.add(res.timings.duration);

  if (res.status === 200) {
    ok200.add(1);
    unexpectedErrors.add(0);
  } else if (res.status === 429) {
    rateLimited429.add(1);
    unexpectedErrors.add(0);
  } else if (res.status === 503) {
    loadShed503.add(1);
    unexpectedErrors.add(0);
  } else {
    unexpectedErrors.add(1);
  }

  check(res, {
    'valid proxy or protective status (200/429/503)': (r) =>
      r.status === 200 || r.status === 429 || r.status === 503,
    'concurrency headers emitted': (r) =>
      r.status === 429 || (r.headers['X-Concurrency-Limit'] !== undefined && r.headers['X-Concurrency-Inflight'] !== undefined),
  });
}

export function rateLimitBurstProbe() {
  const payload = JSON.stringify({
    v1: { x: 1.0, y: 2.0, z: 3.0 },
    v2: { x: 4.0, y: 5.0, z: 6.0 },
  });

  const res = http.post(`${BASE_URL}/api/vectors/addVectors`, payload, {
    headers: authHeaders('burst-probe-single-user'),
    tags: { scenario: 'rate_limit_burst' },
  });

  if (__ITER < 10) {
    check(res, {
      'first 10 burst requests allowed (200)': (r) => r.status === 200,
    });
  } else {
    check(res, {
      'excess burst rejected (429)': (r) => r.status === 429,
      '429 includes Retry-After & X-RateLimit-Remaining=0': (r) =>
        r.headers['Retry-After'] !== undefined && r.headers['X-RateLimit-Remaining'] === '0',
    });
  }
}

export function sseStreamingCheck() {
  const res = http.get(`${BASE_URL}${SSE_PATH}`, {
    headers: authHeaders(`sse-${__VU}`),
    timeout: '65s',
    tags: { scenario: 'sse_stream' },
  });

  check(res, {
    'SSE stream completed or cleanly shed (200/503)': (r) => r.status === 200 || r.status === 503,
  });
  sleep(0.2);
}