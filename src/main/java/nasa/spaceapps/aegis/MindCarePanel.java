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
            new Quest("STRETCH", "🤸 5-min stretch"),
            new Quest("WATER", "💧 Drink water"),
            new Quest("GAZE", "🪟 Look into the distance for 2 min (no screen)"),
            new Quest("TALK", "🗣 Talk with a crewmate for 5 min")
    };

    private static final String[] GROUNDING = {
            "Name 5 things YOU CAN SEE around you.",
            "Name 4 things YOU CAN TOUCH.",
            "Name 3 sounds YOU CAN HEAR.",
            "Name 2 smells YOU CAN SMELL.",
            "Name 1 taste in your mouth. Take a deep breath. Done."
    };

    private static final String[] MOOD_LABEL = {"Very bad", "Bad", "Okay", "Good", "Great"};

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
    private final Button sosBtn = new Button("🆘 SOS: psychological help needed");

    private String lastQuestSig = "";
    private int groundingStep = -1;
    private final Label groundingText = lbl("", 12, "#E2E8F0", false);
    private final Button groundingBtn = new Button();

    /** Короткий текст для звіту (див. buildReport). */
    public static String summaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "- Recovery exercises marked today: %d of %d%n", JOURNAL.doneToday.size(), QUESTS.length));
        if (JOURNAL.moodCount == 0) {
            sb.append("- Mood check-in: no entries\n");
        } else {
            sb.append(String.format(Locale.US, "- Average mood: %.1f of 5 (entries: %d)%n",
                    JOURNAL.moodSum / (double) JOURNAL.moodCount, JOURNAL.moodCount));
            for (String m : JOURNAL.recentMoods) sb.append("  ").append(m).append('\n');
        }
        return sb.toString();
    }

    public MindCarePanel(Hooks hooks) {
        this.hooks = hooks;
        breathing = new BreathingPane(this::onBreathingFinished);

        // Дві колонки на всю ширину екрана: ліворуч стан і дихання, праворуч вправи та SOS
        VBox breathCard = card("4-4-4-4 breathing", "Inhale 4 s, hold 4 s, exhale 4 s, hold 4 s. Four cycles: about a minute.", breathing);
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
        Label title = lbl("VITAL: PSYCHOLOGICAL SUPPORT", 20, "#22D3EE", true);
        title.setGraphic(HoloUi.iconMind());
        title.setGraphicTextGap(10);
        Label sub = lbl("Local support with no internet and no neural networks: transparent rules and ready-made exercises. "
                + "This is not a medical device.", 11, "#A3B1C6", false);
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
        return card("📈 Current stress level",
                "Calculated from pulse, HRV, sleep and water, and smoothed so a one-off spike doesn't alarm you.",
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
        return card("😊 Emotional check-in", "One tap. The entry goes into the log with mission time so mission control on Earth sees the context.",
                row, moodInfo, moodHint);
    }

    private Node questsCard() {
        return card("🌿 Daily recovery exercises",
                "Mark what you really did. This is self-reported: the app cannot verify it. New day: the list resets.",
                questBox);
    }

    private Node groundingCard() {
        groundingText.setWrapText(true);
        groundingBtn.setStyle(btnStyle("#7C3AED"));
        groundingBtn.setOnAction(e -> onGroundingNext());
        updateGroundingView();
        return card("🌿 “5-4-3-2-1” exercise",
                "Brings attention back to the “here and now” during anxiety. Do the steps one by one.",
                groundingText, groundingBtn);
    }

    private Node sosCard() {
        sosBtn.setStyle(btnStyle("#B91C1C"));
        sosBtn.setMaxWidth(Double.MAX_VALUE);
        sosBtn.setOnAction(e -> onSos());
        Label note = lbl("There may be no link with Earth. Then the signal is saved in a local buffer "
                + "and goes to mission control on Earth as soon as the link returns.", 11, "#A3B1C6", false);
        note.setWrapText(true);
        return card("🆘 Emergency signal", "Use it if you are struggling on your own or you are worried about a colleague.",
                sosBtn, note);
    }

    // ============ ДІЇ ============
    private void onBreathingFinished() {
        markOnce("BREATH_SESSION", "4-4-4-4 breathing session completed");
        hooks.onBreathingDone().run();
        refreshView();
    }

    private void onMood(int value) {
        StressEngine.Assessment a = hooks.engine().latest();
        String line = String.format(Locale.US, "%s %d/5 (stress index %d, %s)",
                MOOD_LABEL[value - 1], value, a.index(), a.level().label);
        JOURNAL.moodSum += value;
        JOURNAL.moodCount++;
        JOURNAL.recentMoods.addFirst(line);
        while (JOURNAL.recentMoods.size() > 5) JOURNAL.recentMoods.removeLast();

        hooks.log().accept("🧠 [MindCare] Mood check-in: " + line + ".");

        if (value <= 1) {
            moodHint.setText("You are having a very hard time. Do the breathing, talk to a crewmate or press SOS below.");
        } else if (value == 2 || a.level().ordinal() >= StressEngine.Level.HIGH.ordinal()) {
            moodHint.setText("Looks like a tough day. Try the 4-4-4-4 breathing above.");
        } else {
            moodHint.setText("Thank you, recorded.");
        }
        refreshView();
    }

    private void onQuest(Quest q) {
        if (!JOURNAL.doneToday.add(q.id())) return;
        hooks.log().accept("🧠 [MindCare] Marked manually: " + q.text() + ".");
        refreshView();
    }

    private void onGroundingNext() {
        if (groundingStep < 0 || groundingStep >= GROUNDING.length) {
            groundingStep = 0;
        } else {
            groundingStep++;
            if (groundingStep >= GROUNDING.length) {
                markOnce("GROUNDING", "“5-4-3-2-1” exercise completed");
                groundingStep = -1;
            }
        }
        updateGroundingView();
        refreshView();
    }

    private void onSos() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Send an emergency psychological support signal to mission control on Earth?",
                ButtonType.YES, ButtonType.NO);
        alert.setHeaderText("SOS signal: psychological help needed");
        alert.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> hooks.onSos().run());
    }

    private void markOnce(String id, String logText) {
        boolean first = JOURNAL.doneToday.add(id);
        hooks.log().accept("🧠 [MindCare] " + logText + (first ? "." : " (repeated today)."));
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
                ? "No obvious causes for concern."
                : "What is affecting it: " + String.join("; ", a.reasons()) + ".");
        protocolLabel.setText(StressEngine.protocolFor(a.level()));

        boolean banner = a.sustained();
        suggestBanner.setVisible(banner);
        suggestBanner.setManaged(banner);
        if (banner) {
            suggestBanner.setText("Stress has persisted for several minutes. Take a break: start the breathing below.");
        }

        boolean critical = a.level() == StressEngine.Level.CRITICAL;
        sosBtn.setText(critical ? "🆘 SOS: sending a signal is recommended" : "🆘 SOS: psychological help needed");

        if (JOURNAL.moodCount == 0) {
            moodInfo.setText("No entries yet.");
        } else {
            StringBuilder sb = new StringBuilder(String.format(Locale.US,
                    "Average mood: %.1f of 5. Latest entries:", JOURNAL.moodSum / (double) JOURNAL.moodCount));
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
            groundingText.setText("Press “Start” to go through five short steps.");
            groundingBtn.setText("Start");
        } else {
            groundingText.setText("Step " + (groundingStep + 1) + " of " + GROUNDING.length + ": " + GROUNDING[groundingStep]);
            groundingBtn.setText(groundingStep == GROUNDING.length - 1 ? "Finish" : "Next →");
        }
    }

    // ============ ДИХАННЯ 4-4-4-4 ============
    private static final class BreathingPane extends VBox {
        private static final double PHASE_SEC = 4.0;
        private static final int CYCLES = 4;
        private static final String[] PHASES = {"Inhale", "Hold", "Exhale", "Hold"};
        private static final double R_MIN = 38, R_MAX = 92;

        private final Canvas canvas = new Canvas(240, 220);
        private final Label phaseLabel = lbl("Ready?", 16, "#22D3EE", true);
        private final Label infoLabel = lbl("", 11, "#94A3B8", false);
        private final Button startBtn = new Button("▶ Start breathing");
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
            startBtn.setText("■ Stop");
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
                    infoLabel.setText("Cycle " + (cycle + 1) + " of " + CYCLES);
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
            startBtn.setText("▶ Start breathing");
            phaseLabel.setText("Ready?");
            infoLabel.setText("");
            draw(R_MIN, Color.web("#22D3EE"));
        }

        private void finish() {
            stop();
            phaseLabel.setText("Session complete ✔");
            infoLabel.setText("Notice how your well-being has changed.");
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