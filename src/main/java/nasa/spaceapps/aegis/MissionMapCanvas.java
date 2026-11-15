package nasa.spaceapps.aegis;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.Random;

/**
 * Схема «Де ми та зв'язок із Землею»: Земля, Сонце, Марс, траєкторія польоту,
 * позначка «ВИ ТУТ» і сигнал, що біжить до Землі. Малюється кодом, тому змінюється
 * разом з вибраним місцем польоту.
 */
public final class MissionMapCanvas extends Canvas {

    private AegisBioApp.MissionLocation loc = AegisBioApp.MissionLocation.MARS_TRANSIT;
    private double time = 0;
    private final double[][] stars = new double[80][3];

    private double ex, ey, sx, sy, mx, my, cx, cy;   // Земля, Сонце, Марс, контрольна точка дуги

    public MissionMapCanvas() {
        Random rnd = new Random(11);
        for (double[] s : stars) {
            s[0] = rnd.nextDouble();
            s[1] = rnd.nextDouble();
            s[2] = 0.6 + rnd.nextDouble() * 1.2;
        }
        widthProperty().addListener(o -> draw());
        heightProperty().addListener(o -> draw());
    }

    public void setLocation(AegisBioApp.MissionLocation l) {
        this.loc = l;
        draw();
    }

    public void frame(double dt) {
        time += dt;
        draw();
    }

    // ---------- допоміжне ----------
    private double bx(double t) {
        double k = 1 - t;
        return k * k * ex + 2 * k * t * cx + t * t * mx;
    }

    private double by(double t) {
        double k = 1 - t;
        return k * k * ey + 2 * k * t * cy + t * t * my;
    }

    private static Font font(double size, boolean bold) {
        return Font.font("Segoe UI", bold ? FontWeight.BOLD : FontWeight.NORMAL, size * 1.6);
    }

    private void label(GraphicsContext g, String s, double x, double y, Color c, double size, boolean bold) {
        g.setFont(font(size, bold));
        g.setFill(c);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(s, x, y);
    }

    private void halfPlanet(GraphicsContext g, double x, double y, double r, Color light, Color dark) {
        g.setFill(dark);
        g.fillOval(x - r, y - r, r * 2, r * 2);
        g.setFill(light);
        g.fillArc(x - r, y - r, r * 2, r * 2, 90, 180, ArcType.ROUND);
    }

