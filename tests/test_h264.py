"""Protocolo de vídeo (v2 e v3), fila de atraso, comandos do FFmpeg. Só stdlib + ffmpeg (opcional)."""
import shutil
import struct
import subprocess
import unittest

import _paths  # noqa: F401
from video_h264 import (AccessUnitFramer, BacklogQueue, build_attempts, check_encoder, framed,
                        framed_v3, h264_command, header, header_v3, is_refresh, nal_types,
                        parse_v3_packet, probe_ffmpeg)

MON = {'left': 0, 'top': 0, 'width': 1920, 'height': 1080}
KEY = b'\x00\x00\x00\x01\x09\xf0\x00\x00\x01\x67\x01\x00\x00\x01\x68\x02\x00\x00\x01\x65\x03'
P = b'\x00\x00\x01\x09\xf0\x00\x00\x01\x41\x11'


class ProtocolTests(unittest.TestCase):
    def test_v2_header_and_packet_unchanged(self):
        self.assertEqual(header(1280, 720), b'OMH2' + struct.pack('!HH', 1280, 720))
        self.assertEqual(framed(b'abcd'), struct.pack('!I', 4) + b'abcd')

    def test_v3_roundtrip(self):
        self.assertEqual(header_v3(1280, 720, 60), b'OMH3' + struct.pack('!HHBB', 1280, 720, 60, 0))
        wire = framed_v3(KEY, seq=7, ts_us=123456789012, key=True)
        size = struct.unpack('!I', wire[:4])[0]
        self.assertEqual(size, len(wire) - 4)
        key, seq, ts, payload = parse_v3_packet(wire[4:])
        self.assertEqual((key, seq, ts, payload), (True, 7, 123456789012, KEY))
        key, seq, *_ = parse_v3_packet(framed_v3(P, 2 ** 32 + 5, 1, False)[4:])
        self.assertEqual((key, seq), (False, 5))   # seq dá a volta em 32 bits

    def test_v3_rejects_bad_values(self):
        with self.assertRaises(ValueError):
            framed_v3(b'', 1, 1, False)
        with self.assertRaises(ValueError):
            header_v3(0, 720, 30)
        with self.assertRaises(ValueError):
            parse_v3_packet(b'short')

    def test_split_across_tcp_packets(self):
        au3 = b'\x00\x00\x01\x09\xf0\x00\x00\x01\x41\x22'
        framer = AccessUnitFramer()
        result = []
        sample = KEY + P + au3
        for step in (sample[:3], sample[3:9], sample[9:19], sample[19:25], sample[25:]):
            result.extend(framer.feed(step))
        self.assertEqual(result, [KEY, P])
        self.assertTrue(is_refresh(KEY))
        self.assertFalse(is_refresh(P))
        self.assertEqual(nal_types(KEY), {9, 7, 8, 5})

    def test_flush_releases_tail_only_when_it_starts_with_aud(self):
        f = AccessUnitFramer()
        self.assertIsNone(f.flush())
        f.feed(b'\x00\x00\x01\x41\x01')            # lixo antes de qualquer AUD
        self.assertIsNone(f.flush())
        f2 = AccessUnitFramer()
        f2.feed(KEY)
        self.assertTrue(f2.has_pending)
        self.assertEqual(f2.flush(), KEY)
        self.assertFalse(f2.has_pending)
        self.assertEqual(f2.feed(P + P), [P])      # continua alinhado depois do flush


class BacklogQueueTests(unittest.TestCase):
    def test_absorbs_jitter_without_dropping(self):
        q = BacklogQueue(limit=3)
        for i in range(3):
            self.assertEqual(q.push(bytes([i]), key=(i == 0)), 0)
        self.assertEqual([q.pop()[0] for _ in range(3)], [b'\x00', b'\x01', b'\x02'])
        self.assertEqual(q.dropped, 0)

    def test_overflow_on_p_drops_queue_and_waits_for_key(self):
        q = BacklogQueue(limit=2)
        q.push(b'K', True)
        q.push(b'p1', False)
        self.assertEqual(q.push(b'p2', False), 3)       # fila (2) + este quadro
        self.assertTrue(q.waiting_key)
        self.assertEqual(len(q), 0)
        self.assertEqual(q.push(b'p3', False), 1)        # P ignorado enquanto espera IDR
        self.assertEqual(q.push(b'K2', True), 0)         # IDR volta ao normal
        self.assertFalse(q.waiting_key)
        self.assertEqual(q.pop()[0], b'K2')              # primeiro entregue depois do corte é IDR
        self.assertEqual(q.dropped, 4)

    def test_key_with_full_queue_discards_obsolete_frames(self):
        q = BacklogQueue(limit=2)
        q.push(b'K', True)
        q.push(b'p1', False)
        self.assertEqual(q.push(b'K2', True), 2)
        self.assertEqual([q.pop()[0], q.pop()], [b'K2', None])

    def test_never_exceeds_limit(self):
        q = BacklogQueue(limit=3)
        for i in range(500):
            q.push(b'x', key=(i % 15 == 0))
            self.assertLessEqual(len(q), 3)


