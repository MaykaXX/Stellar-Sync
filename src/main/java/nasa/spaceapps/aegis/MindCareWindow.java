package nasa.spaceapps.aegis;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Aegis MindCare: окреме вікно психологічної підтримки екіпажу.
 * Усе працює локально: правила й заздалегідь підготовлені вправи, без нейромереж і токенів.
 * Це демонстраційний модуль, а не медичний прилад і не заміна психолога.
 */
public final class MindCareWindow {

    /** Усе, що вікну потрібно від головного застосунку. */
    public record Hooks(StressEngine engine,
                        LongSupplier clockSec,
                        Consumer<String> log,
                        Runnable onBreathingDone,
                        Runnable onSos) {}

    // ============ СТАН СЕСІЇ (живе, поки працює програма) ============
    private static final class Journal {
        int points = 0;
        int moodSum = 0;
        int moodCount = 0;
        long lastMoodRewardDay = -1;
        long questDay = -1;
        final Set<String> doneToday = new HashSet<>();
        final Deque<String> recentMoods = new ArrayDeque<>();
    }

    private static final Journal JOURNAL = new Journal();
    private static Stage instance;

    private record Quest(String id, String text, int points) {}

    private static final Quest[] QUESTS = {
            new Quest("STRETCH", "🤸 Розминка 5 хв", 10),
            new Quest("WATER", "💧 Випити води", 5),
            new Quest("GAZE", "🪟 2 хв подивитись вдалину (без екрана)", 5),
            new Quest("TALK", "🗣 5 хв поговорити з колегою по екіпажу", 10)
    };

    private static final String[] GROUNDING = {
            "Назвіть 5 речей, які ВИ БАЧИТЕ навколо.",
            "Назвіть 4 речі, яких ВИ МОЖЕТЕ ТОРКНУТИСЬ.",
            "Назвіть 3 звуки, які ВИ ЧУЄТЕ.",
            "Назвіть 2 запахи, які ВИ ВІДЧУВАЄТЕ.",
            "Назвіть 1 смак у роті. Зробіть глибокий вдих. Готово."
    };

    private static final String[] MOOD_EMOJI = {"😞", "😕", "😐", "🙂", "😄"};

    // ============ ВІКНО ============
    private final Hooks hooks;
    private final Stage stage;
    private final Timeline refresh;
    private final BreathingPane breathing;

    private final Label pointsLabel = lbl("", 12, "#FBBF24", true);
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

    /** Відкриває вікно (або виносить уже відкрите на передній план). */
    public static void open(Stage owner, Hooks hooks) {
        if (instance != null && instance.isShowing()) {
            instance.toFront();
            instance.requestFocus();
            return;
        }
        MindCareWindow w = new MindCareWindow(owner, hooks);
        instance = w.stage;
        w.stage.show();
    }

