import pytest
from flask import Flask
from dashboard import app, bot_manager, server_ip
from bot_manager import is_valid_hostname

@pytest.fixture
def client():
    app.config["TESTING"] = True
    with app.test_client() as client:
        yield client

def test_home(client):
    response = client.get("/")
    assert response.status_code == 200
    assert b"BotFury" in response.data

def test_get_bots(client):
    response = client.get("/get_bots")
    assert response.status_code == 200
    assert response.is_json

def test_add_remote_bot(client):
    bot_manager.bots.clear()
    response = client.post("/add_remote_bot", json={"host": "127.0.0.1", "port": 12345})
    assert response.status_code == 200
    assert "bot_id" in response.get_json()


def test_add_remote_bot_invalid_host(client):
    response = client.post("/add_remote_bot", json={"host": "invalid host !!!", "port": 12345})
    assert response.status_code == 400
    assert response.get_json()["status"] == "Failed: Invalid host"


def test_add_remote_bot_invalid_port(client):
    response = client.post("/add_remote_bot", json={"host": "127.0.0.1", "port": "invalid"})
    assert response.status_code == 400
    assert response.get_json()["status"] == "Failed: Invalid port"


def test_stop_bot(client):
    bot_manager.bots.clear()
    result = client.post("/add_remote_bot", json={"host": "127.0.0.1", "port": 12345})
    bot_id = result.get_json()["bot_id"]
    response = client.post("/stop_bot", json={"bot_id": bot_id})
    assert response.status_code == 200
    assert bot_id not in bot_manager.bots

def test_set_server_ip_valid(client):
    response = client.post("/set_server_ip", json={"server_ip": "192.168.1.1"})
    assert response.status_code == 200
    data = response.get_json()
    assert "192.168.1.1" in data["status"]

def test_set_server_ip_invalid(client):
    response = client.post("/set_server_ip", json={"server_ip": "not_an_ip!!"})
    assert response.status_code == 400
    data = response.get_json()
    assert "Failed" in data["status"]

def test_is_valid_hostname():
    assert is_valid_hostname("192.168.1.1") == True
    assert is_valid_hostname("127.0.0.1") == True
    assert is_valid_hostname("localhost") == True
    assert is_valid_hostname("google.com") == True
    assert is_valid_hostname("[2001:db8::1]") == True

    assert is_valid_hostname("invalid ip space") == False
    assert is_valid_hostname("") == False

def test_send_command_invalid(client):
    response = client.post("/send_command", json={"bot_id": "invalid", "cmd": "test"})
    assert response.status_code == 400
    data = response.get_json()
    assert "Failed: Invalid bot_id" in data["status"]

def test_send_command_empty(client):
    response = client.post("/send_command", json={"bot_id": "1", "cmd": ""})
    assert response.status_code == 400
    data = response.get_json()
    assert "Failed: Command cannot be empty" in data["status"]

def test_remote_bot_send_command_error():
    from unittest.mock import patch
    import requests as req
    from bot_manager import RemoteBot
    bot = RemoteBot(bot_id=1, host="127.0.0.1", port=8765, name="TestBot")
    with patch('requests.get') as mock_get:
        mock_get.side_effect = req.ConnectionError("Mocked connection error")
        result = bot.send_command("test")
        assert result == "Command failed (127.0.0.1:8765 unreachable)"
