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
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
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
 * VITAL: помічник здоров'я екіпажу в далекому космосі.
 * Показники змінюються лише після дії, яку космонавт справді виконав: розпочати, дочекатися повної
 * тривалості, підтвердити. Журнал виконання (ActivityJournal) фіксує підтверджені, перервані дії,
 * серії швидких натискань і довгі періоди без дій. Програма показує, ЩО змінилось і ЧОМУ.
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
        SOLAR_CONJUNCTION("⚠️ Сонце блокує зв'язок (зв'язку із Землею немає)", Double.POSITIVE_INFINITY);

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
        l.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 22px;");
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

    // Таблиця показників і лічильники в меню
    private final Map<MetricType, Label> chips = new EnumMap<>(MetricType.class);

    // Журнал виконання: що космонавт справді робив
    private final ActivityJournal journal = new ActivityJournal();
    private final Label sessionIdle = new Label();
    private final Label sessionTitle = new Label();
    private final Label sessionTime = new Label();
    private final ProgressBar sessionBar = new ProgressBar(0);
    private final Button confirmBtn = new Button("✔ Підтвердити виконання");
    private final Button abortBtn = new Button("✖ Скасувати");
    private final VBox sessionActive = new VBox(6);
    private final FlowPane journalChips = new FlowPane(8, 8);
    private final Label journalVerdict = new Label();
    private final VBox journalRows = new VBox(6);

    // Анімація
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
        journal.setListener(this::onJournalEntry);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #060A12, #0A1424); "
                + "-fx-font-family: 'Segoe UI', system-ui, sans-serif;");

        root.setTop(new VBox(buildTopHeader(primaryStage), buildBuddySosBanner()));
        root.setCenter(buildMainDashboard());
        root.setBottom(buildBottomStatusBar());
        showScreen(Screen.MENU);

        // Mesh екіпажу живе завжди (таймери, пакети), малюється лише на своєму екрані
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (lastNanos == 0) lastNanos = now;
                double dt = (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;
                if (crewPanel != null) crewPanel.frame(dt, currentScreen == Screen.MESH_CREW);
                updateBuddySosBanner();
                if (currentScreen == Screen.MENU && orbitMenu != null) {
                    orbitMenu.frame(dt);
                } else if (currentScreen == Screen.MISSION && missionMap != null) {
                    missionMap.frame(dt);
                } else if (currentScreen == Screen.BODY && bodyAstronaut != null) {
                    bodyAstronaut.frame(dt);
                    if (heartMonitor != null) {
                        double hr = currentTelemetry.get(MetricType.HEART_RATE);
                        boolean warn = hr > 100 || hr < 55 || currentTelemetry.get(MetricType.HRV) < 40;
                        heartMonitor.update(dt, hr, warn, true);
                    }
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

        // Розмір вікна підлаштовується під екран (стандартний ПК, ноутбук, планшет)
        javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(root, Math.min(1366, vb.getWidth()), Math.min(820, vb.getHeight()));
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && currentScreen != Screen.MENU) {
                showScreen(Screen.MENU);
            }
        });
        primaryStage.setMinWidth(Math.min(900, vb.getWidth()));
        primaryStage.setMinHeight(Math.min(600, vb.getHeight()));
        primaryStage.setTitle("VITAL: помічник здоров'я екіпажу в далекому космосі");
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
        journal.reset();
        if (blackoutActive) abortBlackoutSilently();
        showEffectsPlaceholder();
        updateHabitViews();
        updateAllTelemetryViews();
        updateTrendChart();
        updateSessionView();
        refreshJournalView();
        renderActiveProtocols();
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
    private ChoiceBar<MissionLocation> locationBar;
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

    /** Змінює поточну локацію і синхронізує кнопки вибору (без повторного запуску їх обробника). */
    private void setLocationSilently(MissionLocation loc) {
        currentLocation = loc;
        if (locationBar != null) locationBar.setValue(loc);
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
                + ". Керування перейшло до автопілота.");
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
                "Тривалість блекауту: %s%nПакетів телеметрії передано: %d (~%d КБ)%nДій автопілота: %d%n"
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
            blackoutStatusLabel.setText("📡❌ НЕМАЄ ЗВ'ЯЗКУ: до відновлення "
                    + formatDuration(blackoutRemainingSec()).replaceAll("\\s+", " ")
                    + "  |  Дій автопілота: " + blackoutAiActions);
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
                        + ", дій автопілота: " + blackoutAiActions + ".");
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
            journal.addAuto(a.logText(), "Автопілот (ШІ): " + r.reason() + ". Це не дія космонавта.", clockSec);

            String msg = a.logText() + " (" + r.reason() + ")";
            addLog("🤖 [Автопілот] " + msg + ".");
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
        boolean actionNeeded = activeProtocols.stream()
                .anyMatch(p -> !"NOMINAL".equals(p.urgency()) && !"FORECAST".equals(p.urgency()));
        journal.checkInactivity(clockSec, actionNeeded && !blackoutActive);
        updateSessionView();
    }

    // ============ VITAL MINDCARE ============
    private void updateStress() {
        stressEngine.update(new StressEngine.Reading(
                currentTelemetry.get(MetricType.HEART_RATE),
                currentTelemetry.get(MetricType.HRV),
                currentTelemetry.get(MetricType.HYDRATION),
                sleepHours), clockSec);
        updateMenuStates();
        if (stressEngine.shouldAlertCritical(clockSec)) {
            addLog("🧠 [MindCare] Стрес критичний і не минає. Відкриваю вкладку психологічної підтримки.");
            openMindCare();
        }
    }

    private void openMindCare() {
        showScreen(Screen.MINDCARE);
        if (blackoutStage != null && blackoutStage.isShowing() && mainStage != null) mainStage.toFront();
    }

    private void onMindCareBreathingDone() {
        Activity breath = findActivity("BREATH");
        if (breath != null && blockedReason(breath) == null) {
            applyActivity(breath, true);
            journal.addAuto(breath.logText(), "Повну вправу виконано у вкладці MindCare.", clockSec);
        }
    }

    private void onMindCareSos() {
        if (crewPanel != null) crewPanel.raiseCommanderSos();
        addLog("🆘 [Психологічна підтримка] Сигнал SOS надіслано в центр керування на Землі та екіпажу.");
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

        list.add(new Activity("STRENGTH", "Силові тренування (ARED)", "Силові тренування, 30 хв", "#059669", List.of(
                new Effect(MetricType.BONE_DENSITY, 0.4, false, true, "Навантаження на кістки сповільнює втрату кальцію в невагомості."),
                new Effect(MetricType.HRV, 1.5, false, true, "Регулярні тренування роблять серце стійкішим до стресу."),
                new Effect(MetricType.HEART_RATE, 15, true, false, "Під час навантаження серце б'ється швидше. Через кілька хвилин пульс повернеться до норми."),
                new Effect(MetricType.BLOOD_PRESSURE, 8, true, false, "Під час навантаження тиск тимчасово зростає."),
                new Effect(MetricType.BODY_TEMP, 0.4, true, false, "Тіло нагрілось від роботи."),
                new Effect(MetricType.HYDRATION, -3, false, false, "Ви втратили воду з потом. Випийте води.")
        )));

        list.add(new Activity("CARDIO", "Кардіо (доріжка T2 / велотренажер CEVIS)", "Кардіо-тренування, 30 хв", "#16A34A", List.of(
                new Effect(MetricType.HRV, 2.5, false, true, "Кардіо тренує серце і покращує стрес-баланс."),
                new Effect(MetricType.BLOOD_PRESSURE, -3, false, true, "Регулярне кардіо поступово знижує тиск крові."),
                new Effect(MetricType.BONE_DENSITY, 0.1, false, true, "Біг із фіксацією дає невелике навантаження на кістки."),
                new Effect(MetricType.HEART_RATE, 28, true, false, "Кардіо сильно прискорює серце. Це нормально, пульс повернеться."),
                new Effect(MetricType.BLOOD_GLUCOSE, -10, true, true, "М'язи витрачають цукор із крові."),
                new Effect(MetricType.BODY_TEMP, 0.5, true, false, "Тіло нагрілось від роботи."),
                new Effect(MetricType.HYDRATION, -4, false, false, "Ви втратили воду з потом. Випийте води.")
        )));

        list.add(new Activity("WATER", "Випити води з електролітами (400 мл)", "Випито 400 мл води з електролітами", "#0891B2", List.of(
                new Effect(MetricType.HYDRATION, 4, false, true, "Вода поповнює запас рідини в організмі."),
                new Effect(MetricType.HEART_RATE, -2, true, true, "Коли води достатньо, серцю легше працювати.")
        )));

        list.add(new Activity("SLEEP", "Поспати", "Сон, 2 години", "#4F46E5", List.of(
                new Effect(MetricType.HRV, 2, false, true, "Сон відновлює нервову систему й серце."),
                new Effect(MetricType.HEART_RATE, -5, true, true, "Під час сну пульс нижчий, серце відпочиває.")
        )));

        list.add(new Activity("MEAL", "Поїсти (прийом їжі)", "Прийом їжі", "#CA8A04", List.of(
                new Effect(MetricType.BLOOD_GLUCOSE, 30, true, false, "Після їжі цукор у крові зростає, через 1-2 години повернеться до норми."),
                new Effect(MetricType.HEART_RATE, 3, true, false, "Травлення трохи прискорює пульс.")
        )));

        list.add(new Activity("BREATH", "Дихальна вправа", "Дихальна вправа, 10 хв", "#7C3AED", List.of(
                new Effect(MetricType.HEART_RATE, -6, true, true, "Повільне дихання заспокоює серце."),
                new Effect(MetricType.BLOOD_PRESSURE, -6, true, true, "Повільне дихання знижує тиск крові."),
                new Effect(MetricType.HRV, 4, true, true, "Повільне дихання одразу покращує стрес-баланс серця.")
        )));

        list.add(new Activity("LBNP", "Сеанс LBNP (камера для ніг)", "Сеанс LBNP, 45 хв", "#2563EB", List.of(
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

    /** automatic = true: дію виконує Автопілот (ШІ) (без вікна пояснень, з позначкою в журналі). */
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
            addLog("✔ Виконано і підтверджено: " + a.logText());
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
            journal.rejectedPress(clockSec, System.currentTimeMillis());
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
        journal.addSelfReported(p.name(), n + " шт. Прийом відмічено космонавтом: таймером його не перевірити.",
                clockSec, System.currentTimeMillis());
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
        boolean busy = journal.hasActive();
        for (Activity a : buildActivities()) {
            Button b = activityButtons.get(a.id());
            if (b == null) continue;
            String reason = blockedReason(a);
            b.setDisable(reason != null || busy);
            b.setText(reason != null
                    ? "⛔ " + a.button() + " (не зараз)"
                    : activityIcon(a.id()) + a.button() + "  ·  " + ActivityJournal.formatSec(activityDurationSec(a.id())));
        }
    }

    // ============ ВИКОНАННЯ ДІЙ: РОЗПОЧАТИ, ВИКОНАТИ, ПІДТВЕРДИТИ ============
    /** Справжня тривалість дії (секунди часу місії). Показники змінюються лише після неї. */
    private static long activityDurationSec(String id) {
        return switch (id) {
            case "STRENGTH", "CARDIO" -> 30 * 60;
            case "WATER" -> 60;
            case "SLEEP" -> 2 * 3600;
            case "MEAL" -> 15 * 60;
            case "BREATH" -> 10 * 60;
            case "LBNP" -> 45 * 60;
            default -> 5 * 60;
        };
    }

    private void beginActivity(Activity a) {
        String blocked = blockedReason(a);
        if (blocked != null) {
            journal.rejectedPress(clockSec, System.currentTimeMillis());
            showNotice("Зараз не можна: " + a.logText(), blocked);
            return;
        }
        long dur = activityDurationSec(a.id());
        String err = journal.begin(a.id(), a.logText(), dur, clockSec, System.currentTimeMillis());
        if (err != null) {
            showNotice("Зараз не можна: " + a.logText(), err);
            return;
        }
        addLog("▶ Розпочато: " + a.logText() + " (триває " + ActivityJournal.formatSec(dur) + ").");
        afterSessionChange();
    }

    private void confirmActivity() {
        ActivityJournal.Session s = journal.active();
        if (s == null) return;
        Activity a = findActivity(s.activityId());
        long now = System.currentTimeMillis();
        if (a == null || !journal.isReady(clockSec)) {
            journal.confirm(clockSec, now); // рахується як зайве натискання
            updateSessionView();
            return;
        }
        String blocked = blockedReason(a);
        if (blocked != null) {
            journal.abort(clockSec, now);
            showNotice("Дію не зараховано: " + a.logText(), blocked);
            afterSessionChange();
            return;
        }
        journal.confirm(clockSec, now);
        applyActivity(a, false);
        afterSessionChange();
    }

    private void abortActivity() {
        journal.abort(clockSec, System.currentTimeMillis());
        afterSessionChange();
    }

    private void afterSessionChange() {
        updateSessionView();
        updateActivityButtons();
        renderActiveProtocols();
    }

    /** Панель «Виконується зараз»: таймер, кнопка підтвердження доступна лише після повної тривалості. */
    private void updateSessionView() {
        ActivityJournal.Session s = journal.active();
        boolean on = s != null;
        sessionActive.setVisible(on);
        sessionIdle.setVisible(!on);
        if (on) {
            long req = s.requiredSec();
            boolean ready = journal.isReady(clockSec);
            sessionTitle.setText("Виконується: " + s.title());
            sessionBar.setProgress(Math.min(1.0, (double) journal.elapsedSec(clockSec) / req));
            sessionTime.setText(ready
                    ? "Час минув. Якщо ви справді це виконали, підтвердіть."
                    : "Залишилось " + ActivityJournal.clock(journal.remainingSec(clockSec))
                    + " із " + ActivityJournal.clock(req) + ". Підтвердити можна після завершення.");
            confirmBtn.setDisable(!ready);
            abortBtn.setText(ready ? "✖ Не виконано" : "✖ Скасувати (показники не зміняться)");
        } else {
            long since = journal.sinceLastConfirmed(clockSec);
            sessionIdle.setText("Зараз нічого не виконується. Показники змінюються лише після виконаної й підтвердженої дії. "
                    + (since < 0 ? "Підтверджених дій ще не було."
                    : "Остання підтверджена дія: " + ActivityJournal.formatSec(since) + " тому."));
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
        head.setStyle("-fx-text-fill: white; -fx-font-size: 26px; -fx-font-weight: bold;");
        Label text = new Label(body);
        text.setWrapText(true);
        text.setStyle("-fx-text-fill: #FBBF24; -fx-font-size: 24px;");
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
        Label hint = new Label("Тут з'явиться, як остання підтверджена дія вплинула на ваше здоров'я і чому.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 24px;");
        effectsBox.getChildren().add(hint);
    }

    private void showEffects(Activity a) {
        effectsBox.getChildren().clear();

        Label head = new Label("Ви зробили: " + a.logText());
        head.setWrapText(true);
        head.setStyle("-fx-text-fill: white; -fx-font-size: 26px; -fx-font-weight: bold;");
        effectsBox.getChildren().add(head);

        for (Effect e : a.effects()) {
            String arrow = e.delta() > 0 ? "▲" : "▼";
            String when = e.temporary() ? "тимчасово" : "надовго";
            Label main = new Label(String.format(Locale.US, "%s %s: %+.1f %s (%s)",
                    arrow, e.metric().title, e.delta(), e.metric().unit, when));
            main.setWrapText(true);
            main.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: "
                    + (e.good() ? "#34D399" : "#FBBF24") + ";");

            Label why = new Label("Чому: " + e.why());
            why.setWrapText(true);
            why.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px; -fx-padding: 0 0 4 14;");

            effectsBox.getChildren().addAll(main, why);
        }

        Label legend = new Label("Зелений: корисно для здоров'я. Жовтий: тимчасова реакція організму або витрата ресурсів.");
        legend.setWrapText(true);
        legend.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");
        effectsBox.getChildren().add(legend);
    }

    private void addLog(String text) {
        String line = "[" + LOG_TIME.format(missionNow()) + "] " + text;
        if (logLines.isEmpty()) logBox.getChildren().clear();
        logLines.add(line);

        Label l = new Label(line);
        l.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 22px;");
        logBox.getChildren().add(0, l);
        if (logBox.getChildren().size() > 12) {
            logBox.getChildren().remove(logBox.getChildren().size() - 1);
        }
    }

    // ============ ЕКРАНИ ТА НАВІГАЦІЯ ============
    private enum Screen {
        STATUS("🩺", "Стан і дії", "Показники, підказки, ваші дії"),
        JOURNAL("📋", "Журнал виконання", "Що реально зроблено"),
        TRENDS("📈", "Історія і прогноз", "Що було і що буде"),
        MISSION("🛰️", "Місія", "Зв'язок, дані, звіт"),
        MESH_CREW("👥", "Команда", "Зв'язок та статус екіпажу"),
        SOURCES("📚", "Джерела", "Наукова основа: NASA, CSA"),
        MENU("🏠", "Меню", "Головне меню"),
        BODY("🧑‍🚀", "Голограма екіпажу", "Живі показники на тілі"),
        MINDCARE("🧠", "Психологічна підтримка", "Дихання, настрій, SOS");

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
    private Screen currentScreen = Screen.MENU;
    private OrbitMenu orbitMenu;
    private MissionMapCanvas missionMap;
    private AstronautView bodyAstronaut;
    private MindCarePanel mindCarePanel;
    private HeartMonitor heartMonitor;
    private final Button backBtn = new Button("◀ Меню");
    private final Label pageTitleLabel = new Label();
    private Label headerLogo;
    private final Map<Screen, Boolean> counterOn = new EnumMap<>(Screen.class);
    private int healthScoreValue = 100;
    private String healthVerdict = "Усе гаразд";
    private String healthColorHex = "#3CC48A";
    private final Label bodyScoreLabel = new Label();
    private final Label bodyVerdictLabel = new Label();
    private final Label bodyExplainLabel = new Label();
    private final Button urgentBtn = new Button();
    private Stage mainStage;

    private static final String PANEL_STYLE = HoloUi.BLOCK_STYLE;
    private static final String SECTION_TITLE_STYLE =
            "-fx-text-fill: #38BDF8; -fx-font-size: 26px; -fx-font-weight: bold;";
    private static final String CARD_STYLE = HoloUi.CARD_STYLE;

    /** Лічильник розділу: червоний бейдж на вузлі орбітального меню. */
    private void setCounter(Screen sc, String text, boolean visible) {
        counterOn.put(sc, visible);
        if (orbitMenu != null) {
            orbitMenu.setBadge(sc.name(), visible ? text : null);
            updateMenuStates();
        }
    }

    private void showScreen(Screen target) {
        Screen prev = currentScreen;
        currentScreen = target;
        for (Map.Entry<Screen, Node> en : screens.entrySet()) {
            en.getValue().setVisible(en.getKey() == target);
        }
        boolean menu = target == Screen.MENU;
        backBtn.setVisible(!menu);
        pageTitleLabel.setVisible(!menu);
        if (headerLogo != null) headerLogo.setVisible(menu); // на підекранах місце потрібне назві розділу
        Node drawn = switch (target) {
            case MESH_CREW -> HoloUi.iconTeam();
            case MINDCARE -> HoloUi.iconMind();
            case BODY -> HoloUi.iconAstronaut();
            default -> null;
        };
        pageTitleLabel.setGraphicTextGap(10);
        pageTitleLabel.setGraphic(drawn);
        pageTitleLabel.setText(drawn != null ? target.title : target.icon + "  " + target.title);
        if (mindCarePanel != null) {
            if (target == Screen.MINDCARE) mindCarePanel.onShown();
            else if (prev == Screen.MINDCARE) mindCarePanel.onHidden();
        }
    }

    private static boolean isOrderedAtLeast(StressEngine.Level level, StressEngine.Level min) {
        return level.ordinal() >= min.ordinal();
    }

    /** Колір вузлів орбітального меню відображає стан кожного розділу. */
    private void updateMenuStates() {
        if (orbitMenu == null) return;
        boolean urgent = false;
        boolean todo = false;
        boolean forecast = false;
        for (ClinicalProtocol p : activeProtocols) {
            if ("CRITICAL".equals(p.urgency()) || "HIGH".equals(p.urgency())) urgent = true;
            if (!"NOMINAL".equals(p.urgency()) && !"FORECAST".equals(p.urgency())) todo = true;
            if ("FORECAST".equals(p.urgency())) forecast = true;
        }
        orbitMenu.setItemState(Screen.STATUS.name(),
                urgent ? OrbitMenu.State.DANGER : todo ? OrbitMenu.State.WARN : OrbitMenu.State.INFO,
                urgent ? "Є термінові підказки" : todo ? "Є підказки, що потребують дій" : "Усе в нормі");
        orbitMenu.setItemState(Screen.JOURNAL.name(),
                counterOn.getOrDefault(Screen.JOURNAL, false) ? OrbitMenu.State.WARN : OrbitMenu.State.NEUTRAL,
                counterOn.getOrDefault(Screen.JOURNAL, false) ? "Є невиконані або розбіжні записи" : "");
        orbitMenu.setItemState(Screen.TRENDS.name(),
                forecast ? OrbitMenu.State.WARN : OrbitMenu.State.NEUTRAL,
                forecast ? "Прогноз попереджає про вихід показника за межу" : "");
        orbitMenu.setItemState(Screen.MISSION.name(),
                blackoutActive ? OrbitMenu.State.WARN : OrbitMenu.State.NEUTRAL,
                blackoutActive ? "Автономний режим: зв'язку із Землею немає" : "");
        boolean sos = counterOn.getOrDefault(Screen.MESH_CREW, false);
        orbitMenu.setItemState(Screen.MESH_CREW.name(),
                sos ? OrbitMenu.State.DANGER : OrbitMenu.State.OK,
                sos ? "Хтось з екіпажу подав сигнал" : "Екіпаж на зв'язку");
        orbitMenu.setItemState(Screen.SOURCES.name(), OrbitMenu.State.NEUTRAL, "");
        StressEngine.Level lvl = stressEngine.latest().level();
        orbitMenu.setItemState(Screen.MINDCARE.name(),
                isOrderedAtLeast(lvl, StressEngine.Level.CRITICAL) ? OrbitMenu.State.DANGER
                        : isOrderedAtLeast(lvl, StressEngine.Level.HIGH) ? OrbitMenu.State.WARN : OrbitMenu.State.OK,
                "Рівень стресу: " + lvl.label);

        String text;
        Color c;
        if (healthScoreValue >= 90) { text = "Усе гаразд"; c = Color.web("#3CC48A"); }
        else if (healthScoreValue >= 70) { text = "Добре"; c = Color.web("#7FD8A8"); }
        else if (healthScoreValue >= 50) { text = "Потрібна увага"; c = Color.web("#F2B33D"); }
        else { text = "Небезпечно"; c = Color.web("#E5535F"); }
        orbitMenu.setVerdict(text, c);
    }

    /** Червона кнопка у верхній панелі та лічильник на пункті «Стан і дії». */
    private void updateAlertIndicators() {
        int urgent = 0;
        int todo = 0;
        for (ClinicalProtocol p : activeProtocols) {
            if ("CRITICAL".equals(p.urgency()) || "HIGH".equals(p.urgency())) urgent++;
            if (!"NOMINAL".equals(p.urgency()) && !"FORECAST".equals(p.urgency())) todo++;
        }
        setCounter(Screen.STATUS, String.valueOf(todo), todo > 0);
        urgentBtn.setVisible(urgent > 0);
        urgentBtn.setText("⚠ ТЕРМІНОВО: " + urgent + ". Відкрити підказки");
    }

    // ============ ВЕРХНЯ ПАНЕЛЬ (годинник видно на всіх екранах) ============
    private HBox buildTopHeader(Stage stage) {
        HBox header = new HBox(18);
        header.setPadding(new Insets(10, 24, 10, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #0F1626; -fx-border-color: #1E293B; -fx-border-width: 0 0 1 0;");

        Label logoTitle = new Label("◈ VITAL");
        logoTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 34px; -fx-font-weight: 900; "
                + "-fx-effect: dropshadow(gaussian, rgba(56,189,248,0.8), 12, 0.25, 0, 0);");
        logoTitle.setMinWidth(Region.USE_PREF_SIZE);
        logoTitle.managedProperty().bind(logoTitle.visibleProperty());
        headerLogo = logoTitle;

        Label clockCaption = new Label("Час місії (UTC)");
        clockCaption.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 18px; -fx-font-weight: bold;");
        bigClockLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 44px; -fx-font-weight: 900; "
                + "-fx-font-family: 'Consolas', 'Courier New', monospace;");
        simClockLabel.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 18px;");
        clockCaption.setMinWidth(Region.USE_PREF_SIZE);
        bigClockLabel.setMinWidth(Region.USE_PREF_SIZE);
        simClockLabel.setMinWidth(Region.USE_PREF_SIZE);
        VBox clockBox = new VBox(1, clockCaption, bigClockLabel, simClockLabel);
        clockBox.setAlignment(Pos.CENTER);
        clockBox.setPadding(new Insets(4, 18, 4, 18));
        clockBox.setStyle(CARD_STYLE);
        updateClock();

        urgentBtn.setStyle("-fx-background-color: #B91C1C; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 4;");
        urgentBtn.managedProperty().bind(urgentBtn.visibleProperty());
        urgentBtn.setVisible(false);
        urgentBtn.setOnAction(e -> showScreen(Screen.STATUS));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label speedLabel = new Label("Швидкість часу:");
        speedLabel.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 20px; -fx-font-weight: bold;");
        speedLabel.setMinWidth(Region.USE_PREF_SIZE);

        ComboBox<String> speedBox = new ComboBox<>();
        speedBox.getItems().addAll("Звичайна (×1)", "Швидка (×10)", "Дуже швидка (×60)");
        speedBox.getSelectionModel().select(0);
        speedBox.setStyle("-fx-font-size: 20px;");
        speedBox.setMinWidth(Region.USE_PREF_SIZE);
        speedBox.setOnAction(e -> {
            int idx = speedBox.getSelectionModel().getSelectedIndex();
            simSpeed = new int[]{1, 10, 60}[Math.max(0, idx)];
        });

        backBtn.setStyle("-fx-background-color: rgba(56,189,248,0.14); -fx-text-fill: #E2E8F0; -fx-font-size: 24px; "
                + "-fx-font-weight: bold; -fx-border-color: #38BDF8; -fx-border-radius: 8; -fx-background-radius: 8; "
                + "-fx-padding: 8 16 8 16; -fx-cursor: hand;");
        backBtn.setMinWidth(Region.USE_PREF_SIZE);
        backBtn.setTooltip(new Tooltip("Повернутися в головне меню"));
        backBtn.managedProperty().bind(backBtn.visibleProperty());
        backBtn.setVisible(false);
        backBtn.setOnAction(e -> showScreen(Screen.MENU));
        pageTitleLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 32px; -fx-font-weight: bold;");
        pageTitleLabel.setMinWidth(Region.USE_PREF_SIZE);
        pageTitleLabel.managedProperty().bind(pageTitleLabel.visibleProperty());
        pageTitleLabel.setVisible(false);

        header.getChildren().addAll(logoTitle, clockBox, backBtn, pageTitleLabel, urgentBtn, spacer, speedLabel, speedBox);
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

    // ============ ГОЛОВНЕ ОРБІТАЛЬНЕ МЕНЮ ============
    private Node buildMenuScreen() {
        List<OrbitMenu.Item> items = List.of(
                // акцентне коло: голограма екіпажу (космонавт)
                new OrbitMenu.Item(Screen.BODY.name(), "Голограма екіпажу", "Живі показники на тілі космонавта", true),
                new OrbitMenu.Item(Screen.STATUS.name(), "Стан і дії", "Показники, підказки та ваші дії"),
                new OrbitMenu.Item(Screen.JOURNAL.name(), "Журнал", "Що справді зроблено"),
                new OrbitMenu.Item(Screen.TRENDS.name(), "Історія і прогноз", "Що було і що буде"),
                new OrbitMenu.Item(Screen.MISSION.name(), "Місія", "Зв'язок, дані, звіт"),
                new OrbitMenu.Item(Screen.MESH_CREW.name(), "Команда", "Зв'язок та статус екіпажу"),
                new OrbitMenu.Item(Screen.SOURCES.name(), "Джерела", "Наукова основа: NASA, CSA"),
                new OrbitMenu.Item(Screen.MINDCARE.name(), "Психологічна підтримка", "Дихання, настрій, допомога колезі"));
        orbitMenu = new OrbitMenu(items);
        orbitMenu.setOnSelect(id -> showScreen(Screen.valueOf(id)));
        updateMenuStates();
        return orbitMenu;
    }

    // ============ ГОЛОГРАМА ЕКІПАЖУ (космонавт із версії Stellar-Sync) ============
    private Node buildBodyScreen() {
        bodyAstronaut = new AstronautView(false, currentTelemetry, this::inRange, this::metricHint,
                () -> healthScoreValue, SPINE_ELONGATION_CM);
        bodyAstronaut.setMinHeight(380);
        VBox.setVgrow(bodyAstronaut, Priority.ALWAYS);
        VBox bodyBlock = HoloUi.block(HoloUi.iconAstronaut(), "Голограма екіпажу",
                "Світні точки на тілі показують живі дані вашого організму",
                "Кожна світна точка: частина тіла, за якою стежить програма. Наведіть на неї курсор: "
                        + "з'явиться пояснення простими словами та межі норми. "
                        + "Колір сяйва змінюється, коли стан погіршується.",
                bodyAstronaut);
        HBox.setHgrow(bodyBlock, Priority.ALWAYS);
        bodyBlock.setMinWidth(0);

        bodyScoreLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 68px; -fx-font-weight: bold;");
        bodyVerdictLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 40px; -fx-font-weight: bold;");
        bodyExplainLabel.setWrapText(true);
        bodyExplainLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 26px;");
        VBox healthBox = new VBox(4, bodyScoreLabel, bodyVerdictLabel, bodyExplainLabel);
        VBox healthBlock = HoloUi.block("🩺", "Загальний стан", "Оцінка від 0 до 100",
                "Програма перевіряє 10 показників. Оцінка = скільки з них зараз у нормі. "
                        + "90 і більше: чудово. 70-89: добре. 50-69: потрібна увага. Менше 50: небезпечно.",
                healthBox);

        heartMonitor = new HeartMonitor();
        heartMonitor.setManaged(false);
        Pane hrHolder = new Pane(heartMonitor);
        hrHolder.setMinHeight(120);
        hrHolder.setPrefHeight(120);
        hrHolder.setMaxHeight(120);
        heartMonitor.widthProperty().bind(hrHolder.widthProperty());
        heartMonitor.heightProperty().bind(hrHolder.heightProperty());
        VBox heartBlock = HoloUi.block("❤", "Серцебиття", "Лінія біжить із вашою справжньою частотою пульсу",
                "Зелена лінія: серце працює спокійно. Помаранчева: пульс або стрес-баланс поза нормою.", hrHolder);

        Button toStatus = new Button("Усі показники й дії →");
        toStatus.setMaxWidth(Double.MAX_VALUE);
        toStatus.setStyle("-fx-background-color: #0EA5E9; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 28px; -fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 10 16 10 16;");
        toStatus.setOnAction(e -> showScreen(Screen.STATUS));

        VBox right = new VBox(12, healthBlock, heartBlock, toStatus);
        right.setPrefWidth(400);
        right.setMinWidth(320);
        right.setMaxWidth(460);

        HBox row = new HBox(14, bodyBlock, right);
        row.setPadding(new Insets(2));
        return row;
    }

    // ============ ПСИХОЛОГІЧНА ПІДТРИМКА (окрема вкладка) ============
    private Node buildMindCareScreen() {
        mindCarePanel = new MindCarePanel(new MindCarePanel.Hooks(
                stressEngine,
                () -> clockSec,
                this::addLog,
                this::onMindCareBreathingDone,
                this::onMindCareSos));
        return mindCarePanel.view();
    }

    private StackPane buildMainDashboard() {
        StackPane stack = new StackPane();
        stack.setPadding(new Insets(16));

        screens.put(Screen.MENU, buildMenuScreen());
        screens.put(Screen.STATUS, buildStatusScreen());
        screens.put(Screen.JOURNAL, buildJournalScreen());
        screens.put(Screen.TRENDS, buildTrendsScreen());
        screens.put(Screen.MISSION, buildMissionScreen());
        screens.put(Screen.MESH_CREW, buildCrewMeshScreen());
        screens.put(Screen.SOURCES, scroll(new SourcesPanel()));
        screens.put(Screen.BODY, buildBodyScreen());
        screens.put(Screen.MINDCARE, buildMindCareScreen());

        for (Node n : screens.values()) stack.getChildren().add(n);

        showScreen(Screen.MENU);
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
        t.setStyle("-fx-font-size: 24px;");
        return t;
    }

    // ============ ЕКРАН 1: СТАН І ДІЇ (показники, підказки і дії в одному місці) ============
    /** Компактна таблиця всіх показників: значення, норма, стан, зміна після останньої дії. */
    private Node buildMetricsTable() {
        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(7);
        ColumnConstraints nameCol = new ColumnConstraints();
        nameCol.setHgrow(Priority.ALWAYS);
        nameCol.setMinWidth(150);
        g.getColumnConstraints().add(nameCol);

        String[] heads = {"Показник", "Зараз", "Норма", "Стан", "Після дії", "Джерело"};
        for (int c = 0; c < heads.length; c++) {
            Label h = new Label(heads[c]);
            h.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px; -fx-font-weight: bold;");
            g.add(h, c, 0);
        }
        int r = 1;
        for (MetricType t : MetricType.values()) {
            Label name = new Label(t.title);
            name.setWrapText(true);
            name.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 24px;");
            Tooltip tip = new Tooltip(metricHint(t));
            tip.setWrapText(true);
            tip.setMaxWidth(320);
            tip.setShowDelay(Duration.millis(80));
            name.setTooltip(tip);

            Label val = new Label();
            valueLabels.put(t, val);
            Label norm = new Label(HoloUi.num(t.minNominal) + "–" + HoloUi.num(t.maxNominal) + " " + t.unit);
            norm.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 22px;");
            Label chip = new Label();
            chips.put(t, chip);
            HoloUi.styleChip(chip, "...", HoloUi.CYAN);
            Label delta = new Label();
            deltaLabels.put(t, delta);

            g.add(name, 0, r);
            g.add(val, 1, r);
            g.add(norm, 2, r);
            g.add(chip, 3, r);
            g.add(delta, 4, r);
            SpaceSources.Risk nasaRisk = SpaceSources.forMetric(t.name());
            if (nasaRisk != null) {
                Hyperlink src = SourceLinks.link("📎 NASA", nasaRisk.source().url());
                src.setTooltip(new Tooltip("NASA HRP: " + nasaRisk.title() + "\n" + nasaRisk.source().url()
                        + "\nКлік відкриє сторінку в браузері"));
                g.add(src, 5, r);
            }
            r++;
        }
        return g;
    }

    private Node buildStatusScreen() {
        // ---- ліва колонка: загальний стан, денні цілі, таблиця показників
        healthScoreLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 28px; -fx-font-weight: bold;");
        healthBar.setMaxWidth(Double.MAX_VALUE);

        workoutLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 22px;");
        workoutBar.setMaxWidth(Double.MAX_VALUE);
        workoutBar.setStyle("-fx-accent: #10B981;");
        hydrationLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 22px;");
        hydrationBar.setMaxWidth(Double.MAX_VALUE);
        hydrationBar.setStyle("-fx-accent: #06B6D4;");
        sleepLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 22px;");
        sleepBar.setMaxWidth(Double.MAX_VALUE);
        sleepBar.setStyle("-fx-accent: #6366F1;");
        Label goalsNote = new Label("Денні цілі зараховуються лише за підтверджені дії. "
                + "Орієнтир NASA: близько 2.5 год тренувань, достатньо води й 8 год сну на добу.");
        goalsNote.setWrapText(true);
        goalsNote.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");

        VBox summary = HoloUi.block("🩺", "Загальний стан", "Скільки показників у нормі і виконання денних цілей",
                null, healthScoreLabel, healthBar, workoutLabel, workoutBar, hydrationLabel, hydrationBar,
                sleepLabel, sleepBar, goalsNote);

        Label legendNote = new Label("Стрілки ▲▼ у колонці «Після дії» показують, як показник змінився після "
                + "останньої підтвердженої дії. Наведіть курсор на назву, щоб прочитати, що це означає.");
        legendNote.setWrapText(true);
        legendNote.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");
        VBox metrics = HoloUi.block("📊", "Показники", "10 вимірів організму з нормою і поточним станом",
                null, buildMetricsTable(), legendNote);

        updateHabitViews();
        VBox left = new VBox(14, summary, metrics);
        left.setPadding(new Insets(0, 4, 0, 0));

        // ---- права колонка: що робити, ваші дії, препарати, що змінилось
        VBox todo = HoloUi.block("🧭", "Що робити зараз", "Підказки від найважливішого",
                null, protocolContainer);

        initSessionPanel();
        VBox doing = HoloUi.block("▶", "Ваші дії", "Розпочніть дію, виконайте її, підтвердіть",
                null, sessionIdle, sessionActive, buildActionButtons(), buildSessionNote());
        updateSessionView();

        VBox meds = HoloUi.block("💊", "Препарати", "Дозу розраховує програма, ліміти й перерви враховано",
                null, buildPillList());

        derivedLabel.setWrapText(true);
        derivedLabel.setMaxWidth(Double.MAX_VALUE);
        derivedLabel.setStyle("-fx-text-fill: #FDE68A; -fx-font-size: 24px; -fx-background-color: #3B2A07; "
                + "-fx-padding: 8; -fx-background-radius: 6;");
        derivedLabel.managedProperty().bind(derivedLabel.visibleProperty());
        derivedLabel.setVisible(!derivedNotes.isEmpty());
        effectsBox.setPadding(new Insets(10));
        effectsBox.setStyle(CARD_STYLE);
        showEffectsPlaceholder();
        VBox effects = HoloUi.block("🔬", "Як це вплинуло на здоров'я", "Пояснення останньої підтвердженої дії",
                null, derivedLabel, effectsBox);

        VBox right = new VBox(14, todo, doing, meds, effects);
        right.setPadding(new Insets(0, 4, 0, 0));

        GridPane grid = twoColumnGrid(14);
        RowConstraints row = new RowConstraints();
        row.setVgrow(Priority.ALWAYS);
        grid.getRowConstraints().add(row);
        grid.add(scroll(left), 0, 0);
        grid.add(scroll(right), 1, 0);
        return grid;
    }

    private Node buildActionButtons() {
        VBox btns = new VBox(8);
        for (String id : new String[]{"STRENGTH", "CARDIO", "LBNP", "WATER", "MEAL", "BREATH", "SLEEP"}) {
            Activity a = findActivity(id);
            if (a == null) continue;
            Button b = new Button(activityIcon(id) + a.button());
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setWrapText(true);
            b.setStyle("-fx-background-color: " + a.color() + "; -fx-text-fill: white; -fx-font-size: 24px; "
                    + "-fx-padding: 8 12 8 12; -fx-background-radius: 8; -fx-cursor: hand;");
            b.setTooltip(activityTooltip(a));
            b.setOnAction(e -> beginActivity(a));
            activityButtons.put(a.id(), b);
            btns.getChildren().add(b);
        }
        return btns;
    }

    /** Одноразове налаштування панелі «Виконується зараз» (таймер і кнопки підтвердження). */
    private void initSessionPanel() {
        sessionIdle.setWrapText(true);
        sessionIdle.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 24px;");
        sessionTitle.setWrapText(true);
        sessionTitle.setStyle("-fx-text-fill: white; -fx-font-size: 26px; -fx-font-weight: bold;");
        sessionTime.setWrapText(true);
        sessionTime.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 24px;");
        sessionBar.setMaxWidth(Double.MAX_VALUE);
        sessionBar.setStyle("-fx-accent: #38BDF8;");
        confirmBtn.setMaxWidth(Double.MAX_VALUE);
        confirmBtn.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-background-radius: 8; -fx-cursor: hand;");
        confirmBtn.setOnAction(e -> confirmActivity());
        abortBtn.setMaxWidth(Double.MAX_VALUE);
        abortBtn.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #CBD5E1; -fx-background-radius: 8; "
                + "-fx-cursor: hand;");
        abortBtn.setOnAction(e -> abortActivity());
        sessionActive.getChildren().setAll(sessionTitle, sessionBar, sessionTime, confirmBtn, abortBtn);
        sessionActive.setPadding(new Insets(10));
        sessionActive.setStyle(CARD_STYLE);
        sessionActive.managedProperty().bind(sessionActive.visibleProperty());
        sessionIdle.managedProperty().bind(sessionIdle.visibleProperty());
    }

    private Node buildSessionNote() {
        Label rule = new Label("Дія має тривати повний час. Лише після цього кнопка підтвердження стане активною, "
                + "і показники зміняться. Скасовані дії, серії швидких натискань і довгі перерви потрапляють у «Журнал виконання».");
        rule.setWrapText(true);
        rule.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");
        return rule;
    }

    private Node buildPillList() {
        Label note = new Label("Прийом таблетки відмічається вручну: таймером його не перевірити, тому в журналі він "
                + "позначений як «Відмічено вручну». Це демонстраційна модель: реальні дози призначає лікар місії.");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");

        VBox list = new VBox(8);
        for (Pill p : buildPills()) {
            Label pillName = new Label(p.name());
            pillName.setWrapText(true);
            pillName.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 24px; -fx-font-weight: bold;");
            Label pillPurpose = new Label(p.purpose());
            pillPurpose.setWrapText(true);
            pillPurpose.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px;");
            Label pillStatus = new Label();
            pillStatus.setWrapText(true);
            pillStatus.setStyle("-fx-text-fill: #FDE68A; -fx-font-size: 22px;");
            Button pillBtn = new Button();
            pillBtn.setMaxWidth(Double.MAX_VALUE);
            pillBtn.setWrapText(true);
            pillBtn.setAlignment(Pos.CENTER_LEFT);
            pillBtn.setStyle("-fx-background-color: " + p.color() + "; -fx-text-fill: white; -fx-font-size: 24px; -fx-cursor: hand;");
            pillBtn.setOnAction(e -> applyPill(p));
            pillLabels.put(p.id(), pillStatus);
            pillButtons.put(p.id(), pillBtn);

            VBox row = new VBox(5, pillName, pillPurpose, pillStatus, pillBtn);
            row.setPadding(new Insets(10));
            row.setStyle(CARD_STYLE);
            list.getChildren().add(row);
        }
        updatePillViews();
        return new VBox(8, note, list);
    }

    // ============ ЕКРАН 2: ЖУРНАЛ ВИКОНАННЯ ============
    private static Color journalColor(ActivityJournal.Status s) {
        return switch (s) {
            case CONFIRMED -> HoloUi.GREEN;
            case SELF_REPORTED -> HoloUi.CYAN;
            case AUTO -> HoloUi.PURPLE;
            case ABORTED -> HoloUi.AMBER;
            case SUSPICIOUS -> HoloUi.RED;
            case INACTIVITY -> HoloUi.ORANGE;
        };
    }

    private Node buildJournalScreen() {
        journalVerdict.setWrapText(true);
        journalVerdict.setMaxWidth(Double.MAX_VALUE);
        Label how = new Label("Як це працює: дія не змінює показники одразу. Її треба розпочати, виконати повну "
                + "тривалість і підтвердити. Журнал показує, що з цього справді сталося, а також: "
                + "перервані дії, серії швидких натискань (схоже на натикування кнопок) і довгі періоди без жодної "
                + "підтвердженої дії, коли підказки вимагали дій. Те саме потрапляє у звіт для лікаря.");
        how.setWrapText(true);
        how.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 22px;");
        VBox summary = HoloUi.block("🧾", "Контроль виконання", "Чи космонавт справді виконував дії",
                null, journalVerdict, journalChips, how);

        VBox entries = HoloUi.block("📋", "Записи", "Нові зверху", null, journalRows);

        Label emptyLog = new Label("Поки що нічого не зроблено.");
        emptyLog.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 22px;");
        logBox.getChildren().add(emptyLog);
        VBox log = HoloUi.block("📜", "Протокол подій", "Останні 12 записів", null, logBox);

        refreshJournalView();
        VBox root = new VBox(14, summary, entries, log);
        root.setPadding(new Insets(2, 4, 2, 2));
        return scroll(root);
    }

    private void onJournalEntry(ActivityJournal.Entry e) {
        if (e.status().notable) addLog("⚠ [Контроль] " + e.title() + ". " + e.note());
        refreshJournalView();
    }

    private void refreshJournalView() {
        Map<ActivityJournal.Status, Integer> c = journal.counts();
        journalChips.getChildren().clear();
        for (ActivityJournal.Status s : ActivityJournal.Status.values()) {
            journalChips.getChildren().add(HoloUi.chip(s.label + ": " + c.get(s), journalColor(s)));
        }

        int flags = c.get(ActivityJournal.Status.ABORTED) + c.get(ActivityJournal.Status.SUSPICIOUS)
                + c.get(ActivityJournal.Status.INACTIVITY);
        int done = c.get(ActivityJournal.Status.CONFIRMED) + c.get(ActivityJournal.Status.SELF_REPORTED);
        String verdict;
        String color;
        if (journal.entries().isEmpty()) {
            verdict = "Записів ще немає. Показники змінюються лише після виконаної і підтвердженої дії.";
            color = "#94A3B8";
        } else if (c.get(ActivityJournal.Status.SUSPICIOUS) > 0) {
            verdict = "Увага: були серії швидких натискань без реального виконання дій.";
            color = "#FCA5A5";
        } else if (c.get(ActivityJournal.Status.INACTIVITY) > 0) {
            verdict = "Увага: були довгі періоди без підтверджених дій, хоча підказки вимагали їх виконати.";
            color = "#FDBA74";
        } else if (c.get(ActivityJournal.Status.ABORTED) > 0 && done == 0) {
            verdict = "Дії розпочинались, але жодної не завершено.";
            color = "#FBBF24";
        } else {
            verdict = "Дії виконуються й підтверджуються за таймером. Підтверджено або відмічено: " + done + ".";
            color = "#34D399";
        }
        journalVerdict.setText(verdict);
        journalVerdict.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 26px; -fx-font-weight: bold;");
        setCounter(Screen.JOURNAL, String.valueOf(flags), flags > 0);

        journalRows.getChildren().clear();
        List<ActivityJournal.Entry> list = journal.entries();
        if (list.isEmpty()) {
            Label empty = new Label("Поки що записів немає.");
            empty.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 22px;");
            journalRows.getChildren().add(empty);
            return;
        }
        int shown = Math.min(list.size(), 100);
        for (int i = 0; i < shown; i++) journalRows.getChildren().add(journalRow(list.get(i)));
    }

    private Node journalRow(ActivityJournal.Entry e) {
        Label time = new Label(LOG_TIME.format(missionStart.plusSeconds(e.clockSec())));
        time.setMinWidth(64);
        time.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 22px; -fx-font-family: 'Consolas', 'Courier New', monospace;");

        Label chip = HoloUi.chip(e.status().label, journalColor(e.status()));
        chip.setMinWidth(Region.USE_PREF_SIZE);

        Label title = new Label(e.title());
        title.setWrapText(true);
        title.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 24px; -fx-font-weight: bold;");
        Label note = new Label(e.note());
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px;");
        VBox texts = new VBox(2, title, note);
        if (e.plannedSec() > 0) {
            Label dur = new Label("Факт: " + ActivityJournal.formatSec(e.actualSec())
                    + " з " + ActivityJournal.formatSec(e.plannedSec()));
            dur.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 22px;");
            texts.getChildren().add(dur);
        }
        HBox.setHgrow(texts, Priority.ALWAYS);

        HBox row = new HBox(10, time, chip, texts);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setStyle(CARD_STYLE);
        return row;
    }

    // ============ ЕКРАН 3: ІСТОРІЯ І ПРОГНОЗ ============
    private Node buildTrendsScreen() {
        VBox trend = buildTrendSection();
        VBox.setVgrow(trend, Priority.ALWAYS);
        VBox box = HoloUi.block("📈", "Історія і прогноз", "Як змінювався показник і що буде далі",
                "Блакитна лінія: ваш показник. Зелений пунктир: межі норми. Програма продовжує лінію вперед "
                        + "і попереджає за кілька годин, якщо показник збирається вийти за норму.",
                trend);
        return box;
    }

    // ============ ЕКРАН 4: МІСІЯ ============
    private Node buildMissionScreen() {
        Label locLabel = new Label("Де ми зараз:");
        locLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px; -fx-font-weight: bold;");

        locationBar = new ChoiceBar<>(List.of(MissionLocation.values()),
                loc -> switch (loc) {
                    case LEO -> "Навколоземна орбіта (МКС)";
                    case MOON -> "Місячна станція Gateway";
                    case MARS_TRANSIT -> "Політ до Марса";
                    case SOLAR_CONJUNCTION -> "Сонячне з'єднання (блекаут)";
                },
                loc -> loc == MissionLocation.SOLAR_CONJUNCTION ? HoloUi.AMBER : HoloUi.CYAN,
                loc -> switch (loc) {
                    case LEO -> "Зв'язок із Землею майже миттєвий";
                    case MOON -> "Сигнал іде близько 1,3 секунди";
                    case MARS_TRANSIT -> "Сигнал іде близько 14 хвилин";
                    case SOLAR_CONJUNCTION -> "Сонце блокує зв'язок: повна автономія";
                });
        locationBar.setValue(currentLocation);
        locationBar.setOnChange(selected -> {
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

        missionMap = new MissionMapCanvas();
        missionMap.setLocation(currentLocation);
        Pane mapHolder = HoloUi.fill(missionMap, 250);
        mapHolder.setMaxHeight(300);

        latencyNoticeLabel.setWrapText(true);
        latencyNoticeLabel.setMaxWidth(Double.MAX_VALUE);
        updateLatencyDisplay();

        VBox locBlock = HoloUi.block("🛰️", "Де ми та зв'язок із Землею", "Оберіть місце польоту",
                "Чим далі корабель, тим довше сигнал іде до Землі, тому лікар відповідає пізніше. "
                        + "У «Сонячному з'єднанні» Сонце блокує зв'язок.",
                locLabel, locationBar, mapHolder, latencyNoticeLabel,
                SourceLinks.row("Основа:", SpaceSources.hazard("D").source()));

        Button importBtn = new Button("📂 Завантажити дані з файлу (CSV)");
        importBtn.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        importBtn.setTooltip(new Tooltip("Зчитує файл CSV із показниками (формат NASA OSDR) і оновлює екран."));
        importBtn.setOnAction(e -> handleNasaCsvImport(mainStage));

        Button reportBtn = new Button("💾 Зберегти звіт (TXT)");
        reportBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        reportBtn.setTooltip(new Tooltip("Записує у файл показники, підказки та журнал виконання: можна передати лікарю."));
        reportBtn.setOnAction(e -> saveReport(mainStage));

        Button blackoutBtn = new Button("🛡️ Режим без зв'язку");
        blackoutBtn.setStyle("-fx-background-color: #581C87; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        blackoutBtn.setTooltip(new Tooltip("Коли зв'язку немає, штучний інтелект сам піклується про екіпаж."));
        blackoutBtn.setOnAction(e -> openBlackoutVaultWindow(mainStage));

        FlowPane dataRow = new FlowPane(10, 10);
        dataRow.getChildren().addAll(importBtn, reportBtn, blackoutBtn);
        VBox dataBlock = HoloUi.block("📂", "Дані, звіти та автономний режим", "Імпорт, збереження та режим без зв'язку",
                "Імпорт читає готові дані з файлу. Звіт можна зберегти й показати лікарю. "
                        + "Блекаут: режим, коли Земля недоступна.", dataRow);

        Button mindCareBtn = new Button("Психологічна підтримка");
        mindCareBtn.setGraphic(HoloUi.iconMind());
        mindCareBtn.setGraphicTextGap(8);
        mindCareBtn.setStyle("-fx-background-color: #0D9488; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        mindCareBtn.setTooltip(new Tooltip("Дихання, чек-ін настрою, вправи й аварійний сигнал SOS."));
        mindCareBtn.setOnAction(e -> openMindCare());

        VBox mindBlock = HoloUi.block(HoloUi.iconMind(), "Психологічна підтримка", "Дихання, настрій і допомога колезі",
                "Окрема вкладка: дихальна вправа 4-4-4-4, чек-ін настрою, вправа «5-4-3-2-1», щоденні квести "
                        + "й кнопка SOS. Усе працює без зв'язку із Землею. Це не заміна психолога.",
                mindCareBtn);

        Button sansTrigger = new Button("👁 Демо: тиск на очі");
        sansTrigger.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-size: 24px; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        sansTrigger.setOnAction(e -> simulateSansDegradation());

        Button radiationSpike = new Button("☀ Демо: сонячний спалах");
        radiationSpike.setStyle("-fx-background-color: #EA580C; -fx-text-fill: white; -fx-font-size: 24px; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        radiationSpike.setOnAction(e -> simulateRadiationEvent());

        Button resetBtn = new Button("↺ Скинути до норми");
        resetBtn.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #CBD5E1; -fx-font-size: 24px; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        resetBtn.setOnAction(e -> resetAll());

        FlowPane testRow = new FlowPane(10, 10);
        testRow.getChildren().addAll(sansTrigger, radiationSpike, resetBtn);
        VBox testBlock = HoloUi.block("🧪", "Демо-ситуації", "Лише для перевірки програми",
                "Кнопки створюють штучну проблему (наприклад, сонячний спалах). "
                        + "Після цього перейдіть на «Стан і дії» і подивіться, як реагує програма.",
                testRow);

        VBox box = new VBox(14, locBlock, dataBlock, mindBlock, testBlock);
        box.setPadding(new Insets(2, 4, 2, 2));
        return scroll(box);
    }

    // ============ ПЛАШКА «НАПАРНИК У НЕБЕЗПЕЦІ» (видно на всіх екранах) ============
    private final HBox buddySosBanner = new HBox(14);
    private final Label buddySosText = new Label();
    private final Button buddyGoBtn = new Button("🏃 Іду на допомогу");
    private final Button buddyMuteBtn = new Button("🔇 Заглушити");
    private final Button buddyOpenBtn = new Button("👥 Відкрити «Команда»");
    private CrewMemberState bannerReceiver;
    private String bannerSenderId;
    private String lastBannerStyle = "";

    private Node buildBuddySosBanner() {
        buddySosText.setWrapText(true);
        buddySosText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(buddySosText, Priority.ALWAYS);
        String btn = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 20px; -fx-cursor: hand; "
                + "-fx-padding: 6 12; -fx-background-radius: 6; -fx-border-radius: 6; ";
        buddyGoBtn.setStyle("-fx-background-color: #B45309; -fx-border-color: #FBBF24; " + btn);
        buddyMuteBtn.setStyle("-fx-background-color: #1E293B; -fx-border-color: #A3B1C6; " + btn);
        buddyOpenBtn.setStyle("-fx-background-color: #1E293B; -fx-border-color: #38BDF8; " + btn);
        for (Button b : new Button[]{buddyGoBtn, buddyMuteBtn, buddyOpenBtn}) b.setMinWidth(Region.USE_PREF_SIZE);

        buddyGoBtn.setOnAction(e -> {
            if (crewPanel != null && bannerReceiver != null && bannerSenderId != null)
                crewPanel.network().respond(bannerReceiver, bannerSenderId);
        });
        buddyMuteBtn.setOnAction(e -> {
            if (crewPanel != null && bannerReceiver != null && bannerSenderId != null)
                crewPanel.network().mute(bannerReceiver, bannerSenderId);
        });
        buddyOpenBtn.setOnAction(e -> showScreen(Screen.MESH_CREW));

        buddySosBanner.setAlignment(Pos.CENTER_LEFT);
        buddySosBanner.setPadding(new Insets(10, 24, 10, 24));
        buddySosBanner.getChildren().addAll(buddySosText, buddyGoBtn, buddyMuteBtn, buddyOpenBtn);
        buddySosBanner.setVisible(false);
        buddySosBanner.setManaged(false);
        return buddySosBanner;
    }

    /** Викликається щокадру: показує плашку, поки є непогашений сигнал від напарника. */
    private void updateBuddySosBanner() {
        CrewMemberState receiver = null;
        CrewMemberState.IncomingAlert alert = null;
        if (crewPanel != null) {
            CrewMeshNetwork net = crewPanel.network();
            // спершу сигнали, адресовані цьому пристрою, потім будь-які інші
            java.util.List<CrewMemberState> order = new java.util.ArrayList<>();
            order.add(net.local());
            for (CrewMemberState x : net.members()) if (x != net.local()) order.add(x);
            for (CrewMemberState x : order) {
                for (CrewMemberState.IncomingAlert al : x.getAlerts().values()) {
                    if (alert == null || (alert.handled && !al.handled)) { receiver = x; alert = al; }
                }
                if (alert != null && !alert.handled) break;
            }
        }

        boolean on = alert != null;
        if (buddySosBanner.isVisible() != on) {
            buddySosBanner.setVisible(on);
            buddySosBanner.setManaged(on);
        }
        if (!on) { bannerReceiver = null; bannerSenderId = null; return; }

        bannerReceiver = receiver;
        bannerSenderId = alert.senderId;
        double now = crewPanel.network().now();
        boolean pulse = ((int) (now * 2)) % 2 == 0;

        String style = alert.handled
                ? "-fx-background-color: #064E3B; -fx-border-color: #10B981; -fx-border-width: 0 0 3 0;"
                : "-fx-background-color: " + (pulse ? "#7F1D1D" : "#450A0A")
                + "; -fx-border-color: #EF4444; -fx-border-width: 0 0 3 0;";
        if (!style.equals(lastBannerStyle)) {
            lastBannerStyle = style;
            buddySosBanner.setStyle(style);
        }

        String text;
        if (alert.handled) {
            text = "🤝 Допомога на місці: " + alert.senderName + ". Ситуація стабілізується.";
        } else {
            text = "🚨 НАПАРНИК У НЕБЕЗПЕЦІ: " + alert.senderName
                    + String.format(java.util.Locale.US, "  ·  ≈%.0f м від вас", alert.distanceM);
            if (alert.summary != null && !alert.summary.isEmpty()) text += "\n" + alert.summary;
            if (alert.responding && !alert.arrived) {
                text += "\n🏃 Ви в дорозі: ще "
                        + Math.max(0, Math.round(alert.respondDuration * (1 - alert.progress(now)))) + " с";
            }
        }
        buddySosText.setText(text);
        buddySosText.setStyle("-fx-text-fill: " + (alert.handled ? "#6EE7B7" : "#FECACA")
                + "; -fx-font-weight: bold; -fx-font-size: 22px;");

        boolean here = currentScreen == Screen.MESH_CREW;
        buddyGoBtn.setVisible(!alert.responding && !alert.handled);
        buddyGoBtn.setManaged(buddyGoBtn.isVisible());
        buddyMuteBtn.setVisible(!alert.responding && !alert.muted && !alert.handled);
        buddyMuteBtn.setManaged(buddyMuteBtn.isVisible());
        buddyOpenBtn.setVisible(!here);
        buddyOpenBtn.setManaged(!here);
    }

    private Node buildCrewMeshScreen() {
        crewPanel = new CrewMeshPanel(CrewMeshNetwork.createDefaultCrew(), this::addLog, count -> {
            setCounter(Screen.MESH_CREW, "🚨 " + count, count > 0);
        });
        return scroll(crewPanel);
    }

    // ============ НИЖНІЙ РЯДОК ============
    private FlowPane buildBottomStatusBar() {
        FlowPane status = new FlowPane(28, 4);
        status.setPadding(new Insets(8, 24, 8, 24));
        status.setAlignment(Pos.CENTER_LEFT);
        status.setStyle("-fx-background-color: #0B111E; -fx-border-color: #1E293B; -fx-border-width: 1 0 0 0;");

        Label crewStatus = new Label("● Командир: М. Водко. Автономний режим");
        crewStatus.setStyle("-fx-text-fill: #10B981; -fx-font-size: 20px; -fx-font-weight: bold;");
        crewStatus.setMinWidth(Region.USE_PREF_SIZE);

        Label linkStatus = new Label("Зв'язок із Землею (Х'юстон): із затримкою");
        linkStatus.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 20px;");
        linkStatus.setMinWidth(Region.USE_PREF_SIZE);

        blackoutStatusLabel.setStyle("-fx-text-fill: #C084FC; -fx-font-size: 20px; -fx-font-weight: bold;");
        blackoutStatusLabel.setMinWidth(Region.USE_PREF_SIZE);
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
                        "Денну норму тренувань уже виконано. Продовжите завтра. Можна прийняти вітамін D (блок «Препарати»).",
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

        ChoiceBar<MetricType> pick = new ChoiceBar<>(List.of(MetricType.values()), this::shortMetricName,
                t -> HoloUi.CYAN, t -> t.title);
        pick.setValue(chartMetric);
        pick.setOnChange(t -> {
            chartMetric = t;
            updateTrendChart();
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
        forecastLabel.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 22px; "
                + "-fx-background-color: #151F32; -fx-padding: 8; -fx-background-radius: 4;");

        Label legend = new Label("Блакитна лінія: ваш показник. Зелений пунктир: межі норми. "
                + "Прогноз не враховує тимчасових піків після тренувань.");
        legend.setWrapText(true);
        legend.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");

        Label pickCaption = new Label("Оберіть показник, щоб побачити його графік:");
        pickCaption.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px;");
        sec.getChildren().addAll(pickCaption, pick, trendChart, forecastLabel, legend);
        updateTrendChart();
        return sec;
    }

    /** Короткі назви для кнопок вибору показника. */
    private String shortMetricName(MetricType t) {
        return switch (t) {
            case HEART_RATE -> "Пульс";
            case HRV -> "Стрес-баланс (HRV)";
            case BLOOD_PRESSURE -> "Тиск крові";
            case BLOOD_GLUCOSE -> "Цукор";
            case BODY_TEMP -> "Температура";
            case SPO2 -> "Кисень (SpO2)";
            case ICP_OPTIC -> "Зоровий нерв (SANS)";
            case RADIATION_DOSE -> "Радіація";
            case HYDRATION -> "Вода";
            case BONE_DENSITY -> "Кістки";
        };
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
            category.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 22px; -fx-font-weight: bold;");

            Label title = new Label(p.title());
            title.setWrapText(true);
            title.setStyle("-fx-text-fill: white; -fx-font-size: 26px; -fx-font-weight: bold;");

            Label action = new Label("Що робити:\n" + p.action());
            action.setWrapText(true);
            action.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 24px;");

            card.getChildren().addAll(category, title, action);

            // Кнопка «Зробити зараз» прямо на підказці
            Activity act = findActivity(p.actionId());
            if (act != null && blockedReason(act) == null) {
                Button doIt = new Button("▶ Почати: " + act.button() + " ("
                        + ActivityJournal.formatSec(activityDurationSec(act.id())) + ")");
                doIt.setDisable(journal.hasActive());
                doIt.setWrapText(true);
                doIt.setMaxWidth(Double.MAX_VALUE);
                doIt.setStyle("-fx-background-color: " + act.color()
                        + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
                doIt.setOnAction(e -> beginActivity(act));
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
                    + "тому лікар не відповість швидко. Дійте за підказками на екрані «Стан і дії».";
            case SOLAR_CONJUNCTION -> "⚠️ Сонячне з'єднання (Блекаут). Сигнал заблокований Сонцем. "
                    + "Зв'язку із Землею немає. Екіпаж діє самостійно.";
        };
        latencyNoticeLabel.setText(desc);
        if (missionMap != null) missionMap.setLocation(currentLocation);
        latencyNoticeLabel.setStyle("-fx-text-fill: #F59E0B; -fx-font-size: 24px; -fx-background-color: #261E0A; -fx-padding: 8; -fx-background-radius: 4;");
    }

    // ============ ОНОВЛЕННЯ ЕКРАНА ============
    private void updateAllTelemetryViews() {
        int okCount = 0;

        for (MetricType type : MetricType.values()) {
            double val = currentTelemetry.get(type);
            Label lbl = valueLabels.get(type);
            Label deltaLbl = deltaLabels.get(type);
            Label chip = chips.get(type);

            boolean high = val > type.maxNominal;
            boolean low = val < type.minNominal;
            if (!high && !low) okCount++;

            boolean tempOnly = (high || low)
                    && inRange(type, val - tempOffsets.getOrDefault(type, 0.0));

            Color stateColor = (!high && !low) ? HoloUi.GREEN : (tempOnly ? HoloUi.AMBER : HoloUi.RED);
            String textColor = (!high && !low) ? "#F8FAFC" : (tempOnly ? "#FBBF24" : "#FCA5A5");

            if (chip != null) {
                String st = (!high && !low) ? "● Норма"
                        : (high ? "▲ Вище норми" : "▼ Нижче норми") + (tempOnly ? ", тимчасово" : "");
                HoloUi.styleChip(chip, st, stateColor);
            }
            if (lbl != null) {
                lbl.setText(String.format(Locale.US, "%.1f %s", val, type.unit));
                lbl.setStyle("-fx-text-fill: " + textColor + "; -fx-font-size: 24px; -fx-font-weight: bold;");
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
                deltaLbl.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: "
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
        healthVerdict = verdict;
        healthColorHex = color;
        bodyScoreLabel.setText(score + " / 100");
        bodyVerdictLabel.setText(verdict);
        bodyVerdictLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 40px; -fx-font-weight: bold;");
        bodyExplainLabel.setText("У нормі " + okCount + " з " + total + " показників.");
        healthScoreLabel.setText("ЗАГАЛЬНИЙ СТАН: " + score + " з 100 (" + verdict + "). У нормі "
                + okCount + " з " + total + " показників");
        healthBar.setProgress(score / 100.0);
        healthBar.setStyle("-fx-accent: " + color + ";");

        // Підказки перемальовуємо лише коли вони змінились
        runAutonomousTriage();
        updatePillViews();
        updateActivityButtons();
        updateAlertIndicators();
        updateMenuStates();
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
        sb.append(MindCarePanel.summaryText());

        sb.append("\nКОНТРОЛЬ ВИКОНАННЯ (що космонавт справді робив):\n");
        Map<ActivityJournal.Status, Integer> jc = journal.counts();
        for (ActivityJournal.Status st : ActivityJournal.Status.values()) {
            sb.append("- ").append(st.label).append(": ").append(jc.get(st)).append('\n');
        }
        if (journal.entries().isEmpty()) sb.append("(записів немає)\n");
        for (ActivityJournal.Entry je : journal.entries()) {
            sb.append("  [").append(LOG_TIME.format(missionStart.plusSeconds(je.clockSec()))).append("] ")
                    .append(je.status().label).append(": ").append(je.title());
            if (je.plannedSec() > 0) {
                sb.append(" (факт ").append(ActivityJournal.formatSec(je.actualSec()))
                        .append(" з ").append(ActivityJournal.formatSec(je.plannedSec())).append(')');
            }
            sb.append(". ").append(je.note()).append('\n');
        }

        sb.append("\nПРОТОКОЛ ПОДІЙ:\n");
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
        stage.setTitle("VITAL: режим без зв'язку із Землею");

        VBox root = new VBox(14);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #0F0505; -fx-font-family: 'Segoe UI', system-ui, sans-serif;");

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #581C87; -fx-padding: 12; -fx-background-radius: 6;");

        Label icon = new Label("📡❌");
        icon.setStyle("-fx-font-size: 48px;");

        VBox headerText = new VBox(2);
        Label title = new Label("РЕЖИМ БЕЗ ЗВ'ЯЗКУ: ДАНІ ЗБЕРІГАЮТЬСЯ НА КОРАБЛІ");
        title.setStyle("-fx-text-fill: #F3E8FF; -fx-font-size: 28px; -fx-font-weight: 900;");

        Label sub = new Label("Сонце блокує сигнал із Землею. Зв'язку немає. Корабель керується автопілотом VITAL (штучний інтелект).");
        sub.setStyle("-fx-text-fill: #D8B4FE; -fx-font-size: 22px;");
        sub.setWrapText(true);

        headerText.getChildren().addAll(title, sub);
        header.getChildren().addAll(icon, headerText);

        // --- Таймер ---
        VBox counterCard = new VBox(8);
        counterCard.setAlignment(Pos.CENTER);
        counterCard.setPadding(new Insets(14));
        counterCard.setStyle("-fx-background-color: #1E1B4B; -fx-border-color: #4338CA; -fx-border-radius: 6; -fx-background-radius: 6;");

        Label counterTitle = new Label();
        counterTitle.setStyle("-fx-text-fill: #93C5FD; -fx-font-size: 20px; -fx-font-weight: bold;");

        Label countdownDisplay = new Label();
        countdownDisplay.setStyle("-fx-text-fill: #C084FC; -fx-font-size: 56px; -fx-font-weight: 900;");

        ProgressBar blackoutProgress = new ProgressBar(0);
        blackoutProgress.setMaxWidth(Double.MAX_VALUE);

        Label bufferStatus = new Label();
        bufferStatus.setStyle("-fx-text-fill: #A78BFA; -fx-font-size: 22px;");

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
        durationLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px; -fx-font-weight: bold;");

        HBox controlsRow = new HBox(10, durationLabel, durationBox, startStopBtn);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        Label speedHint = new Label("Таймер іде за часом місії: змініть «Швидкість часу» у верхній панелі, щоб прискорити його.");
        speedHint.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");
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
            addLog("⚡ [Аварійний режим] Використано аварійні резерви корабля! Залишилось зарядів: " + emergencyCharges + ".");

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

        Label aiTitle = new Label("ЩО РОБИТЬ АВТОПІЛОТ VITAL БЕЗ ЗВ'ЯЗКУ:");
        aiTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 24px; -fx-font-weight: bold;");

        VBox decisionsList = new VBox(6);
        decisionsList.getChildren().addAll(
                createBlackoutItem("✓ Радіація: водяні щити поступово знижують отриману дозу."),
                createBlackoutItem("✓ Вода: якщо запас біля нижньої межі, автопілот сам п'є 400 мл (у межах денної норми)."),
                createBlackoutItem("✓ Серце й тиск: при підвищеному пульсі, тиску чи низькому стрес-балансі автопілот робить дихальну вправу."),
                createBlackoutItem("✓ Тиск на зоровий нерв: біля межі норми автопілот запускає тренування ніг під низьким тиском (LBNP)."),
                createBlackoutItem("✓ Телеметрія: щохвилини місії пакет шифрується й іде в локальний буфер.")
        );

        Label recentTitle = new Label("ОСТАННІ ДІЇ АВТОПІЛОТА:");
        recentTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 22px; -fx-font-weight: bold;");
        Label recentActions = new Label();
        recentActions.setWrapText(true);
        recentActions.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 22px;");

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
                        + " КБ) чекають відправки. Дій автопілота: " + blackoutAiActions);
                startStopBtn.setText("⏹ Вимкнути режим без зв'язку");
                startStopBtn.setStyle("-fx-background-color: #B91C1C; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            } else {
                counterTitle.setText("РЕЖИМ БЕЗ ЗВ'ЯЗКУ ВИМКНЕНО. ОБЕРІТЬ ТРИВАЛІСТЬ І ЗАПУСТІТЬ");
                countdownDisplay.setText("Зв'язок із Землею є");
                blackoutProgress.setProgress(0);
                bufferStatus.setText("📦 Локальний буфер порожній");
                startStopBtn.setText("▶ Увімкнути режим без зв'язку");
                startStopBtn.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            }
            durationBox.setDisable(on);
            aiBoostBtn.setDisable(!on || emergencyCharges <= 0);
            aiBoostBtn.setText("⚡ Аварійне посилення життєзабезпечення, зарядів: "
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