import unittest

import _paths  # noqa: F401
from om_presets import PRESETS, server_args
from video_h264 import h264_command

MON = {'left': 0, 'top': 0, 'width': 1920, 'height': 1080}


class PresetTests(unittest.TestCase):
    def test_spec_profiles_exist_with_expected_targets(self):
        self.assertEqual(PRESETS['Competitivo'][:2], (60, 1280))      # 1280x720 @ 60 (meta)
        self.assertEqual(PRESETS['Qualidade'][:2], (60, 1920))        # 1920x1080 @ 60 (meta)
        self.assertEqual(PRESETS['Equilibrado'], (30, 640, 3000))

    def test_every_preset_is_accepted_by_the_ffmpeg_command_builder(self):
        for name, (fps, width, kbps) in PRESETS.items():
            for capture in ('gdigrab', 'ddagrab'):
                for encoder in ('software', 'amd'):
                    h264_command('ffmpeg', monitor=MON, width=width, height=width * 9 // 16,
                                 fps=fps, bitrate_k=kbps, encoder=encoder, capture=capture)

    def test_pin_never_goes_on_the_command_line(self):
        args = server_args('python', 'server.py', fps=30, width=640, bitrate=3000,
                           encoder='software', capture='auto', eager_flush=True, discovery=False)
        self.assertNotIn('--pin', args)
        self.assertIn('--eager-flush', args)
        self.assertIn('--no-discovery', args)
        self.assertEqual(args[args.index('--capture') + 1], 'auto')

    def test_rejects_unknown_values(self):
        with self.assertRaises(ValueError):
            server_args('p', 's', fps=30, width=960, bitrate=6000, encoder='nvidia', capture='gdigrab')


if __name__ == '__main__':
    unittest.main()
