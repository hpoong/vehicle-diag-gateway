"use strict";
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmt = (n) => (n == null ? "-" : Number(n).toLocaleString("ko-KR"));
const pct = (v) => (v == null ? "-" : Math.round(v * 1000) / 10 + "%");
const day = (v) => (v ? String(v).slice(0, 10) : "-");
const time = (v) => {
  const d = v ? new Date(v) : null;
  if (!d || isNaN(d)) return "-";
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
};
const LOTS = ["L-2401", "L-2402", "L-2403", "L-2404", "L-2405", "L-2406"];
const FIELDS = ["faultVehicles", "diagnosedVehicles", "installedVehicles"];

const state = { run: null, setup: null, trace: null, sending: false, lot: null, fwd: null, showAll: false, db: null, dbTab: "parts", ready: null };

/* ------------------------------------------------------------ 용어 */
const TERMS = {
  lot: ["LOT (로트)", "같은 시기·같은 라인에서 함께 만든 부품 묶음입니다. 같은 LOT 부품은 같은 원인으로 함께 불량일 수 있습니다."],
  dtc: ["고장코드 (DTC)", "차량 제어기가 이상을 감지하면 남기는 표준 코드입니다. 예: P0700"],
  p0700: ["P0700", "변속기 제어 시스템에 이상이 있다는 고장코드입니다. 이 데모에서 '고장'의 기준입니다."],
  tcm: ["TCM (변속기 제어기)", "Transmission Control Module. 이 데모에서 LOT를 추적하는 부품입니다."],
  vin: ["VIN (차대번호)", "차마다 하나뿐인 17자리 식별번호입니다."],
  diag: ["진단 결과", "차량이 보낸 점검 결과 1건입니다. 언제, 어떤 제어기에서, 어떤 고장코드가 나왔는지 담깁니다."],
  resend: ["재전송", "통신이 불안정해 이미 보낸 같은 결과를 다시 보내는 것입니다. 같은 결과는 한 번만 저장합니다."],
  parts: ["생산 이력", "공장 시스템이 알려 주는 기록입니다. 어느 차(VIN)에 어느 LOT 부품을 언제 달았고 언제 뗐는지 담깁니다."],
  gateway: ["수집 서버 (gateway)", "차량 진단 결과와 생산 이력을 받아 저장하고, 원인 LOT·점검 대상 차량을 계산하는 서버입니다."],
  receiver: ["품질 시스템 (receiver)", "수집 서버가 진단 결과를 넘겨주는 받는 쪽 시스템입니다. 추적 계산에는 쓰이지 않습니다."],
  db: ["DB (PostgreSQL)", "두 서비스가 데이터를 저장하는 데이터베이스입니다. 수집 서버와 품질 시스템은 DB를 따로 씁니다."],
  reverse: ["역추적", "고장 → 원인 LOT. 고장 난 차들에 공통으로 달린 LOT를 찾습니다."],
  forward: ["순추적", "LOT → 차량. 그 LOT 부품이 지금 달려 있는 차를 모두 찾습니다."],
  rate: ["고장 비율", "그 LOT가 달린 차 가운데, 기간 안에 진단받은 차 대비 고장코드가 나온 차의 비율입니다."],
};
$("#glossary").innerHTML = Object.values(TERMS).map(([t, d]) => `<div><dt>${esc(t)}</dt><dd>${esc(d)}</dd></div>`).join("");

