"use strict";

const $ = id => document.getElementById(id);
const canvas = $("map");
const ctx = canvas.getContext("2d");
const attach = (id, event, callback) => $(id).addEventListener(event, callback);
const selections = new Set();
const view = {x: 0, z: 0, scale: 2, width: 0, height: 0, fitted: false};
const tones = {ink: "#191815", rust: "#943823", paper: "#f4edda", wash: "#e8ddc2", yellow: "#f6cd73"};
let hosts = [], bots = [], visible = [], inspecting = "", hovered = "", chatSource = "";
let drag = null, polling = false, ordering = false, saved = [], rosterSignature = "", runSignature = "";
const itemName = id => id.replace(/^minecraft:/, "").replaceAll("_", " ");
const serverName = bot => bot.status.serverAddress || bot.status.server || "";
const dimension = bot => bot.status.dimension || "minecraft:overworld";
const scope = bot => JSON.stringify([serverName(bot), dimension(bot)]);
const hasPosition = bot => bot.online && bot.status.connected
    && Number.isFinite(bot.status.x) && Number.isFinite(bot.status.z);
const botName = bot => bot.status.player && bot.status.player !== "None" ? bot.status.player : bot.name;
const stateName = bot => !bot.online ? "Host offline" : bot.status.connected
    ? (bot.status.taskKind && bot.status.taskKind !== "none" ? bot.status.taskKind : "In game")
    : bot.reachable ? "Ready" : bot.running ? "Starting" : "Stopped";

function node(tag, text, className) {
  const el = document.createElement(tag);
  if (text) el.textContent = text;
  if (className) el.className = className;
  return el;
}

