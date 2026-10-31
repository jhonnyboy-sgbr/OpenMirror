<div align="center">
  <img src="./imagens/Banner Neon do OpenMirror.png" alt="OpenMirror Banner" width="100%">
</div>

# OpenMirror 0.3.1 — Base estável

**Espelhamento e controle remoto do Windows 10 pelo Android**, para uso **somente em rede local
privada**. Feito em cima da 0.2.0: o foco desta versão é **consertar o que estava quebrado e medir
melhor** (Fase 1 do plano), não adicionar recursos novos de uma vez.

> **0.3.1 (só o app Android muda; servidor e cliente web continuam 0.3.0):** o app agora **grava os erros em arquivo** e mostra o texto (com botão *Copiar*) na tela inicial quando fechou por erro; a tela de vídeo H.264 mostra o erro na tela em vez de fechar; threads de fundo não derrubam mais o app; e o **modo compatível (WebView) passou a ter tela cheia** (botão *Tela cheia* + suporte ao fullscreen da página). **Nada disso foi executado num celular**: o objetivo é descobrir a causa do crash do H.264 sem adivinhar.

> **Estado de validação (leia):** 72 testes Python, 175 + 12 + 4 verificações Java no JVM e a
> compilação do app contra stubs passaram **num Linux sem Windows e sem Android SDK**. **O servidor
> HTTP (`aiohttp`), o app rodando num celular, o Gradle, a captura real, DXGI e AMD AMF ainda NÃO
> foram executados em lugar nenhum.** Não há APK compilado neste ZIP. Os FPS são metas, não promessas.
> O relatório completo, com a tabela do que está pronto/testado/pendente, está em
> [`docs/RELATORIO_TECNICO.md`](docs/RELATORIO_TECNICO.md).

## O que mudou da 0.2.0 para a 0.3.0

| Problema da 0.2.0 | Agora |
|---|---|
| Arrastar não pressionava o botão (não dava para mover janela) | Arrasto de verdade (`down`/`move`/`up`), no app e no navegador |
| Cada toque abria uma conexão HTTP nova | WebSocket persistente (HTTP de reserva) |
| Minimizar o app e voltar deixava o vídeo parado | Reconecta sozinho; uma sessão por Surface |
| Tela cheia perdida depois de teclado/notificação | Reaplicada ao recuperar foco; usa a área do recorte da tela |
| Soluço de Wi-Fi = até 1 s de imagem parada | Fila de 3 quadros + descarte até o próximo IDR (servidor e celular) |
| Sem ajuste de imagem, zoom ou atalhos | Ajustar / Preencher / Esticar, zoom por pinça, atalhos do Windows, modo Trackpad |
| Sem medições separadas | Indicadores: **medido no celular**, **medido no PC** e **estimado** (marcado com `*`) |
| Servidor e app tinham que ser da mesma versão | `/api/hello` negocia versão e recursos; app novo funciona (limitado) com servidor antigo |
| Erro 500 com PIN estranho; sessão eterna | 401 limpo; sessão expira (30 min ocioso / 12 h) |
| Sem descoberta do PC | Botão **Procurar PC na rede** (UDP na LAN) |

## 1 — Rodar no PC (Windows 10)

1. Extraia o ZIP numa pasta nova e feche a versão anterior.
2. Para uso simples, dê duplo clique em [`downloads/OpenMirror.exe`](downloads/OpenMirror.exe).
   O executável já inclui o painel, o servidor e as dependências Python; o FFmpeg continua opcional
   para H.264 e deve ficar no PATH ou em `downloads/tools/`.
   Também é possível usar o atalho [`OpenMirror.lnk`](OpenMirror.lnk) na raiz do projeto.
3. Como alternativa para desenvolvimento, dê duplo clique em `windows/INICIAR_OPENMIRROR.bat` (requer Python 3.11+ com *Add Python to PATH*;
   instala `aiohttp`, `mss` e `Pillow` na primeira vez).