const pop = $("#pop"); let popFor = null;
function showPop(el) {
  const def = TERMS[el.dataset.t]; if (!def) return;
  pop.innerHTML = `<b>${esc(def[0])}</b>${esc(def[1])}`; pop.hidden = false; popFor = el;
  const r = el.getBoundingClientRect(), w = Math.min(300, innerWidth - 24);
  pop.style.left = Math.max(12, Math.min(r.left + scrollX, scrollX + innerWidth - w - 12)) + "px";
  pop.style.top = r.bottom + scrollY + 8 + "px";
}
const hidePop = () => { pop.hidden = true; popFor = null; };
function initTerms(root = document) {
  $$(".term", root).forEach((el) => { if (!el.hasAttribute("tabindex")) el.setAttribute("tabindex", "0"); });
}
document.addEventListener("mouseover", (ev) => { const t = ev.target.closest(".term"); if (t) showPop(t); });
document.addEventListener("mouseout", (ev) => { const t = ev.target.closest(".term"); if (t && !t.contains(ev.relatedTarget)) hidePop(); });
document.addEventListener("focusin", (ev) => { const t = ev.target.closest(".term"); if (t) showPop(t); });
document.addEventListener("focusout", (ev) => { if (ev.target.closest(".term")) hidePop(); });
document.addEventListener("click", (ev) => { const t = ev.target.closest(".term"); if (t) { popFor === t ? hidePop() : showPop(t); } else if (!pop.hidden) hidePop(); });
initTerms();

/* ------------------------------------------------------------ 공통 */
async function api(path, body) {
  const init = body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
  const res = await fetch("/api/" + path, init);
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);
  return data;
}
let toastTimer;
function toast(msg, err = false) {
  const t = $("#toast"); t.textContent = msg; t.className = "toast" + (err ? " err" : ""); t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), err ? 7000 : 2500);
}
async function busy(btn, label, fn) {
  const prev = btn.textContent; btn.disabled = true; btn.textContent = label;
  try { return await fn(); } catch (e) { toast(e.message, true); } finally { btn.disabled = false; btn.textContent = prev; refreshStatus(); loadDb(); }
}
const show = (id) => { $(id).hidden = false; };
const scrollTo = (id) => $(id).scrollIntoView({ behavior: "smooth", block: "start" });

/* ------------------------------------------------------------ 서비스 상태 */
const STATE_KO = { up: "켜짐", external: "켜짐", down: "꺼짐", starting: "켜는 중", paused: "멈춤" };
const SVC = [["postgres", "db", "DB", "postgres"], ["gateway", "gateway", "수집 서버", "gateway"], ["receiver", "receiver", "품질 시스템", "receiver"]];
async function refreshStatus() {
  try {
    const r = await api("ready"); state.ready = r;
    $("#svc").innerHTML = SVC.map(([k, t, label, tech]) => {
      const st = r[k] === "external" ? "up" : r[k];
      return `<span class="pill term" data-t="${t}" tabindex="0"><span class="dot ${esc(st)}"></span>${label} <small>${tech}</small> ${STATE_KO[r[k]] || esc(r[k])}</span>`;
    }).join("");
    $("#btnPrepare").hidden = r.all;
    if ($("#btnRun").textContent === "데이터 보내기") $("#btnRun").disabled = !r.ok;
    $("#btnRun").title = r.ok ? "" : "DB와 수집 서버가 켜져 있어야 합니다";
  } catch (e) {
    $("#svc").innerHTML = `<span class="pill"><span class="dot down"></span>데모 서버 연결 실패</span>`;
  }
}
$("#btnPrepare").addEventListener("click", (ev) => busy(ev.target, "켜는 중… (최대 1분)", async () => {
  await api("prepare", {}); toast("서비스를 켰습니다");
}));

/* ------------------------------------------------------------ 1 → 2 → 3 */
$("#defectLot").insertAdjacentHTML("beforeend", LOTS.map((l) => `<option>${l}</option>`).join(""));
$("#setup").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const f = ev.target;
  const body = { vehicles: Number(f.vehicles.value), defectLot: f.defectLot.value, severity: f.severity.value, noise: f.noise.checked, reset: f.reset.checked };
  if (!body.reset && !confirm("DB를 비우지 않고 보냅니다. 이전 데이터가 섞여 기대값 비교가 맞지 않을 수 있습니다.")) return;
  const btn = $("#btnRun"); btn.disabled = true; btn.textContent = "보내는 중…";
  try {
    const r = await api("run", body);
    startLive(r.setup);
    scrollTo("#step2");
    poll();
  } catch (e) { toast(e.message, true); endSending(); }
});

