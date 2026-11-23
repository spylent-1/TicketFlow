const by = (selector) => document.querySelector(selector);
const detail = by("#detail");
const compose = by("#compose");
const create = by("#create");
const states = {
  INVESTIGATING: "待继续调查", AWAITING_APPROVAL: "待审批", APPROVED: "待继续执行",
  DECLINED: "待结束", RESOLVED: "已处理", MANUAL: "待人工核查", REJECTED: "已拒绝",
};
const reasons = {
  MISSING: "订单已付款并超过 15 分钟。台账缺少报表导出权益，提请审批补发。",
  SYNC_PENDING: "订单已付款，仍在 15 分钟同步窗口内。建议等待后刷新。",
  UNPAID: "订单未完成付款，请核对支付记录或引导客户完成付款。",
  ORDER_UNKNOWN: "未查到绑定订单，请人工核对订单编号和付款凭证。",
  PLAN_EXCLUDED: "基础版套餐仅支持在线预览，报表导出需要专业版。",
  ALREADY_GRANTED: "权益台账已存在报表导出权限。请核对账号、角色或刷新会话。",
  INSUFFICIENT_EVIDENCE: "调查缺少必要事实或适用条款，请人工补充核查。",
  BUDGET: "调查达到轮次或时间上限，请人工核查已采集的事实。",
  TOOL_ERROR: "工具暂未取得结果，请人工核查时间线。",
  MODEL_ERROR: "模型调用失败或参数无效，请人工处理。",
  INVALID_DECISION: "模型未给出单一有效的工具选择，请人工处理。",
  REJECTED: "审批员已拒绝此补发申请。",
  GRANTED: "报表导出权益已写入台账。",
  OPERATION_UNCERTAIN: "暂无法确认操作结果，请用工单运行 ID 核对台账。",
};
const steps = {
  read_order: "核对订单与支付", read_entitlement: "查询权益台账",
  search_knowledge: "检索知识条款", conclude: "得出调查结论",
  approval: "接收人工审批决定", grant: "补发响应异常",
  operation_lookup: "按运行 ID 查询操作结果", execution: "确认补发执行结果",
};
let session;
let rows = [];
let active = null;
let busy = false;

async function api(path, data) {
  const response = await fetch(path, data === undefined ? {} : {
    method: "POST",
    headers: {"Content-Type": "application/json", [session.csrfHeader]: session.csrf},
    body: JSON.stringify(data),
  });
  if (response.redirected && new URL(response.url).pathname === "/login") {
    location.assign("/login");
    throw new Error("登录已过期");
  }
  if (!response.ok) {
    const failure = await response.json().catch(() => ({}));
    throw new Error(failure.message || (response.status === 403 ? "当前账号无权进行此操作" : "请求失败"));
  }
  return response.json();
}

function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text != null) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function pill(status) {
  const badge = node("span", states[status] || status, "pill");
  badge.dataset.state = status;
  return badge;
}

function disclosure(title, content) {
  const block = node("details");
  block.append(node("summary", title), content);
  return block;
}

function notice(message) {
  by("#notice").textContent = message || "";
  by("#notice").hidden = !message;
}

async function action(button, task) {
  if (busy) return;
  busy = true;
  button.disabled = true;
  notice();
  try {
    await task();
  } catch (error) {
    notice(error.message);
    if (compose.open) compose.close();
  } finally {
    busy = false;
    button.disabled = false;
    detail.setAttribute("aria-busy", "false");
  }
}

function queue() {
  const filter = by("#filter").value;
  const visible = rows.filter(row => !filter || row.status === filter);
  by("#count").textContent = visible.length;
  by("#tickets").replaceChildren();
  if (!visible.length) by("#tickets").append(node("p", "此队列暂无工单", "hint"));
  for (const row of visible) {
    const button = node("button", null, "ticket");
    button.setAttribute("aria-current", String(row.id === active));
    const meta = node("small");
    meta.append(node("span", row.order_id), pill(row.status));
    button.append(node("strong", row.description), meta);
    button.addEventListener("click", () => action(button, async () => show(await api("/tickets/" + row.id))));
    by("#tickets").append(button);
  }
}