class CommandTests(unittest.TestCase):
    def test_software_options_unchanged(self):
        cmd = h264_command('ffmpeg', monitor=MON, width=960, height=540, fps=30, encoder='software')
        joined = ' '.join(cmd)
        for token in ('gdigrab', 'zerolatency', 'repeat-headers=1', 'aud=1', 'keyint=30'):
            self.assertIn(token, joined)
        self.assertIn('h264_amf', h264_command('ffmpeg', monitor=MON, width=960, height=540, fps=30, encoder='amd'))

    def test_amf_repeats_headers_and_inserts_aud(self):
        cmd = h264_command('ffmpeg', monitor=MON, width=960, height=540, fps=60, encoder='amd')
        self.assertIn('dump_extra=freq=keyframe,h264_metadata=aud=insert', cmd)

    def test_ddagrab_uses_dxgi_filter_and_monitor_index(self):
        cmd = h264_command('ffmpeg', monitor={**MON, 'index': 1}, width=1280, height=720, fps=60,
                           capture='ddagrab')
        joined = ' '.join(cmd)
        self.assertIn('ddagrab=output_idx=1:framerate=60:draw_mouse=1', joined)
        self.assertIn('hwdownload,format=bgra,scale=1280:720', joined)
        self.assertNotIn('gdigrab', joined)

    def test_keyint_follows_gop_seconds(self):
        cmd = h264_command('ffmpeg', monitor=MON, width=960, height=540, fps=60, keyint_s=0.5)
        self.assertIn('keyint=30', ' '.join(cmd))

    def test_validation(self):
        for kw in ({'fps': 0}, {'fps': 61}, {'width': 100}, {'bitrate_k': 10}, {'encoder': 'x'},
                   {'capture': 'x'}, {'keyint_s': 0}):
            args = dict(monitor=MON, width=960, height=540, fps=30)
            args.update(kw)
            with self.assertRaises(ValueError, msg=str(kw)):
                h264_command('ffmpeg', **args)

    def test_attempts_fall_back_to_most_compatible_last(self):
        a = build_attempts('ffmpeg', monitor=MON, width=960, height=540, fps=30, bitrate_k=6000,
                           encoder='amd', capture='auto')
        self.assertEqual([x[0] for x in a], ['ddagrab+amf', 'gdigrab+amf', 'gdigrab+x264'])
        a = build_attempts('ffmpeg', monitor=MON, width=960, height=540, fps=30, bitrate_k=6000,
                           encoder='software', capture='gdigrab')
        self.assertEqual([x[0] for x in a], ['gdigrab+x264'])
        a = build_attempts('ffmpeg', monitor=MON, width=960, height=540, fps=30, bitrate_k=6000,
                           encoder='software', capture='auto', disabled={'ddagrab'})
        self.assertEqual([x[0] for x in a], ['gdigrab+x264'])


@unittest.skipUnless(shutil.which('ffmpeg'), 'FFmpeg não disponível')
class RealFfmpegTests(unittest.TestCase):
    def test_real_ffmpeg_annex_b(self):
        cmd = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
               'testsrc2=size=320x180:rate=10', '-frames:v', '10', '-c:v', 'libx264',
               '-preset', 'ultrafast', '-tune', 'zerolatency', '-x264-params',
               'aud=1:repeat-headers=1:keyint=10', '-f', 'h264', 'pipe:1']
        result = subprocess.run(cmd, capture_output=True, timeout=15)
        if result.returncode != 0:
            self.skipTest('FFmpeg local não possui libx264')
        framer = AccessUnitFramer()
        units = []
        for i in range(0, len(result.stdout), 113):
            units.extend(framer.feed(result.stdout[i:i + 113]))
        self.assertGreaterEqual(len(units), 9)
        self.assertTrue(is_refresh(units[0]))

    def test_amf_style_bsf_chain_yields_self_contained_units(self):
        """A mesma cadeia de bitstream filters usada para o AMF, aplicada ao libx264, para
        provar que dump_extra+h264_metadata produz AU com AUD+SPS+PPS+IDR."""
        cmd = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
               'testsrc2=size=320x180:rate=10', '-frames:v', '12', '-c:v', 'libx264',
               '-preset', 'ultrafast', '-tune', 'zerolatency', '-g', '6', '-bf', '0',
               '-bsf:v', 'dump_extra=freq=keyframe,h264_metadata=aud=insert', '-f', 'h264', 'pipe:1']
        result = subprocess.run(cmd, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))
        framer = AccessUnitFramer()
        units = framer.feed(result.stdout)
        self.assertGreaterEqual(len(units), 10)
        self.assertTrue(is_refresh(units[0]))
        self.assertTrue(any(is_refresh(u) for u in units[1:]))   # IDRs seguintes também completos

    def test_probe_reports_what_this_build_lists(self):
        info = probe_ffmpeg(shutil.which('ffmpeg'))
        self.assertIsNone(info['error'])
        self.assertTrue(info['version'])
        self.assertTrue(info['libx264'])
        self.assertFalse(info['h264_amf'])     # Linux/sem GPU AMD
        self.assertFalse(info['ddagrab'])      # filtro só existe no Windows
        self.assertFalse(info['gdigrab'])

    def test_probe_with_missing_binary(self):
        info = probe_ffmpeg('/nao/existe/ffmpeg')
        self.assertIsNotNone(info['error'])

    def test_check_encoder_distinguishes_working_and_missing(self):
        ok, _ = check_encoder(shutil.which('ffmpeg'), 'libx264')
        self.assertTrue(ok)
        ok, msg = check_encoder(shutil.which('ffmpeg'), 'h264_amf')
        self.assertFalse(ok)
        self.assertTrue(msg)


if __name__ == '__main__':
    unittest.main()