async function api(path, body) {
  const response = await fetch("api/" + path, body === undefined ? {cache: "no-store"} : {
    method: "POST",
    headers: {"Content-Type": "application/json", "X-BotFury": "fleet"},
    body: JSON.stringify(body),
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || "Request failed.");
  return data;
}

function updateRoster() {
  const query = $("find").value.toLowerCase().trim();
  visible = bots.filter(bot => [bot.name, botName(bot), bot.hostName, serverName(bot)]
      .some(field => field.toLowerCase().includes(query)));
  const signature = JSON.stringify([
    hosts.map(host => [host.id, host.name, host.online]), inspecting, [...selections],
    visible.map(bot => [bot.key, botName(bot), bot.status.connected, bot.reachable, stateName(bot), serverName(bot)]),
  ]);
  if (signature !== rosterSignature) {
    const focused = document.activeElement.dataset.botFocus;
    const scroll = $("hosts").scrollTop;
    const sections = hosts.map(host => {
      const entries = visible.filter(bot => bot.host === host.id);
      const section = node("section");
      const head = node("div", host.name, "host-head");
      head.append(node("small", host.online ? entries.filter(bot => bot.status.connected).length + " online" : "Offline"));
      section.append(head);
      for (const bot of entries) {
        const row = node("div", "", "bot-row" + (inspecting === bot.key ? " active" : ""));
        const checkbox = node("input");
        checkbox.type = "checkbox";
        checkbox.checked = selections.has(bot.key);
        checkbox.disabled = !bot.online;
        checkbox.dataset.botFocus = bot.key + "/check";
        checkbox.setAttribute("aria-label", "Select " + botName(bot));
        checkbox.addEventListener("change", () => {
          if (checkbox.checked) selections.add(bot.key);
          else selections.delete(bot.key);
          updateRoster();
        });
        const open = node("button");
        open.dataset.botFocus = bot.key + "/open";
        const name = node("span", "", "bot-name");
        name.append(node("i", "", "dot" + (bot.online && bot.reachable ? (bot.status.connected ? " live" : " ready") : "")),
          node("span", botName(bot)));
        const state = stateName(bot) + (serverName(bot) && bot.status.connected ? " · " + serverName(bot) : "");
        open.append(name, node("span", state, "subline"));
        open.title = state;
        open.addEventListener("click", () => inspect(inspecting === bot.key ? "" : bot.key, true));
        row.append(checkbox, open);
        section.append(row);
      }
      return section;
    });
    $("hosts").replaceChildren(...sections);
    if (focused) Array.from($("hosts").querySelectorAll("[data-bot-focus]"))
      .find(el => el.dataset.botFocus === focused)?.focus({preventScroll: true});
    $("hosts").scrollTop = scroll;
    rosterSignature = signature;
  }
  const selected = bots.filter(bot => bot.online && selections.has(bot.key));
  $("selection-count").textContent = selected.length + " selected";
  $("select-visible").disabled = !visible.some(bot => bot.online);
  $("open-bed").disabled = !selected.some(bot => hasPosition(bot) && scope(bot) === $("world").value);
  $("dispatch-profession").disabled = !selected.length || !$("saved-profession").value;
  for (const button of document.querySelectorAll("[data-order]")) {
    const order = button.dataset.order;
    button.disabled = ordering || !selected.some(bot => order === "start" ? !bot.running && !bot.reachable
        : order === "stop" ? bot.running : order === "connect" ? bot.reachable && !bot.status.connected : bot.reachable);
  }
}

function updateWorld() {
  const worlds = new Map();
  for (const bot of bots) {
    if (!hasPosition(bot)) continue;
    const key = scope(bot);
    const label = (serverName(bot) || "Local world") + " · " + dimension(bot).replace(/^minecraft:/, "");
    worlds.set(key, label);
  }
  const select = $("world");
  const old = select.value;
  const signature = JSON.stringify([...worlds]);
  if (signature !== select.dataset.signature) {
    select.dataset.signature = signature;
    select.replaceChildren(...(worlds.size ? Array.from(worlds, ([key, label]) => new Option(label, key)) : [new Option("No active world", "")]));
    if (worlds.has(old)) select.value = old;
    if (select.value !== old) view.fitted = false;
  }
}

function renderChat(id, lines) {
  const list = $(id);
  const signature = JSON.stringify(lines);
  if (list.dataset.signature === signature) return;
  list.dataset.signature = signature;
  const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 20;
  const entries = lines.map(line => {
    const row = node("li");
    const date = new Date(line.time);
    row.append(node("time", date.toLocaleTimeString([], {hour: "2-digit", minute: "2-digit"})),
      document.createTextNode(line.text));
    return row;
  });
  list.replaceChildren(...(entries.length ? entries : [node("li", "No chat received.", "subline")]));
  if (atBottom) list.scrollTop = list.scrollHeight;
}

function renderInspector() {
  const bot = bots.find(candidate => candidate.key === inspecting);
  $("inspect-heading").textContent = bot ? botName(bot) : "Inventories";
  const status = bot?.status;
  const details = [];
  if (bot) {
    details.push(bot.hostName, stateName(bot));
    if (hasPosition(bot)) details.push([status.x, status.y, status.z].map(Math.floor).join(", "));
    if (status.bedTask) details.push(status.bedTask);
    const task = status.projectTask || status.miningTask || status.inventoryTask;
    if (task && task !== "idle" && task !== "none") details.push(task);
  }
  $("inspect-info").textContent = bot ? details.join(" · ") : "Choose a bot to inspect its status and chat.";
  $("health").hidden = !Number.isFinite(status?.health);
  if (!$("health").hidden) {
    $("health-meter").max = status.maxHealth || 20;
    $("health-meter").value = status.health;
    $("health").lastElementChild.textContent = Math.round(status.health) + "/" + $("health-meter").max;
  }
  const aggregate = new Map();
  const candidates = bot ? [bot] : bots.filter(b => b.online && b.status.connected);
  for (const candidate of candidates) {
    for (const [item, count] of Object.entries(candidate.status.items || {})) {
      if (Number.isFinite(count)) aggregate.set(item, (aggregate.get(item) || 0) + count);
    }
  }
  $("inventory-heading").textContent = bot ? "Inventory" : "Fleet totals";
  const slotItems = Array.isArray(status?.inventorySlots) ? status.inventorySlots : [];
  const items = slotItems.length
    ? slotItems.map(item => [item.id, item.count, item.slot < 36 ? String(item.slot + 1) : ["BT", "LG", "CH", "HD", "OH"][item.slot - 36]])
    : [...aggregate].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([id, count]) => [id, count, ""]);
  const signature = JSON.stringify(items);
  if ($("inventory").dataset.signature !== signature) {
    $("inventory").dataset.signature = signature;
    $("inventory").replaceChildren(...(items.length ? items.map(([id, count, slot]) => {
      const row = node("div", "", "item");
      row.append(node("small", slot), node("span", itemName(id)), node("strong", String(count)));
      return row;
    }) : [node("p", "No items reported.", "subline")]));
  }
  $("bot-chat-panel").hidden = !bot;
  renderChat("bot-chat", Array.isArray(status?.chat) ? status.chat : []);
}

