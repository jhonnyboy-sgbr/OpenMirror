"""EncoderSession com FFmpeg de verdade (fonte de teste no lugar da captura de tela)."""
import asyncio
import os
import shutil
import sys
import time
import unittest

import _paths  # noqa: F401
from video_h264 import EncoderError, EncoderSession, is_refresh

FFMPEG = shutil.which('ffmpeg')


def source(rate=30, gop=15, frames=None):
    cmd = [FFMPEG, '-hide_banner', '-loglevel', 'error', '-nostdin', '-re', '-f', 'lavfi', '-i',
           f'testsrc2=size=320x180:rate={rate}']
    if frames:
        cmd += ['-frames:v', str(frames)]
    return cmd + ['-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency', '-bf', '0',
                  '-g', str(gop), '-x264-params', f'aud=1:repeat-headers=1:scenecut=0:keyint={gop}:min-keyint={gop}',
                  '-f', 'h264', '-flush_packets', '1', 'pipe:1']


BROKEN = [sys.executable, '-c', "import sys; sys.stderr.write('encoder explodiu'); sys.exit(3)"]


def alive(pid):
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    # processo zumbi (morto e não colhido) também conta como "não vivo"
    try:
        with open(f'/proc/{pid}/stat') as f:
            return f.read().split(')')[-1].split()[0] != 'Z'
    except OSError:
        return False


