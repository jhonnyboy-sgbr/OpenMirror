"""OpenMirror: vídeo H.264 sobre TCP, vindo de um processo FFmpeg.

Protocolo v2 ('OMH2', 0.2.0): cabeçalho  = b'OMH2' + u16 largura + u16 altura
                              cada pacote = u32 tamanho + 1 access unit Annex-B
Protocolo v3 ('OMH3', 0.3.0): cabeçalho  = b'OMH3' + u16 largura + u16 altura + u8 fps + u8 reservado
                              cada pacote = u32 tamanho + u8 flags + u32 seq + u64 ts_us + AU
    flags bit0 = quadro-chave (SPS+PPS+IDR). `seq` conta cada AU que saiu do encoder
    (lacunas = quadros descartados). `ts_us` = relógio monotônico do servidor quando a
    AU saiu do encoder; com o timesync do WebSocket o cliente estima a idade do quadro.
Tudo big-endian. O FFmpeg precisa estar no PATH ou em windows/tools/ffmpeg.exe.
"""
from __future__ import annotations

import asyncio
import shutil
import struct
import subprocess
from collections import deque
from pathlib import Path
from typing import Any, Iterable, Optional

from om_metrics import RateCounter, now_us

MAGIC = b'OMH2'
MAGIC_V3 = b'OMH3'
MAX_ACCESS_UNIT = 4 * 1024 * 1024
V3_META = 13  # flags(1) + seq(4) + ts(8)


# ------------------------------------------------------------------ protocolo
def header(width: int, height: int) -> bytes:
    if not (1 <= width <= 65535 and 1 <= height <= 65535):
        raise ValueError('Resolução inválida')
    return MAGIC + struct.pack('!HH', width, height)


def framed(frame: bytes) -> bytes:
    if not (0 < len(frame) <= MAX_ACCESS_UNIT):
        raise ValueError('Quadro inválido')
    return struct.pack('!I', len(frame)) + frame


def header_v3(width: int, height: int, fps: int) -> bytes:
    if not (1 <= width <= 65535 and 1 <= height <= 65535 and 1 <= fps <= 255):
        raise ValueError('Cabeçalho inválido')
    return MAGIC_V3 + struct.pack('!HHBB', width, height, fps, 0)


def framed_v3(frame: bytes, seq: int, ts_us: int, key: bool) -> bytes:
    if not (0 < len(frame) <= MAX_ACCESS_UNIT):
        raise ValueError('Quadro inválido')
    body = struct.pack('!BIQ', 1 if key else 0, seq & 0xFFFFFFFF, ts_us & 0xFFFFFFFFFFFFFFFF) + frame
    return struct.pack('!I', len(body)) + body


def parse_v3_packet(packet: bytes) -> tuple[bool, int, int, bytes]:
    """Inverso de framed_v3 (sem o u32 de tamanho). Usado nos testes e na doc."""
    if len(packet) <= V3_META:
        raise ValueError('Pacote v3 curto demais')
    flags, seq, ts_us = struct.unpack('!BIQ', packet[:V3_META])
    return bool(flags & 1), seq, ts_us, packet[V3_META:]


# --------------------------------------------------------------- Annex-B / AUD
def aud_positions(buffer: bytearray) -> list[int]:
    """Posições de AUD (NAL 9) em Annex-B; aceita start code de 3 ou 4 bytes."""
    pos = []
    start = 0
    while True:
        index = buffer.find(b'\x00\x00\x01\x09', start)
        if index == -1:
            break
        index = index - 1 if index > 0 and buffer[index - 1] == 0 else index
        if not pos or index != pos[-1]:
            pos.append(index)
        start = index + 4
    return pos