    // ---------- малювання ----------
    private void draw() {
        double w = getWidth(), h = getHeight();
        if (w < 80 || h < 80) return;
        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#070C18")), new Stop(1, Color.web("#0A1222"))));
        g.fillRoundRect(0, 0, w, h, 16, 16);
        g.setStroke(Color.web("#1E3A5F"));
        g.setLineWidth(1);
        g.setLineDashes((double[]) null);
        g.strokeRoundRect(0.5, 0.5, w - 1, h - 1, 16, 16);

        for (int i = 0; i < stars.length; i++) {
            double[] s = stars[i];
            double a = 0.25 + 0.45 * (0.5 + 0.5 * Math.sin(time * 0.8 + i));
            g.setFill(Color.color(0.85, 0.9, 1.0, a));
            g.fillOval(s[0] * w, s[1] * h, s[2], s[2]);
        }

        double u = Math.min(h, w * 0.34);
        double earthR = Math.max(12, u * 0.085);
        double sunR = Math.max(11, u * 0.075);
        double marsR = Math.max(10, u * 0.065);
        ex = w * 0.09;
        sx = w * 0.50;
        mx = w * 0.91;
        ey = sy = my = h * 0.62;
        cx = w * 0.50;
        cy = -h * 0.02;

        boolean blackout = loc == AegisBioApp.MissionLocation.SOLAR_CONJUNCTION;
        Color cyan = HoloUi.CYAN;

        // траєкторія Земля - Марс
        g.setStroke(HoloUi.alpha(cyan, blackout ? 0.15 : 0.35));
        g.setLineWidth(1.3);
        g.setLineDashes(7, 6);
        g.beginPath();
        g.moveTo(ex, ey);
        g.quadraticCurveTo(cx, cy, mx, my);
        g.stroke();
        g.setLineDashes((double[]) null);

        // Сонце
        g.setFill(Color.color(1.0, 0.75, 0.15, 0.10));
        g.fillOval(sx - sunR * 2.8, sy - sunR * 2.8, sunR * 5.6, sunR * 5.6);
        g.setFill(Color.color(1.0, 0.75, 0.15, 0.28));
        g.fillOval(sx - sunR * 1.7, sy - sunR * 1.7, sunR * 3.4, sunR * 3.4);
        g.setFill(Color.web("#FBBF24"));
        g.fillOval(sx - sunR, sy - sunR, sunR * 2, sunR * 2);
        label(g, "СОНЦЕ", sx, sy + sunR * 3.4, Color.web("#94A3B8"), 11, false);

        // Земля
        g.setFill(Color.color(0.22, 0.74, 0.97, 0.07));
        g.fillOval(ex - earthR * 1.75, ey - earthR * 1.75, earthR * 3.5, earthR * 3.5);
        g.setStroke(Color.color(0.22, 0.74, 0.97, 0.25));
        g.setLineWidth(1);
        g.strokeOval(ex - earthR * 1.75, ey - earthR * 1.75, earthR * 3.5, earthR * 3.5);
        halfPlanet(g, ex, ey, earthR, Color.web("#38BDF8"), Color.web("#0F5F96"));
        double moonX = ex + earthR * 2.6 * Math.cos(0.6), moonY = ey + earthR * 2.6 * Math.sin(0.6);
        g.setFill(Color.web("#E2E8F0"));
        g.fillOval(moonX - 3.5, moonY - 3.5, 7, 7);
        label(g, "ЗЕМЛЯ", ex, ey + earthR * 3.6, Color.web("#94A3B8"), 11, false);

        // Марс
        g.setFill(Color.color(0.95, 0.4, 0.1, 0.10));
        g.fillOval(mx - marsR * 1.6, my - marsR * 1.6, marsR * 3.2, marsR * 3.2);
        halfPlanet(g, mx, my, marsR, Color.web("#F97316"), Color.web("#9A3412"));
        label(g, "МАРС", mx, my + marsR * 3.0, Color.web("#94A3B8"), 11, false);

        // позиція корабля
        double shipX, shipY;
        switch (loc) {
            case LEO -> {
                double r = earthR * 1.75;
                g.setStroke(Color.color(1, 1, 1, 0.18));
                g.setLineWidth(1);
                g.strokeOval(ex - r, ey - r, r * 2, r * 2);
                double a = -1.1 + time * 0.8;
                shipX = ex + r * Math.cos(a);
                shipY = ey + r * Math.sin(a);
            }
            case MOON -> {
                shipX = moonX + earthR * 0.9;
                shipY = moonY - earthR * 1.0;
            }
            case MARS_TRANSIT -> {
                shipX = bx(0.38);
                shipY = by(0.38);
            }
            default -> {
                shipX = sx + w * 0.22;
                shipY = sy;
            }
        }

        // сигнал до Землі
        if (blackout) {
            double stopX = sx - sunR * 1.15;
            g.setStroke(Color.color(0.94, 0.27, 0.27, 0.55));
            g.setLineWidth(1.4);
            g.setLineDashes(5, 5);
            g.strokeLine(ex + earthR * 1.1, ey, stopX, sy);
            g.strokeLine(sx + sunR * 1.15, sy, shipX - 12, shipY);
            g.setLineDashes((double[]) null);
            for (int k = 0; k < 3; k++) {
                double f = (time * 0.22 + k / 3.0) % 1.0;
                double px = ex + earthR * 1.1 + (stopX - ex - earthR * 1.1) * f;
                g.setFill(Color.color(0.94, 0.27, 0.27, 0.9 * (1 - f)));
                g.fillOval(px - 3.5, sy - 3.5, 7, 7);
            }
            g.setStroke(Color.web("#EF4444"));
            g.setLineWidth(2.5);
            double xx = stopX - 6;
            g.strokeLine(xx - 6, sy - 6, xx + 6, sy + 6);
            g.strokeLine(xx - 6, sy + 6, xx + 6, sy - 6);
            label(g, "СИГНАЛ ЗАБЛОКОВАНО", sx, sy - sunR * 3.1, Color.web("#FCA5A5"), 11, true);
        } else {
            boolean arc = loc == AegisBioApp.MissionLocation.MARS_TRANSIT;
            double ts = 0.38;
            g.setStroke(Color.color(0.24, 0.77, 0.54, 0.45));
            g.setLineWidth(1.2);
            g.setLineDashes(5, 5);
            if (arc) {
                g.beginPath();
                g.moveTo(ex, ey);
                for (int i = 1; i <= 30; i++) g.lineTo(bx(ts * i / 30.0), by(ts * i / 30.0));
                g.stroke();
            } else {
                g.strokeLine(ex, ey, shipX, shipY);
            }
            g.setLineDashes((double[]) null);
            int dots = 3;
            for (int k = 0; k < dots; k++) {
                double f = 1 - ((time * (arc ? 0.22 : 0.6) + k / (double) dots) % 1.0);   // від корабля до Землі
                double px = arc ? bx(ts * f) : ex + (shipX - ex) * f;
                double py = arc ? by(ts * f) : ey + (shipY - ey) * f;
                double al = Math.min(1, Math.min(f, 1 - f) * 5);
                g.setFill(Color.color(0.24, 0.77, 0.54, 0.35 + 0.65 * al));
                g.fillOval(px - 3.2, py - 3.2, 6.4, 6.4);
            }
        }

        // позначка «ВИ ТУТ»
        Color ring = blackout ? HoloUi.AMBER : cyan;
        g.setFill(Color.web("#0B1322"));
        g.fillOval(shipX - 10, shipY - 10, 20, 20);
        g.setStroke(ring);
        g.setLineWidth(1.6);
        g.strokeOval(shipX - 10, shipY - 10, 20, 20);
        g.setFill(Color.WHITE);
        g.fillPolygon(new double[]{shipX, shipX - 5.5, shipX + 5.5},
                new double[]{shipY - 6, shipY + 5, shipY + 5}, 3);
        label(g, "ВИ ТУТ", shipX, shipY - 17, Color.WHITE, 11, true);

        // підпис затримки сигналу
        String lat = switch (loc) {
            case LEO -> "Сигнал до Землі: майже миттєво";
            case MOON -> "Сигнал до Землі: ~1,3 с";
            case MARS_TRANSIT -> "Сигнал до Землі: ~14 хв";
            case SOLAR_CONJUNCTION -> "Зв'язок відсутній (0 bps)";
        };
        Color latColor = blackout ? Color.web("#FCA5A5") : Color.web("#7DD3FC");
        Text probe = new Text(lat);
        probe.setFont(font(12, true));
        double tw = probe.getLayoutBounds().getWidth();
        g.setFill(HoloUi.alpha(latColor, 0.12));
        g.fillRoundRect(12, 10, tw + 20, 24, 12, 12);
        g.setStroke(HoloUi.alpha(latColor, 0.55));
        g.setLineWidth(1);
        g.strokeRoundRect(12.5, 10.5, tw + 19, 23, 12, 12);
        g.setFont(font(12, true));
        g.setFill(latColor);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(lat, 22, 26);
    }
}
