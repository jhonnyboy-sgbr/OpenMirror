"""Sessões, comparação segura e limite de tentativas. Só biblioteca padrão."""
from __future__ import annotations

import hmac
import secrets
import time
from collections import defaultdict, deque
from typing import Callable

PIN_LENGTH = 4


def safe_equals(a: object, b: object) -> bool:
    """Comparação em tempo constante que NUNCA levanta exceção.

    `hmac.compare_digest` com `str` não-ASCII levanta TypeError; na 0.2.0 isso
    virava HTTP 500 ao enviar um PIN como "é". Aqui comparamos bytes UTF-8.
    """
    if isinstance(a, str):
        a = a.encode('utf-8', 'surrogatepass')
    if isinstance(b, str):
        b = b.encode('utf-8', 'surrogatepass')
    if not isinstance(a, (bytes, bytearray)) or not isinstance(b, (bytes, bytearray)):
        return False
    return hmac.compare_digest(bytes(a), bytes(b))


def generate_pin() -> str:
    return f'{secrets.randbelow(10 ** PIN_LENGTH):0{PIN_LENGTH}d}'


class SessionStore:
    """Tokens de sessão com expiração por inatividade e por tempo total."""

    def __init__(self, idle_timeout: float = 1800.0, max_lifetime: float = 12 * 3600.0,
                 max_sessions: int = 4, clock: Callable[[], float] = time.monotonic) -> None:
        self.idle_timeout = idle_timeout
        self.max_lifetime = max_lifetime
        self.max_sessions = max_sessions
        self._clock = clock
        self._sessions: dict[str, list[float]] = {}  # token -> [criado, último uso]

    def create(self) -> str:
        self._purge()
        while len(self._sessions) >= self.max_sessions:
            oldest = min(self._sessions, key=lambda t: self._sessions[t][1])
            del self._sessions[oldest]
        token = secrets.token_urlsafe(32)
        now = self._clock()
        self._sessions[token] = [now, now]
        return token

    def valid(self, token: object, touch: bool = True) -> bool:
        if not isinstance(token, str) or not token:
            return False
        self._purge()
        found = None
        for stored in self._sessions:  # varre tudo: tempo não depende de qual acertou
            if safe_equals(token, stored):
                found = stored
        if found is None:
            return False
        if touch:
            self._sessions[found][1] = self._clock()
        return True

    def revoke(self, token: object) -> None:
        if isinstance(token, str):
            for stored in list(self._sessions):
                if safe_equals(token, stored):
                    del self._sessions[stored]

    def revoke_all(self) -> None:
        self._sessions.clear()

    def __len__(self) -> int:
        self._purge()
        return len(self._sessions)

    def _purge(self) -> None:
        now = self._clock()
        for token, (created, last) in list(self._sessions.items()):
            if now - last > self.idle_timeout or now - created > self.max_lifetime:
                del self._sessions[token]


class PairLimiter:
    """Limita tentativas de pareamento erradas: por IP e no total."""

    def __init__(self, per_ip: int = 8, total: int = 30, window: float = 60.0,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self.per_ip, self.total, self.window = per_ip, total, window
        self._clock = clock
        self._by_ip: dict[str, deque[float]] = defaultdict(deque)
        self._all: deque[float] = deque()

    def _trim(self) -> None:
        now = self._clock()
        while self._all and now - self._all[0] >= self.window:
            self._all.popleft()
        for ip in list(self._by_ip):
            q = self._by_ip[ip]
            while q and now - q[0] >= self.window:
                q.popleft()
            if not q:
                del self._by_ip[ip]

    def allowed(self, ip: str) -> bool:
        self._trim()
        return len(self._by_ip.get(ip, ())) < self.per_ip and len(self._all) < self.total

    def record_failure(self, ip: str) -> None:
        now = self._clock()
        self._by_ip[ip].append(now)
        self._all.append(now)
