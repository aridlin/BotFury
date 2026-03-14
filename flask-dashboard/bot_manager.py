import json
import os
import re
import socket
import urllib.parse
from typing import Dict, Optional, List

import requests

CONFIG_PATH = os.path.join(
    os.path.dirname(os.path.dirname(__file__)),
    "config",
    "botfury.json"
)


def load_config():
    default_config = {
        "max_bot_instances": 50,
        "default_bot_names": [],
        "webhook_url": ""
    }

    config_dir = os.path.dirname(CONFIG_PATH)
    os.makedirs(config_dir, exist_ok=True)

    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(default_config, f, indent=4)
        return default_config

    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            config = json.load(f)
        for k, v in default_config.items():
            if k not in config:
                config[k] = v
        return config
    except Exception:
        return default_config


def is_valid_hostname(hostname: str) -> bool:
    if not hostname or not isinstance(hostname, str) or len(hostname) > 255:
        return False

    hostname = hostname.strip()

    if hostname.startswith("[") and hostname.endswith("]"):
        try:
            socket.inet_pton(socket.AF_INET6, hostname[1:-1])
            return True
        except OSError:
            return False

    try:
        socket.inet_pton(socket.AF_INET6, hostname)
        return True
    except OSError:
        pass

    host_only = hostname
    if ":" in hostname and not hostname.startswith("["):
        # host:port typed accidentally
        parts = hostname.rsplit(":", 1)
        if len(parts) == 2 and parts[1].isdigit():
            host_only = parts[0]

    try:
        socket.inet_pton(socket.AF_INET, host_only)
        return True
    except OSError:
        pass

    if host_only.endswith("."):
        host_only = host_only[:-1]

    allowed = re.compile(r"(?!-)[A-Z\d-]{1,63}(?<!-)$", re.IGNORECASE)
    return all(allowed.match(x) for x in host_only.split("."))


class RemoteBot:
    def __init__(self, bot_id: int, host: str, port: int, name: str):
        self.bot_id = bot_id
        self.host = host
        self.port = port
        self.name = name

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    def get_status(self) -> dict:
        try:
            r = requests.get(f"{self.base_url}/status", timeout=1.5)
            r.raise_for_status()
            data = r.json()

            return {
                "connected": bool(data.get("connected", False)),
                "renderEnabled": bool(data.get("renderEnabled", True)),
                "player": str(data.get("player", "None"))
            }
        except (requests.RequestException, ValueError):
            return {
                "connected": False,
                "renderEnabled": True,
                "player": "None"
            }

    def send_command(self, command: str) -> str:
        try:
            encoded_cmd = urllib.parse.quote(command, safe="")
            r = requests.get(f"{self.base_url}/command?cmd={encoded_cmd}", timeout=2)
            r.raise_for_status()
            return r.text.strip() or "ok"
        except requests.RequestException:
            return f"Command failed ({self.host}:{self.port} unreachable)"

    def is_reachable(self) -> bool:
        try:
            r = requests.get(f"{self.base_url}/status", timeout=0.8)
            return r.ok
        except requests.RequestException:
            return False


class BotManager:
    def __init__(self):
        self.bots: Dict[int, RemoteBot] = {}
        self.next_bot_id = 1
        self.config = load_config()

    def send_webhook(self, message: str):
        webhook_url = self.config.get("webhook_url")
        if not webhook_url:
            return

        try:
            requests.post(webhook_url, json={"content": message}, timeout=2)
        except Exception:
            pass

    def get_bot_name(self, bot_id: int) -> str:
        default_names = self.config.get("default_bot_names", [])
        if default_names and len(default_names) >= bot_id:
            return default_names[bot_id - 1]
        return f"Bot{bot_id:02}"

    def _already_attached(self, host: str, port: int) -> bool:
        return any(bot.host == host and bot.port == port for bot in self.bots.values())

    def add_remote_bot(self, host: str, port: int) -> int:
        if len(self.bots) >= self.config.get("max_bot_instances", 50):
            return -1

        if self._already_attached(host, port):
            return -2

        bot_id = self.next_bot_id
        self.next_bot_id += 1

        name = self.get_bot_name(bot_id)
        bot = RemoteBot(bot_id, host, port, name)
        self.bots[bot_id] = bot

        self.send_webhook(f"🌐 Attached remote bot `{name}` at `{host}:{port}`.")
        return bot_id

    def scan_and_attach_range(self, host: str, start_port: int, end_port: int) -> List[dict]:
        attached = []

        for port in range(start_port, end_port + 1):
            if len(self.bots) >= self.config.get("max_bot_instances", 50):
                break

            if self._already_attached(host, port):
                continue

            candidate = RemoteBot(-1, host, port, "temp")
            if candidate.is_reachable():
                new_id = self.add_remote_bot(host, port)
                if new_id > 0:
                    attached.append({
                        "bot_id": new_id,
                        "host": host,
                        "port": port
                    })

        return attached

    def stop_bot(self, bot_id: int) -> bool:
        if bot_id not in self.bots:
            return False

        name = self.bots[bot_id].name
        del self.bots[bot_id]
        self.send_webhook(f"🛑 Detached bot `{name}`.")
        return True

    def get_all_bots(self) -> List[dict]:
        results = []

        for bot_id in sorted(self.bots.keys()):
            bot = self.bots[bot_id]
            status = bot.get_status()

            results.append({
                "id": bot_id,
                "name": bot.name,
                "host": bot.host,
                "port": bot.port,
                "status": "Attached",
                "ingame": status["connected"],
                "render": status["renderEnabled"],
                "player": status["player"]
            })

        return results

    def send_command(self, bot_id: int, command: str) -> str:
        if bot_id in self.bots:
            return self.bots[bot_id].send_command(command)
        return "Bot not found"

    def broadcast_command(self, command: str) -> List[dict]:
        results = []

        for bot_id, bot in self.bots.items():
            result = bot.send_command(command)
            results.append({
                "bot_id": bot_id,
                "host": bot.host,
                "port": bot.port,
                "result": result
            })

        return results