class AccessUnitFramer:
    """Separa access units completas (delimitadas por AUD) de um fluxo Annex-B."""

    def __init__(self) -> None:
        self.pending = bytearray()

    def feed(self, part: bytes) -> list[bytes]:
        self.pending.extend(part)
        positions = aud_positions(self.pending)
        if not positions:
            if len(self.pending) > MAX_ACCESS_UNIT:
                self.pending.clear()
                raise ValueError('Fluxo H.264 sem marcadores AUD')
            return []
        if positions[0] > 0:
            del self.pending[:positions[0]]
            positions = [p - positions[0] for p in positions]
        if len(positions) == 1:
            if len(self.pending) > MAX_ACCESS_UNIT:
                self.pending.clear()
                raise ValueError('Quadro H.264 excedeu o limite')
            return []
        output = [bytes(self.pending[a:b]) for a, b in zip(positions, positions[1:])]
        del self.pending[:positions[-1]]
        if any(len(chunk) > MAX_ACCESS_UNIT for chunk in output):
            raise ValueError('Quadro H.264 excedeu o limite')
        return output

    @property
    def has_pending(self) -> bool:
        return bool(self.pending)

    def flush(self) -> Optional[bytes]:
        """Entrega a AU que está pela metade-do-buffer como se estivesse completa.

        Normalmente uma AU só é liberada quando o AUD da próxima aparece, o que segura
        cada quadro por um período de quadro (17-33 ms). Com o modo `eager_flush` o
        EncoderSession chama isto quando o FFmpeg fica quieto por alguns ms (ele grava
        cada pacote de uma vez e descarrega). Se o FFmpeg for interrompido NO MEIO de um
        pacote, o quadro sai truncado: por isso o modo é opcional."""
        positions = aud_positions(self.pending)
        if not positions or positions[0] != 0:
            return None
        unit = bytes(self.pending)
        self.pending.clear()
        return unit


def nal_types(unit: bytes) -> set[int]:
    result = set()
    i = 0
    while True:
        start = unit.find(b'\x00\x00\x01', i)
        if start < 0 or start + 3 >= len(unit):
            break
        result.add(unit[start + 3] & 0x1f)
        i = start + 4
    return result


def is_refresh(unit: bytes) -> bool:
    """True se a AU sozinha inicia um decoder: SPS + PPS + IDR."""
    types = nal_types(unit)
    return 7 in types and 8 in types and 5 in types


# -------------------------------------------------------------- fila com limite
class BacklogQueue:
    """Fila curta que troca "atraso acumulado" por "descartar até o próximo IDR".

    * Até `limit` quadros ficam na fila: absorvem o tremido normal do Wi-Fi sem
      congelar a imagem (na 0.2.0 a fila tinha 1 posição e qualquer soluço virava
      até 1 s de imagem parada).
    * Estourou o limite com um quadro P: tudo que estava na fila é descartado (a
      referência se perdeu) e os próximos P são ignorados até chegar um IDR.
    * Chegou um IDR com a fila cheia: o que estava na fila é obsoleto, descarta.
    """

    def __init__(self, limit: int = 3) -> None:
        self.limit = max(1, int(limit))
        self._items: deque[tuple[bytes, bool, int, int]] = deque()
        self._waiting_key = False
        self.dropped = 0

    def __len__(self) -> int:
        return len(self._items)

    @property
    def waiting_key(self) -> bool:
        return self._waiting_key

    def push(self, data: bytes, key: bool, ts_us: int = 0, seq: int = 0) -> int:
        """Retorna quantos quadros este push descartou."""
        if self._waiting_key:
            if not key:
                self.dropped += 1
                return 1
            self._waiting_key = False
        dropped = 0
        if len(self._items) >= self.limit:
            if key:
                dropped = len(self._items)
                self._items.clear()
            else:
                dropped = len(self._items) + 1
                self._items.clear()
                self._waiting_key = True
                self.dropped += dropped
                return dropped
        self._items.append((data, key, ts_us, seq))
        self.dropped += dropped
        return dropped

    def pop(self) -> Optional[tuple[bytes, bool, int, int]]:
        return self._items.popleft() if self._items else None


# ------------------------------------------------------------------- FFmpeg
def locate_ffmpeg(base: Path) -> Optional[str]:
    candidates = (
        base / 'tools' / 'ffmpeg.exe',
        base.parent / 'windows' / 'tools' / 'ffmpeg.exe',
    )
    for bundled in candidates:
        if bundled.is_file():
            return str(bundled)
    return shutil.which('ffmpeg')


def _names(text: str) -> set[str]:
    return {tok for line in text.splitlines() for tok in line.split()[:3]}