function currentMapBots() {
  return bots.filter(bot => hasPosition(bot) && scope(bot) === $("world").value);
}

function renderWorldChat() {
  const current = currentMapBots();
  const first = current[0];
  const sameServer = first ? bots.filter(bot => bot.online && bot.status.connected && serverName(bot) === serverName(first)) : [];
  const source = sameServer.find(bot => bot.key === chatSource && Array.isArray(bot.status.chat))
      || sameServer.find(bot => bot.status.chat?.length) || sameServer.find(bot => Array.isArray(bot.status.chat));
  chatSource = source?.key || "";
  $("chat-source").textContent = source ? "From " + botName(source) : "";
  renderChat("world-chat", (source?.status.chat || []).filter(line => line.publicChat));
}

function inspect(key, center) {
  if (inspecting !== key) $("bot-chat-panel").open = false;
  inspecting = key;
  const bot = bots.find(candidate => candidate.key === key);
  if (bot && center && hasPosition(bot)) {
    const changedWorld = $("world").value !== scope(bot);
    $("world").value = scope(bot);
    if (changedWorld) fit(false);
    view.x = bot.status.x;
    view.z = bot.status.z;
  }
  updateRoster();
  renderInspector();
  renderWorldChat();
  paint();
}

function mapBox() {
  const rect = canvas.getBoundingClientRect();
  const resolution = Math.min(window.devicePixelRatio || 1, 2);
  view.width = rect.width;
  view.height = rect.height;
  const width = Math.round(rect.width * resolution), height = Math.round(rect.height * resolution);
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  ctx.setTransform(resolution, 0, 0, resolution, 0, 0);
}

function fit(redraw = true) {
  const current = currentMapBots();
  if (!current.length) return;
  const xs = current.map(b => b.status.x), zs = current.map(b => b.status.z);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const minZ = Math.min(...zs), maxZ = Math.max(...zs);
  view.x = (minX + maxX) / 2;
  view.z = (minZ + maxZ) / 2;
  view.scale = Math.max(.00001, Math.min(12, (view.width - 95) / Math.max(16, maxX - minX),
      (view.height - 95) / Math.max(16, maxZ - minZ)));
  view.fitted = true;
  if (redraw) paint();
}

function project(x, z) {
  return [(x - view.x) * view.scale + view.width / 2, (z - view.z) * view.scale + view.height / 2];
}

function unproject(x, y) {
  return [view.x + (x - view.width / 2) / view.scale, view.z + (y - view.height / 2) / view.scale];
}

