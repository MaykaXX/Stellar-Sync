package nasa.spaceapps.aegis;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Схема корабля: вузли екіпажу, радіозв'язки, пакети, що летять між ними, хвилі SOS-маячка.
 * <p>
 * Коли отримувач приймає запит («Іду на допомогу»), його аватар сам іде відсіками до потерпілого
 * (на його місці лишається порожнє «місце-привид»), біля потерпілого стоїть і надає допомогу
 * (зелені хвилі, значок «+», промінь між ними), а після відбою повертається на свій пост.
 */
public final class MeshMapCanvas extends Canvas {

    private static final double NODE_R = 25;
    /** На скільки пікселів збоку від потерпілого зупиняється той, хто допомагає. */
    private static final double HELP_OFFSET = 88;
    /** Швидкість повернення на пост, пікселів за секунду. */
    private static final double RETURN_SPEED = 40;
    private static final Color GREEN = Color.web("#10B981");

    private final CrewMeshNetwork net;
    private final Map<String, Image> avatars = new HashMap<>();
    /** Де зараз «ходить» член екіпажу, коли він не на своєму місці (частки від розміру полотна). */
    private final Map<String, double[]> away = new HashMap<>();
    private double lastNow = -1;

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

    private static int indexOfId(List<CrewMemberState> ms, String id) {
        for (int i = 0; i < ms.size(); i++) if (ms.get(i).getId().equals(id)) return i;
        return -1;
    }

