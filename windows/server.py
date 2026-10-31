"""OpenMirror 0.3.0 - servidor Windows/LAN.

Exclusivamente para rede local confiável. Transmite MJPEG e H.264/HTTP, recebe mouse e
teclado depois do pareamento por código. A lógica (sessões, validação de entrada, fila de
vídeo, descoberta) mora em módulos `om_*` / `video_h264` testáveis sem Windows; este arquivo
é só a camada HTTP/WebSocket.
"""
from __future__ import annotations

import argparse
import asyncio
import io
import json
import logging
import os
import socket
import sys
import threading
import time
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from aiohttp import web, WSMsgType

from om_controller import WindowsController
from om_discovery import start_discovery
from om_input import InputDispatcher, InputError
from om_meta import APP_NAME, CAPABILITIES, DISCOVERY_PORT, VERSION, VIDEO_PROTOCOLS
from om_metrics import RateCounter, make_pong, now_us
from om_security import PIN_LENGTH, PairLimiter, SessionStore, generate_pin, safe_equals
from video_h264 import (EncoderError, EncoderSession, build_attempts, check_encoder, framed,
                        framed_v3, header, header_v3, locate_ffmpeg, probe_ffmpeg)

BASE = Path(__file__).resolve().parent
if getattr(sys, 'frozen', False):
    BASE = Path(sys.executable).resolve().parent
WEB_FILE = BASE.parent / 'shared' / 'index.html'
LOGGER = logging.getLogger('openmirror')
_CAPTURE_LOCAL = threading.local()
STREAM_PATHS = ('/stream', '/ws/input', '/stream/h264')  # <img>/WebSocket do navegador não mandam header


def lan_ip() -> str:
    """IP LAN provável, sem enviar pacote para fora."""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.connect(('10.255.255.255', 1))
            return sock.getsockname()[0]
        finally:
            sock.close()
    except OSError:
        return '127.0.0.1'


def capture_frame(max_width: int, quality: int) -> tuple[bytes, dict[str, int]]:
    """Captura MJPEG com MSS persistente na thread dedicada (modo compatível)."""
    import mss
    from PIL import Image

    display = getattr(_CAPTURE_LOCAL, 'display', None)
    if display is None:
        display = mss.mss()
        _CAPTURE_LOCAL.display = display
    monitor = dict(display.monitors[1])
    shot = display.grab(monitor)
    frame = Image.frombytes('RGB', shot.size, shot.bgra, 'raw', 'BGRX')
    if frame.width > max_width:
        width = max_width
        height = max(1, round(frame.height * width / frame.width))
        frame = frame.resize((width, height), Image.Resampling.BILINEAR)
    output = io.BytesIO()
    frame.save(output, format='JPEG', quality=quality, optimize=False)
    return output.getvalue(), monitor


def primary_monitor() -> dict[str, int]:
    """Retângulo do monitor primário do Windows."""
    import ctypes
    user = ctypes.windll.user32  # type: ignore[attr-defined]
    try:
        user.SetProcessDPIAware()
    except Exception:  # noqa: BLE001
        pass
    return {'left': 0, 'top': 0, 'index': 0,
            'width': user.GetSystemMetrics(0), 'height': user.GetSystemMetrics(1)}


# ------------------------------------------------------------------ MJPEG
async def _producer(app: web.Application, executor: ThreadPoolExecutor) -> None:
    """Uma captura/JPEG por quadro para TODOS os espectadores MJPEG (sem fila)."""
    state = app['state']
    loop = asyncio.get_running_loop()
    while True:
        await state['wake'].wait()
        if state['streams'] == 0:
            state['wake'].clear()
            continue
        started = time.monotonic()
        try:
            data, monitor = await loop.run_in_executor(executor, capture_frame,
                                                       app['max_width'], app['quality'])
        except Exception:  # noqa: BLE001
            LOGGER.exception('Erro ao capturar vídeo')
            await asyncio.sleep(0.25)
            continue
        state['monitor'] = monitor
        state['frame'] = data
        state['sequence'] += 1
        state['frame_times'].append(time.monotonic())
        async with state['condition']:
            state['condition'].notify_all()
        await asyncio.sleep(max(0, 1 / app['fps'] - (time.monotonic() - started)))


