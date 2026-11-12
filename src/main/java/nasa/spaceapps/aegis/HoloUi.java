package nasa.spaceapps.aegis;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.Locale;

/**
 * Готові «цеглинки» інтерфейсу та голографічні малюнки.
 * Нічого не знає про логіку програми: лише малює те, що йому передали.
 */
public final class HoloUi {

    private HoloUi() {
    }

    // ============ КОЛЬОРИ ============
    public static final Color CYAN = Color.web("#38BDF8");
    public static final Color GREEN = Color.web("#10B981");
    public static final Color AMBER = Color.web("#F59E0B");
    public static final Color ORANGE = Color.web("#F97316");
    public static final Color RED = Color.web("#EF4444");
    public static final Color PURPLE = Color.web("#A855F7");
    public static final Color TEXT_DIM = Color.web("#94A3B8");

    public static final String BLOCK_STYLE =
            "-fx-background-color: linear-gradient(to bottom, #0F1A2E, #0B1322);"
                    + "-fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: #1E3A5F;"
                    + "-fx-effect: dropshadow(gaussian, rgba(56,189,248,0.14), 18, 0.15, 0, 0);";

    public static final String CARD_STYLE =
            "-fx-background-color: #121C30; -fx-background-radius: 10; "
                    + "-fx-border-color: #22334F; -fx-border-radius: 10;";

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    public static String rgba(Color c, double a) {
        return String.format(Locale.US, "rgba(%d,%d,%d,%.2f)",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255), a);
    }

    public static Color alpha(Color c, double a) {
        return Color.color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(1, a)));
    }

    // ============ ЦЕГЛИНКИ ІНТЕРФЕЙСУ ============

    /** Логічний блок: іконка, заголовок, пояснення, кнопка «?» і вміст. */
    public static VBox block(String icon, String title, String subtitle, String help, Node... content) {
        Label ic = new Label(icon);
        ic.setStyle("-fx-font-size: 20px;");

        Label t = new Label(title);
        t.setStyle("-fx-text-fill: #E0F2FE; -fx-font-size: 14px; -fx-font-weight: bold;");

        VBox titles = new VBox(1, t);
        if (subtitle != null && !subtitle.isEmpty()) {
            Label s = new Label(subtitle);
            s.setWrapText(true);
            s.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
            titles.getChildren().add(s);
        }
        HBox.setHgrow(titles, Priority.ALWAYS);

        HBox head = new HBox(10, ic, titles);
        head.setAlignment(Pos.CENTER_LEFT);
        if (help != null && !help.isEmpty()) head.getChildren().add(helpBadge(help));

        Region line = new Region();
        line.setMinHeight(1);
        line.setPrefHeight(1);
        line.setMaxHeight(1);
        line.setStyle("-fx-background-color: linear-gradient(to right, #38BDF8, transparent);");

        VBox box = new VBox(10, head, line);
        box.getChildren().addAll(content);
        box.setPadding(new Insets(14));
        box.setStyle(BLOCK_STYLE);
        return box;
    }

    /** Кругла кнопка «?»: наведіть курсор, щоб прочитати пояснення простими словами. */
    public static Label helpBadge(String text) {
        Label q = new Label("?");
        q.setMinSize(20, 20);
        q.setAlignment(Pos.CENTER);
        q.setStyle("-fx-background-color: #0C4A6E; -fx-text-fill: #E0F2FE; -fx-font-weight: bold; "
                + "-fx-font-size: 12px; -fx-background-radius: 10; -fx-cursor: help;");
        Tooltip tip = new Tooltip(text);
        tip.setWrapText(true);
        tip.setMaxWidth(320);
        tip.setShowDelay(Duration.millis(60));
        tip.setShowDuration(Duration.seconds(40));
        tip.setStyle("-fx-font-size: 12px;");
        Tooltip.install(q, tip);
        return q;
    }

    /** Маленька кольорова позначка стану: «Норма», «Увага»... */
    public static void styleChip(Label chip, String text, Color c) {
        chip.setText(text);
        chip.setStyle("-fx-background-color: " + rgba(c, 0.16)
                + "; -fx-text-fill: " + hex(c) + "; -fx-font-size: 10px; -fx-font-weight: bold; "
                + "-fx-padding: 2 8 2 8; -fx-background-radius: 10; -fx-border-radius: 10; "
                + "-fx-border-color: " + hex(c) + ";");
    }

    public static Label chip(String text, Color c) {
        Label l = new Label();
        styleChip(l, text, c);
        return l;
    }

    /** Полотно, яке розтягується разом із контейнером. */
    public static Pane fill(Canvas canvas, double height) {
        canvas.setManaged(false);
        Pane holder = new Pane(canvas);
        holder.setMinSize(0, height);
        holder.setPrefHeight(height);
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        return holder;
    }

    // ============ КРУГОВИЙ ПОКАЗНИК ============
    /** Кругова шкала: кольцо показує, де значення між мінімумом і максимумом, зелена дуга: норма. */
    public static final class RingGauge extends Canvas {
        private double frac = 0.5, nomLo = 0.3, nomHi = 0.7;
        private Color color = CYAN;
        private String center = "", sub = "";

        public RingGauge(double size) {
            super(size, size);
            redraw();
        }

        public void set(double value, double hardMin, double hardMax, double nomMin, double nomMax,
                        Color c, String centerText, String subText) {
            double span = hardMax - hardMin;
            if (span <= 0) span = 1;
            this.frac = clamp01((value - hardMin) / span);
            this.nomLo = clamp01((nomMin - hardMin) / span);
            this.nomHi = clamp01((nomMax - hardMin) / span);
            this.color = c;
            this.center = centerText;
            this.sub = subText;
            redraw();
        }

        private void redraw() {
            GraphicsContext gc = getGraphicsContext2D();
            double w = getWidth(), h = getHeight();
            gc.clearRect(0, 0, w, h);
            double cx = w / 2, cy = h / 2;
            double r = Math.min(w, h) / 2 - 9;
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setLineDashes((double[]) null);

            // фонова дуга
            gc.setStroke(Color.rgb(30, 41, 59, 0.9));
            gc.setLineWidth(8);
            gc.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 225, -270, ArcType.OPEN);

            // межі норми (зелена тонка дуга зовні)
            double rn = r + 6;
            gc.setStroke(alpha(GREEN, 0.75));
            gc.setLineWidth(2.5);
            gc.strokeArc(cx - rn, cy - rn, 2 * rn, 2 * rn, 225 - 270 * nomLo, -270 * (nomHi - nomLo), ArcType.OPEN);

            // значення: світіння і чітка лінія
            double ext = -270 * Math.max(0.02, frac);
            gc.setStroke(alpha(color, 0.18));
            gc.setLineWidth(13);
            gc.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 225, ext, ArcType.OPEN);
            gc.setStroke(color);
            gc.setLineWidth(6);
            gc.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 225, ext, ArcType.OPEN);

            // маркер на кінці дуги
            double ang = Math.toRadians(225 - 270 * Math.max(0.02, frac));
            double mx = cx + r * Math.cos(ang), my = cy - r * Math.sin(ang);
            gc.setFill(Color.WHITE);
            gc.fillOval(mx - 3, my - 3, 6, 6);

            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFill(Color.web("#F8FAFC"));
            gc.setFont(Font.font("Consolas", FontWeight.BOLD, Math.max(11, r * 0.42)));
            gc.fillText(center, cx, cy + 3);
            gc.setFill(TEXT_DIM);
            gc.setFont(Font.font("Consolas", Math.max(8, r * 0.19)));
            gc.fillText(sub, cx, cy + r * 0.45);
            gc.setTextAlign(TextAlignment.LEFT);
        }
    }

    // ============ ДНК (радіація) ============
    /** Обертова спіраль ДНК. Частина «сходинок» червоніє тим більше, чим вища доза радіації. */
    public static final class DnaHelix extends Canvas {
        public DnaHelix() {
            super(120, 160);
        }

        public void render(double t, double risk, Color base) {
            GraphicsContext gc = getGraphicsContext2D();
            double w = getWidth(), h = getHeight();
            if (w < 10 || h < 10) return;
            gc.clearRect(0, 0, w, h);
            gc.setLineDashes((double[]) null);
            gc.setLineCap(StrokeLineCap.ROUND);

            int n = 22;
            double amp = w * 0.30;
            double[] ax = new double[n], bx = new double[n], ys = new double[n];
            for (int i = 0; i < n; i++) {
                double y = 8 + i * (h - 16) / (n - 1);
                double ph = t * 1.5 + i * 0.5;
                ax[i] = w / 2 + amp * Math.sin(ph);
                bx[i] = w / 2 - amp * Math.sin(ph);
                ys[i] = y;
                double depth = Math.cos(ph);
                boolean damaged = ((i * 37) % 100) / 100.0 < risk;
                Color rung = damaged ? RED : base;
                gc.setStroke(alpha(rung, 0.25 + 0.25 * (depth + 1) / 2));
                gc.setLineWidth(1.6);
                gc.strokeLine(ax[i], y, bx[i], y);
                if (damaged) {
                    gc.setFill(alpha(RED, 0.9));
                    gc.fillOval(w / 2 - 2.5, y - 2.5, 5, 5);
                }
            }
            gc.setStroke(alpha(base, 0.85));
            gc.setLineWidth(2);
            gc.strokePolyline(ax, ys, n);
            gc.setStroke(alpha(PURPLE, 0.85));
            gc.strokePolyline(bx, ys, n);
            for (int i = 0; i < n; i += 2) {
                double d = 3 + 1.5 * Math.cos(t * 1.5 + i * 0.5);
                gc.setFill(alpha(base, 0.95));
                gc.fillOval(ax[i] - d / 2, ys[i] - d / 2, d, d);
                gc.setFill(alpha(PURPLE, 0.95));
                gc.fillOval(bx[i] - d / 2, ys[i] - d / 2, d, d);
            }
        }
    }

    // ============ КАРТА МІСІЇ ============
    /**
     * Схема «Земля - Місяць - Марс» з кораблем і сигналом зв'язку.
     * mode: 0 = навколоземна орбіта, 1 = Місяць, 2 = політ до Марса, 3 = сонячне з'єднання (блекаут).
     */
    public static final class OrbitMap extends Canvas {

        public void render(double t, int mode, boolean blackout) {
            GraphicsContext gc = getGraphicsContext2D();
            double w = getWidth(), h = getHeight();
            if (w < 50 || h < 50) return;
            gc.clearRect(0, 0, w, h);
            gc.setLineDashes((double[]) null);
            gc.setLineCap(StrokeLineCap.ROUND);

            // зірки
            for (int i = 0; i < 70; i++) {
                double sx = ((i * 7919L) % 1000) / 1000.0 * w;
                double sy = ((i * 104729L) % 1000) / 1000.0 * h;
                double tw = 0.25 + 0.25 * Math.sin(t * 1.3 + i);
                gc.setFill(Color.color(0.7, 0.85, 1.0, tw));
                gc.fillOval(sx, sy, 1.5, 1.5);
            }

            double ey = h * 0.62;
            double ex = w * 0.10, mx = w * 0.90, sxp = w * 0.50;
            double cpx = w * 0.50, cpy = h * 0.02;

            // траєкторія перельоту
            gc.setStroke(alpha(CYAN, 0.35));
            gc.setLineWidth(1.2);
            gc.setLineDashes(6, 6);
            gc.beginPath();
            gc.moveTo(ex, ey);
            gc.quadraticCurveTo(cpx, cpy, mx, ey);
            gc.stroke();
            gc.setLineDashes((double[]) null);

            // Сонце
            double sunR = Math.min(w, h) * 0.075;
            double pulse = 1 + 0.06 * Math.sin(t * 2);
            gc.setFill(alpha(AMBER, 0.12));
            gc.fillOval(sxp - sunR * 2.6, ey - sunR * 2.6, sunR * 5.2, sunR * 5.2);
            gc.setFill(alpha(AMBER, 0.30));
            gc.fillOval(sxp - sunR * 1.6 * pulse, ey - sunR * 1.6 * pulse, sunR * 3.2 * pulse, sunR * 3.2 * pulse);
            gc.setFill(Color.web("#FBBF24"));
            gc.fillOval(sxp - sunR, ey - sunR, sunR * 2, sunR * 2);

            // Земля, орбіта Місяця, Марс
            double earthR = 13;
            planet(gc, ex, ey, earthR, Color.web("#38BDF8"));
            double moonOrbit = 38;
            gc.setStroke(alpha(Color.WHITE, 0.18));
            gc.setLineWidth(1);
            gc.strokeOval(ex - moonOrbit, ey - moonOrbit, moonOrbit * 2, moonOrbit * 2);
            double ma = t * 0.6;
            double moonX = ex + moonOrbit * Math.cos(ma), moonY = ey + moonOrbit * Math.sin(ma);
            gc.setFill(Color.web("#CBD5E1"));
            gc.fillOval(moonX - 4, moonY - 4, 8, 8);
            planet(gc, mx, ey, 10, Color.web("#F97316"));

            // де корабель
            double shipX, shipY;
            switch (mode) {
                case 0 -> {
                    double a = t * 1.8;
                    shipX = ex + 24 * Math.cos(a);
                    shipY = ey + 24 * Math.sin(a);
                }
                case 1 -> {
                    shipX = moonX + 9;
                    shipY = moonY - 9;
                }
                case 2 -> {
                    double u = 0.38 + 0.03 * Math.sin(t * 0.4);
                    shipX = quad(ex, cpx, mx, u);
                    shipY = quad(ey, cpy, ey, u);
                }
                default -> {
                    shipX = mx - 26;
                    shipY = ey - 26;
                }
            }

            // сигнал до Землі
            boolean blocked = blackout || mode == 3;
            double sigSpeed = switch (mode) {
                case 0 -> 1.6;
                case 1 -> 0.9;
                case 2 -> 0.18;
                default -> 0.18;
            };
            Color sigColor = blocked ? RED : GREEN;
            gc.setStroke(alpha(sigColor, 0.35));
            gc.setLineWidth(1);
            gc.setLineDashes(3, 5);
            double endX = blocked ? sxp + sunR : ex + earthR;
            double endY = ey;
            gc.strokeLine(shipX, shipY, endX, endY);
            gc.setLineDashes((double[]) null);
            for (int k = 0; k < 3; k++) {
                double p = (t * sigSpeed + k / 3.0) % 1.0;
                double px = shipX + (endX - shipX) * p;
                double py = shipY + (endY - shipY) * p;
                gc.setFill(alpha(sigColor, 0.25));
                gc.fillOval(px - 6, py - 6, 12, 12);
                gc.setFill(sigColor);
                gc.fillOval(px - 2.5, py - 2.5, 5, 5);
            }
            if (blocked) {
                gc.setStroke(RED);
                gc.setLineWidth(2.5);
                gc.strokeLine(sxp - 10, ey - sunR - 16, sxp + 10, ey - sunR - 36);
                gc.strokeLine(sxp + 10, ey - sunR - 16, sxp - 10, ey - sunR - 36);
            }

            // корабель
            gc.setFill(alpha(CYAN, 0.25));
            gc.fillOval(shipX - 9, shipY - 9, 18, 18);
            gc.setFill(Color.WHITE);
            gc.fillPolygon(new double[]{shipX, shipX - 5, shipX + 5},
                    new double[]{shipY - 6, shipY + 5, shipY + 5}, 3);

            // підписи
            gc.setFont(Font.font("Consolas", 11));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFill(TEXT_DIM);
            gc.fillText("ЗЕМЛЯ", ex, ey + earthR + 26);
            gc.fillText("СОНЦЕ", sxp, ey + sunR * 2.6 + 12);
            gc.fillText("МАРС", mx, ey + 26);
            gc.setFill(Color.WHITE);
            gc.fillText("ВИ ТУТ", shipX, shipY - 14);
            gc.setTextAlign(TextAlignment.LEFT);
        }

        private static double quad(double p0, double c, double p2, double u) {
            double v = 1 - u;
            return v * v * p0 + 2 * v * u * c + u * u * p2;
        }

        private static void planet(GraphicsContext gc, double x, double y, double r, Color c) {
            gc.setFill(alpha(c, 0.15));
            gc.fillOval(x - r * 1.9, y - r * 1.9, r * 3.8, r * 3.8);
            gc.setFill(alpha(c, 0.9));
            gc.fillOval(x - r, y - r, r * 2, r * 2);
            gc.setFill(Color.color(0, 0, 0, 0.28));
            gc.fillArc(x - r, y - r, r * 2, r * 2, -60, 180, ArcType.CHORD);
        }
    }

    // ============ КОРИСНЕ ============
    public static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Число без зайвих нулів: 60 -> "60", 36.2 -> "36.2". */
    public static String num(double v) {
        return v == Math.rint(v) ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v);
    }
}