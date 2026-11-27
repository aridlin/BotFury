"""Store only terrain tiles actually observed by managed clients."""

from base64 import b64decode
import binascii
import json
from threading import Lock

from professions import open_database

MAX_CHUNK = 1_875_000
MAX_PNG = 240_000
MAX_TILES = 32_768


class TerrainStore:
    def __init__(self, database):
        self.database = database
        self.lock = Lock()
        self.seen = {}
        self.since_prune = 0
        with open_database(database) as db:
            db.execute("""CREATE TABLE IF NOT EXISTS terrain (
                server TEXT NOT NULL, dimension TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL,
                seen INTEGER NOT NULL, quarters TEXT NOT NULL, image BLOB NOT NULL,
                PRIMARY KEY(server, dimension, x, z)
            ) WITHOUT ROWID""")

    def observe(self, hosts):
        with self.lock:
            tiles = []
            for host in hosts:
                for bot in host["slots"]:
                    status = bot["status"]
                    server = status.get("serverAddress")
                    dimension = status.get("dimension")
                    candidates = status.pop("mapTiles", [])
                    if (status.get("connected") and isinstance(server, str) and len(server) <= 255
                            and isinstance(dimension, str) and len(dimension) <= 160 and isinstance(candidates, list)):
                        for candidate in candidates[:4]:
                            tile = self.parse(server, dimension, candidate)
                            if tile is not None:
                                tiles.append(tile)
            if not tiles:
                return
            with open_database(self.database) as db:
                db.executemany("""INSERT INTO terrain VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(server, dimension, x, z) DO UPDATE SET
                        seen=excluded.seen, quarters=excluded.quarters, image=excluded.image
                    WHERE excluded.seen > terrain.seen""", tiles)
                self.since_prune += len(tiles)
                if self.since_prune >= 1024:
                    db.execute("""DELETE FROM terrain WHERE (server, dimension, x, z) IN (
                        SELECT server, dimension, x, z FROM terrain ORDER BY seen DESC
                        LIMIT -1 OFFSET ?)""", (MAX_TILES,))
                    self.since_prune = 0
            if len(self.seen) > 4096:
                self.seen.clear()
            self.seen.update((tile[:4], tile[4]) for tile in tiles)

    def parse(self, server, dimension, tile):
        if not isinstance(tile, dict):
            return None
        x, z, seen = tile.get("x"), tile.get("z"), tile.get("seen")
        if (any(type(value) is not int for value in (x, z, seen))
                or abs(x) > MAX_CHUNK or abs(z) > MAX_CHUNK or not 0 < seen < 10**15):
            return None
        key = server, dimension, x, z
        if self.seen.get(key, 0) >= seen:
            return None
        quarters, encoded = tile.get("quarters"), tile.get("png")
        if (not isinstance(quarters, list) or len(quarters) != 4
                or any(type(color) is not int or not 0 <= color <= 0xFFFFFF for color in quarters)
                or not isinstance(encoded, str) or len(encoded) > MAX_PNG * 4 // 3 + 4):
            return None
        try:
            png = b64decode(encoded, validate=True)
        except (ValueError, binascii.Error):
            return None
        if (len(png) > MAX_PNG or not png.startswith(b"\x89PNG\r\n\x1a\n")
                or png[16:24] != b"\x00\x00\x01\x00\x00\x00\x01\x00"):
            return None
        return *key, seen, json.dumps(quarters), png

    def worlds(self):
        with open_database(self.database) as db:
            worlds = db.execute("""SELECT server, dimension, MIN(x), MIN(z), MAX(x), MAX(z)
                                   FROM terrain GROUP BY server, dimension ORDER BY MAX(seen) DESC LIMIT 100""")
            return [{"server": server, "dimension": dimension,
                     "bounds": [min_x * 16, min_z * 16, (max_x + 1) * 16, (max_z + 1) * 16]}
                    for server, dimension, min_x, min_z, max_x, max_z in worlds]

    def list(self, server, dimension, min_x, min_z, max_x, max_z):
        with open_database(self.database) as db:
            rows = db.execute("""SELECT x, z, seen, quarters FROM terrain
                WHERE server=? AND dimension=? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?
                ORDER BY seen DESC LIMIT 384""", (server, dimension, min_x, max_x, min_z, max_z))
            return [dict(x=x, z=z, seen=seen, quarters=json.loads(quarters))
                    for x, z, seen, quarters in rows]

    def png(self, server, dimension, x, z):
        with open_database(self.database) as db:
            row = db.execute("""SELECT image FROM terrain WHERE server=? AND dimension=? AND x=? AND z=?""",
                             (server, dimension, x, z)).fetchone()
            return row[0] if row else None
