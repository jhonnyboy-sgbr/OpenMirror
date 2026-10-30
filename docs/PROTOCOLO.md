# Protocolo OpenMirror (rede local)

Tudo em HTTP/WebSocket na porta 8765 (TCP) e descoberta na 8766 (UDP). **Sem criptografia.**
Todos os inteiros são big-endian.

## Negociação
`GET /api/hello` (sem sessão) →
`{"app":"OpenMirror","version":"0.3.0","video_protocols":[2,3],"capabilities":[...],"h264_available":true,"auth":"pin"}`
O cliente usa só o que está em `capabilities` e escolhe o maior `video_protocols` em comum. Sem
`/api/hello` (HTTP 404) = servidor 0.2.0: vídeo v2, sem `drag`/`wheel`/`combo`.
Capacidades 0.3.0: `mjpeg h264 ws-input drag wheel combo keydown timesync discovery`.

## Sessão
`POST /api/pair {"pin":"1234"}` → `{"session":"..."}` (401 PIN errado, 429 muitas tentativas).
A sessão vai no header `X-OpenMirror-Session`. Só `/stream`, `/stream/h264` e `/ws/input` aceitam
`?session=` na URL (navegador não manda header ali). Expira após 30 min sem uso ou 12 h; máx. 4.
`GET /api/status` (sessão) → medições do servidor: `encode_fps sent_fps bitrate_kbps dropped_frames encoder_active ...`.

## Vídeo H.264 — `GET /stream/h264[?proto=3]`
Um espectador por vez. Annex-B com AUD; cada AU-chave tem SPS+PPS+IDR.
* **v2 'OMH2'**: `b'OMH2' u16 largura u16 altura` depois `[u32 tam][AU]`...
* **v3 'OMH3'**: `b'OMH3' u16 largura u16 altura u8 fps u8 0` depois
  `[u32 tam][u8 flags][u32 seq][u64 ts_us][AU]`... — `flags` bit0 = quadro-chave; `seq` conta cada AU
  que saiu do encoder (**lacuna = quadro descartado**); `ts_us` = relógio monotônico do servidor
  quando a AU saiu do encoder.

## Entrada — `WS /ws/input` (ou `POST /api/input`)
JSON por mensagem (≤ 2048 bytes). Coordenadas `x,y` em 0..1 do monitor.
`move` · `tap` `right` `double` · `down`/`up` (`button`: left/right/middle; arrastar de verdade) ·
`scroll` (`dx`,`dy` em 1/120 de clique, ou `direction` up/down) · `key` `keydown` `keyup` (nome:
`a`-`z` `0`-`9` `f1`-`f12` `enter` `esc` `tab` `win` `ctrl` `alt` `shift` setas…) ·
`combo` (`keys`: até 4) · `text` (≤ 250) · `release_all`.
`ping {id,t}` → `{"type":"pong","id","t","s"}` (`s` = relógio do servidor em µs, para estimar
a idade do quadro). Erros: `{"type":"error","message"}`; com `id` inteiro no comando vem `{"type":"ack","id"}`.
O servidor **solta botões/teclas presos** quando o WebSocket fecha e após 30 s sem comandos.

## Descoberta — UDP 8766
Envie `OPENMIRROR_DISCOVER_V1`; o servidor responde um JSON com `app name port version video_protocols capabilities`.
Nunca inclui PIN ou sessão. Desligue com `server.py --no-discovery`.