async def _background(app: web.Application):
    """Tarefas de fundo: captura MJPEG, watchdog de botões presos e descoberta UDP."""
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='OpenMirrorCapture')
    tasks = [asyncio.create_task(_producer(app, executor))]

    async def watchdog() -> None:
        while True:
            await asyncio.sleep(1.0)
            if app['dispatcher'].tick():
                LOGGER.warning('Botão/tecla preso sem comandos: soltos automaticamente.')

    tasks.append(asyncio.create_task(watchdog()))
    transport = None
    if app['discovery']:
        try:
            transport, _ = await start_discovery(app['port'], port=app['discovery_port'])
        except OSError as exc:
            LOGGER.warning('Descoberta UDP indisponível (%s). O IP manual continua funcionando.', exc)
    try:
        yield
    finally:
        for task in tasks:
            task.cancel()
        for task in tasks:
            try:
                await task
            except asyncio.CancelledError:
                pass
        if transport is not None:
            transport.close()
        app['dispatcher'].release_all_sessions()
        app['sessions'].revoke_all()
        executor.shutdown(wait=True, cancel_futures=True)


def _actual_fps(state: dict[str, Any]) -> float:
    now = time.monotonic()
    frames = [t for t in state['frame_times'] if now - t <= 2]
    if len(frames) < 2:
        return 0.0
    return round((len(frames) - 1) / max(0.001, frames[-1] - frames[0]), 1)


# --------------------------------------------------------------- autenticação
def authenticated(request: web.Request) -> bool:
    token = session_token(request)
    return request.app['sessions'].valid(token)


def session_token(request: web.Request) -> str | None:
    token = request.headers.get('X-OpenMirror-Session')
    if not token and request.path in STREAM_PATHS:
        token = request.query.get('session')
    return token


async def index(request: web.Request) -> web.Response:
    return web.Response(text=WEB_FILE.read_text(encoding='utf-8'), content_type='text/html',
                        headers={'Cache-Control': 'no-store',
                                 'Content-Security-Policy': "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'"})


async def hello(request: web.Request) -> web.Response:
    """Negociação de versão/capacidades. Não exige sessão e não revela nada sensível."""
    return web.json_response({'app': APP_NAME, 'version': VERSION,
                              'video_protocols': list(VIDEO_PROTOCOLS),
                              'capabilities': list(CAPABILITIES),
                              'h264_available': bool(locate_ffmpeg(BASE)),
                              'auth': 'pin'})


async def pair(request: web.Request) -> web.Response:
    addr = request.remote or 'unknown'
    limiter: PairLimiter = request.app['limiter']
    if not limiter.allowed(addr):
        raise web.HTTPTooManyRequests(text='Muitas tentativas. Aguarde 1 minuto.')
    try:
        body = await request.json()
    except (ValueError, json.JSONDecodeError):
        raise web.HTTPBadRequest(text='JSON inválido.')
    if not isinstance(body, dict):
        raise web.HTTPBadRequest(text='JSON inválido.')
    if not safe_equals(body.get('pin'), request.app['pin']):
        limiter.record_failure(addr)
        raise web.HTTPUnauthorized(text='Código incorreto.')
    return web.json_response({'session': request.app['sessions'].create()})


async def disconnect(request: web.Request) -> web.Response:
    if not authenticated(request):
        raise web.HTTPUnauthorized()
    token = session_token(request)
    request.app['dispatcher'].release_all(token)
    request.app['sessions'].revoke(token)
    return web.json_response({'ok': True})


async def status(request: web.Request) -> web.Response:
    if not authenticated(request):
        raise web.HTTPUnauthorized()
    app = request.app
    state = app['state']
    encoder: EncoderSession | None = state['encoder']
    enc_stats = encoder.stats() if encoder else {}
    return web.json_response({
        'name': APP_NAME, 'version': VERSION,
        'video_protocols': list(VIDEO_PROTOCOLS), 'capabilities': list(CAPABILITIES),
        'h264_available': bool(locate_ffmpeg(BASE)), 'h264_viewers': state['h264_viewers'],
        'encoder': app['encoder'], 'capture': app['capture'],
        'encoder_active': enc_stats.get('encoder_active'),
        'capture_disabled': sorted(state['capture_disabled']),
        'capture_notes': list(state['capture_notes'])[-3:],
        'last_error': state['last_error'],
        # medições reais do servidor (não são estimativas):
        'actual_fps': _actual_fps(state),                  # captura MJPEG
        'encode_fps': enc_stats.get('encode_fps', 0.0),    # AUs saindo do FFmpeg
        'h264_fps': enc_stats.get('encode_fps', 0.0),      # nome da 0.2.0
        'sent_fps': state['sent'].per_second(),            # AUs gravadas no socket
        'bitrate_kbps': state['sent_bytes'].kbps(),        # bytes realmente enviados
        'dropped_frames': enc_stats.get('dropped', 0), 'backlog': enc_stats.get('backlog', 0),
        'fps': app['fps'], 'port': app['port'], 'quality': app['quality'], 'mode': 'mirror',
        'sessions': len(app['sessions']), 'uptime_s': round(time.monotonic() - state['started'])})


