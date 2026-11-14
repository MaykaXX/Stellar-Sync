package nasa.spaceapps.aegis;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
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
import javafx.scene.paint.RadialGradient;
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

/**
 * Aegis-Bio: помічник здоров'я екіпажу в далекому космосі.
 * Усі дії космонавта (тренування, вода, сон, їжа...) змінюють показники,
 * а програма показує, ЩО саме змінилось і ЧОМУ.
 * Модель спрощена і призначена для демонстрації, це не медичний прилад.
 */
public class AegisBioApp extends Application {
    // --- Mesh-мережі екіпажу ---
    private CrewMeshPanel crewPanel;
    private final StressEngine stressEngine = new StressEngine();

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
    private static final double BODY_W = 640, BODY_H = 480;

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

    // Нові візуальні елементи
    private final Map<MetricType, HoloUi.RingGauge> gauges = new EnumMap<>(MetricType.class);
    private final Map<MetricType, Label> chips = new EnumMap<>(MetricType.class);
    private final Map<String, Button> quickButtons = new HashMap<>();
    private final Map<Screen, Label> navBadges = new EnumMap<>(Screen.class);
    private HoloUi.RingGauge healthGauge;
    private HoloUi.DnaHelix dnaHelix;
    private HoloUi.OrbitMap orbitMap;
    private final Label healthVerdictLabel = new Label();
    private final Label healthExplainLabel = new Label();
    private final Label assistantText = new Label();
    private final Label assistantFace = new Label("🧑‍🚀");
    private final VBox assistantBox = new VBox(8);
    private int healthScoreValue = 100;
    private final Tooltip bodyTip = new Tooltip();
    private double bodyScale = 1, bodyOffX = 0, bodyOffY = 0;
    private Hot hoverHot;

