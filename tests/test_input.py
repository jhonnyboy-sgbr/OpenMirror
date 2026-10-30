"""Despacho de entrada + controlador Windows com user32 falso. Roda no Linux, sem aiohttp."""
import unittest

import _paths  # noqa: F401
from om_controller import WindowsController
from om_input import KEYS, InputDispatcher, InputError, resolve_key

MON = {'left': 100, 'top': 50, 'width': 1920, 'height': 1080}


class FakeController:
    def __init__(self):
        self.log = []

    def move(self, x, y, monitor):
        self.log.append(('move', x, y))

    def button(self, name, down):
        self.log.append(('button', name, down))

    def wheel(self, dy, dx):
        self.log.append(('wheel', dy, dx))

    def key_event(self, vk, ext, down):
        self.log.append(('key', vk, ext, down))

    def type_text(self, value):
        self.log.append(('text', value))


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class DispatcherTests(unittest.TestCase):
    def setUp(self):
        self.c = FakeController()
        self.clock = Clock()
        self.mon = MON
        self.d = InputDispatcher(self.c, lambda: self.mon, clock=self.clock)

    def test_tap_is_move_then_press_release(self):
        self.d.handle({'action': 'tap', 'x': .5, 'y': .25})
        self.assertEqual(self.c.log, [('move', .5, .25), ('button', 'left', True), ('button', 'left', False)])

    def test_double_is_two_clicks(self):
        self.d.handle({'action': 'double', 'x': 0, 'y': 1})
        self.assertEqual([e for e in self.c.log if e[0] == 'button'],
                         [('button', 'left', True), ('button', 'left', False)] * 2)

    def test_drag_presses_for_real_and_releases(self):
        """Bug da 0.2.0: arrastar só movia o cursor, o botão nunca era pressionado."""
        self.d.handle({'action': 'down', 'x': .1, 'y': .1})
        self.assertEqual(self.d.pressed_buttons, {'left'})
        self.d.handle({'action': 'move', 'x': .4, 'y': .4})
        self.d.handle({'action': 'up', 'x': .5, 'y': .5})
        self.assertEqual(self.d.pressed_buttons, set())
        kinds = [(e[0], e[-1]) if e[0] == 'button' else e[0] for e in self.c.log]
        self.assertEqual(kinds, ['move', ('button', True), 'move', 'move', ('button', False)])

    def test_release_all_frees_stuck_button_and_keys(self):
        self.d.handle({'action': 'down', 'x': .1, 'y': .1, 'button': 'right'})
        self.d.handle({'action': 'keydown', 'key': 'ctrl'})
        self.c.log.clear()
        self.d.handle({'action': 'release_all'})
        self.assertIn(('button', 'right', False), self.c.log)
        self.assertIn(('key', 0x11, False, False), self.c.log)
        self.assertFalse(self.d.pressed_buttons or self.d.pressed_keys)

    def test_release_all_works_even_without_capture(self):
        self.d.handle({'action': 'down', 'x': .1, 'y': .1})
        self.mon = None
        self.d.handle({'action': 'release_all'})
        self.assertEqual(self.d.pressed_buttons, set())

    def test_release_all_is_scoped_to_owner(self):
        self.d.handle({'action': 'down', 'x': .1, 'y': .1}, owner='phone-a')
        self.d.handle({'action': 'keydown', 'key': 'ctrl'}, owner='phone-b')
        self.c.log.clear()
        self.d.release_all('phone-a')
        self.assertIn(('button', 'left', False), self.c.log)
        self.assertNotIn(('key', 0x11, False, False), self.c.log)
        self.assertEqual(self.d.pressed_buttons, set())
        self.assertEqual(self.d.pressed_keys, {'ctrl': (0x11, False)})

    def test_watchdog_releases_after_timeout_only(self):
        self.d.handle({'action': 'down', 'x': .1, 'y': .1})
        self.clock.t += 29
        self.assertFalse(self.d.tick())
        self.clock.t += 2
        self.assertTrue(self.d.tick())
        self.assertEqual(self.c.log[-1], ('button', 'left', False))

    def test_watchdog_ignores_idle_when_nothing_pressed(self):
        self.clock.t += 999
        self.assertFalse(self.d.tick())

    def test_no_capture_returns_503(self):
        self.mon = None
        with self.assertRaises(InputError) as ctx:
            self.d.handle({'action': 'tap', 'x': .5, 'y': .5})
        self.assertEqual(ctx.exception.status, 503)

    def test_rejects_bad_input(self):
        bad = [[], 'x', {'action': 5}, {'action': 'execute', 'cmd': 'calc'},
               {'action': 'move', 'x': 1.2, 'y': 0}, {'action': 'move', 'x': True, 'y': 0},
               {'action': 'move', 'x': float('nan'), 'y': 0}, {'action': 'move', 'x': '0.5', 'y': 0},
               {'action': 'down', 'button': 'x', 'x': 0, 'y': 0},
               {'action': 'key', 'key': 'nope'}, {'action': 'key', 'key': 7},
               {'action': 'combo', 'keys': []}, {'action': 'combo', 'keys': ['a'] * 5},
               {'action': 'combo', 'keys': ['ctrl', 'nope']},
               {'action': 'scroll', 'direction': 'left'}, {'action': 'scroll'},
               {'action': 'scroll', 'dy': 99999}, {'action': 'text', 'text': ''},
               {'action': 'text', 'text': 'a' * 251}, {'action': 'text', 'text': 'a\x00b'},
               {'action': '_do_move'}]
        for item in bad:
            with self.assertRaises(InputError, msg=repr(item)):
                self.d.handle(item)
        self.assertEqual(self.c.log, [])  # nada foi executado

    def test_scroll_forms(self):
        self.d.handle({'action': 'scroll', 'direction': 'down'})
        self.d.handle({'action': 'scroll', 'dy': -45.4, 'dx': 30})
        self.assertEqual(self.c.log, [('wheel', -120, 0), ('wheel', -45, 30)])

    def test_combo_presses_in_order_releases_reversed_and_validates_first(self):
        self.d.handle({'action': 'combo', 'keys': ['ctrl', 'shift', 'esc']})
        self.assertEqual(self.c.log, [
            ('key', 0x11, False, True), ('key', 0x10, False, True), ('key', 0x1B, False, True),
            ('key', 0x1B, False, False), ('key', 0x10, False, False), ('key', 0x11, False, False)])

    def test_combo_releases_if_controller_fails_midway(self):
        calls = []

        def flaky(vk, ext, down):
            calls.append((vk, down))
            if vk == 0x43 and down:
                raise OSError('boom')

        self.c.key_event = flaky
        with self.assertRaises(OSError):
            self.d.handle({'action': 'combo', 'keys': ['ctrl', 'c']})
        self.assertIn((0x11, False), calls)  # ctrl foi solto

    def test_key_aliases_and_extended_flag(self):
        self.assertEqual(resolve_key('ESC'), (0x1B, False))
        self.assertEqual(resolve_key('Windows'), (0x5B, True))
        self.assertEqual(resolve_key('up'), (0x26, True))
        self.assertEqual(resolve_key('f12'), (0x7B, False))
        self.assertEqual(resolve_key('a'), (0x41, False))
        self.assertEqual(resolve_key('9'), (0x39, False))
        for old in ('enter', 'backspace', 'tab', 'escape', 'up', 'down', 'left', 'right', 'delete', 'space'):
            self.assertIn(old, KEYS)  # compatibilidade com a 0.2.0

    def test_rate_limit(self):
        d = InputDispatcher(self.c, lambda: MON, clock=self.clock, max_per_second=5)
        for _ in range(5):
            d.handle({'action': 'move', 'x': .1, 'y': .1})
        with self.assertRaises(InputError) as ctx:
            d.handle({'action': 'move', 'x': .1, 'y': .1})
        self.assertEqual(ctx.exception.status, 429)
        self.clock.t += 1.1
        d.handle({'action': 'move', 'x': .1, 'y': .1})


