import unittest

import _paths  # noqa: F401
from om_metrics import RateCounter, make_pong
from om_security import PIN_LENGTH, PairLimiter, SessionStore, generate_pin, safe_equals


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


class SecurityTests(unittest.TestCase):
    def test_safe_equals_never_raises(self):
        self.assertTrue(safe_equals('1234', '1234'))
        self.assertFalse(safe_equals('é', '1234'))      # 0.2.0: TypeError -> HTTP 500
        self.assertFalse(safe_equals('\ud800', 'x'))        # surrogate solto
        self.assertFalse(safe_equals(None, 'x'))
        self.assertFalse(safe_equals(12345678, '12345678'))
        self.assertTrue(safe_equals(b'ab', 'ab'))

    def test_pin_format(self):
        for _ in range(200):
            pin = generate_pin()
            self.assertRegex(pin, rf'^\d{{{PIN_LENGTH}}}$')

    def test_session_idle_and_lifetime_expiry(self):
        clock = Clock()
        s = SessionStore(idle_timeout=100, max_lifetime=500, clock=clock)
        token = s.create()
        clock.t = 90
        self.assertTrue(s.valid(token))          # usa e renova o ocioso
        clock.t = 180
        self.assertTrue(s.valid(token))
        clock.t = 290
        self.assertFalse(s.valid(token))         # >100 s sem uso
        t2 = s.create()
        for step in range(1, 9):                 # uso constante (a cada 80 s), mas passa de 500 s no total
            clock.t = 290 + step * 80
            ok = s.valid(t2)
        self.assertFalse(ok)

    def test_valid_without_touch_does_not_renew(self):
        clock = Clock()
        s = SessionStore(idle_timeout=100, clock=clock)
        token = s.create()
        clock.t = 60
        self.assertTrue(s.valid(token, touch=False))
        clock.t = 120
        self.assertFalse(s.valid(token))

    def test_max_sessions_evicts_least_recently_used(self):
        clock = Clock()
        s = SessionStore(max_sessions=2, clock=clock)
        a = s.create()
        clock.t = 1
        b = s.create()
        clock.t = 2
        s.valid(a)                               # a foi usada depois de b
        c = s.create()
        self.assertTrue(s.valid(a))
        self.assertFalse(s.valid(b))
        self.assertTrue(s.valid(c))
        self.assertEqual(len(s), 2)

    def test_revoke_and_garbage_tokens(self):
        s = SessionStore()
        t = s.create()
        for junk in (None, '', 5, 'é', t + 'x', t[:-1]):
            self.assertFalse(s.valid(junk))
        s.revoke(t)
        self.assertFalse(s.valid(t))
        s.revoke(None)  # não explode
        t2 = s.create()
        s.revoke_all()
        self.assertFalse(s.valid(t2))

    def test_pair_limiter_per_ip_and_total(self):
        clock = Clock()
        lim = PairLimiter(per_ip=3, total=5, window=60, clock=clock)
        for _ in range(3):
            self.assertTrue(lim.allowed('1.1.1.1'))
            lim.record_failure('1.1.1.1')
        self.assertFalse(lim.allowed('1.1.1.1'))
        self.assertTrue(lim.allowed('2.2.2.2'))          # outro IP ainda pode
        lim.record_failure('2.2.2.2')
        lim.record_failure('2.2.2.2')
        self.assertFalse(lim.allowed('3.3.3.3'))         # limite total atingido
        clock.t = 61
        self.assertTrue(lim.allowed('1.1.1.1'))          # janela passou


class RateCounterTests(unittest.TestCase):
    def test_rate_and_kbps(self):
        clock = Clock()
        r = RateCounter(window=2, clock=clock)
        for i in range(31):
            clock.t = i / 30
            r.tick(1000)                                  # 1000 bytes a 30/s
        self.assertAlmostEqual(r.per_second(), 30000, delta=1000)
        self.assertAlmostEqual(r.kbps(), 240, delta=10)

    def test_empty_and_expiry(self):
        clock = Clock()
        r = RateCounter(window=2, clock=clock)
        self.assertEqual(r.per_second(), 0.0)
        r.tick()
        clock.t = 1
        r.tick()
        clock.t = 10
        self.assertEqual(r.per_second(), 0.0)


class PongTests(unittest.TestCase):
    def test_pong_echoes_client_fields_and_adds_server_clock(self):
        self.assertEqual(make_pong({'id': 7, 't': 123456}, 999), {'type': 'pong', 'id': 7, 't': 123456, 's': 999})

    def test_pong_ignores_non_numeric_fields(self):
        r = make_pong({'id': '<script>', 't': True}, 5)
        self.assertEqual((r['id'], r['t']), (None, None))


if __name__ == '__main__':
    unittest.main()