    /** Світна точка на голограмі: де вона і що про неї розповісти. */
    private record Hot(double x, double y, String title, String text) {}

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
        mainStage = primaryStage;
        SourceLinks.init(getHostServices(), primaryStage);
        initDefaultTelemetry();

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #060A12, #0A1424); "
                + "-fx-font-family: 'Segoe UI', system-ui, sans-serif;");

        root.setTop(buildTopHeader(primaryStage));
        root.setLeft(buildNavBar());
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
                boolean onHome = currentScreen == Screen.HOME;
                if (onHome) drawHolographicBody(bodyHudCanvas.getGraphicsContext2D());
                if (heartMonitor != null) {
                    boolean hw = hr > 100 || hr < 55 || currentTelemetry.get(MetricType.HRV) < 40;
                    heartMonitor.update(dt, hr, hw, onHome);
                }
                // Mesh екіпажу живе завжди (таймери, пакети), малюється лише на своєму екрані
                if (crewPanel != null) crewPanel.frame(dt, currentScreen == Screen.MESH_CREW);
                double tSec = animationPulse / 3.0;
                if (currentScreen == Screen.METRICS && dnaHelix != null) {
                    double risk = Math.min(1.0, currentTelemetry.get(MetricType.RADIATION_DOSE) / 300.0);
                    dnaHelix.render(tSec, risk, HoloUi.CYAN);
                }
                if (currentScreen == Screen.MISSION && orbitMap != null) {
                    int mode = blackoutActive ? 3 : currentLocation.ordinal();
                    orbitMap.render(tSec, mode, blackoutActive);
                }
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
        stressEngine.reset();
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
        Stage owner = (blackoutStage != null && blackoutStage.isShowing()) ? blackoutStage : mainStage;
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
        updateStress();
    }

    // ============ AEGIS MINDCARE ============
    private void updateStress() {
        stressEngine.update(new StressEngine.Reading(
                currentTelemetry.get(MetricType.HEART_RATE),
                currentTelemetry.get(MetricType.HRV),
                currentTelemetry.get(MetricType.HYDRATION),
                sleepHours), clockSec);
        if (stressEngine.shouldAlertCritical(clockSec)) {
            addLog("🧠 [MindCare] Стрес критичний і не минає. Відкриваю вікно психологічної підтримки.");
            openMindCare();
        }
    }

    private void openMindCare() {
        Stage owner = (blackoutStage != null && blackoutStage.isShowing()) ? blackoutStage : mainStage;
        MindCareWindow.open(owner, new MindCareWindow.Hooks(
                stressEngine,
                () -> clockSec,
                this::addLog,
                this::onMindCareBreathingDone,
                this::onMindCareSos));
    }

    private void onMindCareBreathingDone() {
        Activity breath = findActivity("BREATH");
        if (breath != null && blockedReason(breath) == null) applyActivity(breath, true);
    }

    private void onMindCareSos() {
        if (crewPanel != null) crewPanel.raiseCommanderSos();
        addLog("🆘 [MindCare] SOS Mental Alert надіслано в ЦУП та екіпажу.");
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
            b.setText(blocked ? "⛔ " + a.button() + " (не зараз)" : activityIcon(a.id()) + a.button());
            Button q = quickButtons.get(a.id());
            if (q != null) q.setDisable(blocked);
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

    // ============ ЕКРАНИ ТА НАВІГАЦІЯ ============
    private enum Screen {
        HOME("🏠", "Головний", "Тіло, пульс і поради"),
        METRICS("📊", "Показники", "Усі виміри по групах"),
        ACTIONS("🧭", "Що робити", "Підказки та ваші дії"),
        MEDKIT("💊", "Аптечка", "Таблетки з безпечною дозою"),
        TRENDS("📈", "Історія і прогноз", "Що було і що буде"),
        MISSION("🛰️", "Місія", "Зв'язок, дані, тести"),
        MESH_CREW("👥", "Команда (Mesh)", "P2P зв'язок та статус екіпажу"),
        SOURCES("📚", "Ризики і джерела", "Наукова основа: NASA, CSA");

        final String icon;
        final String title;
        final String hint;

        Screen(String icon, String title, String hint) {
            this.icon = icon;
            this.title = title;
            this.hint = hint;
        }
    }

    private final Map<Screen, Node> screens = new EnumMap<>(Screen.class);
    private final Map<Screen, ToggleButton> navButtons = new EnumMap<>(Screen.class);
    private final ToggleGroup navGroup = new ToggleGroup();
    private Screen currentScreen = Screen.HOME;
    private HeartMonitor heartMonitor;
    private final Button urgentBtn = new Button();
    private Stage mainStage;

    private static final String PANEL_STYLE = HoloUi.BLOCK_STYLE;
    private static final String SECTION_TITLE_STYLE =
            "-fx-text-fill: #38BDF8; -fx-font-size: 13px; -fx-font-weight: bold;";
    private static final String CARD_STYLE = HoloUi.CARD_STYLE;

    private void showScreen(Screen target) {
        currentScreen = target;
        for (Map.Entry<Screen, Node> en : screens.entrySet()) {
            en.getValue().setVisible(en.getKey() == target);
        }
        ToggleButton tb = navButtons.get(target);
        if (tb != null && !tb.isSelected()) tb.setSelected(true);
    }

    private void styleNav(ToggleButton b, boolean selected) {
        b.setStyle(selected
                ? "-fx-background-color: linear-gradient(to right, rgba(56,189,248,0.28), rgba(56,189,248,0.04)); "
                + "-fx-border-color: #38BDF8; -fx-border-width: 0 0 0 3; "
                + "-fx-background-radius: 8; -fx-border-radius: 8; -fx-cursor: hand;"
                : "-fx-background-color: transparent; -fx-border-color: transparent; -fx-border-width: 0 0 0 3; "
                + "-fx-background-radius: 8; -fx-cursor: hand;");
    }

    private VBox buildNavBar() {
        VBox nav = new VBox(6);
        nav.setPadding(new Insets(16, 10, 16, 10));
        nav.setPrefWidth(214);
        nav.setMinWidth(214);
        nav.setStyle("-fx-background-color: #0B111E; -fx-border-color: #1E293B; -fx-border-width: 0 1 0 0;");

        Label menu = new Label("МЕНЮ");
        menu.setStyle("-fx-text-fill: #475569; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 0 0 4 6;");
        nav.getChildren().add(menu);

        for (Screen sc : Screen.values()) {
            Label ic = new Label(sc.icon);
            ic.setMinWidth(30);
            ic.setStyle("-fx-font-size: 20px;");
            Label tt = new Label(sc.title);
            tt.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 13px; -fx-font-weight: bold;");
            Label hh = new Label(sc.hint);
            hh.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px;");
            VBox texts = new VBox(1, tt, hh);
            HBox.setHgrow(texts, Priority.ALWAYS);

            Label badge = new Label();
            badge.managedProperty().bind(badge.visibleProperty());
            badge.setVisible(false);
            badge.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-size: 10px; "
                    + "-fx-font-weight: bold; -fx-padding: 1 6 1 6; -fx-background-radius: 8;");
            navBadges.put(sc, badge);

            HBox row = new HBox(8, ic, texts, badge);
            row.setAlignment(Pos.CENTER_LEFT);

            ToggleButton b = new ToggleButton();
            b.setGraphic(row);
            b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            b.setToggleGroup(navGroup);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setPadding(new Insets(8, 10, 8, 10));
            styleNav(b, false);
            b.selectedProperty().addListener((o, was, is) -> styleNav(b, is));
            b.setOnAction(e -> showScreen(sc));
            navButtons.put(sc, b);
            nav.getChildren().add(b);
        }
        // Не дозволяємо «зняти вибір» повторним натисканням
        navGroup.selectedToggleProperty().addListener((o, oldT, newT) -> {
            if (newT == null && oldT != null) oldT.setSelected(true);
        });

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label legendTitle = new Label("ЩО ОЗНАЧАЮТЬ КОЛЬОРИ");
        legendTitle.setStyle("-fx-text-fill: #475569; -fx-font-size: 10px; -fx-font-weight: bold;");
        VBox legend = new VBox(5, legendTitle,
                HoloUi.chip("● Усе гаразд", HoloUi.GREEN),
                HoloUi.chip("● Потрібна увага", HoloUi.AMBER),
                HoloUi.chip("● Небезпека", HoloUi.RED));
        legend.setPadding(new Insets(10, 6, 0, 6));
        nav.getChildren().addAll(spacer, legend);
        return nav;
    }

    /** Червона кнопка у верхній панелі та лічильник на пункті «Що робити». */
    private void updateAlertIndicators() {
        int urgent = 0;
        int todo = 0;
        for (ClinicalProtocol p : activeProtocols) {
            if ("CRITICAL".equals(p.urgency()) || "HIGH".equals(p.urgency())) urgent++;
            if (!"NOMINAL".equals(p.urgency()) && !"FORECAST".equals(p.urgency())) todo++;
        }
        Label badge = navBadges.get(Screen.ACTIONS);
        if (badge != null) {
            badge.setText(String.valueOf(todo));
            badge.setVisible(todo > 0);
        }
        urgentBtn.setVisible(urgent > 0);
        urgentBtn.setText("⚠ ТЕРМІНОВО: " + urgent + ". Відкрити підказки");
    }

    /** Порадник на головному екрані: одна проста фраза про те, що важливо зараз. */
    private void updateAssistant() {
        ClinicalProtocol top = null;
        ClinicalProtocol forecast = null;
        for (ClinicalProtocol p : activeProtocols) {
            if ("NOMINAL".equals(p.urgency())) continue;
            if ("FORECAST".equals(p.urgency())) {
                if (forecast == null) forecast = p;
                continue;
            }
            if (top == null || rank(p.urgency()) < rank(top.urgency())) top = p;
        }

        String face;
        String msg;
        Color c;
        if (blackoutActive) {
            face = "📡";
            c = HoloUi.PURPLE;
            msg = "Зв'язку із Землею немає. Програма працює сама: виконуйте підказки на вкладці «Що робити».";
        } else if (top != null && "CRITICAL".equals(top.urgency())) {
            face = "🚨";
            c = HoloUi.RED;
            msg = "Терміново! " + top.title() + ". Відкрийте «Що робити»: там розписано кроки.";
        } else if (top != null && "HIGH".equals(top.urgency())) {
            face = "⚠️";
            c = HoloUi.ORANGE;
            msg = "Потрібна увага: " + top.title() + ". Краще зробити це найближчим часом.";
        } else if (top != null) {
            face = "🤔";
            c = HoloUi.AMBER;
            msg = "Невелике відхилення: " + top.title() + ". Подивіться підказку, це легко виправити.";
        } else if (forecast != null) {
            face = "🔮";
            c = HoloUi.CYAN;
            msg = "Зараз усе гаразд, але прогноз застерігає: " + forecast.title() + ".";
        } else if (hydrationLiters < 1.4) {
            face = "💧";
            c = HoloUi.GREEN;
            msg = String.format(Locale.US, "Усе гаразд. Але води випито лише %.1f з 2.8 л. Не забувайте пити.", hydrationLiters);
        } else if (countermeasureMinutes < 90) {
            face = "🏋️";
            c = HoloUi.GREEN;
            msg = "Усе гаразд. Не пропустіть тренування: у невагомості воно захищає кістки й очі.";
        } else {
            face = "😊";
            c = HoloUi.GREEN;
            msg = "Усе чудово! Показники в нормі. Так тримати.";
        }
        assistantFace.setText(face);
        assistantText.setText(msg);
        assistantBox.setStyle("-fx-background-color: " + HoloUi.rgba(c, 0.10) + "; -fx-border-color: "
                + HoloUi.hex(c) + "; -fx-border-radius: 10; -fx-background-radius: 10;");
    }

    // ============ ВЕРХНЯ ПАНЕЛЬ (годинник видно на всіх екранах) ============
    private HBox buildTopHeader(Stage stage) {
        HBox header = new HBox(18);
        header.setPadding(new Insets(10, 24, 10, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #0F1626; -fx-border-color: #1E293B; -fx-border-width: 0 0 1 0;");

        Label logoTitle = new Label("◈ AEGIS-BIO");
        logoTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 20px; -fx-font-weight: 900; "
                + "-fx-effect: dropshadow(gaussian, rgba(56,189,248,0.8), 12, 0.25, 0, 0);");

        Label clockCaption = new Label("ЧАС МІСІЇ (UTC)");
        clockCaption.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px; -fx-font-weight: bold;");
        bigClockLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 28px; -fx-font-weight: 900; "
                + "-fx-font-family: 'Consolas', 'Courier New', monospace;");
        simClockLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");
        VBox clockBox = new VBox(1, clockCaption, bigClockLabel, simClockLabel);
        clockBox.setAlignment(Pos.CENTER);
        clockBox.setPadding(new Insets(4, 18, 4, 18));
        clockBox.setStyle(CARD_STYLE);
        updateClock();

        urgentBtn.setStyle("-fx-background-color: #B91C1C; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 4;");
        urgentBtn.managedProperty().bind(urgentBtn.visibleProperty());
        urgentBtn.setVisible(false);
        urgentBtn.setOnAction(e -> showScreen(Screen.ACTIONS));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

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

        header.getChildren().addAll(logoTitle, clockBox, urgentBtn, spacer, speedLabel, speedBox);
        return header;
    }

    // ============ ГОЛОВНИЙ КОНТЕЙНЕР ============
    private ScrollPane scroll(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        return sp;
    }

    private GridPane twoColumnGrid(double gap) {
        GridPane g = new GridPane();
        g.setHgap(gap);
        g.setVgap(gap);
        ColumnConstraints a = new ColumnConstraints();
        a.setPercentWidth(50);
        ColumnConstraints b = new ColumnConstraints();
        b.setPercentWidth(50);
        g.getColumnConstraints().addAll(a, b);
        return g;
    }

    private StackPane buildMainDashboard() {
        StackPane stack = new StackPane();
        stack.setPadding(new Insets(16));

        screens.put(Screen.HOME, buildHomeScreen());
        screens.put(Screen.METRICS, buildMetricsScreen());
        screens.put(Screen.ACTIONS, buildActionsScreen());
        screens.put(Screen.MEDKIT, buildMedkitScreen());
        screens.put(Screen.TRENDS, buildTrendsScreen());
        screens.put(Screen.MISSION, buildMissionScreen());
        screens.put(Screen.MESH_CREW, buildCrewMeshScreen());
        screens.put(Screen.SOURCES, scroll(new SourcesPanel()));

        for (Node n : screens.values()) stack.getChildren().add(n);


        showScreen(Screen.HOME);
        updateAllTelemetryViews();
        return stack;
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

    // ============ ДОПОМІЖНІ ПІДКАЗКИ ДЛЯ КНОПОК ============
    private String activityIcon(String id) {
        return switch (id) {
            case "STRENGTH" -> "🏋️ ";
            case "CARDIO" -> "🏃 ";
            case "WATER" -> "💧 ";
            case "SLEEP" -> "😴 ";
            case "MEAL" -> "🍽️ ";
            case "BREATH" -> "🌬️ ";
            case "LBNP" -> "🦵 ";
            default -> "";
        };
    }

    /** Спливаюча підказка: що саме змінить ця дія і чому. */
    private Tooltip activityTooltip(Activity a) {
        StringBuilder sb = new StringBuilder("Що зміниться:");
        for (Effect e : a.effects()) {
            sb.append('\n').append(e.delta() > 0 ? "▲ " : "▼ ").append(e.metric().title)
                    .append(": ").append(e.why());
        }
        Tooltip t = new Tooltip(sb.toString());
        t.setWrapText(true);
        t.setMaxWidth(360);
        t.setShowDelay(Duration.millis(80));
        t.setShowDuration(Duration.seconds(40));
        t.setStyle("-fx-font-size: 12px;");
        return t;
    }

    private List<Hot> bodyHotspots() {
        double cx = BODY_W / 2;
        double hy = 62;
        List<Hot> l = new ArrayList<>();
        l.add(hotFor(cx, hy - 3, MetricType.ICP_OPTIC));
        l.add(hotFor(cx + 16, hy + 68, MetricType.SPO2));
        l.add(new Hot(cx, hy + 135, "Хребет у невагомості",
                "Без ваги хребет розтягується приблизно на " + SPINE_ELONGATION_CM + " см. "
                        + "Це нормально, але може боліти спина. Допомагають силові тренування."));
        l.add(hotFor(cx + 40, hy + 150, MetricType.RADIATION_DOSE));
        l.add(hotFor(cx, hy + 75, MetricType.HEART_RATE));
        l.add(hotFor(cx - 14, hy + 100, MetricType.HRV));
        l.add(hotFor(cx, hy + 160, MetricType.HYDRATION));
        l.add(hotFor(cx - 26, hy + 285, MetricType.BONE_DENSITY));
        return l;
    }

    private Hot hotFor(double x, double y, MetricType type) {
        double v = currentTelemetry.get(type);
        String st = inRange(type, v) ? "у нормі" : "поза нормою";
        return new Hot(x, y,
                String.format(Locale.US, "%s: %.1f %s (%s)", type.title, v, type.unit, st),
                metricHint(type) + "\nНорма: " + HoloUi.num(type.minNominal) + "–"
                        + HoloUi.num(type.maxNominal) + " " + type.unit);
    }

    private void onBodyHover(double mx, double my, double screenX, double screenY) {
        double lx = (mx - bodyOffX) / bodyScale;
        double ly = (my - bodyOffY) / bodyScale;
        Hot best = null;
        double bestDist = 24;
        for (Hot h : bodyHotspots()) {
            double d = Math.hypot(h.x() - lx, h.y() - ly);
            if (d < bestDist) {
                bestDist = d;
                best = h;
            }
        }
        hoverHot = best;
        if (best == null) {
            bodyTip.hide();
            bodyHudCanvas.setCursor(Cursor.DEFAULT);
            return;
        }
        bodyHudCanvas.setCursor(Cursor.HAND);
        bodyTip.setText(best.title() + "\n\n" + best.text());
        bodyTip.show(bodyHudCanvas, screenX + 16, screenY + 16);
    }

    // ============ ЕКРАН 1: ГОЛОВНИЙ (голограма, порадник, серцебиття) ============
    private Node buildHomeScreen() {
        // Підказка для новачка (її можна закрити)
        Label tipText = new Label("👋 Як користуватись: 1) наведіть курсор на світну точку космонавта, "
                + "щоб дізнатися, що вона означає; 2) читайте «Порадника» праворуч; "
                + "3) коли щось не так, відкрийте «Що робити» і натискайте кнопки.");
        tipText.setWrapText(true);
        tipText.setStyle("-fx-text-fill: #BAE6FD; -fx-font-size: 12px;");
        Button closeTip = new Button("✕");
        closeTip.setStyle("-fx-background-color: transparent; -fx-text-fill: #7DD3FC; -fx-cursor: hand;");
        HBox tipBanner = new HBox(10, tipText, closeTip);
        HBox.setHgrow(tipText, Priority.ALWAYS);
        tipBanner.setAlignment(Pos.CENTER_LEFT);
        tipBanner.setPadding(new Insets(8, 12, 8, 14));
        tipBanner.setStyle("-fx-background-color: rgba(56,189,248,0.10); -fx-border-color: #0C4A6E; "
                + "-fx-border-radius: 10; -fx-background-radius: 10;");
        tipBanner.managedProperty().bind(tipBanner.visibleProperty());
        closeTip.setOnAction(e -> tipBanner.setVisible(false));

        // Ліва частина: голограма
        bodyHudCanvas = new Canvas(BODY_W, BODY_H);
        bodyHudCanvas.setManaged(false);
        Pane canvasHolder = new Pane(bodyHudCanvas);
        canvasHolder.setMinSize(0, 0);
        bodyHudCanvas.widthProperty().bind(canvasHolder.widthProperty());
        bodyHudCanvas.heightProperty().bind(canvasHolder.heightProperty());
        VBox.setVgrow(canvasHolder, Priority.ALWAYS);

        bodyTip.setWrapText(true);
        bodyTip.setMaxWidth(320);
        bodyTip.setStyle("-fx-font-size: 12px;");
        bodyHudCanvas.setOnMouseMoved(e -> onBodyHover(e.getX(), e.getY(), e.getScreenX(), e.getScreenY()));
        bodyHudCanvas.setOnMouseExited(e -> {
            bodyTip.hide();
            hoverHot = null;
        });

        VBox bodyBlock = HoloUi.block("🧑‍🚀", "Голограма екіпажу",
                "Світні точки на тілі показують живі дані вашого організму",
                "Кожна світна точка: частина тіла, за якою стежить програма. Наведіть на неї курсор: "
                        + "з'явиться пояснення простими словами та межі норми. "
                        + "Колір і сяйво навколо голограми змінюються, коли стан погіршується.",
                canvasHolder);
        HBox.setHgrow(bodyBlock, Priority.ALWAYS);
        bodyBlock.setMinWidth(0);

        // Права частина: загальний стан
        healthGauge = new HoloUi.RingGauge(124);
        healthVerdictLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 20px; -fx-font-weight: bold;");
        healthExplainLabel.setWrapText(true);
        healthExplainLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 12px;");
        VBox healthText = new VBox(4, healthVerdictLabel, healthExplainLabel);
        healthText.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(healthText, Priority.ALWAYS);
        HBox healthRow = new HBox(14, healthGauge, healthText);
        healthRow.setAlignment(Pos.CENTER_LEFT);
        VBox healthBlock = HoloUi.block("🛡️", "Загальний стан", "Оцінка від 0 до 100",
                "Програма перевіряє 10 показників. Оцінка = скільки з них зараз у нормі. "
                        + "90 і більше: чудово. 70-89: добре. 50-69: потрібна увага. Менше 50: небезпечно.",
                healthRow);

        // Порадник
        assistantFace.setStyle("-fx-font-size: 32px;");
        assistantText.setWrapText(true);
        assistantText.setMaxWidth(Double.MAX_VALUE);
        assistantText.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 13px;");
        HBox bubble = new HBox(12, assistantFace, assistantText);
        bubble.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(assistantText, Priority.ALWAYS);
        Button goActions = new Button("Відкрити «Що робити» →");
        goActions.setMaxWidth(Double.MAX_VALUE);
        goActions.setStyle("-fx-background-color: #0C4A6E; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-background-radius: 8; -fx-cursor: hand;");
        goActions.setOnAction(e -> showScreen(Screen.ACTIONS));
        assistantBox.getChildren().setAll(bubble, goActions);
        assistantBox.setPadding(new Insets(12));
        VBox assistBlock = HoloUi.block("💬", "Порадник", "Що важливо саме зараз",
                "Порадник читає всі показники й каже одну головну річ. Колір рамки: зелений: усе добре, "
                        + "жовтий: легке відхилення, оранжевий: потрібна увага, червоний: терміново.",
                assistantBox);

        // Швидкі дії
        FlowPane quick = new FlowPane(8, 8);
        String[][] quickDefs = {{"WATER", "💧 Випити води"}, {"BREATH", "🌬️ Подихати 10 хв"}, {"MEAL", "🍽️ Поїсти"}};
        for (String[] d : quickDefs) {
            Activity a = findActivity(d[0]);
            if (a == null) continue;
            Button b = new Button(d[1]);
            b.setStyle("-fx-background-color: " + a.color() + "; -fx-text-fill: white; -fx-font-size: 12px; "
                    + "-fx-background-radius: 8; -fx-cursor: hand;");
            b.setTooltip(activityTooltip(a));
            b.setOnAction(e -> {
                applyActivity(a);
                addLogIfBlockedHint(a);
            });
            quickButtons.put(a.id(), b);
            quick.getChildren().add(b);
        }
        Button more = new Button("Усі дії →");
        more.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #CBD5E1; -fx-font-size: 12px; "
                + "-fx-background-radius: 8; -fx-cursor: hand;");
        more.setOnAction(e -> showScreen(Screen.ACTIONS));
        quick.getChildren().add(more);
        VBox quickBlock = HoloUi.block("⚡", "Швидкі дії", "Найчастіші кроки в один дотик",
                "Наведіть курсор на кнопку, щоб побачити, що саме вона змінить в організмі. "
                        + "Повний список дій на вкладці «Що робити».", quick);

        // Серцебиття
        heartMonitor = new HeartMonitor();
        heartMonitor.setManaged(false);
        Pane hrHolder = new Pane(heartMonitor);
        hrHolder.setMinHeight(120);
        hrHolder.setPrefHeight(120);
        hrHolder.setMaxHeight(120);
        heartMonitor.widthProperty().bind(hrHolder.widthProperty());
        heartMonitor.heightProperty().bind(hrHolder.heightProperty());
        VBox heartBlock = HoloUi.block("💓", "Серцебиття", "Лінія біжить із вашою справжньою частотою пульсу",
                "Блакитна лінія: серце працює спокійно. Помаранчева: пульс або стрес-баланс поза нормою. "
                        + "Високі зубці: самі удари серця.", hrHolder);

        VBox right = new VBox(12, healthBlock, assistBlock, quickBlock, heartBlock);
        right.setPadding(new Insets(0, 4, 0, 0));
        ScrollPane rightScroll = scroll(right);
        rightScroll.setPrefWidth(420);
        rightScroll.setMinWidth(380);
        rightScroll.setMaxWidth(440);

        HBox columns = new HBox(14, bodyBlock, rightScroll);
        VBox page = new VBox(10, tipBanner, columns);
        VBox.setVgrow(columns, Priority.ALWAYS);
        return page;
    }

    /** Якщо швидку дію зараз не можна виконати, пояснюємо чому (вкладка «Що робити» цього не видно з головного). */
    private void addLogIfBlockedHint(Activity a) {
        String reason = blockedReason(a);
        if (reason != null) addLog("ℹ " + reason);
    }

    // ============ ЕКРАН 2: ПОКАЗНИКИ (по логічних групах) ============
    private VBox metricCard(MetricType type) {
        HoloUi.RingGauge gauge = new HoloUi.RingGauge(100);
        gauges.put(type, gauge);

        Label name = new Label(type.title);
        name.setWrapText(true);
        name.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 13px; -fx-font-weight: bold;");

        Label chip = new Label();
        chips.put(type, chip);
        HoloUi.styleChip(chip, "...", HoloUi.CYAN);

        Label deltaLbl = new Label();
        deltaLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        deltaLabels.put(type, deltaLbl);

        HBox statusRow = new HBox(8, chip, deltaLbl);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        Label range = new Label("Норма: " + HoloUi.num(type.minNominal) + "–"
                + HoloUi.num(type.maxNominal) + " " + type.unit);
        range.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px;");

        Label hint = new Label(metricHint(type));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        VBox texts = new VBox(4, name, statusRow, range, hint);
        SpaceSources.Risk nasaRisk = SpaceSources.forMetric(type.name());
        if (nasaRisk != null) {
            Hyperlink src = SourceLinks.link("📎 Джерело NASA ↗", nasaRisk.source().url());
            src.setTooltip(new Tooltip("NASA HRP: " + nasaRisk.title() + "\n" + nasaRisk.source().url()
                    + "\nКлік відкриє сторінку в браузері"));
            texts.getChildren().add(src);
        }
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(12, gauge, texts);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(row);
        card.setPadding(new Insets(10));
        card.setPrefWidth(400);
        card.setMinWidth(320);
        card.setStyle(CARD_STYLE);
        return card;
    }

    private VBox metricGroup(String icon, String title, String subtitle, String help, MetricType... types) {
        FlowPane flow = new FlowPane(12, 12);
        for (MetricType t : types) flow.getChildren().add(metricCard(t));
        return HoloUi.block(icon, title, subtitle, help, flow);
    }

    private Node buildMetricsScreen() {
        healthScoreLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 14px; -fx-font-weight: bold;");
        healthBar.setMaxWidth(Double.MAX_VALUE);
        HBox legend = new HBox(8,
                HoloUi.chip("● Норма", HoloUi.GREEN),
                HoloUi.chip("● Тимчасово поза нормою", HoloUi.AMBER),
                HoloUi.chip("● Поза нормою", HoloUi.RED));
        legend.setAlignment(Pos.CENTER_LEFT);
        Label legendNote = new Label("Кільце показує, де зараз ваш показник. Тонка зелена дуга зовні кільця: "
                + "безпечна зона. Стрілки ▲▼ показують, як показник змінився після вашої останньої дії.");
        legendNote.setWrapText(true);
        legendNote.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
        VBox summary = HoloUi.block("📊", "Загальна оцінка", "Скільки показників зараз у нормі",
                "Усі 10 показників розкладено нижче на три групи. Якщо щось не в нормі, "
                        + "відкрийте вкладку «Що робити».",
                healthScoreLabel, healthBar, legend, legendNote);

        VBox heart = metricGroup("❤️", "Серце і судини",
                "Як працює серце і чи не перевантажена кров'яна система",
                "Серце в невагомості працює інакше: кров не тягне вниз, тому пульс і тиск можуть «стрибати».",
                MetricType.HEART_RATE, MetricType.HRV, MetricType.BLOOD_PRESSURE);
        VBox breath = metricGroup("🫁", "Дихання та обмін речовин",
                "Кисень, цукор, вода й температура: паливо для організму",
                "Ці показники найшвидше реагують на їжу, воду, тренування та сон.",
                MetricType.SPO2, MetricType.BLOOD_GLUCOSE, MetricType.BODY_TEMP, MetricType.HYDRATION);
        VBox space = metricGroup("🛰️", "Вплив космосу",
                "Те, що в польоті змінюється найсильніше: очі, радіація, кістки",
                "Це специфічні космічні ризики. Вони повільні, тому їх важливо ловити заздалегідь.",
                MetricType.ICP_OPTIC, MetricType.RADIATION_DOSE, MetricType.BONE_DENSITY);

        dnaHelix = new HoloUi.DnaHelix();
        Label dnaText = new Label("Так радіація б'є по ДНК. Чим більша накопичена доза, тим більше «сходинок» "
                + "спіралі червоніють: це місця можливих пошкоджень. Доза не зникає, тому її важливо не пропустити.");
        dnaText.setWrapText(true);
        dnaText.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");
        HBox.setHgrow(dnaText, Priority.ALWAYS);
        HBox dnaRow = new HBox(14, dnaHelix, dnaText);
        dnaRow.setAlignment(Pos.CENTER_LEFT);
        dnaRow.setPadding(new Insets(8));
        dnaRow.setStyle(CARD_STYLE);
        space.getChildren().add(dnaRow);

        Label csaNote = new Label("На МКС близько " + Math.round(SpaceSources.NEUTRON_SHARE_ISS * 100) + "% радіації дають нейтрони. Вони особливо шкідливі, тож Канада "
                + "розробляє прилад CANS, який вимірює їх автономно. Це дані для МКС: у польоті до Марса частка може бути іншою.");
        csaNote.setWrapText(true);
        csaNote.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");
        VBox csaBox = new VBox(4, csaNote, SourceLinks.row("", SpaceSources.CSA_RADIATION));
        csaBox.setPadding(new Insets(8));
        csaBox.setStyle(CARD_STYLE);
        space.getChildren().add(csaBox);

        VBox root = new VBox(14, summary, heart, breath, space);
        root.setPadding(new Insets(2, 4, 2, 2));
        return scroll(root);
    }

    // ============ ЕКРАН 3: ЩО РОБИТИ (підказки, дії по групах, вплив, журнал) ============
    private VBox activityGroup(String icon, String title, String subtitle, String help, String... ids) {
        VBox btns = new VBox(8);
        for (String id : ids) {
            Activity a = findActivity(id);
            if (a == null) continue;
            Button b = new Button(activityIcon(id) + a.button());
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setWrapText(true);
            b.setStyle("-fx-background-color: " + a.color() + "; -fx-text-fill: white; -fx-font-size: 12px; "
                    + "-fx-padding: 8 12 8 12; -fx-background-radius: 8; -fx-cursor: hand;");
            b.setTooltip(activityTooltip(a));
            b.setOnAction(e -> applyActivity(a));
            activityButtons.put(a.id(), b);
            btns.getChildren().add(b);
        }
        return HoloUi.block(icon, title, subtitle, help, btns);
    }

    private Node buildActionsScreen() {
        // Ліва колонка: підказки
        autoPlanBtn.setMaxWidth(Double.MAX_VALUE);
        autoPlanBtn.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-size: 13px; "
                + "-fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 10; -fx-background-radius: 8;");
        autoPlanBtn.managedProperty().bind(autoPlanBtn.visibleProperty());
        autoPlanBtn.setOnAction(e -> {
            for (String id : recommendedActionIds()) {
                Activity a = findActivity(id);
                if (a != null) applyActivity(a);
            }
        });
        updateAutoPlanButton();

        VBox left = HoloUi.block("🧭", "Що робити зараз", "Підказки лікаря-автопілота, від найважливішого",
                "Програма сама перевіряє показники й складає список кроків. Червоні картки: терміново. "
                        + "Кнопка «Зробити зараз» виконує крок одразу. Зелена кнопка зверху виконує все рекомендоване.",
                autoPlanBtn, protocolContainer);

        // Права колонка: ваш день і дії по групах
        workoutLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");
        workoutBar.setMaxWidth(Double.MAX_VALUE);
        workoutBar.setStyle("-fx-accent: #10B981;");
        hydrationLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");
        hydrationBar.setMaxWidth(Double.MAX_VALUE);
        hydrationBar.setStyle("-fx-accent: #06B6D4;");
        sleepLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");
        sleepBar.setMaxWidth(Double.MAX_VALUE);
        sleepBar.setStyle("-fx-accent: #6366F1;");
        VBox day = HoloUi.block("📅", "Ваш день", "Три щоденні цілі екіпажу",
                "NASA радить космонавтам близько 2.5 години тренувань на добу, достатньо води й 8 годин сну. "
                        + "Смужки показують, скільки вже зроблено.",
                workoutLabel, workoutBar, hydrationLabel, hydrationBar, sleepLabel, sleepBar);

        VBox move = activityGroup("🏋️", "Рух і тренування", "Проти слабких кісток, м'язів і тиску на очі",
                "Наведіть курсор на кнопку, щоб побачити, що вона змінить і чому.",
                "STRENGTH", "CARDIO", "LBNP");
        VBox food = activityGroup("💧", "Вода та їжа", "Паливо й рідина для організму",
                "Вода потрібна серцю та голові. Їжа піднімає цукор у крові на 1-2 години.",
                "WATER", "MEAL");
        VBox rest = activityGroup("😴", "Відпочинок і спокій", "Щоб серце та нерви відновились",
                "Сон і повільне дихання покращують стрес-баланс серця (HRV).",
                "SLEEP", "BREATH");

        Label driftNote = new Label("Без тренувань і сеансів LBNP стан у невагомості поступово погіршується: "
                + "тиск на очі росте, кістки слабшають, а вода потроху витрачається. "
                + "Таблетки шукайте на екрані «Аптечка».");
        driftNote.setWrapText(true);
        driftNote.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");

        derivedLabel.setWrapText(true);
        derivedLabel.setMaxWidth(Double.MAX_VALUE);
        derivedLabel.setStyle("-fx-text-fill: #FDE68A; -fx-font-size: 12px; -fx-background-color: #3B2A07; "
                + "-fx-padding: 8; -fx-background-radius: 6;");
        derivedLabel.managedProperty().bind(derivedLabel.visibleProperty());
        derivedLabel.setVisible(!derivedNotes.isEmpty());

        effectsBox.setPadding(new Insets(10));
        effectsBox.setStyle(CARD_STYLE);
        showEffectsPlaceholder();
        VBox effectsBlock = HoloUi.block("🔬", "Як це вплинуло на здоров'я", "Після кожної дії тут з'явиться пояснення",
                null, derivedLabel, effectsBox);

        Label emptyLog = new Label("Поки що нічого не зроблено.");
        emptyLog.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
        logBox.getChildren().add(emptyLog);
        VBox logBlock = HoloUi.block("📜", "Журнал дій", "Останні 12 записів", null, logBox);

        updateHabitViews();
        VBox right = new VBox(12, day, move, food, rest, driftNote, effectsBlock, logBlock);
        right.setPadding(new Insets(0, 4, 0, 0));

        GridPane grid = twoColumnGrid(14);
        RowConstraints row = new RowConstraints();
        row.setVgrow(Priority.ALWAYS);
        grid.getRowConstraints().add(row);
        grid.add(scroll(new VBox(left)), 0, 0);
        grid.add(scroll(right), 1, 0);
        return grid;
    }

    // ============ ЕКРАН 4: АПТЕЧКА ============
    private Node buildMedkitScreen() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        box.setStyle(PANEL_STYLE);

        Label pillsTitle = new Label("АПТЕЧКА: ТАБЛЕТКИ (дозу рахує програма)");
        pillsTitle.setStyle(SECTION_TITLE_STYLE);

        Label pillsNote = new Label("Доза залежить від того, наскільки показник поза нормою. "
                + "Програма не дозволяє перевищити дозу за один раз, денний максимум і мінімальну перерву між прийомами. "
                + "Це демонстраційна модель: реальні дози призначає лікар місії.");
        pillsNote.setWrapText(true);
        pillsNote.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        GridPane grid = twoColumnGrid(12);
        int i = 0;
        for (Pill p : buildPills()) {
            Label pillName = new Label(p.name());
            pillName.setWrapText(true);
            pillName.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 12px; -fx-font-weight: bold;");
            Label pillPurpose = new Label(p.purpose());
            pillPurpose.setWrapText(true);
            pillPurpose.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");
            Label pillStatus = new Label();
            pillStatus.setWrapText(true);
            pillStatus.setStyle("-fx-text-fill: #FDE68A; -fx-font-size: 11px;");
            Button pillBtn = new Button();
            pillBtn.setMaxWidth(Double.MAX_VALUE);
            pillBtn.setWrapText(true);
            pillBtn.setAlignment(Pos.CENTER_LEFT);
            pillBtn.setStyle("-fx-background-color: " + p.color() + "; -fx-text-fill: white; -fx-font-size: 12px; -fx-cursor: hand;");
            pillBtn.setOnAction(e -> applyPill(p));
            pillLabels.put(p.id(), pillStatus);
            pillButtons.put(p.id(), pillBtn);

            VBox row = new VBox(5, pillName, pillPurpose, pillStatus, pillBtn);
            row.setPadding(new Insets(10));
            row.setStyle(CARD_STYLE);
            grid.add(row, i % 2, i / 2);
            i++;
        }
        updatePillViews();

        box.getChildren().addAll(pillsTitle, pillsNote, grid);
        return scroll(box);
    }

    // ============ ЕКРАН 5: ІСТОРІЯ І ПРОГНОЗ ============
    private Node buildTrendsScreen() {
        VBox trend = buildTrendSection();
        VBox.setVgrow(trend, Priority.ALWAYS);
        VBox box = HoloUi.block("📈", "Історія і прогноз", "Як змінювався показник і що буде далі",
                "Блакитна лінія: ваш показник. Зелений пунктир: межі норми. Програма продовжує лінію вперед "
                        + "і попереджає за кілька годин, якщо показник збирається вийти за норму.",
                trend);
        return box;
    }

    // ============ ЕКРАН 6: МІСІЯ ============
    private Node buildMissionScreen() {
        Label locLabel = new Label("Де ми зараз:");
        locLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px; -fx-font-weight: bold;");

        locationBox = new ComboBox<>();
        locationBox.getItems().addAll(MissionLocation.values());
        locationBox.setValue(currentLocation);
        locationBox.setMaxWidth(Double.MAX_VALUE);
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

        orbitMap = new HoloUi.OrbitMap();
        Pane orbitHolder = HoloUi.fill(orbitMap, 230);
        orbitHolder.setStyle("-fx-background-color: #070C16; -fx-background-radius: 10; "
                + "-fx-border-color: #1E3A5F; -fx-border-radius: 10;");

        latencyNoticeLabel.setWrapText(true);
        latencyNoticeLabel.setMaxWidth(Double.MAX_VALUE);
        updateLatencyDisplay();

        VBox locBlock = HoloUi.block("🛰️", "Де ми та зв'язок із Землею", "Оберіть місце польоту: схема покаже сигнал",
                "Зелені точки: сигнал, що летить до Землі. Чим далі корабель, тим довше сигнал іде, "
                        + "тому лікар на Землі відповідає пізніше. У «Сонячному з'єднанні» Сонце блокує зв'язок.",
                locLabel, locationBox, orbitHolder, latencyNoticeLabel,
                SourceLinks.row("Основа:", SpaceSources.hazard("D").source()));

        Button importBtn = new Button("📂 Завантажити дані з файлу (CSV)");
        importBtn.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        importBtn.setTooltip(new Tooltip("Зчитує файл CSV із показниками (формат NASA OSDR) і оновлює екран."));
        importBtn.setOnAction(e -> handleNasaCsvImport(mainStage));

        Button reportBtn = new Button("💾 Зберегти звіт (TXT)");
        reportBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        reportBtn.setTooltip(new Tooltip("Записує у файл усі показники й поточні підказки: можна передати лікарю."));
        reportBtn.setOnAction(e -> saveReport(mainStage));

        Button blackoutBtn = new Button("🛡️ Режим Блекауту");
        blackoutBtn.setStyle("-fx-background-color: #581C87; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        blackoutBtn.setTooltip(new Tooltip("Коли зв'язку немає, штучний інтелект сам піклується про екіпаж."));
        blackoutBtn.setOnAction(e -> openBlackoutVaultWindow(mainStage));

        FlowPane dataRow = new FlowPane(10, 10);
        dataRow.getChildren().addAll(importBtn, reportBtn, blackoutBtn);
        VBox dataBlock = HoloUi.block("📂", "Дані, звіти та автономний режим", "Імпорт, збереження та режим без зв'язку",
                "Імпорт читає готові дані з файлу. Звіт можна зберегти й показати лікарю. "
                        + "Блекаут: режим, коли Земля недоступна.", dataRow);

        Button mindCareBtn = new Button("🧠 Психологічна підтримка (MindCare)");
        mindCareBtn.setStyle("-fx-background-color: #0D9488; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        mindCareBtn.setTooltip(new Tooltip("Дихання, чек-ін настрою, вправи й аварійний сигнал SOS."));
        mindCareBtn.setOnAction(e -> openMindCare());

        VBox mindBlock = HoloUi.block("🧠", "Психологічна підтримка", "Дихання, настрій і допомога колезі",
                "Окреме вікно: дихальна вправа 4-4-4-4, чек-ін настрою, вправа «5-4-3-2-1», щоденні квести "
                        + "й кнопка SOS. Усе працює без зв'язку із Землею. Це не заміна психолога.",
                mindCareBtn);

        Button sansTrigger = new Button("👁 Тест: тиск на очі");
        sansTrigger.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-size: 12px; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        sansTrigger.setOnAction(e -> simulateSansDegradation());

        Button radiationSpike = new Button("☀ Тест: сонячний спалах");
        radiationSpike.setStyle("-fx-background-color: #EA580C; -fx-text-fill: white; -fx-font-size: 12px; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        radiationSpike.setOnAction(e -> simulateRadiationEvent());

        Button resetBtn = new Button("↺ Скинути до норми");
        resetBtn.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #CBD5E1; -fx-font-size: 12px; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        resetBtn.setOnAction(e -> resetAll());

        FlowPane testRow = new FlowPane(10, 10);
        testRow.getChildren().addAll(sansTrigger, radiationSpike, resetBtn);
        VBox testBlock = HoloUi.block("🧪", "Тестові ситуації", "Щоб побачити, як працюють підказки",
                "Кнопки створюють штучну проблему (наприклад, сонячний спалах). "
                        + "Після цього перейдіть на «Головний» або «Що робити» і подивіться, як реагує програма.",
                testRow);

        VBox box = new VBox(14, locBlock, dataBlock, mindBlock, testBlock);
        box.setPadding(new Insets(2, 4, 2, 2));
        return scroll(box);
    }

    private Node buildCrewMeshScreen() {
        crewPanel = new CrewMeshPanel(CrewMeshNetwork.createDefaultCrew(), this::addLog, count -> {
            Label badge = navBadges.get(Screen.MESH_CREW);
            if (badge != null) {
                badge.setText("🚨 " + count);
                badge.setVisible(count > 0);
            }
        });
        return scroll(crewPanel);
    }

    // ============ НИЖНІЙ РЯДОК ============
    private HBox buildBottomStatusBar() {
        HBox status = new HBox(20);
        status.setPadding(new Insets(8, 24, 8, 24));
        status.setAlignment(Pos.CENTER_LEFT);
        status.setStyle("-fx-background-color: #0B111E; -fx-border-color: #1E293B; -fx-border-width: 1 0 0 0;");

        Label crewStatus = new Label("● КОМАНДИР: М. ВОДКО. Автономний режим: рішення ви приймаєте самі");
        crewStatus.setStyle("-fx-text-fill: #10B981; -fx-font-size: 11px; -fx-font-weight: bold;");

        Label linkStatus = new Label("Зв'язок із Землею (Х'юстон): повідомлення йдуть із затримкою");
        linkStatus.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        blackoutStatusLabel.setStyle("-fx-text-fill: #C084FC; -fx-font-size: 11px; -fx-font-weight: bold;");
        blackoutStatusLabel.setVisible(false);
        blackoutStatusLabel.setManaged(false);

        status.getChildren().addAll(crewStatus, linkStatus, blackoutStatusLabel);
        return status;
    }

    private void updateClock() {
        Instant now = missionNow();
        clockLabel.setText("Доба місії: " + (142 + clockSec / 86400) + "  |  Час (UTC): " + CLOCK_FORMAT.format(now));
        bigClockLabel.setText(LOG_TIME.format(now));
        simClockLabel.setText(String.format(Locale.US, "Доба місії %d  |  симуляція: %d д %02d:%02d:%02d",
                142 + clockSec / 86400, clockSec / 86400, (clockSec / 3600) % 24, (clockSec / 60) % 60, clockSec % 60));
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

    /** Колір сяйва голограми залежить від загального стану. */
    private Color auraColor() {
        if (healthScoreValue >= 80) return Color.rgb(56, 189, 248);
        if (healthScoreValue >= 50) return Color.rgb(245, 158, 11);
        return Color.rgb(239, 68, 68);
    }

    /** Кути рамки HUD. */
    private void drawBrackets(GraphicsContext gc, double w, double h, Color c) {
        double m = 8, L = 22;
        gc.setStroke(c);
        gc.setLineWidth(2);
        gc.strokeLine(m, m, m + L, m);
        gc.strokeLine(m, m, m, m + L);
        gc.strokeLine(w - m, m, w - m - L, m);
        gc.strokeLine(w - m, m, w - m, m + L);
        gc.strokeLine(m, h - m, m + L, h - m);
        gc.strokeLine(m, h - m, m, h - m - L);
        gc.strokeLine(w - m, h - m, w - m - L, h - m);
        gc.strokeLine(w - m, h - m, w - m, h - m - L);
    }

    /** Малює голографічну сцену: сяйво, проектор, промінь, кільця, частинки, космонавта і сканер. */
    private void drawHolographicBody(GraphicsContext gc) {
        double w = bodyHudCanvas.getWidth();
        double h = bodyHudCanvas.getHeight();
        if (w < 10 || h < 10) return;
        double t = animationPulse / 3.0;
        Color aura = auraColor();
        gc.clearRect(0, 0, w, h);
        gc.setLineDashes((double[]) null);
        gc.setGlobalAlpha(1.0);

        double s = Math.min(w / BODY_W, h / BODY_H);
        double ox = (w - BODY_W * s) / 2;
        double oy = (h - BODY_H * s) / 2;
        bodyScale = s;
        bodyOffX = ox;
        bodyOffY = oy;
        double midX = w / 2;
        double footY = oy + (62 + 392) * s;

        // 1. М'яке сяйво за спиною
        gc.setFill(new RadialGradient(0, 0, midX, h * 0.5, Math.min(w, h) * 0.65, false, CycleMethod.NO_CYCLE,
                new Stop(0, HoloUi.alpha(aura, 0.16)), new Stop(1, HoloUi.alpha(aura, 0.0))));
        gc.fillRect(0, 0, w, h);

        // 2. Сітка сканера
        gc.setStroke(Color.rgb(30, 41, 59, 0.35));
        gc.setLineWidth(1);
        for (double y = 0; y < h; y += 30) gc.strokeLine(0, y, w, y);
        for (double x = 0; x < w; x += 30) gc.strokeLine(x, 0, x, h);

        // 3. Промінь проектора від майданчика вгору
        double padRx = 120 * s;
        gc.setFill(new LinearGradient(0, 1, 0, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, HoloUi.alpha(aura, 0.20)), new Stop(1, HoloUi.alpha(aura, 0.0))));
        gc.fillPolygon(
                new double[]{midX - padRx * 0.8, midX + padRx * 0.8, midX + padRx * 0.5, midX - padRx * 0.5},
                new double[]{footY, footY, oy, oy}, 4);

        // 4. Майданчик проектора: кільця, що пульсують
        for (int k = 0; k < 3; k++) {
            double ph = (t * 0.5 + k / 3.0) % 1.0;
            double rx = padRx * (0.45 + 0.75 * ph);
            gc.setStroke(HoloUi.alpha(aura, 0.55 * (1 - ph)));
            gc.setLineWidth(1.5);
            gc.strokeOval(midX - rx, footY - rx * 0.2 + 4 * s, rx * 2, rx * 0.4);
        }
        gc.setStroke(HoloUi.alpha(aura, 0.7));
        gc.setLineWidth(2);
        gc.strokeOval(midX - padRx * 0.45, footY - padRx * 0.09 + 4 * s, padRx * 0.9, padRx * 0.18);

        // 5. Кільця навколо тіла (як гіроскоп)
        double ringCx = ox + (BODY_W / 2) * s;
        double ringCy = oy + (62 + 190) * s;
        for (int k = 0; k < 2; k++) {
            gc.save();
            gc.translate(ringCx, ringCy);
            gc.rotate(k == 0 ? 14 : -14);
            double rx = (150 + 22 * k) * s;
            double ry = rx * (0.16 + 0.05 * Math.sin(t * 0.8 + k * 2));
            gc.setStroke(HoloUi.alpha(aura, 0.30));
            gc.setLineWidth(1.2);
            gc.strokeOval(-rx, -ry, 2 * rx, 2 * ry);
            double a = t * (k == 0 ? 0.9 : -0.7);
            gc.setFill(HoloUi.alpha(aura, 0.95));
            gc.fillOval(rx * Math.cos(a) - 3, ry * Math.sin(a) - 3, 6, 6);
            gc.restore();
        }

        // 6. Космонавт (легке мерехтіння, як у справжньої голограми)
        gc.setGlobalAlpha(0.93 + 0.07 * Math.sin(animationPulse * 23));
        gc.save();
        gc.translate(ox, oy);
        gc.scale(s, s);
        drawBodyLogical(gc);
        gc.restore();
        gc.setGlobalAlpha(1.0);

        // 7. Частинки світла, що летять угору
        for (int i = 0; i < 28; i++) {
            double px = midX + ((i * 53) % 100 - 50) / 50.0 * (BODY_W * 0.42) * s;
            double life = (t * (0.10 + (i % 5) * 0.03) + i * 0.137) % 1.0;
            double py = footY - life * (footY - 4);
            double sz = 1.5 + (i % 3);
            gc.setFill(HoloUi.alpha(aura, Math.sin(life * Math.PI) * 0.7));
            gc.fillOval(px - sz / 2, py - sz / 2, sz, sz);
        }

        // 8. Промінь сканера з шлейфом
        double scanY = (animationPulse * 25) % h;
        gc.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, HoloUi.alpha(aura, 0.0)), new Stop(1, HoloUi.alpha(aura, 0.22))));
        gc.fillRect(0, scanY - 46, w, 46);
        gc.setStroke(Color.rgb(186, 230, 253, 0.65));
        gc.setLineWidth(1);
        gc.strokeLine(0, scanY, w, scanY);

        // 9. Рамка HUD і написи
        drawBrackets(gc, w, h, HoloUi.alpha(aura, 0.8));
        gc.setFont(Font.font("Consolas", 10));
        gc.setFill(HoloUi.alpha(aura, 0.9));
        gc.fillText("◈ ГОЛОГРАФІЧНЕ СКАНУВАННЯ", 18, 24);
        boolean blink = Math.sin(animationPulse * 3) > 0;
        gc.setFill(blink ? Color.rgb(239, 68, 68, 0.95) : Color.rgb(239, 68, 68, 0.3));
        gc.fillText("● НАЖИВО", w - 78, 24);
        gc.setFill(Color.rgb(148, 163, 184, 0.8));
        gc.fillText("Наведіть курсор на світну точку", 18, h - 16);
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
        double boxL = 20;        // ліві виноски
        double boxR = cx + 130;  // праві виноски

        // Очі (видно крізь візор)
        double icp = currentTelemetry.get(MetricType.ICP_OPTIC);
        Color sansColor = icp > 18.0 ? Color.rgb(239, 68, 68, 0.95) : Color.rgb(16, 185, 129, 0.9);
        glowDot(gc, cx - 8, hy - 3, pulse, sansColor);
        glowDot(gc, cx + 8, hy - 3, pulse, sansColor);
        drawCallout(gc, cx + 8, hy - 3, boxR, 18, true, "Тиск на зоровий нерв",
                String.format(Locale.US, "%.1f мм рт.ст.", icp), sansColor);

        // Кисень (легені)
        double spo2 = currentTelemetry.get(MetricType.SPO2);
        Color spoColor = spo2 < 96 ? Color.rgb(249, 115, 22, 0.95) : Color.rgb(34, 211, 238, 0.9);
        glowDot(gc, cx + 16, hy + 68, pulse, spoColor);
        drawCallout(gc, cx + 16, hy + 68, boxR, 92, true, "Кисень у крові (SpO2)",
                String.format(Locale.US, "%.1f %%", spo2), spoColor);

        // Хребет
        Color spineColor = Color.rgb(168, 85, 247, 0.9);
        glowDot(gc, cx, hy + 135, pulse, spineColor);
        drawCallout(gc, cx, hy + 135, boxR, 166, true, "Хребет у невагомості",
                String.format(Locale.US, "+%.1f см", SPINE_ELONGATION_CM), spineColor);

        // Радіація
        double rad = currentTelemetry.get(MetricType.RADIATION_DOSE);
        Color radColor = rad > 100 ? Color.rgb(239, 68, 68, 0.95) : Color.rgb(16, 185, 129, 0.9);
        glowDot(gc, cx + 40, hy + 150, pulse, radColor);
        drawCallout(gc, cx + 40, hy + 150, boxR, 240, true, "Доза радіації",
                String.format(Locale.US, "%.1f мЗв", rad), radColor);

        // Серце: б'ється з частотою пульсу
        double hr = currentTelemetry.get(MetricType.HEART_RATE);
        double hrv = currentTelemetry.get(MetricType.HRV);
        boolean heartWarn = hr > 100 || hr < 55 || hrv < 40;
        Color heartColor = heartWarn ? Color.rgb(249, 115, 22, 0.95) : Color.rgb(56, 189, 248, 0.9);
        double beat = 0.5 + 0.5 * Math.sin(heartPhase);
        glowDot(gc, cx, hy + 75, 5 + 6 * beat * beat, heartColor);
        drawCallout(gc, cx, hy + 75, boxL, 110, false, "Пульс",
                String.format(Locale.US, "%.0f уд/хв", hr), heartColor);

        // Стрес-баланс серця
        Color hrvColor = hrv < 40 ? Color.rgb(249, 115, 22, 0.95) : Color.rgb(56, 189, 248, 0.9);
        glowDot(gc, cx - 14, hy + 100, pulse, hrvColor);
        drawCallout(gc, cx - 14, hy + 100, boxL, 156, false, "Стрес-баланс серця (HRV)",
                String.format(Locale.US, "%.1f мс", hrv), hrvColor);

        // Вода
        double hyd = currentTelemetry.get(MetricType.HYDRATION);
        Color hydColor = hyd < 90 ? Color.rgb(249, 115, 22, 0.95) : Color.rgb(34, 211, 238, 0.9);
        glowDot(gc, cx, hy + 160, pulse, hydColor);
        drawCallout(gc, cx, hy + 160, boxL, 202, false, "Запас води",
                String.format(Locale.US, "%.0f %%", hyd), hydColor);

        // Кістки ніг
        double bone = currentTelemetry.get(MetricType.BONE_DENSITY);
        Color boneColor = bone < 97 ? Color.rgb(239, 68, 68, 0.95) : Color.rgb(234, 179, 8, 0.9);
        glowDot(gc, cx - 26, hy + 285, pulse, boneColor);
        glowDot(gc, cx + 26, hy + 285, pulse, boneColor);
        drawCallout(gc, cx - 26, hy + 285, boxL, 330, false, "Міцність кісток",
                String.format(Locale.US, "%.1f %%", bone), boneColor);

        // Підсвітка точки, на яку навели курсор
        if (hoverHot != null) {
            double r = 16 + 3 * Math.sin(animationPulse * 2);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1.5);
            gc.setLineDashes(4, 4);
            gc.strokeOval(hoverHot.x() - r, hoverHot.y() - r, 2 * r, 2 * r);
            gc.setLineDashes((double[]) null);
        }
    }

    /** Виноска: лінія від точки на тілі до підписаної рамки збоку. */
    private void drawCallout(GraphicsContext gc, double dotX, double dotY, double boxX, double boxY,
                             boolean boxOnRight, String title, String value, Color color) {
        double bw = 170, bh = 38;
        double edgeX = boxOnRight ? boxX : boxX + bw;
        double edgeY = boxY + bh / 2;

        gc.setLineDashes((double[]) null);
        gc.setStroke(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.6));
        gc.setLineWidth(1.2);
        gc.strokeLine(dotX, dotY, edgeX, edgeY);

        gc.setFill(Color.rgb(8, 12, 20, 0.88));
        gc.fillRoundRect(boxX, boxY, bw, bh, 8, 8);
        gc.setStroke(color);
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(boxX, boxY, bw, bh, 8, 8);

        gc.setFill(Color.rgb(148, 163, 184));
        gc.setFont(Font.font("Consolas", 10));
        gc.fillText(title, boxX + 8, boxY + 14);
        gc.setFill(color);
        gc.setFont(Font.font("Consolas", 14));
        gc.fillText(value, boxX + 8, boxY + 31);
        gc.setFont(Font.font("Consolas", 11));
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
                        "Денну норму тренувань уже виконано. Продовжите завтра. Можна прийняти вітамін D (екран «Аптечка»).",
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
            // Зниження дози радіації (наприклад, водяними щитами в блекауті) не є загрозою
            if (type == MetricType.RADIATION_DOSE && f.direction() == TrendEngine.Direction.DOWN) continue;

            String verb = f.direction() == TrendEngine.Direction.UP ? "зросте вище норми" : "впаде нижче норми";
            activeProtocols.add(new ClinicalProtocol(
                    "Прогноз",
                    type.title + ": " + verb + " приблизно через " + formatHours(f.hoursToLimit()),
                    "1. " + preventiveAdviceNow(type) + "\n"
                            + "2. Подивіться графік на екрані «Історія і прогноз».",
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
        trendChart.setMinHeight(260);
        trendChart.setPrefHeight(420);
        VBox.setVgrow(trendChart, Priority.ALWAYS);
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

        Label pickCaption = new Label("Оберіть показник, щоб побачити його графік:");
        pickCaption.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");
        sec.getChildren().addAll(pickCaption, pick, trendChart, forecastLabel, legend);
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
                    + "тому лікар не відповість швидко. Дійте за підказками на екрані «Що робити».";
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

            // Кругова шкала та позначка стану
            HoloUi.RingGauge gauge = gauges.get(type);
            Label chip = chips.get(type);
            Color stateColor = (!high && !low) ? HoloUi.GREEN : (tempOnly ? HoloUi.AMBER : HoloUi.RED);
            if (gauge != null) {
                gauge.set(val, type.hardMin, type.hardMax, type.minNominal, type.maxNominal, stateColor,
                        String.format(Locale.US, Math.abs(val) >= 100 ? "%.0f" : "%.1f", val), type.unit);
            }
            if (chip != null) {
                String st = (!high && !low) ? "● Норма"
                        : (high ? "▲ Вище норми" : "▼ Нижче норми") + (tempOnly ? ", тимчасово" : "");
                HoloUi.styleChip(chip, st, stateColor);
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

        // Пристрій командира в mesh-мережі показує ті самі живі дані
        if (crewPanel != null) {
            crewPanel.setLocalVitals(currentTelemetry.get(MetricType.HEART_RATE),
                    currentTelemetry.get(MetricType.HRV), currentTelemetry.get(MetricType.HYDRATION));
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
        healthScoreValue = score;
        healthScoreLabel.setText("ЗАГАЛЬНИЙ СТАН: " + score + " з 100 (" + verdict + ")");
        healthBar.setProgress(score / 100.0);
        healthBar.setStyle("-fx-accent: " + color + ";");
        Color healthColor = Color.web(color);
        if (healthGauge != null) {
            healthGauge.set(score, 0, 100, 90, 100, healthColor, String.valueOf(score), "з 100");
        }
        healthVerdictLabel.setText(verdict);
        healthVerdictLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 20px; -fx-font-weight: bold;");
        healthExplainLabel.setText("У нормі " + okCount + " з " + total + " показників. "
                + (okCount < total ? "Що саме поза нормою, видно на вкладці «Показники»." : "Так тримати!"));

        // Підказки перемальовуємо лише коли вони змінились
        runAutonomousTriage();
        updateAutoPlanButton();
        updatePillViews();
        updateActivityButtons();
        updateAlertIndicators();
        updateAssistant();
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

        StressEngine.Assessment stress = stressEngine.latest();
        sb.append(String.format(Locale.US, "%nПСИХОЛОГІЧНИЙ СТАН (MindCare):%n- Індекс стресу: %d з 100 (%s)%n",
                stress.index(), stress.level().label));
        if (!stress.reasons().isEmpty()) sb.append("- Що тисне: ").append(String.join("; ", stress.reasons())).append('\n');
        sb.append(MindCareWindow.summaryText());

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

    public enum CrewStatus {
        NOMINAL("Усе гаразд", HoloUi.GREEN),
        WARNING("Потрібна увага", HoloUi.AMBER),
        CRITICAL("Критично / SOS", HoloUi.RED),
        OFFLINE("⚠️ Немає сигналу (Mesh Lost)", HoloUi.TEXT_DIM);

        public final String label;
        public final Color color;

        CrewStatus(String label, Color color) {
            this.label = label;
            this.color = color;
        }
    }


    public static void main(String[] args) {
        launch(args);
    }
}