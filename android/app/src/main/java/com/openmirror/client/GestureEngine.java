package com.openmirror.client;

/**
 * Máquina de estados dos gestos. Recebe toques já simplificados (até 2 dedos) e emite comandos
 * de mouse para um {@link Sink}. Java puro: o relógio e os timers entram por interface, então
 * dá para testar no JVM com tempo falso.
 *
 * Modo TOUCH (absoluto): o ponteiro vai para onde o dedo toca.
 *   1 toque = clique · 2 toques rápidos = duplo clique · toque longo = clique direito
 *   arrastar = botão esquerdo PRESSIONADO de verdade (down ... move ... up)
 *   2 dedos: rolagem (deslizar) ou zoom da imagem (pinça) · toque de 2 dedos = clique direito
 * Modo TRACKPAD (relativo): o dedo move o cursor virtual; toque = clique no cursor;
 *   toque longo = segurar o botão (arrastar); o resto igual.
 */
final class GestureEngine {
    static final int LEFT = 0, RIGHT = 1;

    interface Sink {
        void move(float u, float v);
        void down(float u, float v, int button);
        void up(float u, float v, int button);
        void tap(float u, float v);
        void rightClick(float u, float v);
        /** Unidades de 1/120 de "clique" da roda: dy>0 rola para cima, dx>0 para a direita. */
        void scroll(int dx, int dy);
        void releaseAll();
    }

    interface Host { void onViewportChanged(); }
    interface Timers { void post(Runnable r, long delayMs); void cancel(Runnable r); }

