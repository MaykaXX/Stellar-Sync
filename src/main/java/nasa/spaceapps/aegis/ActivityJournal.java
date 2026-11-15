package nasa.spaceapps.aegis;

import java.util.*;
import java.util.function.Consumer;

/**
 * Журнал виконання: фіксує, що космонавт ДІЙСНО робив, а що лише натискав.
 *
 * Дія не діє миттєво. Спочатку її треба розпочати, потім минає її справжня тривалість
 * (за годинником місії), і лише тоді космонавт підтверджує виконання. Тільки після цього
 * змінюються показники. Усе інше (передчасне завершення, серії швидких натискань,
 * довга відсутність дій при активних підказках) потрапляє в журнал як окремі записи.
 *
 * Клас не залежить від JavaFX, тому його можна перевіряти окремо.
 */
final class ActivityJournal {

    enum Status {
        CONFIRMED("✔ Виконано, підтверджено", false),
        SELF_REPORTED("● Відмічено вручну", false),
        AUTO("🤖 Виконав автопілот / MindCare", false),
        ABORTED("✖ Не завершено", true),
        SUSPICIOUS("⚠ Підозріло", true),
        INACTIVITY("⚠ Немає активності", true);

        final String label;
        /** Чи вимагає запис уваги (показується в журналі та логах як попередження). */
        final boolean notable;

        Status(String label, boolean notable) {
            this.label = label;
            this.notable = notable;
        }
    }

    /** Один запис журналу. clockSec: час місії, actualSec/plannedSec: фактична й планова тривалість (с). */
    record Entry(long clockSec, String title, Status status, long plannedSec, long actualSec, String note) {}

    /** Дія, що виконується просто зараз. */
    record Session(String activityId, String title, long startSec, long requiredSec) {}

    /** Скільки секунд місії без підтверджених дій вважається «простоєм». */
    static final long INACTIVITY_SEC = 4 * 3600;
    /** Скільки натискань за яке реальне вікно вважається серією «натикування». */
    static final int MASH_PRESSES = 6;
    static final long MASH_WINDOW_MS = 8_000;
    static final long MASH_REPEAT_FLAG_MS = 30_000;
    private static final int MAX_ENTRIES = 500;

    private final List<Entry> entries = new ArrayList<>(); // нові зверху
    private final Deque<Long> pressTimes = new ArrayDeque<>();
    private Session active;
    private long lastConfirmedSec = -1;
    private long lastInactivityFlagSec = Long.MIN_VALUE;
    private long lastMashFlagMs = Long.MIN_VALUE;
    private Consumer<Entry> listener = e -> { };

    void setListener(Consumer<Entry> listener) {
        this.listener = listener == null ? e -> { } : listener;
    }

    // ---------- сесії ----------

    boolean hasActive() {
        return active != null;
    }

    Session active() {
        return active;
    }

    /** Розпочинає дію. Повертає null, якщо все добре, або причину відмови. */
    String begin(String id, String title, long requiredSec, long nowSec, long nowMs) {
        registerPress(nowSec, nowMs);
        if (active != null) {
            return "Спочатку завершіть або скасуйте поточну дію: " + active.title() + ".";
        }
        active = new Session(id, title, nowSec, Math.max(1, requiredSec));
        return null;
    }

    long elapsedSec(long nowSec) {
        return active == null ? 0 : Math.max(0, nowSec - active.startSec());
    }

    long remainingSec(long nowSec) {
        return active == null ? 0 : Math.max(0, active.requiredSec() - elapsedSec(nowSec));
    }

    boolean isReady(long nowSec) {
        return active != null && remainingSec(nowSec) == 0;
    }

    /** Підтвердження виконання. Повертає запис, або null, якщо час ще не минув (натискання зараховується як «зайве»). */
    Entry confirm(long nowSec, long nowMs) {
        registerPress(nowSec, nowMs);
        if (active == null || !isReady(nowSec)) return null;
        Session s = active;
        active = null;
        lastConfirmedSec = nowSec;
        return add(new Entry(nowSec, s.title(), Status.CONFIRMED, s.requiredSec(), nowSec - s.startSec(),
                "Підтверджено після повної тривалості."));
    }

    /** Скасування або дострокове завершення. Показники не змінюються. */
    Entry abort(long nowSec, long nowMs) {
        registerPress(nowSec, nowMs);
        if (active == null) return null;
        Session s = active;
        active = null;
        long done = Math.min(s.requiredSec(), Math.max(0, nowSec - s.startSec()));
        return add(new Entry(nowSec, s.title(), Status.ABORTED, s.requiredSec(), done,
                "Дію не завершено: минуло " + formatSec(done) + " із " + formatSec(s.requiredSec()) + ". Показники не змінено."));
    }

