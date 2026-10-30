# OpenMirror 0.3.0 — Relatório técnico

**Leia isto primeiro.** O OpenMirror 1.0.0 do seu documento mestre (servidor C++ com DXGI/AMF,
interface Tauri, app Kotlin, áudio, gamepad, monitor virtual, instalador) **não está pronto, e esta
versão não finge que está.** A 0.3.0 é a **Fase 1 (base estável) com um pedaço da Fase 2 e da
Fase 3**, feita em cima do seu código 0.2.0 (como você pediu: aproveitar, não reescrever).

Ambiente em que trabalhei: Linux, **sem Windows, sem Android SDK, sem aparelho, sem GPU AMD e sem
internet** (não deu para instalar `aiohttp` nem baixar o Gradle). Tudo abaixo diz exatamente o que
isso permitiu validar.

## 0. Atualização 0.3.1 (somente o app Android)

Depois de a 0.3.0 mostrar crash no H.264 e tela cheia inoperante, a 0.3.1 **não corrige a causa do crash (ainda desconhecida)**: ela torna o erro visível e impede que ele feche o app. Mudanças: `CrashLog`/`OpenMirrorApp` (registro de erros + handler global, texto copiável na tela inicial), `onCreate` e etapas de janela da `NativeStreamActivity` protegidos (mostra tela de erro), threads de fundo sem queda, e **tela cheia no modo WebView** (antes inexistente). Testes: `UnitTests` agora com 175 verificações (+13 do `CrashLog`); 20 arquivos Java compilados contra stubs. Tudo continua **sem execução em aparelho**.

## 1. Inventário da 0.2.0 (o que você me mandou)

Servidor Python (`aiohttp` + FFmpeg + `mss`), painel `tkinter`, app Android em Java (~600 linhas),
cliente web, 12 testes. Tinha: pareamento por PIN de 4 dígitos, MJPEG, H.264 via FFmpeg
(`gdigrab`, libx264 ou `h264_amf`), WebSocket de entrada (mas o APK não usava), tela cheia, fila de
quadros no servidor.

Problemas **reais** que encontrei lendo o código (todos corrigidos na 0.3.0, salvo indicação):

| # | Problema na 0.2.0 | Efeito para você | Correção |
|---|---|---|---|
| 1 | Arrastar só enviava `move`, **nunca pressionava o botão** | Não dava para arrastar janela/selecionar | Ações `down`/`up` + gesto reescrito |
| 2 | Cada movimento do dedo abria uma conexão HTTP nova (e `disconnect()` fechava o socket) | Latência de entrada alta e irregular | WebSocket persistente (HTTP com keep-alive de reserva) |
| 3 | `surfaceDestroyed` não fazia nada | Ao minimizar e voltar: **vídeo congelado** | Sessão nova por Surface + reconexão automática |
| 4 | Tela cheia só aplicada uma vez | Barras voltam após teclado/diálogo/notificação | Reaplica em `onWindowFocusChanged`; recorte de tela |
| 5 | Leitura de rede e decoder na mesma thread | Rede lenta travava a exibição; atraso acumulava | Thread leitora + fila que descarta até o próximo IDR; só desenha o quadro mais novo |
| 6 | Fila do servidor com 1 posição | Qualquer soluço de Wi-Fi = **até 1 s de imagem parada** | `BacklogQueue` (3 quadros) absorve o tremido |
| 7 | PIN não-ASCII (ex.: `é`) → `TypeError` → **HTTP 500** | Erro 500 em vez de 401 | `safe_equals` sem exceção |
| 8 | Sessão nunca expirava; um PIN por execução | Token vivo para sempre | Expira por inatividade (30 min) e por tempo (12 h), máx. 4 sessões |
| 9 | Teclas: só 10 teclas; setas sem flag "estendida" | Sem atalhos; setas podem virar teclado numérico | ~90 teclas, combos (Ctrl+C, Alt+Tab…), flag estendida |
| 10 | Botão/tecla ficava preso se o celular caía no meio do arrasto | Mouse "travado" no PC | Solta tudo ao fechar o WS + watchdog de 30 s |
| 11 | AMD AMF podia nunca iniciar (SPS/PPS só no extradata) | "FFmpeg não produziu vídeo" | `dump_extra` repete SPS/PPS a cada IDR (validado só com libx264) |
| 12 | Cliente web: mesmo bug do arrasto | — | Corrigido (`down`/`up`) |
| 13 | PIN aparecia na linha de comando do servidor | Visível na lista de processos | Vai por variável de ambiente `OPENMIRROR_PIN` |
| 14 | Sem Gradle Wrapper configurado | Tinha que escolher Gradle à mão | `gradle-wrapper.properties` (8.9) |

## 2. Estado de cada funcionalidade (as 5 categorias do seu documento)

**Implementado e testado** = testado automaticamente aqui. **Implementado, não testado no hardware**
= código escrito/compilado/testado em partes, mas nunca rodou no Windows/Samsung A14.

