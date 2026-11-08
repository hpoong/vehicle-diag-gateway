"use strict";
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmt = (n) => (n == null ? "-" : Number(n).toLocaleString("ko-KR"));

const state = {
  services: null, fault: null, job: null, canPause: true,
  logCursor: 0, jobId: null, samples: [], scenarios: [], lastResult: {},
  opsStatus: "DEAD", logSel: "gateway", tab: "trace",
  fleet: null, fleetJobId: null, trace: null, fwdLot: null,
};

async function api(path, opts = {}) {
  const init = { method: opts.method || "GET", headers: {} };
  if (opts.body !== undefined) { init.body = JSON.stringify(opts.body); init.headers["Content-Type"] = "application/json"; }
  const res = await fetch("/api/" + path, init);
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const msg = body?.error || body?.detail || body?.title || `HTTP ${res.status}`;
    throw Object.assign(new Error(msg), { status: res.status, body });
  }
  return body;
}

let toastTimer;
function toast(msg, err = false) {
  const t = $("#toast");
  t.textContent = msg; t.className = "toast" + (err ? " err" : ""); t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), err ? 6000 : 2500);
}

async function startJob(body) {
  try {
    const job = await api("jobs", { method: "POST", body });
    state.jobId = job.id; state.logCursor = 0;
    $("#console").innerHTML = ""; $("#jobResult").innerHTML = "";
    if (body.type === "scenario" || body.type === "simulate") selectTab("scenarios");
    if (body.type === "fleet") { $("#fleetLog").innerHTML = ""; $("#fleetLog").hidden = false; $("#fleetSummary").innerHTML = ""; }
    pollJob();
  } catch (e) { toast(e.message, true); }
}

/* ------------------------------------------------------------------ 서비스 */
const SVC_LABEL = { postgres: "PostgreSQL", gateway: "gateway gw-1", gateway2: "gateway gw-2", receiver: "receiver" };
const STATE_LABEL = { up: "실행 중", down: "중지", starting: "기동 중", paused: "일시 정지", external: "외부 실행" };

function renderPills() {
  const s = state.services; if (!s) return;
  $("#pills").innerHTML = Object.keys(SVC_LABEL).map((k) => {
    const st = s[k]?.state || "down";
    const extra = k === "receiver" && s[k]?.options?.naive ? " · naive" : "";
    return `<span class="pill" title="${esc(STATE_LABEL[st])}"><span class="dot ${st}"></span>${esc(SVC_LABEL[k])}${extra}</span>`;
  }).join("") + `<span class="pill">장애 <b>${esc(state.fault?.mode || "?")}</b></span>`;
}

function renderServices() {
  const s = state.services; if (!s) return;
  const busy = state.job?.state === "running";
  const b = (name, action, label, cls = "", extra = "") =>
    `<button class="btn ${cls}" data-svc="${name}" data-act="${action}" ${extra} ${busy ? "disabled" : ""}>${label}</button>`;
  const rows = [];
  const pg = s.postgres;
  rows.push(`<div class="svc"><div class="svc-name"><span class="dot ${pg.state}"></span>PostgreSQL</div>
    <div class="svc-meta">:${pg.port} · ${esc(STATE_LABEL[pg.state])}</div>
    <div class="svc-actions">${b("postgres", "up", "compose up")}${b("postgres", "down", "stop")}</div></div>`);
  for (const name of ["receiver", "gateway", "gateway2"]) {
    const v = s[name]; const st = v.state; const running = ["up", "starting", "paused"].includes(st);
    const meta = [`:${v.port}`, STATE_LABEL[st], v.pid ? `pid ${v.pid}` : "", v.options?.naive ? "naive" : ""].filter(Boolean).join(" · ");
    let acts = "";
    if (!running && st !== "external") {
      acts += b(name, "start", "시작", "primary");
      if (name === "receiver") acts += b(name, "start", "시작 (naive)", "", 'data-naive="1"');
    }
    if (running) {
      acts += b(name, "stop", "정지");
      acts += b(name, "kill", "강제 종료", "danger", 'title="SIGKILL"');
      if (state.canPause) acts += st === "paused" ? b(name, "resume", "재개", "primary", 'title="SIGCONT"') : b(name, "pause", "일시 정지", "", 'title="SIGSTOP"');
    }
    if (st === "external") acts = `<span class="hint">스크립트 밖에서 실행 중 (제어 불가)</span>`;
    rows.push(`<div class="svc"><div class="svc-name"><span class="dot ${st}"></span>${esc(SVC_LABEL[name])}</div>
      <div class="svc-meta">${esc(meta)}</div><div class="svc-actions">${acts}</div></div>`);
  }
  $("#services").innerHTML = rows.join("");
}

document.addEventListener("click", (ev) => {
  const btn = ev.target.closest("[data-svc]"); if (!btn) return;
  const name = btn.dataset.svc;
  if (name === "build") return startJob({ type: "service", action: "build" });
  const action = btn.dataset.act;
  if (action === "kill" && !confirm(`${SVC_LABEL[name]}를 SIGKILL로 강제 종료할까요?`)) return;
  startJob({ type: "service", name, action, naive: !!btn.dataset.naive });
});

/* ------------------------------------------------------------------ 장애 주입 */
function renderFault() {
  const mode = state.fault?.mode;
  $$("#faultSeg button").forEach((b) => b.setAttribute("aria-checked", String(b.dataset.mode === mode)));
  $("#faultNow").textContent = mode ? `${mode}${mode === "DROP_AFTER_COMMIT" ? ` ${state.fault.holdMs}ms` : ""}` : "알 수 없음";
  $("#faultNow").className = "tag " + (mode === "NONE" ? "good" : mode ? "warn" : "");
}
$("#faultSeg").addEventListener("click", async (ev) => {
  const b = ev.target.closest("button"); if (!b) return;
  try {
    const body = { mode: b.dataset.mode };
    if (b.dataset.mode === "DROP_AFTER_COMMIT") body.holdMs = Number($("#holdMs").value || 6000);
    state.fault = await api("fault", { method: "POST", body });
    renderFault(); renderPills(); toast(`장애 모드: ${state.fault.mode}`);
  } catch (e) { toast(e.message, true); }
});