function paint() {
  mapBox();
  if (!view.fitted) fit(false);
  const {width, height, scale} = view;
  ctx.fillStyle = tones.wash;
  ctx.fillRect(0, 0, width, height);
  const current = currentMapBots();
  $("map-empty").hidden = current.length !== 0;
  const [left, top] = unproject(0, 0);
  const [right, bottom] = unproject(width, height);
  const spacing = 2 ** Math.ceil(Math.log2(34 / scale));
  const startX = Math.floor(left / spacing) * spacing;
  const startZ = Math.floor(top / spacing) * spacing;
  ctx.font = "10px monospace";
  for (let x = startX; x <= right; x += spacing) {
    const px = Math.round(project(x, 0)[0]) + .5;
    ctx.strokeStyle = x === 0 ? "#94382388" : "#19181530";
    ctx.beginPath(); ctx.moveTo(px, 0); ctx.lineTo(px, height); ctx.stroke();
    if (px > 40) {
      ctx.fillStyle = "#665d4c";
      ctx.fillText(String(x), px + 3, 14);
    }
  }
  for (let z = startZ; z <= bottom; z += spacing) {
    const py = Math.round(project(0, z)[1]) + .5;
    ctx.strokeStyle = z === 0 ? "#94382388" : "#19181530";
    ctx.beginPath(); ctx.moveTo(0, py); ctx.lineTo(width, py); ctx.stroke();
    if (py > 21) {
      ctx.fillStyle = "#665d4c";
      ctx.fillText(String(z), 5, py - 3);
    }
  }
  const dots = document.createElement("canvas");
  dots.width = dots.height = 4;
  const ink = dots.getContext("2d");
  ink.fillStyle = "#94382340";
  ink.fillRect(0, 0, 1, 1);
  ink.fillRect(2, 2, 1, 1);
  ctx.fillStyle = ctx.createPattern(dots, "repeat");
  ctx.fillRect(0, 0, width, height);
  for (const bot of current) {
    if (bot.key !== inspecting && !selections.has(bot.key)) continue;
    const route = bot.status.mapRoute;
    if (!Array.isArray(route) || route.length === 0) continue;
    ctx.strokeStyle = "#943823aa";
    ctx.lineWidth = 1.2;
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(...project(bot.status.x, bot.status.z));
    for (const [x, z] of route) ctx.lineTo(...project(x + .5, z + .5));
    ctx.stroke();
    ctx.setLineDash([]);
  }
  for (const bot of current) {
    const [x, y] = project(bot.status.x, bot.status.z);
    if (x < -15 || y < -15 || x > width + 15 || y > height + 15) continue;
    const selected = inspecting === bot.key || selections.has(bot.key);
    if (selected) {
      ctx.strokeStyle = tones.rust;
      ctx.lineWidth = 2;
      ctx.strokeRect(x - 11, y - 11, 22, 22);
    }
    ctx.beginPath();
    ctx.moveTo(x, y - 8); ctx.lineTo(x + 8, y); ctx.lineTo(x, y + 8); ctx.lineTo(x - 8, y);
    ctx.closePath();
    ctx.fillStyle = bot.status.alive === false ? tones.wash : selected ? tones.yellow : tones.rust;
    ctx.fill();
    ctx.strokeStyle = tones.ink;
    ctx.lineWidth = 1.5;
    ctx.stroke();
  }
  const label = current.find(b => b.key === (hovered || inspecting));
  if (label) {
    const [x, y] = project(label.status.x, label.status.z);
    ctx.font = "bold 12px monospace";
    const text = botName(label) + " · " + stateName(label);
    const span = ctx.measureText(text).width + 14;
    const dx = Math.max(0, Math.min(x + 12, width - span));
    const dy = Math.max(0, Math.min(y - 12, height - 24));
    ctx.fillStyle = tones.paper;
    ctx.fillRect(dx, dy, span, 23);
    ctx.strokeRect(dx, dy, span, 23);
    ctx.fillStyle = tones.ink;
    ctx.fillText(text, dx + 7, dy + 16);
  }
}

function mouse(event) {
  const {left, top} = canvas.getBoundingClientRect();
  return [event.clientX - left, event.clientY - top];
}

function hit(x, y) {
  let best = "", radius = 13;
  for (const bot of currentMapBots()) {
    const [bx, by] = project(bot.status.x, bot.status.z);
    const distance = Math.hypot(x - bx, y - by);
    if (distance <= radius) {
      best = bot.key;
      radius = distance;
    }
  }
  return best;
}

function zoom(multiplier, x = view.width / 2, y = view.height / 2) {
  const [anchorX, anchorZ] = unproject(x, y);
  view.scale = Math.min(32, Math.max(.00001, view.scale * multiplier));
  view.x = anchorX - (x - view.width / 2) / view.scale;
  view.z = anchorZ - (y - view.height / 2) / view.scale;
  paint();
}

