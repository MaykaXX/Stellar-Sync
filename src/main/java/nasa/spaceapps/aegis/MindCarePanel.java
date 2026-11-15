package nasa.spaceapps.aegis;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * VITAL MindCare: вкладка психологічної підтримки екіпажу (раніше окреме вікно).
 * Усе працює локально: правила й заздалегідь підготовлені вправи, без нейромереж і токенів.
 * Це демонстраційний модуль, а не медичний прилад і не заміна психолога.
 */
public final class MindCarePanel {

    /** Усе, що вікну потрібно від головного застосунку. */
    public record Hooks(StressEngine engine,
                        LongSupplier clockSec,
                        Consumer<String> log,
                        Runnable onBreathingDone,
                        Runnable onSos) {}

    // ============ СТАН СЕСІЇ (живе, поки працює програма) ============
    private static final class Journal {
        int moodSum = 0;
        int moodCount = 0;
        long questDay = -1;
        final Set<String> doneToday = new HashSet<>();
        final Deque<String> recentMoods = new ArrayDeque<>();
    }

    private static final Journal JOURNAL = new Journal();

    private record Quest(String id, String text) {}

    private static final Quest[] QUESTS = {
            new Quest("STRETCH", "🤸 Розминка 5 хв"),
            new Quest("WATER", "💧 Випити води"),
            new Quest("GAZE", "🪟 2 хв подивитись вдалину (без екрана)"),
            new Quest("TALK", "🗣 5 хв поговорити з колегою по екіпажу")
    };

    private static final String[] GROUNDING = {
            "Назвіть 5 речей, які ВИ БАЧИТЕ навколо.",
            "Назвіть 4 речі, яких ВИ МОЖЕТЕ ТОРКНУТИСЬ.",
            "Назвіть 3 звуки, які ВИ ЧУЄТЕ.",
            "Назвіть 2 запахи, які ВИ ВІДЧУВАЄТЕ.",
            "Назвіть 1 смак у роті. Зробіть глибокий вдих. Готово."
    };

    private static final String[] MOOD_LABEL = {"Дуже погано", "Погано", "Нормально", "Добре", "Чудово"};

    // ============ ВІКНО ============
    private final Hooks hooks;
    private final ScrollPane view;
    private final BreathingPane breathing;

    private final Label stressValue = lbl("", 26, "#34D399", true);
    private final Label stressLevel = lbl("", 13, "#34D399", true);
    private final ProgressBar stressBar = new ProgressBar(0);
    private final Label reasonsLabel = lbl("", 11, "#94A3B8", false);
    private final Label protocolLabel = lbl("", 12, "#E2E8F0", false);
    private final Label suggestBanner = lbl("", 12, "#0B1220", true);
    private final Label moodInfo = lbl("", 11, "#94A3B8", false);
    private final Label moodHint = lbl("", 11, "#FBBF24", false);
    private final VBox questBox = new VBox(6);
    private final Button sosBtn = new Button("🆘 SOS: потрібна психологічна допомога");

    private String lastQuestSig = "";
    private int groundingStep = -1;
    private final Label groundingText = lbl("", 12, "#E2E8F0", false);
    private final Button groundingBtn = new Button();