| Funcionalidade | Estado | Observação |
|---|---|---|
| Validação/despacho de entrada (mouse, arrastar, rolagem, teclas, combos, texto) | **Implementado e testado** | 19 testes; limites, recusas, botão preso, watchdog, rate limit |
| Chamadas ao `user32` (flags, coordenadas, UTF-16/emoji) | Implementado e testado **com user32 falso** | A chamada real ao Windows não rodou |
| Sessões, PIN, limite de tentativas | **Implementado e testado** | 11 testes |
| Protocolo de vídeo v2 e **v3** (seq + timestamp por quadro) | **Implementado e testado** | Cliente Java lê pacotes reais gerados pelo Python |
| Fila com descarte até IDR (servidor e cliente) | **Implementado e testado** | Com FFmpeg real e consumidor lento |
| `EncoderSession`: fallback entre tentativas, erro com stderr, sem processo órfão | **Implementado e testado** | FFmpeg real (fonte de teste no lugar da tela) |
| Descoberta UDP na LAN (servidor e cliente) | **Implementado e testado** | Loopback; **broadcast real em Wi-Fi não testado** |
| Cliente WebSocket Android (RFC 6455) | **Implementado e testado** | Contra um servidor WS independente em Python; 12 verificações |
| Geometria: Ajustar/Preencher/Esticar, zoom, pan, coordenadas do toque | **Implementado e testado** | No JVM; **a SurfaceView maior que a tela não foi vista num aparelho** |
| Gestos (toque, duplo, longo, arrastar, rolagem, pinça, 2 dedos, trackpad) | **Implementado e testado** | No JVM com tempo falso; **sensação no dedo não validada** |
| Estimativa de relógio/idade do quadro (ClockSync) | **Implementado e testado** | É **estimativa** (± RTT/2) |
| Camada HTTP/WebSocket do servidor (`server.py`) | **Implementado, não executado aqui** | 15 testes escritos (`test_server.py`) mas **pulados**: sem `aiohttp`. Rode no seu PC |
| `StreamSession`, `NativeStreamActivity`, `MainActivity` | **Implementado, só compilado contra stubs** | Compila contra assinaturas escritas à mão; **nunca foi compilado com o SDK real nem executado** |
| Tela cheia imersiva, recorte, rotação manual, ciclo de vida | Implementado, **não testado no aparelho** | Tela H.264: desde a 0.3.0. **Modo WebView: só a partir da 0.3.1** |
| Registro de erros e proteção contra crash (0.3.1) | Implementado e testado no JVM (CrashLog); **integração Android não testada** | Handler global, tela de erro, threads protegidas |
| Reconexão automática do app | Implementado, **não testado no aparelho** | Lógica simples (espera crescente 1–8 s) |
| Captura DXGI (`ddagrab`) | **Parcial / não validado** | Comando montado e testado; **FFmpeg local nem tem o filtro**; cai para GDI se falhar |
| AMD AMF | **Parcial / não validado** | `--probe` e o painel testam o encoder de verdade; nunca rodei numa RX 580 |
| Modo "menos atraso" (`--eager-flush`) | Implementado e testado com FFmpeg; **experimental** | Ganha 1 período de quadro (~17 ms a 60 FPS). Pode truncar 1 quadro se o FFmpeg pausar no meio |
| USB | **Parcial** | Só o `ATIVAR_USB.bat` (ADB reverse) que já existia. Sem detecção/explicação no app |
| Seleção automática Wi-Fi/USB por medição | **Não implementado** | |
| Bitrate/FPS/resolução adaptativos | **Não implementado** | Só perfis fixos (metas) |
| Criptografia de transporte (TLS) | **Implementado como opção no servidor** | Use `--tls-cert` e `--tls-key`; o cliente web acompanha HTTPS/WSS. O app Android ainda precisa ser validado com certificado confiável no aparelho. |
| Pareamento com autorização no PC, lista de dispositivos, revogação individual, modo só-visualização | **Não implementado** | Existe só PIN + expiração + "desconectar" |
| Áudio, microfone | **Não implementado** | |
| Controles virtuais, gamepad (Modo Gaming) | **Não implementado** | |
| Segundo monitor real (IddCx) | **Não implementado** | Exige driver assinado; não dá para fazer sem Windows |
| App Android em Kotlin; servidor C++/Tauri | **Não implementado (decisão)** | Ver §5 |
| Instalador Windows, atualização automática, bandeja | **Não implementado** | |
| Teclado ao vivo, teclado/mouse Bluetooth | **Não implementado** | Há caixa de texto + atalhos |
| Monitoramento de CPU/GPU, bateria | **Não implementado** | |
| Vários monitores | **Não implementado** | Só o primário |

## 3. Resultados reais dos testes (rodei aqui)

| Suíte | Resultado |
|---|---|
| Python (stdlib + FFmpeg real) | **72 executados, 72 passaram** |
| Python — `test_server.py` (aiohttp) | **15 pulados** (aiohttp não instala sem internet) |
| Java no JVM — `UnitTests` | **175 verificações, 0 falhas** |
| Java no JVM — WebSocket x servidor Python independente | **12 verificações, 0 falhas** |
| Java no JVM — descoberta x responder real | **4 verificações, 0 falhas** |
| Compilação de todo o app Android (20 arquivos) | OK **contra stubs** (`-Xlint:all`) |
| `node --check` no JS do cliente web | OK |

