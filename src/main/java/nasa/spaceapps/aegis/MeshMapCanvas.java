package nasa.spaceapps.aegis;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Схема корабля: вузли екіпажу, радіозв'язки, пакети, що летять між ними,
 * хвилі SOS-маячка і рух помічників до потерпілого.
 */
public final class MeshMapCanvas extends Canvas {

    private static final double NODE_R = 25;

    private final CrewMeshNetwork net;
    private final Map<String, Image> avatars = new HashMap<>();

    public MeshMapCanvas(CrewMeshNetwork net) {
        this.net = net;
        for (CrewMemberState m : net.members()) {
            try {
                var in = MeshMapCanvas.class.getResourceAsStream("/images/" + m.getId().toLowerCase() + ".png");
                if (in != null) avatars.put(m.getId(), new Image(in));
            } catch (Exception ignored) {
                // без фото буде літера
            }
        }
    }

    private static Color statusColor(CrewMemberState m) {
        return Color.web(m.status().hex);
    }

    public void render(double now) {
        double w = getWidth(), h = getHeight();
        if (w < 50 || h < 50) return;
        GraphicsContext gc = getGraphicsContext2D();
        gc.setLineDashes((double[]) null);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setTextAlign(TextAlignment.LEFT);

        // фон і сітка
        gc.setFill(Color.web("#070D18"));
        gc.fillRect(0, 0, w, h);
        gc.setStroke(Color.rgb(30, 41, 59, 0.55));
        gc.setLineWidth(1);
        for (double x = 0; x < w; x += 28) gc.strokeLine(x, 0, x, h);
        for (double y = 0; y < h; y += 28) gc.strokeLine(0, y, w, y);

        // корпус корабля
        double mx = 18, my = 16;
        gc.setStroke(Color.web("#1E3A5F"));
        gc.setLineWidth(2);
        gc.strokeRoundRect(mx, my, w - 2 * mx, h - 2 * my, 46, 46);
        gc.setStroke(Color.rgb(56, 189, 248, 0.10));
        gc.setLineWidth(1);
        gc.strokeRoundRect(mx + 7, my + 7, w - 2 * mx - 14, h - 2 * my - 14, 40, 40);

        List<CrewMemberState> ms = net.members();
        double[] px = new double[ms.size()], py = new double[ms.size()];
        for (int i = 0; i < ms.size(); i++) {
            px[i] = mx + 40 + ms.get(i).getNx() * (w - 2 * mx - 80);
            py[i] = my + 34 + ms.get(i).getNy() * (h - 2 * my - 90);
        }

        // радіозв'язки
        for (int i = 0; i < ms.size(); i++) {
            for (int j = i + 1; j < ms.size(); j++) {
                boolean up = ms.get(i).isLinkUp() && ms.get(j).isLinkUp();
                gc.setLineWidth(1.4);
                if (up) {
                    gc.setLineDashes((double[]) null);
                    gc.setStroke(Color.rgb(56, 189, 248, 0.30));
                } else {
                    gc.setLineDashes(5, 6);
                    gc.setStroke(Color.rgb(148, 163, 184, 0.30));
                }
                gc.strokeLine(px[i], py[i], px[j], py[j]);
            }
        }
        gc.setLineDashes((double[]) null);

        // хвилі маячка
        for (int i = 0; i < ms.size(); i++) {
            CrewMemberState m = ms.get(i);
            if (m.isSosActive()) {
                for (int k = 0; k < 3; k++) {
                    double ph = ((now * 0.55) + k / 3.0) % 1.0;
                    double r = NODE_R + ph * 95;
                    gc.setStroke(Color.rgb(239, 68, 68, 0.60 * (1 - ph)));
                    gc.setLineWidth(2.2);
                    gc.strokeOval(px[i] - r, py[i] - r, 2 * r, 2 * r);
                }
            } else if (hasActiveAlert(m)) {
                double ph = (now * 1.1) % 1.0;
                double r = NODE_R + ph * 30;
                gc.setStroke(Color.rgb(245, 158, 11, 0.7 * (1 - ph)));
                gc.setLineWidth(2);
                gc.strokeOval(px[i] - r, py[i] - r, 2 * r, 2 * r);
            }
        }

        // помічники в дорозі
        for (int v = 0; v < ms.size(); v++) {
            for (CrewMeshNetwork.Helper hp : net.helpersOf(ms.get(v))) {
                int hi = ms.indexOf(hp.helper());
                double t = hp.alert().progress(now);
                gc.setLineDashes(7, 6);
                gc.setStroke(Color.rgb(16, 185, 129, 0.75));
                gc.setLineWidth(2);
                gc.strokeLine(px[hi], py[hi], px[v], py[v]);
                gc.setLineDashes((double[]) null);
                double x = px[hi] + (px[v] - px[hi]) * t, y = py[hi] + (py[v] - py[hi]) * t;
                glow(gc, x, y, 5, Color.web("#10B981"));
                if (!hp.alert().arrived) {
                    gc.setFill(Color.web("#6EE7B7"));
                    gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
                    gc.fillText("🏃 " + Math.max(0, Math.round(hp.alert().respondDuration * (1 - t))) + " с", x + 9, y - 7);
                }
            }
        }

        // пакети
        for (CrewMeshNetwork.Packet p : net.packets()) {
            double t = p.progress(now);
            if (t < 0 || t > 1) continue;
            int a = ms.indexOf(p.from), b = ms.indexOf(p.to);
            if (a < 0 || b < 0) continue;
            Color c;
            double size;
            switch (p.type) {
                case EMERGENCY -> { c = Color.web("#EF4444"); size = 6; }
                case ACK, PONG -> { c = Color.web("#10B981"); size = 4.5; }
                case PING -> { c = Color.web("#A855F7"); size = 4.5; }
                default -> { c = Color.web("#38BDF8"); size = 2.8; }
            }
            if (p.type == CrewMeshNetwork.PacketType.EMERGENCY) {
                for (int k = 4; k >= 1; k--) {
                    double tt = Math.max(0, t - k * 0.035);
                    glow(gc, px[a] + (px[b] - px[a]) * tt, py[a] + (py[b] - py[a]) * tt, size * (1 - k * 0.15),
                            HoloUi.alpha(c, 0.35 / k));
                }
            }
            glow(gc, px[a] + (px[b] - px[a]) * t, py[a] + (py[b] - py[a]) * t, size, c);
        }

        // вузли
        for (int i = 0; i < ms.size(); i++) drawNode(gc, ms.get(i), px[i], py[i], now);

        // підпис
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.web("#475569"));
        gc.setFont(Font.font("Consolas", 10));
        gc.fillText("LOCAL MESH · BLE / UWB · БЕЗ ЦЕНТРАЛЬНОГО СЕРВЕРА", mx + 14, h - my - 8);
    }

    private boolean hasActiveAlert(CrewMemberState m) {
        for (CrewMemberState.IncomingAlert al : m.getAlerts().values()) {
            if (!al.muted) return true;
        }
        return false;
    }

    private void drawNode(GraphicsContext gc, CrewMemberState m, double x, double y, double now) {
        Color sc = statusColor(m);
        boolean off = m.isOffline();

        glow(gc, x, y, NODE_R * 2 + 4, HoloUi.alpha(sc, off ? 0.05 : 0.14));

        // фото в колі
        gc.save();
        gc.beginPath();
        gc.arc(x, y, NODE_R - 2, NODE_R - 2, 0, 360);
        gc.closePath();
        gc.clip();
        Image img = avatars.get(m.getId());
        gc.setFill(Color.web("#14213A"));
        gc.fillRect(x - NODE_R, y - NODE_R, 2 * NODE_R, 2 * NODE_R);
        if (img != null) {
            gc.setGlobalAlpha(off ? 0.35 : 1.0);
            gc.drawImage(img, x - NODE_R + 2, y - NODE_R + 2, 2 * NODE_R - 4, 2 * NODE_R - 4);
            gc.setGlobalAlpha(1.0);
        } else {
            gc.setFill(Color.web("#E2E8F0"));
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(m.shortName().substring(0, 1), x, y + 7);
        }
        gc.restore();

        // кільце статусу
        gc.setLineWidth(3);
        gc.setStroke(sc);
        gc.strokeOval(x - NODE_R, y - NODE_R, 2 * NODE_R, 2 * NODE_R);

        if (off) {
            gc.setStroke(Color.web("#F87171"));
            gc.setLineWidth(3);
            gc.strokeLine(x - 9, y - 9, x + 9, y + 9);
            gc.strokeLine(x - 9, y + 9, x + 9, y - 9);
        }

        // підписи
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFill(Color.web("#E2E8F0"));
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        gc.fillText(m.shortName() + (m.isLocal() ? " (ви)" : ""), x, y + NODE_R + 14);
        gc.setFill(off ? Color.web("#94A3B8") : sc);
        gc.setFont(Font.font("Consolas", FontWeight.BOLD, 11));
        gc.fillText(off ? "немає сигналу" : "♥ " + Math.round(m.getHr()) + " · HRV " + Math.round(m.getHrv()), x, y + NODE_R + 27);
        gc.setFill(Color.web("#64748B"));
        gc.setFont(Font.font("Segoe UI", 9));
        gc.fillText(m.getCompartment(), x, y - NODE_R - 6);
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private static void glow(GraphicsContext gc, double x, double y, double size, Color c) {
        gc.setFill(HoloUi.alpha(c, c.getOpacity() * 0.22));
        double halo = size * 2.8;
        gc.fillOval(x - halo / 2, y - halo / 2, halo, halo);
        gc.setFill(c);
        gc.fillOval(x - size / 2, y - size / 2, size, size);
    }
}