# -------------------------------------------------------------------- entrada
async def input_event(request: web.Request) -> web.Response:
    if not authenticated(request):
        raise web.HTTPUnauthorized()
    if request.content_length is not None and request.content_length > 2048:
        raise web.HTTPRequestEntityTooLarge(max_size=2048, actual_size=request.content_length)
    try:
        body = await request.json()
    except (ValueError, json.JSONDecodeError):
        raise web.HTTPBadRequest(text='JSON inválido.')
    try:
        request.app['dispatcher'].handle(body, session_token(request))
    except InputError as exc:
        return web.Response(status=exc.status, text=str(exc))
    return web.json_response({'ok': True})


async def ws_input(request: web.Request) -> web.WebSocketResponse:
    if not authenticated(request):
        raise web.HTTPUnauthorized()
    dispatcher: InputDispatcher = request.app['dispatcher']
    ws = web.WebSocketResponse(heartbeat=15, max_msg_size=2048)
    await ws.prepare(request)
    try:
        async for message in ws:
            if not authenticated(request):
                await ws.close(code=1008, message=b'Sessao revogada')
                break
            if message.type == WSMsgType.TEXT:
                try:
                    data = json.loads(message.data)
                except ValueError:
                    await ws.send_json({'type': 'error', 'message': 'JSON inválido'})
                    continue
                if isinstance(data, dict) and data.get('action') == 'ping':
                    await ws.send_json(make_pong(data, now_us()))
                    continue
                try:
                    dispatcher.handle(data, session_token(request))
                except InputError as exc:
                    if exc.status != 429:  # excesso de eventos: descarta em silêncio
                        await ws.send_json({'type': 'error', 'message': str(exc)})
                    continue
                if isinstance(data, dict) and isinstance(data.get('id'), int):
                    await ws.send_json({'type': 'ack', 'id': data['id']})
            elif message.type == WSMsgType.ERROR:
                break
    finally:
        dispatcher.release_all(session_token(request))  # solta apenas o estado desta sessão
    return ws


# ---------------------------------------------------------------------- MJPEG
async def stream(request: web.Request) -> web.StreamResponse:
    if not authenticated(request):
        raise web.HTTPUnauthorized()
    state = request.app['state']
    if state['streams'] >= 2:
        raise web.HTTPTooManyRequests(text='Limite de 2 transmissões simultâneas.')
    state['streams'] += 1
    state['wake'].set()
    response = web.StreamResponse(status=200, headers={
        'Content-Type': 'multipart/x-mixed-replace; boundary=frame',
        'Cache-Control': 'no-store, no-cache', 'Pragma': 'no-cache',
        'X-Content-Type-Options': 'nosniff'})
    try:
        await response.prepare(request)
        sequence = -1
        while authenticated(request):
            async with state['condition']:
                try:
                    await asyncio.wait_for(
                        state['condition'].wait_for(lambda: state['sequence'] != sequence), timeout=8)
                except asyncio.TimeoutError:
                    continue
                sequence = state['sequence']
                data = state['frame']
            if data is None:
                continue
            await response.write(b'--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ' +
                                 str(len(data)).encode() + b'\r\n\r\n' + data + b'\r\n')
    except (ConnectionResetError, BrokenPipeError, asyncio.CancelledError):
        pass
    except Exception:  # noqa: BLE001
        LOGGER.exception('Falha de captura/transmissão')
    finally:
        state['streams'] -= 1
        if state['streams'] == 0:
            state['wake'].clear()
    return response


