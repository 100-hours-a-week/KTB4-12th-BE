import http from 'k6/http';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const requests = new SharedArray('requests', () => JSON.parse(open(__ENV.FIXTURE)));
const published = new Counter('profiling_published');
const rejected = new Counter('profiling_publish_failed');
const plan = __ENV.LOAD_PLAN ? JSON.parse(__ENV.LOAD_PLAN) : [{ name: 'publish', rate: Number(__ENV.RATE_PER_MIN),
  duration: Number(__ENV.DURATION_SECONDS), start_seconds: 0, offset: 0 }];
const scenarios = {};
for (const stage of plan) {
  scenarios[stage.name] = { executor: 'constant-arrival-rate', rate: stage.rate, timeUnit: '1m',
    duration: `${stage.duration}s`, startTime: `${stage.start_seconds}s`, preAllocatedVUs: 10, maxVUs: 50,
    gracefulStop: '15s' };
}
export const options = {
  // Avoid idle keep-alive EOFs in the local Management HTTP publishing path.
  noConnectionReuse: true,
  scenarios,
  thresholds: { dropped_iterations: ['count==0'], profiling_publish_failed: ['count==0'] },
};

export default function () {
  const stage = plan.find(item => item.name === exec.scenario.name);
  if (stage.fixture_count && exec.scenario.iterationInTest >= stage.fixture_count) throw new Error('stage fixture exhausted');
  const fixture = requests[stage.offset + exec.scenario.iterationInTest];
  if (!fixture) throw new Error('fixture exhausted');
  const sent = Date.now();
  const request = { ...fixture, createdAt: new Date(sent).toISOString() };
  const payload = JSON.stringify(request);
  const encoded = encoding.b64encode(payload);
  const payloadBytes = encoded.length * 3 / 4 - (encoded.endsWith('==') ? 2 : encoded.endsWith('=') ? 1 : 0);
  const response = http.post('http://127.0.0.1:25673/api/exchanges/profiling-bench/profiling.exchange/publish',
    JSON.stringify({ routing_key: 'profiling.request', payload, payload_encoding: 'string',
      properties: { content_type: 'application/json', content_encoding: 'UTF-8', delivery_mode: 2,
        message_id: request.requestId } }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Basic ${encoding.b64encode('bench:bench-local-only')}` },
      timeout: '10s', tags: { name: 'profiling_publish' } });
  let routed = false;
  try { routed = response.status === 200 && response.json('routed') === true; } catch (_) { /* recorded below */ }
  published.add(routed ? 1 : 0);
  rejected.add(routed ? 0 : 1);
  console.log(`LOAD_EVENT ${JSON.stringify({ request_id: request.requestId, sent_at_ms: sent,
    stage: exec.scenario.name, scenario_start_ms: exec.scenario.startTime,
    routed, payload_bytes: payloadBytes, http_status: response.status, error_code: response.error_code || null,
    error: response.error || null })}`);
}