    public void render(double now) {
        double w = getWidth(), h = getHeight();
        if (w < 50 || h < 50) return;
        double dt = lastNow < 0 ? 0 : Math.min(0.25, Math.max(0, now - lastNow));
        lastNow = now;

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
        int n = ms.size();

        // «свої місця» у відсіках
        double[] hx = new double[n], hy = new double[n];
        for (int i = 0; i < n; i++) {
            hx[i] = mx + 40 + ms.get(i).getNx() * (w - 2 * mx - 80);
            hy[i] = my + 34 + ms.get(i).getNy() * (h - 2 * my - 90);
        }

        // хто зараз іде комусь на допомогу
        int[] goesTo = new int[n];
        Arrays.fill(goesTo, -1);
        CrewMemberState.IncomingAlert[] job = new CrewMemberState.IncomingAlert[n];
        for (int i = 0; i < n; i++) {
            for (CrewMemberState.IncomingAlert al : ms.get(i).getAlerts().values()) {
                if (!al.responding) continue;
                int v = indexOfId(ms, al.senderId);
                if (v >= 0 && v != i) {
                    goesTo[i] = v;
                    job[i] = al;
                    break;
                }
            }
        }

        // де кожен стоїть просто зараз
        double[] cx = hx.clone(), cy = hy.clone();
        double[] spotX = new double[n], spotY = new double[n];
        boolean[] walking = new boolean[n], helping = new boolean[n], returning = new boolean[n];
        boolean[] helped = new boolean[n];
        for (int i = 0; i < n; i++) {
            CrewMemberState m = ms.get(i);
            if (goesTo[i] >= 0) {
                int v = goesTo[i];
                double dir = Math.signum(hx[i] - hx[v]);
                if (dir == 0) dir = -1;
                double sx = hx[v] + dir * HELP_OFFSET;
                if (sx < mx + 34 || sx > w - mx - 34) {
                    dir = -dir;
                    sx = hx[v] + dir * HELP_OFFSET;
                }
                double sy = hy[v];
                spotX[i] = sx;
                spotY[i] = sy;
                double t = job[i].progress(now);
                cx[i] = hx[i] + (sx - hx[i]) * t;
                cy[i] = hy[i] + (sy - hy[i]) * t;
                if (job[i].arrived) {
                    helping[i] = true;
                    helped[v] = true;
                } else {
                    walking[i] = true;
                }
                away.put(m.getId(), new double[]{cx[i] / w, cy[i] / h});
            } else {
                double[] a = away.get(m.getId());
                if (a != null) {
                    double ax = a[0] * w, ay = a[1] * h;
                    double dx = hx[i] - ax, dy = hy[i] - ay;
                    double dist = Math.hypot(dx, dy), step = RETURN_SPEED * dt;
                    if (dist <= Math.max(step, 1.0)) {
                        away.remove(m.getId());
                    } else {
                        ax += dx / dist * step;
                        ay += dy / dist * step;
                        a[0] = ax / w;
                        a[1] = ay / h;
                        cx[i] = ax;
                        cy[i] = ay;
                        returning[i] = true;
                    }
                }
            }
        }

        // радіозв'язки
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                boolean up = ms.get(i).isLinkUp() && ms.get(j).isLinkUp();
                gc.setLineWidth(1.4);
                if (up) {
                    gc.setLineDashes((double[]) null);
                    gc.setStroke(Color.rgb(56, 189, 248, 0.30));
                } else {
                    gc.setLineDashes(5, 6);
                    gc.setStroke(Color.rgb(148, 163, 184, 0.30));
                }
                gc.strokeLine(hx[i], hy[i], hx[j], hy[j]);
            }
        }
        gc.setLineDashes((double[]) null);

        // хвилі маячка (червоні: чекає допомоги; зелені малює drawHelpedMarks, коли допомога вже поруч)
        for (int i = 0; i < n; i++) {
            CrewMemberState m = ms.get(i);
            if (m.isSosActive() && !helped[i]) {
                for (int k = 0; k < 3; k++) {
                    double ph = ((now * 0.55) + k / 3.0) % 1.0;
                    double r = NODE_R + ph * 95;
                    gc.setStroke(Color.rgb(239, 68, 68, 0.60 * (1 - ph)));
                    gc.setLineWidth(2.2);
                    gc.strokeOval(hx[i] - r, hy[i] - r, 2 * r, 2 * r);
                }
            } else if (!helped[i] && hasActiveAlert(m)) {
                double ph = (now * 1.1) % 1.0;
                double r = NODE_R + ph * 30;
                gc.setStroke(Color.rgb(245, 158, 11, 0.7 * (1 - ph)));
                gc.setLineWidth(2);
                gc.strokeOval(hx[i] - r, hy[i] - r, 2 * r, 2 * r);
            }
        }

        // шлях помічника: пунктир до місця зустрічі, пройдена частина суцільна; промінь допомоги
        for (int i = 0; i < n; i++) {
            if (goesTo[i] >= 0) {
                gc.setLineDashes(7, 6);
                gc.setStroke(Color.rgb(16, 185, 129, 0.55));
                gc.setLineWidth(2);
                gc.strokeLine(hx[i], hy[i], spotX[i], spotY[i]);
                gc.setLineDashes((double[]) null);
                gc.setStroke(Color.rgb(16, 185, 129, 0.90));
                gc.setLineWidth(2.8);
                gc.strokeLine(hx[i], hy[i], cx[i], cy[i]);
                if (helping[i]) {
                    int v = goesTo[i];
                    gc.setStroke(Color.rgb(110, 231, 183, 0.40 + 0.25 * Math.sin(now * 5)));
                    gc.setLineWidth(4);
                    gc.strokeLine(cx[i], cy[i], hx[v], hy[v]);
                }
            } else if (returning[i]) {
                gc.setLineDashes(3, 6);
                gc.setStroke(Color.rgb(148, 163, 184, 0.40));
                gc.setLineWidth(1.6);
                gc.strokeLine(cx[i], cy[i], hx[i], hy[i]);
                gc.setLineDashes((double[]) null);
            }
        }

        // пакети (летять між «своїми місцями»: радіо не залежить від того, хто де ходить)
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
                    glow(gc, hx[a] + (hx[b] - hx[a]) * tt, hy[a] + (hy[b] - hy[a]) * tt, size * (1 - k * 0.15),
                            HoloUi.alpha(c, 0.35 / k));
                }
            }
            glow(gc, hx[a] + (hx[b] - hx[a]) * t, hy[a] + (hy[b] - hy[a]) * t, size, c);
        }

        // вузли на своїх місцях (хто пішов, лишає порожній контур)
        for (int i = 0; i < n; i++) {
            boolean left = walking[i] || helping[i] || returning[i];
            if (left) drawGhost(gc, ms.get(i), hx[i], hy[i]);
            else drawNode(gc, ms.get(i), hx[i], hy[i], false, null);
        }

        // потерпілі, яким уже допомагають
        for (int i = 0; i < n; i++) {
            if (helped[i]) drawHelpedMarks(gc, hx[i], hy[i], now);
        }

        // космонавти в русі: вони поверх усього
        for (int i = 0; i < n; i++) {
            if (!(walking[i] || helping[i] || returning[i])) continue;
            CrewMemberState m = ms.get(i);
            double bob = (walking[i] || returning[i]) ? -Math.abs(Math.sin(now * 9)) * 3 : 0;

            gc.setFill(Color.rgb(0, 0, 0, 0.35));
            gc.fillOval(cx[i] - 16, cy[i] + NODE_R - 3, 32, 8);

            String caption;
            if (walking[i]) {
                double left = Math.max(0, Math.round(job[i].respondDuration * (1 - job[i].progress(now))));
                caption = "🏃 до " + ms.get(goesTo[i]).shortName() + " · " + (int) left + " с";
            } else if (helping[i]) {
                caption = "🤝 допомагає: " + ms.get(goesTo[i]).shortName();
            } else {
                caption = "↩ повертається на пост";
            }
            drawNode(gc, m, cx[i], cy[i] + bob, true, caption);

            // репліки: «запит прийнято» на старті, «я поруч» одразу після прибуття
            double bubbleY = cy[i] + bob - NODE_R - 34;
            if (job[i] != null) {
                double arrivedAt = job[i].respondStart + job[i].respondDuration;
                if (now - job[i].respondStart < 2.6 && !job[i].arrived) {
                    bubble(gc, cx[i], bubbleY, w, "✔ Запит прийнято, іду!", GREEN);
                } else if (job[i].arrived && now - arrivedAt < 3.2) {
                    bubble(gc, cx[i], bubbleY, w, "Я поруч, допомагаю", GREEN);
                }
            }
        }

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

    /** Порожнє місце того, хто пішов допомагати. */
    private void drawGhost(GraphicsContext gc, CrewMemberState m, double x, double y) {
        gc.setStroke(Color.rgb(148, 163, 184, 0.45));
        gc.setLineWidth(1.6);
        gc.setLineDashes(4, 5);
        gc.strokeOval(x - NODE_R, y - NODE_R, 2 * NODE_R, 2 * NODE_R);
        gc.setLineDashes((double[]) null);

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFill(Color.rgb(148, 163, 184, 0.75));
        gc.setFont(Font.font("Segoe UI", 9));
        gc.fillText(m.shortName(), x, y + 3);
        gc.setFill(Color.web("#64748B"));
        gc.fillText(m.getCompartment(), x, y - NODE_R - 6);
        gc.fillText("пішов допомагати", x, y + NODE_R + 14);
        gc.setTextAlign(TextAlignment.LEFT);
    }

    /**
     * Вузол-аватар. responder=true: той, хто йде на допомогу (зелений пунктирний обідок, підпис над фото).
     */
    private void drawNode(GraphicsContext gc, CrewMemberState m, double x, double y, boolean responder, String caption) {
        Color sc = statusColor(m);
        boolean off = m.isOffline();

        glow(gc, x, y, NODE_R * 2 + 4, HoloUi.alpha(sc, off ? 0.05 : 0.14));

        if (responder) {
            gc.setStroke(GREEN);
            gc.setLineWidth(2);
            gc.setLineDashes(4, 4);
            gc.strokeOval(x - NODE_R - 5, y - NODE_R - 5, 2 * NODE_R + 10, 2 * NODE_R + 10);
            gc.setLineDashes((double[]) null);
        }

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
        String name = m.shortName() + (m.isLocal() ? " (ви)" : "");
        if (responder) {
            gc.setFill(Color.web("#6EE7B7"));
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
            gc.fillText(caption, x, y - NODE_R - 20);
            gc.setFill(Color.web("#E2E8F0"));
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
            gc.fillText(name, x, y - NODE_R - 7);
        } else {
            gc.setFill(Color.web("#E2E8F0"));
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
            gc.fillText(name, x, y + NODE_R + 14);
            gc.setFill(off ? Color.web("#94A3B8") : sc);
            gc.setFont(Font.font("Consolas", FontWeight.BOLD, 11));
            gc.fillText(off ? "немає сигналу" : "♥ " + Math.round(m.getHr()) + " · HRV " + Math.round(m.getHrv()), x, y + NODE_R + 27);
            gc.setFill(Color.web("#64748B"));
            gc.setFont(Font.font("Segoe UI", 9));
            gc.fillText(m.getCompartment(), x, y - NODE_R - 6);
        }
        gc.setTextAlign(TextAlignment.LEFT);
    }

    /** Те, що видно біля потерпілого, коли допомога вже на місці: зелені хвилі, значок «+», плюсики вгору. */
    private void drawHelpedMarks(GraphicsContext gc, double x, double y, double now) {
        for (int k = 0; k < 2; k++) {
            double ph = ((now * 0.35) + k / 2.0) % 1.0;
            double r = NODE_R + 4 + ph * 38;
            gc.setStroke(Color.rgb(16, 185, 129, 0.55 * (1 - ph)));
            gc.setLineWidth(2);
            gc.strokeOval(x - r, y - r, 2 * r, 2 * r);
        }

        double bx = x + NODE_R * 0.8, by = y - NODE_R * 0.8;
        gc.setFill(GREEN);
        gc.fillOval(bx - 9, by - 9, 18, 18);
        gc.setFill(Color.WHITE);
        gc.fillRoundRect(bx - 5, by - 1.5, 10, 3, 2, 2);
        gc.fillRoundRect(bx - 1.5, by - 5, 3, 10, 2, 2);

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 15));
        for (int k = 0; k < 3; k++) {
            double ph = ((now * 0.5) + k / 3.0) % 1.0;
            gc.setFill(Color.rgb(110, 231, 183, 0.9 * (1 - ph)));
            gc.fillText("+", x + (k - 1) * 16, y - NODE_R - 2 - ph * 30);
        }
        gc.setFill(Color.web("#6EE7B7"));
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
        gc.fillText("🤝 допомога надається", x, y + NODE_R + 40);
        gc.setTextAlign(TextAlignment.LEFT);
    }

    /** Хмарка-репліка над космонавтом. topY: де закінчується хмарка (її нижній край). */
    private static void bubble(GraphicsContext gc, double cx, double topY, double canvasW, String text, Color c) {
        double bw = text.length() * 6.2 + 16, bh = 20;
        double bx = Math.max(6, Math.min(canvasW - bw - 6, cx - bw / 2));
        double by = topY - bh;
        Color bg = Color.rgb(15, 23, 42, 0.94);
        gc.setFill(bg);
        gc.fillRoundRect(bx, by, bw, bh, 10, 10);
        gc.setStroke(c);
        gc.setLineWidth(1.4);
        gc.strokeRoundRect(bx, by, bw, bh, 10, 10);
        gc.setFill(bg);
        gc.fillPolygon(new double[]{cx - 4, cx + 4, cx}, new double[]{by + bh - 0.5, by + bh - 0.5, by + bh + 6}, 3);
        gc.setFill(c);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
        gc.fillText(text, bx + bw / 2, by + 14);
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