/* ------------------------------------------------------------------ 시뮬레이터 */
$$("#simForm .range").forEach((l) => {
  const i = $("input", l), o = $("output", l);
  const sync = () => (o.textContent = Math.round(i.value * 100) + "%");
  i.addEventListener("input", sync); sync();
});
$("#simForm").addEventListener("submit", (ev) => {
  ev.preventDefault();
  const params = Object.fromEntries(new FormData(ev.target).entries());
  startJob({ type: "simulate", params });
});

/* ------------------------------------------------------------------ 데이터 */
$("#btnReset").addEventListener("click", async () => {
  if (!confirm("로컬 gateway·receiver DB의 업무 데이터를 모두 지웁니다.")) return;
  try { const r = await api("reset", { method: "POST" }); state.samples = []; toast(`초기화: 결과 ${r.diagnostic_result}건, 수신 ${r.received_event}건 삭제`); }
  catch (e) { toast(e.message, true); }
});
async function runReconcile() {
  selectTab("recon");
  $("#recon").innerHTML = '<p class="empty">대조 중…</p>';
  try {
    const r = await api("reconcile?mode=" + ($("#reconNaive").checked ? "naive" : "standard"));
    $("#recon").innerHTML = reconHtml(r);
  } catch (e) { $("#recon").innerHTML = `<p class="empty">${esc(e.message)}</p>`; }
}
$("#btnReconcile").addEventListener("click", runReconcile);
$("#btnReconcile2").addEventListener("click", runReconcile);

const VERDICT_DESC = {
  I1: "결과 수 = outbox 수 (저장·전송 예약 원자성)",
  I2: "PENDING + SENT + DEAD = outbox 수",
  I3: "SENT인데 receiver에 없는 이벤트 0건 (누락)",
  I4: "누락 0건, SENT 아닌 이벤트의 반영 0건",
  I5: "이벤트별 ECU·DTC 쌍 = case_count = quality_case 수",
  I6: "(VIN, ECU) 최신 상태 = 가장 늦은 진단 시각",
  E1: "저장 결과 수 = 시뮬레이터 기대 건수",
};
function reconHtml(r) {
  const kv = [["결과", r.results], ["outbox", r.outbox], ["PENDING", r.pending], ["SENT", r.sent], ["DEAD", r.dead],
    ["receiver 반영", r.received], ["품질 건", r.cases], ["중복 그룹 DUP1", r.dup1]]
    .map(([k, v]) => `<div><span>${k}</span><b>${fmt(v)}</b></div>`).join("");
  const rows = Object.entries(r.verdicts).map(([k, v]) =>
    `<tr><td class="mono">${k}</td><td><span class="verdict ${v.status}">${v.status}</span></td><td>${esc(VERDICT_DESC[k] || "")}</td><td class="mono">${esc(v.detail)}</td></tr>`).join("");
  const inst = Object.entries(r.successByInstance || {}).map(([k, v]) => `${k}=${v}`).join(", ") || "-";
  const late = Object.entries(r.lateOutcomes || {}).map(([k, v]) => `${k}=${v}`).join(", ") || "-";
  return `<div class="kv">${kv}</div>
    <div class="table-wrap" style="margin-top:12px"><table class="grid"><thead><tr><th>항목</th><th>판정</th><th>의미</th><th>상세</th></tr></thead><tbody>${rows}</tbody></table></div>
    <p class="hint">시도 결과 ${esc(JSON.stringify(r.attempts))} · 인스턴스별 SUCCESS ${esc(inst)} · late_outcome ${esc(late)}</p>
    <div class="resline">${esc(r.line)}</div>`;
}

/* ------------------------------------------------------------------ 실시간 현황 */
const SERIES = [
  { key: "PENDING", label: "PENDING", color: "var(--series-1)" },
  { key: "SENT", label: "SENT", color: "var(--series-2)" },
  { key: "received", label: "receiver 반영", color: "var(--series-3)" },
];
$("#tlLegend").innerHTML = SERIES.map((s) => `<span><i style="background:${s.color}"></i>${s.label}</span>`).join("");

function renderCounts(c) {
  if (!c.ok) {
    $("#tiles").innerHTML = `<div class="tile" style="grid-column:1/-1"><div class="label">DB 연결 실패</div><div class="note">${esc(c.error)}</div></div>`;
    return;
  }
  $("#countsAt").textContent = new Date().toLocaleTimeString("ko-KR");
  const total = c.PENDING + c.SENT + c.DEAD;
  const tiles = [
    ["outbox 전체", total, "진단 결과 1건 = outbox 1건"],
    ["PENDING", c.PENDING, "전송 대기·재시도 대기", c.PENDING ? "◔" : ""],
    ["SENT", c.SENT, "receiver 반영 확인"],
    ["DEAD", c.DEAD, "자동 처리 중단 → 운영 재처리", c.DEAD ? "⚠" : ""],
    ["receiver 반영", c.received, c.naiveCases ? `naive 품질 건 ${fmt(c.naiveCases)}` : "received_event"],
  ];
  $("#tiles").innerHTML = tiles.map(([l, v, n, icon]) =>
    `<div class="tile"><div class="label">${icon ? `<span aria-hidden="true" style="color:${l === "DEAD" ? "var(--critical-ink)" : "var(--warning-ink)"}">${icon}</span>` : ""}${l}</div><div class="value">${fmt(v)}</div><div class="note">${esc(n)}</div></div>`).join("");

  const order = ["SUCCESS", "RETRYABLE", "UNCERTAIN", "ABANDONED", "NON_RETRYABLE", "SKIPPED_MAX_ATTEMPTS", "STARTED"];
  const entries = order.map((k) => [k, c.attempts[k] || 0]).filter(([k, v]) => v > 0 || ["SUCCESS", "RETRYABLE", "UNCERTAIN", "ABANDONED"].includes(k));
  const max = Math.max(1, ...entries.map(([, v]) => v));
  $("#attemptBars").innerHTML = entries.map(([k, v]) =>
    `<div class="hbar" title="${k}: ${v}"><span class="name">${k}</span><span class="track"><span class="fill" style="width:${(v / max) * 100}%"></span></span><span class="val">${fmt(v)}</span></div>`).join("");

  state.samples.push({ t: Date.now(), PENDING: c.PENDING, SENT: c.SENT, received: c.received });
  if (state.samples.length > 120) state.samples.shift();
  drawTimeline();
}

