package nasa.spaceapps.aegis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Зберігає історію показників і прогнозує, коли показник вийде за межі норми.
 * Не залежить від JavaFX, тому його легко тестувати.
 *
 * Прогноз: лінійна регресія (метод найменших квадратів) за останніми вимірюваннями.
 * Час усюди в хвилинах симуляції, швидкість зміни повертається в одиницях за годину.
 */
public final class TrendEngine<K extends Enum<K>> {

    /** Одне вимірювання: час симуляції (хв) і значення. */
    public record Sample(double timeMin, double value) {}

    public enum Direction { UP, DOWN, STABLE }

    /**
     * @param ready         чи достатньо даних для прогнозу
     * @param slopePerHour  швидкість зміни (одиниць за годину), знак показує напрям
     * @param direction     UP / DOWN / STABLE
     * @param leavesRange   true, якщо за цим темпом показник вийде за межу норми
     * @param hoursToLimit  через скільки годин (NaN, якщо не виходить)
     * @param limit         межа, яку буде перетнуто (NaN, якщо не виходить)
     */
    public record Forecast(boolean ready, double slopePerHour, Direction direction,
                           boolean leavesRange, double hoursToLimit, double limit) {
        static Forecast notReady() {
            return new Forecast(false, 0, Direction.STABLE, false, Double.NaN, Double.NaN);
        }
    }

    private final Class<K> keyType;
    private final int capacity;
    private final int window;
    private final int minSamples;
    private final Map<K, Deque<Sample>> data;

    /**
     * @param keyType    тип ключа (enum показників)
     * @param capacity   скільки вимірювань на показник зберігати
     * @param window     скільки останніх вимірювань брати для прогнозу
     * @param minSamples мінімум вимірювань, щоб прогноз вважався надійним
     */
    public TrendEngine(Class<K> keyType, int capacity, int window, int minSamples) {
        if (capacity < 2 || window < 2 || minSamples < 2 || minSamples > window || window > capacity) {
            throw new IllegalArgumentException("Некоректні параметри TrendEngine");
        }
        this.keyType = keyType;
        this.capacity = capacity;
        this.window = window;
        this.minSamples = minSamples;
        this.data = new EnumMap<>(keyType);
    }

    /** Додає вимірювання. Нескінченні значення і вимірювання з «минулого» ігноруються. */
    public void record(K key, double timeMin, double value) {
        if (!Double.isFinite(timeMin) || !Double.isFinite(value)) return;
        Deque<Sample> q = data.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (!q.isEmpty() && timeMin <= q.peekLast().timeMin()) return;
        q.addLast(new Sample(timeMin, value));
        while (q.size() > capacity) q.removeFirst();
    }

    /** Забуває історію одного показника. */
    public void clear(K key) {
        data.remove(key);
    }

    /** Забуває всю історію (наприклад, після різкої зміни стану: дія, імпорт, скидання). */
    public void clearAll() {
        data.clear();
    }

    public int size(K key) {
        Deque<Sample> q = data.get(key);
        return q == null ? 0 : q.size();
    }

    /** Останні {@code max} вимірювань у хронологічному порядку. */
    public List<Sample> samples(K key, int max) {
        Deque<Sample> q = data.get(key);
        if (q == null || q.isEmpty()) return List.of();
        List<Sample> all = new ArrayList<>(q);
        return all.subList(Math.max(0, all.size() - max), all.size());
    }

    /**
     * Прогноз виходу за межі [min, max].
     * Показник вважається стабільним, якщо за годину він міняється менше ніж на 0.25% ширини норми.
     */
    public Forecast forecast(K key, double min, double max) {
        List<Sample> pts = samples(key, window);
        if (pts.size() < minSamples) return Forecast.notReady();

        // Лінійна регресія value = a + b * t (t відносно першої точки, для точності)
        double t0 = pts.get(0).timeMin();
        int n = pts.size();
        double sumT = 0, sumV = 0;
        for (Sample s : pts) {
            sumT += s.timeMin() - t0;
            sumV += s.value();
        }
        double meanT = sumT / n, meanV = sumV / n;
        double cov = 0, varT = 0;
        for (Sample s : pts) {
            double dt = (s.timeMin() - t0) - meanT;
            cov += dt * (s.value() - meanV);
            varT += dt * dt;
        }
        if (varT <= 0) return Forecast.notReady();

        double slopePerHour = (cov / varT) * 60.0;
        double span = Math.max(1e-9, max - min);
        double eps = 0.0025 * span;

        if (Math.abs(slopePerHour) < eps) {
            return new Forecast(true, slopePerHour, Direction.STABLE, false, Double.NaN, Double.NaN);
        }

        double current = pts.get(n - 1).value();
        if (slopePerHour > 0) {
            if (current > max) return new Forecast(true, slopePerHour, Direction.UP, false, Double.NaN, Double.NaN);
            double h = (max - current) / slopePerHour;
            return new Forecast(true, slopePerHour, Direction.UP, true, h, max);
        } else {
            if (current < min) return new Forecast(true, slopePerHour, Direction.DOWN, false, Double.NaN, Double.NaN);
            double h = (current - min) / -slopePerHour;
            return new Forecast(true, slopePerHour, Direction.DOWN, true, h, min);
        }
    }

    public Class<K> keyType() {
        return keyType;
    }
}