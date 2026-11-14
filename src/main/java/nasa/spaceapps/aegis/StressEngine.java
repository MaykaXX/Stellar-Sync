package nasa.spaceapps.aegis;

import java.util.ArrayList;
import java.util.List;

/**
 * Aegis MindCare: локальна експертна система оцінки стресу.
 * Без нейромереж, без мережі, без токенів: лише прозорі правила й ковзне середнє.
 * Не залежить від JavaFX, тому легко тестується.
 *
 * Це демонстраційна модель, а не медичний прилад.
 */
public final class StressEngine {

    public enum Level {
        LOW("Спокійно", "#34D399"),
        MEDIUM("Напруга", "#FBBF24"),
        HIGH("Високий стрес", "#F97316"),
        CRITICAL("Критично", "#EF4444");

        public final String label;
        public final String color;

        Level(String label, String color) {
            this.label = label;
            this.color = color;
        }
    }

    /** Вхідні показники одного виміру. */
    public record Reading(double hr, double hrv, double hydration, double sleepHours) {}

    /**
     * @param index     згладжений індекс стресу 0..100
     * @param level     рівень за індексом
     * @param reasons   що саме тисне на організм (людською мовою)
     * @param sustained true, якщо рівень MEDIUM або вищий тримається довше за {@link #SUSTAIN_SEC}
     */
    public record Assessment(int index, Level level, List<String> reasons, boolean sustained) {}

    /** Як довго (с часу місії) стрес має триматись, перш ніж система запропонує паузу. */
    public static final long SUSTAIN_SEC = 180;
    /** Мінімальна перерва між двома пропозиціями паузи (с часу місії), щоб не дратувати. */
    public static final long SUGGEST_COOLDOWN_SEC = 900;
    /** Вага нового виміру у згладженні (EWMA): менше значення = плавніша реакція. */
    private static final double ALPHA = 0.15;

    private double smoothed = Double.NaN;
    private long elevatedSinceSec = -1;
    private long lastSuggestSec = Long.MIN_VALUE / 2;
    private long lastCriticalAlertSec = Long.MIN_VALUE / 2;
    private Assessment latest = new Assessment(0, Level.LOW, List.of(), false);

    /** Лінійна шкала: 0 при x ≤ good, 1 при x ≥ bad (працює в обидва боки). */
    static double scale(double x, double good, double bad) {
        if (good == bad) return 0;
        double t = (x - good) / (bad - good);
        return Math.max(0, Math.min(1, t));
    }

    /** Сирий (негладкий) індекс 0..100 за вагами: сон 30%, HRV 30%, пульс 25%, вода 15%. */
    static double rawIndex(Reading r) {
        double hrHigh = scale(r.hr(), 75, 120);
        double hrLow = scale(r.hr(), 55, 40);
        double hrF = Math.max(hrHigh, hrLow);
        double hrvF = scale(r.hrv(), 60, 25);
        double sleepF = scale(r.sleepHours(), 7, 3);
        double hydF = scale(r.hydration(), 90, 70);
        return 100.0 * (0.25 * hrF + 0.30 * hrvF + 0.30 * sleepF + 0.15 * hydF);
    }

    static Level levelFor(double index) {
        if (index < 25) return Level.LOW;
        if (index < 50) return Level.MEDIUM;
        if (index < 75) return Level.HIGH;
        return Level.CRITICAL;
    }

    /** Викликається раз на крок (наприклад, щосекунди) з поточними показниками й годинником місії. */
    public synchronized Assessment update(Reading r, long clockSec) {
        double raw = rawIndex(r);
        smoothed = Double.isNaN(smoothed) ? raw : smoothed + ALPHA * (raw - smoothed);

        Level level = levelFor(smoothed);
        if (level.ordinal() >= Level.MEDIUM.ordinal()) {
            if (elevatedSinceSec < 0) elevatedSinceSec = clockSec;
        } else {
            elevatedSinceSec = -1;
        }
        boolean sustained = elevatedSinceSec >= 0 && clockSec - elevatedSinceSec >= SUSTAIN_SEC;

        List<String> reasons = new ArrayList<>();
        if (r.sleepHours() < 6) {
            reasons.add(String.format(java.util.Locale.US, "мало сну (%.1f год)", r.sleepHours()));
        }
        if (r.hrv() < 45) {
            reasons.add(String.format(java.util.Locale.US, "низький стрес-баланс серця HRV (%.0f мс)", r.hrv()));
        }
        if (r.hr() > 100) {
            reasons.add(String.format(java.util.Locale.US, "підвищений пульс (%.0f уд/хв)", r.hr()));
        } else if (r.hr() < 55) {
            reasons.add(String.format(java.util.Locale.US, "низький пульс (%.0f уд/хв)", r.hr()));
        }
        if (r.hydration() < 90) {
            reasons.add(String.format(java.util.Locale.US, "не вистачає води (%.0f%%)", r.hydration()));
        }

        latest = new Assessment((int) Math.round(smoothed), level, List.copyOf(reasons), sustained);
        return latest;
    }

    public synchronized Assessment latest() {
        return latest;
    }

    /**
     * Чи варто зараз м'яко запропонувати паузу. Повертає true не частіше, ніж раз на
     * {@link #SUGGEST_COOLDOWN_SEC}, і лише коли стрес тримається, а не при разовому стрибку.
     */
    public synchronized boolean shouldSuggest(long clockSec) {
        if (!latest.sustained()) return false;
        if (clockSec - lastSuggestSec < SUGGEST_COOLDOWN_SEC) return false;
        lastSuggestSec = clockSec;
        return true;
    }

    /**
     * Чи треба САМОМУ відкрити вікно MindCare: лише коли рівень «Критично» тримається
     * довше за {@link #SUSTAIN_SEC}, і не частіше, ніж раз на {@link #SUGGEST_COOLDOWN_SEC}.
     */
    public synchronized boolean shouldAlertCritical(long clockSec) {
        if (latest.level() != Level.CRITICAL || !latest.sustained()) return false;
        if (clockSec - lastCriticalAlertSec < SUGGEST_COOLDOWN_SEC) return false;
        lastCriticalAlertSec = clockSec;
        return true;
    }

    /** Забуває історію (наприклад, при скиданні симуляції). */
    public synchronized void reset() {
        smoothed = Double.NaN;
        elevatedSinceSec = -1;
        lastSuggestSec = Long.MIN_VALUE / 2;
        lastCriticalAlertSec = Long.MIN_VALUE / 2;
        latest = new Assessment(0, Level.LOW, List.of(), false);
    }

    /** Заздалегідь підготовлений протокол реагування за рівнем (без генерації тексту). */
    public static String protocolFor(Level level) {
        return switch (level) {
            case LOW -> "Усе спокійно. Можна зробити короткий чек-ін настрою або легку розминку.";
            case MEDIUM -> "Помітна напруга. Спробуйте дихання 4-4-4-4 (близько 1 хвилини).";
            case HIGH -> "Високий стрес. Зробіть дихання 4-4-4-4, потім вправу «5-4-3-2-1». "
                    + "Усуньте причини нижче: вода, сон, відпочинок.";
            case CRITICAL -> "Критичний рівень. Зупиніть роботу, зробіть дихання, повідомте екіпаж "
                    + "і надішліть сигнал SOS у ЦУП кнопкою нижче.";
        };
    }
}