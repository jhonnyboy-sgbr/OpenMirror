"""Validação e despacho dos comandos de entrada (mouse/teclado). Só stdlib.

O servidor HTTP/WebSocket apenas repassa o JSON recebido para `InputDispatcher`.
Toda a regra (limites, lista de teclas permitidas, botões presos) fica aqui, onde
dá para testar no Linux sem Windows e sem aiohttp.

Interface esperada do `controller` (WindowsController ou um fake de teste):
    move(x, y, monitor)        x/y normalizados 0..1
    button(name, down)         name: 'left' | 'right' | 'middle'
    wheel(dy, dx)              unidades de 1/120 de "clique" (120 = 1 notch)
    key_event(vk, extended, down)
    type_text(text)
"""
from __future__ import annotations

import math
import time
from collections import deque
from typing import Any, Callable, Optional

MOUSE_BUTTONS = ('left', 'right', 'middle')
MAX_TEXT = 250
MAX_WHEEL = 3600
MAX_COMBO = 4
HOLD_TIMEOUT = 30.0  # s com botão/tecla preso e sem nenhum comando => solta tudo


class InputError(ValueError):
    """Comando recusado. `status` segue o HTTP equivalente (400, 429, 503)."""

    def __init__(self, message: str, status: int = 400) -> None:
        super().__init__(message)
        self.status = status


def _build_keys() -> dict[str, tuple[int, bool]]:
    keys: dict[str, tuple[int, bool]] = {
        'backspace': (0x08, False), 'tab': (0x09, False), 'enter': (0x0D, False),
        'shift': (0x10, False), 'ctrl': (0x11, False), 'alt': (0x12, False),
        'pause': (0x13, False), 'capslock': (0x14, False), 'escape': (0x1B, False),
        'space': (0x20, False),
        'pageup': (0x21, True), 'pagedown': (0x22, True), 'end': (0x23, True),
        'home': (0x24, True), 'left': (0x25, True), 'up': (0x26, True),
        'right': (0x27, True), 'down': (0x28, True),
        'printscreen': (0x2C, True), 'insert': (0x2D, True), 'delete': (0x2E, True),
        'win': (0x5B, True),
        'volumemute': (0xAD, False), 'volumedown': (0xAE, False), 'volumeup': (0xAF, False),
        'medianext': (0xB0, False), 'mediaprev': (0xB1, False),
        'mediastop': (0xB2, False), 'mediaplay': (0xB3, False),
    }
    for i in range(10):
        keys[str(i)] = (0x30 + i, False)
    for i in range(26):
        keys[chr(ord('a') + i)] = (0x41 + i, False)
    for i in range(1, 13):
        keys[f'f{i}'] = (0x6F + i, False)
    return keys


KEYS = _build_keys()
ALIASES = {'esc': 'escape', 'del': 'delete', 'control': 'ctrl', 'meta': 'win',
           'windows': 'win', 'return': 'enter', 'pgup': 'pageup', 'pgdn': 'pagedown',
           'ins': 'insert', 'prtsc': 'printscreen', 'menu': 'alt'}


def resolve_key(name: object) -> tuple[int, bool]:
    if not isinstance(name, str):
        raise InputError('Tecla inválida.')
    key = name.strip().lower()
    key = ALIASES.get(key, key)
    if key not in KEYS:
        raise InputError('Tecla inválida.')
    return KEYS[key]