attach("map", "pointerdown", event => {
  if (event.button !== 0) return;
  const [x, y] = mouse(event);
  drag = {x, y, originX: view.x, originZ: view.z, moved: false};
  canvas.setPointerCapture(event.pointerId);
});
attach("map", "pointermove", event => {
  const [x, y] = mouse(event);
  if (drag && Math.hypot(x - drag.x, y - drag.y) > 4) drag.moved = true;
  if (drag?.moved) {
    canvas.classList.add("dragging");
    view.x = drag.originX - (x - drag.x) / view.scale;
    view.z = drag.originZ - (y - drag.y) / view.scale;
  }
  hovered = drag?.moved ? "" : hit(x, y);
  const [wx, wz] = unproject(x, y);
  $("coords").textContent = "X " + Math.floor(wx) + " · Z " + Math.floor(wz);
  paint();
});
attach("map", "pointerup", event => {
  const moved = drag?.moved;
  drag = null;
  canvas.classList.remove("dragging");
  if (!moved) {
    const key = hit(...mouse(event));
    if (key && (event.shiftKey || event.ctrlKey)) {
      if (selections.has(key)) selections.delete(key);
      else selections.add(key);
    }
    inspect(key, false);
  }
});
attach("map", "pointercancel", () => {
  drag = null;
  canvas.classList.remove("dragging");
});
attach("map", "pointerleave", () => {
  hovered = "";
  $("coords").textContent = "X →  Z ↓";
  paint();
});
canvas.addEventListener("wheel", event => {
  if (event.ctrlKey) return;
  event.preventDefault();
  zoom(event.deltaY < 0 ? 1.2 : 1 / 1.2, ...mouse(event));
}, {passive: false});
attach("map", "keydown", event => {
  const delta = 35 / view.scale;
  switch (event.key) {
    case "ArrowRight": view.x += delta; break;
    case "ArrowLeft": view.x -= delta; break;
    case "ArrowUp": view.z -= delta; break;
    case "ArrowDown": view.z += delta; break;
    case "+": case "=": zoom(1.2); break;
    case "-": zoom(1 / 1.2); break;
    default: return;
  }
  event.preventDefault();
  paint();
});
attach("fit", "click", () => fit());
attach("zoom-in", "click", () => zoom(1.25));
attach("zoom-out", "click", () => zoom(.8));
attach("world", "change", () => {
  view.fitted = false;
  hovered = "";
  paint();
  renderWorldChat();
  updateRoster();
});
new ResizeObserver(paint).observe(canvas);

attach("find", "input", updateRoster);
attach("select-visible", "click", () => {
  const online = visible.filter(bot => bot.online);
  const clear = online.every(bot => selections.has(bot.key));
  for (const bot of online) {
    if (clear) selections.delete(bot.key);
    else selections.add(bot.key);
  }
  updateRoster();
});

for (const button of document.querySelectorAll("[data-order]")) {
  button.addEventListener("click", async () => {
    const action = button.dataset.order;
    const selected = bots.filter(bot => bot.online && selections.has(bot.key)
      && (action === "start" ? !bot.running && !bot.reachable
        : action === "stop" ? bot.running : action === "connect" ? bot.reachable && !bot.status.connected : bot.reachable));
    if (action === "stop" && !window.confirm("Stop " + selected.length + " instance(s)?")) return;
    ordering = true;
    updateRoster();
    $("order-result").textContent = "Sending…";
    try {
      const {results} = await api("action", {action, server: $("target").value.trim(),
        bots: selected.map(bot => ({host: bot.host, slot: bot.id}))});
      const failures = results.filter(result => !result.ok);
      const accepted = results.length - failures.length;
      $("order-result").textContent = accepted + " accepted"
        + (failures.length ? " · " + failures.length + " failed · " + failures[0].message : "");
    } catch (error) {
      $("order-result").textContent = error.message;
    } finally {
      ordering = false;
      updateRoster();
      refresh();
    }
  });
}

attach("open-bed", "click", () => {
  $("bed-result").textContent = "";
  $("bed-dialog").showModal();
  $("bed-coords").focus();
});
attach("close-bed", "click", () => $("bed-dialog").close());
attach("send-bed", "click", async () => {
  const parts = $("bed-coords").value.trim().split(/[ ,]+/);
  if (parts.length !== 3 || parts.some(part => !/^-?[0-9]+$/.test(part))) {
    $("bed-result").textContent = "Enter X Y Z coordinates.";
    return;
  }
  const selected = currentMapBots().filter(bot => selections.has(bot.key));
  if (!selected.length) {
    $("bed-result").textContent = "Select bots in this world.";
    return;
  }
  const [server, dimension] = JSON.parse($("world").value);
  try {
    const {results} = await api("action", {action: "bed", server, bed: {dimension, coords: parts.map(Number)},
      bots: selected.map(bot => ({host: bot.host, slot: bot.id}))});
    const accepted = results.filter(result => result.ok).length;
    $("bed-result").textContent = accepted + "/" + results.length + " queued";
  } catch (error) {
    $("bed-result").textContent = error.message;
  }
});