class RecordingUser32:
    def __init__(self):
        self.calls = []

    def SetCursorPos(self, x, y):
        self.calls.append(('cursor', x, y))

    def mouse_event(self, flags, dx, dy, data, extra):
        self.calls.append(('mouse', flags, data))

    def keybd_event(self, vk, scan, flags, extra):
        self.calls.append(('keybd', vk, flags))

    def SendInput(self, n, ptr, size):
        self.calls.append(('sendinput', n, size))


class WindowsControllerTests(unittest.TestCase):
    def setUp(self):
        self.u = RecordingUser32()
        self.w = WindowsController(user32=self.u)

    def test_move_uses_monitor_origin_and_last_pixel(self):
        self.w.move(0, 0, MON)
        self.w.move(1, 1, MON)
        self.assertEqual(self.u.calls, [('cursor', 100, 50), ('cursor', 100 + 1919, 50 + 1079)])

    def test_buttons(self):
        for name, down, flag in [('left', True, 2), ('left', False, 4), ('right', True, 8),
                                 ('right', False, 0x10), ('middle', True, 0x20), ('middle', False, 0x40)]:
            self.u.calls.clear()
            self.w.button(name, down)
            self.assertEqual(self.u.calls, [('mouse', flag, 0)])

    def test_wheel_vertical_and_horizontal(self):
        self.w.wheel(-120, 60)
        self.assertEqual(self.u.calls, [('mouse', 0x0800, -120), ('mouse', 0x1000, 60)])

    def test_key_flags(self):
        self.w.key_event(0x26, True, True)    # seta: estendida, pressionar
        self.w.key_event(0x26, True, False)   # soltar
        self.w.key_event(0x41, False, True)
        self.assertEqual(self.u.calls, [('keybd', 0x26, 0x1), ('keybd', 0x26, 0x3), ('keybd', 0x41, 0)])

    def test_type_text_two_events_per_utf16_unit(self):
        self.w.type_text('olá')                 # 3 unidades
        self.assertEqual(len(self.u.calls), 6)
        self.u.calls.clear()
        self.w.type_text('😀')                  # 1 caractere = par substituto = 2 unidades
        self.assertEqual(len(self.u.calls), 4)
        self.assertTrue(all(c[0] == 'sendinput' and c[1] == 1 for c in self.u.calls))


if __name__ == '__main__':
    unittest.main()
