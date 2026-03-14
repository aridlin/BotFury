from flask import Flask, render_template, request, jsonify
from bot_manager import BotManager, is_valid_hostname

app = Flask(__name__)
bot_manager = BotManager()

server_ip = "minecraft.pl"


@app.route("/")
def home():
    return render_template("dashboard.html", server_ip=server_ip)


@app.route("/get_bots", methods=["GET"])
def get_bots():
    return jsonify(bot_manager.get_all_bots())


@app.route("/add_remote_bot", methods=["POST"])
def add_remote_bot():
    data = request.get_json(force=True, silent=True) or {}

    host = str(data.get("host", "")).strip()
    port = data.get("port")

    if not host or not is_valid_hostname(host):
        return jsonify({"status": "Failed: Invalid host"}), 400

    try:
        port = int(port)
    except (TypeError, ValueError):
        return jsonify({"status": "Failed: Invalid port"}), 400

    result = bot_manager.add_remote_bot(host, port)

    if result == -1:
        return jsonify({"status": "Failed: Max bot instances reached"}), 400
    if result == -2:
        return jsonify({"status": "Failed: Bot already attached"}), 400

    return jsonify({"status": f"Attached bot {result} at {host}:{port}", "bot_id": result})


@app.route("/scan_host_range", methods=["POST"])
def scan_host_range():
    data = request.get_json(force=True, silent=True) or {}

    host = str(data.get("host", "")).strip()
    start_port = data.get("start_port")
    end_port = data.get("end_port")

    if not host or not is_valid_hostname(host):
        return jsonify({"status": "Failed: Invalid host"}), 400

    try:
        start_port = int(start_port)
        end_port = int(end_port)
    except (TypeError, ValueError):
        return jsonify({"status": "Failed: Invalid port range"}), 400

    if start_port < 1 or end_port > 65535 or start_port > end_port:
        return jsonify({"status": "Failed: Invalid port range"}), 400

    attached = bot_manager.scan_and_attach_range(host, start_port, end_port)

    return jsonify({
        "status": f"Attached {len(attached)} bot(s) from {host}:{start_port}-{end_port}",
        "attached": attached
    })


@app.route("/stop_bot", methods=["POST"])
def stop_bot():
    data = request.get_json(force=True, silent=True) or {}
    bot_id = data.get("bot_id")

    try:
        bot_id = int(bot_id)
    except (TypeError, ValueError):
        return jsonify({"status": "Failed: Invalid bot_id"}), 400

    ok = bot_manager.stop_bot(bot_id)
    if not ok:
        return jsonify({"status": "Failed: Bot not found"}), 404

    return jsonify({"status": f"Bot {bot_id} detached"})


@app.route("/send_command", methods=["POST"])
def send_command():
    data = request.get_json(force=True, silent=True) or {}
    bot_id = data.get("bot_id")
    cmd = str(data.get("cmd", "")).strip()

    if not cmd:
        return jsonify({"status": "Failed: Command cannot be empty"}), 400

    if bot_id == "all":
        results = bot_manager.broadcast_command(cmd)
        return jsonify({"status": f"Broadcast sent to {len(results)} bot(s)", "results": results})

    try:
        bot_id = int(bot_id)
    except (TypeError, ValueError):
        return jsonify({"status": "Failed: Invalid bot_id"}), 400

    status = bot_manager.send_command(bot_id, cmd)
    return jsonify({"status": status})


@app.route("/set_server_ip", methods=["POST"])
def set_server_ip():
    global server_ip
    data = request.get_json(force=True, silent=True) or {}
    new_ip = str(data.get("server_ip", "")).strip()

    if not new_ip or not is_valid_hostname(new_ip):
        return jsonify({"status": "Failed: Invalid server IP"}), 400

    server_ip = new_ip
    return jsonify({"status": f"Next connect command will use {server_ip}"})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