function hydrateProfessions(professions, selected = "") {
  saved = professions;
  $("saved-profession").replaceChildren(new Option("New profession", ""),
    ...saved.map(profession => new Option(profession.name, profession.name)));
  $("saved-profession").value = selected;
  updateRoster();
}

attach("open-professions", "click", async () => {
  $("professions").showModal();
  try {
    const {professions} = await api("professions");
    hydrateProfessions(professions, $("saved-profession").value);
  } catch (error) { $("profession-result").textContent = error.message; }
});
attach("close-professions", "click", () => $("professions").close());
attach("saved-profession", "change", () => {
  const entry = saved.find(value => value.name === $("saved-profession").value);
  $("profession-name").value = entry?.name || "";
  $("profession-server").value = entry?.server || "";
  $("profession-steps").value = entry?.steps || "";
  $("profession-result").textContent = "";
  updateRoster();
});
attach("save-profession", "click", async () => {
  const name = $("profession-name").value.trim();
  try {
    const {professions} = await api("professions", {name, server: $("profession-server").value.trim(),
      steps: $("profession-steps").value.trim()});
    hydrateProfessions(professions, name);
    $("profession-result").textContent = "Plan saved.";
  } catch (error) {
    $("profession-result").textContent = error.message;
  }
});
attach("use-server", "click", () => {
  $("target").value = $("profession-server").value.trim();
  $("professions").close();
  $("target").focus();
});

attach("dispatch-profession", "click", async () => {
  const selected = bots.filter(bot => bot.online && selections.has(bot.key));
  try {
    const {id} = await api("runs", {name: $("saved-profession").value,
      bots: selected.map(bot => ({host: bot.host, slot: bot.id}))});
    $("profession-result").textContent = "Queued run " + id + ".";
    await refreshRuns();
  } catch (error) {
    $("profession-result").textContent = error.message;
  }
});

async function refreshRuns() {
  const {runs} = await api("runs");
  const signature = JSON.stringify(runs);
  if (signature === runSignature) return;
  runSignature = signature;
  $("runs").hidden = runs.length === 0;
  $("run-list").replaceChildren(...runs.slice(0, 10).map(run => {
    const row = node("li", "", "run");
    const info = node("div");
    info.append(node("strong", run.name + " · " + run.server), node("p", run.detail, "subline"));
    row.append(info);
    if (run.status === "running") {
      const stop = node("button", "Stop dispatch");
      stop.title = "Stop new assignments from the dashboard. Workers keep their current project.";
      stop.addEventListener("click", async () => {
        if (!window.confirm("Stop new assignments? Workers will keep their current project.")) return;
        try {
          await api("runs/" + run.id + "/cancel", {});
          await refreshRuns();
        } catch (error) {
          $("order-result").textContent = error.message;
        }
      });
      row.append(stop);
    }
    return row;
  }));
}


async function refresh() {
  if (polling) return;
  polling = true;
  try {
    const [result] = await Promise.all([api("fleet"), refreshRuns()]);
    const previous = new Map(hosts.map(host => [host.id, host.slots]));
    hosts = result.hosts.map(host => ({...host, slots: host.online ? host.slots : previous.get(host.id) || []}));
    bots = hosts.flatMap(host => host.slots.map(bot => ({...bot, host: host.id, hostName: host.name,
      online: host.online, key: JSON.stringify([host.id, bot.id])})));
    const active = bots.filter(bot => bot.online && bot.status.connected).length;
    $("summary").textContent = active + " in game · " + hosts.filter(host => host.online).length + "/" + hosts.length + " hosts online";
    updateWorld();
    updateRoster();
    renderInspector();
    renderWorldChat();
    paint();
  } catch {
    $("summary").textContent = "Fleet connection lost";
  } finally {
    polling = false;
  }
}

refresh();
setInterval(refresh, 1750);
