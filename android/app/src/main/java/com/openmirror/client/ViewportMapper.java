package com.openmirror.client;

/**
 * Geometria do vídeo dentro da tela: Ajustar / Preencher / Esticar, zoom e deslocamento,
 * e a conversão de um toque (px da view) em coordenadas 0..1 do vídeo.
 *
 * Java puro (sem android.*) para ser testável no JVM. O retângulo do vídeo pode ser MAIOR que
 * a view (zoom, ou Preencher cortando as bordas); a SurfaceView é posicionada com ele.
 */
final class ViewportMapper {
    enum Mode { FIT, FILL, STRETCH }

    static final float MAX_ZOOM = 4f;

    private int videoW = 16, videoH = 9, viewW = 1, viewH = 1;
    private Mode mode = Mode.FIT;
    private float zoom = 1f;
    /** Deslocamento do centro do vídeo em relação ao centro da view, em px. */
    private float panX = 0f, panY = 0f;

    void setVideo(int w, int h) {
        if (w > 0 && h > 0) { videoW = w; videoH = h; }
        clampPan();
    }

    void setView(int w, int h) {
        viewW = Math.max(1, w); viewH = Math.max(1, h);
        clampPan();
    }

    void setMode(Mode m) { mode = m; clampPan(); }
    Mode mode() { return mode; }
    float zoom() { return zoom; }
    boolean isZoomed() { return zoom > 1.001f; }
    void reset() { zoom = 1f; panX = 0f; panY = 0f; }

    private float baseW() {
        switch (mode) {
            case STRETCH: return viewW;
            case FILL: return videoW * Math.max((float) viewW / videoW, (float) viewH / videoH);
            default: return videoW * Math.min((float) viewW / videoW, (float) viewH / videoH);
        }
    }

    private float baseH() {
        switch (mode) {
            case STRETCH: return viewH;
            case FILL: return videoH * Math.max((float) viewW / videoW, (float) viewH / videoH);
            default: return videoH * Math.min((float) viewW / videoW, (float) viewH / videoH);
        }
    }

    float width() { return baseW() * zoom; }
    float height() { return baseH() * zoom; }
    float left() { return (viewW - width()) / 2f + panX; }
    float top() { return (viewH - height()) / 2f + panY; }

    /** out = {left, top, width, height} do vídeo, em px da view. */
    void rect(float[] out) {
        out[0] = left(); out[1] = top(); out[2] = width(); out[3] = height();
    }

    /** Muda o zoom mantendo parado o ponto (fx, fy) da tela (centro dos dedos na pinça). */
    void setZoomAround(float newZoom, float fx, float fy) {
        float u = (fx - left()) / width();
        float v = (fy - top()) / height();
        zoom = Math.max(1f, Math.min(MAX_ZOOM, newZoom));
        panX = (fx - u * width()) - (viewW - width()) / 2f;
        panY = (fy - v * height()) - (viewH - height()) / 2f;
        clampPan();
    }

    void panBy(float dx, float dy) {
        panX += dx; panY += dy;
        clampPan();
    }

    private void clampPan() {
        panX = clampAxis(panX, width(), viewW);
        panY = clampAxis(panY, height(), viewH);
    }

    private static float clampAxis(float pan, float size, float view) {
        if (size <= view) return 0f;               // menor que a tela: fica centralizado
        float max = (size - view) / 2f;            // maior: não deixa aparecer fundo além da borda
        return Math.max(-max, Math.min(max, pan));
    }

    /**
     * Toque (x, y em px da view) -> out = {u, v} em 0..1 do vídeo (limitado às bordas).
     * Retorna true se o toque caiu DENTRO da imagem (false = tarja preta ou parte cortada).
     */
    boolean map(float x, float y, float[] out) {
        float u = (x - left()) / width();
        float v = (y - top()) / height();
        boolean inside = u >= 0f && u <= 1f && v >= 0f && v <= 1f;
        out[0] = Math.max(0f, Math.min(1f, u));
        out[1] = Math.max(0f, Math.min(1f, v));
        return inside;
    }

    int viewWidth() { return viewW; }
    int viewHeight() { return viewH; }
}
