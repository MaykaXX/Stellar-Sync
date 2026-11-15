package nasa.spaceapps.aegis;

import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.paint.*;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import nasa.spaceapps.aegis.AegisBioApp.MetricType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * Голограма космонавта (малюнок з версії Stellar-Sync) як окремий компонент.
 * Два режими: повний (виноски, сканер, підказки при наведенні) і компактний (для центру орбітального меню).
 */
public final class AstronautView extends Pane {

    private record Hot(double x, double y, String title, String text) {}

    static final double BODY_W = 640, BODY_H = 480;

    private final Canvas canvas = new Canvas(BODY_W, BODY_H);
    private final boolean compact;
    private final Map<MetricType, Double> tel;
    private final BiPredicate<MetricType, Double> inRange;
    private final Function<MetricType, String> hintOf;
    private final DoubleSupplier healthSupplier;
    private final double spineCm;
    private final Tooltip bodyTip = new Tooltip();

    private double animationPulse = 0;
    private double heartPhase = 0;
    private int healthScore = 100;
    private Hot hoverHot;
    private double bodyScale = 1, bodyOffX = 0, bodyOffY = 0;

    public AstronautView(boolean compact, Map<MetricType, Double> telemetry,
                         BiPredicate<MetricType, Double> inRange, Function<MetricType, String> hintOf,
                         DoubleSupplier healthSupplier, double spineCm) {
        this.compact = compact;
        this.tel = telemetry;
        this.inRange = inRange;
        this.hintOf = hintOf;
        this.healthSupplier = healthSupplier;
        this.spineCm = spineCm;
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        if (!compact) {
            bodyTip.setWrapText(true);
            bodyTip.setMaxWidth(380);
            bodyTip.setStyle("-fx-font-size: 32px;");
            canvas.setOnMouseMoved(e -> onBodyHover(e.getX(), e.getY(), e.getScreenX(), e.getScreenY()));
            canvas.setOnMouseExited(e -> {
                bodyTip.hide();
                hoverHot = null;
            });
        } else {
            setMouseTransparent(true);
        }
    }

    /** Викликається щокадру, коли космонавта видно. */
    public void frame(double dt) {
        animationPulse += dt * 3;
        double hr = tel.getOrDefault(MetricType.HEART_RATE, 70.0);
        heartPhase += dt * (hr / 60.0) * 2 * Math.PI;
        healthScore = (int) Math.round(healthSupplier.getAsDouble());
        GraphicsContext gc = canvas.getGraphicsContext2D();
        if (compact) drawCompact(gc); else drawHolographicBody(gc);
    }

    /** Частота пульсу для кардіомонітора на екрані космонавта. */
    public double heartRate() {
        return tel.getOrDefault(MetricType.HEART_RATE, 70.0);
    }