@unittest.skipUnless(FFMPEG, 'FFmpeg não disponível')
class EncoderSessionTests(unittest.IsolatedAsyncioTestCase):
    async def test_first_unit_is_decodable_keyframe_and_stream_flows(self):
        s = EncoderSession([('x264', source())], first_timeout=10)
        first, ts = await s.start()
        try:
            self.assertTrue(is_refresh(first))
            self.assertGreater(ts, 0)
            self.assertEqual(s.active_label, 'x264')
            got = []
            for _ in range(20):
                got.append(await s.next_unit(timeout=3))
            self.assertTrue(all(g is not None for g in got))
            stamps = [g[2] for g in got]
            self.assertEqual(stamps, sorted(stamps))            # timestamps monotônicos
            self.assertTrue(any(g[1] for g in got))             # chegou outro IDR (gop=15)
            self.assertGreater(s.stats()['encode_fps'], 5)
        finally:
            await s.stop()

    async def test_falls_back_and_reports_why_the_first_attempt_failed(self):
        s = EncoderSession([('quebrado', BROKEN), ('x264', source())], first_timeout=10)
        first, _ = await s.start()
        try:
            self.assertEqual(s.active_label, 'x264')
            self.assertEqual(len(s.attempt_errors), 1)
            self.assertIn('quebrado', s.attempt_errors[0])
            self.assertIn('código 3', s.attempt_errors[0])
            self.assertIn('encoder explodiu', s.attempt_errors[0])   # stderr vai para o diagnóstico
        finally:
            await s.stop()

    async def test_all_attempts_failing_raises_with_every_reason(self):
        s = EncoderSession([('a', BROKEN), ('b', ['/nao/existe/ffmpeg', '-x'])], first_timeout=5)
        with self.assertRaises(EncoderError) as ctx:
            await s.start()
        text = str(ctx.exception)
        self.assertIn('a:', text)
        self.assertIn('b:', text)
        self.assertIn('não iniciou', text)

    async def test_timeout_when_encoder_never_emits_a_keyframe(self):
        silent = [sys.executable, '-c', 'import time; time.sleep(30)']
        s = EncoderSession([('mudo', silent)], first_timeout=0.5)
        t0 = time.monotonic()
        with self.assertRaises(EncoderError) as ctx:
            await s.start()
        self.assertLess(time.monotonic() - t0, 3)
        self.assertIn('a tempo', str(ctx.exception))
        self.assertFalse(alive(s.pid))                          # o processo mudo foi morto

    async def test_stop_kills_ffmpeg_no_orphans(self):
        s = EncoderSession([('x264', source())], first_timeout=10)
        await s.start()
        pid = s.pid
        self.assertTrue(alive(pid))
        await s.stop()
        self.assertFalse(alive(pid))
        await s.stop()                                          # idempotente

    async def test_slow_consumer_gets_a_keyframe_after_the_gap(self):
        """Consumidor parado por 1 s com 60 fps: o atraso NÃO pode se acumular."""
        s = EncoderSession([('x264', source(rate=60, gop=15))], backlog=3, first_timeout=10)
        await s.start()
        try:
            await asyncio.sleep(1.0)
            self.assertGreater(s.queue.dropped, 0)
            self.assertLessEqual(len(s.queue), 3)
            item = await s.next_unit(timeout=3)
            self.assertTrue(item[1], 'depois de um corte o primeiro quadro entregue tem de ser IDR')
        finally:
            await s.stop()

    async def test_seq_exposes_dropped_frames_to_the_client(self):
        """Quadros descartados na fila aparecem como LACUNA no seq (o cliente conta perdas)."""
        s = EncoderSession([('x264', source(rate=60, gop=15))], backlog=3, first_timeout=10)
        await s.start()                                          # seq 0 já foi entregue
        try:
            await asyncio.sleep(1.0)
            first = await s.next_unit(timeout=3)
            self.assertGreater(first[3], 1, 'depois de descartar, o seq tem que ter pulado')
            second = await s.next_unit(timeout=3)
            self.assertEqual(second[3], first[3] + 1)            # sem perda, seq é contíguo
            self.assertGreaterEqual(s.queue.dropped, first[3] - 1 - 3)
        finally:
            await s.stop()

    async def test_end_of_stream_returns_none(self):
        s = EncoderSession([('x264', source(rate=30, gop=10, frames=25))], first_timeout=10)
        await s.start()
        try:
            n = 0
            while True:
                item = await s.next_unit(timeout=4)
                if item is None:
                    break
                n += 1
            self.assertGreater(n, 10)
        finally:
            await s.stop()

    async def test_next_unit_times_out_when_idle(self):
        s = EncoderSession([('x264', source(rate=1, gop=1))], eager_flush=True, first_timeout=10)
        await s.start()
        try:
            await s.next_unit(timeout=3)                         # consome o que houver
            with self.assertRaises(asyncio.TimeoutError):
                await s.next_unit(timeout=0.1)
        finally:
            await s.stop()

    async def _time_to_second(self, eager):
        s = EncoderSession([('x264', source(rate=1, gop=1))], eager_flush=eager, first_timeout=10)
        t0 = time.monotonic()
        first, _ = await s.start()
        await s.next_unit(timeout=5)
        elapsed = time.monotonic() - t0
        await s.stop()
        return elapsed, first

    async def test_eager_flush_removes_the_one_frame_hold(self):
        """Sem eager, cada AU espera o AUD da seguinte: a 1 fps isso é 1 s; com eager some.
        (Medido em regime: o 1º quadro tem uma esquisitice de partida do `-re`, por isso
        comparamos o 2º.) A 60 fps o ganho real é 1 período de quadro = ~17 ms."""
        slow, first_slow = await self._time_to_second(False)
        fast, first_fast = await self._time_to_second(True)
        self.assertGreater(slow - fast, 0.7, f'normal={slow:.2f}s eager={fast:.2f}s')
        self.assertTrue(is_refresh(first_fast))
        self.assertTrue(is_refresh(first_slow))

    async def test_eager_flush_delivers_the_same_bytes(self):
        """Sem pausa no meio do pacote, eager e normal entregam AUs idênticas (sem truncar)."""
        async def collect(eager):
            s = EncoderSession([('x264', source(rate=30, gop=10, frames=40))], eager_flush=eager,
                               backlog=200, first_timeout=10)
            first, _ = await s.start()
            units = [first]
            try:
                while True:
                    item = await s.next_unit(timeout=4)
                    if item is None:
                        break
                    units.append(item[0])
            finally:
                await s.stop()
            return units

        normal = await collect(False)
        eager = await collect(True)
        # o encoder é determinístico: o conteúdo comum tem de ser byte a byte igual
        common = min(len(normal), len(eager)) - 1
        self.assertGreater(common, 25)
        for a, b in zip(normal[:common], eager[:common]):
            self.assertEqual(a, b)


if __name__ == '__main__':
    unittest.main()