function drawTimeline() {
  const el = $("#tlPlot"); const data = state.samples;
  const W = el.clientWidth || 600, H = el.clientHeight || 190;
  const m = { l: 40, r: 90, t: 8, b: 20 };
  const iw = W - m.l - m.r, ih = H - m.t - m.b;
  if (data.length < 2) { el.innerHTML = '<p class="empty">수집 중…</p>'; return; }
  const t0 = data[0].t, t1 = data[data.length - 1].t;
  const ymax = Math.max(4, ...data.flatMap((d) => SERIES.map((s) => d[s.key])));
  const nice = niceMax(ymax);
  const x = (t) => m.l + ((t - t0) / Math.max(1, t1 - t0)) * iw;
  const y = (v) => m.t + ih - (v / nice) * ih;
  const ticks = [0, nice / 2, nice];
  let svg = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="outbox 상태 추이"><g class="axis">`;
  for (const tv of ticks) svg += `<line class="gridline" x1="${m.l}" x2="${m.l + iw}" y1="${y(tv)}" y2="${y(tv)}"/><text x="${m.l - 6}" y="${y(tv) + 3}" text-anchor="end">${fmt(Math.round(tv))}</text>`;
  svg += `<text x="${m.l}" y="${H - 4}">-${Math.round((t1 - t0) / 1000)}s</text><text x="${m.l + iw}" y="${H - 4}" text-anchor="end">지금</text></g>`;
  const last = data[data.length - 1];
  const labelYs = SERIES.map((s) => ({ s, y: y(last[s.key]) })).sort((a, b) => a.y - b.y);
  for (let i = 1; i < labelYs.length; i++) if (labelYs[i].y - labelYs[i - 1].y < 12) labelYs[i].y = labelYs[i - 1].y + 12;
  for (const s of SERIES) {
    const d = data.map((p, i) => `${i ? "L" : "M"}${x(p.t).toFixed(1)},${y(p[s.key]).toFixed(1)}`).join("");
    svg += `<path class="series" d="${d}" stroke="${s.color}"/>`;
  }
  for (const { s, y: ly } of labelYs) svg += `<text class="endlabel" x="${m.l + iw + 8}" y="${ly + 4}">${s.label} ${fmt(last[s.key])}</text>`;
  svg += `<line class="cross" id="tlCross" y1="${m.t}" y2="${m.t + ih}" visibility="hidden"/>`;
  svg += `<rect x="${m.l}" y="${m.t}" width="${iw}" height="${ih}" fill="transparent" id="tlHit"/></svg><div class="tip" id="tlTip" hidden></div>`;
  el.innerHTML = svg;
  const hit = $("#tlHit", el), cross = $("#tlCross", el), tip = $("#tlTip", el);
  hit.addEventListener("mousemove", (ev) => {
    const r = el.getBoundingClientRect(); const px = (ev.clientX - r.left) * (W / r.width);
    const t = t0 + ((px - m.l) / iw) * (t1 - t0);
    let best = data[0]; for (const p of data) if (Math.abs(p.t - t) < Math.abs(best.t - t)) best = p;
    const cx = x(best.t); cross.setAttribute("x1", cx); cross.setAttribute("x2", cx); cross.setAttribute("visibility", "visible");
    tip.hidden = false;
    tip.innerHTML = `<b>${new Date(best.t).toLocaleTimeString("ko-KR")}</b><br>` + SERIES.map((s) => `<i style="background:${s.color}"></i>${s.label} ${fmt(best[s.key])}`).join("<br>");
    const left = (cx / W) * r.width; tip.style.left = Math.min(left + 12, r.width - 150) + "px"; tip.style.top = "8px";
  });
  hit.addEventListener("mouseleave", () => { cross.setAttribute("visibility", "hidden"); tip.hidden = true; });
}
function niceMax(v) { const p = Math.pow(10, Math.floor(Math.log10(v))); for (const k of [1, 2, 2.5, 5, 10]) if (k * p >= v) return k * p; return 10 * p; }
window.addEventListener("resize", drawTimeline);

/* ------------------------------------------------------------------ 시나리오 */
async function loadScenarios() {
  state.scenarios = await api("scenarios");
  renderScenarios();
}
function renderScenarios() {
  const busy = state.job?.state === "running";
  $("#scenarios").innerHTML = state.scenarios.map((s) => {
    const last = state.lastResult[s.id];
    const tag = last == null ? "" : `<span class="tag ${last ? "good" : "bad"}">${last ? "PASS" : "FAIL"}</span>`;
    return `<article class="scen"><div class="scen-h"><span class="scen-id">${s.id}</span><span class="scen-t">${esc(s.title)}</span>${tag}</div>
      <p>${esc(s.desc)}</p><p class="expect">기대: ${esc(s.expect)}</p>
      <div class="scen-f"><span class="tag">${esc(s.fault)}</span><button class="btn sm primary" data-scen="${s.id}" ${busy ? "disabled" : ""}>실행</button></div></article>`;
  }).join("");
  $("#btnRunAll").disabled = busy;
  $("#btnStop").disabled = !busy;
}
$("#scenarios").addEventListener("click", (ev) => {
  const b = ev.target.closest("[data-scen]"); if (b) startJob({ type: "scenario", id: b.dataset.scen });
});
$("#btnRunAll").addEventListener("click", () => {
  if (!confirm("시나리오 전체를 순서대로 실행합니다 (수 분 소요, 매 시나리오마다 DB 초기화).")) return;
  startJob({ type: "scenario", ids: state.scenarios.map((s) => s.id) });
});
$("#btnStop").addEventListener("click", () => api("jobs/stop", { method: "POST" }).catch((e) => toast(e.message, true)));

/* ------------------------------------------------------------------ 작업 폴링 */
let jobPolling = false;
async function pollJob() {
  if (jobPolling) return; jobPolling = true;
  try {
    while (true) {
      const j = await api("jobs/current?since=" + state.logCursor);
      if (!j) break;
      if (j.id !== state.jobId) { state.jobId = j.id; state.logCursor = 0; $("#console").innerHTML = ""; continue; }
      appendLogs(j.logs, j.kind); state.logCursor = j.logCount;
      renderJob(j);
      if (j.state !== "running") break;
      await sleep(700);
    }
  } catch (e) { toast(e.message, true); }
  jobPolling = false;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function appendLogs(lines, kind) {
  if (!lines.length) return;
  const html = lines.map((l) => {
    const cls = l.includes("✔") || l.includes("PASS") && l.includes("결과") ? "ok" : l.includes("✘") || l.includes("FAIL") ? "ng" : l.includes("▶") || l.includes("=====") ? "st" : "";
    return `<span class="${cls}">${esc(l)}</span>\n`;
  }).join("");
  for (const c of kind === "fleet" ? [$("#console"), $("#fleetLog")] : [$("#console")]) {
    const atBottom = c.scrollHeight - c.scrollTop - c.clientHeight < 40;
    c.insertAdjacentHTML("beforeend", html);
    if (atBottom) c.scrollTop = c.scrollHeight;
  }
}

function renderJob(j) {
  state.job = j;
  $("#jobTitle").textContent = j.title;
  const st = $("#jobState");
  st.textContent = { running: "실행 중", done: "완료", failed: "실패" }[j.state] + ` · ${j.elapsedSec}s`;
  st.className = "tag " + (j.state === "running" ? "run" : j.state === "done" ? "good" : "bad");
  const chip = $("#jobchip");
  chip.hidden = j.state !== "running"; chip.textContent = `실행 중: ${j.title}`;
  const p = j.progress; const bar = $("#jobProgress");
  bar.hidden = !(p && p.total); if (p && p.total) $("div", bar).style.width = (p.done / p.total) * 100 + "%";
  if (j.state !== "running" && j.result) {
    const r = j.result;
    if (r.scenario) state.lastResult[r.scenario] = r.passed;
    if (r.batch) r.batch.forEach((b) => (state.lastResult[b.scenario] = b.passed));
    $("#jobResult").innerHTML = resultHtml(r);
    loadHistory();
  } else if (j.state === "failed") {
    $("#jobResult").innerHTML = `<ul class="checks"><li class="fail"><span class="mark">✘</span><span>${esc(j.error)}</span></li></ul>`;
  }
  if (j.kind === "fleet") renderFleetJob(j);
  renderScenarios(); renderServices();
}

function resultHtml(r) {
  let h = "";
  if (r.fleet) return fleetSummaryHtml(r.fleet);
  if (r.batch) {
    h += `<ul class="checks">${r.batch.map((b) => `<li class="${b.passed ? "pass" : "fail"}"><span class="mark">${b.passed ? "✔" : "✘"}</span><span><b>${b.scenario}</b> ${esc(b.title)} <span class="d">${b.elapsedSec}s ${esc(b.error || "")}</span></span></li>`).join("")}</ul>`;
    return h;
  }
  if (r.checks) h += `<ul class="checks">${r.checks.map((c) => `<li class="${c.ok ? "pass" : "fail"}"><span class="mark">${c.ok ? "✔" : "✘"}</span><span>${esc(c.name)}<br><span class="d">${esc(c.detail)}</span></span></li>`).join("")}</ul>`;
  const sim = r.simulation;
  if (sim) {
    const kinds = Object.entries(sim.byKind).map(([k, v]) => `<tr><td class="mono">${k}</td><td class="mono">${esc(Object.entries(v).map(([s, n]) => `${s}×${n}`).join("  "))}</td></tr>`).join("");
    h += `<div class="table-wrap" style="margin-top:10px"><table class="grid"><thead><tr><th>전송 종류</th><th>응답 코드</th></tr></thead><tbody>${kinds}</tbody></table></div>
      <p class="hint">run ${esc(sim.runId)} · 저장 기대 ${fmt(sim.expectedResults)}건 · p50 ${sim.latencyMs.p50}ms · p95 ${sim.latencyMs.p95}ms · ${sim.throughputPerSec}/s · 기대와 다른 응답 ${sim.unexpectedCount}건</p>`;
    if (sim.unexpected?.length) h += `<pre class="preview">${esc(JSON.stringify(sim.unexpected.slice(0, 3), null, 2))}</pre>`;
  }
  if (r.trace) h += t1TraceHtml(r.trace);
  if (r.reconcile) h += `<div class="resline">${esc(r.reconcile.line)}</div>`;
  return h;
}

async function loadHistory() {
  const rows = await api("history").catch(() => []);
  $("#history").innerHTML = `<thead><tr><th>#</th><th>작업</th><th>상태</th><th>소요</th><th>오류</th></tr></thead><tbody>` +
    (rows.map((h) => `<tr><td class="mono">${h.id}</td><td>${esc(h.title)}</td><td><span class="tag ${h.state === "done" ? "good" : "bad"}">${h.state}</span></td><td class="num">${h.elapsedSec}s</td><td class="err" title="${esc(h.error || "")}">${esc(h.error || "")}</td></tr>`).join("") ||
      '<tr><td colspan="5" class="empty">아직 기록이 없습니다.</td></tr>') + "</tbody>";
}

