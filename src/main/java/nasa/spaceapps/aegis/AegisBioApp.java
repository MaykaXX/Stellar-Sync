package nasa.spaceapps.aegis;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Aegis-Bio: помічник здоров'я екіпажу в далекому космосі.
 * Усі дії космонавта (тренування, вода, сон, їжа...) змінюють показники,
 * а програма показує, ЩО саме змінилось і ЧОМУ.
 * Модель спрощена і призначена для демонстрації, це не медичний прилад.
 */
public class AegisBioApp extends Application {

    // ============ МОДЕЛЬ ДАНИХ ============
    public enum MissionLocation {
        LEO("Навколоземна орбіта (МКС)", 0.05),
        MOON("Місячна станція Gateway", 1.3),
        MARS_TRANSIT("Політ до Марса", 840.0),
        SOLAR_CONJUNCTION("⚠️ Сонячне з'єднання (Blackout 0 bps)", Double.POSITIVE_INFINITY);

        final String label;
        final double latencySec;

        MissionLocation(String label, double latencySec) {
            this.label = label;
            this.latencySec = latencySec;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private Label createBlackoutItem(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");
        return l;
    }

    public enum MetricType {
        HEART_RATE("Пульс", "уд/хв", 60, 100, 40, 180),
        HRV("Стрес-баланс серця (HRV)", "мс", 45, 120, 10, 150),
        BLOOD_PRESSURE("Тиск крові (верхній)", "мм рт.ст.", 105, 135, 80, 200),
        BLOOD_GLUCOSE("Цукор у крові", "мг/дл", 70, 110, 40, 250),
        BODY_TEMP("Температура тіла", "°C", 36.2, 37.4, 35, 40),
        SPO2("Кисень у крові (SpO2)", "%", 96, 100, 80, 100),
        ICP_OPTIC("Тиск на зоровий нерв (SANS)", "мм рт.ст.", 10, 18, 5, 40),
        RADIATION_DOSE("Отримана доза радіації", "мЗв", 0, 100, 0, 1000),
        HYDRATION("Запас води в організмі", "%", 90, 100, 60, 100),
        BONE_DENSITY("Міцність кісток", "%", 97, 100, 80, 100);

        final String title;
        final String unit;
        final double minNominal;
        final double maxNominal;
        final double hardMin;
        final double hardMax;

        MetricType(String title, String unit, double min, double max, double hardMin, double hardMax) {
            this.title = title;
            this.unit = unit;
            this.minNominal = min;
            this.maxNominal = max;
            this.hardMin = hardMin;
            this.hardMax = hardMax;
        }
    }

    public record TelemetryPoint(Instant timestamp, MetricType metric, double value) {}

    /**
     * urgency: CRITICAL / HIGH / MEDIUM / FORECAST / NOMINAL (внутрішні коди; на екрані показуються українською).
     * actionId: id дії (Activity), яку можна виконати однією кнопкою; null, якщо такої дії немає.
     */
    public record ClinicalProtocol(String hazardCategory, String title, String action, String urgency, String actionId) {
        public ClinicalProtocol(String hazardCategory, String title, String action, String urgency) {
            this(hazardCategory, title, action, urgency, null);
        }
    }

    /** Вплив дії на показник. temporary = тимчасово (потім повертається), good = корисний для здоров'я. */
    public record Effect(MetricType metric, double delta, boolean temporary, boolean good, String why) {}

    public record Activity(String id, String button, String logText, String color, List<Effect> effects) {}

    // ============ СТАН ============
    private static final double SPINE_ELONGATION_CM = 4.2;
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("UTC"));

    /** Логічний розмір малюнка космонавта; на екрані він масштабується під доступне місце. */
    private static final double BODY_W = 340, BODY_H = 480;

    private MissionLocation currentLocation = MissionLocation.MARS_TRANSIT;

    /** Постійний стан організму. */
    private final Map<MetricType, Double> baseTelemetry = new EnumMap<>(MetricType.class);
    /** Тимчасові зміни (наприклад, пульс після тренування), які поступово зникають. */
    private final Map<MetricType, Double> tempOffsets = new EnumMap<>(MetricType.class);
    /** Зміни через нестачу води або сну. */
    private final Map<MetricType, Double> derivedOffsets = new EnumMap<>(MetricType.class);
    /** Те, що показується на екрані: база + тимчасове + похідне. */
    private final Map<MetricType, Double> currentTelemetry = new EnumMap<>(MetricType.class);
    /** Знімок показників перед останньою дією (щоб показати стрілки ▲▼). */
    private Map<MetricType, Double> beforeAction = null;
    private final List<String> derivedNotes = new ArrayList<>();
    private final List<ClinicalProtocol> activeProtocols = new ArrayList<>();
    private String lastProtocolSignature = "";

    // Інтерфейс
    private final Map<MetricType, Label> valueLabels = new EnumMap<>(MetricType.class);
    private final Map<MetricType, Label> deltaLabels = new EnumMap<>(MetricType.class);
    private final Map<MetricType, ProgressBar> progressBars = new EnumMap<>(MetricType.class);
    private final VBox protocolContainer = new VBox(8);
    private final Label latencyNoticeLabel = new Label();
    private final Label clockLabel = new Label();
    private final Label bigClockLabel = new Label();
    private final Label simClockLabel = new Label();
    private final Label healthScoreLabel = new Label();
    private final ProgressBar healthBar = new ProgressBar(1);
    private final Label derivedLabel = new Label();
    private final VBox effectsBox = new VBox(6);
    private final VBox logBox = new VBox(3);
    private final List<String> logLines = new ArrayList<>();
    private final Button autoPlanBtn = new Button();
    private Canvas bodyHudCanvas;

    // Анімація
    private double animationPulse = 0;
    private double heartPhase = 0;
    private long lastNanos = 0;
    private int simSpeed = 1;

    // Історія та прогноз
    /** Припущення моделі: один крок симуляції = 1 хвилина часу екіпажу. */
    private static final double MINUTES_PER_STEP = 1.0;
    /** За скільки годин до виходу за норму показувати раннє попередження. */
    private static final double FORECAST_WARN_HOURS = 12.0;
    private double simMinutes = 0;
    /** Те, що бачить користувач (з піками після тренувань): для графіка. */
    private final TrendEngine<MetricType> chartHistory = new TrendEngine<>(MetricType.class, 1440, 60, 6);
    /** Стійкий стан без тимчасових піків: для прогнозу. */
    private final TrendEngine<MetricType> steadyHistory = new TrendEngine<>(MetricType.class, 1440, 60, 6);
    private MetricType chartMetric = MetricType.ICP_OPTIC;
    private LineChart<Number, Number> trendChart;
    private final XYChart.Series<Number, Number> valueSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> lowSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> highSeries = new XYChart.Series<>();
    private final Label forecastLabel = new Label();

    // Щоденні лічильники
    private int countermeasureMinutes = 75; // ціль 150 хв
    private double hydrationLiters = 1.6;   // ціль 2.8 л
    private double sleepHours = 6.0;        // ціль 8 год
    private final ProgressBar workoutBar = new ProgressBar();
    private final ProgressBar hydrationBar = new ProgressBar();
    private final ProgressBar sleepBar = new ProgressBar();
    private final Label workoutLabel = new Label();
    private final Label hydrationLabel = new Label();
    private final Label sleepLabel = new Label();

    // ============ ЗАПУСК ============
    @Override
    public void start(Stage primaryStage) {
        this.primaryStageRef = primaryStage;
        initDefaultTelemetry();

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #080C14; -fx-font-family: 'Segoe UI', system-ui, sans-serif;");

        root.setTop(buildTopHeader(primaryStage));
        root.setCenter(buildMainDashboard());
        root.setBottom(buildBottomStatusBar());

        // Анімація тіла: серце б'ється з реальною частотою пульсу
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (lastNanos == 0) lastNanos = now;
                double dt = (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;
                animationPulse += dt * 3;
                double hr = currentTelemetry.get(MetricType.HEART_RATE);
                heartPhase += dt * (hr / 60.0) * 2 * Math.PI;
                drawHolographicBody(bodyHudCanvas.getGraphicsContext2D());
            }
        };
        timer.start();

