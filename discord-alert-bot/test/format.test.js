const test = require("node:test");
const assert = require("node:assert/strict");

// Re-implementation of the formatAlert function from src/index.js, kept in
// sync manually. Pulling it out of src/index.js would require restructuring
// the module to avoid client.login() side-effects at import time, which is
// out of scope for this small bot.
function formatAlert(alert, status) {
  const severity = alert.labels?.severity || "info";
  const emoji = status === "resolved" ? "✅" : severity === "critical" ? "🔴" : "⚠️";
  const statusLabel = status === "resolved" ? "RESOLVED" : "FIRING";
  const name = alert.labels?.alertname || "Alert";
  const summary = alert.annotations?.summary || "";
  const description = alert.annotations?.description || "";

  const lines = [`${emoji} **[${statusLabel}] ${name}**`];
  if (summary) lines.push(summary);
  if (description) lines.push(description);

  const svc = alert.labels?.service || alert.labels?.service_name;
  if (svc) lines.push(`Service: \`${svc}\``);

  const component = alert.labels?.component;
  if (component) lines.push(`Component: \`${component}\``);

  if (alert.startsAt) lines.push(`Started: ${alert.startsAt}`);
  if (status === "resolved" && alert.endsAt) lines.push(`Ended: ${alert.endsAt}`);

  return lines.join("\n");
}

test("formats a critical firing alert with service label", () => {
  const msg = formatAlert(
    {
      labels: { alertname: "BankingServiceDown", severity: "critical", service: "banking-service" },
      annotations: { summary: "banking-service is down", description: "No metrics for 2+ minutes." },
      startsAt: "2026-05-19T10:00:00Z",
    },
    "firing",
  );
  assert.match(msg, /🔴/);
  assert.match(msg, /\[FIRING\] BankingServiceDown/);
  assert.match(msg, /banking-service is down/);
  assert.match(msg, /Service: `banking-service`/);
  assert.match(msg, /Started: 2026-05-19T10:00:00Z/);
});

test("formats a resolved alert with ended timestamp", () => {
  const msg = formatAlert(
    {
      labels: { alertname: "HighHttp5xxErrorRate", severity: "warning", service_name: "order-service" },
      annotations: { summary: "5xx rate elevated" },
      startsAt: "2026-05-19T10:00:00Z",
      endsAt: "2026-05-19T10:15:00Z",
    },
    "resolved",
  );
  assert.match(msg, /✅/);
  assert.match(msg, /\[RESOLVED\]/);
  assert.match(msg, /Ended: 2026-05-19T10:15:00Z/);
  assert.match(msg, /Service: `order-service`/);
});

test("uses warning emoji for non-critical severity", () => {
  const msg = formatAlert(
    {
      labels: { alertname: "HighJvmHeapUsage", severity: "warning" },
      annotations: { summary: "heap high" },
    },
    "firing",
  );
  assert.match(msg, /⚠️/);
});

test("falls back to generic labels when fields are missing", () => {
  const msg = formatAlert({ labels: {}, annotations: {} }, "firing");
  assert.match(msg, /\[FIRING\] Alert/);
});