/* ------------------------------------------------------------------ 운영 콘솔 */
const opsGw = () => encodeURIComponent($("#opsGateway").value);
async function loadOps() {
  try {
    const s = (await api(`ops/summary?gateway=${opsGw()}`)).data;
    $("#opsSummary").innerHTML = [["PENDING", s.counts.PENDING], ["SENT", s.counts.SENT], ["DEAD", s.counts.DEAD], ["TOTAL", s.counts.TOTAL],
      ["가장 오래된 PENDING", s.oldestPendingAgeSeconds == null ? "-" : s.oldestPendingAgeSeconds + "s"],
      ["DEAD · NON_RETRYABLE", s.dead.NON_RETRYABLE], ["DEAD · MAX_ATTEMPTS", s.dead.MAX_ATTEMPTS]]
      .map(([k, v]) => `<div><span>${k}</span><b>${typeof v === "number" ? fmt(v) : esc(v)}</b></div>`).join("");
  } catch (e) { $("#opsSummary").innerHTML = `<p class="empty">${esc(e.message)}</p>`; }
  try {
    const list = (await api(`ops/events?status=${state.opsStatus}&limit=100&gateway=${opsGw()}`)).data;
    const dead = state.opsStatus === "DEAD";
    $("#opsEvents").innerHTML = `<thead><tr><th>outbox</th><th>result</th><th>시도</th><th>사이클</th><th>마지막 결과</th><th>HTTP</th><th>오류</th>${dead ? "<th>사유</th><th></th>" : "<th>갱신</th>"}</tr></thead><tbody>` +
      (list.map((e) => `<tr class="click" data-outbox="${e.outboxId}"><td class="mono">${e.outboxId}</td><td class="mono">${e.resultId}</td><td class="num">${e.attemptCount}</td><td class="num">${e.cycleNo}</td>
        <td class="mono">${esc(e.lastOutcome || "-")}</td><td class="num">${e.lastHttpStatus ?? "-"}</td><td class="err" title="${esc(e.lastError || "")}">${esc(e.lastError || "")}</td>
        ${dead ? `<td class="mono">${esc(e.deadReason)}</td><td><button class="btn sm" data-requeue="${e.outboxId}">재처리</button></td>` : `<td class="mono">${esc((e.updatedAt || "").replace("T", " ").slice(0, 19))}</td>`}</tr>`).join("") ||
        `<tr><td colspan="9" class="empty">${state.opsStatus} 이벤트가 없습니다.</td></tr>`) + "</tbody>";
  } catch (e) { $("#opsEvents").innerHTML = `<tbody><tr><td class="empty">${esc(e.message)}</td></tr></tbody>`; }
}
$("#btnOpsRefresh").addEventListener("click", loadOps);
$("#opsGateway").addEventListener("change", loadOps);
$("#opsStatus").addEventListener("click", (ev) => {
  const b = ev.target.closest("button"); if (!b) return;
  state.opsStatus = b.dataset.st;
  $$("#opsStatus button").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
  loadOps();
});
$("#opsEvents").addEventListener("click", async (ev) => {
  const rq = ev.target.closest("[data-requeue]");
  if (rq) {
    ev.stopPropagation();
    const reason = prompt("재처리 사유 (10자 이상)", "receiver 복구 확인 후 수동 재처리");
    if (!reason) return;
    try {
      await api(`ops/events/${rq.dataset.requeue}/requeue?gateway=${opsGw()}`, { method: "POST", body: { reason, operator: "front" } });
      toast(`outbox ${rq.dataset.requeue} 재처리 요청`); loadOps();
    } catch (e) { toast(e.message, true); }
    return;
  }
  const tr = ev.target.closest("[data-outbox]"); if (!tr) return;
  try {
    const rows = (await api(`ops/events/${tr.dataset.outbox}/attempts?gateway=${opsGw()}`)).data;
    $("#attemptCard").hidden = false; $("#attemptTitle").textContent = `outbox ${tr.dataset.outbox}`;
    $("#opsAttempts").innerHTML = `<thead><tr><th>사이클</th><th>시도</th><th>worker</th><th>결과</th><th>HTTP</th><th>오류 코드</th><th>지연</th></tr></thead><tbody>` +
      rows.map((a) => `<tr><td class="num">${a.cycleNo}</td><td class="num">${a.attemptNo}</td><td class="mono">${esc(a.workerId)}</td><td class="mono">${esc(a.outcome)}</td><td class="num">${a.httpStatus ?? "-"}</td><td class="mono">${esc(a.errorCode || "-")}</td><td class="num">${a.latencyMs == null ? "-" : a.latencyMs + "ms"}</td></tr>`).join("") + "</tbody>";
  } catch (e) { toast(e.message, true); }
});