/* 전송은 서버 스레드가 실제 gateway API로 한 건씩 보낸다. 화면은 1초마다 진행 상태와 gateway 역추적 결과를 다시 받는다. */
function startLive(setup) {
  state.run = null; state.setup = setup; state.trace = null; state.sending = true; state.lot = null; state.fwd = null;
  $("#btnRun").disabled = true; $("#btnRun").textContent = "보내는 중…";
  $("#step4").hidden = true; $("#answerBody").hidden = true; $("#btnReveal").hidden = true; $("#btnToForward").hidden = true;
  $("#btnRestart").hidden = true; $("#bars").innerHTML = ""; $("#waffles").innerHTML = ""; $("#lotTable").innerHTML = ""; $("#unattributed").innerHTML = "";
  $("#lowNote").hidden = true;
  $("#headline").textContent = "생산 이력을 보내는 중입니다…";
  $("#liveBadge").hidden = false;
  show("#step2"); show("#step3");
}
function endSending() {
  state.sending = false; $("#liveBadge").hidden = true;
  const btn = $("#btnRun"); btn.textContent = "데이터 보내기"; btn.disabled = !(state.ready && state.ready.ok);
}
async function poll() {
  let p;
  try { p = await api("progress"); }
  catch (e) { toast(e.message, true); setTimeout(poll, 2000); return; }
  if (p.state === "idle") { endSending(); return; }
  renderProgress(p);
  if (p.trace) renderTrace(p.trace, p.state !== "done");
  if (p.state === "done") {
    state.run = { setup: p.setup, trace: p.trace, answer: p.answer };
    endSending(); $("#btnReveal").hidden = false; $("#btnRestart").hidden = false; loadDb();
    toast(`전송 완료 · ${fmt(p.total)}건, ${p.elapsedSec}초`);
  } else if (p.state === "error") {
    endSending(); $("#sendStatus").textContent = "전송 실패: " + (p.error || ""); toast(p.error || "전송 실패", true);
  } else setTimeout(poll, 1000);
}

function renderProgress(p) {
  $("#partRows").textContent = p.state === "parts" ? "…" : fmt(p.partRows);
  $("#diagSent").textContent = fmt(p.done);
  $("#diagResent").textContent = fmt(p.resent);
  $("#diagStored").textContent = fmt(p.diagStored);
  $("#vehiclesN").textContent = fmt(p.setup.vehicles);
  const ratio = p.total ? p.done / p.total : 0;
  const meter = $("#sendBar"); $("span", meter).style.width = ratio * 100 + "%";
  meter.classList.toggle("done", p.state === "done"); meter.setAttribute("aria-valuenow", Math.round(ratio * 100));
  $("#sendStatus").textContent = p.state === "parts" ? "생산 이력을 보내는 중입니다…"
    : p.state === "done" ? `전송 완료 · 진단 결과 ${fmt(p.total)}건을 ${p.elapsedSec}초 동안 보냈습니다.`
      : `진단 결과 보내는 중 ${fmt(p.done)} / ${fmt(p.total)}건 · 초당 약 ${fmt(p.setup.ratePerSec)}건${p.resent ? " · 지금은 재전송분" : ""}`;
}


/* 표본이 충분한 LOT 중 고장 비율 1위. 모두 표본 부족이면 없음 */
function topLot(t) { return [...t.lots].filter((l) => !l.lowSample).sort((a, b) => b.faultRate - a.faultRate)[0]; }