    // ============ КОМПАКТНИЙ РЕЖИМ (центр меню) ============
    private void drawCompact(GraphicsContext gc) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w < 10 || h < 10) return;
        gc.clearRect(0, 0, w, h);
        gc.setLineDashes((double[]) null);
        Color aura = auraColor();
        double t = animationPulse / 3.0;
        gc.setFill(new RadialGradient(0, 0, w / 2, h / 2, Math.min(w, h) * 0.55, false, CycleMethod.NO_CYCLE,
                new Stop(0, HoloUi.alpha(aura, 0.20)), new Stop(1, HoloUi.alpha(aura, 0.0))));
        gc.fillRect(0, 0, w, h);
        // постать займає приблизно 200 x 430 логічних одиниць
        double s = Math.min(w / 230.0, h / 450.0);
        gc.save();
        gc.translate(w / 2, h / 2);
        gc.scale(s, s);
        gc.translate(-BODY_W / 2, -240);
        gc.setGlobalAlpha(0.93 + 0.07 * Math.sin(animationPulse * 23));
        drawBodyLogical(gc, false);
        gc.restore();
        gc.setGlobalAlpha(1.0);
        double scanY = (animationPulse * 14) % h;
        gc.setStroke(Color.rgb(220, 232, 242, 0.45));
        gc.setLineWidth(1);
        gc.strokeLine(0, scanY, w, scanY);
    }

    // ============ ПІДКАЗКИ ПРИ НАВЕДЕННІ ============
    private List<Hot> bodyHotspots() {
        double cx = BODY_W / 2;
        double hy = 62;
        List<Hot> l = new ArrayList<>();
        l.add(hotFor(cx, hy - 3, MetricType.ICP_OPTIC));
        l.add(hotFor(cx + 16, hy + 68, MetricType.SPO2));
        l.add(new Hot(cx, hy + 135, "Хребет у невагомості",
                "Без ваги хребет розтягується приблизно на " + spineCm + " см. "
                        + "Це нормально, але може боліти спина. Допомагають силові тренування."));
        l.add(hotFor(cx + 40, hy + 150, MetricType.RADIATION_DOSE));
        l.add(hotFor(cx, hy + 75, MetricType.HEART_RATE));
        l.add(hotFor(cx - 14, hy + 100, MetricType.HRV));
        l.add(hotFor(cx, hy + 160, MetricType.HYDRATION));
        l.add(hotFor(cx - 26, hy + 285, MetricType.BONE_DENSITY));
        return l;
    }

    private Hot hotFor(double x, double y, MetricType type) {
        double v = tel.getOrDefault(type, 0.0);
        String st = inRange.test(type, v) ? "у нормі" : "поза нормою";
        return new Hot(x, y,
                String.format(Locale.US, "%s: %.1f %s (%s)", type.title, v, type.unit, st),
                hintOf.apply(type) + "\nНорма: " + HoloUi.num(type.minNominal) + "–"
                        + HoloUi.num(type.maxNominal) + " " + type.unit);
    }

    private void onBodyHover(double mx, double my, double screenX, double screenY) {
        double lx = (mx - bodyOffX) / bodyScale;
        double ly = (my - bodyOffY) / bodyScale;
        Hot best = null;
        double bestDist = 24;
        for (Hot hot : bodyHotspots()) {
            double d = Math.hypot(hot.x() - lx, hot.y() - ly);
            if (d < bestDist) {
                bestDist = d;
                best = hot;
            }
        }
        hoverHot = best;
        if (best == null) {
            bodyTip.hide();
            canvas.setCursor(Cursor.DEFAULT);
            return;
        }
        canvas.setCursor(Cursor.HAND);
        bodyTip.setText(best.title() + "\n\n" + best.text());
        bodyTip.show(canvas, screenX + 16, screenY + 16);
    }

    // ============ МАЛЮВАННЯ ТІЛА (з Stellar-Sync) ============
    private void glowDot(GraphicsContext gc, double cx, double cy, double size, Color color) {
        gc.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.18));
        double halo = size * 2.6;
        gc.fillOval(cx - halo / 2, cy - halo / 2, halo, halo);
        gc.setFill(color);
        gc.fillOval(cx - size / 2, cy - size / 2, size, size);
    }

    private void drawTag(GraphicsContext gc, String text, double x, double y, Color color) {
        double textWidth = text.length() * 9.8;
        gc.setFill(Color.rgb(0, 0, 0, 0.8));
        gc.fillRect(x - 3, y - 17, textWidth + 6, 23);
        gc.setFill(color);
        gc.fillText(text, x, y);
    }

    /** Контур частини скафандра: прозора заливка + світіння + чітка лінія. */
    private void drawPart(GraphicsContext gc, double[] xs, double[] ys, Color fill, Color stroke) {
        gc.setFill(fill);
        gc.fillPolygon(xs, ys, xs.length);
        gc.setStroke(Color.color(stroke.getRed(), stroke.getGreen(), stroke.getBlue(), 0.10));
        gc.setLineWidth(6);
        gc.strokePolygon(xs, ys, xs.length);
        gc.setStroke(stroke);
        gc.setLineWidth(1.5);
        gc.strokePolygon(xs, ys, xs.length);
    }

    private void drawRoundPart(GraphicsContext gc, double x, double y, double w, double h, double r,
                               Color fill, Color stroke) {
        gc.setFill(fill);
        gc.fillRoundRect(x, y, w, h, r, r);
        gc.setStroke(stroke);
        gc.setLineWidth(1.5);
        gc.strokeRoundRect(x, y, w, h, r, r);
    }

    /** Колір сяйва голограми залежить від загального стану. */
    private Color auraColor() {
        if (healthScore >= 80) return Color.rgb(157, 183, 206);
        if (healthScore >= 50) return Color.rgb(230, 178, 62);
        return Color.rgb(229, 83, 95);
    }

    /** Кути рамки HUD. */
    private void drawBrackets(GraphicsContext gc, double w, double h, Color c) {
        double m = 8, L = 22;
        gc.setStroke(c);
        gc.setLineWidth(2);
        gc.strokeLine(m, m, m + L, m);
        gc.strokeLine(m, m, m, m + L);
        gc.strokeLine(w - m, m, w - m - L, m);
        gc.strokeLine(w - m, m, w - m, m + L);
        gc.strokeLine(m, h - m, m + L, h - m);
        gc.strokeLine(m, h - m, m, h - m - L);
        gc.strokeLine(w - m, h - m, w - m - L, h - m);
        gc.strokeLine(w - m, h - m, w - m, h - m - L);
    }

    /** Малює голографічну сцену: сяйво, проектор, промінь, кільця, частинки, космонавта і сканер. */

    private void drawHolographicBody(GraphicsContext gc) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w < 10 || h < 10) return;
        double t = animationPulse / 3.0;
        Color aura = auraColor();
        gc.clearRect(0, 0, w, h);
        gc.setLineDashes((double[]) null);
        gc.setGlobalAlpha(1.0);

        double s = Math.min(w / BODY_W, h / BODY_H);
        double ox = (w - BODY_W * s) / 2;
        double oy = (h - BODY_H * s) / 2;
        bodyScale = s;
        bodyOffX = ox;
        bodyOffY = oy;
        double midX = w / 2;
        double footY = oy + (62 + 392) * s;

        // 1. М'яке сяйво за спиною
        gc.setFill(new RadialGradient(0, 0, midX, h * 0.5, Math.min(w, h) * 0.65, false, CycleMethod.NO_CYCLE,
                new Stop(0, HoloUi.alpha(aura, 0.16)), new Stop(1, HoloUi.alpha(aura, 0.0))));
        gc.fillRect(0, 0, w, h);

        // 2. Сітка сканера
        gc.setStroke(Color.rgb(38, 50, 61, 0.35));
        gc.setLineWidth(1);
        for (double y = 0; y < h; y += 30) gc.strokeLine(0, y, w, y);
        for (double x = 0; x < w; x += 30) gc.strokeLine(x, 0, x, h);

        // 3. Промінь проектора від майданчика вгору
        double padRx = 120 * s;
        gc.setFill(new LinearGradient(0, 1, 0, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, HoloUi.alpha(aura, 0.20)), new Stop(1, HoloUi.alpha(aura, 0.0))));
        gc.fillPolygon(
                new double[]{midX - padRx * 0.8, midX + padRx * 0.8, midX + padRx * 0.5, midX - padRx * 0.5},
                new double[]{footY, footY, oy, oy}, 4);

        // 4. Майданчик проектора: кільця, що пульсують
        for (int k = 0; k < 3; k++) {
            double ph = (t * 0.5 + k / 3.0) % 1.0;
            double rx = padRx * (0.45 + 0.75 * ph);
            gc.setStroke(HoloUi.alpha(aura, 0.55 * (1 - ph)));
            gc.setLineWidth(1.5);
            gc.strokeOval(midX - rx, footY - rx * 0.2 + 4 * s, rx * 2, rx * 0.4);
        }
        gc.setStroke(HoloUi.alpha(aura, 0.7));
        gc.setLineWidth(2);
        gc.strokeOval(midX - padRx * 0.45, footY - padRx * 0.09 + 4 * s, padRx * 0.9, padRx * 0.18);

        // 5. Кільця навколо тіла (як гіроскоп)
        double ringCx = ox + (BODY_W / 2) * s;
        double ringCy = oy + (62 + 190) * s;
        for (int k = 0; k < 2; k++) {
            gc.save();
            gc.translate(ringCx, ringCy);
            gc.rotate(k == 0 ? 14 : -14);
            double rx = (150 + 22 * k) * s;
            double ry = rx * (0.16 + 0.05 * Math.sin(t * 0.8 + k * 2));
            gc.setStroke(HoloUi.alpha(aura, 0.30));
            gc.setLineWidth(1.2);
            gc.strokeOval(-rx, -ry, 2 * rx, 2 * ry);
            double a = t * (k == 0 ? 0.9 : -0.7);
            gc.setFill(HoloUi.alpha(aura, 0.95));
            gc.fillOval(rx * Math.cos(a) - 3, ry * Math.sin(a) - 3, 6, 6);
            gc.restore();
        }

        // 6. Космонавт (легке мерехтіння, як у справжньої голограми)
        gc.setGlobalAlpha(0.93 + 0.07 * Math.sin(animationPulse * 23));
        gc.save();
        gc.translate(ox, oy);
        gc.scale(s, s);
        drawBodyLogical(gc, true);
        gc.restore();
        gc.setGlobalAlpha(1.0);

        // 7. Частинки світла, що летять угору
        for (int i = 0; i < 28; i++) {
            double px = midX + ((i * 53) % 100 - 50) / 50.0 * (BODY_W * 0.42) * s;
            double life = (t * (0.10 + (i % 5) * 0.03) + i * 0.137) % 1.0;
            double py = footY - life * (footY - 4);
            double sz = 1.5 + (i % 3);
            gc.setFill(HoloUi.alpha(aura, Math.sin(life * Math.PI) * 0.7));
            gc.fillOval(px - sz / 2, py - sz / 2, sz, sz);
        }

        // 8. Промінь сканера з шлейфом
        double scanY = (animationPulse * 25) % h;
        gc.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, HoloUi.alpha(aura, 0.0)), new Stop(1, HoloUi.alpha(aura, 0.22))));
        gc.fillRect(0, scanY - 46, w, 46);
        gc.setStroke(Color.rgb(220, 232, 242, 0.65));
        gc.setLineWidth(1);
        gc.strokeLine(0, scanY, w, scanY);

        // 9. Рамка HUD і написи
        drawBrackets(gc, w, h, HoloUi.alpha(aura, 0.9));
        gc.setFont(Font.font("Consolas", FontWeight.BOLD, 27));
        gc.setFill(HoloUi.alpha(aura, 0.95));
        gc.fillText("СКАНУВАННЯ ТІЛА", 22, 28);
        boolean blink = Math.sin(animationPulse * 3) > 0;
        gc.setFill(blink ? Color.rgb(229, 83, 95, 0.95) : Color.rgb(229, 83, 95, 0.3));
        javafx.scene.text.Text liveProbe = new javafx.scene.text.Text("● НАЖИВО");
        liveProbe.setFont(gc.getFont());
        gc.fillText("● НАЖИВО", w - liveProbe.getLayoutBounds().getWidth() - 24, 28);
        gc.setFill(Color.rgb(169, 183, 196, 0.8));
        gc.fillText("Наведіть курсор на світну точку", 22, h - 18);
    }


    /** Малюнок космонавта в логічних координатах BODY_W x BODY_H. */
    private void drawBodyLogical(GraphicsContext gc, boolean callouts) {
        double w = BODY_W;
        double h = BODY_H;
        gc.setFont(Font.font("Consolas", 14.5));

        double cx = w / 2;
        double hy = 62; // центр голови

        Color fill = Color.rgb(157, 183, 206, 0.10);
        Color line = Color.rgb(157, 183, 206, 0.75);
        Color inner = Color.rgb(220, 232, 242, 0.25);

        // Ранцевий блок життєзабезпечення (за спиною), пунктиром
        gc.setFill(Color.rgb(157, 183, 206, 0.05));
        gc.fillRoundRect(cx - 60, hy + 44, 120, 108, 16, 16);
        gc.setStroke(Color.rgb(157, 183, 206, 0.35));
        gc.setLineWidth(1.2);
        gc.setLineDashes(5, 5);
        gc.strokeRoundRect(cx - 60, hy + 44, 120, 108, 16, 16);
        gc.setLineDashes((double[]) null);

        // Ноги
        drawPart(gc,
                new double[]{cx - 38, cx - 2, cx - 8, cx - 12, cx - 44, cx - 46},
                new double[]{hy + 185, hy + 185, hy + 300, hy + 372, hy + 372, hy + 300}, fill, line);
        drawPart(gc,
                new double[]{cx + 38, cx + 2, cx + 8, cx + 12, cx + 44, cx + 46},
                new double[]{hy + 185, hy + 185, hy + 300, hy + 372, hy + 372, hy + 300}, fill, line);
        // Черевики
        drawRoundPart(gc, cx - 52, hy + 370, 44, 22, 10, fill, line);
        drawRoundPart(gc, cx + 8, hy + 370, 44, 22, 10, fill, line);

        // Руки (рукави) і рукавички
        drawPart(gc,
                new double[]{cx - 50, cx - 68, cx - 96, cx - 76},
                new double[]{hy + 44, hy + 48, hy + 160, hy + 166}, fill, line);
        drawPart(gc,
                new double[]{cx + 50, cx + 68, cx + 96, cx + 76},
                new double[]{hy + 44, hy + 48, hy + 160, hy + 166}, fill, line);
        gc.setFill(fill);
        gc.setStroke(line);
        gc.setLineWidth(1.5);
        gc.fillOval(cx - 97, hy + 160, 24, 26);
        gc.strokeOval(cx - 97, hy + 160, 24, 26);
        gc.fillOval(cx + 73, hy + 160, 24, 26);
        gc.strokeOval(cx + 73, hy + 160, 24, 26);

        // Тулуб скафандра
        drawPart(gc,
                new double[]{cx - 50, cx + 50, cx + 44, cx + 38, cx - 38, cx - 44},
                new double[]{hy + 42, hy + 42, hy + 120, hy + 185, hy + 185, hy + 120}, fill, line);
        // Пояс і панель керування на грудях
        drawRoundPart(gc, cx - 39, hy + 176, 78, 9, 4, fill, line);
        drawRoundPart(gc, cx - 17, hy + 92, 34, 18, 4, Color.rgb(157, 183, 206, 0.06), line);

        // Внутрішній «рентген»: ребра, хребет, кістки ніг
        gc.setStroke(inner);
        gc.setLineWidth(1.2);
        for (int i = 0; i < 4; i++) {
            gc.strokeArc(cx - 26, hy + 50 + i * 9, 52, 26, 180, 180, ArcType.OPEN);
        }
        for (int i = 0; i < 13; i++) {
            double vy = hy + 48 + i * 10;
            gc.strokeLine(cx - 3, vy, cx + 3, vy);
        }
        gc.strokeLine(cx - 26, hy + 200, cx - 26, hy + 362);
        gc.strokeLine(cx + 26, hy + 200, cx + 26, hy + 362);

        // Шийне кільце
        gc.setFill(fill);
        gc.setStroke(line);
        gc.setLineWidth(1.5);
        gc.fillOval(cx - 22, hy + 30, 44, 14);
        gc.strokeOval(cx - 22, hy + 30, 44, 14);

        // Шолом
        gc.setFill(Color.rgb(157, 183, 206, 0.07));
        gc.fillOval(cx - 36, hy - 36, 72, 72);
        gc.setStroke(Color.rgb(157, 183, 206, 0.10));
        gc.setLineWidth(6);
        gc.strokeOval(cx - 36, hy - 36, 72, 72);
        gc.setStroke(line);
        gc.setLineWidth(1.5);
        gc.strokeOval(cx - 36, hy - 36, 72, 72);

        // Скло шолома (візор) з градієнтом і відблиском
        gc.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(198, 216, 232, 0.35)),
                new Stop(1, Color.rgb(47, 93, 138, 0.10))));
        gc.fillOval(cx - 25, hy - 20, 50, 40);
        gc.setStroke(Color.rgb(198, 216, 232, 0.8));
        gc.setLineWidth(1.2);
        gc.strokeOval(cx - 25, hy - 20, 50, 40);
        gc.setStroke(Color.rgb(255, 255, 255, 0.45));
        gc.setLineWidth(2);
        gc.strokeArc(cx - 20, hy - 16, 40, 32, 110, 50, ArcType.OPEN);

        double pulse = 6 + 2 * Math.sin(animationPulse);
        double boxL = 8;            // ліві виноски
        double boxR = BODY_W - 228; // праві виноски

        // Очі (видно крізь візор)
        double icp = tel.get(MetricType.ICP_OPTIC);
        Color sansColor = icp > 18.0 ? Color.rgb(229, 83, 95, 0.95) : Color.rgb(68, 194, 142, 0.9);
        glowDot(gc, cx - 8, hy - 3, pulse, sansColor);
        glowDot(gc, cx + 8, hy - 3, pulse, sansColor);
        if (callouts) drawCallout(gc, cx + 8, hy - 3, boxR, 18, true, "Тиск на нерв ока",
                String.format(Locale.US, "%.1f мм рт.ст.", icp), sansColor);

        // Кисень (легені)
        double spo2 = tel.get(MetricType.SPO2);
        Color spoColor = spo2 < 96 ? Color.rgb(232, 132, 60, 0.95) : Color.rgb(198, 216, 232, 0.9);
        glowDot(gc, cx + 16, hy + 68, pulse, spoColor);
        if (callouts) drawCallout(gc, cx + 16, hy + 68, boxR, 92, true, "Кисень (SpO2)",
                String.format(Locale.US, "%.1f %%", spo2), spoColor);

        // Хребет
        Color spineColor = Color.rgb(154, 168, 255, 0.9);
        glowDot(gc, cx, hy + 135, pulse, spineColor);
        if (callouts) drawCallout(gc, cx, hy + 135, boxR, 166, true, "Хребет у невагомості",
                String.format(Locale.US, "+%.1f см", spineCm), spineColor);

        // Радіація
        double rad = tel.get(MetricType.RADIATION_DOSE);
        Color radColor = rad > 100 ? Color.rgb(229, 83, 95, 0.95) : Color.rgb(68, 194, 142, 0.9);
        glowDot(gc, cx + 40, hy + 150, pulse, radColor);
        if (callouts) drawCallout(gc, cx + 40, hy + 150, boxR, 240, true, "Доза радіації",
                String.format(Locale.US, "%.1f мЗв", rad), radColor);

        // Серце: б'ється з частотою пульсу
        double hr = tel.get(MetricType.HEART_RATE);
        double hrv = tel.get(MetricType.HRV);
        boolean heartWarn = hr > 100 || hr < 55 || hrv < 40;
        Color heartColor = heartWarn ? Color.rgb(232, 132, 60, 0.95) : Color.rgb(157, 183, 206, 0.9);
        double beat = 0.5 + 0.5 * Math.sin(heartPhase);
        glowDot(gc, cx, hy + 75, 5 + 6 * beat * beat, heartColor);
        if (callouts) drawCallout(gc, cx, hy + 75, boxL, 96, false, "Пульс",
                String.format(Locale.US, "%.0f уд/хв", hr), heartColor);

        // Стрес-баланс серця
        Color hrvColor = hrv < 40 ? Color.rgb(232, 132, 60, 0.95) : Color.rgb(157, 183, 206, 0.9);
        glowDot(gc, cx - 14, hy + 100, pulse, hrvColor);
        if (callouts) drawCallout(gc, cx - 14, hy + 100, boxL, 162, false, "Стрес-баланс (HRV)",
                String.format(Locale.US, "%.1f мс", hrv), hrvColor);

        // Вода
        double hyd = tel.get(MetricType.HYDRATION);
        Color hydColor = hyd < 90 ? Color.rgb(232, 132, 60, 0.95) : Color.rgb(198, 216, 232, 0.9);
        glowDot(gc, cx, hy + 160, pulse, hydColor);
        if (callouts) drawCallout(gc, cx, hy + 160, boxL, 228, false, "Запас води",
                String.format(Locale.US, "%.0f %%", hyd), hydColor);

        // Кістки ніг
        double bone = tel.get(MetricType.BONE_DENSITY);
        Color boneColor = bone < 97 ? Color.rgb(229, 83, 95, 0.95) : Color.rgb(230, 178, 62, 0.9);
        glowDot(gc, cx - 26, hy + 285, pulse, boneColor);
        glowDot(gc, cx + 26, hy + 285, pulse, boneColor);
        if (callouts) drawCallout(gc, cx - 26, hy + 285, boxL, 330, false, "Міцність кісток",
                String.format(Locale.US, "%.1f %%", bone), boneColor);

        // Підсвітка точки, на яку навели курсор
        if (hoverHot != null) {
            double r = 16 + 3 * Math.sin(animationPulse * 2);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1.5);
            gc.setLineDashes(4, 4);
            gc.strokeOval(hoverHot.x() - r, hoverHot.y() - r, 2 * r, 2 * r);
            gc.setLineDashes((double[]) null);
        }
    }

    /** Виноска: лінія від точки на тілі до підписаної рамки збоку. */
    private void drawCallout(GraphicsContext gc, double dotX, double dotY, double boxX, double boxY,
                             boolean boxOnRight, String title, String value, Color color) {
        double bw = 220, bh = 56;
        double edgeX = boxOnRight ? boxX : boxX + bw;
        double edgeY = boxY + bh / 2;

        gc.setLineDashes((double[]) null);
        gc.setStroke(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.6));
        gc.setLineWidth(1.2);
        gc.strokeLine(dotX, dotY, edgeX, edgeY);

        gc.setFill(Color.rgb(0, 0, 0, 0.88));
        gc.fillRoundRect(boxX, boxY, bw, bh, 5, 5);
        gc.setStroke(color);
        gc.setLineWidth(1.8);
        gc.strokeRoundRect(boxX, boxY, bw, bh, 5, 5);

        gc.setFill(Color.rgb(169, 183, 196));
        gc.setFont(Font.font("Consolas", 14.5));
        gc.fillText(title, boxX + 9, boxY + 20);
        gc.setFill(color);
        gc.setFont(Font.font("Consolas", FontWeight.BOLD, 21));
        gc.fillText(value, boxX + 9, boxY + 45);
        gc.setFont(Font.font("Consolas", 14.5));
    }


}
