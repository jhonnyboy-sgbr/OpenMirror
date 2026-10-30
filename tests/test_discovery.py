import asyncio
import socket
import unittest

import _paths  # noqa: F401
from om_discovery import MAGIC, DiscoveryProtocol, build_reply, parse_reply, start_discovery
from om_meta import VERSION


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


class DiscoveryTests(unittest.IsolatedAsyncioTestCase):
    async def ask(self, port, payload=MAGIC, timeout=0.5):
        loop = asyncio.get_running_loop()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setblocking(False)
        try:
            sock.sendto(payload, ('127.0.0.1', port))
            return await asyncio.wait_for(loop.sock_recv(sock, 2048), timeout)
        finally:
            sock.close()

    async def test_answers_with_public_info_only(self):
        transport, proto = await start_discovery(8765, host='127.0.0.1', port=0, name='PC-DO-JOAO')
        port = transport.get_extra_info('sockname')[1]
        try:
            data = await self.ask(port)
            info = parse_reply(data)
            self.assertEqual(info['name'], 'PC-DO-JOAO')
            self.assertEqual(info['port'], 8765)
            self.assertEqual(info['version'], VERSION)
            self.assertIn(3, info['video_protocols'])
            text = data.decode().lower()
            for secret in ('pin', 'session', 'token', 'secret'):
                self.assertNotIn(secret, text)
            self.assertLess(len(data), 512)
        finally:
            transport.close()

    async def test_ignores_anything_else(self):
        transport, proto = await start_discovery(8765, host='127.0.0.1', port=0)
        port = transport.get_extra_info('sockname')[1]
        try:
            with self.assertRaises(asyncio.TimeoutError):
                await self.ask(port, payload=b'GET / HTTP/1.1', timeout=0.3)
            self.assertEqual(proto.answered, 0)
        finally:
            transport.close()

    async def test_rate_limited_per_source(self):
        clock = Clock()
        proto = DiscoveryProtocol('pc', 8765, clock=clock)
        sent = []

        class T:
            def sendto(self, data, addr):
                sent.append(addr)

        proto.transport = T()
        for _ in range(20):
            proto.datagram_received(MAGIC, ('10.0.0.5', 5000))
        self.assertEqual(len(sent), 5)
        proto.datagram_received(MAGIC, ('10.0.0.6', 5000))   # outra origem ainda é atendida
        self.assertEqual(len(sent), 6)
        clock.t = 1.5
        proto.datagram_received(MAGIC, ('10.0.0.5', 5000))
        self.assertEqual(len(sent), 7)

    def test_parse_reply_rejects_garbage(self):
        for junk in (b'', b'\xff\xfe', b'[]', b'{"app":"Outro","port":1}', b'{"app":"OpenMirror","port":"x"}'):
            self.assertIsNone(parse_reply(junk))
        self.assertIsNotNone(parse_reply(build_reply('x', 8765)))


if __name__ == '__main__':
    unittest.main()