def _number(value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise InputError('Número inválido.')
    if not math.isfinite(value):
        raise InputError('Número inválido.')
    return float(value)


def _coords(data: dict[str, Any], required: bool = True) -> Optional[tuple[float, float]]:
    if not required and 'x' not in data and 'y' not in data:
        return None
    x, y = _number(data.get('x')), _number(data.get('y'))
    if not (0.0 <= x <= 1.0 and 0.0 <= y <= 1.0):
        raise InputError('Posição fora da tela.')
    return x, y


def _button(data: dict[str, Any]) -> str:
    name = data.get('button', 'left')
    if name not in MOUSE_BUTTONS:
        raise InputError('Botão inválido.')
    return name


class InputDispatcher:
    def __init__(self, controller: Any, monitor_getter: Callable[[], Optional[dict]],
                 clock: Callable[[], float] = time.monotonic,
                 max_per_second: int = 800, hold_timeout: float = HOLD_TIMEOUT) -> None:
        self.controller = controller
        self._monitor = monitor_getter
        self._clock = clock
        self.max_per_second = max_per_second
        self.hold_timeout = hold_timeout
        self._states: dict[object, dict[str, Any]] = {}
        self.handled = 0

    # ---------------------------------------------------------------- entrada
    def handle(self, data: object, owner: object = 'default') -> None:
        if not isinstance(data, dict):
            raise InputError('Formato inválido.')
        action = data.get('action')
        if not isinstance(action, str):
            raise InputError('Ação desconhecida.')
        if action == 'release_all':
            self.release_all(owner)
            return
        state = self._state(owner)
        self._rate_limit(state)
        monitor = self._monitor()
        if monitor is None:
            # nunca aceitar entrada antes da primeira captura de tela
            raise InputError('Captura ainda não iniciada.', 503)
        handler = getattr(self, f'_do_{action}', None)
        if handler is None or action.startswith('_'):
            raise InputError('Ação desconhecida.')
        handler(data, monitor, state)
        state['last_input'] = self._clock()
        self.handled += 1

    def _state(self, owner: object) -> dict[str, Any]:
        return self._states.setdefault(owner, {
            'buttons': set(), 'keys': {}, 'last_input': self._clock(), 'recent': deque()
        })

    def _rate_limit(self, state: dict[str, Any]) -> None:
        now = self._clock()
        recent: deque[float] = state['recent']
        while recent and now - recent[0] > 1.0:
            recent.popleft()
        if len(recent) >= self.max_per_second:
            raise InputError('Muitos comandos por segundo.', 429)
        recent.append(now)

    # ------------------------------------------------------------------ mouse
    def _do_move(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        x, y = _coords(data)  # type: ignore[misc]
        self.controller.move(x, y, monitor)

    def _click(self, button: str) -> None:
        self.controller.button(button, True)
        self.controller.button(button, False)

    def _do_tap(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        self._do_move(data, monitor, state)
        self._click('left')

    def _do_right(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        self._do_move(data, monitor, state)
        self._click('right')

    def _do_double(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        self._do_move(data, monitor, state)
        self._click('left')
        self._click('left')

    def _do_down(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        button = _button(data)
        pos = _coords(data, required=False)
        if pos:
            self.controller.move(pos[0], pos[1], monitor)
        self.controller.button(button, True)
        state['buttons'].add(button)

    def _do_up(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        button = _button(data)
        pos = _coords(data, required=False)
        if pos:
            self.controller.move(pos[0], pos[1], monitor)
        self.controller.button(button, False)
        state['buttons'].discard(button)

    def _do_scroll(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        if 'direction' in data:  # compatível com a 0.2.0
            if data['direction'] not in ('up', 'down'):
                raise InputError('Rolagem inválida.')
            self.controller.wheel(120 if data['direction'] == 'up' else -120, 0)
            return
        dy = round(_number(data.get('dy', 0)))
        dx = round(_number(data.get('dx', 0)))
        if dy == 0 and dx == 0:
            raise InputError('Rolagem inválida.')
        if abs(dy) > MAX_WHEEL or abs(dx) > MAX_WHEEL:
            raise InputError('Rolagem fora do limite.')
        self.controller.wheel(dy, dx)

    # ---------------------------------------------------------------- teclado
    def _do_key(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        vk, ext = resolve_key(data.get('key'))
        self.controller.key_event(vk, ext, True)
        self.controller.key_event(vk, ext, False)

    def _do_keydown(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        name = str(data.get('key')).strip().lower()
        vk, ext = resolve_key(data.get('key'))
        self.controller.key_event(vk, ext, True)
        state['keys'][ALIASES.get(name, name)] = (vk, ext)

    def _do_keyup(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        name = str(data.get('key')).strip().lower()
        vk, ext = resolve_key(data.get('key'))
        self.controller.key_event(vk, ext, False)
        state['keys'].pop(ALIASES.get(name, name), None)

    def _do_combo(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        names = data.get('keys')
        if not isinstance(names, list) or not (1 <= len(names) <= MAX_COMBO):
            raise InputError('Atalho inválido.')
        resolved = [resolve_key(n) for n in names]  # valida tudo antes de apertar algo
        pressed: list[tuple[int, bool]] = []
        try:
            for vk, ext in resolved:
                self.controller.key_event(vk, ext, True)
                pressed.append((vk, ext))
        finally:
            for vk, ext in reversed(pressed):
                self.controller.key_event(vk, ext, False)

    def _do_text(self, data: dict, monitor: dict, state: dict[str, Any]) -> None:
        value = data.get('text', '')
        if not isinstance(value, str) or not value or len(value) > MAX_TEXT or '\x00' in value:
            raise InputError(f'Texto inválido (limite de {MAX_TEXT} caracteres).')
        self.controller.type_text(value)

    # -------------------------------------------------------- segurança/limpeza
    def release_all(self, owner: object = 'default') -> None:
        """Solta botões e teclas presos (conexão caiu, app foi pausado, etc.)."""
        state = self._states.get(owner)
        if state is None:
            return
        for button in list(state['buttons']):
            try:
                self.controller.button(button, False)
            except Exception:  # noqa: BLE001 - soltar o resto é mais importante
                pass
        for vk, ext in list(state['keys'].values()):
            try:
                self.controller.key_event(vk, ext, False)
            except Exception:  # noqa: BLE001
                pass
        state['buttons'].clear()
        state['keys'].clear()
        self._states.pop(owner, None)

    def release_all_sessions(self) -> None:
        for owner in list(self._states):
            self.release_all(owner)

    def tick(self) -> bool:
        """Watchdog: chame ~1x/s. Solta tudo se algo ficou preso sem comandos."""
        now = self._clock()
        expired = [owner for owner, state in self._states.items()
                   if (state['buttons'] or state['keys']) and
                   now - state['last_input'] > self.hold_timeout]
        for owner in expired:
            self.release_all(owner)
        return bool(expired)

    @property
    def pressed_buttons(self) -> set[str]:
        return set().union(*(state['buttons'] for state in self._states.values()))

    @property
    def pressed_keys(self) -> dict[str, tuple[int, bool]]:
        result: dict[str, tuple[int, bool]] = {}
        for state in self._states.values():
            result.update(state['keys'])
        return result
