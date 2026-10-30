"""Constantes de versão e de negociação de capacidades do OpenMirror."""

VERSION = '0.3.0'
APP_NAME = 'OpenMirror'

# Versões do protocolo de vídeo H.264 que este servidor sabe falar.
#   2 = 'OMH2': cabeçalho + [u32 tamanho][access unit]           (0.2.0)
#   3 = 'OMH3': cabeçalho + [u32 tamanho][flags][seq][ts][AU]     (0.3.0)
VIDEO_PROTOCOLS = (2, 3)

# Capacidades anunciadas em /api/hello. O cliente só usa o que estiver aqui,
# então um APK novo continua funcionando (com menos recursos) num servidor antigo.
CAPABILITIES = (
    'mjpeg',        # /stream (multipart/x-mixed-replace)
    'h264',         # /stream/h264
    'ws-input',     # /ws/input
    'drag',         # ações down/up (botão pressionado de verdade)
    'wheel',        # scroll com dx/dy em unidades de 1/120 de "clique"
    'combo',        # atalhos com várias teclas
    'keydown',      # keydown/keyup individuais
    'timesync',     # pong com relógio do servidor (para estimar idade do quadro)
    'discovery',    # resposta UDP de descoberta na LAN
)

DISCOVERY_PORT = 8766
