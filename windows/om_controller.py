"""Controle real de mouse/teclado no Windows (user32). Importável no Linux.

`user32` pode ser injetado nos testes para registrar as chamadas sem Windows.
"""
from __future__ import annotations

from typing import Any

MOUSEEVENTF = {
    ('left', True): 0x0002, ('left', False): 0x0004,
    ('right', True): 0x0008, ('right', False): 0x0010,
    ('middle', True): 0x0020, ('middle', False): 0x0040,
}
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000
KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
INPUT_KEYBOARD = 1


class WindowsController:
    def __init__(self, user32: Any = None) -> None:
        import ctypes
        self.ctypes = ctypes
        if user32 is None:
            user32 = ctypes.windll.user32  # type: ignore[attr-defined]
            try:  # coordenadas corretas com escala de DPI > 100%
                user32.SetProcessDPIAware()
            except Exception:  # noqa: BLE001
                pass
        self.user32 = user32

    def move(self, x: float, y: float, monitor: dict[str, int]) -> None:
        px = monitor['left'] + round(min(1.0, max(0.0, x)) * (monitor['width'] - 1))
        py = monitor['top'] + round(min(1.0, max(0.0, y)) * (monitor['height'] - 1))
        self.user32.SetCursorPos(px, py)

    def button(self, name: str, down: bool) -> None:
        self.user32.mouse_event(MOUSEEVENTF[(name, down)], 0, 0, 0, 0)

    def wheel(self, dy: int, dx: int = 0) -> None:
        if dy:
            self.user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, dy, 0)
        if dx:
            self.user32.mouse_event(MOUSEEVENTF_HWHEEL, 0, 0, dx, 0)

    def key_event(self, vk: int, extended: bool, down: bool) -> None:
        flags = (KEYEVENTF_EXTENDEDKEY if extended else 0) | (0 if down else KEYEVENTF_KEYUP)
        self.user32.keybd_event(vk, 0, flags, 0)

    def type_text(self, value: str) -> None:
        """Digita Unicode (inclusive acentos e emoji) via SendInput."""
        ctypes = self.ctypes
        from ctypes import wintypes

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [('wVk', wintypes.WORD), ('wScan', wintypes.WORD),
                        ('dwFlags', wintypes.DWORD), ('time', wintypes.DWORD),
                        ('dwExtraInfo', ctypes.c_size_t)]

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [('dx', wintypes.LONG), ('dy', wintypes.LONG),
                        ('mouseData', wintypes.DWORD), ('dwFlags', wintypes.DWORD),
                        ('time', wintypes.DWORD), ('dwExtraInfo', ctypes.c_size_t)]

        class HARDWAREINPUT(ctypes.Structure):
            _fields_ = [('uMsg', wintypes.DWORD), ('wParamL', wintypes.WORD),
                        ('wParamH', wintypes.WORD)]

        class INPUT_UNION(ctypes.Union):
            _fields_ = [('mi', MOUSEINPUT), ('ki', KEYBDINPUT), ('hi', HARDWAREINPUT)]

        class INPUT(ctypes.Structure):
            _fields_ = [('type', wintypes.DWORD), ('u', INPUT_UNION)]

        raw = value.encode('utf-16-le', 'surrogatepass')  # pares substitutos p/ emoji
        for i in range(0, len(raw), 2):
            unit = raw[i] | (raw[i + 1] << 8)
            for flags in (KEYEVENTF_UNICODE, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP):
                event = INPUT(INPUT_KEYBOARD, INPUT_UNION(ki=KEYBDINPUT(0, unit, flags, 0, 0)))
                self.user32.SendInput(1, ctypes.byref(event), ctypes.sizeof(INPUT))
