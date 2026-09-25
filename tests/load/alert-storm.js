import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import { Counter } from 'k6/metrics';
import execution from 'k6/execution';

const mode = __ENV.LOAD_MODE || 'storm';
const isDefaultLimit = mode === 'default-limit';
if (!['storm', 'default-limit'].includes(mode)) {
  throw new Error('LOAD_MODE must be storm or default-limit');
}
if (!__ENV.API_URL || !__ENV.WEBHOOK_SECRET || !__ENV.STORM_FINGERPRINT) {
  throw new Error('API_URL, WEBHOOK_SECRET and STORM_FINGERPRINT are required');
}

const accepted = new Counter('webhook_accepted');
const limited = new Counter('webhook_rate_limited');

http.setResponseCallback(isDefaultLimit
  ? http.expectedStatuses(202, 429)
  : http.expectedStatuses(202));

export const options = {
  scenarios: {
    [mode]: isDefaultLimit
      ? { executor: 'constant-arrival-rate', rate: 10, timeUnit: '1s', duration: '10s', preAllocatedVUs: 20 }
      : { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '60s', preAllocatedVUs: 100, maxVUs: 300 },
  },
  thresholds: isDefaultLimit
    ? {
        http_req_failed: ['rate==0'],
        webhook_rate_limited: ['count>0'],
        webhook_accepted: ['count>0'],
        checks: ['rate==1'],
      }
    : {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
        dropped_iterations: ['count==0'],
        webhook_accepted: ['count==6000'],
        checks: ['rate==1'],
      },
};

function signedAlert(incidentFingerprint) {
  const timestamp = `${Math.floor(Date.now() / 1000)}`;
  const nonce = Array.from(new Uint8Array(crypto.randomBytes(16)), (byte) => byte.toString(16).padStart(2, '0')).join('');
  const sourceEventId = `${incidentFingerprint}-${nonce}`;
  const payload = JSON.stringify({
    version: '4',
    groupKey: incidentFingerprint,
    status: 'firing',
    receiver: 'sentinelops-load',
    groupLabels: { alertname: 'CheckoutConnectionPoolExhausted', service_key: 'checkout-api' },
    commonLabels: {
      alertname: 'CheckoutConnectionPoolExhausted',
      service_key: 'checkout-api',
      severity: 'sev1',
      incident_fingerprint: incidentFingerprint,
    },
    commonAnnotations: { summary: 'Synthetic connection-pool alert storm' },
    externalURL: 'http://alertmanager:9093',
    alerts: [{
      status: 'firing',
      labels: { alertname: 'CheckoutConnectionPoolExhausted', service_key: 'checkout-api', severity: 'sev1' },
      annotations: { summary: 'Synthetic connection-pool alert storm' },
      startsAt: new Date().toISOString(),
      endsAt: '0001-01-01T00:00:00Z',
      generatorURL: 'http://prometheus:9090/graph',
      fingerprint: sourceEventId,
    }],
  });
  const signature = crypto.hmac('sha256', __ENV.WEBHOOK_SECRET, `${timestamp}\n${nonce}\n${payload}`, 'hex');
  return {
    payload,
    headers: {
      'Content-Type': 'application/json',
      'X-Sentinel-Source': isDefaultLimit ? 'load-default' : 'load-test',
      'X-Sentinel-Timestamp': timestamp,
      'X-Sentinel-Nonce': nonce,
      'X-Sentinel-Signature': `v1=${signature}`,
      'X-SentinelOps-Event-Id': sourceEventId,
    },
  };
}

export default function () {
  // Arrival-rate scheduling can include one boundary iteration at 60 seconds.
  if (!isDefaultLimit && execution.scenario.iterationInTest >= 6000) return;
  const signed = signedAlert(__ENV.STORM_FINGERPRINT);
  const response = http.post(
    `${__ENV.API_URL.replace(/\/$/, '')}/api/v1/integrations/alertmanager/webhook`,
    signed.payload,
    { headers: signed.headers, timeout: '10s' },
  );
  if (response.status === 202) accepted.add(1);
  if (response.status === 429) limited.add(1);
  if (isDefaultLimit) {
    check(response, {
      'accepted or documented rate limit': (r) => r.status === 202 || (r.status === 429 && !!r.headers['Retry-After']),
    });
  } else {
    check(response, { 'alert accepted': (r) => r.status === 202 });
  }
}