4. No painel, olhe a linha do **FFmpeg**: ela diz o que o seu FFmpeg tem (x264, DXGI, AMD AMF) e **se o
   AMF funciona de verdade**. Comece com perfil **Equilibrado**, captura **auto**, encoder **software**.
5. **Iniciar transmissão**. Libere o firewall **só para redes privadas** (TCP 8765 e UDP 8766).
6. O painel mostra o endereço `http://192.168.x.x:8765` e o PIN de 4 dígitos.

PC por cabo Ethernet + celular no Wi-Fi do mesmo roteador funciona normalmente.

**FFmpeg:** se ele não estiver instalado, abra **Menu → Configurações** e clique em
**Instalar FFmpeg automaticamente**. O OpenMirror pedirá confirmação e usará o `winget` da Microsoft
para instalar o pacote Gyan.FFmpeg. Como alternativa, execute `winget install --id Gyan.FFmpeg -e`
ou copie o `ffmpeg.exe` para `windows/tools/`. Para a captura DXGI use FFmpeg **6.0 ou mais novo**.
`python windows/server.py --probe` mostra o diagnóstico em texto. Sem FFmpeg o modo MJPEG segue funcionando.

**Opções novas do painel/servidor:** `--capture gdigrab|ddagrab|auto` (ddagrab = DXGI, **experimental**,
cai para GDI se falhar), `--backlog N`, `--eager-flush` (experimental, ~1 quadro a menos de atraso),
`--no-discovery`, `--session-minutes N`. O PIN vai pela variável `OPENMIRROR_PIN`.

## 2 — Compilar o APK (Android Studio)

### Download rápido

Uma cópia atual do APK de debug está em
[`downloads/OpenMirror-0.3.1-debug.apk`](downloads/OpenMirror-0.3.1-debug.apk).
Baixe esse arquivo pelo GitHub e instale-o no Android. Ele é uma build de teste e pode exigir
que a instalação de aplicativos de fontes desconhecidas esteja autorizada.

Requisitos: Android Studio recente (JDK 17+), SDK Platform 35, internet na primeira sincronização.

1. **Open** → pasta `android`. O `gradle-wrapper.properties` pede o Gradle 8.9 e o Android Studio
   baixa sozinho (o `gradle-wrapper.jar` não vai no ZIP; na linha de comando: `gradle wrapper --gradle-version 8.9`).
2. **Build → Build APK(s)** → `android/app/build/outputs/apk/debug/app-debug.apk`.
3. Instale no celular. Abra o app, digite o endereço (ou **Procurar PC na rede**) e o PIN → **Conectar em H.264 nativo**.

Detalhes de assinatura/atualização: `android/COMO_GERAR_APK.txt`. **Se o Gradle ou o app falharem, me
mande a mensagem exata** — é a parte que nunca foi compilada com o SDK de verdade.

### Gestos no app

| Gesto | Ação |
|---|---|
| 1 toque | clique esquerdo (2 toques rápidos = duplo clique) |
| Toque longo | clique direito |
| Arrastar | segura o botão esquerdo e move (arrasta janelas) |
| 2 dedos deslizando | rolagem |
| Pinça | zoom da imagem (não vai para o PC) · **Zoom 1×** volta ao normal |
| Toque rápido com 2 dedos | clique direito |
| 3 dedos | mostra/esconde os controles |
| Modo **Trackpad** | o dedo move o cursor; toque clica no cursor; toque longo segura o botão |

Botões: *Teclado* (texto), *Atalhos* (Alt+Tab, Win+D, Ctrl+C/V/Z, Esc, setas…), *Imagem* (Ajustar/Preencher/Esticar),
*Modo* (Toque/Trackpad + velocidade), *Tela* (Paisagem/Retrato/Automático), *Indicadores*, *Diagnóstico*.

## 3 — Usar por USB

1. Ative **Depuração USB** no Android, conecte o cabo e autorize o computador.
2. Com o servidor rodando, `windows/ATIVAR_USB.bat` (`adb reverse tcp:8765 tcp:8765`).
3. No app use `127.0.0.1:8765` e o mesmo PIN. Desfazer: `adb reverse --remove tcp:8765`.