    enum PointerMode { TOUCH, TRACKPAD }
    enum Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }
    private enum State { IDLE, PENDING, LONG_DONE, DRAGGING, TRACK_MOVING, TRACK_DRAG,
                         TWO_UNDECIDED, PINCH, SCROLL, IGNORE }

    private static final long TWO_FINGER_TAP_MS = 350;
    private static final float MIN_SCROLL_UNITS = 24f;

    private final Sink sink;
    private final Host host;
    private final ViewportMapper mapper;
    private final Timers timers;
    private final float slop, pinchSlop, scrollSlop, pxPerNotch;

    private PointerMode pointerMode = PointerMode.TOUCH;
    private float sensitivity = 1.6f;
    private long longPressMs = 500;

    private State state = State.IDLE;
    private float downX, downY, lastX, lastY;
    private float startU, startV;
    private boolean startInside;
    private float cursorU = .5f, cursorV = .5f;
    private float startDist, startCx, startCy, lastDist, lastCx, lastCy, accX, accY;
    private long twoStart;
    private boolean twoTapCandidate;
    private final float[] tmp = new float[2];

    private final Runnable longPress = this::onLongPress;

    GestureEngine(Sink sink, Host host, ViewportMapper mapper, Timers timers, float density) {
        this.sink = sink; this.host = host; this.mapper = mapper; this.timers = timers;
        slop = 8 * density; pinchSlop = 18 * density; scrollSlop = 10 * density;
        pxPerNotch = 40 * density;
    }

    void setPointerMode(PointerMode m) { pointerMode = m; }
    PointerMode pointerMode() { return pointerMode; }
    void setSensitivity(float s) { sensitivity = Math.max(0.3f, Math.min(6f, s)); }
    float sensitivity() { return sensitivity; }
    void setLongPressMs(long ms) { longPressMs = ms; }
    float cursorU() { return cursorU; }
    float cursorV() { return cursorV; }

    // ------------------------------------------------------------------ entrada
    void onTouch(Action a, int count, float x0, float y0, float x1, float y1, long now) {
        switch (a) {
            case DOWN: onDown(x0, y0); break;
            case POINTER_DOWN: onPointerDown(count, x0, y0, x1, y1, now); break;
            case MOVE: onMove(count, x0, y0, x1, y1); break;
            case POINTER_UP: onPointerUp(now); break;
            case UP: onUp(x0, y0); break;
            default: onCancel(); break;
        }
    }

    private void onDown(float x, float y) {
        timers.cancel(longPress);
        downX = lastX = x; downY = lastY = y;
        startInside = mapper.map(x, y, tmp);
        startU = tmp[0]; startV = tmp[1];
        state = State.PENDING;
        timers.post(longPress, longPressMs);
    }

    private void onLongPress() {
        if (state != State.PENDING) return;
        if (pointerMode == PointerMode.TRACKPAD) {
            sink.down(cursorU, cursorV, LEFT);
            state = State.TRACK_DRAG;
        } else {
            if (startInside) sink.rightClick(startU, startV);
            state = State.LONG_DONE;
        }
    }

    private void onPointerDown(int count, float x0, float y0, float x1, float y1, long now) {
        timers.cancel(longPress);
        // Só vira "toque de 2 dedos" se o gesto começou com o dedo parado (PENDING): um segundo
        // dedo que encosta sem querer no fim de um arrasto não pode virar clique direito.
        twoTapCandidate = state == State.PENDING;
        endDragIfAny();
        if (count >= 3 || state == State.LONG_DONE || state == State.IGNORE || state == State.IDLE) {
            state = State.IGNORE;
            return;
        }
        state = State.TWO_UNDECIDED;
        startDist = lastDist = (float) Math.hypot(x1 - x0, y1 - y0);
        startCx = lastCx = (x0 + x1) / 2f; startCy = lastCy = (y0 + y1) / 2f;
        accX = accY = 0f;
        twoStart = now;
    }

    private void endDragIfAny() {
        if (state == State.DRAGGING) {
            mapper.map(lastX, lastY, tmp);
            sink.up(tmp[0], tmp[1], LEFT);
        } else if (state == State.TRACK_DRAG) {
            sink.up(cursorU, cursorV, LEFT);
        }
    }

    private void onMove(int count, float x0, float y0, float x1, float y1) {
        switch (state) {
            case PENDING:
                if (count == 1 && Math.hypot(x0 - downX, y0 - downY) > slop) {
                    timers.cancel(longPress);
                    if (pointerMode == PointerMode.TRACKPAD) {
                        state = State.TRACK_MOVING;
                        trackMove(x0, y0);
                    } else if (startInside) {
                        sink.down(startU, startV, LEFT);   // o arrasto começa onde o dedo tocou
                        state = State.DRAGGING;
                        dragMove(x0, y0);
                    } else {
                        state = State.IGNORE;              // começou na tarja preta: não faz nada
                    }
                }
                break;
            case DRAGGING: dragMove(x0, y0); break;
            case TRACK_MOVING:
            case TRACK_DRAG: trackMove(x0, y0); break;
            case TWO_UNDECIDED: decideTwoFingers(x0, y0, x1, y1); break;
            case PINCH: pinch(x0, y0, x1, y1); break;
            case SCROLL: scrollMove(x0, y0, x1, y1); break;
            default: break;
        }
    }

    private void dragMove(float x, float y) {
        lastX = x; lastY = y;
        mapper.map(x, y, tmp);
        sink.move(tmp[0], tmp[1]);
    }

    private void trackMove(float x, float y) {
        float du = (x - lastX) / mapper.viewWidth() * sensitivity;
        float dv = (y - lastY) / mapper.viewHeight() * sensitivity;
        lastX = x; lastY = y;
        cursorU = Math.max(0f, Math.min(1f, cursorU + du));
        cursorV = Math.max(0f, Math.min(1f, cursorV + dv));
        sink.move(cursorU, cursorV);
    }

    private void decideTwoFingers(float x0, float y0, float x1, float y1) {
        float dist = (float) Math.hypot(x1 - x0, y1 - y0);
        float cx = (x0 + x1) / 2f, cy = (y0 + y1) / 2f;
        if (Math.abs(dist - startDist) > pinchSlop) {
            state = State.PINCH;
            lastDist = dist; lastCx = cx; lastCy = cy;
        } else if (Math.hypot(cx - startCx, cy - startCy) > scrollSlop) {
            state = State.SCROLL;
            lastCx = cx; lastCy = cy;
        }
    }

    private void pinch(float x0, float y0, float x1, float y1) {
        float dist = (float) Math.hypot(x1 - x0, y1 - y0);
        float cx = (x0 + x1) / 2f, cy = (y0 + y1) / 2f;
        if (dist > 1f && lastDist > 1f) {
            mapper.setZoomAround(mapper.zoom() * dist / lastDist, cx, cy);
            mapper.panBy(cx - lastCx, cy - lastCy);
            host.onViewportChanged();
        }
        lastDist = dist; lastCx = cx; lastCy = cy;
    }

    private void scrollMove(float x0, float y0, float x1, float y1) {
        float cx = (x0 + x1) / 2f, cy = (y0 + y1) / 2f;
        accY += cy - lastCy;     // dedos descem  -> conteúdo desce  -> roda para cima (+)
        accX -= cx - lastCx;     // dedos à direita -> conteúdo à direita -> roda para a esquerda (-)
        lastCx = cx; lastCy = cy;
        int dy = takeUnits(true), dx = takeUnits(false);
        if (dx != 0 || dy != 0) sink.scroll(dx, dy);
    }

    /** Converte o acumulado em px para unidades da roda, só quando passa do mínimo. */
    private int takeUnits(boolean vertical) {
        float acc = vertical ? accY : accX;
        float units = acc * 120f / pxPerNotch;
        if (Math.abs(units) < MIN_SCROLL_UNITS) return 0;
        int whole = (int) units;
        float consumed = whole * pxPerNotch / 120f;
        if (vertical) accY -= consumed; else accX -= consumed;
        return whole;
    }

    private void onPointerUp(long now) {
        if (state == State.TWO_UNDECIDED && twoTapCandidate && now - twoStart <= TWO_FINGER_TAP_MS) {
            if (pointerMode == PointerMode.TRACKPAD) {
                sink.rightClick(cursorU, cursorV);
            } else if (mapper.map(lastCx, lastCy, tmp)) {
                sink.rightClick(tmp[0], tmp[1]);
            }
        }
        state = State.IGNORE;   // o dedo que sobrou não pode virar clique/arrasto
    }

    private void onUp(float x, float y) {
        timers.cancel(longPress);
        switch (state) {
            case PENDING:
                if (pointerMode == PointerMode.TRACKPAD) sink.tap(cursorU, cursorV);
                else if (startInside) sink.tap(startU, startV);
                break;
            case DRAGGING:
                mapper.map(x, y, tmp);
                sink.up(tmp[0], tmp[1], LEFT);
                break;
            case TRACK_DRAG:
                sink.up(cursorU, cursorV, LEFT);
                break;
            default: break;
        }
        state = State.IDLE;
    }

    private void onCancel() {
        timers.cancel(longPress);
        boolean held = state == State.DRAGGING || state == State.TRACK_DRAG;
        state = State.IDLE;
        if (held) sink.releaseAll();
    }

    /** Chamar quando a tela perde foco/pausa: nada de botão preso no PC. */
    void cancelAll() {
        timers.cancel(longPress);
        state = State.IDLE;
        sink.releaseAll();
    }
}
