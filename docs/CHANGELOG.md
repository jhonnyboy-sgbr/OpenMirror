# Alterações — 0.3.1 em comparação com 0.3.0 (somente o app Android)

**Motivo:** na 0.3.0 o botão «Conectar em H.264 nativo» fechava o app sem explicação e a tela cheia não funcionava no modo compatível.

**Novo**
- `CrashLog` + `OpenMirrorApp`: handler global de erros. O erro (com pilha de chamadas e dados do aparelho) é gravado em arquivo ANTES de o app fechar; na próxima abertura a tela inicial mostra o texto com botão **Copiar e limpar**. Erros capturados (que não fecham o app) também entram no registro, sem repetição em sequência, limitado a 12 KB.
- `NativeStreamActivity`: o `onCreate` agora é protegido. Se falhar, aparece uma tela com o erro (em vez de fechar) e o erro fica gravado. As etapas não essenciais (orientação, recorte da tela, tela cheia, gestos de borda, insets) rodam isoladas: se o aparelho recusar uma delas, o app segue e o aviso aparece em **Diagnóstico**. Toques e botões também são protegidos.
- `StreamSession`/`WsClient`: nenhuma thread de fundo derruba mais o app (captura de `Throwable` na sessão, na leitura de vídeo, nas estatísticas e no WebSocket).
- Modo compatível (WebView): abre sem barras do sistema, botão **Tela cheia** (esconde também a barra do app; sair pelo Voltar do Android) e suporte ao `requestFullscreen()` da página via `WebChromeClient`. Barras do sistema voltam na tela inicial.
- `Immersive`: tela cheia compartilhada pelas duas telas, com falhas isoladas.

**Versão:** app 0.3.1 (versionCode 4). Servidor e web continuam 0.3.0.

**Testes (neste Linux):** 72 Python passaram (+15 do servidor HTTP pulados); 175 + 12 + 4 verificações Java no JVM (13 novas do `CrashLog`); 20 arquivos Java compilados contra stubs do SDK. **Nada foi executado num celular.**

**Não resolvido:** a CAUSA do crash do H.264 continua desconhecida (precisa do log do app ou do logcat). O FPS dos perfis não foi investigado.

---

# Alterações — 0.3.0 em comparação com 0.2.0

**Corrigido**
- Arrastar agora pressiona o botão de verdade (`down`/`up`); antes só movia o cursor. Também no cliente web.
- App: vídeo não congela mais ao minimizar/voltar (uma sessão nova por Surface; reconexão automática com espera de 1 a 8 s).
- App: tela cheia reaplicada ao recuperar foco; vídeo usa a área do recorte; controles respeitam o recorte; gesto de voltar do Android não rouba arrastos nas bordas.
- App: leitura de rede em thread própria; decoder só desenha o quadro mais novo; fila descarta até o próximo IDR em vez de acumular atraso.
- Servidor: fila de 3 quadros (era 1) — soluços de Wi-Fi não congelam mais a imagem por até 1 s.
- Servidor: PIN/JSON inválido dá 401/400 (antes podia dar 500); sessões expiram (30 min ocioso, 12 h total, máx. 4).
- Servidor solta botões/teclas presos quando o WebSocket fecha e após 30 s sem comandos.
- Teclas estendidas (setas, Home, Delete…) enviadas com a flag correta.
- PIN passa por variável de ambiente (não aparece mais na linha de comando).

**Novo**
- Entrada do app por WebSocket (HTTP com keep-alive de reserva).
- `/api/hello`: negociação de versão e capacidades; protocolo de vídeo **v3** (`seq` + timestamp por quadro) além do v2.
- Ajustar / Preencher / Esticar, zoom por pinça com mapeamento correto do toque, modo Trackpad, atalhos do Windows, rotação manual, indicadores, diagnóstico.
- Descoberta do PC na LAN (UDP 8766); botão "Procurar PC na rede".
- Opções `--capture ddagrab|auto` (DXGI, **experimental, não validado no Windows**), `--backlog`, `--eager-flush` (experimental), `--probe`; o painel testa se o AMD AMF funciona de verdade.
- Perfis: Competitivo, Equilibrado, Qualidade (metas).
- Módulos novos no servidor: `om_input`, `om_controller`, `om_security`, `om_metrics`, `om_discovery`, `om_presets`.
- Gradle: `gradle-wrapper.properties` (8.9), assinatura de release por `keystore.properties`.
- `scripts/run_tests.py` e `scripts/check_android_java.py`.

**Testes (neste Linux, sem Windows/Android):** 72 Python passaram (+15 do servidor HTTP pulados: sem aiohttp);
162 + 12 + 4 verificações Java no JVM; app compilado contra stubs. **Nada disso substitui testar no PC e no celular.**

**Compatibilidade:** app 0.3.0 + servidor 0.2.0 funciona (sem arrastar de verdade nem atalhos). Servidor 0.3.0 + app 0.2.0 funciona (v2).
**Mudou:** o servidor deixou de aceitar sessão na URL em `/api/*` (só em `/stream`, `/stream/h264`, `/ws/input`).

---

# Alterações — 0.2.0 em comparação com 0.1.1

- Mantida a captura MJPEG compartilhada da versão anterior, para retrocompatibilidade.
- Adicionada conexão WebSocket autenticada `/ws/input` para comandos do navegador sem POST individual por arrasto.
- Adicionado botão de tela cheia web.
- Criado encoder H.264 de baixa latência via FFmpeg `gdigrab` → libx264/AMF opcional.
- Criado parser incremental de unidades H.264 Annex B com delimitador AUD e envelope binário `OMH2`.
- Primeiro pacote H.264 exige SPS/PPS/IDR, para inicialização do `MediaCodec`.
- Criado cliente Android nativo Java + `MediaCodec` + `SurfaceView`, com gestos, teclado, paisagem e controles ocultáveis.
- Adicionados perfis no painel do Windows e telemetria de FPS e RTT de rede.
- Mantido modo compatibilidade WebView, USB ADB reverse e PIN de pareamento.
- 12 testes Python passaram; Android e fluxo H.264 real no Windows ainda precisam de testes de campo.
