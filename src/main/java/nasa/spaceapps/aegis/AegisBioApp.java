package nasa.spaceapps.aegis;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
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
        RADIATION_DOSE("Отримана доза радіації", "мЗв", 0, 150, 0, 1000),
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

    /** urgency: CRITICAL / HIGH / MEDIUM / NOMINAL (внутрішні коди; на екрані показуються українською). */
    public record ClinicalProtocol(String hazardCategory, String title, String action, String urgency) {}

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
    private final Map<MetricType, ProgressBar> progressBars = new EnumMap<>(MetricType.class);
    private final VBox protocolContainer = new VBox(8);
    private final Label latencyNoticeLabel = new Label();
    private final Label clockLabel = new Label();
    private final Label healthScoreLabel = new Label();
    private final ProgressBar healthBar = new ProgressBar(1);
    private final Label derivedLabel = new Label();
    private final VBox effectsBox = new VBox(6);
    private final VBox logBox = new VBox(3);
    private final List<String> logLines = new ArrayList<>();
    private Canvas bodyHudCanvas;

    // Анімація
    private double animationPulse = 0;
    private double heartPhase = 0;
    private long lastNanos = 0;
    private int simSpeed = 1;

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
        initDefaultTelemetry();
        showEffectsPlaceholder();
        updateHabitViews();
        updateAllTelemetryViews();
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
            double v = en.getValue() * 0.97;
            if (Math.abs(v) < 0.02) it.remove();
            else en.setValue(v);
        }
        // У невагомості без тренувань стан повільно погіршується
        baseTelemetry.put(MetricType.HYDRATION, clamp(MetricType.HYDRATION, baseTelemetry.get(MetricType.HYDRATION) - 0.01));
        baseTelemetry.put(MetricType.ICP_OPTIC, clamp(MetricType.ICP_OPTIC, baseTelemetry.get(MetricType.ICP_OPTIC) + 0.002));
        baseTelemetry.put(MetricType.BONE_DENSITY, clamp(MetricType.BONE_DENSITY, baseTelemetry.get(MetricType.BONE_DENSITY) - 0.0008));
    }

    private int blackoutTickCounter = 0;

    private void runBlackoutAutonomy() {
        blackoutTickCounter++;
        // Кожні 10 секунд симуляції в режимі блекауту AI виконує автоматичну корекцію
        if (blackoutTickCounter % 10 == 0) {
            double currentRad = baseTelemetry.getOrDefault(MetricType.RADIATION_DOSE, 0.0);
            if (currentRad > 10.0) {
                baseTelemetry.put(MetricType.RADIATION_DOSE, Math.max(0, currentRad - 0.5));
            }

            double hyd = baseTelemetry.getOrDefault(MetricType.HYDRATION, 95.0);
            if (hyd < 98.0) {
                baseTelemetry.put(MetricType.HYDRATION, Math.min(100.0, hyd + 0.3));
            }

            addLog("🛡️ [AI Блекаут] Автономний контур корабля стабілізує показники екіпажу (0 bps).");
        }
    }

    private void tick() {
        for (int i = 0; i < simSpeed; i++) physiologyStep();

        // Якщо активовано режим сонячного з'єднання (блекаут), AI автоматично керує системами
        if (currentLocation == MissionLocation.SOLAR_CONJUNCTION) {
            runBlackoutAutonomy();
        }

        recompute();
        updateAllTelemetryViews();
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

    private void applyActivity(Activity a) {
        beforeAction = new EnumMap<>(currentTelemetry);

        for (Effect e : a.effects()) {
            if (e.temporary()) {
                tempOffsets.merge(e.metric(), e.delta(), Double::sum);
            } else {
                baseTelemetry.put(e.metric(), clamp(e.metric(), baseTelemetry.get(e.metric()) + e.delta()));
            }
        }

        switch (a.id()) {
            case "STRENGTH", "CARDIO" -> countermeasureMinutes = Math.min(150, countermeasureMinutes + 30);
            case "WATER" -> hydrationLiters = Math.min(2.8, hydrationLiters + 0.4);
            case "SLEEP" -> sleepHours = Math.min(8.0, sleepHours + 2.0);
            default -> { }
        }

        recompute();
        updateHabitViews();
        updateAllTelemetryViews();
        showEffects(a);
        addLog(a.logText());
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
        String line = "[" + LOG_TIME.format(Instant.now()) + "] " + text;
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

        ComboBox<MissionLocation> locationBox = new ComboBox<>();
        locationBox.getItems().addAll(MissionLocation.values());
        locationBox.setValue(currentLocation);
        locationBox.setStyle("-fx-font-size: 12px;");
        locationBox.setOnAction(e -> {
            this.currentLocation = locationBox.getValue();
            updateLatencyDisplay();
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

        // І додай blackoutBtn у список усіх елементів шапки:
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
        grid.add(scroll(buildAnatomyHudPanel()), 1, 0);
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
            box.getChildren().add(card);
        }

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

    // КОЛОНКА 2: сканер тіла
    private VBox buildAnatomyHudPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setAlignment(Pos.TOP_CENTER);
        box.setStyle(PANEL_STYLE);

        Label title = new Label("СКАНЕР ТІЛА: ЩО ДІЄТЬСЯ В НЕВАГОМОСТІ");
        title.setStyle(SECTION_TITLE_STYLE);

        bodyHudCanvas = new Canvas(340, 480);

        Label subtext = new Label("Прозорий скафандр показує, що відбувається всередині тіла. Зелений, синій колір: усе гаразд. Жовтий, оранжевий, червоний: потрібна увага. "
                + "Серце на малюнку б'ється з вашою справжньою частотою пульсу. "
                + "У невагомості рідина піднімається до голови, тисне на очі, хребет розтягується, а кістки втрачають кальцій.");
        subtext.setWrapText(true);
        subtext.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px; -fx-text-alignment: center;");

        box.getChildren().addAll(title, bodyHudCanvas, subtext);
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
            b.setOnAction(e -> applyActivity(a));
            actionsBox.getChildren().add(b);
        }

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
        box.getChildren().addAll(cdssTitle, latencyNoticeLabel, protocolContainer,
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

        status.getChildren().addAll(crewStatus, linkStatus, spacer, clockLabel);
        return status;
    }

    private void updateClock() {
        clockLabel.setText("Доба місії: 142  |  Час (UTC): " + CLOCK_FORMAT.format(Instant.now()));
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

    private void drawHolographicBody(GraphicsContext gc) {
        double w = bodyHudCanvas.getWidth();
        double h = bodyHudCanvas.getHeight();

        gc.clearRect(0, 0, w, h);
        gc.setFont(Font.font("Consolas", 11));

        // Сітка сканера
        gc.setStroke(Color.rgb(30, 41, 59, 0.4));
        gc.setLineWidth(1);
        for (double y = 0; y < h; y += 30) gc.strokeLine(0, y, w, y);
        for (double x = 0; x < w; x += 30) gc.strokeLine(x, 0, x, h);

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

        // Промінь сканера, що рухається зверху вниз
        double scanY = (animationPulse * 25) % h;
        gc.setFill(Color.rgb(56, 189, 248, 0.10));
        gc.fillRect(0, scanY - 6, w, 12);
        gc.setStroke(Color.rgb(125, 211, 252, 0.5));
        gc.setLineWidth(1);
        gc.strokeLine(0, scanY, w, scanY);

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
            default -> 3;
        };
    }

    private void runAutonomousTriage() {
        activeProtocols.clear();

        if (currentTelemetry.get(MetricType.RADIATION_DOSE) > 100.0) {
            activeProtocols.add(new ClinicalProtocol(
                    "Радіація",
                    "Ви отримали забагато радіації",
                    "1. Негайно перейдіть у захисний відсік (укриття зі стінами з води).\n"
                            + "2. Почніть приймати радіозахисні таблетки (антиоксиданти).",
                    "CRITICAL"));
        }

        if (currentTelemetry.get(MetricType.ICP_OPTIC) > 18.0) {
            activeProtocols.add(new ClinicalProtocol(
                    "Очі",
                    "Занадто високий тиск на зоровий нерв",
                    "1. Зробіть 45 хвилин у камері LBNP (вакуумна камера для ніг). Вона відтягує рідину від голови. "
                            + "Кнопка «Сеанс LBNP» нижче.\n"
                            + "2. Одягніть коригувальні лінзи (для далекозорості).",
                    "HIGH"));
        }

        if (currentTelemetry.get(MetricType.HRV) < 40.0) {
            activeProtocols.add(new ClinicalProtocol(
                    "Стрес і втома",
                    "Організм дуже втомлений",
                    "1. Зменште навантаження на велотренажері CEVIS на 20%.\n"
                            + "2. Зробіть дихальну вправу 20 хвилин (кнопка нижче).\n"
                            + "3. Увімкніть у каюті синє світло (480 нм).",
                    "MEDIUM"));
        }

        if (currentTelemetry.get(MetricType.HYDRATION) < 90.0) {
            activeProtocols.add(new ClinicalProtocol(
                    "Вода",
                    "Вам не вистачає води",
                    "1. Випийте 400 мл води з електролітами (кнопка «Випити води» нижче).\n"
                            + "2. Повторюйте, поки запас води не стане в нормі.",
                    "MEDIUM"));
        }

        if (currentTelemetry.get(MetricType.BONE_DENSITY) < 97.0) {
            activeProtocols.add(new ClinicalProtocol(
                    "Кістки",
                    "Кістки втрачають кальцій",
                    "1. Зробіть силове тренування на ARED, 30 хвилин (кнопка нижче).\n"
                            + "2. Повторюйте такі тренування регулярно.",
                    "MEDIUM"));
        }

        double bpNoTemp = currentTelemetry.get(MetricType.BLOOD_PRESSURE)
                - tempOffsets.getOrDefault(MetricType.BLOOD_PRESSURE, 0.0);
        if (bpNoTemp > 135.0) {
            activeProtocols.add(new ClinicalProtocol(
                    "Тиск",
                    "Підвищений тиск крові",
                    "1. Зробіть дихальну вправу 10 хвилин (кнопка нижче).\n"
                            + "2. Відпочиньте і не робіть важких вправ, поки тиск не знизиться.",
                    "MEDIUM"));
        }

        if (activeProtocols.isEmpty()) {
            activeProtocols.add(new ClinicalProtocol(
                    "Усе добре",
                    "Усі показники в нормі",
                    "Нічого робити не потрібно. Дотримуйтесь графіка харчування "
                            + "і виконуйте 150 хвилин силових тренувань на тренажері ARED.",
                    "NOMINAL"));
        }

        activeProtocols.sort(Comparator.comparingInt(p -> rank(p.urgency())));
    }

    private String urgencyLabel(String urgency) {
        return switch (urgency) {
            case "CRITICAL" -> "ТЕРМІНОВО";
            case "HIGH" -> "ВАЖЛИВО";
            case "MEDIUM" -> "УВАГА";
            default -> "ВСЕ ГАРАЗД";
        };
    }

    private void renderActiveProtocols() {
        protocolContainer.getChildren().clear();
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
            protocolContainer.getChildren().add(card);
        }
    }

    private String cardStyle(String urgency) {
        String[] colors = switch (urgency) {
            case "CRITICAL" -> new String[]{"#450A0A", "#EF4444"};
            case "HIGH" -> new String[]{"#3B1607", "#F97316"};
            case "MEDIUM" -> new String[]{"#2E1065", "#A855F7"};
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
        StringBuilder sig = new StringBuilder();
        for (ClinicalProtocol p : activeProtocols) sig.append(p.urgency()).append('|').append(p.title()).append(';');
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
    }

    // ============ ТЕСТОВІ СИТУАЦІЇ ============
    private void simulateSansDegradation() {
        beforeAction = new EnumMap<>(currentTelemetry);
        baseTelemetry.put(MetricType.ICP_OPTIC, 22.8);
        baseTelemetry.put(MetricType.BLOOD_PRESSURE, 142.0);
        recompute();
        updateAllTelemetryViews();
        addLog("Тест: підвищений тиск на очі");
    }

    private void simulateRadiationEvent() {
        beforeAction = new EnumMap<>(currentTelemetry);
        baseTelemetry.put(MetricType.RADIATION_DOSE, 125.4);
        baseTelemetry.put(MetricType.HRV, 36.0);
        recompute();
        updateAllTelemetryViews();
        addLog("Тест: сонячний спалах");
    }

    // ============ ЗВІТ ============
    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("ЗВІТ ПРО СТАН ЗДОРОВ'Я ЕКІПАЖУ\n");
        sb.append("Час (UTC): ").append(CLOCK_FORMAT.format(Instant.now())).append('\n');
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

    // ============ ЗАВАНТАЖЕННЯ ДАНИХ З CSV ============
    private record ImportResult(Map<MetricType, Double> values, int skipped) {}

    private void openBlackoutVaultWindow(Stage ownerStage) {
        Stage blackoutStage = new Stage();
        blackoutStage.initOwner(ownerStage);
        blackoutStage.setTitle("Aegis Blackout-Vault: Режим повної автономії (Solar Conjunction)");

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

        Label sub = new Label("Сонце повністю блокує сигнал із Землею. Зв'язок: 0 bps. Екіпаж покладається на Aegis AI.");
        sub.setStyle("-fx-text-fill: #D8B4FE; -fx-font-size: 11px;");

        headerText.getChildren().addAll(title, sub);
        header.getChildren().addAll(icon, headerText);

        VBox counterCard = new VBox(8);
        counterCard.setAlignment(Pos.CENTER);
        counterCard.setPadding(new Insets(14));
        counterCard.setStyle("-fx-background-color: #1E1B4B; -fx-border-color: #4338CA; -fx-border-radius: 6; -fx-background-radius: 6;");

        Label counterTitle = new Label("ЧАС ДО ВІДНОВЛЕННЯ ЗВ'ЯЗКУ З Х'ЮСТОНОМ");
        counterTitle.setStyle("-fx-text-fill: #93C5FD; -fx-font-size: 10px; -fx-font-weight: bold;");

        // 👉 ОСЬ ЦЕЙ РЯДОК ПОТРІБНО ПОВЕРНУТИ:
        Label countdownDisplay = new Label();
        countdownDisplay.setStyle("-fx-text-fill: #C084FC; -fx-font-size: 28px; -fx-font-weight: 900;");

        final int[] blackoutDays = {14};
        final int[] blackoutHours = {6};
        final int[] blackoutMinutes = {42};

        Runnable updateTimerDisplay = () -> {
            countdownDisplay.setText(String.format("%d діб  %02d год  %02d хв", blackoutDays[0], blackoutHours[0], blackoutMinutes[0]));
        };
        updateTimerDisplay.run();

        // Запускаємо таймер, який спрацьовує раз на секунду, але хвилини зменшує плавно (по 1 хвилині, помноженій на масштаб)

                javafx.animation.Timeline blackoutTimer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(60.0 / Math.max(1, simSpeed)), ev -> {
                    blackoutMinutes[0]--;
                    if (blackoutMinutes[0] < 0) {
                        blackoutMinutes[0] = 59;
                        blackoutHours[0]--;
                        if (blackoutHours[0] < 0) {
                            blackoutHours[0] = 23;
                            blackoutDays[0]--;
                            if (blackoutDays[0] < 0) blackoutDays[0] = 0;
                        }
                    }
                    updateTimerDisplay.run();
                })
        );
        blackoutTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        blackoutTimer.play();

        // НЕ ЗАБУДЬ зупинити таймер, коли вікно закривається, щоб він не висів у пам'яті:
        blackoutStage.setOnCloseRequest(e -> blackoutTimer.stop());

        Label bufferStatus = new Label("📦 Локальний буфер: крипто-пакети телеметрії захищені й готові до відправки");
        bufferStatus.setStyle("-fx-text-fill: #A78BFA; -fx-font-size: 11px;");

        counterCard.getChildren().addAll(counterTitle, countdownDisplay, bufferStatus);

        Button aiBoostBtn = new Button("⚡ Активувати форсаж систем життєзабезпечення (AI Override)");
        aiBoostBtn.setMaxWidth(Double.MAX_VALUE);
        aiBoostBtn.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        aiBoostBtn.setOnAction(e -> {
            baseTelemetry.put(MetricType.HYDRATION, 100.0);
            baseTelemetry.put(MetricType.RADIATION_DOSE, Math.max(0, baseTelemetry.get(MetricType.RADIATION_DOSE) - 15.0));
            recompute();
            updateAllTelemetryViews();
            addLog("⚡ [AI Override] Використано аварійні резерви корабля! Показники стабілізовано.");

            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Системи корабля перерозподілили аварійні ресурси. Рівень води та радіації покращено!", ButtonType.OK);
            alert.initOwner(blackoutStage);
            alert.setHeaderText("Форсаж успішний");
            alert.show();
        });

        VBox aiDecisionsCard = new VBox(8);
        aiDecisionsCard.setPadding(new Insets(12));
        aiDecisionsCard.setStyle("-fx-background-color: #1E293B; -fx-border-color: #334155; -fx-border-radius: 6; -fx-background-radius: 6;");

        Label aiTitle = new Label("ЛОКАЛЬНІ ПРОТОКОЛИ БЕЗПЕКИ AEGIS (AI-ARBITRATION):");
        aiTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 12px; -fx-font-weight: bold;");

        VBox decisionsList = new VBox(6);
        decisionsList.getChildren().addAll(
                createBlackoutItem("✓ Контроль дози радіації: автоматично активовано водяні щити."),
                createBlackoutItem("✓ Корекція раціону: збільшено дозу вітаміну D на 20%."),
                createBlackoutItem("✓ Психологічний моніторинг: запити заблоковано в локальний буфер."),
                createBlackoutItem("✓ Поточний контур: AI працює у фоновому режимі та виправляє аномалії.")
        );

        aiDecisionsCard.getChildren().addAll(aiTitle, decisionsList);

        Button closeBtn = new Button("Повернутися до моніторингу екіпажу");
        closeBtn.setMaxWidth(Double.MAX_VALUE);
        closeBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> blackoutStage.close());

        root.getChildren().addAll(header, counterCard, aiBoostBtn, aiDecisionsCard, closeBtn);

        Scene scene = new Scene(root, 540, 560);
        blackoutStage.setScene(scene);
        blackoutStage.show();
    }

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
}