import socket
import struct
import subprocess as sp
import sys
import time


def wait(condition, limit=90):
    deadline = time.monotonic() + limit
    while time.monotonic() < deadline:
        try:
            if result := condition():
                return result
        except (OSError, EOFError, ValueError, sp.CalledProcessError):
            pass
        time.sleep(0.25)
    raise RuntimeError(f"Timed out from line {sys._getframe(1).f_lineno}")


class Rcon:
    def __init__(self, port, password):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=12)
        self.reader = self.socket.makefile("rb")
        self.request_id = 1
        try:
            self.send(3, password)
            while True:
                request_id, kind, _ = self.read()
                if request_id == -1:
                    raise RuntimeError("Paper fixture RCON authentication failed")
                if kind == 2:
                    break
        except BaseException:
            self.close()
            raise

    def send(self, kind, body):
        payload = struct.pack("<ii", self.request_id, kind) + body.encode() + b"\0\0"
        self.socket.sendall(struct.pack("<i", len(payload)) + payload)

    def read(self):
        length = self.reader.read(4)
        if len(length) != 4:
            raise EOFError("Paper closed the RCON connection")
        size, = struct.unpack("<i", length)
        data = self.reader.read(size)
        return (*struct.unpack("<ii", data[:8]), data[8:-2].decode())

    def command(self, command):
        self.request_id += 1
        self.send(2, command)
        while True:
            request_id, _, body = self.read()
            if request_id == self.request_id:
                return body

    def close(self):
        self.reader.close()
        self.socket.close()