É um túnel ADB, **não** um driver USB de vídeo. USB não é garantidamente mais rápido que o Wi-Fi: compare.
(A descoberta automática por UDP não atravessa o USB; digite o endereço.)

## 4 — Perfis (metas)

| Perfil | FPS-alvo | Largura | Bitrate |
|---|---:|---:|---:|
| Competitivo | 60 | 1280 | 8000 kbps |
| Equilibrado | 30 | 640 | 3000 kbps |
| Qualidade | 60 | 1920 | 16000 kbps |

Ajuste manual no painel = perfil personalizado. FPS alto exige mais do PC (captura + codificação);
veja o FPS **real** nos indicadores.

## 5 — O que os números significam

* **Medido no celular:** FPS decodificado e exibido, tempo de decodificação, fila, bitrate recebido, perdas.
* **Medido no PC:** FPS saindo do codificador, FPS enviado, bitrate enviado, quadros descartados.
* **Estimado\*:** RTT e *idade do quadro* (desde que saiu do codificador até entrar no decoder). Vêm de
  ping entre relógios diferentes: erro de até ~RTT/2. **Não incluem captura nem exibição**, então não
  são a latência de ponta a ponta. Para essa, fotografe um cronômetro na tela do PC e do celular (ver o relatório).

## 6 — Segurança

* Por padrão o servidor usa HTTP/WS puro. Para proteger PIN, vídeo e comandos, inicie-o com
  `--tls-cert certificado.pem --tls-key chave.pem`; isso habilita HTTPS/WSS. Gere um certificado
  para o nome/IP usado pelo celular e instale-o como confiável no dispositivo (certificados
  autoassinados exigem essa etapa). Sem TLS, use **somente rede privada** e nunca redirecione a
  porta no roteador.
* PIN de 4 dígitos, tentativas limitadas (8/min por IP e 30/min no total), sessão expira, até 4 sessões.
* O servidor solta botões presos se o celular cair. A descoberta UDP responde só nome/porta/versão (nada secreto) e pode ser desligada.
* Ainda **não há** autorização de pareamento no PC, lista de dispositivos, revogação individual nem modo só-visualização.

Com TLS manual:

```text
python windows/server.py --tls-cert C:\caminho\certificado.pem --tls-key C:\caminho\chave.pem
```

O cliente web detecta automaticamente `https://` e usa `wss://` para o controle. O app Android
precisa confiar na autoridade/certificado correspondente; não desative a validação TLS.

## 7 — Testes

```bash
python scripts/run_tests.py      # tudo que dá para rodar aqui + resumo do que foi PULADO
python scripts/check_android_java.py   # compila o app contra stubs do SDK
```

Precisa de FFmpeg (com x264) e de um JDK (para a parte Java) para rodar tudo. Sem `aiohttp`, os 15
testes do servidor são pulados e o resumo avisa.

## 8 — Arquivos

```text
windows/   server.py (HTTP/WS) · om_input.py (entrada) · om_controller.py (user32) · om_security.py
           om_metrics.py · om_discovery.py · om_presets.py · om_meta.py · video_h264.py (FFmpeg/protocolo)
           openmirror_windows.py (painel) · INICIAR_OPENMIRROR.bat · ATIVAR_USB.bat
shared/    index.html (cliente web, modo compatível)
android/   app/src/main/java/com/openmirror/client/
             lógica pura (testada no JVM): ViewportMapper GestureEngine RemoteInput OutboundQueue WsClient
               MiniJson ServerInfo H264 PacketQueue Stats ClockSync DiscoveryClient InputMessages AppInfo
             dependem do SDK: StreamSession NativeStreamActivity MainActivity
tests/     Python + tests/java (UnitTests, NetTests) · scripts/ · docs/
```

Documentação: [`docs/RELATORIO_TECNICO.md`](docs/RELATORIO_TECNICO.md) · [`docs/PROTOCOLO.md`](docs/PROTOCOLO.md) · [`docs/CHANGELOG.md`](docs/CHANGELOG.md)

**Uso experimental, sem garantias.**