function renderTrace(t, live = false) {
  state.trace = t;
  const top = topLot(t);
  const go = $("#btnToForward");
  go.hidden = live || !top;
  const lots = [...t.lots].sort((a, b) => a.lotNo.localeCompare(b.lotNo));
  if (!lots.length) {
    $("#headline").textContent = live ? "진단 결과가 들어오기를 기다리는 중입니다…" : "기간 안에 진단받은 차량이 없습니다.";
    $("#bars").innerHTML = ""; $("#waffles").innerHTML = ""; $("#lowNote").hidden = true; return;
  }
  if (!top) {
    $("#headline").innerHTML = `아직 LOT마다 진단받은 차가 <b>${fmt(t.minSample)}대</b>가 안 됩니다(표본 부족). 데이터가 더 쌓이면 원인 후보가 드러납니다.`;
  } else {
    const times = top.otherLotsFaultRate > 0 ? Math.round(top.faultRate / top.otherLotsFaultRate) : null;
    $("#headline").innerHTML = `${live ? '<span class="muted">지금까지 쌓인 데이터 기준</span> ' : ""}<b>${esc(top.lotNo)}</b> 부품이 달린 차는 <b>${pct(top.faultRate)}</b>가 고장 났습니다.
      다른 LOT는 평균 <b>${pct(top.otherLotsFaultRate)}</b>${times && times > 1 ? ` · 약 <b>${times}배</b>` : ""}. ${live ? "현재 1위입니다." : "이 LOT가 원인 후보입니다."}`;
    go.dataset.lot = top.lotNo; go.textContent = `${top.lotNo} 장착 차량 찾기 →`;
  }

  const max = Math.max(...lots.map((l) => l.faultRate), 0.0001);
  const box = $("#bars");
  if ($$(".bar", box).map((b) => b.dataset.lot).join() !== lots.map((l) => l.lotNo).join()) {
    box.innerHTML = lots.map((l) => `<div class="bar" role="listitem" tabindex="0" data-lot="${esc(l.lotNo)}">
      <span class="lot">${esc(l.lotNo)}</span><span class="track"><span class="fill"></span></span><span class="val"></span></div>`).join("");
  }
  for (const l of lots) {  // 막대를 다시 만들지 않고 값만 바꿔 길이 변화가 부드럽게 보이게 한다
    const b = $(`.bar[data-lot="${CSS.escape(l.lotNo)}"]`, box);
    b.classList.toggle("top", !!top && l.lotNo === top.lotNo);
    b.classList.toggle("sel", l.lotNo === state.lot);
    b.classList.toggle("low", l.lowSample);
    $(".fill", b).style.width = (l.faultRate / max) * 100 + "%";
    $(".val", b).textContent = `${pct(l.faultRate)} · ${fmt(l.faultVehicles)}/${fmt(l.diagnosedVehicles)}대`;
  }
  $("#lowNote").hidden = !lots.some((l) => l.lowSample);
  $("#lowNote").textContent = `흐린 막대는 진단받은 차가 ${fmt(t.minSample)}대 미만인 LOT(표본 부족)입니다. 원인 후보에서 뺍니다.`;
  if (!top) { $("#waffles").innerHTML = ""; return; }

  const cells = (n) => Array.from({ length: 100 }, (_, i) => `<i class="${i < n ? "on" : ""}"></i>`).join("");
  const a = Math.round(top.faultRate * 100), b = Math.round((top.otherLotsFaultRate || 0) * 100);
  $("#waffles").innerHTML = `
    <div class="waffle"><h4>${esc(top.lotNo)} 장착 차 100대 중 <b>${a}</b>대 고장</h4>
      <div class="cells" role="img" aria-label="100대 중 ${a}대 고장">${cells(a)}</div></div>
    <div class="waffle rest"><h4>다른 LOT 장착 차 100대 중 <b>${b}</b>대 고장</h4>
      <div class="cells" role="img" aria-label="100대 중 ${b}대 고장">${cells(b)}</div>
      <p>정상 부품이 달린 차도 일부는 고장이 납니다. 원인 후보는 이 차이가 뚜렷한 LOT입니다.</p></div>`;

  $("#lotTable").innerHTML = `<thead><tr><th>LOT</th><th class="num">고장 차량</th><th class="num">진단받은 차량</th><th class="num">고장 비율</th><th class="num">이 LOT가 달린 적 있는 차량</th></tr></thead><tbody>` +
    lots.map((l) => `<tr><td class="mono">${esc(l.lotNo)}</td><td class="num">${fmt(l.faultVehicles)}</td><td class="num">${fmt(l.diagnosedVehicles)}</td><td class="num">${pct(l.faultRate)}</td><td class="num">${fmt(l.installedVehicles)}</td></tr>`).join("") + "</tbody>";
  const u = t.unattributed;
  const outside = u.unknownLot.diagnosedVehicles + u.noHistory.diagnosedVehicles + u.ambiguous.diagnosedVehicles;
  const outsideFault = u.unknownLot.faultVehicles + u.noHistory.faultVehicles + u.ambiguous.faultVehicles;
  $("#unattributed").innerHTML = outside
    ? `LOT 기록이 없거나 확인되지 않는 차량 <b>${fmt(outside)}대</b>(그중 고장 ${fmt(outsideFault)}대)는 어느 LOT에도 억지로 넣지 않고 따로 셉니다.` : "";
}

