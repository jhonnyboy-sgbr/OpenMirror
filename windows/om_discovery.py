"""Descoberta do PC na rede local por UDP. Só stdlib.

O celular manda um datagrama `OPENMIRROR_DISCOVER_V1` para o broadcast da rede
(porta 8766); o servidor responde com um JSON pequeno. NÃO vai PIN, sessão nem
qualquer segredo na resposta: só nome do PC, porta, versão e protocolos.
Para desligar: `server.py --no-discovery`.
"""
from __future__ import annotations

import asyncio
import json
import socket
import time
from collections import defaultdict, deque
from typing import Any, Callable, Optional

from om_meta import APP_NAME, CAPABILITIES, DISCOVERY_PORT, VERSION, VIDEO_PROTOCOLS

MAGIC = b'OPENMIRROR_DISCOVER_V1'
MAX_REPLIES_PER_SECOND = 5  # por origem: evita virar amplificador de tráfego


def build_reply(name: str, http_port: int) -> bytes:
    payload = {'app': APP_NAME, 'name': name[:63], 'port': http_port,
               'version': VERSION, 'video_protocols': list(VIDEO_PROTOCOLS),
               'capabilities': list(CAPABILITIES)}
    return json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def parse_reply(data: bytes) -> Optional[dict[str, Any]]:
    try:
        value = json.loads(data.decode('utf-8'))
    except (ValueError, UnicodeDecodeError):
        return None
    if isinstance(value, dict) and value.get('app') == APP_NAME and isinstance(value.get('port'), int):
        return value
    return None


class DiscoveryProtocol(asyncio.DatagramProtocol):
    def __init__(self, name: str, http_port: int,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self.reply = build_reply(name, http_port)
        self.transport: Optional[asyncio.DatagramTransport] = None
        self._clock = clock
        self._recent: dict[str, deque[float]] = defaultdict(deque)
        self.answered = 0

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        self.transport = transport  # type: ignore[assignment]

    def datagram_received(self, data: bytes, addr: tuple) -> None:
        if data.strip() != MAGIC or self.transport is None:
            return
        now = self._clock()
        q = self._recent[addr[0]]
        while q and now - q[0] > 1.0:
            q.popleft()
        if len(q) >= MAX_REPLIES_PER_SECOND:
            return
        q.append(now)
        self.answered += 1
        self.transport.sendto(self.reply, addr)


async def start_discovery(http_port: int, host: str = '0.0.0.0', port: int = DISCOVERY_PORT,
                          name: Optional[str] = None) -> tuple[asyncio.DatagramTransport, DiscoveryProtocol]:
    loop = asyncio.get_running_loop()
    protocol = DiscoveryProtocol(name or socket.gethostname(), http_port)
    transport, _ = await loop.create_datagram_endpoint(lambda: protocol, local_addr=(host, port))
    return transport, protocol  # type: ignore[return-value]