# ---------------------------------------------------------------------- H.264
async def h264_stream(request: web.Request) -> web.StreamResponse:
    """Um espectador H.264 por vez. O FFmpeg só roda enquanto o celular está conectado.

    ?proto=2 (padrão, 0.2.0) ou ?proto=3 (com seq/timestamp por quadro).
    """
    if not authenticated(request):
        raise web.HTTPUnauthorized()
    try:
        proto = int(request.query.get('proto', '2'))
    except ValueError:
        raise web.HTTPBadRequest(text='Protocolo de vídeo inválido.')
    if proto not in VIDEO_PROTOCOLS:
        raise web.HTTPBadRequest(text=f'Protocolo de vídeo {proto} não suportado '
                                      f'(este servidor fala {list(VIDEO_PROTOCOLS)}). Atualize o app ou o Windows.')
    app = request.app
    state = app['state']
    async with state['h264_lock']:
        if state['h264_viewers']:
            raise web.HTTPTooManyRequests(text='Fluxo H.264 ocupado por outro dispositivo.')
        state['h264_viewers'] = 1
    session: EncoderSession | None = None
    output = None
    try:
        exe = locate_ffmpeg(BASE)
        if not exe:
            raise web.HTTPServiceUnavailable(text='FFmpeg não encontrado no Windows.')
        monitor = primary_monitor()
        if monitor['width'] <= 0 or monitor['height'] <= 0:
            raise web.HTTPServiceUnavailable(text='Monitor primário não disponível.')
        state['monitor'] = monitor
        scale = min(1.0, app['max_width'] / monitor['width'])
        width = max(2, int(monitor['width'] * scale) // 2 * 2)
        height = max(2, int(monitor['height'] * scale) // 2 * 2)
        attempts = build_attempts(exe, monitor=monitor, width=width, height=height, fps=app['fps'],
                                  bitrate_k=app['bitrate'], encoder=app['encoder'],
                                  capture=app['capture'], disabled=state['capture_disabled'])
        session = EncoderSession(attempts, backlog=app['backlog'], eager_flush=app['eager_flush'])
        state['encoder'] = session
        try:
            first, first_ts = await session.start()
        except EncoderError as exc:
            state['last_error'] = str(exc)
            LOGGER.error('Encoder não iniciou: %s', exc)
            raise web.HTTPServiceUnavailable(text=f'Não foi possível iniciar o vídeo: {exc}')
        for err in session.attempt_errors:  # captura que falhou não é tentada de novo nesta execução
            LOGGER.warning('Tentativa de vídeo falhou: %s', err)
            if err.startswith('ddagrab'):
                state['capture_disabled'].add('ddagrab')
                state['capture_notes'].append(err[:300])
        state['last_error'] = ''
        LOGGER.info('Vídeo ativo via %s (%dx%d, %d FPS alvo)', session.active_label, width, height, app['fps'])
        output = web.StreamResponse(headers={
            'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store',
            'X-Content-Type-Options': 'nosniff'})
        await output.prepare(request)

        async def send(data: bytes) -> None:
            await output.write(data)
            state['sent'].tick()
            state['sent_bytes'].tick(len(data))

        if proto == 3:
            await output.write(header_v3(width, height, app['fps']))
            await send(framed_v3(first, 0, first_ts, True))
        else:
            await output.write(header(width, height))
            await send(framed(first))
        while authenticated(request):
            try:
                item = await session.next_unit(timeout=6)
            except asyncio.TimeoutError:
                continue
            if item is None:
                break
            unit, key, ts, seq = item
            await send(framed_v3(unit, seq, ts, key) if proto == 3 else framed(unit))
    except (ConnectionResetError, BrokenPipeError, asyncio.CancelledError):
        pass
    finally:
        if session is not None:
            await session.stop()
        state['encoder'] = None
        state['h264_viewers'] = 0
    if output:
        return output
    raise web.HTTPServiceUnavailable(text='Falha ao iniciar codificador')


def make_app(pin: str, controller: Any = None, fps: int = 12, max_width: int = 1280,
             quality: int = 65, port: int = 8765, bitrate: int = 6000,
             encoder: str = 'software', capture: str = 'gdigrab', backlog: int = 3,
             eager_flush: bool = False, discovery: bool = False,
             discovery_port: int = DISCOVERY_PORT, session_idle: float = 1800.0) -> web.Application:
    app = web.Application(client_max_size=2048)
    app['pin'] = pin
    app['state'] = {'monitor': None, 'streams': 0, 'frame': None, 'sequence': 0,
                    'frame_times': deque(maxlen=150), 'condition': asyncio.Condition(),
                    'wake': asyncio.Event(), 'h264_viewers': 0, 'h264_lock': asyncio.Lock(),
                    'encoder': None, 'sent': RateCounter(), 'sent_bytes': RateCounter(),
                    'capture_disabled': set(), 'capture_notes': [], 'last_error': '',
                    'started': time.monotonic()}
    app['controller'] = controller if controller is not None else WindowsController()
    app['dispatcher'] = InputDispatcher(app['controller'], lambda: app['state']['monitor'])
    app['sessions'] = SessionStore(idle_timeout=session_idle)
    app['limiter'] = PairLimiter()
    app['fps'], app['max_width'], app['quality'] = fps, max_width, quality
    app['port'], app['bitrate'], app['encoder'] = port, bitrate, encoder
    app['capture'], app['backlog'], app['eager_flush'] = capture, backlog, eager_flush
    app['discovery'], app['discovery_port'] = discovery, discovery_port
    app.cleanup_ctx.append(_background)
    app.router.add_get('/', index)
    app.router.add_get('/api/hello', hello)
    app.router.add_post('/api/pair', pair)
    app.router.add_get('/api/status', status)
    app.router.add_post('/api/disconnect', disconnect)
    app.router.add_post('/api/input', input_event)
    app.router.add_get('/stream', stream)
    app.router.add_get('/stream/h264', h264_stream)
    app.router.add_get('/ws/input', ws_input)
    return app


def ssl_context(cert_file: str, key_file: str):
    """Cria o contexto TLS para HTTPS/WSS, exigindo certificado e chave explícitos."""
    import ssl
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert_file, key_file)
    return context


def run_probe() -> None:
    """`server.py --probe`: diz o que este FFmpeg/GPU realmente consegue (JSON)."""
    exe = locate_ffmpeg(BASE)
    if not exe:
        print(json.dumps({'error': 'FFmpeg não encontrado'}, ensure_ascii=False))
        return
    info = probe_ffmpeg(exe)
    info['libx264_works'] = check_encoder(exe, 'libx264')[0] if info['libx264'] else False
    if info['h264_amf']:
        ok, message = check_encoder(exe, 'h264_amf')
        info['h264_amf_works'], info['h264_amf_message'] = ok, message
    print(json.dumps(info, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description='OpenMirror Server')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--pin', default=None, help='(prefira a variável OPENMIRROR_PIN: --pin aparece na lista de processos)')
    parser.add_argument('--fps', type=int, default=30)
    parser.add_argument('--width', type=int, default=1280)
    parser.add_argument('--quality', type=int, default=65)
    parser.add_argument('--bitrate', type=int, default=6000)
    parser.add_argument('--encoder', choices=['software', 'amd'], default='software')
    parser.add_argument('--capture', choices=['gdigrab', 'ddagrab', 'auto'], default='gdigrab',
                        help='gdigrab = GDI (estável). ddagrab = DXGI (experimental, FFmpeg >= 6).')
    parser.add_argument('--backlog', type=int, default=3, help='quadros de folga antes de descartar')
    parser.add_argument('--eager-flush', action='store_true',
                        help='(experimental) não segura cada quadro até o AUD seguinte')
    parser.add_argument('--no-discovery', action='store_true', help='desliga a resposta UDP de descoberta')
    parser.add_argument('--session-minutes', type=int, default=30, help='expira sessão ociosa')
    parser.add_argument('--probe', action='store_true', help='mostra o que o FFmpeg/GPU suportam e sai')
    parser.add_argument('--tls-cert', help='certificado PEM para habilitar HTTPS/WSS')
    parser.add_argument('--tls-key', help='chave privada PEM correspondente ao certificado')
    args = parser.parse_args()
    if args.probe:
        run_probe()
        return
    if not (1024 <= args.port <= 65535 and 1 <= args.fps <= 60 and
            320 <= args.width <= 1920 and 30 <= args.quality <= 90 and
            1000 <= args.bitrate <= 30000 and 1 <= args.backlog <= 10 and
            1 <= args.session_minutes <= 720):
        parser.error('Parâmetros fora dos limites permitidos.')
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error('--tls-cert e --tls-key devem ser usados juntos.')
    pin = args.pin or os.environ.get('OPENMIRROR_PIN') or generate_pin()
    if len(pin) != PIN_LENGTH or not pin.isdecimal():
        parser.error(f'O código PIN precisa ter {PIN_LENGTH} dígitos.')
    print(f'\n========== OpenMirror {VERSION} ==========')
    scheme = 'https' if args.tls_cert else 'http'
    print(f'Endereço no celular: {scheme}://{lan_ip()}:{args.port}')
    print('Código de pareamento: exibido somente no painel do Windows.')
    print('Acesse SOMENTE pela sua rede Wi-Fi privada.\n', flush=True)
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    app = make_app(pin, fps=args.fps, max_width=args.width, quality=args.quality, port=args.port,
                   bitrate=args.bitrate, encoder=args.encoder, capture=args.capture,
                   backlog=args.backlog, eager_flush=args.eager_flush,
                   discovery=not args.no_discovery, session_idle=args.session_minutes * 60.0)
    tls = ssl_context(args.tls_cert, args.tls_key) if args.tls_cert else None
    web.run_app(app, host='0.0.0.0', port=args.port, access_log=None, print=None, ssl_context=tls)


if __name__ == '__main__':
    main()