/* 막대 hover 툴팁 + 클릭 → 순추적 */
const bars = $("#bars"), tip = $("#tip");
bars.addEventListener("mousemove", (ev) => {
  const b = ev.target.closest(".bar"); if (!b || !state.trace) { tip.hidden = true; return; }
  const l = state.trace.lots.find((x) => x.lotNo === b.dataset.lot); if (!l) return;
  tip.innerHTML = `<b>${esc(l.lotNo)}</b><br>고장 ${fmt(l.faultVehicles)}대 / 진단 ${fmt(l.diagnosedVehicles)}대 (${pct(l.faultRate)})<br>이 LOT가 달린 적 있는 차 ${fmt(l.installedVehicles)}대<br><span class="muted">${state.sending ? "전송이 끝나면 눌러서 차량 보기" : "누르면 그 LOT 차량 보기"}</span>`;
  const r = $("#bars").parentElement.getBoundingClientRect();
  tip.style.left = Math.max(0, Math.min(ev.clientX - r.left + 14, r.width - 230)) + "px"; tip.style.top = ev.clientY - r.top + 14 + "px"; tip.hidden = false;
});
bars.addEventListener("mouseleave", () => (tip.hidden = true));
bars.addEventListener("click", (ev) => { const b = ev.target.closest(".bar"); if (b) openForward(b.dataset.lot); });
bars.addEventListener("keydown", (ev) => { const b = ev.target.closest(".bar"); if (b && (ev.key === "Enter" || ev.key === " ")) { ev.preventDefault(); openForward(b.dataset.lot); } });
$("#btnToForward").addEventListener("click", (ev) => openForward(ev.target.dataset.lot));

/* 기대값 비교 */
$("#btnReveal").addEventListener("click", () => {
  const r = state.run; if (!r) return;
  const top = topLot(r.trace); const exp = Object.fromEntries(r.answer.expected.lots.map((l) => [l.lotNo, l]));
  const same = r.trace.lots.filter((l) => exp[l.lotNo] && FIELDS.every((k) => l[k] === exp[l.lotNo][k])).length;
  const allSame = same === r.answer.expected.lots.length && same === r.trace.lots.length;
  const found = top?.lotNo === r.answer.defectLot;
  $("#answerBody").innerHTML = `<p class="verdict ${found ? "ok" : "ng"}">기대값(결함을 심어 둔 LOT)은 <span class="mono">${esc(r.answer.defectLot)}</span> · 시스템이 찾은 1위 <span class="mono">${esc(top?.lotNo)}</span> ${found ? "✔ 일치" : "✘ 불일치"}</p>
    <p class="note">시뮬레이터가 보낸 데이터로 따로 계산한 기대값과 LOT별 고장·진단·장착 차량 수가 ${allSame ? `<b>${same}개 LOT 모두 같습니다</b> ✔` : `${same}개 LOT만 같습니다 ✘`}.
    ${!r.setup.reset ? "<b>DB를 비우지 않고 보내</b> 이전 데이터가 함께 집계됐습니다. 기대값과 다르면 이 때문입니다."
      : found ? "" : "결함 정도를 ‘약함’으로 두면 정상 LOT의 우연한 고장과 구분이 어려워질 수 있습니다."}</p>`;
  $("#answerBody").hidden = false; $("#btnReveal").hidden = true;
});