def probe_ffmpeg(exe: str, timeout: float = 8.0) -> dict[str, Any]:
    """O que este FFmpeg *lista*. Listar o encoder não prova que a GPU o aceita:
    use `check_encoder` para isso."""
    info: dict[str, Any] = {'path': exe, 'version': None, 'libx264': False,
                            'h264_amf': False, 'ddagrab': False, 'gdigrab': False, 'error': None}

    def run(*args: str) -> str:
        return subprocess.run([exe, '-hide_banner', *args], capture_output=True, text=True,
                              timeout=timeout, errors='replace').stdout

    try:
        first = (run('-version').splitlines() or [''])[0]
        parts = first.split()
        info['version'] = parts[2] if first.startswith('ffmpeg version') and len(parts) > 2 else first
        encoders = _names(run('-encoders'))
        info['libx264'] = 'libx264' in encoders
        info['h264_amf'] = 'h264_amf' in encoders
        info['ddagrab'] = 'ddagrab' in _names(run('-filters'))
        info['gdigrab'] = 'gdigrab' in _names(run('-devices'))
    except (OSError, subprocess.SubprocessError) as exc:
        info['error'] = str(exc)
    return info


def check_encoder(exe: str, encoder: str, timeout: float = 15.0) -> tuple[bool, str]:
    """Codifica 1 quadro de teste de verdade. É assim que se sabe se o AMF funciona."""
    cmd = [exe, '-hide_banner', '-loglevel', 'error', '-nostdin', '-f', 'lavfi', '-i',
           'color=c=black:size=256x256:rate=5', '-frames:v', '3', '-pix_fmt', 'yuv420p',
           '-c:v', encoder, '-f', 'null', '-']
    try:
        done = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, errors='replace')
    except (OSError, subprocess.SubprocessError) as exc:
        return False, str(exc)
    return done.returncode == 0, (done.stderr or '').strip()[-300:]


def h264_command(ffmpeg: str, *, monitor: dict[str, int], width: int, height: int,
                 fps: int, encoder: str = 'software', bitrate_k: int = 6000,
                 capture: str = 'gdigrab', keyint_s: float = 1.0) -> list[str]:
    """Linha de comando do FFmpeg.

    capture='gdigrab' : captura por GDI (a da 0.2.0). Funciona sempre, mas é a mais pesada.
    capture='ddagrab' : captura por DXGI Desktop Duplication (FFmpeg >= 6.0). NÃO VALIDADA
                        no Windows ainda; o servidor cai para gdigrab se ela falhar.
    AMF é opt-in: depende de driver, API gráfica e build do FFmpeg.
    """
    if encoder not in {'software', 'amd'}:
        raise ValueError('Encoder inválido')
    if capture not in {'gdigrab', 'ddagrab'}:
        raise ValueError('Captura inválida')
    if not (1 <= fps <= 60 and 320 <= width <= 1920 and 1 <= height <= 1080
            and 1000 <= bitrate_k <= 30000 and 0.25 <= keyint_s <= 5):
        raise ValueError('Configuração de vídeo inválida')
    gop = max(1, round(fps * keyint_s))
    args = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin']
    if capture == 'ddagrab':
        index = int(monitor.get('index', 0))
        args += ['-f', 'lavfi', '-i', f'ddagrab=output_idx={index}:framerate={fps}:draw_mouse=1',
                 '-an', '-vf',
                 f'hwdownload,format=bgra,scale={width}:{height}:flags=fast_bilinear,format=yuv420p']
    else:
        args += ['-f', 'gdigrab', '-framerate', str(fps), '-draw_mouse', '1',
                 '-offset_x', str(monitor['left']), '-offset_y', str(monitor['top']),
                 '-video_size', f"{monitor['width']}x{monitor['height']}",
                 '-i', 'desktop', '-an', '-vf', f'scale={width}:{height}:flags=fast_bilinear',
                 '-pix_fmt', 'yuv420p']
    if encoder == 'amd':
        # dump_extra repete SPS/PPS em cada IDR (o AMF pode deixá-los só no extradata,
        # e sem eles o cliente nunca consegue iniciar o MediaCodec).
        args += ['-c:v', 'h264_amf', '-usage', 'ultralowlatency', '-quality', 'speed',
                 '-rc', 'cbr', '-b:v', f'{bitrate_k}k', '-maxrate', f'{bitrate_k}k',
                 '-bufsize', f'{bitrate_k}k', '-g', str(gop), '-bf', '0',
                 '-bsf:v', 'dump_extra=freq=keyframe,h264_metadata=aud=insert']
    else:
        args += ['-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency',
                 '-b:v', f'{bitrate_k}k', '-maxrate', f'{bitrate_k}k',
                 '-bufsize', f'{bitrate_k}k', '-g', str(gop), '-bf', '0',
                 '-x264-params', f'aud=1:repeat-headers=1:scenecut=0:keyint={gop}:min-keyint={gop}']
    return args + ['-f', 'h264', '-flush_packets', '1', 'pipe:1']