/* ------------------------------------------------------------------ 로그 */
async function loadLog() {
  const r = await api(`logs/${state.logSel}?lines=200`).catch((e) => ({ text: e.message }));
  const el = $("#svcLog"); const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
  el.textContent = r.text || "(로그 없음)"; if (atBottom) el.scrollTop = el.scrollHeight;
}
$("#logSel").addEventListener("click", (ev) => {
  const b = ev.target.closest("button"); if (!b) return;
  state.logSel = b.dataset.log; $$("#logSel button").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
  $("#svcLog").scrollTop = 1e9; loadLog();
});

/* ------------------------------------------------------------------ 결함 추적 */
const pct = (v) => (v == null ? "-" : (v * 100).toFixed(1) + "%");
const ms = (v) => (v ? Date.parse(v) : NaN);
const TRACE_FIELDS = [["faultVehicles", "고장 차량"], ["diagnosedVehicles", "진단 차량"], ["installedVehicles", "장착 차량"]];
const BUCKETS = [["unknownLot", "LOT 미확인"], ["noHistory", "이력 없음"], ["ambiguous", "이력 모호"]];

function renderDefectLots() {
  const n = Math.max(1, Math.min(20, Number($("#fleetForm [name=lots]").value) || 1));
  const sel = $("#fleetDefectLot"); const prev = sel.value || "L-2403";
  const lots = Array.from({ length: n }, (_, i) => `L-24${String(i + 1).padStart(2, "0")}`);
  sel.innerHTML = lots.map((l) => `<option${l === prev ? " selected" : ""}>${l}</option>`).join("");
  if (!lots.includes(prev)) sel.value = lots[Math.min(2, n - 1)];
}
$("#fleetForm [name=lots]").addEventListener("input", renderDefectLots);
$("#btnFleetStop").addEventListener("click", () => api("jobs/stop", { method: "POST" }).catch((e) => toast(e.message, true)));
$("#fleetForm [name=reset]").addEventListener("change", (ev) => ($("#fleetResetHint").hidden = ev.target.checked));
$("#fleetForm").addEventListener("submit", (ev) => {
  ev.preventDefault();
  const params = Object.fromEntries(new FormData(ev.target).entries());
  params.reset = ev.target.reset.checked;
  if (params.reset && !confirm("로컬 gateway·receiver DB를 초기화한 뒤 차량군을 전송합니다.")) return;
  state.fleetJobId = null;
  startJob({ type: "fleet", params });
});

