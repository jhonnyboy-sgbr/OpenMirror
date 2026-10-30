import sys
from pathlib import Path

WINDOWS = str(Path(__file__).resolve().parents[1] / 'windows')
if WINDOWS not in sys.path:
    sys.path.insert(0, WINDOWS)