function show(ticket) {
  active = ticket.id;
  history.replaceState(null, "", "/?ticket=" + active);
  queue();
  const head = node("header", null, "detail-head");
  head.append(node("h2", ticket.description), pill(ticket.status));
  detail.replaceChildren(head, node("p", ticket.order_id + " · 运行 ID " + ticket.id, "meta"));
  const facts = ticket.investigation;
  if (facts) {
    const summary = node("section", null, "conclusion");
    summary.append(node("h3", "当前处置"), node("p", reasons[facts.reason] || facts.reason));
    const telemetry = ["模型 " + facts.model,
      facts.promptVersion, facts.durationMs + " ms"];
    if (facts.inputTokens != null) telemetry.push("输入 " + facts.inputTokens + " / 输出 " + facts.outputTokens + " Token");
    summary.append(node("p", telemetry.join(" · "), "meta"));
    if (facts.traceId) summary.append(node("p", "Langfuse Trace " + facts.traceId, "meta"));
    detail.append(summary);
    const grid = node("div", null, "detail-grid");
    const timeline = node("section");
    timeline.append(node("h3", "调查与执行时间线"));
    for (const event of facts.timeline) {
      const {step, ms, ...data} = event;
      const caption = (steps[step] || step) + (ms === undefined ? "" : " · " + ms + " ms");
      timeline.append(disclosure(caption, node("pre", JSON.stringify(data, null, 2))));
    }
    const evidence = node("section");
    evidence.append(node("h3", "引用条款"));
    for (const item of facts.citations) {
      const body = node("div");
      body.append(node("p", item.source + " · " + item.version, "meta"), node("p", item.text));
      evidence.append(disclosure(item.title, body));
    }
    if (!facts.citations.length) evidence.append(node("p", "暂无适用引用", "hint"));
    grid.append(timeline, evidence);
    detail.append(grid);
  }
  if (ticket.status === "AWAITING_APPROVAL") {
    const box = node("section", null, "review");
    box.append(node("h3", "报表导出权益补发申请"),
      node("p", ticket.order_id + " · REPORT_EXPORT"));
    if (session.approver) {
      const form = node("form");
      const label = node("label", "审批理由");
      const reason = node("input");
      reason.required = true;
      reason.maxLength = 300;
      label.append(reason);
      const actions = node("div", null, "actions");
      for (const [decision, copy] of [["APPROVE", "批准并执行"], ["REJECT", "拒绝补发"]]) {
        const button = node("button", copy, decision === "APPROVE" ? "primary" : "");
        button.value = decision;
        actions.append(button);
      }
      form.append(label, actions);
      form.onsubmit = event => {
        event.preventDefault();
        action(event.submitter, async () => refresh(await api("/tickets/" + active + "/decision", {
          decision: event.submitter.value, reason: reason.value,
        })));
      };
      box.append(form);
    } else {
      box.append(node("p", "等待审批员处理。", "hint"));
    }
    detail.append(box);
  }
  if (ticket.decision) detail.append(node("p", "审批员 " + ticket.decided_by + " · " + ticket.decision_reason, "meta"));
  if (ticket.operation) detail.append(disclosure("业务操作流水", node("pre", JSON.stringify(ticket.operation, null, 2))));
  if (["INVESTIGATING", "APPROVED", "DECLINED"].includes(ticket.status)) {
    const button = node("button", "继续运行", "primary");
    button.onclick = () => action(button, async () => refresh(await api("/tickets/" + active + "/run", {})));
    detail.append(button);
  }
  if (ticket.runError) notice(ticket.runError);
  detail.setAttribute("aria-busy", "false");
}

async function refresh(ticket) {
  rows = await api("/tickets");
  show(ticket);
}

by("#filter").onchange = queue;
by("#new-ticket").onclick = () => compose.showModal();
by("#close").onclick = () => compose.close();
create.onsubmit = event => {
  event.preventDefault();
  action(event.submitter, async () => {
    const ticket = await api("/tickets", {
      orderId: create.elements.orderId.value, description: create.elements.description.value,
    });
    compose.close();
    create.reset();
    by("#filter").value = "";
    await refresh(ticket);
  });
};

(async function init() {
  try {
    session = await api("/session");
    by("#role").textContent = session.approver ? "审批员" : "客服";
    by("#logout input").value = session.csrf;
    by("#new-ticket").hidden = session.approver;
    by("#new-ticket").disabled = false;
    rows = await api("/tickets");
    const requested = new URLSearchParams(location.search).get("ticket");
    const selected = requested || rows[0]?.id;
    if (selected) show(await api("/tickets/" + selected));
    else {
      queue();
      const empty = node("div", null, "empty");
      empty.append(node("h2", "暂无工单"),
        node("p", session.approver ? "客服提交工单后，可在此查看待审批的补发申请。" : "新建工单，登记订单号和客户反馈。"));
      detail.replaceChildren(empty);
      detail.setAttribute("aria-busy", "false");
    }
  } catch (error) {
    notice(error.message);
    detail.replaceChildren(node("p", "无法加载工单，请刷新重试。", "empty"));
    detail.setAttribute("aria-busy", "false");
  }
})();