function renderFleetJob(j) {
  const st = $("#fleetState");
  const stopped = !!(j.result?.fleet?.simulation?.stopped || j.result?.fleet?.production?.stopped);
  st.textContent = (stopped ? "중단" : { running: "전송 중", done: "완료", failed: "실패" }[j.state]) + ` · ${j.elapsedSec}s`;
  st.className = "tag " + (j.state === "running" ? "run" : j.state === "done" ? "good" : stopped ? "warn" : "bad");
  const p = j.progress; const bar = $("#fleetProgress");
  bar.hidden = !(j.state === "running" && p && p.total);
  if (p && p.total) $("div", bar).style.width = (p.done / p.total) * 100 + "%";
  $("#btnFleet").disabled = j.state === "running";
  $("#btnFleetStop").disabled = j.state !== "running";
  if (j.state === "running" || state.fleetJobId === j.id) return;
  state.fleetJobId = j.id;
  if (j.result?.fleet) {
    const f = j.result.fleet;
    $("#fleetSummary").innerHTML = fleetSummaryHtml(f);
    state.fleet = f;
    if (!f.stale) { fillWindow(); runTrace(); }
    else if (state.trace) renderTrace();
  } else if (j.error) {
    $("#fleetSummary").innerHTML = `<ul class="checks"><li class="fail"><span class="mark">✘</span><span>${esc(j.error)}</span></li></ul>`;
  }
}

function fleetSummaryHtml(f) {
  const prod = f.production || {}; const sim = f.simulation;
  const exp = f.stale ? {} : f.expected?.totals || {};
  const kinds = sim ? Object.entries(sim.byKind).map(([k, v]) => `${k} ${Object.entries(v).map(([s, n]) => `${s}×${fmt(n)}`).join(" ")}`).join(" · ") : "-";
  const kv = [["생산 이력 행", prod.rows], ["batch", prod.batches], ["inserted", prod.inserted], ["updated", prod.updated],
    ["진단 저장 기대", sim?.expectedResults], ["정답 고장 차량", exp.faultVehicles], ["정답 진단 차량", exp.diagnosedVehicles]]
    .map(([k, v]) => `<div><span>${k}</span><b>${fmt(v)}</b></div>`).join("");
  const warn = f.reset === false ? `<p class="hint">⚠ 초기화 없이 실행했습니다. 이전 데이터가 있으면 정답 비교가 맞지 않습니다.</p>` : "";
  const fail = !prod.ok ? `<ul class="checks"><li class="fail"><span class="mark">✘</span><span>생산 이력 실패 batch ${esc(prod.failedBatch)} · HTTP ${esc(prod.status)}<br><span class="d">${esc(JSON.stringify(prod.body))}</span></span></li></ul>` : "";
  const stale = f.stale ? `<p class="hint">⚠ 정답 비교 안 함: ${esc(f.stale)}</p>` : "";
  return `<div class="kv sub">${kv}</div>${fail}${stale}
    <p class="hint">run ${esc(f.runId)} · 진단 응답 ${esc(kinds)} · 기대와 다른 응답 ${fmt(sim?.unexpectedCount ?? 0)}건 · 기간 ${esc(f.window?.from)} ~ ${esc(f.window?.to)}</p>${warn}`;
}

async function loadLastFleet() {
  try {
    const f = await api("fleet/last");
    const first = !state.fleet && f;
    state.fleet = f;
    if (first) { fillWindow(); if (!state.trace) $("#fleetSummary").innerHTML = fleetSummaryHtml(f); }
    if (state.trace) renderTrace();
  } catch (e) { /* 서버 재시작 중 */ }
}
function fillWindow() {
  if (!state.fleet) return toast("전송한 차량군이 없습니다", true);
  const f = $("#traceForm");
  f.ecuId.value = state.fleet.expected.ecuId; f.dtcCode.value = state.fleet.expected.dtcCode;
  f.from.value = state.fleet.window.from; f.to.value = state.fleet.window.to;
}
$("#btnTraceFill").addEventListener("click", fillWindow);
$("#traceForm").addEventListener("submit", (ev) => { ev.preventDefault(); runTrace(); });

async function runTrace() {
  const form = $("#traceForm");
  for (const k of ["ecuId", "dtcCode"]) form[k].value = form[k].value.trim().toUpperCase();
  const q = Object.fromEntries([...new FormData(form).entries()].filter(([, v]) => v.trim()).map(([k, v]) => [k, v.trim()]));
  $("#traceResult").innerHTML = '<p class="empty">조회 중…</p>';
  try {
    const data = (await api("trace/lots?" + new URLSearchParams(q))).data;
    state.trace = data;
    renderTrace();
    if (state.fwdLot && data.lots.some((l) => l.lotNo === state.fwdLot)) runForward(state.fwdLot);
    else { state.fwdLot = null; $("#forwardCard").hidden = true; }
  } catch (e) {
    state.trace = null; state.fwdLot = null; $("#forwardCard").hidden = true;
    $("#traceResult").innerHTML = `<p class="empty">${esc(apiErrorText(e))}</p>`;
    setTag($("#traceMatch"), "-", "");
  }
}