    /** Відмова системи виконати дію (ліміт, перерва). Саме натискання враховується для виявлення «натикування». */
    void rejectedPress(long nowSec, long nowMs) {
        registerPress(nowSec, nowMs);
    }

    // ---------- інші записи ----------

    /** Одномоментна дія зі слів космонавта (наприклад, таблетка): її не можна перевірити таймером. */
    Entry addSelfReported(String title, String note, long nowSec, long nowMs) {
        registerPress(nowSec, nowMs);
        lastConfirmedSec = nowSec;
        return add(new Entry(nowSec, title, Status.SELF_REPORTED, 0, 0, note));
    }

    /** Дію виконав автопілот блекауту або завершена вправа MindCare. Не рахується як активність космонавта. */
    Entry addAuto(String title, String note, long nowSec) {
        return add(new Entry(nowSec, title, Status.AUTO, 0, 0, note));
    }

    /**
     * Перевірка простою: якщо давно немає підтверджених дій, а підказки вимагають дій, створюється запис.
     * Викликається раз на секунду симуляції.
     */
    Entry checkInactivity(long nowSec, boolean actionNeeded) {
        if (!actionNeeded || active != null) return null;
        long since = lastConfirmedSec < 0 ? nowSec : nowSec - lastConfirmedSec;
        if (lastConfirmedSec < 0 && nowSec < INACTIVITY_SEC) return null;
        if (since < INACTIVITY_SEC) return null;
        if (lastInactivityFlagSec != Long.MIN_VALUE && nowSec - lastInactivityFlagSec < INACTIVITY_SEC) return null;
        lastInactivityFlagSec = nowSec;
        return add(new Entry(nowSec, "Немає підтверджених дій", Status.INACTIVITY, 0, since,
                "Минуло " + formatSec(since) + " без жодної підтвердженої дії, хоча є підказки, які потребують виконання."));
    }

    // ---------- виявлення «натикування» ----------

    private void registerPress(long nowSec, long nowMs) {
        pressTimes.addLast(nowMs);
        while (!pressTimes.isEmpty() && nowMs - pressTimes.peekFirst() > MASH_WINDOW_MS) pressTimes.removeFirst();
        if (pressTimes.size() >= MASH_PRESSES
                && (lastMashFlagMs == Long.MIN_VALUE || nowMs - lastMashFlagMs > MASH_REPEAT_FLAG_MS)) {
            lastMashFlagMs = nowMs;
            int n = pressTimes.size();
            pressTimes.clear();
            add(new Entry(nowSec, "Серія швидких натискань", Status.SUSPICIOUS, 0, 0,
                    n + " натискань кнопок за " + (MASH_WINDOW_MS / 1000) + " с. Схоже на натикування без реального виконання дій."));
        }
    }

    // ---------- читання ----------

    /** Записи, нові зверху. */
    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    Map<Status, Integer> counts() {
        Map<Status, Integer> m = new EnumMap<>(Status.class);
        for (Status s : Status.values()) m.put(s, 0);
        for (Entry e : entries) m.merge(e.status(), 1, Integer::sum);
        return m;
    }

    /** Секунд з останньої підтвердженої дії космонавта, або -1, якщо дій ще не було. */
    long sinceLastConfirmed(long nowSec) {
        return lastConfirmedSec < 0 ? -1 : Math.max(0, nowSec - lastConfirmedSec);
    }

    void reset() {
        entries.clear();
        pressTimes.clear();
        active = null;
        lastConfirmedSec = -1;
        lastInactivityFlagSec = Long.MIN_VALUE;
        lastMashFlagMs = Long.MIN_VALUE;
    }

    private Entry add(Entry e) {
        entries.add(0, e);
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        listener.accept(e);
        return e;
    }

    static String formatSec(long sec) {
        sec = Math.max(0, sec);
        if (sec < 60) return sec + " с";
        if (sec < 3600) return (sec / 60) + " хв" + (sec % 60 == 0 ? "" : " " + (sec % 60) + " с");
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        return h + " год" + (m == 0 ? "" : " " + m + " хв");
    }

    /** Короткий текст «ММ:СС» або «Г:ММ:СС» для лічильника. */
    static String clock(long sec) {
        sec = Math.max(0, sec);
        long h = sec / 3600;
        long m = (sec / 60) % 60;
        long s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }
}
