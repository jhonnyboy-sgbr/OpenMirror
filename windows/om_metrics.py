"""Contadores de taxa e de bytes. Medições reais do servidor, sem estimativas."""
from __future__ import annotations

import time
from collections import deque
from typing import Callable


class RateCounter:
    """Eventos por segundo numa janela deslizante (ex.: FPS de saída do encoder)."""

    def __init__(self, window: float = 2.0, clock: Callable[[], float] = time.monotonic) -> None:
        self.window = window
        self._clock = clock
        self._events: deque[tuple[float, int]] = deque()
        self.total = 0

    def tick(self, amount: int = 1) -> None:
        now = self._clock()
        self._events.append((now, amount))
        self.total += amount
        self._trim(now)

    def _trim(self, now: float) -> None:
        while self._events and now - self._events[0][0] > self.window:
            self._events.popleft()

    def per_second(self) -> float:
        now = self._clock()
        self._trim(now)
        if len(self._events) < 2:
            return 0.0
        span = max(1e-3, self._events[-1][0] - self._events[0][0])
        # o primeiro evento marca o início do intervalo: não entra na soma
        return round(sum(a for _, a in list(self._events)[1:]) / span, 1)

    def kbps(self) -> float:
        """Quando o contador conta bytes: taxa em kilobits por segundo."""
        return round(self.per_second() * 8 / 1000, 1)


def now_us() -> int:
    """Relógio monotônico do servidor em microssegundos (base do timesync)."""
    return time.monotonic_ns() // 1000


def make_pong(data: dict, server_us: int) -> dict:
    """Resposta ao `ping` do WebSocket. Devolve o `id`/`t` do cliente e o relógio do
    servidor (`s`, µs): com isso o cliente estima a diferença entre os dois relógios
    (estilo NTP) e a idade dos quadros. É estimativa, com erro de até ~RTT/2."""
    def num(value):
        return value if isinstance(value, (int, float)) and not isinstance(value, bool) else None

    return {'type': 'pong', 'id': num(data.get('id')), 't': num(data.get('t')), 's': int(server_us)}