/** 정답 비교 기준: 마지막 차량군과 ECU·DTC·기간(시각 값)이 같고, 이후 DB가 바뀌지 않았을 때. */
function comparable(data) {
  const f = state.fleet;
  if (!f) return { ok: false, why: "정답 없음 · 차량군 미전송" };
  if (f.stale) return { ok: false, why: "정답 없음 · " + f.stale };
  if (!data) return { ok: true };
  if (data.ecuId !== f.expected.ecuId || data.dtcCode !== f.expected.dtcCode) return { ok: false, why: "정답 없음 · 조건 다름" };
  if (ms(data.from) !== ms(f.window.from) || ms(data.to) !== ms(f.window.to)) return { ok: false, why: "정답 없음 · 기간 다름" };
  return { ok: true };
}
/** gateway 검증 오류는 필드별 메시지를, 그 밖에는 제목·오류 문구를 보여준다. */
function apiErrorText(e) {
  const errors = e.body?.errors;
  if (errors?.length) return errors.map((x) => (x.field ? `${x.field}: ` : "") + x.message).join(" · ");
  return e.message;
}
function setTag(el, text, cls) { el.textContent = text; el.className = "tag " + cls; }

function renderTrace() {
  const d = state.trace; if (!d) return;
  const cmp = comparable(d); const exp = cmp.ok ? state.fleet.expected : null;
  const expLots = Object.fromEntries((exp?.lots || []).map((l) => [l.lotNo, l]));
  const lotNos = [...d.lots.map((l) => l.lotNo), ...Object.keys(expLots).filter((n) => !d.lots.some((l) => l.lotNo === n))];
  let matched = 0;
  const maxRate = Math.max(0.0001, ...d.lots.map((l) => l.faultRate));
  const rows = lotNos.map((lotNo) => {
    const a = d.lots.find((l) => l.lotNo === lotNo); const e = expLots[lotNo];
    const same = exp && a && e && TRACE_FIELDS.every(([k]) => a[k] === e[k]);
    if (same) matched++;
    const cell = (k) => {
      const v = a?.[k]; const diff = exp && (!e || v !== e[k]);
      return `<td class="num${diff ? " diff" : ""}">${fmt(v)}${diff ? `<span class="exp">정답 ${fmt(e?.[k])}</span>` : ""}</td>`;
    };
    const mark = exp ? `<td><span class="verdict ${same ? "PASS" : "FAIL"}">${same ? "✔" : "✘"}</span></td>` : "";
    return `<tr class="click${state.fwdLot === lotNo ? " sel" : ""}" data-lot="${esc(lotNo)}"><td class="mono">${esc(lotNo)}</td>
      ${TRACE_FIELDS.map(([k]) => cell(k)).join("")}
      <td class="num"><span class="rate" style="width:${a ? Math.round((a.faultRate / maxRate) * 60) : 0}px"></span>${a ? pct(a.faultRate) : "-"}</td>
      <td class="num">${a ? pct(a.otherLotsFaultRate) : "-"}</td>
      <td>${a?.lowSample ? `<span class="tag warn">표본 부족</span>` : ""}</td>${mark}</tr>`;
  }).join("");
  const sameUn = exp && BUCKETS.every(([k]) => ["faultVehicles", "diagnosedVehicles"].every((f) => d.unattributed[k][f] === exp.unattributed[k][f]));
  const sameTot = exp && d.totals.faultVehicles === exp.totals.faultVehicles && d.totals.diagnosedVehicles === exp.totals.diagnosedVehicles;
  const pair = (v, e) => `<b>${fmt(v.faultVehicles)} / ${fmt(v.diagnosedVehicles)}</b>` +
    (e && (v.faultVehicles !== e.faultVehicles || v.diagnosedVehicles !== e.diagnosedVehicles) ? `<span class="exp">정답 ${fmt(e.faultVehicles)} / ${fmt(e.diagnosedVehicles)}</span>` : "");
  const kv = [...BUCKETS.map(([k, l]) => [l, pair(d.unattributed[k], exp?.unattributed[k])]), ["합계", pair(d.totals, exp?.totals)]]
    .map(([k, v]) => `<div><span>${k} · 고장 / 진단</span>${v}</div>`).join("");
  $("#traceResult").innerHTML = `<div class="table-wrap"><table class="grid"><thead><tr><th>LOT</th>
      ${TRACE_FIELDS.map(([, l]) => `<th class="num">${l}</th>`).join("")}<th class="num">고장률</th><th class="num">다른 LOT 고장률</th><th></th>${exp ? "<th>정답</th>" : ""}</tr></thead>
      <tbody>${rows || `<tr><td colspan="8" class="empty">조건에 맞는 진단 차량이 없습니다.</td></tr>`}</tbody></table></div>
    <div class="kv sub">${kv}</div>
    <ul class="notes">${(d.notes || []).map((n) => `<li>${esc(n)}</li>`).join("")}<li>표본 부족: 진단 차량 ${fmt(d.minSample)}대 미만 · 기간 ${esc(d.from)} ~ ${esc(d.to)} · LOT 행을 누르면 순추적</li></ul>`;
  if (!exp) setTag($("#traceMatch"), cmp.why, "");
  else {
    const all = matched === lotNos.length && sameUn && sameTot;
    setTag($("#traceMatch"), `정답 ${all ? "일치" : "불일치"} · LOT ${matched}/${lotNos.length} · 미귀속 ${sameUn ? "✔" : "✘"} · 합계 ${sameTot ? "✔" : "✘"}`, all ? "good" : "bad");
  }
}
$("#traceResult").addEventListener("click", (ev) => { const tr = ev.target.closest("[data-lot]"); if (tr) runForward(tr.dataset.lot); });