    /** Короткий текст для звіту (див. buildReport). */
    public static String summaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "- Бали життєстійкості: %d (%s)%n", JOURNAL.points, rank(JOURNAL.points)));
        if (JOURNAL.moodCount == 0) {
            sb.append("- Чек-ін настрою: записів немає\n");
        } else {
            sb.append(String.format(Locale.US, "- Середній настрій: %.1f з 5 (записів: %d)%n",
                    JOURNAL.moodSum / (double) JOURNAL.moodCount, JOURNAL.moodCount));
            for (String m : JOURNAL.recentMoods) sb.append("  ").append(m).append('\n');
        }
        return sb.toString();
    }

    private MindCareWindow(Stage owner, Hooks hooks) {
        this.hooks = hooks;
        this.stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Aegis MindCare: психологічна підтримка екіпажу");
        stage.setMinWidth(480);
        stage.setMinHeight(560);

        breathing = new BreathingPane(this::onBreathingFinished);

        VBox root = new VBox(14,
                header(),
                statusCard(),
                card("🫁 Дихання 4-4-4-4", "Вдих 4 с, затримка 4 с, видих 4 с, затримка 4 с. Чотири цикли: близько хвилини.", breathing),
                moodCard(),
                questsCard(),
                groundingCard(),
                sosCard());
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: #060A12;");

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #060A12; -fx-background-color: #060A12;");

        stage.setScene(new Scene(scroll, 540, 800));

        refresh = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshView()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        refresh.play();
        refreshView();

        stage.setOnHidden(e -> {
            refresh.stop();
            breathing.stop();
            instance = null;
        });
    }

    // ============ БЛОКИ ІНТЕРФЕЙСУ ============
    private Node header() {
        Label title = lbl("🧠 AEGIS MINDCARE", 18, "#22D3EE", true);
        Label sub = lbl("Локальна підтримка без інтернету й без нейромереж: прозорі правила та готові вправи. "
                + "Це не медичний прилад.", 11, "#64748B", false);
        sub.setWrapText(true);
        VBox box = new VBox(4, title, sub, pointsLabel);
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
                + "-fx-text-fill: #0B1220; -fx-font-size: 12px; -fx-font-weight: bold;");
        suggestBanner.setVisible(false);
        suggestBanner.setManaged(false);

        VBox top = new VBox(2, stressValue, stressLevel);
        return card("📈 Рівень стресу зараз",
                "Рахується з пульсу, HRV, сну та води й згладжується, щоб разовий стрибок не лякав.",
                top, stressBar, reasonsLabel, protocolLabel, suggestBanner);
    }

    private Node moodCard() {
        FlowPane row = new FlowPane(8, 8);
        for (int i = 0; i < MOOD_EMOJI.length; i++) {
            final int value = i + 1;
            Button b = new Button(MOOD_EMOJI[i]);
            b.setStyle("-fx-font-size: 22px; -fx-background-color: #1E293B; -fx-background-radius: 10; -fx-cursor: hand;");
            b.setOnAction(e -> onMood(value));
            row.getChildren().add(b);
        }
        moodInfo.setWrapText(true);
        moodHint.setWrapText(true);
        return card("😊 Емоційний чек-ін", "Одне натискання. Запис потрапляє в журнал з часом місії, щоб ЦУП бачив контекст.",
                row, moodInfo, moodHint);
    }

    private Node questsCard() {
        return card("🎯 Міні-квести на сьогодні",
                "Дрібні дії на відновлення. За кожну дають бали життєстійкості. Нова доба: квести оновлюються.",
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
                + "й піде в ЦУП, щойно зв'язок з'явиться.", 11, "#64748B", false);
        note.setWrapText(true);
        return card("🆘 Аварійний сигнал", "Використовуйте, якщо вам важко самостійно або ви хвилюєтесь за колегу.",
                sosBtn, note);
    }

    // ============ ДІЇ ============
    private void onBreathingFinished() {
        awardOnce("BREATH_SESSION", 20, "Дихальний сеанс 4-4-4-4 завершено");
        hooks.onBreathingDone().run();
        refreshView();
    }

    private void onMood(int value) {
        StressEngine.Assessment a = hooks.engine().latest();
        String line = String.format(Locale.US, "%s %d/5 (індекс стресу %d, %s)",
                MOOD_EMOJI[value - 1], value, a.index(), a.level().label);
        JOURNAL.moodSum += value;
        JOURNAL.moodCount++;
        JOURNAL.recentMoods.addFirst(line);
        while (JOURNAL.recentMoods.size() > 5) JOURNAL.recentMoods.removeLast();

        hooks.log().accept("🧠 [MindCare] Чек-ін настрою: " + line + ".");

        long day = hooks.clockSec().getAsLong() / 86400;
        if (JOURNAL.lastMoodRewardDay != day) {
            JOURNAL.lastMoodRewardDay = day;
            JOURNAL.points += 5;
        }

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
        JOURNAL.points += q.points();
        hooks.log().accept("🧠 [MindCare] Квест виконано: " + q.text() + " (+" + q.points() + " балів).");
        refreshView();
    }

    private void onGroundingNext() {
        if (groundingStep < 0 || groundingStep >= GROUNDING.length) {
            groundingStep = 0;
        } else {
            groundingStep++;
            if (groundingStep >= GROUNDING.length) {
                awardOnce("GROUNDING", 15, "Вправу «5-4-3-2-1» виконано");
                groundingStep = -1;
            }
        }
        updateGroundingView();
        refreshView();
    }

    private void onSos() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Надіслати аварійний сигнал психологічної підтримки в ЦУП?",
                ButtonType.YES, ButtonType.NO);
        alert.setHeaderText("SOS Mental Alert");
        alert.initOwner(stage);
        alert.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> hooks.onSos().run());
    }

    private void awardOnce(String id, int points, String logText) {
        if (JOURNAL.doneToday.add(id)) {
            JOURNAL.points += points;
            hooks.log().accept("🧠 [MindCare] " + logText + " (+" + points + " балів).");
        } else {
            hooks.log().accept("🧠 [MindCare] " + logText + " (бали за сьогодні вже нараховано).");
        }
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

        pointsLabel.setText(String.format(Locale.US, "⭐ Бали життєстійкості: %d  ·  %s", JOURNAL.points, rank(JOURNAL.points)));

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
            Button b = new Button((done ? "✔ " : "") + q.text() + "  (+" + q.points() + ")");
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

    private static String rank(int points) {
        if (points < 20) return "Новачок";
        if (points < 60) return "Стійкий";
        if (points < 120) return "Загартований";
        return "Незламний";
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
        return "-fx-text-fill: " + color + "; -fx-font-size: " + size + "px;" + (bold ? " -fx-font-weight: bold;" : "");
    }

    private static String btnStyle(String bg) {
        return "-fx-background-color: " + bg + "; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;";
    }

    private static VBox card(String title, String subtitle, Node... content) {
        Label t = lbl(title, 14, "#E2E8F0", true);
        Label s = lbl(subtitle, 11, "#64748B", false);
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