        // Годинник місії
        Timeline clockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateClock()));
        clockTimeline.setCycleCount(Timeline.INDEFINITE);
        clockTimeline.play();
        updateClock();

        // Життя організму: раз на секунду (або швидше, якщо вибрано прискорення)
        Timeline physiologyTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        physiologyTimeline.setCycleCount(Timeline.INDEFINITE);
        physiologyTimeline.play();

        Scene scene = new Scene(root, 1366, 820);
        primaryStage.setTitle("Aegis-Bio: помічник здоров'я екіпажу в далекому космосі");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void initDefaultTelemetry() {
        baseTelemetry.put(MetricType.HEART_RATE, 78.0);
        baseTelemetry.put(MetricType.HRV, 62.0);
        baseTelemetry.put(MetricType.BLOOD_PRESSURE, 122.0);
        baseTelemetry.put(MetricType.BLOOD_GLUCOSE, 94.0);
        baseTelemetry.put(MetricType.BODY_TEMP, 36.7);
        baseTelemetry.put(MetricType.SPO2, 98.4);
        baseTelemetry.put(MetricType.ICP_OPTIC, 14.5);
        baseTelemetry.put(MetricType.RADIATION_DOSE, 48.2);
        baseTelemetry.put(MetricType.HYDRATION, 95.0);
        baseTelemetry.put(MetricType.BONE_DENSITY, 98.0);
        tempOffsets.clear();
        beforeAction = null;
        recompute();
    }

    private void resetAll() {
        countermeasureMinutes = 75;
        hydrationLiters = 1.6;
        sleepHours = 6.0;
        pillsToday.clear();
        lastPillTime.clear();
        currentDay = 0;
        initDefaultTelemetry();
        chartHistory.clearAll();
        steadyHistory.clearAll();
        simMinutes = 0;
        clockSec = 0;
        if (blackoutActive) abortBlackoutSilently();
        showEffectsPlaceholder();
        updateHabitViews();
        updateAllTelemetryViews();
        updateTrendChart();
    }

    // ============ ФІЗІОЛОГІЯ ============
    private double clamp(MetricType type, double v) {
        return Math.max(type.hardMin, Math.min(type.hardMax, v));
    }

    private boolean inRange(MetricType type, double v) {
        return v >= type.minNominal && v <= type.maxNominal;
    }

    /** Складає показники для екрана: база + тимчасові зміни + наслідки нестачі води/сну. */
    private void recompute() {
        derivedOffsets.clear();
        derivedNotes.clear();

        double hyd = baseTelemetry.get(MetricType.HYDRATION);
        if (hyd < 90.0) {
            double d = 90.0 - hyd;
            derivedOffsets.merge(MetricType.HEART_RATE, d * 0.9, Double::sum);
            derivedOffsets.merge(MetricType.HRV, -d * 0.7, Double::sum);
            derivedOffsets.merge(MetricType.BLOOD_PRESSURE, d * 0.4, Double::sum);
            derivedNotes.add(String.format(Locale.US,
                    "Вам не вистачає води. Через це пульс вищий на %.0f, а стрес-баланс серця нижчий на %.0f. Випийте води.",
                    d * 0.9, d * 0.7));
        }

        if (sleepHours < 6.0) {
            double d = 6.0 - sleepHours;
            derivedOffsets.merge(MetricType.HRV, -d * 1.5, Double::sum);
            derivedOffsets.merge(MetricType.HEART_RATE, d * 1.0, Double::sum);
            derivedOffsets.merge(MetricType.BLOOD_GLUCOSE, d * 2.0, Double::sum);
            derivedNotes.add(String.format(Locale.US,
                    "Ви мало спали (%.1f год). Через це серце втомлене, а цукор у крові вищий. Відпочиньте.", sleepHours));
        }

        for (MetricType type : MetricType.values()) {
            double v = baseTelemetry.getOrDefault(type, 0.0)
                    + tempOffsets.getOrDefault(type, 0.0)
                    + derivedOffsets.getOrDefault(type, 0.0);
            currentTelemetry.put(type, clamp(type, v));
        }

        derivedLabel.setText(String.join("\n\n", derivedNotes));
        derivedLabel.setVisible(!derivedNotes.isEmpty());
    }

    /** Один крок симуляції (1 секунда часу організму). */
    private void physiologyStep() {
        Iterator<Map.Entry<MetricType, Double>> it = tempOffsets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MetricType, Double> en = it.next();
            double v = en.getValue() * 0.995;
            if (Math.abs(v) < 0.02) it.remove();
            else en.setValue(v);
        }
        // У невагомості без тренувань стан повільно погіршується
        baseTelemetry.put(MetricType.HYDRATION, clamp(MetricType.HYDRATION, baseTelemetry.get(MetricType.HYDRATION) - 0.01 * DRIFT_PER_SEC));
        baseTelemetry.put(MetricType.ICP_OPTIC, clamp(MetricType.ICP_OPTIC, baseTelemetry.get(MetricType.ICP_OPTIC) + 0.002 * DRIFT_PER_SEC));
        baseTelemetry.put(MetricType.BONE_DENSITY, clamp(MetricType.BONE_DENSITY, baseTelemetry.get(MetricType.BONE_DENSITY) - 0.0008 * DRIFT_PER_SEC));
    }

    // ============ РЕЖИМ БЛЕКАУТУ ============
    /** Стандартна тривалість радіоблекауту: 14 діб 6 год 42 хв. */
    private static final long BLACKOUT_DEFAULT_SEC = (14L * 24 * 60 + 6 * 60 + 42) * 60;
    private static final int EMERGENCY_CHARGES_MAX = 3;
    /** Як часто (у секундах часу місії) AI-автопілот перевіряє показники. */
    private static final long AUTOPILOT_SCAN_SEC = 15;
    /** Приблизний розмір одного крипто-пакета телеметрії, КБ (один пакет на хвилину місії). */
    private static final int PACKET_KB = 2;

    /**
     * Правило автопілота: якщо показник метрики наближається до межі норми
     * (на margin), AI сам виконує дію actionId, поки діють денні ліміти.
     */
    private record AutoRule(String actionId, MetricType metric, boolean whenHigh,
                            double margin, String reason, long cooldownSec) {}

    private static final List<AutoRule> AUTO_RULES = List.of(
            new AutoRule("WATER", MetricType.HYDRATION, false, 1.5,
                    "запас води наближається до нижньої межі", 30 * 60),
            new AutoRule("LBNP", MetricType.ICP_OPTIC, true, 1.5,
                    "тиск на зоровий нерв наближається до межі", 120 * 60),
            new AutoRule("BREATH", MetricType.HEART_RATE, true, 8,
                    "пульс підвищений", 30 * 60),
            new AutoRule("BREATH", MetricType.HRV, false, 8,
                    "стрес-баланс серця знижений", 30 * 60),
            new AutoRule("BREATH", MetricType.BLOOD_PRESSURE, true, 6,
                    "тиск крові наближається до межі", 30 * 60)
    );

    private Stage primaryStageRef;
    private Stage blackoutStage;
    private ComboBox<MissionLocation> locationBox;
    private boolean syncingLocation = false;
    private final Label blackoutStatusLabel = new Label();

    private boolean blackoutActive = false;
    /** Початок і кінець блекауту у секундах годинника місії (clockSec): таймер залежить від швидкості часу. */
    private long blackoutStartSec = 0;
    private long blackoutEndSec = 0;
    private MissionLocation locationBeforeBlackout = MissionLocation.MARS_TRANSIT;
    private int blackoutAiActions = 0;
    private double blackoutRadReduced = 0;
    private int emergencyCharges = EMERGENCY_CHARGES_MAX;
    private long lastAutopilotScan = -1;
    private long lastBlackoutReportHour = 0;
    private final Map<String, Long> lastAutoActionClock = new HashMap<>();
    private final Deque<String> autopilotRecent = new ArrayDeque<>();

    private static String formatDuration(long sec) {
        sec = Math.max(0, sec);
        return String.format(Locale.US, "%d діб  %02d год  %02d хв  %02d с",
                sec / 86400, (sec / 3600) % 24, (sec / 60) % 60, sec % 60);
    }

    private long blackoutRemainingSec() {
        return blackoutActive ? Math.max(0, blackoutEndSec - clockSec) : 0;
    }

    private int blackoutPackets() {
        long elapsed = Math.min(clockSec, blackoutEndSec) - blackoutStartSec;
        return (int) Math.max(0, elapsed / 60);
    }

    /** Змінює поточну локацію і синхронізує випадаючий список (без повторного запуску його обробника). */
    private void setLocationSilently(MissionLocation loc) {
        currentLocation = loc;
        if (locationBox != null && locationBox.getValue() != loc) {
            syncingLocation = true;
            try {
                locationBox.setValue(loc);
            } finally {
                syncingLocation = false;
            }
        }
        updateLatencyDisplay();
    }

    private void startBlackout(long durationSec) {
        if (blackoutActive) return;
        locationBeforeBlackout = currentLocation == MissionLocation.SOLAR_CONJUNCTION
                ? MissionLocation.MARS_TRANSIT : currentLocation;
        long duration = Math.max(60, durationSec);

        blackoutActive = true;
        blackoutStartSec = clockSec;
        blackoutEndSec = clockSec + duration;
        blackoutAiActions = 0;
        blackoutRadReduced = 0;
        emergencyCharges = EMERGENCY_CHARGES_MAX;
        lastAutopilotScan = -1;
        lastBlackoutReportHour = 0;
        lastAutoActionClock.clear();
        autopilotRecent.clear();

        setLocationSilently(MissionLocation.SOLAR_CONJUNCTION);
        addLog("📡❌ [Блекаут] Зв'язок із Землею втрачено на " + formatDuration(duration).replaceAll("\\s+", " ")
                + ". Керування перейшло до AI-автопілота.");
        updateBlackoutStatus();
    }

    /** completed = true: таймер дійшов до нуля; false: екіпаж перервав режим достроково. */
    private void endBlackout(boolean completed) {
        if (!blackoutActive) return;
        long elapsed = Math.min(clockSec, blackoutEndSec) - blackoutStartSec;
        int packets = blackoutPackets();
        int actions = blackoutAiActions;
        double rad = blackoutRadReduced;

        blackoutActive = false;
        setLocationSilently(locationBeforeBlackout);

        addLog(completed
                ? "📡✅ [Блекаут] Зв'язок відновлено. У Х'юстон передано пакетів телеметрії: " + packets + "."
                : "⚠️ [Блекаут] Режим перервано достроково. У Х'юстон передано пакетів телеметрії: " + packets + ".");
        updateBlackoutStatus();

        String summary = String.format(Locale.US,
                "Тривалість блекауту: %s%nПакетів телеметрії передано: %d (~%d КБ)%nДій AI-автопілота: %d%n"
                        + "Дозу радіації знижено щитами: %.2f мЗв",
                formatDuration(elapsed).replaceAll("\\s+", " "), packets, packets * PACKET_KB, actions, rad);
        Alert alert = new Alert(Alert.AlertType.INFORMATION, summary, ButtonType.OK);
        Stage owner = (blackoutStage != null && blackoutStage.isShowing()) ? blackoutStage : primaryStageRef;
        if (owner != null) alert.initOwner(owner);
        alert.setHeaderText(completed ? "Зв'язок із Х'юстоном відновлено" : "Блекаут перервано");
        alert.show();
    }

    /** Тихе скасування (наприклад, при повному скиданні симуляції). */
    private void abortBlackoutSilently() {
        blackoutActive = false;
        setLocationSilently(locationBeforeBlackout);
        updateBlackoutStatus();
    }

    private void updateBlackoutStatus() {
        boolean on = blackoutActive;
        blackoutStatusLabel.setVisible(on);
        blackoutStatusLabel.setManaged(on);
        if (on) {
            blackoutStatusLabel.setText("📡❌ БЛЕКАУТ: до відновлення зв'язку "
                    + formatDuration(blackoutRemainingSec()).replaceAll("\\s+", " ")
                    + "  |  Дій AI: " + blackoutAiActions);
        }
    }

    /** Один крок блекауту (викликається з tick()): таймер, автопілот, періодичний звіт. */
    private void blackoutStep() {
        if (clockSec >= blackoutEndSec) {
            endBlackout(true);
            return;
        }
        long scan = clockSec / AUTOPILOT_SCAN_SEC;
        if (scan != lastAutopilotScan) {
            lastAutopilotScan = scan;
            runBlackoutAutonomy();
        }
        long hour = (clockSec - blackoutStartSec) / 3600;
        if (hour > lastBlackoutReportHour) {
            lastBlackoutReportHour = hour;
            if (hour % 6 == 0) {
                addLog("🛰️ [Блекаут] Минуло " + hour + " год. У локальному буфері пакетів: " + blackoutPackets()
                        + ", дій AI: " + blackoutAiActions + ".");
            }
        }
        updateBlackoutStatus();
    }

    /**
     * Автономний контур: водяні щити поступово знижують дозу радіації,
     * а автопілот сам виконує дії (вода, дихання, LBNP), коли показники наближаються до межі норми.
     * Він користується тими самими денними лімітами й перервами, що й екіпаж.
     */
    private void runBlackoutAutonomy() {
        double rad = baseTelemetry.getOrDefault(MetricType.RADIATION_DOSE, 0.0);
        if (rad > 10.0) {
            double d = Math.min(0.02, rad - 10.0);
            baseTelemetry.put(MetricType.RADIATION_DOSE, rad - d);
            blackoutRadReduced += d;
        }

        for (AutoRule r : AUTO_RULES) {
            MetricType m = r.metric();
            double steady = currentTelemetry.getOrDefault(m, 0.0) - tempOffsets.getOrDefault(m, 0.0);
            boolean needed = r.whenHigh() ? steady > m.maxNominal - r.margin() : steady < m.minNominal + r.margin();
            if (!needed) continue;

            Long last = lastAutoActionClock.get(r.actionId());
            if (last != null && clockSec - last < r.cooldownSec()) continue;

            Activity a = findActivity(r.actionId());
            if (a == null || blockedReason(a) != null) continue;

            lastAutoActionClock.put(r.actionId(), clockSec);
            applyActivity(a, true);
            blackoutAiActions++;

            String msg = a.logText() + " (" + r.reason() + ")";
            addLog("🤖 [AI-автопілот] " + msg + ".");
            autopilotRecent.addFirst("[" + LOG_TIME.format(missionNow()) + "] " + msg);
            while (autopilotRecent.size() > 5) autopilotRecent.removeLast();
            break; // за одне сканування — одна дія
        }
    }

    private void tick() {
        for (int i = 0; i < simSpeed; i++) physiologyStep();

        clockSec += simSpeed;
        simMinutes = clockSec / 60.0;
        long day = clockSec / 86400;
        if (day > currentDay) {
            currentDay = day;
            startNewDay();
        }

        // Режим блекауту: таймер іде від годинника місії, AI-автопілот сам виконує дії
        if (blackoutActive) {
            blackoutStep();
        }

        recompute();
        recordHistory();
        updateAllTelemetryViews();
        updateTrendChart();
        updateClock();
    }

    /** Записує поточні показники в історію (для графіка і прогнозу). */
    private void recordHistory() {
        for (MetricType t : MetricType.values()) {
            double cur = currentTelemetry.get(t);
            chartHistory.record(t, simMinutes, cur);
            // Для прогнозу прибираємо тимчасові піки (наприклад, пульс після тренування)
            steadyHistory.record(t, simMinutes, cur - tempOffsets.getOrDefault(t, 0.0));
        }
    }

    // ============ ДІЇ ЕКІПАЖУ ============
    private List<Activity> buildActivities() {
        List<Activity> list = new ArrayList<>();

        list.add(new Activity("STRENGTH", "Силові тренування (ARED): +30 хв", "Силові тренування, 30 хв", "#059669", List.of(
                new Effect(MetricType.BONE_DENSITY, 0.4, false, true, "Навантаження на кістки сповільнює втрату кальцію в невагомості."),
                new Effect(MetricType.HRV, 1.5, false, true, "Регулярні тренування роблять серце стійкішим до стресу."),
                new Effect(MetricType.HEART_RATE, 15, true, false, "Під час навантаження серце б'ється швидше. Через кілька хвилин пульс повернеться до норми."),
                new Effect(MetricType.BLOOD_PRESSURE, 8, true, false, "Під час навантаження тиск тимчасово зростає."),
                new Effect(MetricType.BODY_TEMP, 0.4, true, false, "Тіло нагрілось від роботи."),
                new Effect(MetricType.HYDRATION, -3, false, false, "Ви втратили воду з потом. Випийте води.")
        )));

        list.add(new Activity("CARDIO", "Кардіо (доріжка T2 / велотренажер CEVIS): +30 хв", "Кардіо-тренування, 30 хв", "#16A34A", List.of(
                new Effect(MetricType.HRV, 2.5, false, true, "Кардіо тренує серце і покращує стрес-баланс."),
                new Effect(MetricType.BLOOD_PRESSURE, -3, false, true, "Регулярне кардіо поступово знижує тиск крові."),
                new Effect(MetricType.BONE_DENSITY, 0.1, false, true, "Біг із фіксацією дає невелике навантаження на кістки."),
                new Effect(MetricType.HEART_RATE, 28, true, false, "Кардіо сильно прискорює серце. Це нормально, пульс повернеться."),
                new Effect(MetricType.BLOOD_GLUCOSE, -10, true, true, "М'язи витрачають цукор із крові."),
                new Effect(MetricType.BODY_TEMP, 0.5, true, false, "Тіло нагрілось від роботи."),
                new Effect(MetricType.HYDRATION, -4, false, false, "Ви втратили воду з потом. Випийте води.")
        )));

        list.add(new Activity("WATER", "Випити води з електролітами: +400 мл", "Випито 400 мл води з електролітами", "#0891B2", List.of(
                new Effect(MetricType.HYDRATION, 4, false, true, "Вода поповнює запас рідини в організмі."),
                new Effect(MetricType.HEART_RATE, -2, true, true, "Коли води достатньо, серцю легше працювати.")
        )));

        list.add(new Activity("SLEEP", "Поспати: +2 год", "Сон, 2 години", "#4F46E5", List.of(
                new Effect(MetricType.HRV, 2, false, true, "Сон відновлює нервову систему й серце."),
                new Effect(MetricType.HEART_RATE, -5, true, true, "Під час сну пульс нижчий, серце відпочиває.")
        )));

        list.add(new Activity("MEAL", "Поїсти (прийом їжі)", "Прийом їжі", "#CA8A04", List.of(
                new Effect(MetricType.BLOOD_GLUCOSE, 30, true, false, "Після їжі цукор у крові зростає, через 1-2 години повернеться до норми."),
                new Effect(MetricType.HEART_RATE, 3, true, false, "Травлення трохи прискорює пульс.")
        )));

        list.add(new Activity("BREATH", "Дихальна вправа: 10 хв", "Дихальна вправа, 10 хв", "#7C3AED", List.of(
                new Effect(MetricType.HEART_RATE, -6, true, true, "Повільне дихання заспокоює серце."),
                new Effect(MetricType.BLOOD_PRESSURE, -6, true, true, "Повільне дихання знижує тиск крові."),
                new Effect(MetricType.HRV, 4, true, true, "Повільне дихання одразу покращує стрес-баланс серця.")
        )));

        list.add(new Activity("LBNP", "Сеанс LBNP (камера для ніг): 45 хв", "Сеанс LBNP, 45 хв", "#2563EB", List.of(
                new Effect(MetricType.ICP_OPTIC, -3.5, false, true, "Камера відтягує рідину від голови, тиск на зоровий нерв зменшується."),
                new Effect(MetricType.BLOOD_PRESSURE, -5, true, true, "Рідина перерозподіляється, тиск крові тимчасово знижується."),
                new Effect(MetricType.HEART_RATE, 6, true, false, "Серце працює більше, щоб компенсувати зсув рідини.")
        )));

        return list;
    }

    private Activity findActivity(String id) {
        if (id == null) return null;
        for (Activity a : buildActivities()) if (a.id().equals(id)) return a;
        return null;
    }

    /** Унікальні дії, які радять активні підказки (без прогнозів). */
    private List<String> recommendedActionIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ClinicalProtocol p : activeProtocols) {
            if (p.actionId() == null || "FORECAST".equals(p.urgency())) continue;
            Activity a = findActivity(p.actionId());
            if (a != null && blockedReason(a) == null) ids.add(p.actionId());
        }
        return new ArrayList<>(ids);
    }

    private void updateAutoPlanButton() {
        List<String> ids = recommendedActionIds();
        autoPlanBtn.setVisible(!ids.isEmpty());
        autoPlanBtn.setText("✅ Виконати все рекомендоване (" + ids.size() + ")");
    }

    private void applyActivity(Activity a) {
        applyActivity(a, false);
    }

    /** automatic = true: дію виконує AI-автопілот блекауту (без вікна пояснень, з позначкою в журналі). */
    private void applyActivity(Activity a, boolean automatic) {
        String blocked = blockedReason(a);
        if (blocked != null) {
            if (!automatic) showNotice("Зараз не можна: " + a.logText(), blocked);
            return;
        }
        beforeAction = new EnumMap<>(currentTelemetry);

        lastActivityClock.put(a.id(), clockSec);

        for (Effect e : a.effects()) {
            if (e.temporary()) {
                tempOffsets.merge(e.metric(), e.delta(), Double::sum);
            } else {
                baseTelemetry.put(e.metric(), clamp(e.metric(), baseTelemetry.get(e.metric()) + e.delta()));
            }
        }

        // Після різкої зміни стану стара історія вже не показує тренд
        if (a.effects().stream().anyMatch(e -> !e.temporary())) steadyHistory.clearAll();

        switch (a.id()) {
            case "STRENGTH", "CARDIO" -> countermeasureMinutes = Math.min(150, countermeasureMinutes + 30);
            case "WATER" -> hydrationLiters = Math.min(2.8, hydrationLiters + 0.4);
            case "SLEEP" -> sleepHours = Math.min(8.0, sleepHours + 2.0);
            default -> { }
        }

        recompute();
        updateHabitViews();
        updateAllTelemetryViews();
        if (!automatic) {
            showEffects(a);
            addLog(a.logText());
        }
    }

    // ============ ЛІМІТИ, ДОБА І ТАБЛЕТКИ ============
    private static final double WATER_MAX_L = 2.8;
    private static final int WORKOUT_MAX_MIN = 150;
    private static final double SLEEP_MAX_H = 8.0;
    private static final double MINUTES_PER_DAY = 1440.0;
    private long currentDay = 0;
    /** Годинник доби та перерв між таблетками, у секундах. Іде 1 с за секунду (x1), швидше при прискоренні. */
    private long clockSec = 0;
    private final Instant missionStart = Instant.now();
    /** Повільні зміни (вода, око, кістки) задані «за хвилину», а крок = 1 секунда. */
    private static final double DRIFT_PER_SEC = 1.0 / 60.0;
    private static final long BREATH_COOLDOWN_SEC = 30 * 60;
    private final Map<String, Long> lastActivityClock = new HashMap<>();

    /** Поточний час місії (UTC): старт + секунди годинника, тож швидкість часу впливає на нього. */
    private Instant missionNow() {
        return missionStart.plusSeconds(clockSec);
    }

    /** Таблетка. perTablet = вплив ОДНІЄЇ таблетки (множиться на кількість прийнятих). */
    public record Pill(String id, String name, String color, String purpose,
                       int maxPerDose, int maxPerDay, double intervalMin, List<Effect> perTablet) {}

    /** Результат розрахунку дози: tablets = 0 означає «зараз не можна», note пояснює чому. */
    private record PillDose(int tablets, String note) {}

    private final Map<String, Button> activityButtons = new HashMap<>();
    private final Map<String, Integer> pillsToday = new HashMap<>();
    private final Map<String, Double> lastPillTime = new HashMap<>();
    private final Map<String, Label> pillLabels = new HashMap<>();
    private final Map<String, Button> pillButtons = new HashMap<>();
    private final Map<String, Button> cardPillButtons = new HashMap<>();

    private List<Pill> buildPills() {
        return List.of(
                new Pill("ANTIOX", "Антиоксидантний комплекс (захист від радіації)", "#B45309",
                        "Допомагає клітинам витримувати радіацію. Отриману дозу НЕ зменшує, тому укриття важливіше.",
                        3, 6, 240, List.of(
                        new Effect(MetricType.HRV, 1.0, false, true,
                                "Антиоксиданти зменшують пошкодження клітин і втому серця."))),
                new Pill("VITD", "Вітамін D3 (для кісток)", "#CA8A04",
                        "Допомагає кісткам засвоювати кальцій. Не замінює силові тренування.",
                        2, 2, 720, List.of(
                        new Effect(MetricType.BONE_DENSITY, 0.15, false, true,
                                "Вітамін D допомагає кісткам засвоювати кальцій."))),
                new Pill("MAGNESIUM", "Магній (проти стресу й втоми)", "#0F766E",
                        "Допомагає нервам і серцю відновлюватись.",
                        2, 4, 240, List.of(
                        new Effect(MetricType.HRV, 2.0, false, true, "Магній підтримує нерви та серце, стрес-баланс покращується."),
                        new Effect(MetricType.HEART_RATE, -1, true, true, "Магній трохи заспокоює серце."))),
                new Pill("MELATONIN", "Мелатонін (допомога зі сном)", "#4338CA",
                        "Допомагає заснути. Не замінює сам сон.",
                        1, 1, 1440, List.of(
                        new Effect(MetricType.HEART_RATE, -3, true, true, "Мелатонін заспокоює організм перед сном."),
                        new Effect(MetricType.HRV, 1.0, false, true, "Спокійний сон краще відновлює серце."))));
    }

    private Pill findPill(String id) {
        for (Pill p : buildPills()) if (p.id().equals(id)) return p;
        return null;
    }

    /** Яка таблетка підходить до підказки (за категорією). */
    private Pill pillForProtocol(ClinicalProtocol p) {
        String id = switch (p.hazardCategory()) {
            case "Радіація" -> "ANTIOX";
            case "Кістки" -> "VITD";
            case "Стрес і втома" -> "MAGNESIUM";
            case "Сон" -> "MELATONIN";
            default -> null;
        };
        return id == null ? null : findPill(id);
    }

    /** Ступінь небезпеки: 0 = норма, 1 = легке відхилення, 2 = помітне, 3 = сильне. */
    private int pillSeverity(String id) {
        return switch (id) {
            case "ANTIOX" -> {
                double r = currentTelemetry.get(MetricType.RADIATION_DOSE);
                yield r >= 250 ? 3 : r >= 150 ? 2 : r > 100 ? 1 : 0;
            }
            case "VITD" -> {
                double b = currentTelemetry.get(MetricType.BONE_DENSITY);
                yield b < 92 ? 3 : b < 95 ? 2 : b < 97 ? 1 : 0;
            }
            case "MAGNESIUM" -> {
                double h = currentTelemetry.get(MetricType.HRV);
                yield h < 20 ? 3 : h < 30 ? 2 : h < 40 ? 1 : 0;
            }
            case "MELATONIN" -> sleepHours < 4.0 ? 2 : sleepHours < 6.0 ? 1 : 0;
            default -> 0;
        };
    }

    /**
     * Розрахунок дози. Кількість таблеток = ступінь небезпеки, але ніколи не більше:
     * 1) максимуму за один раз, 2) того, що лишилось від денного ліміту,
     * і лише якщо минула мінімальна перерва після попереднього прийому.
     */
    private PillDose pillDose(Pill p) {
        int taken = pillsToday.getOrDefault(p.id(), 0);
        int left = p.maxPerDay() - taken;
        if (left <= 0) {
            return new PillDose(0, "Денний ліміт вичерпано (" + taken + " з " + p.maxPerDay()
                    + " таб.). Більше не можна, інакше є ризик передозування.");
        }
        Double last = lastPillTime.get(p.id());
        double elapsedMin = last == null ? Double.MAX_VALUE : (clockSec - last) / 60.0;
        if (last != null && elapsedMin < p.intervalMin()) {
            double wait = p.intervalMin() - elapsedMin;
            return new PillDose(0, "Наступну дозу можна прийняти через " + formatMinutes(wait) + ".");
        }
        int severity = pillSeverity(p.id());
        if (severity == 0) return new PillDose(0, "Зараз не потрібно: показник у нормі.");
        int n = Math.min(Math.min(severity, p.maxPerDose()), left);
        return new PillDose(n, "");
    }

    private String formatMinutes(double minutes) {
        if (minutes < 60) return "~" + Math.max(1, Math.round(minutes)) + " хв";
        return "~" + Math.round(minutes / 60.0) + " год";
    }

    private String tabletWord(int n) {
        if (n == 1) return "таблетку";
        if (n >= 2 && n <= 4) return "таблетки";
        return "таблеток";
    }

    private void applyPill(Pill p) {
        PillDose d = pillDose(p);
        if (d.tablets() <= 0) {
            showNotice("Таблетки не прийнято: " + p.name(), d.note());
            return;
        }
        int n = d.tablets();
        beforeAction = new EnumMap<>(currentTelemetry);

        List<Effect> scaled = new ArrayList<>();
        for (Effect e : p.perTablet()) {
            Effect s = new Effect(e.metric(), e.delta() * n, e.temporary(), e.good(), e.why());
            scaled.add(s);
            if (s.temporary()) {
                tempOffsets.merge(s.metric(), s.delta(), Double::sum);
            } else {
                baseTelemetry.put(s.metric(), clamp(s.metric(), baseTelemetry.get(s.metric()) + s.delta()));
            }
        }
        if (scaled.stream().anyMatch(e -> !e.temporary())) steadyHistory.clearAll();

        pillsToday.merge(p.id(), n, Integer::sum);
        lastPillTime.put(p.id(), (double) clockSec);

        recompute();
        updateHabitViews();
        updateAllTelemetryViews();

        String logText = "Прийнято: " + p.name() + ", " + n + " шт.";
        showEffects(new Activity("PILL_" + p.id(), p.name(), logText, p.color(), scaled));
        addLog(logText);
    }

    private void refreshCardPillButton(Pill pill, Button btn) {
        PillDose d = pillDose(pill);
        btn.setDisable(d.tablets() <= 0);
        btn.setText(d.tablets() > 0
                ? "💊 Прийняти " + d.tablets() + " " + tabletWord(d.tablets()) + ": " + pill.name()
                : "💊 " + pill.name() + ": " + d.note());
    }

    private void updatePillViews() {
        for (Pill p : buildPills()) {
            Label status = pillLabels.get(p.id());
            Button btn = pillButtons.get(p.id());
            if (status == null || btn == null) continue;

            PillDose d = pillDose(p);
            int taken = pillsToday.getOrDefault(p.id(), 0);
            String text = String.format(Locale.US, "Сьогодні прийнято: %d з %d таб. (максимум)", taken, p.maxPerDay());
            if (d.tablets() <= 0) text += "\n" + d.note();
            status.setText(text);

            btn.setDisable(d.tablets() <= 0);
            btn.setText(d.tablets() > 0
                    ? "💊 Прийняти " + d.tablets() + " " + tabletWord(d.tablets()) + " (розрахована доза)"
                    : "💊 Прийняти (зараз не можна)");
            Button cb = cardPillButtons.get(p.id());
            if (cb != null) refreshCardPillButton(p, cb);
        }
    }

    /** Чому дію зараз не можна виконати (денна норма вже виконана). null = можна. */
    private String blockedReason(Activity a) {
        return switch (a.id()) {
            case "WATER" -> hydrationLiters >= WATER_MAX_L - 1e-6
                    ? "Ви вже випили денну норму води (2.8 л). Більше пити не можна: надлишок води шкідливий."
                    : null;
            case "STRENGTH", "CARDIO" -> countermeasureMinutes >= WORKOUT_MAX_MIN
                    ? "Денну норму тренувань виконано (150 хв). Організму треба відновитись."
                    : null;
            case "SLEEP" -> sleepHours >= SLEEP_MAX_H - 1e-6
                    ? "Ви вже виспалися (8 год)."
                    : null;
            case "BREATH" -> {
                Long last = lastActivityClock.get("BREATH");
                yield (last != null && clockSec - last < BREATH_COOLDOWN_SEC)
                        ? "Дихальну вправу щойно виконано. Між вправами потрібна перерва 30 хв."
                        : null;
            }
            default -> null;
        };
    }

    private void updateActivityButtons() {
        for (Activity a : buildActivities()) {
            Button b = activityButtons.get(a.id());
            if (b == null) continue;
            boolean blocked = blockedReason(a) != null;
            b.setDisable(blocked);
            b.setText(blocked ? "⛔ " + a.button() + " (не зараз)" : a.button());
        }
    }

    /** Нова доба: денні лічильники скидаються. */
    private void startNewDay() {
        countermeasureMinutes = 0;
        hydrationLiters = 0.0;
        pillsToday.clear();
        updateHabitViews();
        addLog("🌅 Нова доба: лічильники води, тренувань і таблеток скинуто.");
    }

    private void showNotice(String headText, String body) {
        effectsBox.getChildren().clear();
        Label head = new Label(headText);
        head.setWrapText(true);
        head.setStyle("-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: bold;");
        Label text = new Label(body);
        text.setWrapText(true);
        text.setStyle("-fx-text-fill: #FBBF24; -fx-font-size: 12px;");
        effectsBox.getChildren().addAll(head, text);
    }

    private String preventiveAdviceNow(MetricType type) {
        Activity a = findActivity(preventiveAction(type));
        String reason = a == null ? null : blockedReason(a);
        return reason != null ? reason : preventiveAdvice(type);
    }

    private String preventiveActionNow(MetricType type) {
        String id = preventiveAction(type);
        Activity a = findActivity(id);
        return (a != null && blockedReason(a) != null) ? null : id;
    }

    private void showEffectsPlaceholder() {
        effectsBox.getChildren().clear();
        Label hint = new Label("Натисніть будь-яку дію вище. Тут з'явиться, як вона вплинула на ваше здоров'я і чому.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 12px;");
        effectsBox.getChildren().add(hint);
    }

    private void showEffects(Activity a) {
        effectsBox.getChildren().clear();

        Label head = new Label("Ви зробили: " + a.logText());
        head.setWrapText(true);
        head.setStyle("-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: bold;");
        effectsBox.getChildren().add(head);

        for (Effect e : a.effects()) {
            String arrow = e.delta() > 0 ? "▲" : "▼";
            String when = e.temporary() ? "тимчасово" : "надовго";
            Label main = new Label(String.format(Locale.US, "%s %s: %+.1f %s (%s)",
                    arrow, e.metric().title, e.delta(), e.metric().unit, when));
            main.setWrapText(true);
            main.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: "
                    + (e.good() ? "#34D399" : "#FBBF24") + ";");

            Label why = new Label("Чому: " + e.why());
            why.setWrapText(true);
            why.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px; -fx-padding: 0 0 4 14;");

            effectsBox.getChildren().addAll(main, why);
        }

        Label legend = new Label("Зелений: корисно для здоров'я. Жовтий: тимчасова реакція організму або витрата ресурсів.");
        legend.setWrapText(true);
        legend.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px;");
        effectsBox.getChildren().add(legend);
    }

    private void addLog(String text) {
        String line = "[" + LOG_TIME.format(missionNow()) + "] " + text;
        if (logLines.isEmpty()) logBox.getChildren().clear();
        logLines.add(line);

        Label l = new Label(line);
        l.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 11px;");
        logBox.getChildren().add(0, l);
        if (logBox.getChildren().size() > 12) {
            logBox.getChildren().remove(logBox.getChildren().size() - 1);
        }
    }

    // ============ ВЕРХНЯ ПАНЕЛЬ ============
    private HBox buildTopHeader(Stage stage) {
        HBox header = new HBox(16);
        header.setPadding(new Insets(12, 24, 12, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #0F1626; -fx-border-color: #1E293B; -fx-border-width: 0 0 1 0;");

        Label logoTitle = new Label("AEGIS-BIO");
        logoTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 17px; -fx-font-weight: 900;");

        Label missionDesc = new Label("Помічник лікаря для екіпажу в далекому космосі");
        missionDesc.setStyle("-fx-text-fill: #64748B; -fx-font-size: 13px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label locLabel = new Label("ДЕ МИ ЗАРАЗ:");
        locLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px; -fx-font-weight: bold;");

        locationBox = new ComboBox<>();
        locationBox.getItems().addAll(MissionLocation.values());
        locationBox.setValue(currentLocation);
        locationBox.setStyle("-fx-font-size: 12px;");
        locationBox.setOnAction(e -> {
            if (syncingLocation) return;
            MissionLocation selected = locationBox.getValue();
            if (selected == MissionLocation.SOLAR_CONJUNCTION) {
                // Вибір «Сонячне з'єднання» запускає справжній блекаут зі стандартним таймером
                startBlackout(BLACKOUT_DEFAULT_SEC);
            } else if (blackoutActive) {
                locationBeforeBlackout = selected;
                endBlackout(false);
            } else {
                this.currentLocation = selected;
                updateLatencyDisplay();
            }
        });

        Label speedLabel = new Label("ШВИДКІСТЬ ЧАСУ:");
        speedLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px; -fx-font-weight: bold;");

        ComboBox<String> speedBox = new ComboBox<>();
        speedBox.getItems().addAll("Звичайна (x1)", "Швидка (x10)", "Дуже швидка (x60)");
        speedBox.getSelectionModel().select(0);
        speedBox.setStyle("-fx-font-size: 12px;");
        speedBox.setOnAction(e -> {
            int idx = speedBox.getSelectionModel().getSelectedIndex();
            simSpeed = new int[]{1, 10, 60}[Math.max(0, idx)];
        });

        Button importBtn = new Button("Завантажити дані з файлу (CSV)");
        importBtn.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4;");
        importBtn.setOnAction(e -> handleNasaCsvImport(stage));

        Button reportBtn = new Button("Зберегти звіт (TXT)");
        reportBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4;");
        reportBtn.setOnAction(e -> saveReport(stage));

        Button blackoutBtn = new Button("🛡️ Режим Блекауту");
        blackoutBtn.setStyle("-fx-background-color: #581C87; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4;");
        blackoutBtn.setOnAction(e -> openBlackoutVaultWindow(stage));

        header.getChildren().addAll(logoTitle, missionDesc, spacer, locLabel, locationBox,
                speedLabel, speedBox, importBtn, reportBtn, blackoutBtn);
        return header;
    }

    // ============ ГОЛОВНИЙ ЕКРАН ============
    private ScrollPane scroll(VBox content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        return sp;
    }

    private GridPane buildMainDashboard() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(16));
        grid.setHgap(16);
        grid.setVgap(16);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(30);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(33);
        ColumnConstraints col3 = new ColumnConstraints();
        col3.setPercentWidth(37);
        grid.getColumnConstraints().addAll(col1, col2, col3);

        RowConstraints row = new RowConstraints();
        row.setVgrow(Priority.ALWAYS);
        grid.getRowConstraints().add(row);

        grid.add(scroll(buildTelemetryPanel()), 0, 0);
        grid.add(buildAnatomyHudPanel(), 1, 0); // без ScrollPane: займає всю висоту колонки
        grid.add(scroll(buildTriageAndHabitPanel()), 2, 0);

        updateAllTelemetryViews();
        return grid;
    }

    private static final String PANEL_STYLE =
            "-fx-background-color: #0E1726; -fx-border-color: #1E293B; -fx-border-radius: 8; -fx-background-radius: 8;";
    private static final String SECTION_TITLE_STYLE =
            "-fx-text-fill: #38BDF8; -fx-font-size: 13px; -fx-font-weight: bold;";

    // КОЛОНКА 1: показники
    private VBox buildTelemetryPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setStyle(PANEL_STYLE);

        Label title = new Label("ВАШІ ПОКАЗНИКИ ЗАРАЗ");
        title.setStyle(SECTION_TITLE_STYLE);

        healthScoreLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 13px; -fx-font-weight: bold;");
        healthBar.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().addAll(title, healthScoreLabel, healthBar);

        for (MetricType type : MetricType.values()) {
            VBox card = new VBox(3);
            card.setPadding(new Insets(7));
            card.setStyle("-fx-background-color: #151F32; -fx-background-radius: 6; -fx-border-color: #24324D; -fx-border-radius: 6;");

            BorderPane cardHead = new BorderPane();
            Label nameLbl = new Label(type.title);
            nameLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");
            Label deltaLbl = new Label();
            deltaLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
            deltaLabels.put(type, deltaLbl);
            cardHead.setLeft(nameLbl);
            cardHead.setRight(deltaLbl);

            Label valLbl = new Label();
            valLbl.setWrapText(true);
            valLbl.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 12px; -fx-font-weight: bold;");
            valueLabels.put(type, valLbl);

            ProgressBar bar = new ProgressBar(0.5);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setStyle("-fx-accent: #0284C7;");
            progressBars.put(type, bar);

            card.getChildren().addAll(cardHead, valLbl, bar);

            // Спливаюче пояснення простою мовою
            Tooltip tip = new Tooltip(metricHint(type));
            tip.setWrapText(true);
            tip.setMaxWidth(280);
            Tooltip.install(card, tip);

            box.getChildren().add(card);
        }

        box.getChildren().add(buildTrendSection());

        Label simLabel = new Label("ТЕСТОВІ СИТУАЦІЇ (щоб побачити, як працюють підказки):");
        simLabel.setWrapText(true);
        simLabel.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 8 0 0 0;");

        FlowPane btnRow = new FlowPane(8, 8);
        Button sansTrigger = new Button("Тест: тиск на очі");
        sansTrigger.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        sansTrigger.setOnAction(e -> simulateSansDegradation());

        Button radiationSpike = new Button("Тест: сонячний спалах");
        radiationSpike.setStyle("-fx-background-color: #EA580C; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        radiationSpike.setOnAction(e -> simulateRadiationEvent());

        Button resetBtn = new Button("Скинути до норми");
        resetBtn.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-cursor: hand;");
        resetBtn.setOnAction(e -> resetAll());

        btnRow.getChildren().addAll(sansTrigger, radiationSpike, resetBtn);
        box.getChildren().addAll(simLabel, btnRow);

        return box;
    }

    private String metricHint(MetricType type) {
        return switch (type) {
            case HEART_RATE -> "Скільки разів на хвилину б'ється серце. Після навантаження росте, у спокої нижчий.";
            case HRV -> "Показує, наскільки серце та нерви відпочили. Чим вище, тим краще ви справляєтесь зі стресом.";
            case BLOOD_PRESSURE -> "Сила, з якою кров тисне на судини. Надто високий тиск шкодить серцю.";
            case BLOOD_GLUCOSE -> "Паливо для мозку й м'язів. Після їжі росте, після руху знижується.";
            case BODY_TEMP -> "Температура тіла. Після тренування трохи підвищується.";
            case SPO2 -> "Скільки кисню несе кров. Нижче 95% потрібно перевірити вентиляцію.";
            case ICP_OPTIC -> "У невагомості рідина йде до голови й тисне на очі. Це головна причина проблем із зором у польоті.";
            case RADIATION_DOSE -> "Скільки радіації накопичилось. Вона не зникає, тому її важливо не пропустити.";
            case HYDRATION -> "Скільки рідини в організмі. Без води гіршає серце й голова.";
            case BONE_DENSITY -> "У невагомості кістки слабшають. Силові тренування це сповільнюють.";
        };
    }

    // КОЛОНКА 2: сканер тіла (на всю висоту)
    private VBox buildAnatomyHudPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setAlignment(Pos.TOP_CENTER);
        box.setStyle(PANEL_STYLE);

        Label title = new Label("СКАНЕР ТІЛА: ЩО ДІЄТЬСЯ В НЕВАГОМОСТІ");
        title.setStyle(SECTION_TITLE_STYLE);

        bodyHudCanvas = new Canvas(BODY_W, BODY_H);
        bodyHudCanvas.setManaged(false); // щоб канвас не заважав контейнеру стискатись
        Pane canvasHolder = new Pane(bodyHudCanvas);
        canvasHolder.setMinSize(0, 0);
        bodyHudCanvas.widthProperty().bind(canvasHolder.widthProperty());
        bodyHudCanvas.heightProperty().bind(canvasHolder.heightProperty());
        VBox.setVgrow(canvasHolder, Priority.ALWAYS);

        Label subtext = new Label("Зелений, синій: усе гаразд. Жовтий, оранжевий, червоний: потрібна увага. "
                + "Серце б'ється з вашою справжньою частотою пульсу. "
                + "У невагомості рідина піднімається до голови, тисне на очі, хребет розтягується, а кістки втрачають кальцій.");
        subtext.setWrapText(true);
        subtext.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px; -fx-text-alignment: center;");

        Label clockCaption = new Label("ЧАС МІСІЇ (UTC)");
        clockCaption.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px; -fx-font-weight: bold;");
        bigClockLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 30px; -fx-font-weight: 900; "
                + "-fx-font-family: 'Consolas', 'Courier New', monospace;");
        simClockLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");
        VBox clockBox = new VBox(2, clockCaption, bigClockLabel, simClockLabel);
        clockBox.setAlignment(Pos.CENTER);
        clockBox.setPadding(new Insets(8));
        clockBox.setMaxWidth(Double.MAX_VALUE);
        clockBox.setStyle("-fx-background-color: #151F32; -fx-background-radius: 6; -fx-border-color: #24324D; -fx-border-radius: 6;");
        updateClock();

        box.getChildren().addAll(title, clockBox, canvasHolder, subtext);
        return box;
    }

    // КОЛОНКА 3: підказки, дії, вплив, журнал
    private VBox buildTriageAndHabitPanel() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        box.setStyle(PANEL_STYLE);

        Label cdssTitle = new Label("ЩО РОБИТИ ЗАРАЗ (ПІДКАЗКИ ЛІКАРЯ)");
        cdssTitle.setStyle(SECTION_TITLE_STYLE);

        latencyNoticeLabel.setWrapText(true);
        latencyNoticeLabel.setMaxWidth(Double.MAX_VALUE);
        updateLatencyDisplay();

        // Велика кнопка «виконати все рекомендоване»
        autoPlanBtn.setMaxWidth(Double.MAX_VALUE);
        autoPlanBtn.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-size: 13px; "
                + "-fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 10;");
        autoPlanBtn.managedProperty().bind(autoPlanBtn.visibleProperty());
        autoPlanBtn.setOnAction(e -> {
            for (String id : recommendedActionIds()) {
                Activity a = findActivity(id);
                if (a != null) applyActivity(a);
            }
        });
        updateAutoPlanButton();

        // Дії
        Label actionsTitle = new Label("ВАШІ ДІЇ (натисніть, коли зробили)");
        actionsTitle.setStyle(SECTION_TITLE_STYLE + " -fx-padding: 8 0 0 0;");

        VBox actionsBox = new VBox(8);
        actionsBox.setPadding(new Insets(10));
        actionsBox.setStyle("-fx-background-color: #151F32; -fx-background-radius: 6; -fx-border-color: #24324D; -fx-border-radius: 6;");

        workoutLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");
        workoutBar.setMaxWidth(Double.MAX_VALUE);
        workoutBar.setStyle("-fx-accent: #10B981;");
        hydrationLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");
        hydrationBar.setMaxWidth(Double.MAX_VALUE);
        hydrationBar.setStyle("-fx-accent: #06B6D4;");
        sleepLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");
        sleepBar.setMaxWidth(Double.MAX_VALUE);
        sleepBar.setStyle("-fx-accent: #6366F1;");

        actionsBox.getChildren().addAll(workoutLabel, workoutBar, hydrationLabel, hydrationBar,
                sleepLabel, sleepBar, new Separator());

        for (Activity a : buildActivities()) {
            Button b = new Button(a.button());
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setStyle("-fx-background-color: " + a.color() + "; -fx-text-fill: white; -fx-font-size: 11px; -fx-cursor: hand;");
            b.setWrapText(true);
            b.setOnAction(e -> applyActivity(a));
            activityButtons.put(a.id(), b);
            actionsBox.getChildren().add(b);
        }

        actionsBox.getChildren().add(new Separator());
        Label pillsTitle = new Label("ТАБЛЕТКИ (дозу рахує програма)");
        pillsTitle.setStyle("-fx-text-fill: #FBBF24; -fx-font-size: 12px; -fx-font-weight: bold;");
        Label pillsNote = new Label("Доза залежить від того, наскільки показник поза нормою. "
                + "Програма не дозволяє перевищити дозу за один раз, денний максимум і мінімальну перерву між прийомами. "
                + "Це демонстраційна модель: реальні дози призначає лікар місії.");
        pillsNote.setWrapText(true);
        pillsNote.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");
        actionsBox.getChildren().addAll(pillsTitle, pillsNote);

        for (Pill p : buildPills()) {
            Label pillName = new Label(p.name());
            pillName.setWrapText(true);
            pillName.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 11px; -fx-font-weight: bold;");
            Label pillPurpose = new Label(p.purpose());
            pillPurpose.setWrapText(true);
            pillPurpose.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");
            Label pillStatus = new Label();
            pillStatus.setWrapText(true);
            pillStatus.setStyle("-fx-text-fill: #FDE68A; -fx-font-size: 10px;");
            Button pillBtn = new Button();
            pillBtn.setMaxWidth(Double.MAX_VALUE);
            pillBtn.setWrapText(true);
            pillBtn.setAlignment(Pos.CENTER_LEFT);
            pillBtn.setStyle("-fx-background-color: " + p.color() + "; -fx-text-fill: white; -fx-font-size: 11px; -fx-cursor: hand;");
            pillBtn.setOnAction(e -> applyPill(p));
            pillLabels.put(p.id(), pillStatus);
            pillButtons.put(p.id(), pillBtn);

            VBox row = new VBox(3, pillName, pillPurpose, pillStatus, pillBtn);
            row.setPadding(new Insets(6));
            row.setStyle("-fx-background-color: #0E1726; -fx-background-radius: 6;");
            actionsBox.getChildren().add(row);
        }
        updatePillViews();

        Label driftNote = new Label("Без тренувань і сеансів LBNP стан у невагомості поступово погіршується: "
                + "тиск на очі росте, кістки слабшають, а вода потроху витрачається.");
        driftNote.setWrapText(true);
        driftNote.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px;");
        actionsBox.getChildren().add(driftNote);

        // Вплив
        Label effectsTitle = new Label("ЯК ЦЕ ВПЛИНУЛО НА ЗДОРОВ'Я");
        effectsTitle.setStyle(SECTION_TITLE_STYLE + " -fx-padding: 8 0 0 0;");

        derivedLabel.setWrapText(true);
        derivedLabel.setMaxWidth(Double.MAX_VALUE);
        derivedLabel.setStyle("-fx-text-fill: #FDE68A; -fx-font-size: 12px; -fx-background-color: #3B2A07; -fx-padding: 8; -fx-background-radius: 4;");
        derivedLabel.managedProperty().bind(derivedLabel.visibleProperty());
        derivedLabel.setVisible(!derivedNotes.isEmpty());

        effectsBox.setPadding(new Insets(10));
        effectsBox.setStyle("-fx-background-color: #151F32; -fx-background-radius: 6; -fx-border-color: #24324D; -fx-border-radius: 6;");
        showEffectsPlaceholder();

        // Журнал
        Label logTitle = new Label("ЖУРНАЛ ДІЙ");
        logTitle.setStyle(SECTION_TITLE_STYLE + " -fx-padding: 8 0 0 0;");
        Label emptyLog = new Label("Поки що нічого не зроблено.");
        emptyLog.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
        logBox.getChildren().add(emptyLog);

        updateHabitViews();
        box.getChildren().addAll(cdssTitle, latencyNoticeLabel, autoPlanBtn, protocolContainer,
                actionsTitle, actionsBox, effectsTitle, derivedLabel, effectsBox, logTitle, logBox);
        return box;
    }

    private HBox buildBottomStatusBar() {
        HBox status = new HBox(20);
        status.setPadding(new Insets(8, 24, 8, 24));
        status.setAlignment(Pos.CENTER_LEFT);
        status.setStyle("-fx-background-color: #0B111E; -fx-border-color: #1E293B; -fx-border-width: 1 0 0 0;");

        Label crewStatus = new Label("● КОМАНДИР: М. ВОДКО. Автономний режим: рішення ви приймаєте самі");
        crewStatus.setStyle("-fx-text-fill: #10B981; -fx-font-size: 11px; -fx-font-weight: bold;");

        Label linkStatus = new Label("Зв'язок із Землею (Х'юстон): повідомлення йдуть із затримкою");
        linkStatus.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        clockLabel.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");

        blackoutStatusLabel.setStyle("-fx-text-fill: #C084FC; -fx-font-size: 11px; -fx-font-weight: bold;");
        blackoutStatusLabel.setVisible(false);
        blackoutStatusLabel.setManaged(false);

        status.getChildren().addAll(crewStatus, linkStatus, blackoutStatusLabel, spacer, clockLabel);
        return status;
    }

    private void updateClock() {
        Instant now = missionNow();
        clockLabel.setText("Доба місії: " + (142 + clockSec / 86400) + "  |  Час (UTC): " + CLOCK_FORMAT.format(now));
        bigClockLabel.setText(LOG_TIME.format(now));
        simClockLabel.setText(String.format(Locale.US, "З початку симуляції: %d д %02d:%02d:%02d",
                clockSec / 86400, (clockSec / 3600) % 24, (clockSec / 60) % 60, clockSec % 60));
    }

    // ============ МАЛЮВАННЯ ТІЛА ============
    private void glowDot(GraphicsContext gc, double cx, double cy, double size, Color color) {
        gc.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.18));
        double halo = size * 2.6;
        gc.fillOval(cx - halo / 2, cy - halo / 2, halo, halo);
        gc.setFill(color);
        gc.fillOval(cx - size / 2, cy - size / 2, size, size);
    }

    private void drawTag(GraphicsContext gc, String text, double x, double y, Color color) {
        double textWidth = text.length() * 6.6;
        gc.setFill(Color.rgb(8, 12, 20, 0.8));
        gc.fillRect(x - 3, y - 11, textWidth + 6, 15);
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

    /** Малює сітку, масштабованого космонавта і промінь сканера на всю висоту канваса. */
    private void drawHolographicBody(GraphicsContext gc) {
        double w = bodyHudCanvas.getWidth();
        double h = bodyHudCanvas.getHeight();
        if (w < 10 || h < 10) return;
        gc.clearRect(0, 0, w, h);

        // Сітка сканера на весь екран
        gc.setStroke(Color.rgb(30, 41, 59, 0.4));
        gc.setLineWidth(1);
        gc.setLineDashes((double[]) null);
        for (double y = 0; y < h; y += 30) gc.strokeLine(0, y, w, y);
        for (double x = 0; x < w; x += 30) gc.strokeLine(x, 0, x, h);

        // Космонавт: масштаб під доступне місце, по центру
        double s = Math.min(w / BODY_W, h / BODY_H);
        gc.save();
        gc.translate((w - BODY_W * s) / 2, (h - BODY_H * s) / 2);
        gc.scale(s, s);
        drawBodyLogical(gc);
        gc.restore();

        // Промінь сканера на всю висоту
        double scanY = (animationPulse * 25) % h;
        gc.setFill(Color.rgb(56, 189, 248, 0.10));
        gc.fillRect(0, scanY - 6, w, 12);
        gc.setStroke(Color.rgb(125, 211, 252, 0.5));
        gc.setLineWidth(1);
        gc.strokeLine(0, scanY, w, scanY);
    }

    /** Малюнок космонавта в логічних координатах BODY_W x BODY_H. */
    private void drawBodyLogical(GraphicsContext gc) {
        double w = BODY_W;
        double h = BODY_H;
        gc.setFont(Font.font("Consolas", 11));

        double cx = w / 2;
        double hy = 62; // центр голови

        Color fill = Color.rgb(56, 189, 248, 0.10);
        Color line = Color.rgb(56, 189, 248, 0.75);
        Color inner = Color.rgb(186, 230, 253, 0.25);

        // Ранцевий блок життєзабезпечення (за спиною), пунктиром
        gc.setFill(Color.rgb(56, 189, 248, 0.05));
        gc.fillRoundRect(cx - 60, hy + 44, 120, 108, 16, 16);
        gc.setStroke(Color.rgb(56, 189, 248, 0.35));
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
        drawRoundPart(gc, cx - 17, hy + 92, 34, 18, 4, Color.rgb(56, 189, 248, 0.06), line);

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
        gc.setFill(Color.rgb(56, 189, 248, 0.07));
        gc.fillOval(cx - 36, hy - 36, 72, 72);
        gc.setStroke(Color.rgb(56, 189, 248, 0.10));
        gc.setLineWidth(6);
        gc.strokeOval(cx - 36, hy - 36, 72, 72);
        gc.setStroke(line);
        gc.setLineWidth(1.5);
        gc.strokeOval(cx - 36, hy - 36, 72, 72);

        // Скло шолома (візор) з градієнтом і відблиском
        gc.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(125, 211, 252, 0.35)),
                new Stop(1, Color.rgb(14, 116, 144, 0.10))));
        gc.fillOval(cx - 25, hy - 20, 50, 40);
        gc.setStroke(Color.rgb(125, 211, 252, 0.8));
        gc.setLineWidth(1.2);
        gc.strokeOval(cx - 25, hy - 20, 50, 40);
        gc.setStroke(Color.rgb(255, 255, 255, 0.45));
        gc.setLineWidth(2);
        gc.strokeArc(cx - 20, hy - 16, 40, 32, 110, 50, ArcType.OPEN);

        double pulse = 6 + 2 * Math.sin(animationPulse);

        // Очі (видно крізь візор)
        double icp = currentTelemetry.get(MetricType.ICP_OPTIC);
        Color sansColor = icp > 18.0 ? Color.rgb(239, 68, 68, 0.95) : Color.rgb(16, 185, 129, 0.9);
        glowDot(gc, cx - 8, hy - 3, pulse, sansColor);
        glowDot(gc, cx + 8, hy - 3, pulse, sansColor);
        drawTag(gc, String.format(Locale.US, "Тиск на очі: %.1f", icp), cx + 40, hy - 24, sansColor);

        // Серце: б'ється з частотою пульсу
        double hr = currentTelemetry.get(MetricType.HEART_RATE);
        boolean heartWarn = hr > 100 || hr < 55 || currentTelemetry.get(MetricType.HRV) < 40;
        Color heartColor = heartWarn ? Color.rgb(249, 115, 22, 0.95) : Color.rgb(56, 189, 248, 0.9);
        double beat = 0.5 + 0.5 * Math.sin(heartPhase);
        glowDot(gc, cx, hy + 75, 5 + 6 * beat * beat, heartColor);
        drawTag(gc, String.format(Locale.US, "Пульс: %.0f", hr), 8, hy + 79, heartColor);

        // Хребет
        Color spineColor = Color.rgb(168, 85, 247, 0.9);
        glowDot(gc, cx, hy + 135, pulse, spineColor);
        drawTag(gc, String.format(Locale.US, "Хребет: +%.1f см", SPINE_ELONGATION_CM),
                cx + 12, hy + 139, spineColor);

        // Вода
        double hyd = currentTelemetry.get(MetricType.HYDRATION);
        Color hydColor = hyd < 90 ? Color.rgb(249, 115, 22, 0.95) : Color.rgb(34, 211, 238, 0.9);
        glowDot(gc, cx, hy + 160, pulse, hydColor);
        drawTag(gc, String.format(Locale.US, "Вода: %.0f%%", hyd), 8, hy + 215, hydColor);

        // Кістки ніг
        double bone = currentTelemetry.get(MetricType.BONE_DENSITY);
        Color boneColor = bone < 97 ? Color.rgb(239, 68, 68, 0.95) : Color.rgb(234, 179, 8, 0.9);
        glowDot(gc, cx - 26, hy + 285, pulse, boneColor);
        glowDot(gc, cx + 26, hy + 285, pulse, boneColor);
        drawTag(gc, String.format(Locale.US, "Міцність кісток: %.1f%%", bone), cx - 80, h - 8, boneColor);
    }

    // ============ ПІДКАЗКИ ============
    private int rank(String urgency) {
        return switch (urgency) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            default -> 3; // NOMINAL і FORECAST
        };
    }

    private void runAutonomousTriage() {
        activeProtocols.clear();

        if (currentTelemetry.get(MetricType.RADIATION_DOSE) > 100.0) {
            activeProtocols.add(new ClinicalProtocol("Радіація", "Ви отримали забагато радіації",
                    "1. Негайно перейдіть у захисний відсік (укриття зі стінами з води).\n"
                            + "2. Почніть приймати радіозахисні таблетки (антиоксиданти).", "CRITICAL"));
        }
        if (currentTelemetry.get(MetricType.ICP_OPTIC) > 18.0) {
            activeProtocols.add(new ClinicalProtocol("Очі", "Занадто високий тиск на зоровий нерв",
                    "1. Зробіть 45 хвилин у камері LBNP. Вона відтягує рідину від голови.\n"
                            + "2. Одягніть коригувальні лінзи (для далекозорості).", "HIGH", "LBNP"));
        }
        if (currentTelemetry.get(MetricType.HRV) < 40.0) {
            Activity breathAct = findActivity("BREATH");
            boolean breathBlocked = breathAct != null && blockedReason(breathAct) != null;
            String cause = derivedNotes.isEmpty() ? ""
                    : "Головна причина: нестача води або сну (див. жовту підказку). Вправа допоможе лише ненадовго.\n";
            activeProtocols.add(new ClinicalProtocol("Стрес і втома", "Організм дуже втомлений",
                    cause + "1. Зменште навантаження на велотренажері CEVIS на 20%.\n"
                            + (breathBlocked ? "2. Дихальну вправу щойно зроблено, потрібна перерва 30 хв.\n"
                            : "2. Зробіть дихальну вправу.\n")
                            + "3. Увімкніть у каюті синє світло (480 нм).", "MEDIUM", "BREATH"));
        }
        if (currentTelemetry.get(MetricType.HYDRATION) < 90.0) {
            if (hydrationLiters >= WATER_MAX_L - 1e-6) {
                activeProtocols.add(new ClinicalProtocol("Вода", "Денну норму води вже випито",
                        "Ви випили 2.8 з 2.8 л. Більше пити не можна: надлишок води шкідливий.\n"
                                + "1. Не робіть важких тренувань, щоб не втрачати воду з потом.\n"
                                + "2. Лічильник води скинеться з новою добою. Якщо запас води не відновлюється, зверніться до лікаря.",
                        "MEDIUM"));
            } else {
                activeProtocols.add(new ClinicalProtocol("Вода", "Вам не вистачає води",
                        String.format(Locale.US,
                                "Сьогодні випито %.1f з %.1f л. Випийте 400 мл води з електролітами. Понад денну норму пити не можна.",
                                hydrationLiters, WATER_MAX_L),
                        "MEDIUM", "WATER"));
            }
        }
        if (sleepHours < 6.0) {
            activeProtocols.add(new ClinicalProtocol("Сон", "Ви замало спали",
                    String.format(Locale.US, "Ви спали %.1f год. Поспіть ще, щоб відновити серце й знизити цукор у крові.", sleepHours),
                    "MEDIUM", "SLEEP"));
        }
        if (currentTelemetry.get(MetricType.BONE_DENSITY) < 97.0) {
            if (countermeasureMinutes >= WORKOUT_MAX_MIN) {
                activeProtocols.add(new ClinicalProtocol("Кістки", "Кістки втрачають кальцій",
                        "Денну норму тренувань уже виконано. Продовжите завтра. Можна прийняти вітамін D (розділ «Таблетки»).",
                        "MEDIUM"));
            } else {
                activeProtocols.add(new ClinicalProtocol("Кістки", "Кістки втрачають кальцій",
                        "Зробіть силове тренування на ARED, 30 хвилин. Повторюйте регулярно.",
                        "MEDIUM", "STRENGTH"));
            }
        }
        double bpNoTemp = currentTelemetry.get(MetricType.BLOOD_PRESSURE)
                - tempOffsets.getOrDefault(MetricType.BLOOD_PRESSURE, 0.0);
        if (bpNoTemp > 135.0) {
            activeProtocols.add(new ClinicalProtocol("Тиск", "Підвищений тиск крові",
                    "Зробіть дихальну вправу та відпочиньте. Не робіть важких вправ, поки тиск не знизиться.",
                    "MEDIUM", "BREATH"));
        }

        if (activeProtocols.isEmpty()) {
            activeProtocols.add(new ClinicalProtocol("Усе добре", "Усі показники в нормі",
                    "Нічого робити не потрібно. Дотримуйтесь графіка харчування "
                            + "і виконуйте 150 хвилин силових тренувань на тренажері ARED.", "NOMINAL"));
        }

        addForecastWarnings();
        activeProtocols.sort(Comparator.comparingInt(p -> rank(p.urgency())));
    }

    // ============ ІСТОРІЯ І ПРОГНОЗ ============
    /** Раннє попередження: показник ще в нормі, але за поточним трендом скоро вийде за межу. */
    private void addForecastWarnings() {
        for (MetricType type : MetricType.values()) {
            TrendEngine.Forecast f = steadyHistory.forecast(type, type.minNominal, type.maxNominal);
            double steadyNow = currentTelemetry.get(type) - tempOffsets.getOrDefault(type, 0.0);
            if (!f.ready() || !f.leavesRange() || !inRange(type, steadyNow)) continue;
            if (f.hoursToLimit() > FORECAST_WARN_HOURS) continue;

            String verb = f.direction() == TrendEngine.Direction.UP ? "зросте вище норми" : "впаде нижче норми";
            activeProtocols.add(new ClinicalProtocol(
                    "Прогноз",
                    type.title + ": " + verb + " приблизно через " + formatHours(f.hoursToLimit()),
                    "1. " + preventiveAdviceNow(type) + "\n"
                            + "2. Подивіться графік «Історія і прогноз» ліворуч.",
                    "FORECAST",
                    preventiveActionNow(type)));
        }
    }

    private String preventiveAdvice(MetricType type) {
        return switch (type) {
            case ICP_OPTIC -> "Заплануйте сеанс LBNP (камера для ніг), поки тиск на зоровий нерв ще в нормі.";
            case HYDRATION -> "Випийте воду з електролітами заздалегідь.";
            case BONE_DENSITY -> "Заплануйте силове тренування на ARED.";
            case HRV -> "Заплануйте дихальну вправу і відпочинок.";
            case BLOOD_PRESSURE -> "Зробіть кардіо-тренування або дихальну вправу.";
            case BLOOD_GLUCOSE -> "Зробіть кардіо-тренування і перегляньте графік харчування.";
            case HEART_RATE -> "Перевірте запас води і сон, зробіть дихальну вправу.";
            default -> "Стежте за показником, перевіряйте воду, сон і тренування.";
        };
    }

    /** Яку дію можна виконати кнопкою, щоб запобігти виходу показника за норму. */
    private String preventiveAction(MetricType type) {
        return switch (type) {
            case ICP_OPTIC -> "LBNP";
            case HYDRATION -> "WATER";
            case BONE_DENSITY -> "STRENGTH";
            case HRV, HEART_RATE -> "BREATH";
            case BLOOD_PRESSURE, BLOOD_GLUCOSE -> "CARDIO";
            default -> null;
        };
    }

    private String formatHours(double hours) {
        if (hours < 1.0) return "~" + Math.max(1, Math.round(hours * 60)) + " хв";
        if (hours > 72.0) return "понад 3 доби";
        return "~" + Math.round(hours) + " год";
    }

    private VBox buildTrendSection() {
        VBox sec = new VBox(6);
        sec.setPadding(new Insets(8, 0, 0, 0));

        Label title = new Label("ІСТОРІЯ І ПРОГНОЗ");
        title.setStyle(SECTION_TITLE_STYLE);

        ComboBox<MetricType> pick = new ComboBox<>();
        pick.getItems().addAll(MetricType.values());
        pick.setValue(chartMetric);
        pick.setMaxWidth(Double.MAX_VALUE);
        pick.setStyle("-fx-font-size: 12px;");
        StringConverter<MetricType> conv = new StringConverter<>() {
            @Override public String toString(MetricType t) { return t == null ? "" : t.title; }
            @Override public MetricType fromString(String s) { return null; }
        };
        pick.setConverter(conv);
        pick.setOnAction(e -> {
            if (pick.getValue() != null) {
                chartMetric = pick.getValue();
                updateTrendChart();
            }
        });

        NumberAxis xAxis = new NumberAxis();
        xAxis.setForceZeroInRange(false);
        xAxis.setLabel("Час симуляції, год");
        xAxis.setTickLabelFill(Color.web("#94A3B8"));
        NumberAxis yAxis = new NumberAxis();
        yAxis.setForceZeroInRange(false);
        yAxis.setTickLabelFill(Color.web("#94A3B8"));

        trendChart = new LineChart<>(xAxis, yAxis);
        trendChart.setAnimated(false);
        trendChart.setCreateSymbols(false);
        trendChart.setLegendVisible(false);
        trendChart.setMinHeight(220);
        trendChart.setPrefHeight(220);
        trendChart.setStyle("-fx-background-color: transparent;");
        trendChart.getData().add(valueSeries);
        trendChart.getData().add(lowSeries);
        trendChart.getData().add(highSeries);

        forecastLabel.setWrapText(true);
        forecastLabel.setMaxWidth(Double.MAX_VALUE);
        forecastLabel.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 11px; "
                + "-fx-background-color: #151F32; -fx-padding: 8; -fx-background-radius: 4;");

        Label legend = new Label("Блакитна лінія: ваш показник. Зелений пунктир: межі норми. "
                + "Прогноз не враховує тимчасових піків після тренувань.");
        legend.setWrapText(true);
        legend.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px;");

        sec.getChildren().addAll(title, pick, trendChart, forecastLabel, legend);
        updateTrendChart();
        return sec;
    }

    private void updateTrendChart() {
        if (trendChart == null) return;
        MetricType t = chartMetric;

        List<TrendEngine.Sample> pts = chartHistory.samples(t, 120);
        List<XYChart.Data<Number, Number>> vals = new ArrayList<>();
        for (TrendEngine.Sample sm : pts) vals.add(new XYChart.Data<>(sm.timeMin() / 60.0, sm.value()));
        valueSeries.getData().setAll(vals);

        if (pts.size() >= 2) {
            double x0 = pts.get(0).timeMin() / 60.0;
            double x1 = pts.get(pts.size() - 1).timeMin() / 60.0;
            lowSeries.getData().setAll(List.of(
                    new XYChart.Data<Number, Number>(x0, t.minNominal),
                    new XYChart.Data<Number, Number>(x1, t.minNominal)));
            highSeries.getData().setAll(List.of(
                    new XYChart.Data<Number, Number>(x0, t.maxNominal),
                    new XYChart.Data<Number, Number>(x1, t.maxNominal)));
        } else {
            lowSeries.getData().clear();
            highSeries.getData().clear();
        }

        trendChart.getYAxis().setLabel(t.unit);
        styleTrendChart();
        forecastLabel.setText(describeForecast(t));
    }

    private void styleTrendChart() {
        styleSeries(valueSeries, "-fx-stroke: #38BDF8; -fx-stroke-width: 2px;");
        styleSeries(lowSeries, "-fx-stroke: #10B981; -fx-stroke-width: 1px; -fx-stroke-dash-array: 6 6;");
        styleSeries(highSeries, "-fx-stroke: #10B981; -fx-stroke-width: 1px; -fx-stroke-dash-array: 6 6;");

        Node plot = trendChart.lookup(".chart-plot-background");
        if (plot != null) plot.setStyle("-fx-background-color: #0B111E;");
        for (String sel : new String[]{".chart-horizontal-grid-lines", ".chart-vertical-grid-lines"}) {
            Node n = trendChart.lookup(sel);
            if (n != null) n.setStyle("-fx-stroke: #1E293B;");
        }
        for (Node n : trendChart.lookupAll(".axis-label")) n.setStyle("-fx-text-fill: #94A3B8; -fx-fill: #94A3B8;");
    }

    private void styleSeries(XYChart.Series<Number, Number> series, String css) {
        Node n = series.getNode();
        if (n != null) n.setStyle(css);
    }

    private String describeForecast(MetricType t) {
        TrendEngine.Forecast f = steadyHistory.forecast(t, t.minNominal, t.maxNominal);
        if (!f.ready()) return "Збираю дані для прогнозу. Потрібно ще кілька вимірювань без різких змін стану.";

        String rate = String.format(Locale.US, "%+.2f %s/год", f.slopePerHour(), t.unit);
        double steadyNow = currentTelemetry.get(t) - tempOffsets.getOrDefault(t, 0.0);
        if (!inRange(t, steadyNow)) return "Показник уже поза нормою. Швидкість зміни: " + rate + ".";
        if (f.direction() == TrendEngine.Direction.STABLE) return "Показник стабільний (" + rate + ").";
        return String.format(Locale.US,
                "Тренд: %s. За цим темпом показник досягне межі норми (%.1f %s) приблизно через %s.",
                rate, f.limit(), t.unit, formatHours(f.hoursToLimit()));
    }

    private String urgencyLabel(String urgency) {
        return switch (urgency) {
            case "CRITICAL" -> "ТЕРМІНОВО";
            case "HIGH" -> "ВАЖЛИВО";
            case "MEDIUM" -> "УВАГА";
            case "FORECAST" -> "ПРОГНОЗ";
            default -> "ВСЕ ГАРАЗД";
        };
    }

    private void renderActiveProtocols() {
        protocolContainer.getChildren().clear();
        cardPillButtons.clear();
        for (ClinicalProtocol p : activeProtocols) {
            VBox card = new VBox(4);
            card.setPadding(new Insets(10));
            card.setStyle(cardStyle(p.urgency()));

            Label category = new Label(urgencyLabel(p.urgency()) + "  |  " + p.hazardCategory());
            category.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 11px; -fx-font-weight: bold;");

            Label title = new Label(p.title());
            title.setWrapText(true);
            title.setStyle("-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: bold;");

            Label action = new Label("Що робити:\n" + p.action());
            action.setWrapText(true);
            action.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 12px;");

            card.getChildren().addAll(category, title, action);

            // Кнопка «Зробити зараз» прямо на підказці
            Activity act = findActivity(p.actionId());
            if (act != null && blockedReason(act) == null) {
                Button doIt = new Button("▶ Зробити зараз: " + act.button());
                doIt.setWrapText(true);
                doIt.setMaxWidth(Double.MAX_VALUE);
                doIt.setStyle("-fx-background-color: " + act.color()
                        + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
                doIt.setOnAction(e -> applyActivity(act));
                card.getChildren().add(doIt);
            }

            Pill pill = pillForProtocol(p);
            if (pill != null) {
                Button pillBtn = new Button();
                pillBtn.setWrapText(true);
                pillBtn.setMaxWidth(Double.MAX_VALUE);
                cardPillButtons.put(pill.id(), pillBtn);
                refreshCardPillButton(pill, pillBtn);
                pillBtn.setStyle("-fx-background-color: " + pill.color()
                        + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
                pillBtn.setOnAction(e -> applyPill(pill));
                card.getChildren().add(pillBtn);
            }

            protocolContainer.getChildren().add(card);
        }
    }

    private String cardStyle(String urgency) {
        String[] colors = switch (urgency) {
            case "CRITICAL" -> new String[]{"#450A0A", "#EF4444"};
            case "HIGH" -> new String[]{"#3B1607", "#F97316"};
            case "MEDIUM" -> new String[]{"#2E1065", "#A855F7"};
            case "FORECAST" -> new String[]{"#082F49", "#0EA5E9"};
            default -> new String[]{"#064E3B", "#10B981"};
        };
        return "-fx-background-color: " + colors[0] + "; -fx-border-color: " + colors[1]
                + "; -fx-border-radius: 6; -fx-background-radius: 6;";
    }

    private void updateLatencyDisplay() {
        String desc = switch (currentLocation) {
            case LEO -> "Ви на навколоземній орбіті (МКС). Зв'язок із Землею майже миттєвий. "
                    + "Якщо щось незрозуміло, одразу зв'яжіться з лікарем на Землі.";
            case MOON -> "Ви біля Місяця. Сигнал до Землі йде близько 1.3 секунди. "
                    + "Лікар на Землі відповідає майже одразу.";
            case MARS_TRANSIT -> "Ви летите до Марса. Сигнал до Землі йде 14 хвилин, "
                    + "тому лікар не відповість швидко. Дійте за підказками нижче.";
            case SOLAR_CONJUNCTION -> "⚠️ Сонячне з'єднання (Блекаут). Сигнал заблокований Сонцем. "
                    + "Зв'язок відсутній (0 bps). Повна автономія екіпажу!";
        };
        latencyNoticeLabel.setText(desc);
        latencyNoticeLabel.setStyle("-fx-text-fill: #F59E0B; -fx-font-size: 12px; -fx-background-color: #261E0A; -fx-padding: 8; -fx-background-radius: 4;");
    }

    // ============ ОНОВЛЕННЯ ЕКРАНА ============
    private void updateAllTelemetryViews() {
        int okCount = 0;

        for (MetricType type : MetricType.values()) {
            double val = currentTelemetry.get(type);
            Label lbl = valueLabels.get(type);
            Label deltaLbl = deltaLabels.get(type);
            ProgressBar bar = progressBars.get(type);

            boolean high = val > type.maxNominal;
            boolean low = val < type.minNominal;
            if (!high && !low) okCount++;

            boolean tempOnly = (high || low)
                    && inRange(type, val - tempOffsets.getOrDefault(type, 0.0));

            String statusText;
            String textColor;
            String barColor;
            if (!high && !low) {
                statusText = "норма";
                textColor = "#F8FAFC";
                barColor = "#0284C7";
            } else {
                String dir = high ? "ВИЩЕ НОРМИ" : "НИЖЧЕ НОРМИ";
                if (tempOnly) {
                    statusText = dir + " (тимчасово)";
                    textColor = "#FBBF24";
                    barColor = "#F59E0B";
                } else {
                    statusText = dir;
                    textColor = "#FCA5A5";
                    barColor = "#EF4444";
                }
            }

            if (lbl != null) {
                lbl.setText(String.format(Locale.US, "%.1f %s  |  %s", val, type.unit, statusText));
                lbl.setStyle("-fx-text-fill: " + textColor + "; -fx-font-size: 12px; -fx-font-weight: bold;");
            }
            if (bar != null) {
                double range = type.maxNominal - type.minNominal;
                double normalized = range > 0 ? (val - type.minNominal) / range : 0;
                bar.setProgress(Math.max(0.05, Math.min(1.0, normalized)));
                bar.setStyle("-fx-accent: " + barColor + ";");
            }

            if (deltaLbl != null) {
                String text = "";
                if (beforeAction != null) {
                    double d = val - beforeAction.get(type);
                    if (Math.abs(d) >= 0.05) {
                        text = d > 0 ? String.format(Locale.US, "▲ +%.1f", d) : String.format(Locale.US, "▼ %.1f", d);
                    }
                }
                deltaLbl.setText(text);
                deltaLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: "
                        + (inRange(type, val) ? "#34D399" : "#FBBF24") + ";");
            }
        }

        // Загальна оцінка стану
        int total = MetricType.values().length;
        int score = okCount * 100 / total;
        String verdict;
        String color;
        if (score >= 90) { verdict = "Чудово"; color = "#10B981"; }
        else if (score >= 70) { verdict = "Добре"; color = "#0EA5E9"; }
        else if (score >= 50) { verdict = "Потрібна увага"; color = "#F59E0B"; }
        else { verdict = "Небезпечно"; color = "#EF4444"; }
        healthScoreLabel.setText("ЗАГАЛЬНИЙ СТАН: " + score + " з 100 (" + verdict + ")");
        healthBar.setProgress(score / 100.0);
        healthBar.setStyle("-fx-accent: " + color + ";");

        // Підказки перемальовуємо лише коли вони змінились
        runAutonomousTriage();
        updateAutoPlanButton();
        updatePillViews();
        updateActivityButtons();
        StringBuilder sig = new StringBuilder();
        for (ClinicalProtocol p : activeProtocols) {
            sig.append(p.urgency()).append('|').append(p.title()).append('|').append(p.actionId())
                    .append('|').append(p.action().hashCode());
            Activity sa = findActivity(p.actionId());
            if (sa != null && blockedReason(sa) != null) sig.append("|blocked");
            sig.append(';');
        }
        if (!sig.toString().equals(lastProtocolSignature)) {
            lastProtocolSignature = sig.toString();
            renderActiveProtocols();
        }
    }

    private void updateHabitViews() {
        workoutLabel.setText(String.format(Locale.US, "Тренування (силові та кардіо): %d з 150 хв", countermeasureMinutes));
        workoutBar.setProgress(countermeasureMinutes / 150.0);

        hydrationLabel.setText(String.format(Locale.US, "Вода з електролітами: %.1f з 2.8 л", hydrationLiters));
        hydrationBar.setProgress(hydrationLiters / 2.8);

        sleepLabel.setText(String.format(Locale.US, "Сон: %.1f з 8 год", sleepHours));
        sleepBar.setProgress(sleepHours / 8.0);
        updateActivityButtons();
    }

    // ============ ТЕСТОВІ СИТУАЦІЇ ============
    private void simulateSansDegradation() {
        beforeAction = new EnumMap<>(currentTelemetry);
        baseTelemetry.put(MetricType.ICP_OPTIC, 22.8);
        baseTelemetry.put(MetricType.BLOOD_PRESSURE, 142.0);
        steadyHistory.clearAll();
        recompute();
        updateAllTelemetryViews();
        addLog("Тест: підвищений тиск на очі");
    }

    private void simulateRadiationEvent() {
        beforeAction = new EnumMap<>(currentTelemetry);
        baseTelemetry.put(MetricType.RADIATION_DOSE, 125.4);
        baseTelemetry.put(MetricType.HRV, 36.0);
        steadyHistory.clearAll();
        recompute();
        updateAllTelemetryViews();
        addLog("Тест: сонячний спалах");
    }

    // ============ ЗВІТ ============
    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("ЗВІТ ПРО СТАН ЗДОРОВ'Я ЕКІПАЖУ\n");
        sb.append("Час (UTC): ").append(CLOCK_FORMAT.format(missionNow())).append('\n');
        sb.append("Де ми: ").append(currentLocation.label).append("\n\n");

        sb.append("ПОКАЗНИКИ:\n");
        for (MetricType type : MetricType.values()) {
            double v = currentTelemetry.get(type);
            String status = inRange(type, v) ? "норма" : (v > type.maxNominal ? "ВИЩЕ НОРМИ" : "НИЖЧЕ НОРМИ");
            sb.append(String.format(Locale.US, "- %s: %.1f %s (%s)%n", type.title, v, type.unit, status));
        }

        sb.append("\nПІДКАЗКИ:\n");
        for (ClinicalProtocol p : activeProtocols) {
            sb.append("- [").append(urgencyLabel(p.urgency())).append("] ").append(p.title()).append('\n');
            sb.append("  ").append(p.action().replace("\n", "\n  ")).append('\n');
        }

        sb.append(String.format(Locale.US,
                "%nВПРАВИ ТА ВОДА:%n- Тренування: %d з 150 хв%n- Вода: %.1f з 2.8 л%n- Сон: %.1f з 8 год%n",
                countermeasureMinutes, hydrationLiters, sleepHours));

        sb.append("\nЖУРНАЛ ДІЙ:\n");
        if (logLines.isEmpty()) sb.append("(порожньо)\n");
        for (String line : logLines) sb.append(line).append('\n');
        return sb.toString();
    }

    private void saveReport(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Зберегти звіт");
        fc.setInitialFileName("zvit_zdorovya.txt");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Текстовий файл (TXT)", "*.txt"));
        File file = fc.showSaveDialog(stage);
        if (file == null) return;

        try {
            Files.writeString(file.toPath(), buildReport(), StandardCharsets.UTF_8);
            Alert ok = new Alert(Alert.AlertType.INFORMATION, "Звіт збережено: " + file.getName(), ButtonType.OK);
            ok.initOwner(stage);
            ok.setHeaderText("Готово");
            ok.show();
        } catch (Exception ex) {
            Alert err = new Alert(Alert.AlertType.ERROR, "Не вдалося зберегти звіт: " + ex.getMessage(), ButtonType.OK);
            err.initOwner(stage);
            err.show();
        }
    }

    // ============ ВІКНО БЛЕКАУТУ ============
    private record ImportResult(Map<MetricType, Double> values, int skipped) {}

    private void openBlackoutVaultWindow(Stage ownerStage) {
        if (blackoutStage != null && blackoutStage.isShowing()) {
            blackoutStage.toFront();
            return;
        }
        Stage stage = new Stage();
        blackoutStage = stage;
        stage.initOwner(ownerStage);
        stage.setTitle("Aegis Blackout-Vault: Режим повної автономії (Solar Conjunction)");

        VBox root = new VBox(14);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #0F0505; -fx-font-family: 'Segoe UI', system-ui, sans-serif;");

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #581C87; -fx-padding: 12; -fx-background-radius: 6;");

        Label icon = new Label("📡❌");
        icon.setStyle("-fx-font-size: 24px;");

        VBox headerText = new VBox(2);
        Label title = new Label("РЕЖИМ РАДІОБЛЕКАУТУ ТА ЛОКАЛЬНОГО БУФЕРА");
        title.setStyle("-fx-text-fill: #F3E8FF; -fx-font-size: 14px; -fx-font-weight: 900;");

        Label sub = new Label("Сонце блокує сигнал із Землею. Зв'язок: 0 bps. Корабель керується AI-автопілотом Aegis.");
        sub.setStyle("-fx-text-fill: #D8B4FE; -fx-font-size: 11px;");
        sub.setWrapText(true);

        headerText.getChildren().addAll(title, sub);
        header.getChildren().addAll(icon, headerText);

        // --- Таймер ---
        VBox counterCard = new VBox(8);
        counterCard.setAlignment(Pos.CENTER);
        counterCard.setPadding(new Insets(14));
        counterCard.setStyle("-fx-background-color: #1E1B4B; -fx-border-color: #4338CA; -fx-border-radius: 6; -fx-background-radius: 6;");

        Label counterTitle = new Label();
        counterTitle.setStyle("-fx-text-fill: #93C5FD; -fx-font-size: 10px; -fx-font-weight: bold;");

        Label countdownDisplay = new Label();
        countdownDisplay.setStyle("-fx-text-fill: #C084FC; -fx-font-size: 28px; -fx-font-weight: 900;");

        ProgressBar blackoutProgress = new ProgressBar(0);
        blackoutProgress.setMaxWidth(Double.MAX_VALUE);

        Label bufferStatus = new Label();
        bufferStatus.setStyle("-fx-text-fill: #A78BFA; -fx-font-size: 11px;");

        counterCard.getChildren().addAll(counterTitle, countdownDisplay, blackoutProgress, bufferStatus);

        // --- Запуск / зупинка ---
        String[] presetLabels = {"10 хвилин (демо)", "1 година (демо)", "1 доба", "14 діб 6 год 42 хв (повний)"};
        long[] presetSecs = {10 * 60L, 3600L, 86400L, BLACKOUT_DEFAULT_SEC};

        ComboBox<String> durationBox = new ComboBox<>();
        durationBox.getItems().addAll(presetLabels);
        durationBox.getSelectionModel().select(0);
        durationBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(durationBox, Priority.ALWAYS);

        Button startStopBtn = new Button();
        startStopBtn.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        startStopBtn.setOnAction(e -> {
            if (blackoutActive) {
                endBlackout(false);
            } else {
                startBlackout(presetSecs[Math.max(0, durationBox.getSelectionModel().getSelectedIndex())]);
            }
        });

        Label durationLabel = new Label("Тривалість:");
        durationLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px; -fx-font-weight: bold;");

        HBox controlsRow = new HBox(10, durationLabel, durationBox, startStopBtn);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        Label speedHint = new Label("Таймер іде за часом місії: змініть «Швидкість часу» у верхній панелі, щоб прискорити його.");
        speedHint.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px;");
        speedHint.setWrapText(true);

        // --- Аварійний форсаж (обмежені резерви) ---
        Button aiBoostBtn = new Button();
        aiBoostBtn.setMaxWidth(Double.MAX_VALUE);
        aiBoostBtn.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        aiBoostBtn.setOnAction(e -> {
            if (!blackoutActive || emergencyCharges <= 0) return;
            emergencyCharges--;
            baseTelemetry.put(MetricType.HYDRATION, 100.0);
            baseTelemetry.put(MetricType.RADIATION_DOSE, Math.max(0, baseTelemetry.get(MetricType.RADIATION_DOSE) - 15.0));
            recompute();
            updateAllTelemetryViews();
            addLog("⚡ [AI Override] Використано аварійні резерви корабля! Залишилось зарядів: " + emergencyCharges + ".");

            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Системи корабля перерозподілили аварійні ресурси. Рівень води та радіації покращено!\n"
                            + "Залишилось аварійних зарядів: " + emergencyCharges + " з " + EMERGENCY_CHARGES_MAX + ".",
                    ButtonType.OK);
            alert.initOwner(stage);
            alert.setHeaderText("Форсаж успішний");
            alert.show();
        });

        // --- Протоколи й живий журнал автопілота ---
        VBox aiDecisionsCard = new VBox(8);
        aiDecisionsCard.setPadding(new Insets(12));
        aiDecisionsCard.setStyle("-fx-background-color: #1E293B; -fx-border-color: #334155; -fx-border-radius: 6; -fx-background-radius: 6;");

        Label aiTitle = new Label("ЛОКАЛЬНІ ПРОТОКОЛИ БЕЗПЕКИ AEGIS (AI-ARBITRATION):");
        aiTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 12px; -fx-font-weight: bold;");

        VBox decisionsList = new VBox(6);
        decisionsList.getChildren().addAll(
                createBlackoutItem("✓ Радіація: водяні щити поступово знижують отриману дозу."),
                createBlackoutItem("✓ Вода: якщо запас біля нижньої межі, AI сам п'є 400 мл (у межах денної норми)."),
                createBlackoutItem("✓ Серце й тиск: при підвищеному пульсі, тиску чи низькому HRV AI робить дихальну вправу."),
                createBlackoutItem("✓ Тиск на зоровий нерв: біля межі норми AI запускає сеанс LBNP."),
                createBlackoutItem("✓ Телеметрія: щохвилини місії пакет шифрується й іде в локальний буфер.")
        );

        Label recentTitle = new Label("ОСТАННІ ДІЇ АВТОПІЛОТА:");
        recentTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 11px; -fx-font-weight: bold;");
        Label recentActions = new Label();
        recentActions.setWrapText(true);
        recentActions.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");

        aiDecisionsCard.getChildren().addAll(aiTitle, decisionsList, recentTitle, recentActions);

        Button closeBtn = new Button("Повернутися до моніторингу екіпажу");
        closeBtn.setMaxWidth(Double.MAX_VALUE);
        closeBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> stage.close());

        // --- Оновлення вікна зі стану застосунку (вікно лише показує стан, таймер від нього не залежить) ---
        Runnable refresh = () -> {
            boolean on = blackoutActive;
            if (on) {
                counterTitle.setText("ЧАС ДО ВІДНОВЛЕННЯ ЗВ'ЯЗКУ З Х'ЮСТОНОМ");
                countdownDisplay.setText(formatDuration(blackoutRemainingSec()));
                double total = Math.max(1, blackoutEndSec - blackoutStartSec);
                blackoutProgress.setProgress(Math.min(1.0, Math.max(0.0, (clockSec - blackoutStartSec) / total)));
                bufferStatus.setText("📦 Локальний буфер: " + blackoutPackets() + " пакетів (~" + blackoutPackets() * PACKET_KB
                        + " КБ) чекають відправки. Дій AI: " + blackoutAiActions);
                startStopBtn.setText("⏹ Перервати блекаут");
                startStopBtn.setStyle("-fx-background-color: #B91C1C; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            } else {
                counterTitle.setText("БЛЕКАУТ НЕ АКТИВНИЙ. ОБЕРІТЬ ТРИВАЛІСТЬ І ЗАПУСТІТЬ");
                countdownDisplay.setText("Зв'язок із Землею є");
                blackoutProgress.setProgress(0);
                bufferStatus.setText("📦 Локальний буфер порожній");
                startStopBtn.setText("▶ Активувати блекаут");
                startStopBtn.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            }
            durationBox.setDisable(on);
            aiBoostBtn.setDisable(!on || emergencyCharges <= 0);
            aiBoostBtn.setText("⚡ Аварійний форсаж життєзабезпечення (AI Override), зарядів: "
                    + (on ? emergencyCharges : EMERGENCY_CHARGES_MAX) + "/" + EMERGENCY_CHARGES_MAX);
            recentActions.setText(autopilotRecent.isEmpty()
                    ? (on ? "Поки що втручання не потрібні: показники в нормі." : "—")
                    : String.join("\n", autopilotRecent));
        };
        refresh.run();

        Timeline windowRefresh = new Timeline(new KeyFrame(Duration.millis(250), ev -> refresh.run()));
        windowRefresh.setCycleCount(Animation.INDEFINITE);
        windowRefresh.play();

        // Закриття вікна не зупиняє блекаут: зупиняємо лише оновлення самого вікна
        stage.setOnHidden(e -> {
            windowRefresh.stop();
            if (blackoutStage == stage) blackoutStage = null;
        });

        root.getChildren().addAll(header, counterCard, controlsRow, speedHint, aiBoostBtn, aiDecisionsCard, closeBtn);

        Scene scene = new Scene(scroll(root), 580, 700);
        stage.setScene(scene);
        stage.show();
    }

    // ============ ЗАВАНТАЖЕННЯ ДАНИХ З CSV ============
    private void handleNasaCsvImport(Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Виберіть файл з даними (CSV)");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Файли з даними (CSV, TXT)", "*.csv", "*.txt"));

        File file = fileChooser.showOpenDialog(stage);
        if (file == null) return;

        Task<ImportResult> task = new Task<>() {
            @Override
            protected ImportResult call() throws Exception {
                Map<MetricType, Double> parsed = new EnumMap<>(MetricType.class);
                int skipped = 0;
                try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
                    String line;
                    boolean isHeader = true;
                    while ((line = reader.readLine()) != null) {
                        if (isHeader) { isHeader = false; continue; }
                        if (line.isBlank()) continue;

                        String[] tokens = line.split("[,;]");
                        if (tokens.length < 2) { skipped++; continue; }

                        String rawName = tokens[0].trim();
                        if (rawName.isEmpty()) { skipped++; continue; }
                        String key = rawName.toUpperCase().replace(' ', '_');

                        double value;
                        try {
                            value = Double.parseDouble(tokens[1].trim());
                        } catch (NumberFormatException ex) {
                            skipped++;
                            continue;
                        }
                        if (!Double.isFinite(value)) { skipped++; continue; }

                        boolean matched = false;
                        for (MetricType type : MetricType.values()) {
                            if (key.equals(type.name()) || rawName.equalsIgnoreCase(type.title)) {
                                parsed.put(type, value);
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) skipped++;
                    }
                }
                return new ImportResult(parsed, skipped);
            }
        };

        task.setOnSucceeded(e -> {
            ImportResult r = task.getValue();
            beforeAction = new EnumMap<>(currentTelemetry);
            for (Map.Entry<MetricType, Double> en : r.values().entrySet()) {
                baseTelemetry.put(en.getKey(), clamp(en.getKey(), en.getValue()));
            }
            steadyHistory.clearAll();
            recompute();
            updateAllTelemetryViews();
            addLog("Завантажено дані з файлу " + file.getName());

            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Оновлено показників: " + r.values().size()
                            + "\nПропущено рядків (не розпізнано): " + r.skipped()
                            + "\nФайл: " + file.getName(), ButtonType.OK);
            alert.initOwner(stage);
            alert.setHeaderText("Дані завантажено");
            alert.show();
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            Alert err = new Alert(Alert.AlertType.ERROR,
                    "Не вдалося прочитати файл: " + (ex != null ? ex.getMessage() : "невідома помилка"),
                    ButtonType.OK);
            err.initOwner(stage);
            err.show();
        });

        Thread thread = new Thread(task, "csv-import");
        thread.setDaemon(true);
        thread.start();
    }

    public static void main(String[] args) {
        launch(args);
    }

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
}