    /** Короткий текст для звіту (див. buildReport). */
    public static String summaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "- Вправи на відновлення відмічено сьогодні: %d з %d%n", JOURNAL.doneToday.size(), QUESTS.length));
        if (JOURNAL.moodCount == 0) {
            sb.append("- Чек-ін настрою: записів немає\n");
        } else {
            sb.append(String.format(Locale.US, "- Середній настрій: %.1f з 5 (записів: %d)%n",
                    JOURNAL.moodSum / (double) JOURNAL.moodCount, JOURNAL.moodCount));
            for (String m : JOURNAL.recentMoods) sb.append("  ").append(m).append('\n');
        }
        return sb.toString();
    }

    public MindCarePanel(Hooks hooks) {
        this.hooks = hooks;
        breathing = new BreathingPane(this::onBreathingFinished);

        // Дві колонки на всю ширину екрана: ліворуч стан і дихання, праворуч вправи та SOS
        VBox breathCard = card("Дихання 4-4-4-4", "Вдих 4 с, затримка 4 с, видих 4 с, затримка 4 с. Чотири цикли: близько хвилини.", breathing);
        VBox.setVgrow(breathCard, Priority.ALWAYS);
        VBox left = new VBox(14, statusCard(), breathCard);
        VBox right = new VBox(14, moodCard(), questsCard(), groundingCard(), sosCard());
        for (VBox col : new VBox[]{left, right}) {
            col.setMinWidth(0);
            col.setPrefWidth(0);
            col.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(col, Priority.ALWAYS);
        }
        HBox columns = new HBox(16, left, right);

        VBox root = new VBox(14, header(), columns);
        root.setPadding(new Insets(10, 8, 16, 4));
        root.setMaxWidth(Double.MAX_VALUE);
        root.setStyle("-fx-background-color: transparent;");

        view = new ScrollPane(root);
        view.setFitToWidth(true);
        view.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshView()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        refresh.play();
        refreshView();
    }

    /** Вміст вкладки. */
    public Node view() {
        return view;
    }

    /** Викликається, коли вкладку закрили: зупиняє анімацію дихання. */
    public void onHidden() {
        breathing.stop();
    }

    /** Викликається при відкритті вкладки: одразу оновлює дані. */
    public void onShown() {
        refreshView();
    }

    // ============ БЛОКИ ІНТЕРФЕЙСУ ============
    private Node header() {
        Label title = lbl("VITAL: ПСИХОЛОГІЧНА ПІДТРИМКА", 20, "#22D3EE", true);
        title.setGraphic(HoloUi.iconMind());
        title.setGraphicTextGap(10);
        Label sub = lbl("Локальна підтримка без інтернету й без нейромереж: прозорі правила та готові вправи. "
                + "Це не медичний прилад.", 11, "#A3B1C6", false);
        sub.setWrapText(true);
        VBox box = new VBox(4, title, sub);
        return box;
    }

    private Node statusCard() {
        stressBar.setMaxWidth(Double.MAX_VALUE);
        reasonsLabel.setWrapText(true);
        protocolLabel.setWrapText(true);

        suggestBanner.setWrapText(true);
        suggestBanner.setMaxWidth(Double.MAX_VALUE);
        suggestBanner.setPadding(new Insets(8));
        suggestBanner.setStyle("-fx-background-color: #FBBF24; -fx-background-radius: 8; "
                + "-fx-text-fill: #0B1220; -fx-font-size: 24px; -fx-font-weight: bold;");
        suggestBanner.setVisible(false);
        suggestBanner.setManaged(false);

        VBox top = new VBox(2, stressValue, stressLevel);
        return card("📈 Рівень стресу зараз",
                "Рахується з пульсу, HRV, сну та води й згладжується, щоб разовий стрибок не лякав.",
                top, stressBar, reasonsLabel, protocolLabel, suggestBanner);
    }

    private Node moodCard() {
        FlowPane row = new FlowPane(8, 8);
        for (int i = 0; i < MOOD_LABEL.length; i++) {
            final int value = i + 1;
            Button b = new Button();
            b.setGraphic(HoloUi.moodFace(value, 2.2));
            b.setTooltip(new javafx.scene.control.Tooltip(MOOD_LABEL[i]));
            b.setStyle("-fx-background-color: #1E293B; -fx-background-radius: 14; -fx-cursor: hand; "
                    + "-fx-border-color: #334155; -fx-border-radius: 14; -fx-padding: 8 16 8 16;");
            b.setOnAction(e -> onMood(value));
            row.getChildren().add(b);
        }
        moodInfo.setWrapText(true);
        moodHint.setWrapText(true);
        return card("😊 Емоційний чек-ін", "Одне натискання. Запис потрапляє в журнал з часом місії, щоб центр керування на Землі бачив контекст.",
                row, moodInfo, moodHint);
    }

    private Node questsCard() {
        return card("🌿 Щоденні вправи на відновлення",
                "Відмітьте те, що справді зробили. Це самозвіт: програма не може його перевірити. Нова доба: список оновлюється.",
                questBox);
    }

    private Node groundingCard() {
        groundingText.setWrapText(true);
        groundingBtn.setStyle(btnStyle("#7C3AED"));
        groundingBtn.setOnAction(e -> onGroundingNext());
        updateGroundingView();
        return card("🌿 Вправа «5-4-3-2-1»",
                "Повертає увагу в «тут і зараз» при тривозі. Виконуйте кроки по черзі.",
                groundingText, groundingBtn);
    }

    private Node sosCard() {
        sosBtn.setStyle(btnStyle("#B91C1C"));
        sosBtn.setMaxWidth(Double.MAX_VALUE);
        sosBtn.setOnAction(e -> onSos());
        Label note = lbl("Зв'язку із Землею може не бути. Тоді сигнал збережеться в локальному буфері "
                + "й піде в центр керування на Землі, щойно зв'язок з'явиться.", 11, "#A3B1C6", false);
        note.setWrapText(true);
        return card("🆘 Аварійний сигнал", "Використовуйте, якщо вам важко самостійно або ви хвилюєтесь за колегу.",
                sosBtn, note);
    }

    // ============ ДІЇ ============
    private void onBreathingFinished() {
        markOnce("BREATH_SESSION", "Дихальний сеанс 4-4-4-4 завершено");
        hooks.onBreathingDone().run();
        refreshView();
    }

    private void onMood(int value) {
        StressEngine.Assessment a = hooks.engine().latest();
        String line = String.format(Locale.US, "%s %d/5 (індекс стресу %d, %s)",
                MOOD_LABEL[value - 1], value, a.index(), a.level().label);
        JOURNAL.moodSum += value;
        JOURNAL.moodCount++;
        JOURNAL.recentMoods.addFirst(line);
        while (JOURNAL.recentMoods.size() > 5) JOURNAL.recentMoods.removeLast();

        hooks.log().accept("🧠 [MindCare] Чек-ін настрою: " + line + ".");

        if (value <= 1) {
            moodHint.setText("Вам дуже важко. Зробіть дихання, поговоріть із колегою або натисніть SOS нижче.");
        } else if (value == 2 || a.level().ordinal() >= StressEngine.Level.HIGH.ordinal()) {
            moodHint.setText("Схоже, день непростий. Спробуйте дихання 4-4-4-4 вище.");
        } else {
            moodHint.setText("Дякуємо, записано.");
        }
        refreshView();
    }

    private void onQuest(Quest q) {
        if (!JOURNAL.doneToday.add(q.id())) return;
        hooks.log().accept("🧠 [MindCare] Відмічено вручну: " + q.text() + ".");
        refreshView();
    }

    private void onGroundingNext() {
        if (groundingStep < 0 || groundingStep >= GROUNDING.length) {
            groundingStep = 0;
        } else {
            groundingStep++;
            if (groundingStep >= GROUNDING.length) {
                markOnce("GROUNDING", "Вправу «5-4-3-2-1» виконано");
                groundingStep = -1;
            }
        }
        updateGroundingView();
        refreshView();
    }

    private void onSos() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Надіслати аварійний сигнал психологічної підтримки в центр керування на Землі?",
                ButtonType.YES, ButtonType.NO);
        alert.setHeaderText("Сигнал SOS: потрібна психологічна допомога");
        alert.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> hooks.onSos().run());
    }

    private void markOnce(String id, String logText) {
        boolean first = JOURNAL.doneToday.add(id);
        hooks.log().accept("🧠 [MindCare] " + logText + (first ? "." : " (повторно за сьогодні)."));
    }

    // ============ ОНОВЛЕННЯ ЕКРАНА ============
    private void refreshView() {
        long day = hooks.clockSec().getAsLong() / 86400;
        if (JOURNAL.questDay != day) {
            JOURNAL.questDay = day;
            JOURNAL.doneToday.clear();
        }

        StressEngine.Assessment a = hooks.engine().latest();
        String color = a.level().color;
        stressValue.setText(a.index() + " / 100");
        stressValue.setStyle(labelStyle(26, color, true));
        stressLevel.setText(a.level().label);
        stressLevel.setStyle(labelStyle(13, color, true));
        stressBar.setProgress(a.index() / 100.0);
        stressBar.setStyle("-fx-accent: " + color + ";");
        reasonsLabel.setText(a.reasons().isEmpty()
                ? "Причин для хвилювання не видно."
                : "Що впливає: " + String.join("; ", a.reasons()) + ".");
        protocolLabel.setText(StressEngine.protocolFor(a.level()));

        boolean banner = a.sustained();
        suggestBanner.setVisible(banner);
        suggestBanner.setManaged(banner);
        if (banner) {
            suggestBanner.setText("Стрес тримається вже кілька хвилин. Зробіть паузу: почніть дихання нижче.");
        }

        boolean critical = a.level() == StressEngine.Level.CRITICAL;
        sosBtn.setText(critical ? "🆘 SOS: рекомендовано надіслати сигнал" : "🆘 SOS: потрібна психологічна допомога");

        if (JOURNAL.moodCount == 0) {
            moodInfo.setText("Записів ще немає.");
        } else {
            StringBuilder sb = new StringBuilder(String.format(Locale.US,
                    "Середній настрій: %.1f з 5. Останні записи:", JOURNAL.moodSum / (double) JOURNAL.moodCount));
            for (String m : JOURNAL.recentMoods) sb.append("\n• ").append(m);
            moodInfo.setText(sb.toString());
        }

        rebuildQuests();
    }

    private void rebuildQuests() {
        // Перебудовуємо кнопки лише коли щось змінилось, інакше клік може «загубитись» між оновленнями
        String sig = JOURNAL.questDay + ":" + JOURNAL.doneToday.stream().sorted().toList();
        if (sig.equals(lastQuestSig)) return;
        lastQuestSig = sig;
        questBox.getChildren().clear();
        for (Quest q : QUESTS) {
            boolean done = JOURNAL.doneToday.contains(q.id());
            Button b = new Button((done ? "✔ " : "") + q.text());
            b.setMaxWidth(Double.MAX_VALUE);
            b.setDisable(done);
            b.setStyle(btnStyle(done ? "#334155" : "#059669"));
            b.setOnAction(e -> onQuest(q));
            questBox.getChildren().add(b);
        }
    }

    private void updateGroundingView() {
        if (groundingStep < 0) {
            groundingText.setText("Натисніть «Почати», щоб пройти п'ять коротких кроків.");
            groundingBtn.setText("Почати");
        } else {
            groundingText.setText("Крок " + (groundingStep + 1) + " з " + GROUNDING.length + ": " + GROUNDING[groundingStep]);
            groundingBtn.setText(groundingStep == GROUNDING.length - 1 ? "Завершити" : "Далі →");
        }
    }

    // ============ ДИХАННЯ 4-4-4-4 ============
    private static final class BreathingPane extends VBox {
        private static final double PHASE_SEC = 4.0;
        private static final int CYCLES = 4;
        private static final String[] PHASES = {"Вдих", "Затримка", "Видих", "Затримка"};
        private static final double R_MIN = 38, R_MAX = 92;

        private final Canvas canvas = new Canvas(240, 220);
        private final Label phaseLabel = lbl("Готові?", 16, "#22D3EE", true);
        private final Label infoLabel = lbl("", 11, "#94A3B8", false);
        private final Button startBtn = new Button("▶ Почати дихання");
        private final Runnable onDone;
        private AnimationTimer timer;
        private long startNanos;

        BreathingPane(Runnable onDone) {
            super(8);
            this.onDone = onDone;
            setAlignment(Pos.CENTER);
            startBtn.setStyle(btnStyle("#0891B2"));
            startBtn.setOnAction(e -> {
                if (timer == null) start(); else stop();
            });
            getChildren().addAll(canvas, phaseLabel, infoLabel, startBtn);
            draw(R_MIN, Color.web("#22D3EE"));
        }

        void start() {
            stop();
            startNanos = 0;
            startBtn.setText("■ Зупинити");
            timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    if (startNanos == 0) startNanos = now;
                    double e = (now - startNanos) / 1_000_000_000.0;
                    double cycleLen = PHASE_SEC * 4;
                    int cycle = (int) (e / cycleLen);
                    if (cycle >= CYCLES) {
                        finish();
                        return;
                    }
                    double inCycle = e - cycle * cycleLen;
                    int phase = (int) (inCycle / PHASE_SEC);
                    double t = (inCycle - phase * PHASE_SEC) / PHASE_SEC;
                    double s = t * t * (3 - 2 * t); // плавний рух
                    double r = switch (phase) {
                        case 0 -> R_MIN + (R_MAX - R_MIN) * s;
                        case 1 -> R_MAX;
                        case 2 -> R_MAX - (R_MAX - R_MIN) * s;
                        default -> R_MIN;
                    };
                    int left = (int) Math.ceil(PHASE_SEC - (inCycle - phase * PHASE_SEC));
                    phaseLabel.setText(PHASES[phase] + "  " + Math.max(1, left));
                    infoLabel.setText("Цикл " + (cycle + 1) + " з " + CYCLES);
                    draw(r, phase == 0 || phase == 1 ? Color.web("#22D3EE") : Color.web("#34D399"));
                }
            };
            timer.start();
        }

        void stop() {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
            startBtn.setText("▶ Почати дихання");
            phaseLabel.setText("Готові?");
            infoLabel.setText("");
            draw(R_MIN, Color.web("#22D3EE"));
        }

        private void finish() {
            stop();
            phaseLabel.setText("Сеанс завершено ✔");
            infoLabel.setText("Помітьте, як змінилось самопочуття.");
            onDone.run();
        }

        private void draw(double r, Color c) {
            GraphicsContext g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth(), h = canvas.getHeight();
            double cx = w / 2, cy = h / 2;
            g.setFill(Color.web("#070C16"));
            g.fillRect(0, 0, w, h);
            g.setStroke(Color.web("#1E3A5F"));
            g.setLineWidth(1.5);
            g.strokeOval(cx - R_MAX - 8, cy - R_MAX - 8, (R_MAX + 8) * 2, (R_MAX + 8) * 2);
            g.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.18));
            g.fillOval(cx - r - 10, cy - r - 10, (r + 10) * 2, (r + 10) * 2);
            g.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.55));
            g.fillOval(cx - r, cy - r, r * 2, r * 2);
            g.setStroke(c);
            g.setLineWidth(2.5);
            g.strokeOval(cx - r, cy - r, r * 2, r * 2);
        }
    }

    // ============ СТИЛІ ============
    private static Label lbl(String text, int size, String color, boolean bold) {
        Label l = new Label(text);
        l.setStyle(labelStyle(size, color, bold));
        return l;
    }

    private static String labelStyle(int size, String color, boolean bold) {
        return "-fx-text-fill: " + color + "; -fx-font-size: " + (size * 2) + "px;" + (bold ? " -fx-font-weight: bold;" : "");
    }

    private static String btnStyle(String bg) {
        return "-fx-background-color: " + bg + "; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;";
    }

    private static VBox card(String title, String subtitle, Node... content) {
        Label t = lbl(title, 14, "#E2E8F0", true);
        Label s = lbl(subtitle, 11, "#A3B1C6", false);
        s.setWrapText(true);
        VBox box = new VBox(8);
        box.getChildren().addAll(t, s);
        box.getChildren().addAll(content);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #0B1220; -fx-background-radius: 10; "
                + "-fx-border-color: #1E3A5F; -fx-border-radius: 10;");
        return box;
    }
}