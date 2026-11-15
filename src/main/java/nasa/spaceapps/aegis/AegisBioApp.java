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
        LEO("Low Earth orbit (ISS)", 0.05),
        MOON("Lunar Gateway station", 1.3),
        MARS_TRANSIT("Flight to Mars", 840.0),
        SOLAR_CONJUNCTION("⚠️ The Sun blocks communication (no link with Earth)", Double.POSITIVE_INFINITY);

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
        HEART_RATE("Pulse", "bpm", 60, 100, 40, 180),
        HRV("Heart stress balance (HRV)", "ms", 45, 120, 10, 150),
        BLOOD_PRESSURE("Blood pressure (systolic)", "mmHg", 105, 135, 80, 200),
        BLOOD_GLUCOSE("Blood glucose", "mg/dL", 70, 110, 40, 250),
        BODY_TEMP("Body temperature", "°C", 36.2, 37.4, 35, 40),
        SPO2("Blood oxygen (SpO2)", "%", 96, 100, 80, 100),
        ICP_OPTIC("Optic nerve pressure (SANS)", "mmHg", 10, 18, 5, 40),
        RADIATION_DOSE("Radiation dose received", "mSv", 0, 100, 0, 1000),
        HYDRATION("Body water reserve", "%", 90, 100, 60, 100),
        BONE_DENSITY("Bone strength", "%", 97, 100, 80, 100);

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
    private final Button confirmBtn = new Button("✔ Confirm completion");
    private final Button abortBtn = new Button("✖ Cancel");
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
        primaryStage.setTitle("VITAL: crew health assistant for deep space");
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
        resetVault();
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
                    "You are short on water. Because of this your pulse is higher by %.0f and your heart stress balance is lower by %.0f. Drink water.",
                    d * 0.9, d * 0.7));
        }

        if (sleepHours < 6.0) {
            double d = 6.0 - sleepHours;
            derivedOffsets.merge(MetricType.HRV, -d * 1.5, Double::sum);
            derivedOffsets.merge(MetricType.HEART_RATE, d * 1.0, Double::sum);
            derivedOffsets.merge(MetricType.BLOOD_GLUCOSE, d * 2.0, Double::sum);
            derivedNotes.add(String.format(Locale.US,
                    "You slept little (%.1f h). Because of this your heart is tired and your blood sugar is higher. Rest.", sleepHours));
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
    /**
     * Merkle-Delta Sync Vault: щохвилини місії в буфер потрапляє один дельта-пакет з усіма показниками,
     * його хеш стає листком дерева Меркла. Буфер живе й після відновлення зв'язку (до наступного блекауту),
     * щоб Земля могла перевірити цілісність.
     */
    private final MerkleDeltaVault vault = new MerkleDeltaVault(MetricType.values().length);
    /** «Копія в ефірі», яку отримала Земля (її можна пошкодити радіацією, потім перевірити). */
    private List<byte[]> vaultAir = null;
    /** Пакети, які Земля знайшла пошкодженими під час останньої перевірки (підсвічуються в дереві). */
    private Set<Integer> vaultFound = Set.of();
    private String vaultVerifyText = "";

    /**
     * Правило автопілота: якщо показник метрики наближається до межі норми
     * (на margin), AI сам виконує дію actionId, поки діють денні ліміти.
     */
    private record AutoRule(String actionId, MetricType metric, boolean whenHigh,
                            double margin, String reason, long cooldownSec) {}

    private static final List<AutoRule> AUTO_RULES = List.of(
            new AutoRule("WATER", MetricType.HYDRATION, false, 1.5,
                    "water reserve is approaching the lower limit", 30 * 60),
            new AutoRule("LBNP", MetricType.ICP_OPTIC, true, 1.5,
                    "optic nerve pressure is approaching the limit", 120 * 60),
            new AutoRule("BREATH", MetricType.HEART_RATE, true, 8,
                    "pulse is elevated", 30 * 60),
            new AutoRule("BREATH", MetricType.HRV, false, 8,
                    "heart stress balance is lowered", 30 * 60),
            new AutoRule("BREATH", MetricType.BLOOD_PRESSURE, true, 6,
                    "blood pressure is approaching the limit", 30 * 60)
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
        return String.format(Locale.US, "%d d  %02d h  %02d m  %02d s",
                sec / 86400, (sec / 3600) % 24, (sec / 60) % 60, sec % 60);
    }

    private long blackoutRemainingSec() {
        return blackoutActive ? Math.max(0, blackoutEndSec - clockSec) : 0;
    }

    private int blackoutPackets() {
        long elapsed = Math.min(clockSec, blackoutEndSec) - blackoutStartSec;
        return (int) Math.max(0, elapsed / 60);
    }

    /** Знімок усіх показників у порядку MetricType.values() (так їх кодує vault). */
    private double[] telemetrySnapshot() {
        MetricType[] all = MetricType.values();
        double[] v = new double[all.length];
        for (int i = 0; i < all.length; i++) v[i] = currentTelemetry.getOrDefault(all[i], 0.0);
        return v;
    }

    /** Докладає в vault пакети за кожну повну хвилину місії, що минула (при прискореному часі їх може бути багато). */
    private void feedVault() {
        int due = blackoutPackets();
        if (vault.size() >= due) return;
        double[] snap = telemetrySnapshot();
        while (vault.size() < due) vault.append(snap);
    }

    private void resetVault() {
        vault.clear();
        vaultAir = null;
        vaultFound = Set.of();
        vaultVerifyText = "";
    }

    private static String fmtBytes(long b) {
        return b < 1024 ? b + " B" : String.format(Locale.US, "%.1f KB", b / 1024.0);
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
        resetVault();

        setLocationSilently(MissionLocation.SOLAR_CONJUNCTION);
        addLog("📡❌ [Blackout] Contact with Earth lost for " + formatDuration(duration).replaceAll("\\s+", " ")
                + ". Control passed to the autopilot.");
        updateBlackoutStatus();
    }

    /** completed = true: таймер дійшов до нуля; false: екіпаж перервав режим достроково. */
    private void endBlackout(boolean completed) {
        if (!blackoutActive) return;
        feedVault(); // догнати пакети, які ще не потрапили в буфер
        long elapsed = Math.min(clockSec, blackoutEndSec) - blackoutStartSec;
        int packets = blackoutPackets();
        int actions = blackoutAiActions;
        double rad = blackoutRadReduced;

        blackoutActive = false;
        setLocationSilently(locationBeforeBlackout);

        addLog(completed
                ? "📡✅ [Blackout] Link restored. Telemetry packets in buffer: " + packets
                + ", Merkle root: " + MerkleDeltaVault.shortHex(vault.root()) + "."
                : "⚠️ [Blackout] Mode interrupted early. Telemetry packets in buffer: " + packets
                + ", Merkle root: " + MerkleDeltaVault.shortHex(vault.root()) + ".");
        updateBlackoutStatus();

        String summary = String.format(Locale.US,
                "Blackout duration: %s%nPackets in buffer: %d (delta %s instead of %s, compression ×%.1f)%n"
                        + "Merkle root: %s%nAutopilot actions: %d%n"
                        + "Radiation dose reduced by shields: %.2f mSv%n%n"
                        + "Integrity can be checked in the “Offline mode” window.",
                formatDuration(elapsed).replaceAll("\\s+", " "), packets,
                fmtBytes(vault.packedBytes()), fmtBytes(vault.rawBytes()), vault.ratio(),
                MerkleDeltaVault.shortHex(vault.root()), actions, rad);
        Alert alert = new Alert(Alert.AlertType.INFORMATION, summary, ButtonType.OK);
        Stage owner = (blackoutStage != null && blackoutStage.isShowing()) ? blackoutStage : mainStage;
        if (owner != null) alert.initOwner(owner);
        alert.setHeaderText(completed ? "Link with Houston restored" : "Blackout interrupted");
        alert.show();
    }

    /** Тихе скасування (наприклад, при повному скиданні симуляції). */
    private void abortBlackoutSilently() {
        blackoutActive = false;
        resetVault();
        setLocationSilently(locationBeforeBlackout);
        updateBlackoutStatus();
    }

    private void updateBlackoutStatus() {
        boolean on = blackoutActive;
        blackoutStatusLabel.setVisible(on);
        blackoutStatusLabel.setManaged(on);
        if (on) {
            blackoutStatusLabel.setText("📡❌ NO LINK: restoring in "
                    + formatDuration(blackoutRemainingSec()).replaceAll("\\s+", " ")
                    + "  |  Autopilot actions: " + blackoutAiActions);
        }
    }

    /** Один крок блекауту (викликається з tick()): таймер, автопілот, періодичний звіт. */
    private void blackoutStep() {
        feedVault();
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
                addLog("🛰️ [Blackout] " + hour + " h have passed. Packets in the local buffer: " + blackoutPackets()
                        + ", autopilot actions: " + blackoutAiActions + ".");
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
            journal.addAuto(a.logText(), "Autopilot (AI): " + r.reason() + ". This is not an astronaut action.", clockSec);

            String msg = a.logText() + " (" + r.reason() + ")";
            addLog("🤖 [Autopilot] " + msg + ".");
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
            addLog("🧠 [MindCare] Stress is critical and not subsiding. Opening the psychological support tab.");
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
            journal.addAuto(breath.logText(), "Full exercise completed in the MindCare tab.", clockSec);
        }
    }

    private void onMindCareSos() {
        if (crewPanel != null) crewPanel.raiseCommanderSos();
        addLog("🆘 [Psychological support] SOS signal sent to mission control on Earth and to the crew.");
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

        list.add(new Activity("STRENGTH", "Strength training (ARED)", "Strength training, 30 min", "#059669", List.of(
                new Effect(MetricType.BONE_DENSITY, 0.4, false, true, "Load on the bones slows calcium loss in weightlessness."),
                new Effect(MetricType.HRV, 1.5, false, true, "Regular training makes the heart more resistant to stress."),
                new Effect(MetricType.HEART_RATE, 15, true, false, "During exertion the heart beats faster. After a few minutes the pulse will return to normal."),
                new Effect(MetricType.BLOOD_PRESSURE, 8, true, false, "During exertion blood pressure rises temporarily."),
                new Effect(MetricType.BODY_TEMP, 0.4, true, false, "The body warmed up from the work."),
                new Effect(MetricType.HYDRATION, -3, false, false, "You lost water through sweat. Drink water.")
        )));

        list.add(new Activity("CARDIO", "Cardio (T2 treadmill / CEVIS cycle)", "Cardio workout, 30 min", "#16A34A", List.of(
                new Effect(MetricType.HRV, 2.5, false, true, "Cardio trains the heart and improves stress balance."),
                new Effect(MetricType.BLOOD_PRESSURE, -3, false, true, "Regular cardio gradually lowers blood pressure."),
                new Effect(MetricType.BONE_DENSITY, 0.1, false, true, "Running with a harness puts a small load on the bones."),
                new Effect(MetricType.HEART_RATE, 28, true, false, "Cardio speeds up the heart a lot. This is normal, the pulse will recover."),
                new Effect(MetricType.BLOOD_GLUCOSE, -10, true, true, "Muscles burn sugar from the blood."),
                new Effect(MetricType.BODY_TEMP, 0.5, true, false, "The body warmed up from the work."),
                new Effect(MetricType.HYDRATION, -4, false, false, "You lost water through sweat. Drink water.")
        )));

        list.add(new Activity("WATER", "Drink water with electrolytes (400 ml)", "Drank 400 ml of water with electrolytes", "#0891B2", List.of(
                new Effect(MetricType.HYDRATION, 4, false, true, "Water replenishes the body's fluid reserve."),
                new Effect(MetricType.HEART_RATE, -2, true, true, "When there is enough water, the heart works more easily.")
        )));

        list.add(new Activity("SLEEP", "Sleep", "Sleep, 2 hours", "#4F46E5", List.of(
                new Effect(MetricType.HRV, 2, false, true, "Sleep restores the nervous system and the heart."),
                new Effect(MetricType.HEART_RATE, -5, true, true, "During sleep the pulse is lower and the heart rests.")
        )));

        list.add(new Activity("MEAL", "Eat (a meal)", "Meal", "#CA8A04", List.of(
                new Effect(MetricType.BLOOD_GLUCOSE, 30, true, false, "After a meal blood sugar rises and returns to normal within 1-2 hours."),
                new Effect(MetricType.HEART_RATE, 3, true, false, "Digestion speeds up the pulse a little.")
        )));

        list.add(new Activity("BREATH", "Breathing exercise", "Breathing exercise, 10 min", "#7C3AED", List.of(
                new Effect(MetricType.HEART_RATE, -6, true, true, "Slow breathing calms the heart."),
                new Effect(MetricType.BLOOD_PRESSURE, -6, true, true, "Slow breathing lowers blood pressure."),
                new Effect(MetricType.HRV, 4, true, true, "Slow breathing improves the heart's stress balance right away.")
        )));

        list.add(new Activity("LBNP", "LBNP session (leg chamber)", "LBNP session, 45 min", "#2563EB", List.of(
                new Effect(MetricType.ICP_OPTIC, -3.5, false, true, "The chamber draws fluid away from the head, optic nerve pressure decreases."),
                new Effect(MetricType.BLOOD_PRESSURE, -5, true, true, "Fluid is redistributed, blood pressure drops temporarily."),
                new Effect(MetricType.HEART_RATE, 6, true, false, "The heart works harder to compensate for the fluid shift.")
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
            if (!automatic) showNotice("Not allowed right now: " + a.logText(), blocked);
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
            addLog("✔ Completed and confirmed: " + a.logText());
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
                new Pill("ANTIOX", "Antioxidant complex (radiation protection)", "#B45309",
                        "Helps cells withstand radiation. Does NOT reduce the dose received, so shelter is more important.",
                        3, 6, 240, List.of(
                        new Effect(MetricType.HRV, 1.0, false, true,
                                "Antioxidants reduce cell damage and heart fatigue."))),
                new Pill("VITD", "Vitamin D3 (for bones)", "#CA8A04",
                        "Helps bones absorb calcium. Does not replace strength training.",
                        2, 2, 720, List.of(
                        new Effect(MetricType.BONE_DENSITY, 0.15, false, true,
                                "Vitamin D helps bones absorb calcium."))),
                new Pill("MAGNESIUM", "Magnesium (against stress and fatigue)", "#0F766E",
                        "Helps nerves and heart recover.",
                        2, 4, 240, List.of(
                        new Effect(MetricType.HRV, 2.0, false, true, "Magnesium supports nerves and heart, stress balance improves."),
                        new Effect(MetricType.HEART_RATE, -1, true, true, "Magnesium calms the heart a little."))),
                new Pill("MELATONIN", "Melatonin (sleep aid)", "#4338CA",
                        "Helps you fall asleep. Does not replace sleep itself.",
                        1, 1, 1440, List.of(
                        new Effect(MetricType.HEART_RATE, -3, true, true, "Melatonin calms the body before sleep."),
                        new Effect(MetricType.HRV, 1.0, false, true, "Calm sleep restores the heart better."))));
    }

    private Pill findPill(String id) {
        for (Pill p : buildPills()) if (p.id().equals(id)) return p;
        return null;
    }

    /** Яка таблетка підходить до підказки (за категорією). */
    private Pill pillForProtocol(ClinicalProtocol p) {
        String id = switch (p.hazardCategory()) {
            case "Radiation" -> "ANTIOX";
            case "Bones" -> "VITD";
            case "Stress and fatigue" -> "MAGNESIUM";
            case "Sleep" -> "MELATONIN";
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
            return new PillDose(0, "Daily limit reached (" + taken + " of " + p.maxPerDay()
                    + " tablets). No more allowed, otherwise there is an overdose risk.");
        }
        Double last = lastPillTime.get(p.id());
        double elapsedMin = last == null ? Double.MAX_VALUE : (clockSec - last) / 60.0;
        if (last != null && elapsedMin < p.intervalMin()) {
            double wait = p.intervalMin() - elapsedMin;
            return new PillDose(0, "The next dose can be taken in " + formatMinutes(wait) + ".");
        }
        int severity = pillSeverity(p.id());
        if (severity == 0) return new PillDose(0, "Not needed now: the metric is normal.");
        int n = Math.min(Math.min(severity, p.maxPerDose()), left);
        return new PillDose(n, "");
    }

    private String formatMinutes(double minutes) {
        if (minutes < 60) return "~" + Math.max(1, Math.round(minutes)) + " min";
        return "~" + Math.round(minutes / 60.0) + " h";
    }

    private String tabletWord(int n) {
        if (n == 1) return "tablet";
        if (n >= 2 && n <= 4) return "tablets";
        return "tablets";
    }

    private void applyPill(Pill p) {
        PillDose d = pillDose(p);
        if (d.tablets() <= 0) {
            journal.rejectedPress(clockSec, System.currentTimeMillis());
            showNotice("Tablets not taken: " + p.name(), d.note());
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

        String logText = "Taken: " + p.name() + ", " + n + " pcs.";
        showEffects(new Activity("PILL_" + p.id(), p.name(), logText, p.color(), scaled));
        addLog(logText);
        journal.addSelfReported(p.name(), n + " pcs. Intake marked by the astronaut: a timer cannot verify it.",
                clockSec, System.currentTimeMillis());
    }

    private void refreshCardPillButton(Pill pill, Button btn) {
        PillDose d = pillDose(pill);
        btn.setDisable(d.tablets() <= 0);
        btn.setText(d.tablets() > 0
                ? "💊 Take " + d.tablets() + " " + tabletWord(d.tablets()) + ": " + pill.name()
                : "💊 " + pill.name() + ": " + d.note());
    }

    private void updatePillViews() {
        for (Pill p : buildPills()) {
            Label status = pillLabels.get(p.id());
            Button btn = pillButtons.get(p.id());
            if (status == null || btn == null) continue;

            PillDose d = pillDose(p);
            int taken = pillsToday.getOrDefault(p.id(), 0);
            String text = String.format(Locale.US, "Taken today: %d of %d tablets (maximum)", taken, p.maxPerDay());
            if (d.tablets() <= 0) text += "\n" + d.note();
            status.setText(text);

            btn.setDisable(d.tablets() <= 0);
            btn.setText(d.tablets() > 0
                    ? "💊 Take " + d.tablets() + " " + tabletWord(d.tablets()) + " (calculated dose)"
                    : "💊 Take (not allowed now)");
            Button cb = cardPillButtons.get(p.id());
            if (cb != null) refreshCardPillButton(p, cb);
        }
    }

    /** Чому дію зараз не можна виконати (денна норма вже виконана). null = можна. */
    private String blockedReason(Activity a) {
        return switch (a.id()) {
            case "WATER" -> hydrationLiters >= WATER_MAX_L - 1e-6
                    ? "You have already drunk the daily water norm (2.8 L). No more drinking: excess water is harmful."
                    : null;
            case "STRENGTH", "CARDIO" -> countermeasureMinutes >= WORKOUT_MAX_MIN
                    ? "Daily workout norm completed (150 min). The body needs to recover."
                    : null;
            case "SLEEP" -> sleepHours >= SLEEP_MAX_H - 1e-6
                    ? "You are already rested (8 h)."
                    : null;
            case "BREATH" -> {
                Long last = lastActivityClock.get("BREATH");
                yield (last != null && clockSec - last < BREATH_COOLDOWN_SEC)
                        ? "The breathing exercise was just done. A 30-minute break is needed between exercises."
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
                    ? "⛔ " + a.button() + " (not now)"
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
            showNotice("Not allowed right now: " + a.logText(), blocked);
            return;
        }
        long dur = activityDurationSec(a.id());
        String err = journal.begin(a.id(), a.logText(), dur, clockSec, System.currentTimeMillis());
        if (err != null) {
            showNotice("Not allowed right now: " + a.logText(), err);
            return;
        }
        addLog("▶ Started: " + a.logText() + " (takes " + ActivityJournal.formatSec(dur) + ").");
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
            showNotice("Action not counted: " + a.logText(), blocked);
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
            sessionTitle.setText("In progress: " + s.title());
            sessionBar.setProgress(Math.min(1.0, (double) journal.elapsedSec(clockSec) / req));
            sessionTime.setText(ready
                    ? "Time is up. If you really did this, confirm."
                    : "Remaining " + ActivityJournal.clock(journal.remainingSec(clockSec))
                    + " of " + ActivityJournal.clock(req) + ". You can confirm after it finishes.");
            confirmBtn.setDisable(!ready);
            abortBtn.setText(ready ? "✖ Not done" : "✖ Cancel (metrics will not change)");
        } else {
            long since = journal.sinceLastConfirmed(clockSec);
            sessionIdle.setText("Nothing is in progress right now. Metrics change only after an action is completed and confirmed. "
                    + (since < 0 ? "No confirmed actions yet."
                    : "Last confirmed action: " + ActivityJournal.formatSec(since) + " ago."));
        }
    }

    /** Нова доба: денні лічильники скидаються. */
    private void startNewDay() {
        countermeasureMinutes = 0;
        hydrationLiters = 0.0;
        pillsToday.clear();
        updateHabitViews();
        addLog("🌅 New day: water, workout and tablet counters reset.");
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
        Label hint = new Label("Here you will see how the last confirmed action affected your health, and why.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 24px;");
        effectsBox.getChildren().add(hint);
    }

    private void showEffects(Activity a) {
        effectsBox.getChildren().clear();

        Label head = new Label("You did: " + a.logText());
        head.setWrapText(true);
        head.setStyle("-fx-text-fill: white; -fx-font-size: 26px; -fx-font-weight: bold;");
        effectsBox.getChildren().add(head);

        for (Effect e : a.effects()) {
            String arrow = e.delta() > 0 ? "▲" : "▼";
            String when = e.temporary() ? "temporarily" : "long-term";
            Label main = new Label(String.format(Locale.US, "%s %s: %+.1f %s (%s)",
                    arrow, e.metric().title, e.delta(), e.metric().unit, when));
            main.setWrapText(true);
            main.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: "
                    + (e.good() ? "#34D399" : "#FBBF24") + ";");

            Label why = new Label("Why: " + e.why());
            why.setWrapText(true);
            why.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px; -fx-padding: 0 0 4 14;");

            effectsBox.getChildren().addAll(main, why);
        }

        Label legend = new Label("Green: good for health. Yellow: a temporary body reaction or resource spent.");
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
        STATUS("🩺", "Status & Actions", "Metrics, hints, your actions"),
        JOURNAL("📋", "Activity Log", "What was actually done"),
        TRENDS("📈", "History & Forecast", "What was and what will be"),
        MISSION("🛰️", "Mission", "Link, data, report"),
        MESH_CREW("👥", "Crew", "Link and crew status"),
        SOURCES("📚", "Sources", "Scientific basis: NASA, CSA"),
        MENU("🏠", "Menu", "Main menu"),
        BODY("🧑‍🚀", "Crew Hologram", "Live metrics on the body"),
        MINDCARE("🧠", "Psychological Support", "Breathing, mood, SOS");

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
    private final Button backBtn = new Button("◀ Menu");
    private final Label pageTitleLabel = new Label();
    private Label headerLogo;
    private final Map<Screen, Boolean> counterOn = new EnumMap<>(Screen.class);
    private int healthScoreValue = 100;
    private String healthVerdict = "All good";
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
                urgent ? "There are urgent hints" : todo ? "There are hints that need action" : "All normal");
        orbitMenu.setItemState(Screen.JOURNAL.name(),
                counterOn.getOrDefault(Screen.JOURNAL, false) ? OrbitMenu.State.WARN : OrbitMenu.State.NEUTRAL,
                counterOn.getOrDefault(Screen.JOURNAL, false) ? "There are unfinished or conflicting entries" : "");
        orbitMenu.setItemState(Screen.TRENDS.name(),
                forecast ? OrbitMenu.State.WARN : OrbitMenu.State.NEUTRAL,
                forecast ? "The forecast warns that a metric will leave its range" : "");
        orbitMenu.setItemState(Screen.MISSION.name(),
                blackoutActive ? OrbitMenu.State.WARN : OrbitMenu.State.NEUTRAL,
                blackoutActive ? "Autonomous mode: no link with Earth" : "");
        boolean sos = counterOn.getOrDefault(Screen.MESH_CREW, false);
        orbitMenu.setItemState(Screen.MESH_CREW.name(),
                sos ? OrbitMenu.State.DANGER : OrbitMenu.State.OK,
                sos ? "A crew member has raised a signal" : "Crew online");
        orbitMenu.setItemState(Screen.SOURCES.name(), OrbitMenu.State.NEUTRAL, "");
        StressEngine.Level lvl = stressEngine.latest().level();
        orbitMenu.setItemState(Screen.MINDCARE.name(),
                isOrderedAtLeast(lvl, StressEngine.Level.CRITICAL) ? OrbitMenu.State.DANGER
                        : isOrderedAtLeast(lvl, StressEngine.Level.HIGH) ? OrbitMenu.State.WARN : OrbitMenu.State.OK,
                "Stress level: " + lvl.label);

        String text;
        Color c;
        if (healthScoreValue >= 90) { text = "All good"; c = Color.web("#3CC48A"); }
        else if (healthScoreValue >= 70) { text = "Good"; c = Color.web("#7FD8A8"); }
        else if (healthScoreValue >= 50) { text = "Needs attention"; c = Color.web("#F2B33D"); }
        else { text = "Dangerous"; c = Color.web("#E5535F"); }
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
        urgentBtn.setText("⚠ URGENT: " + urgent + ". Open hints");
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

        Label clockCaption = new Label("Mission time (UTC)");
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

        Label speedLabel = new Label("Time speed:");
        speedLabel.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 20px; -fx-font-weight: bold;");
        speedLabel.setMinWidth(Region.USE_PREF_SIZE);

        ComboBox<String> speedBox = new ComboBox<>();
        speedBox.getItems().addAll("Normal (×1)", "Fast (×10)", "Very fast (×60)");
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
        backBtn.setTooltip(new Tooltip("Return to the main menu"));
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
                new OrbitMenu.Item(Screen.BODY.name(), "Crew Hologram", "Live metrics on the astronaut's body", true),
                new OrbitMenu.Item(Screen.STATUS.name(), "Status & Actions", "Metrics, hints and your actions"),
                new OrbitMenu.Item(Screen.JOURNAL.name(), "Log", "What was really done"),
                new OrbitMenu.Item(Screen.TRENDS.name(), "History & Forecast", "What was and what will be"),
                new OrbitMenu.Item(Screen.MISSION.name(), "Mission", "Link, data, report"),
                new OrbitMenu.Item(Screen.MESH_CREW.name(), "Crew", "Link and crew status"),
                new OrbitMenu.Item(Screen.SOURCES.name(), "Sources", "Scientific basis: NASA, CSA"),
                new OrbitMenu.Item(Screen.MINDCARE.name(), "Psychological Support", "Breathing, mood, help for a crewmate"));
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
        VBox bodyBlock = HoloUi.block(HoloUi.iconAstronaut(), "Crew Hologram",
                "Glowing dots on the body show live data from your body",
                "Each glowing dot is a body part the app monitors. Hover over it: "
                        + "a plain-language explanation and the normal range will appear. "
                        + "The glow color changes when your condition worsens.",
                bodyAstronaut);
        HBox.setHgrow(bodyBlock, Priority.ALWAYS);
        bodyBlock.setMinWidth(0);

        bodyScoreLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 68px; -fx-font-weight: bold;");
        bodyVerdictLabel.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 40px; -fx-font-weight: bold;");
        bodyExplainLabel.setWrapText(true);
        bodyExplainLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 26px;");
        VBox healthBox = new VBox(4, bodyScoreLabel, bodyVerdictLabel, bodyExplainLabel);
        VBox healthBlock = HoloUi.block("🩺", "Overall condition", "Score from 0 to 100",
                "The app checks 10 metrics. Score = how many of them are normal right now. "
                        + "90 and above: excellent. 70-89: good. 50-69: needs attention. Below 50: dangerous.",
                healthBox);

        heartMonitor = new HeartMonitor();
        heartMonitor.setManaged(false);
        Pane hrHolder = new Pane(heartMonitor);
        hrHolder.setMinHeight(120);
        hrHolder.setPrefHeight(120);
        hrHolder.setMaxHeight(120);
        heartMonitor.widthProperty().bind(hrHolder.widthProperty());
        heartMonitor.heightProperty().bind(hrHolder.heightProperty());
        VBox heartBlock = HoloUi.block("❤", "Heartbeat", "The line runs at your real pulse rate",
                "Green line: the heart is working calmly. Orange: pulse or stress balance out of range.", hrHolder);

        Button toStatus = new Button("All metrics and actions →");
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
            case HEART_RATE -> "How many times per minute the heart beats. Rises after exertion, lower at rest.";
            case HRV -> "Shows how rested the heart and nerves are. The higher, the better you cope with stress.";
            case BLOOD_PRESSURE -> "The force with which blood presses on the vessels. Too high pressure harms the heart.";
            case BLOOD_GLUCOSE -> "Fuel for the brain and muscles. Rises after eating, falls after movement.";
            case BODY_TEMP -> "Body temperature. Rises slightly after a workout.";
            case SPO2 -> "How much oxygen the blood carries. Below 95% you need to check ventilation.";
            case ICP_OPTIC -> "In weightlessness fluid moves to the head and presses on the eyes. This is the main cause of vision problems in flight.";
            case RADIATION_DOSE -> "How much radiation has accumulated. It does not go away, so it is important not to miss it.";
            case HYDRATION -> "How much fluid is in the body. Without water the heart and head get worse.";
            case BONE_DENSITY -> "In weightlessness bones weaken. Strength training slows this.";
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
        StringBuilder sb = new StringBuilder("What will change:");
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

        String[] heads = {"Metric", "Now", "Normal", "Status", "After action", "Source"};
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
                        + "\nClick opens the page in the browser"));
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
        Label goalsNote = new Label("Daily goals count only for confirmed actions. "
                + "NASA guideline: about 2.5 h of exercise, enough water and 8 h of sleep per day.");
        goalsNote.setWrapText(true);
        goalsNote.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");

        VBox summary = HoloUi.block("🩺", "Overall condition", "How many metrics are normal and progress on daily goals",
                null, healthScoreLabel, healthBar, workoutLabel, workoutBar, hydrationLabel, hydrationBar,
                sleepLabel, sleepBar, goalsNote);

        Label legendNote = new Label("The ▲▼ arrows in the “After action” column show how the metric changed after "
                + "the last confirmed action. Hover over the name to read what it means.");
        legendNote.setWrapText(true);
        legendNote.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");
        VBox metrics = HoloUi.block("📊", "Metrics", "10 body measurements with their normal range and current status",
                null, buildMetricsTable(), legendNote);

        updateHabitViews();
        VBox left = new VBox(14, summary, metrics);
        left.setPadding(new Insets(0, 4, 0, 0));

        // ---- права колонка: що робити, ваші дії, препарати, що змінилось
        VBox todo = HoloUi.block("🧭", "What to do now", "Hints, most important first",
                null, protocolContainer);

        initSessionPanel();
        VBox doing = HoloUi.block("▶", "Your actions", "Start an action, perform it, confirm it",
                null, sessionIdle, sessionActive, buildActionButtons(), buildSessionNote());
        updateSessionView();

        VBox meds = HoloUi.block("💊", "Medications", "The app calculates the dose; limits and breaks are taken into account",
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
        VBox effects = HoloUi.block("🔬", "How this affected your health", "Explanation of the last confirmed action",
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
        Label rule = new Label("An action must last its full time. Only then does the confirm button become active, "
                + "and the metrics change. Cancelled actions, bursts of rapid clicks and long breaks go into the “Activity Log”.");
        rule.setWrapText(true);
        rule.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");
        return rule;
    }

    private Node buildPillList() {
        Label note = new Label("Taking a tablet is marked manually: a timer cannot verify it, so in the log it is "
                + "marked “Marked manually”. This is a demo model: real doses are prescribed by the mission doctor.");
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
        Label how = new Label("How it works: an action does not change metrics immediately. It must be started, performed for the full "
                + "duration and confirmed. The log shows what really happened, and also: "
                + "interrupted actions, bursts of rapid clicks (looks like button mashing) and long periods without any "
                + "confirmed action when the hints required action. The same goes into the report for the doctor.");
        how.setWrapText(true);
        how.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 22px;");
        VBox summary = HoloUi.block("🧾", "Execution control", "Whether the astronaut really performed the actions",
                null, journalVerdict, journalChips, how);

        VBox entries = HoloUi.block("📋", "Entries", "Newest first", null, journalRows);

        Label emptyLog = new Label("Nothing has been done yet.");
        emptyLog.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 22px;");
        logBox.getChildren().add(emptyLog);
        VBox log = HoloUi.block("📜", "Event log", "Last 12 entries", null, logBox);

        refreshJournalView();
        VBox root = new VBox(14, summary, entries, log);
        root.setPadding(new Insets(2, 4, 2, 2));
        return scroll(root);
    }

    private void onJournalEntry(ActivityJournal.Entry e) {
        if (e.status().notable) addLog("⚠ [Control] " + e.title() + ". " + e.note());
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
            verdict = "No entries yet. Metrics change only after an action is completed and confirmed.";
            color = "#94A3B8";
        } else if (c.get(ActivityJournal.Status.SUSPICIOUS) > 0) {
            verdict = "Attention: there were bursts of rapid clicks without actually performing the actions.";
            color = "#FCA5A5";
        } else if (c.get(ActivityJournal.Status.INACTIVITY) > 0) {
            verdict = "Attention: there were long periods without confirmed actions, although the hints required them.";
            color = "#FDBA74";
        } else if (c.get(ActivityJournal.Status.ABORTED) > 0 && done == 0) {
            verdict = "Actions were started, but none was finished.";
            color = "#FBBF24";
        } else {
            verdict = "Actions are performed and confirmed by timer. Confirmed or marked: " + done + ".";
            color = "#34D399";
        }
        journalVerdict.setText(verdict);
        journalVerdict.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 26px; -fx-font-weight: bold;");
        setCounter(Screen.JOURNAL, String.valueOf(flags), flags > 0);

        journalRows.getChildren().clear();
        List<ActivityJournal.Entry> list = journal.entries();
        if (list.isEmpty()) {
            Label empty = new Label("No entries yet.");
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
            Label dur = new Label("Actual: " + ActivityJournal.formatSec(e.actualSec())
                    + " of " + ActivityJournal.formatSec(e.plannedSec()));
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
        VBox box = HoloUi.block("📈", "History & Forecast", "How the metric changed and what comes next",
                "Blue line: your metric. Green dashed: normal range. The app extends the line forward "
                        + "and warns hours ahead if a metric is about to leave the normal range.",
                trend);
        return box;
    }

    // ============ ЕКРАН 4: МІСІЯ ============
    private Node buildMissionScreen() {
        Label locLabel = new Label("Where we are now:");
        locLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px; -fx-font-weight: bold;");

        locationBar = new ChoiceBar<>(List.of(MissionLocation.values()),
                loc -> switch (loc) {
                    case LEO -> "Low Earth orbit (ISS)";
                    case MOON -> "Lunar Gateway station";
                    case MARS_TRANSIT -> "Flight to Mars";
                    case SOLAR_CONJUNCTION -> "Solar conjunction (blackout)";
                },
                loc -> loc == MissionLocation.SOLAR_CONJUNCTION ? HoloUi.AMBER : HoloUi.CYAN,
                loc -> switch (loc) {
                    case LEO -> "Link with Earth is almost instant";
                    case MOON -> "The signal takes about 1.3 seconds";
                    case MARS_TRANSIT -> "The signal takes about 14 minutes";
                    case SOLAR_CONJUNCTION -> "The Sun blocks communication: full autonomy";
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

        VBox locBlock = HoloUi.block("🛰️", "Where we are and the link to Earth", "Choose a flight location",
                "The farther the ship, the longer the signal takes to reach Earth, so the doctor replies later. "
                        + "During “Solar conjunction” the Sun blocks the link.",
                locLabel, locationBar, mapHolder, latencyNoticeLabel,
                SourceLinks.row("Based on:", SpaceSources.hazard("D").source()));

        Button importBtn = new Button("📂 Load data from file (CSV)");
        importBtn.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        importBtn.setTooltip(new Tooltip("Reads a CSV file with metrics (NASA OSDR format) and updates the screen."));
        importBtn.setOnAction(e -> handleNasaCsvImport(mainStage));

        Button reportBtn = new Button("💾 Save report (TXT)");
        reportBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        reportBtn.setTooltip(new Tooltip("Writes metrics, hints and the activity log to a file: can be handed to the doctor."));
        reportBtn.setOnAction(e -> saveReport(mainStage));

        Button blackoutBtn = new Button("🛡️ Offline mode");
        blackoutBtn.setStyle("-fx-background-color: #581C87; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        blackoutBtn.setTooltip(new Tooltip("When there is no link, artificial intelligence looks after the crew on its own."));
        blackoutBtn.setOnAction(e -> openBlackoutVaultWindow(mainStage));

        FlowPane dataRow = new FlowPane(10, 10);
        dataRow.getChildren().addAll(importBtn, reportBtn, blackoutBtn);
        VBox dataBlock = HoloUi.block("📂", "Data, reports and autonomous mode", "Import, saving and offline mode",
                "Import reads ready-made data from a file. The report can be saved and shown to the doctor. "
                        + "Blackout: a mode for when Earth is unreachable.", dataRow);

        Button mindCareBtn = new Button("Psychological Support");
        mindCareBtn.setGraphic(HoloUi.iconMind());
        mindCareBtn.setGraphicTextGap(8);
        mindCareBtn.setStyle("-fx-background-color: #0D9488; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        mindCareBtn.setTooltip(new Tooltip("Breathing, mood check-in, exercises and the SOS emergency signal."));
        mindCareBtn.setOnAction(e -> openMindCare());

        VBox mindBlock = HoloUi.block(HoloUi.iconMind(), "Psychological Support", "Breathing, mood and help for a crewmate",
                "A separate tab: 4-4-4-4 breathing exercise, mood check-in, “5-4-3-2-1” exercise, daily quests "
                        + "and an SOS button. Everything works without a link to Earth. This is not a substitute for a psychologist.",
                mindCareBtn);

        Button sansTrigger = new Button("👁 Demo: eye pressure");
        sansTrigger.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-size: 24px; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        sansTrigger.setOnAction(e -> simulateSansDegradation());

        Button radiationSpike = new Button("☀ Demo: solar flare");
        radiationSpike.setStyle("-fx-background-color: #EA580C; -fx-text-fill: white; -fx-font-size: 24px; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        radiationSpike.setOnAction(e -> simulateRadiationEvent());

        Button resetBtn = new Button("↺ Reset to normal");
        resetBtn.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #CBD5E1; -fx-font-size: 24px; "
                + "-fx-cursor: hand; -fx-background-radius: 8;");
        resetBtn.setOnAction(e -> resetAll());

        FlowPane testRow = new FlowPane(10, 10);
        testRow.getChildren().addAll(sansTrigger, radiationSpike, resetBtn);
        VBox testBlock = HoloUi.block("🧪", "Demo situations", "Only for testing the app",
                "The buttons create an artificial problem (for example, a solar flare). "
                        + "Then go to “Status & Actions” and see how the app reacts.",
                testRow);

        VBox box = new VBox(14, locBlock, dataBlock, mindBlock, testBlock);
        box.setPadding(new Insets(2, 4, 2, 2));
        return scroll(box);
    }

    // ============ ПЛАШКА «НАПАРНИК У НЕБЕЗПЕЦІ» (видно на всіх екранах) ============
    private final HBox buddySosBanner = new HBox(14);
    private final Label buddySosText = new Label();
    private final Button buddyGoBtn = new Button("🏃 On my way to help");
    private final Button buddyMuteBtn = new Button("🔇 Mute");
    private final Button buddyOpenBtn = new Button("👥 Open “Crew”");
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
            text = "🤝 Help on site: " + alert.senderName + ". The situation is stabilizing.";
        } else {
            text = "🚨 BUDDY IN DANGER: " + alert.senderName
                    + String.format(java.util.Locale.US, "  ·  ≈%.0f m from you", alert.distanceM);
            if (alert.summary != null && !alert.summary.isEmpty()) text += "\n" + alert.summary;
            if (alert.responding && !alert.arrived) {
                text += "\n🏃 You are on the way, "
                        + Math.max(0, Math.round(alert.respondDuration * (1 - alert.progress(now)))) + " s left";
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

        Label crewStatus = new Label("● Autonomous mode");
        crewStatus.setStyle("-fx-text-fill: #10B981; -fx-font-size: 20px; -fx-font-weight: bold;");
        crewStatus.setMinWidth(Region.USE_PREF_SIZE);

        Label linkStatus = new Label("Link with Earth (Houston): delayed");
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
        clockLabel.setText("Mission day: " + (142 + clockSec / 86400) + "  |  Time (UTC): " + CLOCK_FORMAT.format(now));
        bigClockLabel.setText(LOG_TIME.format(now));
        simClockLabel.setText(String.format(Locale.US, "Mission day %d  |  simulation: %d d %02d:%02d:%02d",
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
            activeProtocols.add(new ClinicalProtocol("Radiation", "You received too much radiation",
                    "1. Go to the shielded compartment immediately (shelter with water-filled walls).\n"
                            + "2. Start taking radioprotective tablets (antioxidants).", "CRITICAL"));
        }
        if (currentTelemetry.get(MetricType.ICP_OPTIC) > 18.0) {
            activeProtocols.add(new ClinicalProtocol("Eyes", "Optic nerve pressure is too high",
                    "1. Do 45 minutes in the LBNP chamber. It draws fluid away from the head.\n"
                            + "2. Put on corrective lenses (for farsightedness).", "HIGH", "LBNP"));
        }
        if (currentTelemetry.get(MetricType.HRV) < 40.0) {
            Activity breathAct = findActivity("BREATH");
            boolean breathBlocked = breathAct != null && blockedReason(breathAct) != null;
            String cause = derivedNotes.isEmpty() ? ""
                    : "Main cause: lack of water or sleep (see the yellow hint). The exercise will only help briefly.\n";
            activeProtocols.add(new ClinicalProtocol("Stress and fatigue", "The body is very tired",
                    cause + "1. Reduce the load on the CEVIS cycle by 20%.\n"
                            + (breathBlocked ? "2. The breathing exercise was just done, a 30-minute break is needed.\n"
                            : "2. Do a breathing exercise.\n")
                            + "3. Turn on the blue light (480 nm) in the cabin.", "MEDIUM", "BREATH"));
        }
        if (currentTelemetry.get(MetricType.HYDRATION) < 90.0) {
            if (hydrationLiters >= WATER_MAX_L - 1e-6) {
                activeProtocols.add(new ClinicalProtocol("Water", "Daily water norm already drunk",
                        "You drank 2.8 of 2.8 L. No more drinking: excess water is harmful.\n"
                                + "1. Avoid heavy workouts so you don't lose water through sweat.\n"
                                + "2. The water counter resets with the new day. If the water reserve does not recover, see a doctor.",
                        "MEDIUM"));
            } else {
                activeProtocols.add(new ClinicalProtocol("Water", "You are short on water",
                        String.format(Locale.US,
                                "Today you drank %.1f of %.1f L. Drink 400 ml of water with electrolytes. Do not drink more than the daily norm.",
                                hydrationLiters, WATER_MAX_L),
                        "MEDIUM", "WATER"));
            }
        }
        if (sleepHours < 6.0) {
            activeProtocols.add(new ClinicalProtocol("Sleep", "You slept too little",
                    String.format(Locale.US, "You slept %.1f h. Sleep more to restore the heart and lower blood sugar.", sleepHours),
                    "MEDIUM", "SLEEP"));
        }
        if (currentTelemetry.get(MetricType.BONE_DENSITY) < 97.0) {
            if (countermeasureMinutes >= WORKOUT_MAX_MIN) {
                activeProtocols.add(new ClinicalProtocol("Bones", "Bones are losing calcium",
                        "The daily workout norm is already done. Continue tomorrow. You can take vitamin D (“Medications” block).",
                        "MEDIUM"));
            } else {
                activeProtocols.add(new ClinicalProtocol("Bones", "Bones are losing calcium",
                        "Do a 30-minute strength workout on ARED. Repeat regularly.",
                        "MEDIUM", "STRENGTH"));
            }
        }
        double bpNoTemp = currentTelemetry.get(MetricType.BLOOD_PRESSURE)
                - tempOffsets.getOrDefault(MetricType.BLOOD_PRESSURE, 0.0);
        if (bpNoTemp > 135.0) {
            activeProtocols.add(new ClinicalProtocol("Pressure", "Elevated blood pressure",
                    "Do a breathing exercise and rest. Avoid heavy exercise until the pressure drops.",
                    "MEDIUM", "BREATH"));
        }

        if (activeProtocols.isEmpty()) {
            activeProtocols.add(new ClinicalProtocol("All good", "All metrics are normal",
                    "Nothing needs to be done. Stick to the meal schedule "
                            + "and do 150 minutes of strength training on the ARED machine.", "NOMINAL"));
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

            String verb = f.direction() == TrendEngine.Direction.UP ? "will rise above normal" : "will fall below normal";
            activeProtocols.add(new ClinicalProtocol(
                    "Forecast",
                    type.title + ": " + verb + " in about " + formatHours(f.hoursToLimit()),
                    "1. " + preventiveAdviceNow(type) + "\n"
                            + "2. See the chart on the “History & Forecast” screen.",
                    "FORECAST",
                    preventiveActionNow(type)));
        }
    }

    private String preventiveAdvice(MetricType type) {
        return switch (type) {
            case ICP_OPTIC -> "Schedule an LBNP session (leg chamber) while optic nerve pressure is still normal.";
            case HYDRATION -> "Drink water with electrolytes in advance.";
            case BONE_DENSITY -> "Schedule a strength workout on ARED.";
            case HRV -> "Schedule a breathing exercise and rest.";
            case BLOOD_PRESSURE -> "Do a cardio workout or a breathing exercise.";
            case BLOOD_GLUCOSE -> "Do a cardio workout and review the meal schedule.";
            case HEART_RATE -> "Check your water reserve and sleep, do a breathing exercise.";
            default -> "Keep an eye on the metric; check water, sleep and workouts.";
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
        if (hours < 1.0) return "~" + Math.max(1, Math.round(hours * 60)) + " min";
        if (hours > 72.0) return "more than 3 days";
        return "~" + Math.round(hours) + " h";
    }

    private VBox buildTrendSection() {
        VBox sec = new VBox(6);
        sec.setPadding(new Insets(8, 0, 0, 0));

        Label title = new Label("HISTORY & FORECAST");
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
        xAxis.setLabel("Simulation time, h");
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

        Label legend = new Label("Blue line: your metric. Green dashed: normal range. "
                + "The forecast ignores temporary spikes after workouts.");
        legend.setWrapText(true);
        legend.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");

        Label pickCaption = new Label("Choose a metric to see its chart:");
        pickCaption.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px;");
        sec.getChildren().addAll(pickCaption, pick, trendChart, forecastLabel, legend);
        updateTrendChart();
        return sec;
    }

    /** Короткі назви для кнопок вибору показника. */
    private String shortMetricName(MetricType t) {
        return switch (t) {
            case HEART_RATE -> "Pulse";
            case HRV -> "Stress balance (HRV)";
            case BLOOD_PRESSURE -> "Blood pressure";
            case BLOOD_GLUCOSE -> "Glucose";
            case BODY_TEMP -> "Temperature";
            case SPO2 -> "Oxygen (SpO2)";
            case ICP_OPTIC -> "Optic nerve (SANS)";
            case RADIATION_DOSE -> "Radiation";
            case HYDRATION -> "Water";
            case BONE_DENSITY -> "Bones";
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
        if (!f.ready()) return "Collecting data for the forecast. A few more measurements without sharp state changes are needed.";

        String rate = String.format(Locale.US, "%+.2f %s/h", f.slopePerHour(), t.unit);
        double steadyNow = currentTelemetry.get(t) - tempOffsets.getOrDefault(t, 0.0);
        if (!inRange(t, steadyNow)) return "The metric is already out of range. Rate of change: " + rate + ".";
        if (f.direction() == TrendEngine.Direction.STABLE) return "The metric is stable (" + rate + ").";
        return String.format(Locale.US,
                "Trend: %s. At this rate the metric will reach the normal limit (%.1f %s) in about %s.",
                rate, f.limit(), t.unit, formatHours(f.hoursToLimit()));
    }

    private String urgencyLabel(String urgency) {
        return switch (urgency) {
            case "CRITICAL" -> "URGENT";
            case "HIGH" -> "IMPORTANT";
            case "MEDIUM" -> "ATTENTION";
            case "FORECAST" -> "FORECAST";
            default -> "ALL GOOD";
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

            Label action = new Label("What to do:\n" + p.action());
            action.setWrapText(true);
            action.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 24px;");

            card.getChildren().addAll(category, title, action);

            // Кнопка «Зробити зараз» прямо на підказці
            Activity act = findActivity(p.actionId());
            if (act != null && blockedReason(act) == null) {
                Button doIt = new Button("▶ Start: " + act.button() + " ("
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
            case LEO -> "You are in low Earth orbit (ISS). The link with Earth is almost instant. "
                    + "If anything is unclear, contact the doctor on Earth right away.";
            case MOON -> "You are near the Moon. The signal to Earth takes about 1.3 seconds. "
                    + "The doctor on Earth replies almost immediately.";
            case MARS_TRANSIT -> "You are flying to Mars. The signal to Earth takes 14 minutes, "
                    + "so the doctor will not reply quickly. Act on the hints on the “Status & Actions” screen.";
            case SOLAR_CONJUNCTION -> "⚠️ Solar conjunction (Blackout). The signal is blocked by the Sun. "
                    + "There is no link with Earth. The crew acts on its own.";
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
                String st = (!high && !low) ? "● Normal"
                        : (high ? "▲ Above normal" : "▼ Below normal") + (tempOnly ? ", temporary" : "");
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
        if (score >= 90) { verdict = "Excellent"; color = "#10B981"; }
        else if (score >= 70) { verdict = "Good"; color = "#0EA5E9"; }
        else if (score >= 50) { verdict = "Needs attention"; color = "#F59E0B"; }
        else { verdict = "Dangerous"; color = "#EF4444"; }
        healthScoreValue = score;
        healthVerdict = verdict;
        healthColorHex = color;
        bodyScoreLabel.setText(score + " / 100");
        bodyVerdictLabel.setText(verdict);
        bodyVerdictLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 40px; -fx-font-weight: bold;");
        bodyExplainLabel.setText("Normal: " + okCount + " of " + total + " metrics.");
        healthScoreLabel.setText("OVERALL CONDITION: " + score + " of 100 (" + verdict + "). Normal: "
                + okCount + " of " + total + " metrics");
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
        workoutLabel.setText(String.format(Locale.US, "Workouts (strength and cardio): %d of 150 min", countermeasureMinutes));
        workoutBar.setProgress(countermeasureMinutes / 150.0);

        hydrationLabel.setText(String.format(Locale.US, "Water with electrolytes: %.1f of 2.8 L", hydrationLiters));
        hydrationBar.setProgress(hydrationLiters / 2.8);

        sleepLabel.setText(String.format(Locale.US, "Sleep: %.1f of 8 h", sleepHours));
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
        addLog("Demo: elevated eye pressure");
    }

    private void simulateRadiationEvent() {
        beforeAction = new EnumMap<>(currentTelemetry);
        baseTelemetry.put(MetricType.RADIATION_DOSE, 125.4);
        baseTelemetry.put(MetricType.HRV, 36.0);
        steadyHistory.clearAll();
        recompute();
        updateAllTelemetryViews();
        addLog("Demo: solar flare");
    }

    // ============ ЗВІТ ============
    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("CREW HEALTH REPORT\n");
        sb.append("Time (UTC): ").append(CLOCK_FORMAT.format(missionNow())).append('\n');
        sb.append("Where we are: ").append(currentLocation.label).append("\n\n");

        sb.append("METRICS:\n");
        for (MetricType type : MetricType.values()) {
            double v = currentTelemetry.get(type);
            String status = inRange(type, v) ? "normal" : (v > type.maxNominal ? "ABOVE NORMAL" : "BELOW NORMAL");
            sb.append(String.format(Locale.US, "- %s: %.1f %s (%s)%n", type.title, v, type.unit, status));
        }

        sb.append("\nHINTS:\n");
        for (ClinicalProtocol p : activeProtocols) {
            sb.append("- [").append(urgencyLabel(p.urgency())).append("] ").append(p.title()).append('\n');
            sb.append("  ").append(p.action().replace("\n", "\n  ")).append('\n');
        }

        sb.append(String.format(Locale.US,
                "%nEXERCISE AND WATER:%n- Workouts: %d of 150 min%n- Water: %.1f of 2.8 L%n- Sleep: %.1f of 8 h%n",
                countermeasureMinutes, hydrationLiters, sleepHours));

        StressEngine.Assessment stress = stressEngine.latest();
        sb.append(String.format(Locale.US, "%nPSYCHOLOGICAL STATE (MindCare):%n- Stress index: %d of 100 (%s)%n",
                stress.index(), stress.level().label));
        if (!stress.reasons().isEmpty()) sb.append("- Contributing factors: ").append(String.join("; ", stress.reasons())).append('\n');
        sb.append(MindCarePanel.summaryText());

        sb.append("\nEXECUTION CONTROL (what the astronaut really did):\n");
        Map<ActivityJournal.Status, Integer> jc = journal.counts();
        for (ActivityJournal.Status st : ActivityJournal.Status.values()) {
            sb.append("- ").append(st.label).append(": ").append(jc.get(st)).append('\n');
        }
        if (journal.entries().isEmpty()) sb.append("(no entries)\n");
        for (ActivityJournal.Entry je : journal.entries()) {
            sb.append("  [").append(LOG_TIME.format(missionStart.plusSeconds(je.clockSec()))).append("] ")
                    .append(je.status().label).append(": ").append(je.title());
            if (je.plannedSec() > 0) {
                sb.append(" (actual ").append(ActivityJournal.formatSec(je.actualSec()))
                        .append(" of ").append(ActivityJournal.formatSec(je.plannedSec())).append(')');
            }
            sb.append(". ").append(je.note()).append('\n');
        }

        sb.append("\nEVENT LOG:\n");
        if (logLines.isEmpty()) sb.append("(empty)\n");
        for (String line : logLines) sb.append(line).append('\n');
        return sb.toString();
    }

    private void saveReport(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save report");
        fc.setInitialFileName("zvit_zdorovya.txt");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text file (TXT)", "*.txt"));
        File file = fc.showSaveDialog(stage);
        if (file == null) return;

        try {
            Files.writeString(file.toPath(), buildReport(), StandardCharsets.UTF_8);
            Alert ok = new Alert(Alert.AlertType.INFORMATION, "Report saved: " + file.getName(), ButtonType.OK);
            ok.initOwner(stage);
            ok.setHeaderText("Done");
            ok.show();
        } catch (Exception ex) {
            Alert err = new Alert(Alert.AlertType.ERROR, "Could not save the report: " + ex.getMessage(), ButtonType.OK);
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
        stage.setTitle("VITAL: offline mode (no link with Earth)");

        VBox root = new VBox(14);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #0F0505; -fx-font-family: 'Segoe UI', system-ui, sans-serif;");

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #581C87; -fx-padding: 12; -fx-background-radius: 6;");

        Label icon = new Label("📡❌");
        icon.setStyle("-fx-font-size: 48px;");

        VBox headerText = new VBox(2);
        Label title = new Label("OFFLINE MODE: DATA IS STORED ON THE SHIP");
        title.setStyle("-fx-text-fill: #F3E8FF; -fx-font-size: 28px; -fx-font-weight: 900;");

        Label sub = new Label("The Sun blocks the signal with Earth. There is no link. The ship is run by the VITAL autopilot (artificial intelligence).");
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
        String[] presetLabels = {"10 minutes (demo)", "1 hour (demo)", "1 day", "14 days 6 h 42 min (full)"};
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

        Label durationLabel = new Label("Duration:");
        durationLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px; -fx-font-weight: bold;");

        HBox controlsRow = new HBox(10, durationLabel, durationBox, startStopBtn);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        Label speedHint = new Label("The timer follows mission time: change “Time speed” in the top bar to speed it up.");
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
            addLog("⚡ [Emergency mode] Emergency ship reserves used! Charges left: " + emergencyCharges + ".");

            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Ship systems redistributed emergency resources. Water and radiation levels improved!\n"
                            + "Emergency charges left: " + emergencyCharges + " of " + EMERGENCY_CHARGES_MAX + ".",
                    ButtonType.OK);
            alert.initOwner(stage);
            alert.setHeaderText("Boost successful");
            alert.show();
        });

        // --- Протоколи й живий журнал автопілота ---
        VBox aiDecisionsCard = new VBox(8);
        aiDecisionsCard.setPadding(new Insets(12));
        aiDecisionsCard.setStyle("-fx-background-color: #1E293B; -fx-border-color: #334155; -fx-border-radius: 6; -fx-background-radius: 6;");

        Label aiTitle = new Label("WHAT THE VITAL AUTOPILOT DOES WITHOUT A LINK:");
        aiTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 24px; -fx-font-weight: bold;");

        VBox decisionsList = new VBox(6);
        decisionsList.getChildren().addAll(
                createBlackoutItem("✓ Radiation: water shields gradually reduce the dose received."),
                createBlackoutItem("✓ Water: if the reserve is near the lower limit, the autopilot drinks 400 ml itself (within the daily norm)."),
                createBlackoutItem("✓ Heart and pressure: with elevated pulse, pressure or low stress balance the autopilot does a breathing exercise."),
                createBlackoutItem("✓ Optic nerve pressure: near the limit the autopilot starts lower body negative pressure (LBNP) leg training."),
                createBlackoutItem("✓ Telemetry: every mission minute a delta packet (changes only) goes into the buffer; its hash is a Merkle tree leaf.")
        );

        Label recentTitle = new Label("LATEST AUTOPILOT ACTIONS:");
        recentTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 22px; -fx-font-weight: bold;");
        Label recentActions = new Label();
        recentActions.setWrapText(true);
        recentActions.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 22px;");

        aiDecisionsCard.getChildren().addAll(aiTitle, decisionsList, recentTitle, recentActions);

        // --- Merkle-Delta Sync Vault: дерево хешів і перевірка цілісності ---
        VBox vaultCard = new VBox(8);
        vaultCard.setPadding(new Insets(12));
        vaultCard.setStyle("-fx-background-color: #1E293B; -fx-border-color: #334155; -fx-border-radius: 6; -fx-background-radius: 6;");

        Label vaultTitle = new Label("MERKLE-DELTA SYNC VAULT: BUFFER INTEGRITY");
        vaultTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 24px; -fx-font-weight: bold;");

        Label vaultStats = new Label();
        vaultStats.setWrapText(true);
        vaultStats.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 20px;");

        MerkleTreeView merkleView = new MerkleTreeView(500);

        Label vaultHint = new Label();
        vaultHint.setWrapText(true);
        vaultHint.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 20px;");

        Label verifyResult = new Label();
        verifyResult.setWrapText(true);
        verifyResult.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 20px;");

        Button corruptBtn = new Button("☢ Simulate radiation in transit (corrupt a packet)");
        corruptBtn.setMaxWidth(Double.MAX_VALUE);
        corruptBtn.setStyle("-fx-background-color: #B45309; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        corruptBtn.setOnAction(e -> {
            if (blackoutActive || vault.size() == 0) return;
            vaultAir = vault.transmissionCopy();
            Random rnd = new Random();
            int want = vault.size() >= 50 ? 2 : 1;
            Set<Integer> hit = new TreeSet<>();
            while (hit.size() < want) hit.add(rnd.nextInt(vault.size()));
            for (int idx : hit) MerkleDeltaVault.flipBit(vaultAir, idx, rnd.nextInt(1 << 16));
            vaultFound = Set.of();
            vaultVerifyText = "☢ Packets corrupted over the air: #" + hit + ". Earth does not know yet: press “Verify delta integrity”.";
            addLog("☢ [Vault] Radiation over the air corrupted packets: " + hit.size() + ".");
            merkleView.show(vault.levels(), vaultFound);
            verifyResult.setText(vaultVerifyText);
        });

        Button verifyBtn = new Button("🔍 Verify delta integrity after the link is restored");
        verifyBtn.setMaxWidth(Double.MAX_VALUE);
        verifyBtn.setStyle("-fx-background-color: #0369A1; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        verifyBtn.setOnAction(e -> {
            if (blackoutActive || vault.size() == 0) return;
            if (vaultAir == null || vaultAir.size() != vault.size()) vaultAir = vault.transmissionCopy();
            MerkleDeltaVault.VerifyReport r = vault.verify(vaultAir);
            vaultFound = new TreeSet<>(r.corrupted());
            if (r.intactOnFirstCheck()) {
                vaultVerifyText = "✅ Roots match (" + MerkleDeltaVault.shortHex(r.senderRoot()) + "). All "
                        + r.packets() + " packets are intact. Only " + MerkleDeltaVault.HASH_BYTES + " B were sent as proof.";
                addLog("✅ [Vault] Verification passed: root " + MerkleDeltaVault.shortHex(r.senderRoot()) + ".");
            } else {
                long spent = r.proofBytes() + r.repairBytes();
                vaultVerifyText = String.format(Locale.US,
                        "⚠️ Roots differ: Earth %s, ship %s.%n"
                                + "Tree descent: hashes requested %d (%s), corrupted packets found #%s.%n"
                                + "Only those were re-sent: %s. Total %s instead of %s (delta buffer) or %s (uncompressed).%n"
                                + "After repair: %s",
                        MerkleDeltaVault.shortHex(r.receiverRoot()), MerkleDeltaVault.shortHex(r.senderRoot()),
                        r.hashesFetched(), fmtBytes(r.proofBytes()), r.corrupted(),
                        fmtBytes(r.repairBytes()), fmtBytes(spent), fmtBytes(r.fullResendBytes()), fmtBytes(r.rawResendBytes()),
                        r.intactAfterRepair() ? "✅ roots match, data intact." : "❌ roots still do not match!");
                addLog("🔧 [Vault] Corrupted packets found and fixed: " + r.corrupted().size() + ".");
            }
            merkleView.show(vault.levels(), vaultFound);
            verifyResult.setText(vaultVerifyText);
        });

        vaultCard.getChildren().addAll(vaultTitle, vaultStats, merkleView, vaultHint, corruptBtn, verifyBtn, verifyResult);

        Button closeBtn = new Button("Return to crew monitoring");
        closeBtn.setMaxWidth(Double.MAX_VALUE);
        closeBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> stage.close());

        int[] vaultShownSize = {-1};

        // --- Оновлення вікна зі стану застосунку (вікно лише показує стан, таймер від нього не залежить) ---
        Runnable refresh = () -> {
            boolean on = blackoutActive;
            if (on) {
                counterTitle.setText("TIME UNTIL LINK WITH HOUSTON IS RESTORED");
                countdownDisplay.setText(formatDuration(blackoutRemainingSec()));
                double total = Math.max(1, blackoutEndSec - blackoutStartSec);
                blackoutProgress.setProgress(Math.min(1.0, Math.max(0.0, (clockSec - blackoutStartSec) / total)));
                bufferStatus.setText("📦 Local buffer: " + vault.size() + " packets (" + fmtBytes(vault.packedBytes())
                        + ") waiting to be sent. Autopilot actions: " + blackoutAiActions);
                startStopBtn.setText("⏹ Turn off offline mode");
                startStopBtn.setStyle("-fx-background-color: #B91C1C; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            } else {
                counterTitle.setText("OFFLINE MODE IS OFF. CHOOSE A DURATION AND START");
                countdownDisplay.setText("Link with Earth is available");
                blackoutProgress.setProgress(0);
                bufferStatus.setText(vault.size() == 0 ? "📦 Local buffer is empty"
                        : "📦 Buffer of the last blackout: " + vault.size() + " packets (" + fmtBytes(vault.packedBytes())
                        + "). You can verify integrity below.");
                startStopBtn.setText("▶ Turn on offline mode");
                startStopBtn.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            }
            // Merkle-картка: дерево перемальовуємо лише коли змінилась кількість пакетів
            if (vault.size() != vaultShownSize[0]) {
                vaultShownSize[0] = vault.size();
                merkleView.show(vault.levels(), vaultFound);
            }
            vaultStats.setText(vault.size() == 0
                    ? "The buffer is empty: the first packet will appear after a minute of mission time."
                    : String.format(Locale.US, "📦 Packets: %d  |  delta: %s instead of %s (compression ×%.1f)%n🌳 Merkle root: %s",
                    vault.size(), fmtBytes(vault.packedBytes()), fmtBytes(vault.rawBytes()), vault.ratio(),
                    MerkleDeltaVault.fullHex(vault.root()).substring(0, 16) + "…"));
            vaultHint.setText(on ? "Verification will be available once the link with Earth is restored."
                    : vault.size() == 0 ? "" : "The link is up: you can send the buffer and verify its root on Earth.");
            corruptBtn.setDisable(on || vault.size() == 0);
            verifyBtn.setDisable(on || vault.size() == 0);
            verifyResult.setText(vaultVerifyText);
            durationBox.setDisable(on);
            aiBoostBtn.setDisable(!on || emergencyCharges <= 0);
            aiBoostBtn.setText("⚡ Emergency life-support boost, charges: "
                    + (on ? emergencyCharges : EMERGENCY_CHARGES_MAX) + "/" + EMERGENCY_CHARGES_MAX);
            recentActions.setText(autopilotRecent.isEmpty()
                    ? (on ? "No intervention needed yet: metrics are normal." : "—")
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

        root.getChildren().addAll(header, counterCard, controlsRow, speedHint, aiBoostBtn, aiDecisionsCard, vaultCard, closeBtn);

        Scene scene = new Scene(scroll(root), 580, 760);
        stage.setScene(scene);
        stage.show();
    }

    // ============ ЗАВАНТАЖЕННЯ ДАНИХ З CSV ============
    private void handleNasaCsvImport(Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Choose a data file (CSV)");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Data files (CSV, TXT)", "*.csv", "*.txt"));

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
            addLog("Data loaded from file " + file.getName());

            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Metrics updated: " + r.values().size()
                            + "\nRows skipped (not recognized): " + r.skipped()
                            + "\nFile: " + file.getName(), ButtonType.OK);
            alert.initOwner(stage);
            alert.setHeaderText("Data loaded");
            alert.show();
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            Alert err = new Alert(Alert.AlertType.ERROR,
                    "Could not read the file: " + (ex != null ? ex.getMessage() : "unknown error"),
                    ButtonType.OK);
            err.initOwner(stage);
            err.show();
        });

        Thread thread = new Thread(task, "csv-import");
        thread.setDaemon(true);
        thread.start();
    }

    public enum CrewStatus {
        NOMINAL("All good", HoloUi.GREEN),
        WARNING("Needs attention", HoloUi.AMBER),
        CRITICAL("Critical / SOS", HoloUi.RED),
        OFFLINE("⚠️ No signal (Mesh Lost)", HoloUi.TEXT_DIM);

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