Os testes acharam 4 erros meus no caminho (3 de teste, 1 de design: um 2º dedo encostando no fim de
um arrasto virava clique direito — corrigido).

**O que ficou SEM nenhum teste:** executar `server.py` (aiohttp); tudo que toca `user32` de verdade;
captura de tela real; o app em execução; Gradle; Wi-Fi/USB reais; qualquer coisa de GPU.

## 4. Desempenho medido

**Nenhuma latência ponta a ponta foi medida** (não há PC Windows nem celular aqui). O que medi são
microtempos do software, neste Linux:

| Componente | Medido |
|---|---|
| Framing + detecção de IDR + empacotamento v3 (stream 1080p60 real) | 807 MB/s · 21 µs/quadro (orçamento a 60 FPS: 16 667 µs) |
| `BacklogQueue.push` | 0,17 µs |
| `InputDispatcher.handle(move)` | 1,8 µs |
| "Segurar a AU até o próximo AUD" (modo normal) | custa exatamente **1 período de quadro** (medido a 1 e 2 FPS com FFmpeg real) |

Conclusão honesta: esses componentes **não são gargalo**. Onde está o atraso de verdade
(captura GDI, codificação, Wi-Fi, decoder) só dá para saber no seu hardware.

**Como medir a latência ponta a ponta (recomendado):** abra um cronômetro em milissegundos no PC,
coloque o celular ao lado do monitor e tire uma foto dos dois; a diferença entre os números é a
latência de exibição. Repita 10 vezes. Os indicadores do app marcam o que cada número é:
**"Medido"** (contado de verdade) ou **"Estimado*"** (RTT e idade do quadro por ping — erro de até
~RTT/2).

## 5. Decisões e limitações

* **Python + Java em vez de C++/Tauri/Kotlin.** Seu documento prefere C++/Tauri/Kotlin, mas pede para
  **não reescrever sem necessidade** e para trabalhar em etapas. Reescrever sem compilador do Windows
  nem do Android seria entregar código que nunca rodou. Kotlin/C++ entram quando houver o ciclo
  compilar-testar no seu PC. A arquitetura já está modular para isso trocar peça por peça.
* **FFmpeg pipe continua sendo o codificador.** Consequência: não dá para pedir IDR sob demanda;
  a recuperação depende do IDR periódico (1 s). Um encoder nativo (AMF direto) resolve — Fase 2.
* **`targetSdk 28` mantido.** Subir para 34/35 liga o modo edge-to-edge obrigatório e mexe no
  layout; só vale mexer com teste em aparelho.
* **`gradle-wrapper.jar` ausente** (binário; sem internet). O Android Studio usa o
  `gradle-wrapper.properties` sozinho. Na linha de comando: `gradle wrapper --gradle-version 8.9`.
* **Sem criptografia.** PIN + LAN privada. Quem estiver na sua rede pode ver o tráfego.
* **Chave de assinatura:** para atualizar o APK sem desinstalar, use SEMPRE a mesma chave. A chave de
  debug muda de PC para PC. Veja `android/COMO_GERAR_APK.txt`.

## 6. Como validar no seu PC (nesta ordem)

1. `cd windows` → `INICIAR_OPENMIRROR.bat`. No painel, olhe a linha do FFmpeg: ela diz se o FFmpeg,
   o x264, o DXGI e o AMD AMF existem **e se o AMF funciona de verdade**.
2. `python scripts/run_tests.py` (com o venv do projeto): os 15 testes do servidor devem **deixar de
   ser pulados** e passar. **Se algum falhar, me mande a saída.**
3. Compile o APK (`android/COMO_GERAR_APK.txt`). Se o Gradle reclamar de algo, me mande a mensagem:
   é a parte que mais precisa de olho de verdade.
4. Perfil **Equilibrado**, captura **gdigrab**: conecte e confira, nesta ordem: imagem, toque,
   **arrastar uma janela**, rolagem com 2 dedos, pinça, tela cheia, minimizar e voltar, desligar o
   Wi-Fi 10 s e religar.
5. Só depois teste captura **ddagrab/auto** e encoder **amd**. Cada falha aparece no Diagnóstico.
6. Compare FPS "Medido no PC" (codif/env) com "Medido no celular" (decod/exib).

## 7. Próximos passos sugeridos

**Fase 2 (desempenho):** validar DXGI/AMF no seu PC; se o FFmpeg pipe for o limite, capturar via
DXGI em C++ e codificar com AMF direto (permite IDR sob demanda e remove o AUD); bitrate adaptativo
usando as perdas por `seq` que o cliente já mede. **Fase 3:** TLS com impressão digital,
autorização de pareamento no PC, USB assistido, escolha automática por RTT/perdas.
