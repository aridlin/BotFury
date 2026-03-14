const POLL_MS = 2000;

function toast(message) {
    const el = document.getElementById("toast");
    el.textContent = message;
    el.classList.remove("hidden");

    clearTimeout(window.__toastTimer);
    window.__toastTimer = setTimeout(() => {
        el.classList.add("hidden");
    }, 3000);
}

function logEntry(text) {
    const box = document.getElementById("commandLog");
    const div = document.createElement("div");
    div.className = "log-entry";
    div.textContent = `[${new Date().toLocaleTimeString()}] ${text}`;
    box.prepend(div);

    while (box.children.length > 30) {
        box.removeChild(box.lastChild);
    }
}

function getServerIP() {
    return document.getElementById("serverIP").value.trim();
}

async function postJson(url, payload) {
    const response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
    });

    let data = {};
    try {
        data = await response.json();
    } catch (_) {}

    if (!response.ok) {
        throw new Error(data.status || `Request failed: ${response.status}`);
    }

    return data;
}

async function changeServerIP() {
    const ip = getServerIP();
    if (!ip) {
        toast("Server IP cannot be empty.");
        return;
    }

    try {
        const data = await postJson("/set_server_ip", { server_ip: ip });
        toast(data.status || "Server updated");
        logEntry(`SET SERVER -> ${ip}`);
    } catch (error) {
        toast(error.message);
    }
}

async function addRemoteBot() {
    const host = document.getElementById("remoteHost").value.trim();
    const port = document.getElementById("remotePort").value.trim();

    if (!host || !port) {
        toast("Enter both host and port.");
        return;
    }

    try {
        const data = await postJson("/add_remote_bot", { host, port });
        toast(data.status || "Bot attached");
        logEntry(`ATTACH BOT -> ${host}:${port}`);
        document.getElementById("remotePort").value = "";
        refreshBots();
    } catch (error) {
        toast(error.message);
    }
}

async function scanHostRange() {
    const host = document.getElementById("scanHost").value.trim();
    const startPort = document.getElementById("scanStartPort").value.trim();
    const endPort = document.getElementById("scanEndPort").value.trim();

    if (!host || !startPort || !endPort) {
        toast("Enter host, start port, and end port.");
        return;
    }

    try {
        const data = await postJson("/scan_host_range", {
            host,
            start_port: startPort,
            end_port: endPort
        });

        toast(data.status || "Scan complete");
        logEntry(`SCAN RANGE -> ${host}:${startPort}-${endPort}`);
        refreshBots();
    } catch (error) {
        toast(error.message);
    }
}

async function stopBot(botId) {
    try {
        const data = await postJson("/stop_bot", { bot_id: botId });
        toast(data.status || "Bot detached");
        logEntry(`DETACH BOT ${botId}`);
        refreshBots();
    } catch (error) {
        toast(error.message);
    }
}

async function sendCommand(botId, cmd) {
    if (!cmd || !String(cmd).trim()) {
        toast("Command cannot be empty.");
        return;
    }

    try {
        const data = await postJson("/send_command", {
            bot_id: botId,
            cmd: cmd.trim()
        });
        toast(data.status || "Command sent");
        logEntry(`BOT ${botId} <- ${cmd}`);
        refreshBots();
    } catch (error) {
        toast(error.message);
    }
}

function broadcastChat() {
    const input = document.getElementById("broadcastChat");
    const msg = input.value.trim();
    if (!msg) return;

    sendCommand("all", "chat:" + msg);
    input.value = "";
}

function connectAll() {
    sendCommand("all", "connect:" + getServerIP());
}

function toggleAllRender() {
    sendCommand("all", "toggle_render");
}

function stopAllBaritone() {
    sendCommand("all", "chat:#stop");
}

function gotoSpawnAll() {
    sendCommand("all", "chat:#goto 0 64 0");
}

function escapeHtml(str) {
    return String(str)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function botCard(bot) {
    const online = !!bot.ingame;
    const statusClass = online ? "online" : "offline";
    const statusText = online ? "Online" : "Offline";

    return `
        <div class="bot-card">
            <div class="bot-top">
                <div>
                    <h3 class="bot-title">${escapeHtml(bot.name)}</h3>
                    <div class="bot-hostport">${escapeHtml(bot.host)}:${escapeHtml(bot.port)}</div>
                </div>
                <span class="status-pill ${statusClass}">● ${statusText}</span>
            </div>

            <div class="bot-meta">
                <div class="meta-box">
                    <div class="meta-label">Player</div>
                    <div class="meta-value">${escapeHtml(bot.player || "None")}</div>
                </div>
                <div class="meta-box">
                    <div class="meta-label">Render</div>
                    <div class="meta-value">${bot.render ? "ON" : "OFF"}</div>
                </div>
                <div class="meta-box">
                    <div class="meta-label">Host</div>
                    <div class="meta-value">${escapeHtml(bot.host)}</div>
                </div>
                <div class="meta-box">
                    <div class="meta-label">Port</div>
                    <div class="meta-value">${escapeHtml(bot.port)}</div>
                </div>
            </div>

            <div class="bot-controls">
                <button onclick="sendCommand(${bot.id}, 'connect:' + getServerIP())">Connect</button>
                <button onclick="sendCommand(${bot.id}, 'toggle_render')">Toggle Render</button>
                <button onclick="sendCommand(${bot.id}, 'chat:#mine diamond_ore')">Mine Diamond</button>
                <button onclick="sendCommand(${bot.id}, 'chat:#stop')">Stop Baritone</button>
                <button onclick="sendCommand(${bot.id}, 'chat:#goto 0 64 0')">Goto Spawn</button>
                <button onclick="sendCustomPrompt(${bot.id})">Custom Command</button>
            </div>

            <div class="bot-controls single">
                <button class="danger" onclick="stopBot(${bot.id})">Detach Bot</button>
            </div>
        </div>
    `;
}

function sendCustomPrompt(botId) {
    const value = prompt("Enter command.\nExamples:\nchat:hello\nchat:#mine diamond_ore\nconnect:minecraft.pl");
    if (!value) return;
    sendCommand(botId, value);
}

async function refreshBots() {
    try {
        const response = await fetch("/get_bots");
        const bots = await response.json();
        const grid = document.getElementById("botGrid");

        if (!Array.isArray(bots) || bots.length === 0) {
            grid.innerHTML = `
                <div class="empty-state">
                    No bots attached yet.<br>
                    Launch bots on any machine, then attach them by host and port.
                </div>
            `;
            return;
        }

        grid.innerHTML = bots.map(botCard).join("");
    } catch (error) {
        console.error("Failed to refresh bots:", error);
        toast("Failed to refresh bots.");
    }
}

setInterval(refreshBots, POLL_MS);
refreshBots();
