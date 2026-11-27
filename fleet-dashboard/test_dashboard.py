from base64 import b64encode
import json
import logging
from pathlib import Path
import tempfile
import unittest

from dashboard import make_app, server_address
from professions import ProfessionRuns, project_ids
from terrain import TerrainStore


class FleetDashboardTest(unittest.TestCase):
    def test_hub_refuses_cross_site_actions_and_persists_professions(self):
        with tempfile.TemporaryDirectory() as folder:
            config = {"hosts": []}
            body = {"name": "Camp", "server": "localhost:25565", "steps": "stock\nbuild"}
            app = make_app(config, Path(folder))
            with app.test_client() as browser:
                self.assertEqual(browser.post("/api/professions", json=body).status_code, 403)
                accepted = browser.post("/api/professions", json=body, headers={"X-BotFury": "fleet"})
                self.assertEqual(accepted.status_code, 200)
            with make_app(config, Path(folder)).test_client() as browser:
                self.assertEqual(browser.get("/api/professions").get_json()["professions"], [body])

    def test_preview_refuses_writes_and_retains_separate_terrain_worlds(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            app = make_app({"hosts": [], "readOnly": True}, folder)
            store = TerrainStore(folder / "fleet.sqlite3")
            # An IHDR prefix is sufficient for storage validation. Paper integration verifies full PNG decoding.
            png = b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR\x00\x00\x01\x00\x00\x00\x01\x00"
            tile = {"x": -1, "z": 2, "seen": 1000, "quarters": [1, 2, 3, 4],
                    "png": b64encode(png).decode()}
            status = {"connected": True, "serverAddress": "world.test", "dimension": "minecraft:overworld",
                      "mapTiles": [tile]}
            store.observe([{"slots": [{"status": status}]}])
            with app.test_client() as browser:
                fleet = browser.get("/api/fleet").get_json()
                self.assertTrue(fleet["readOnly"])
                self.assertEqual(fleet["worlds"][0]["bounds"], [-16, 32, 0, 48])
                self.assertNotIn("mapTiles", status)
                self.assertEqual(browser.post("/api/action", json={}, headers={"X-BotFury": "fleet"}).status_code, 403)
                query = {"server": "world.test", "dimension": "minecraft:overworld",
                         "minX": -1, "maxX": -1, "minZ": 2, "maxZ": 2}
                self.assertEqual(browser.get("/api/terrain", query_string=query).get_json()["tiles"][0]["quarters"],
                                 [1, 2, 3, 4])
                query["server"] = "other.test"
                self.assertEqual(browser.get("/api/terrain", query_string=query).get_json()["tiles"], [])

    def test_agent_authenticates_before_reading_slots(self):
        with tempfile.TemporaryDirectory() as folder:
            token = "secret" * 8
            config = {"role": "agent", "token": token,
                      "slots": [{"id": "worker", "name": "Worker", "port": 18000, "argv": ["unused"]}]}
            app = make_app(config, Path(folder))
            # A rejected request must never probe the configured worker or start a process.
            agent = app.extensions["fleet_agent"]
            agent.snapshots = lambda: [{"name": "Worker"}]
            with app.test_client() as client:
                self.assertEqual(client.get("/slots").status_code, 403)
                good = client.get("/slots", headers={"Authorization": "Bearer " + token})
                self.assertEqual(good.get_json(), [{"name": "Worker"}])

    def test_validation_rejects_non_minecraft_endpoints_and_duplicate_steps(self):
        for value in ("https://localhost", "three..test", "one:65536", "user@host", "[::1]extra"):
            with self.assertRaises(ValueError):
                server_address(value)
        self.assertEqual(server_address("[::1]:25565"), "[::1]:25565")
        self.assertEqual(project_ids("gather\n build "), ["gather", "build"])
        with self.assertRaises(ValueError):
            project_ids("gather\ngather")


class ProfessionRunTest(unittest.TestCase):
    def test_complete_jobs_advance_to_next_project_and_bind_to_correct_world(self):
        with tempfile.TemporaryDirectory() as folder:
            project = {"id": "gather", "world": "local:25565|minecraft:overworld", "jobs": [{"state": "DONE"}]}
            attach = {"id": "build", "world": project["world"], "jobs": [{"state": "WAITING"}]}
            commands = []
            def invoke(*args):
                commands.append(args)
                return {"ok": True}
            runner = ProfessionRuns(Path(folder) / "state.sqlite", {"localhost": 18000},
                lambda _: [project, attach], lambda: [], invoke, logging.getLogger(__name__))
            row = {"id": 1, "server": "localhost", "step": 0, "status": "running",
                   "steps": '["gather","build"]', "targets": '[["host","worker"]]', "progress": "{}"}
            bot = {"projectServers": ["localhost:25565"], "reachable": True, "running": True,
                   "status": {"serverAddress": "localhost", "server": "local:25565", "connected": True,
                              "runtimeId": "client-1", "dimension": "minecraft:the_nether"}}
            lookup = {("host", "worker"): bot}
            runner.tick(row, lookup)
            self.assertEqual(row["step"], 1)
            self.assertEqual(commands, [])
            row["progress"] = json.dumps(row["progress"])
            runner.tick(row, lookup)
            self.assertEqual(commands, [])
            bot["status"]["dimension"] = "minecraft:overworld"
            row["progress"] = json.dumps(row["progress"])
            runner.tick(row, lookup)
            self.assertEqual(commands, [("host", "worker", "project", "localhost", "build")])


if __name__ == "__main__":
    unittest.main()
