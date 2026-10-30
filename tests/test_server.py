"""Camada HTTP/WebSocket do servidor (precisa de aiohttp: `pip install -r windows/requirements.txt`).

Sem aiohttp instalado estes testes são PULADOS (e o relatório diz isso). Rode no seu PC:
    python -m unittest discover -s tests -v
"""
import shutil
import struct
import unittest
from unittest.mock import patch

import _paths  # noqa: F401

try:
    from aiohttp.test_utils import TestClient, TestServer
    from server import make_app
    HAVE_AIOHTTP = True
except ImportError:  # pragma: no cover
    HAVE_AIOHTTP = False

from om_meta import VERSION

MON = {'left': 0, 'top': 0, 'width': 1920, 'height': 1080}


class FakeController:
    def __init__(self):
        self.actions = []

    def move(self, x, y, monitor):
        self.actions.append(('move', x, y))

    def button(self, name, down):
        self.actions.append(('button', name, down))

    def wheel(self, dy, dx):
        self.actions.append(('wheel', dy, dx))

    def key_event(self, vk, ext, down):
        self.actions.append(('key', vk, ext, down))

    def type_text(self, value):
        self.actions.append(('text', value))


@unittest.skipUnless(HAVE_AIOHTTP, 'aiohttp não instalado: testes do servidor HTTP não executados')
class ServerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.controller = FakeController()
        self.app = make_app('1234', controller=self.controller)
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()

    async def auth(self):
        response = await self.client.post('/api/pair', json={'pin': '1234'})
        self.assertEqual(response.status, 200)
        return (await response.json())['session']

    async def headers(self):
        return {'X-OpenMirror-Session': await self.auth()}

    # ---------------------------------------------------------- pareamento
    async def test_hello_needs_no_session_and_leaks_nothing_sensitive(self):
        r = await self.client.get('/api/hello')
        self.assertEqual(r.status, 200)
        data = await r.json()
        self.assertEqual(data['version'], VERSION)
        self.assertIn(3, data['video_protocols'])
        for cap in ('drag', 'ws-input', 'timesync', 'combo'):
            self.assertIn(cap, data['capabilities'])
        self.assertNotIn('1234', str(data))

    async def test_pair_and_status(self):
        r = await self.client.get('/api/status')
        self.assertEqual(r.status, 401)
        r = await self.client.post('/api/pair', json={'pin': 'wrong'})
        self.assertEqual(r.status, 401)
        h = await self.headers()
        r = await self.client.get('/api/status', headers=h)
        self.assertEqual(r.status, 200)
        data = await r.json()
        self.assertEqual(data['version'], VERSION)
        for key in ('actual_fps', 'encode_fps', 'sent_fps', 'bitrate_kbps', 'dropped_frames',
                    'h264_available', 'encoder_active', 'sessions', 'capture_disabled'):
            self.assertIn(key, data)

    async def test_pair_survives_garbage_instead_of_500(self):
        """0.2.0: PIN não-ASCII virava TypeError -> HTTP 500."""
        for body in ({'pin': 'é'}, {'pin': 12345678}, {'pin': None}, {}, {'pin': ['x']}):
            r = await self.client.post('/api/pair', json=body)
            self.assertEqual(r.status, 401, body)
        for raw in ('[]', '"x"', 'not json', '5'):
            r = await self.client.post('/api/pair', data=raw, headers={'Content-Type': 'application/json'})
            self.assertEqual(r.status, 400, raw)

    async def test_session_only_in_header_for_api_paths(self):
        token = await self.auth()
        r = await self.client.get('/api/status?session=' + token)
        self.assertEqual(r.status, 401)       # query só vale para /stream, /stream/h264 e /ws/input

    async def test_disconnect_revokes_session_and_releases_buttons(self):
        h = await self.headers()
        self.app['state']['monitor'] = MON
        await self.client.post('/api/input', json={'action': 'down', 'x': .1, 'y': .1}, headers=h)
        r = await self.client.post('/api/disconnect', headers=h)
        self.assertEqual(r.status, 200)
        self.assertEqual(self.controller.actions[-1], ('button', 'left', False))
        r = await self.client.get('/api/status', headers=h)
        self.assertEqual(r.status, 401)

    async def test_disconnect_only_releases_that_session(self):
        first = await self.auth()
        second = await self.auth()
        self.app['state']['monitor'] = MON
        h1 = {'X-OpenMirror-Session': first}
        h2 = {'X-OpenMirror-Session': second}
        await self.client.post('/api/input', json={'action': 'down', 'x': .1, 'y': .1}, headers=h1)
        await self.client.post('/api/input', json={'action': 'keydown', 'key': 'ctrl'}, headers=h2)
        self.controller.actions.clear()
        response = await self.client.post('/api/disconnect', headers=h1)
        self.assertEqual(response.status, 200)
        self.assertIn(('button', 'left', False), self.controller.actions)
        self.assertNotIn(('key', 0x11, False, False), self.controller.actions)
        self.assertTrue(self.app['sessions'].valid(second))

    async def test_rate_limit(self):
        for _ in range(8):
            r = await self.client.post('/api/pair', json={'pin': '00000000'})
            self.assertEqual(r.status, 401)
        r = await self.client.post('/api/pair', json={'pin': '1234'})
        self.assertEqual(r.status, 429)

    # ------------------------------------------------------------- entrada
    async def test_control_validation_and_actions(self):
        h = await self.headers()
        r = await self.client.post('/api/input', json={'action': 'tap', 'x': .2, 'y': .7}, headers=h)
        self.assertEqual(r.status, 503)  # sem captura ainda
        self.app['state']['monitor'] = MON
        r = await self.client.post('/api/input', json={'action': 'tap', 'x': .2, 'y': .7}, headers=h)
        self.assertEqual(r.status, 200)
        self.assertEqual(self.controller.actions[-2:], [('button', 'left', True), ('button', 'left', False)])
        r = await self.client.post('/api/input', json={'action': 'text', 'text': 'olá'}, headers=h)
        self.assertEqual(self.controller.actions[-1], ('text', 'olá'))
        for bad in ({'action': 'move', 'x': 1.2, 'y': 0}, {'action': 'execute', 'cmd': 'rm -rf'}, [], 'x'):
            r = await self.client.post('/api/input', json=bad, headers=h)
            self.assertEqual(r.status, 400, bad)

    async def test_drag_and_combo_over_http(self):
        h = await self.headers()
        self.app['state']['monitor'] = MON
        for body in ({'action': 'down', 'x': .1, 'y': .1}, {'action': 'move', 'x': .5, 'y': .5},
                     {'action': 'up', 'x': .5, 'y': .5}, {'action': 'combo', 'keys': ['alt', 'tab']}):
            r = await self.client.post('/api/input', json=body, headers=h)
            self.assertEqual(r.status, 200, body)
        self.assertIn(('button', 'left', True), self.controller.actions)
        self.assertIn(('key', 0x09, False, True), self.controller.actions)

    async def test_websocket_commands_ack_pong_and_error(self):
        session = await self.auth()
        self.app['state']['monitor'] = MON
        ws = await self.client.ws_connect('/ws/input?session=' + session)
        await ws.send_json({'action': 'move', 'x': .25, 'y': .5, 'id': 1})
        answer = await ws.receive_json(timeout=3)
        self.assertEqual(answer, {'type': 'ack', 'id': 1})
        self.assertEqual(self.controller.actions[-1], ('move', .25, .5))
        await ws.send_json({'action': 'ping', 'id': 12, 't': 555})
        pong = await ws.receive_json(timeout=3)
        self.assertEqual((pong['type'], pong['id'], pong['t']), ('pong', 12, 555))
        self.assertIsInstance(pong['s'], int)
        await ws.send_json({'action': 'move', 'x': 7, 'y': 0})
        self.assertEqual((await ws.receive_json(timeout=3))['type'], 'error')
        await ws.send_str('isto não é json')
        self.assertEqual((await ws.receive_json(timeout=3))['type'], 'error')
        await ws.close()

    async def test_websocket_close_releases_held_button(self):
        """Celular perde o Wi-Fi no meio do arrasto: o botão não pode ficar preso no PC."""
        session = await self.auth()
        self.app['state']['monitor'] = MON
        ws = await self.client.ws_connect('/ws/input', headers={'X-OpenMirror-Session': session})
        await ws.send_json({'action': 'down', 'x': .1, 'y': .1, 'id': 1})
        await ws.receive_json(timeout=3)
        await ws.close()
        for _ in range(50):
            if self.controller.actions[-1] == ('button', 'left', False):
                break
            import asyncio
            await asyncio.sleep(0.02)
        self.assertEqual(self.controller.actions[-1], ('button', 'left', False))

    async def test_websocket_requires_session(self):
        r = await self.client.get('/ws/input')
        self.assertEqual(r.status, 401)

    # --------------------------------------------------------------- vídeo
    async def test_h264_requires_authentication_and_known_protocol(self):
        resp = await self.client.get('/stream/h264')
        self.assertEqual(resp.status, 401)
        token = await self.auth()
        for proto in ('9', 'x'):
            resp = await self.client.get(f'/stream/h264?proto={proto}&session={token}')
            self.assertEqual(resp.status, 400, proto)

    @unittest.skipUnless(shutil.which('ffmpeg'), 'FFmpeg não disponível')
    async def test_h264_v3_end_to_end_with_test_source(self):
        """HTTP real -> EncoderSession real -> FFmpeg real (fonte de teste no lugar da tela)."""
        from test_encoder_session import source
        from video_h264 import is_refresh, parse_v3_packet
        token = await self.auth()
        with patch('server.primary_monitor', return_value={**MON, 'width': 320, 'height': 180}), \
                patch('server.locate_ffmpeg', return_value=shutil.which('ffmpeg')), \
                patch('server.build_attempts', return_value=[('teste', source())]):
            resp = await self.client.get('/stream/h264?proto=3&session=' + token)
            self.assertEqual(resp.status, 200)
            head = await resp.content.readexactly(10)
            self.assertEqual(head[:4], b'OMH3')
            width, height, fps, _ = struct.unpack('!HHBB', head[4:])
            self.assertEqual((width, height), (320, 180))
            seqs = []
            for i in range(6):
                size = struct.unpack('!I', await resp.content.readexactly(4))[0]
                key, seq, ts, payload = parse_v3_packet(await resp.content.readexactly(size))
                seqs.append(seq)
                if i == 0:
                    self.assertTrue(key and is_refresh(payload))
            self.assertEqual(seqs, sorted(seqs))
            self.assertEqual(self.app['state']['h264_viewers'], 1)
            status = await (await self.client.get('/api/status', headers={'X-OpenMirror-Session': token})).json()
            self.assertEqual(status['encoder_active'], 'teste')
            self.assertGreater(status['bitrate_kbps'], 0)
            resp.close()
        import asyncio
        for _ in range(100):                      # ao fechar o cliente o FFmpeg tem de morrer
            if self.app['state']['h264_viewers'] == 0 and self.app['state']['encoder'] is None:
                break
            await asyncio.sleep(0.05)
        self.assertEqual(self.app['state']['h264_viewers'], 0)
        self.assertIsNone(self.app['state']['encoder'])

    # ---------------------------------------------------------------- MJPEG
    async def test_two_viewers_share_one_capture_worker(self):
        session = await self.auth()
        jpeg = b'\xff\xd8shared-frame\xff\xd9'
        monitor = {'left': 0, 'top': 0, 'width': 200, 'height': 100}
        with patch('server.capture_frame', return_value=(jpeg, monitor)) as grab:
            url = '/stream?session=' + session
            first = await self.client.get(url)
            second = await self.client.get(url)
            a = await first.content.read(100)
            b = await second.content.read(100)
            self.assertIn(jpeg, a)
            self.assertIn(jpeg, b)
            self.assertGreaterEqual(grab.call_count, 1)
            self.assertEqual(self.app['state']['streams'], 2)
            first.close()
            second.close()

    async def test_stream_requires_pairing_and_sends_jpeg(self):
        r = await self.client.get('/stream')
        self.assertEqual(r.status, 401)
        token = await self.auth()
        sample = b'\xff\xd8fakejpeg\xff\xd9'
        monitor = {'left': 0, 'top': 0, 'width': 200, 'height': 100}
        with patch('server.capture_frame', return_value=(sample, monitor)):
            r = await self.client.get('/stream?session=' + token)
            self.assertEqual(r.status, 200)
            block = await r.content.read(300)
            self.assertIn(b'Content-Type: image/jpeg', block)
            self.assertIn(sample, block)
            r.close()


if __name__ == '__main__':
    unittest.main()