async function runForward(lotNo) {
  state.fwdLot = lotNo;
  $$("#traceResult [data-lot]").forEach((tr) => tr.classList.toggle("sel", tr.dataset.lot === lotNo));
  const ecuId = state.trace?.ecuId || $("#traceForm").ecuId.value;
  $("#forwardCard").hidden = false; $("#forwardTitle").textContent = `${lotNo} · ${ecuId} 현재 장착 차량`;
  $("#forwardResult").innerHTML = '<p class="empty">조회 중…</p>';
  try {
    const d = (await api(`trace/lots/${encodeURIComponent(lotNo)}/vehicles?` + new URLSearchParams({ ecuId, limit: 200 }))).data;
    const cmp = comparable(null); const e = cmp.ok && ecuId === state.fleet.expected.ecuId ? state.fleet.expected.forward?.[lotNo] : null;
    const dtc = state.trace?.dtcCode;
    const val = (k, l) => { const diff = e && d[k] !== e[k]; return `<div><span>${l}</span><b>${fmt(d[k])}</b>${diff ? `<span class="exp">정답 ${fmt(e[k])}</span>` : ""}</div>`; };
    const rows = d.vehicles.map((v) => `<tr><td class="mono">${esc(v.vin)}</td><td class="mono">${esc(v.serialNo)}</td>
      <td class="mono">${esc((v.installedAt || "").replace("T", " ").slice(0, 19))}</td><td class="mono">${esc((v.latestDiagAt || "-").replace("T", " ").slice(0, 19))}</td>
      <td class="mono">${(v.latestDtcCodes || []).map((c) => (c === dtc ? `<b>${esc(c)}</b>` : esc(c))).join(" ") || "-"}</td></tr>`).join("");
    $("#forwardResult").innerHTML = `<div class="kv">${val("currentVehicles", "현재 장착 차량")}${val("excludedRemoved", "탈거 제외")}</div>
      <div class="table-wrap" style="margin-top:12px"><table class="grid"><thead><tr><th>VIN</th><th>시리얼</th><th>장착 시각 (UTC)</th><th>최근 진단 (UTC)</th><th>최근 DTC</th></tr></thead>
      <tbody>${rows || '<tr><td colspan="5" class="empty">현재 장착 차량이 없습니다.</td></tr>'}</tbody></table></div>
      ${d.truncated ? `<p class="hint">상위 ${fmt(d.vehicles.length)}대만 표시합니다.</p>` : ""}`;
    if (!e) setTag($("#forwardMatch"), cmp.ok ? "정답 없음" : cmp.why, "");
    else {
      const same = d.currentVehicles === e.currentVehicles && d.excludedRemoved === e.excludedRemoved;
      setTag($("#forwardMatch"), `정답 ${same ? "일치" : "불일치"}`, same ? "good" : "bad");
    }
  } catch (err) {
    $("#forwardResult").innerHTML = `<p class="empty">${esc(apiErrorText(err))}</p>`;
    setTag($("#forwardMatch"), "-", "");
  }
}

function t1TraceHtml(t) {
  const rows = t.lots.map((l) => {
    const same = l.expected && TRACE_FIELDS.every(([k]) => l.api[k] === l.expected[k]);
    return `<tr><td class="mono">${esc(l.lotNo)}</td>${TRACE_FIELDS.map(([k]) => `<td class="num${same ? "" : " diff"}">${fmt(l.api[k])}<span class="exp">정답 ${fmt(l.expected?.[k])}</span></td>`).join("")}
      <td><span class="verdict ${same ? "PASS" : "FAIL"}">${same ? "✔" : "✘"}</span></td></tr>`;
  }).join("");
  const tot = (v) => `${fmt(v.faultVehicles)} / ${fmt(v.diagnosedVehicles)}`;
  return `<div class="table-wrap" style="margin-top:10px"><table class="grid"><thead><tr><th>LOT</th>${TRACE_FIELDS.map(([, l]) => `<th class="num">${l}</th>`).join("")}<th>정답</th></tr></thead><tbody>${rows}</tbody></table></div>
    <p class="hint">결함 LOT 1위 ${esc(t.topLot)} · 합계 API ${tot(t.totals.api)} / 정답 ${tot(t.totals.expected)} · 기간 ${esc(t.window.from)} ~ ${esc(t.window.to)}</p>`;
}

/* ------------------------------------------------------------------ 탭 */
function selectTab(name) {
  state.tab = name;
  $$(".tabs [role=tab]").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.tab === name)));
  $$(".tab").forEach((t) => (t.hidden = t.id !== "tab-" + name));
  if (name === "ops") loadOps();
  if (name === "logs") loadLog();
  if (name === "history") loadHistory();
  if (name === "trace") loadLastFleet();
}
$(".tabs").addEventListener("click", (ev) => { const b = ev.target.closest("[data-tab]"); if (b) selectTab(b.dataset.tab); });

/* ------------------------------------------------------------------ 주기 갱신 */
async function tick() {
  try {
    const s = await api("state");
    state.services = s.services; state.fault = s.fault; state.canPause = s.canPause;
    if (s.job && (s.job.state === "running" || state.jobId == null)) {
      if (s.job.state === "running" && !jobPolling) pollJob();
      if (state.jobId == null) { state.jobId = s.job.id; pollJob(); }
    }
    if (s.job) state.job = { ...state.job, ...s.job };
    renderPills(); renderServices(); renderFault();
  } catch (e) { /* 서버 재시작 중 */ }
  try { renderCounts(await api("counts")); } catch (e) { /* ignore */ }
  if (state.tab === "logs") loadLog();
  if (state.tab === "ops" && !document.hidden && ++opsTick % 4 === 0) loadOps();
}
let opsTick = 0;

loadScenarios().catch((e) => toast(e.message, true));
renderDefectLots();
loadLastFleet();
loadHistory();
tick();
setInterval(tick, 1500);