/* ------------------------------------------------------------ 4 순추적 */
async function openForward(lot) {
  if (state.sending || !state.run) { toast("전송이 끝난 뒤 볼 수 있습니다"); return; }
  state.lot = lot; state.showAll = false;
  $$(".bar").forEach((b) => b.classList.toggle("sel", b.dataset.lot === lot));
  try {
    state.fwd = await api("forward?lot=" + encodeURIComponent(lot));
    renderForward(); show("#step4"); scrollTo("#step4");
  } catch (e) { toast(e.message, true); }
}
function renderForward() {
  const { forward: f, expected } = state.fwd;
  const dtc = state.run.setup.dtcCode;
  const faulty = f.vehicles.filter((v) => (v.latestDtcCodes || []).includes(dtc)).length;
  const others = f.vehicles.length - faulty;
  const match = expected && expected.currentVehicles === f.currentVehicles && expected.excludedRemoved === f.excludedRemoved;
  $("#fwdHeadline").innerHTML = `지금 <b>${esc(f.lotNo)}</b> 부품이 달려 있는 차는 <b>${fmt(f.currentVehicles)}대</b>입니다. 이 차들이 점검 대상입니다.
    <span class="note">${f.excludedRemoved ? `이미 다른 부품으로 교체한 ${fmt(f.excludedRemoved)}대는 뺐습니다. ` : ""}${match ? "기대값과 일치 ✔" : ""}</span>`;
  const total = Math.max(1, f.vehicles.length);
  $("#split").innerHTML = `<span class="a" style="flex:${faulty}">${faulty ? fmt(faulty) : ""}</span><span class="b" style="flex:${others}">${others ? fmt(others) : ""}</span>`;
  $("#split").setAttribute("aria-label", `점검 대상 ${fmt(f.vehicles.length)}대 중 최근 ${dtc} ${faulty}대`);
  $("#splitLegend").innerHTML = `<span><i style="background:var(--hit)"></i>최근 진단에서 ${dtc} 나옴 ${fmt(faulty)}대 (${pct(faulty / total)})</span>
    <span><i style="background:var(--accent)"></i>아직 안 나옴 ${fmt(others)}대 → 고장 전에 미리 점검</span>`;
  const rows = state.showAll ? f.vehicles : f.vehicles.slice(0, 10);
  $("#fwdTable").innerHTML = `<thead><tr><th><span class="term" data-t="vin">차대번호(VIN)</span></th><th>부품 장착일</th><th>최근 진단일</th><th>최근 고장코드</th></tr></thead><tbody>` +
    rows.map((v) => `<tr><td class="mono">${esc(v.vin)}</td><td>${day(v.installedAt)}</td><td>${day(v.latestDiagAt)}</td>
      <td class="mono">${(v.latestDtcCodes || []).map((c) => (c === dtc ? `<b>${esc(c)}</b>` : esc(c))).join(" ") || "없음"}</td></tr>`).join("") + "</tbody>";
  initTerms($("#fwdTable"));
  $("#btnMore").hidden = state.showAll || f.vehicles.length <= 10;
  $("#btnMore").textContent = `전체 ${fmt(f.vehicles.length)}대 보기`;
}
$("#btnMore").addEventListener("click", () => { state.showAll = true; renderForward(); });

