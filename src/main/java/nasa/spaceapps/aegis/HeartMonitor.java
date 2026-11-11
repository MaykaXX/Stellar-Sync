package nasa.spaceapps.aegis;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.Locale;

/**
 * Лінія серцебиття у стилі кардіомонітора.
 * Стрічка біжить зліва направо, а зубці йдуть із реальною частотою пульсу.
 * Не залежить від решти програми: достатньо викликати update() кожен кадр.
 */
public final class HeartMonitor extends Canvas {

    private static final int CAPACITY = 4096;
    /** Швидкість стрічки, пікселів за секунду. */
    private static final double SPEED_PX_PER_SEC = 140.0;

    private final float[] buffer = new float[CAPACITY];
    private int head = 0;          // куди писати наступну точку
    private double columnAccum = 0;
    private double beatPhase = 0;  // 0..1 всередині одного удару
    private double bpm = 75;

    /**
     * @param dtSec  скільки секунд минуло з минулого кадру
     * @param bpm    поточний пульс
     * @param warn   true, якщо пульс або стрес-баланс поза нормою (лінія стане помаранчевою)
     * @param render малювати чи ні (стрічка рухається й тоді, коли екран прихований)
     */
    public void update(double dtSec, double bpm, boolean warn, boolean render) {
        this.bpm = bpm;
        double dt = Math.min(Math.max(dtSec, 0), 0.25);
        columnAccum += dt * SPEED_PX_PER_SEC;
        double phaseStep = (bpm / 60.0) / SPEED_PX_PER_SEC;

        while (columnAccum >= 1.0) {
            columnAccum -= 1.0;
            beatPhase += phaseStep;
            if (beatPhase >= 1.0) beatPhase -= 1.0;
            buffer[head] = (float) waveform(beatPhase);
            head = (head + 1) % CAPACITY;
        }
        if (render) draw(warn);
    }

    private static double bump(double x, double center, double width) {
        double d = (x - center) / width;
        return Math.exp(-d * d);
    }

    /** Форма одного удару: зубці P, Q, R, S і T. Значення приблизно від -0.25 до 1. */
    private static double waveform(double p) {
        return 0.12 * bump(p, 0.18, 0.04)
                - 0.10 * bump(p, 0.285, 0.012)
                + 1.00 * bump(p, 0.300, 0.014)
                - 0.22 * bump(p, 0.318, 0.014)
                + 0.30 * bump(p, 0.550, 0.060);
    }

    private void draw(boolean warn) {
        double w = getWidth();
        double h = getHeight();
        if (w < 10 || h < 10) return;

        GraphicsContext gc = getGraphicsContext2D();
        gc.setLineDashes((double[]) null);
        gc.setFill(Color.rgb(8, 12, 20));
        gc.fillRect(0, 0, w, h);

        // Сітка
        gc.setStroke(Color.rgb(30, 41, 59, 0.9));
        gc.setLineWidth(1);
        for (double x = 0; x < w; x += 20) gc.strokeLine(x, 0, x, h);
        for (double y = 0; y < h; y += 20) gc.strokeLine(0, y, w, y);

        Color c = warn ? Color.rgb(249, 115, 22) : Color.rgb(52, 211, 153);
        int n = (int) Math.min(w, CAPACITY - 1);
        double base = h * 0.66;
        double amp = h * 0.50;

        // Лінія зі світінням: спочатку товста прозора, потім тонка яскрава
        for (int pass = 0; pass < 2; pass++) {
            gc.setStroke(pass == 0 ? Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.25) : c);
            gc.setLineWidth(pass == 0 ? 5 : 1.8);
            gc.beginPath();
            for (int i = 0; i < n; i++) {
                int idx = ((head - n + i) % CAPACITY + CAPACITY) % CAPACITY;
                double x = w - n + i;
                double y = base - buffer[idx] * amp;
                if (i == 0) gc.moveTo(x, y);
                else gc.lineTo(x, y);
            }
            gc.stroke();
        }

        // Число пульсу
        gc.setFill(c);
        gc.setFont(Font.font("Consolas", 24));
        gc.fillText(String.format(Locale.US, "%.0f", bpm), 12, 30);
        gc.setFont(Font.font("Consolas", 11));
        gc.fillText("уд/хв", 12, 46);
    }
}