def build_attempts(ffmpeg: str, *, monitor: dict[str, int], width: int, height: int, fps: int,
                   bitrate_k: int, encoder: str, capture: str,
                   disabled: Iterable[str] = ()) -> list[tuple[str, list[str]]]:
    """Lista ordenada de (rótulo, comando) a tentar; a última é sempre a mais compatível."""
    blocked = set(disabled)
    captures = {'auto': ['ddagrab', 'gdigrab'], 'ddagrab': ['ddagrab', 'gdigrab'],
                'gdigrab': ['gdigrab']}[capture]
    captures = [c for c in captures if c not in blocked] or ['gdigrab']
    combos: list[tuple[str, str]] = [(c, encoder) for c in captures]
    if ('gdigrab', 'software') not in combos:
        combos.append(('gdigrab', 'software'))
    attempts = []
    for cap, enc in combos:
        label = f"{cap}+{'x264' if enc == 'software' else 'amf'}"
        attempts.append((label, h264_command(ffmpeg, monitor=monitor, width=width, height=height,
                                             fps=fps, bitrate_k=bitrate_k, encoder=enc, capture=cap)))
    return attempts


class EncoderError(RuntimeError):
    pass


class EncoderSession:
    """Um processo FFmpeg por espectador, com fallback entre tentativas.

    `start()` percorre `attempts` até uma produzir um quadro-chave completo e devolve
    esse primeiro quadro. Depois, `next_unit()` entrega os quadros seguintes através de
    uma `BacklogQueue`. `stop()` sempre mata o processo (sem processos órfãos).
    """

    def __init__(self, attempts: list[tuple[str, list[str]]], *, backlog: int = 3,
                 first_timeout: float = 12.0, eager_flush: bool = False,
                 eager_ms: float = 4.0) -> None:
        self.eager_flush = eager_flush
        self.eager_s = eager_ms / 1000.0
        self.attempts = list(attempts)
        self.queue = BacklogQueue(backlog)
        self.first_timeout = first_timeout
        self.active_label: Optional[str] = None
        self.attempt_errors: list[str] = []
        self.last_error = ''
        self.encoded = RateCounter()
        self.pid: Optional[int] = None
        self._proc: Optional[asyncio.subprocess.Process] = None
        self._tasks: list[asyncio.Task] = []
        self._ready = asyncio.Event()
        self._eof = False
        self._stderr_tail = bytearray()
        self._first: Optional[asyncio.Future] = None
        self._seq = 0

    # ------------------------------------------------------------------ início
    async def start(self) -> tuple[bytes, int]:
        errors: list[str] = []
        for label, argv in self.attempts:
            try:
                first = await self._attempt(argv)
            except EncoderError as exc:
                errors.append(f'{label}: {exc}')
                await self._teardown()
                continue
            self.active_label = label
            self.attempt_errors = errors
            return first
        self.attempt_errors = errors
        self.last_error = ' | '.join(errors) or 'nenhum encoder configurado'
        raise EncoderError(self.last_error)

    def _tail(self) -> str:
        text = self._stderr_tail.decode('utf-8', 'replace').strip()
        return f' [{text[-300:]}]' if text else ''

    async def _attempt(self, argv: list[str]) -> tuple[bytes, int]:
        self.queue = BacklogQueue(self.queue.limit)
        self._eof = False
        self._seq = 0
        self._ready.clear()
        self._stderr_tail.clear()
        try:
            self._proc = await asyncio.create_subprocess_exec(
                *argv, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        except (OSError, ValueError) as exc:
            raise EncoderError(f'não iniciou ({exc})')
        self.pid = self._proc.pid
        self._first = asyncio.get_running_loop().create_future()
        self._tasks = [asyncio.create_task(self._read_stdout()),
                       asyncio.create_task(self._drain_stderr())]
        try:
            first = await asyncio.wait_for(self._first, timeout=self.first_timeout)
        except asyncio.TimeoutError:
            raise EncoderError('não produziu vídeo a tempo' + self._tail())
        if first is None:
            try:
                code = await asyncio.wait_for(self._proc.wait(), 2)
            except asyncio.TimeoutError:
                code = '?'
            try:  # deixa o dreno de stderr ler o que o FFmpeg disse antes de morrer
                await asyncio.wait_for(asyncio.shield(self._tasks[1]), 1)
            except (asyncio.TimeoutError, Exception):  # noqa: BLE001
                pass
            raise EncoderError(f'encerrou sem vídeo (código {code})' + self._tail())
        return first

    def _emit(self, unit: bytes) -> None:
        first = self._first
        assert first is not None
        ts = now_us()
        key = is_refresh(unit)
        self.encoded.tick()
        if not first.done():
            if key:
                first.set_result((unit, ts))
                self._seq = 1            # o primeiro quadro entregue por start() é o seq 0
            return
        self.queue.push(unit, key, ts, self._seq)
        self._seq += 1
        self._ready.set()

    async def _read_stdout(self) -> None:
        framer = AccessUnitFramer()
        proc, first = self._proc, self._first
        assert proc is not None and proc.stdout is not None and first is not None
        try:
            while True:
                if self.eager_flush and framer.has_pending:
                    try:
                        chunk = await asyncio.wait_for(proc.stdout.read(65536), self.eager_s)
                    except asyncio.TimeoutError:
                        tail = framer.flush()  # FFmpeg ficou quieto: o pacote terminou
                        if tail:
                            self._emit(tail)
                        chunk = await proc.stdout.read(65536)
                else:
                    chunk = await proc.stdout.read(65536)
                if not chunk:
                    break
                for unit in framer.feed(chunk):
                    self._emit(unit)
        except (OSError, ValueError) as exc:
            self.last_error = f'leitura do encoder: {exc}'
        finally:
            self._eof = True
            if not first.done():
                first.set_result(None)
            self._ready.set()

    async def _drain_stderr(self) -> None:
        proc = self._proc
        assert proc is not None and proc.stderr is not None
        try:
            while True:
                chunk = await proc.stderr.read(4096)
                if not chunk:
                    break
                self._stderr_tail += chunk
                if len(self._stderr_tail) > 4096:
                    del self._stderr_tail[:-4096]
        except OSError:
            pass

    # ----------------------------------------------------------------- consumo
    async def next_unit(self, timeout: float = 6.0) -> Optional[tuple[bytes, bool, int, int]]:
        """(AU, é_chave, ts_us, seq); None quando o encoder terminou; TimeoutError se ocioso."""
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while True:
            item = self.queue.pop()
            if item is not None:
                return item
            if self._eof:
                return None
            self._ready.clear()
            remaining = deadline - loop.time()
            if remaining <= 0:
                raise asyncio.TimeoutError
            await asyncio.wait_for(self._ready.wait(), remaining)

    # ---------------------------------------------------------------------- fim
    async def stop(self) -> None:
        await self._teardown()

    async def _teardown(self) -> None:
        tasks, self._tasks = self._tasks, []
        for task in tasks:
            task.cancel()
        for task in tasks:
            try:
                await task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        proc, self._proc = self._proc, None
        if proc is not None and proc.returncode is None:
            try:
                proc.kill()
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(proc.wait(), 5)
            except asyncio.TimeoutError:
                pass

    def stats(self) -> dict[str, Any]:
        return {'encoder_active': self.active_label, 'encode_fps': self.encoded.per_second(),
                'dropped': self.queue.dropped, 'backlog': len(self.queue),
                'attempt_errors': list(self.attempt_errors)}