/* ------------------------------------------------------------ DB */
const DB_TABS = {
  parts: { note: "수집 서버 DB · vehicle_part · 최근 8행", head: ["VIN", "LOT", "부품 번호", "시리얼", "장착", "탈거"],
    row: (r) => [r.vin, r.lot_no ?? "(기록 없음)", r.part_no, r.serial_no, day(r.installed_at), r.removed_at ? day(r.removed_at) : "현재 장착"] },
  results: { note: "수집 서버 DB · diagnostic_result + ecu_observation(TCM) · 최근 8건", head: ["VIN", "단말 ID", "이벤트 ID", "진단 시각", "TCM 고장코드"],
    row: (r) => [r.vin, r.device_id, r.event_id, time(r.occurred_at), (r.dtc_codes || []).join(" ") || "없음"] },
  cases: { note: "품질 시스템 DB · quality_case · 최근 8건 (전달받은 고장코드 1개 = 1행)", head: ["VIN", "제어기", "고장코드", "진단 시각", "등록 시각"],
    row: (r) => [r.vin, r.ecu_id, r.dtc_code, time(r.occurred_at), time(r.created_at)] },
};
function renderDb() {
  const d = state.db; if (!d) return;
  const g = d.gateway, rc = d.receiver;
  const kv = (label, tech, v) => `<div><span>${label}</span><small>${tech}</small><b>${fmt(v)}</b></div>`;
  $("#dbGateway").innerHTML = kv("생산 이력", "vehicle_part", g.parts) + kv("LOT 종류", "lot_no", g.lots) + kv("진단 결과", "diagnostic_result", g.results)
    + kv("진단받은 차량", "vin", g.vehicles) + kv("품질 시스템 전달 대기", "outbox PENDING", g.pending) + kv("전달 완료", "outbox SENT", g.sent);
  $("#dbReceiver").innerHTML = kv("받은 진단 결과", "received_event", rc.received) + kv("등록된 고장", "quality_case", rc.cases);
  const tab = DB_TABS[state.dbTab]; const rows = d.samples[state.dbTab];
  $("#dbTabNote").textContent = tab.note;
  $("#dbTable").innerHTML = `<thead><tr>${tab.head.map((h) => `<th>${h}</th>`).join("")}</tr></thead><tbody>` +
    (rows.length ? rows.map((r) => `<tr>${tab.row(r).map((v, i) => `<td class="${i < 3 ? "mono" : ""}">${esc(v)}</td>`).join("")}</tr>`).join("")
      : `<tr><td colspan="${tab.head.length}" class="note">비어 있습니다.</td></tr>`) + "</tbody>";
}
async function loadDb() {
  try { state.db = await api("db"); renderDb(); }
  catch (e) { $("#dbGateway").innerHTML = `<p class="note">DB를 읽을 수 없습니다: ${esc(e.message)}</p>`; $("#dbReceiver").innerHTML = ""; }
}
$("#dbTabs").addEventListener("click", (ev) => {
  const b = ev.target.closest("[data-k]"); if (!b) return;
  state.dbTab = b.dataset.k; $$("#dbTabs button").forEach((x) => x.setAttribute("aria-selected", String(x === b))); renderDb();
});
$("#btnDbRefresh").addEventListener("click", loadDb);
$("#btnReset").addEventListener("click", (ev) => {
  if (!confirm("수집 서버·품질 시스템 DB의 데이터를 모두 지웁니다. (표 구조는 유지)")) return;
  busy(ev.target, "비우는 중…", async () => {
    await api("reset", {}); resetSteps(); toast("DB를 비웠습니다");
  });
});

function resetSteps() {
  for (const id of ["#step2", "#step3", "#step4"]) $(id).hidden = true;
  $("#btnRestart").hidden = true; state.run = null; state.setup = null; state.trace = null;
}
$("#btnRestart").addEventListener("click", () => { resetSteps(); window.scrollTo({ top: 0, behavior: "smooth" }); });

refreshStatus(); loadDb();
/* 전송 중에 새로고침했으면 이어서 보여 준다 */
api("progress").then((p) => { if (p.state === "parts" || p.state === "diag") { startLive(p.setup); poll(); } }).catch(() => {});
setInterval(refreshStatus, 4000);
setInterval(() => { if (!document.hidden) loadDb(); }, 2000);
