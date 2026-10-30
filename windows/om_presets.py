"""Perfis de transmissão e montagem da linha de comando do servidor (puro, testável).

Os valores são METAS. O servidor/aplicativo mostram o FPS que realmente saiu; escolher
"60 FPS" não significa que o PC (captura + codificação) ou o Wi-Fi vão entregar 60.
"""
from __future__ import annotations

from typing import Optional

# nome: (fps, largura, bitrate_kbps)
PRESETS: dict[str, tuple[int, int, int]] = {
    'Competitivo': (60, 1280, 8000),   # 1280x720, prioridade para baixa latência
    'Equilibrado': (30, 640, 3000),    # Wi-Fi: prioriza estabilidade e baixa fila
    'Qualidade': (60, 1920, 16000),    # 1920x1080, mais bitrate
}
CAPTURES = ('gdigrab', 'ddagrab', 'auto')
ENCODERS = ('software', 'amd')


def server_args(python: str, server_py: str, *, fps: int, width: int, bitrate: int,
                encoder: str, capture: str, eager_flush: bool = False,
                discovery: bool = True, port: Optional[int] = None) -> list[str]:
    """Argumentos do servidor. O PIN NÃO entra aqui: vai por variável de ambiente
    (OPENMIRROR_PIN) para não aparecer na lista de processos do Windows."""
    if encoder not in ENCODERS or capture not in CAPTURES:
        raise ValueError('Encoder ou captura inválidos')
    args = [python, server_py, '--fps', str(fps), '--width', str(width),
            '--encoder', encoder, '--bitrate', str(bitrate), '--capture', capture]
    if eager_flush:
        args.append('--eager-flush')
    if not discovery:
        args.append('--no-discovery')
    if port is not None:
        args += ['--port', str(port)]
    return args
