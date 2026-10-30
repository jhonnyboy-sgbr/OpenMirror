"""Compila e executa no JVM a lógica Android que não depende de android.* (UnitTests e NetTests).

NÃO compila as Activities nem o StreamSession (dependem do SDK do Android): isso é o
`scripts/check_android_java.py`, com stubs, e o build de verdade é no Android Studio.
"""
import asyncio
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path

import _paths  # noqa: F401
from mini_ws_server import MiniWsServer
from om_discovery import build_reply, start_discovery
from om_meta import APP_NAME, CAPABILITIES, VERSION, VIDEO_PROTOCOLS
from om_metrics import make_pong, now_us

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'android' / 'app' / 'src' / 'main' / 'java' / 'com' / 'openmirror' / 'client'
TESTS = Path(__file__).resolve().parent / 'java' / 'com' / 'openmirror' / 'client'
PURE_EXCLUDE = ('Activity', 'StreamSession', 'Immersive', 'OpenMirrorApp')


def javac_cmd():
    if shutil.which('javac'):
        return ['javac']
    if shutil.which('java'):
        probe = subprocess.run(['java', '--list-modules'], capture_output=True, text=True)
        if 'jdk.compiler' in probe.stdout:
            return ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    return None


JAVAC = javac_cmd()


@unittest.skipUnless(JAVAC, 'JDK (javac) não disponível')
class AndroidLogicOnJvm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='om_java_'))
        cls.out = cls.tmp / 'classes'
        cls.out.mkdir()
        sources = [str(p) for p in sorted(SRC.glob('*.java')) if not any(x in p.name for x in PURE_EXCLUDE)]
        sources += [str(TESTS / 'UnitTests.java'), str(TESTS / 'NetTests.java')]
        done = subprocess.run(JAVAC + ['-Xlint:all', '-d', str(cls.out)] + sources,
                              capture_output=True, text=True)
        if done.returncode != 0:
            raise RuntimeError('Falha ao compilar o Java:\n' + done.stdout + done.stderr)
        cls.fixtures = cls.tmp / 'fixtures'
        cls.fixtures.mkdir()
        cls.make_fixtures()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @classmethod
    def make_fixtures(cls):
        f = cls.fixtures
        hello = {'app': APP_NAME, 'version': VERSION, 'video_protocols': list(VIDEO_PROTOCOLS),
                 'capabilities': list(CAPABILITIES), 'h264_available': True, 'auth': 'pin'}
        (f / 'hello.json').write_text(json.dumps(hello, ensure_ascii=False), encoding='utf-8')
        (f / 'discovery.json').write_bytes(build_reply('PC-DO-JOÃO', 8765))
        (f / 'pong.json').write_text(json.dumps(make_pong({'id': 7, 't': 123456789012}, now_us())), encoding='utf-8')
        ffmpeg = shutil.which('ffmpeg')
        if not ffmpeg:
            return
        from test_encoder_session import source
        from video_h264 import EncoderSession, is_refresh

        async def grab():
            s = EncoderSession([('x264', source(rate=30, gop=15))], first_timeout=10)
            key, _ = await s.start()
            try:
                while True:
                    unit, is_key, _, _ = await s.next_unit(timeout=4)
                    if not is_key:
                        return key, unit
            finally:
                await s.stop()

        key, p = asyncio.run(grab())
        import re

        def nals(data):  # varredura independente da usada no código de produção
            return [data[m.end()] & 0x1f for m in re.finditer(b'\x00\x00\x01', data)]

        (f / 'au_key.bin').write_bytes(key)
        (f / 'au_p.bin').write_bytes(p)
        (f / 'au_key.nals').write_text(str(nals(key)))
        (f / 'au_p.nals').write_text(str(nals(p)))

    def run_java(self, *args):
        done = subprocess.run(['java', '-cp', str(self.out), *args], capture_output=True, text=True, timeout=60)
        fails = [l for l in done.stdout.splitlines() if l.startswith('FAIL')]
        passed = [l for l in done.stdout.splitlines() if l.startswith('PASS')]
        return done, passed, fails

    def test_unit_tests(self):
        done, passed, fails = self.run_java('com.openmirror.client.UnitTests', str(self.fixtures))
        self.assertEqual(fails, [], '\n'.join(fails) + done.stderr)
        self.assertEqual(done.returncode, 0, done.stdout[-500:] + done.stderr)
        self.assertGreater(len(passed), 140)

    def _run_in_loop(self, coro_factory):
        loop = asyncio.new_event_loop()
        ready = threading.Event()
        holder = {}

        def runner():
            asyncio.set_event_loop(loop)
            holder['obj'] = loop.run_until_complete(coro_factory())
            ready.set()
            loop.run_forever()

        t = threading.Thread(target=runner, daemon=True)
        t.start()
        self.assertTrue(ready.wait(5))
        return loop, t, holder['obj']

    def test_websocket_client_against_independent_server(self):
        server = MiniWsServer()
        loop, thread, _ = self._run_in_loop(lambda: _start(server))
        try:
            done, passed, fails = self.run_java('com.openmirror.client.NetTests', 'ws', '127.0.0.1', str(server.port))
        finally:
            asyncio.run_coroutine_threadsafe(server.stop(), loop).result(5)
            loop.call_soon_threadsafe(loop.stop)
            thread.join(3)
        self.assertEqual(fails, [], '\n'.join(fails) + done.stderr)
        self.assertEqual(done.returncode, 0, done.stdout[-500:] + done.stderr)
        self.assertGreaterEqual(len(passed), 10)
        self.assertEqual(server.errors, [])          # todo quadro do cliente veio mascarado, pong correto
        self.assertGreaterEqual(server.pongs, 1)

    def test_discovery_client_against_real_responder(self):
        async def start():
            return await start_discovery(8765, host='127.0.0.1', port=0, name='PC-DO-JOÃO')

        loop, thread, (transport, proto) = self._run_in_loop(start)
        try:
            port = transport.get_extra_info('sockname')[1]
            done, passed, fails = self.run_java('com.openmirror.client.NetTests', 'discovery', str(port))
        finally:
            loop.call_soon_threadsafe(transport.close)
            loop.call_soon_threadsafe(loop.stop)
            thread.join(3)
        self.assertEqual(fails, [], '\n'.join(fails) + done.stderr)
        self.assertEqual(done.returncode, 0, done.stdout[-500:] + done.stderr)
        self.assertGreaterEqual(proto.answered, 1)


async def _start(server):
    await server.start()
    return server


if __name__ == '__main__':
    unittest.main()
