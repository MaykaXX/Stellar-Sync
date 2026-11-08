package nasa.spaceapps.aegis;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class AegisBioApp extends Application {

    // --- ДОМЕННА МОДЕЛЬ ТА ТИПИ ДАНИХ NASA RIDGE ---
    public enum MissionLocation {
        LEO("ННО (ISS)", 0.05),
        MOON("Lunar Gateway", 1.3),
        MARS_TRANSIT("Марсіанський транзит", 840.0); // 14 хв затримки

        final String label;
        final double latencySec;

        MissionLocation(String label, double latencySec) {
            this.label = label;
            this.latencySec = latencySec;
        }

        // Без цього ComboBox показував би LEO / MARS_TRANSIT
        @Override
        public String toString() {
            return label;
        }
    }

    public enum MetricType {
        HEART_RATE("ЧСС", "уд/хв", 60, 100),
        HRV("Варіабельність (HRV)", "мс", 45, 120),
        BLOOD_PRESSURE("АТ (Систолічний)", "мм рт.ст.", 105, 135),
        BLOOD_GLUCOSE("Глюкоза", "мг/дл", 70, 110),
        BODY_TEMP("Температура", "°C", 36.2, 37.4),
        SPO2("SpO2", "%", 96, 100),
        ICP_OPTIC("Тиск зорового нерва (SANS)", "мм рт.ст.", 10, 18),
        RADIATION_DOSE("Кумулятивна доза", "мЗв", 0, 150);

        final String title;
        final String unit;
        final double minNominal;
        final double maxNominal;

        MetricType(String title, String unit, double min, double max) {
            this.title = title;
            this.unit = unit;
            this.minNominal = min;
            this.maxNominal = max;
        }
    }

    public record TelemetryPoint(Instant timestamp, MetricType metric, double value) {}

    public record ClinicalProtocol(String hazardCategory, String title, String action, String urgency) {}

    // --- БАЗОВІ ПОКАЗНИКИ ТА СТАН СИСТЕМИ ---
    private static final double SPINE_ELONGATION_CM = 4.2;
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"));

    private MissionLocation currentLocation = MissionLocation.MARS_TRANSIT;
    private final Map<MetricType, Double> currentTelemetry = new EnumMap<>(MetricType.class);
    private final List<ClinicalProtocol> activeProtocols = new ArrayList<>();

    // Елементи інтерфейсу (ініціалізуємо одразу, щоб не було null при порядку побудови UI)
    private final Map<MetricType, Label> valueLabels = new EnumMap<>(MetricType.class);
    private final Map<MetricType, ProgressBar> progressBars = new EnumMap<>(MetricType.class);
    private final VBox protocolContainer = new VBox(8);
    private final Label latencyNoticeLabel = new Label();
    private final Label clockLabel = new Label();
    private Canvas bodyHudCanvas;
    private double animationPulse = 0;

    // Гейміфікація тренувань
    private int countermeasureMinutes = 75; // Ціль 150 хв (2.5 год)
    private double hydrationLiters = 1.6;   // Ціль 2.8 л
    private final ProgressBar workoutBar = new ProgressBar();
    private final ProgressBar hydrationBar = new ProgressBar();
    private final Label workoutLabel = new Label();
    private final Label hydrationLabel = new Label();

    @Override
    public void start(Stage primaryStage) {
        initDefaultTelemetry();

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #080C14; -fx-font-family: 'Segoe UI', system-ui, sans-serif;");

        root.setTop(buildTopHeader(primaryStage));
        root.setCenter(buildMainDashboard());
        root.setBottom(buildBottomStatusBar());

        // Таймер анімації пульсації датчиків
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                animationPulse += 0.05;
                drawHolographicBody(bodyHudCanvas.getGraphicsContext2D());
            }
        };
        timer.start();

        // Годинник місії оновлюється щосекунди
        Timeline clockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateClock()));
        clockTimeline.setCycleCount(Timeline.INDEFINITE);
        clockTimeline.play();
        updateClock();

        Scene scene = new Scene(root, 1366, 820);
        primaryStage.setTitle("NASA Space Apps 2026 | Aegis-Bio: Deep Space Autonomous Health Console");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void initDefaultTelemetry() {
        currentTelemetry.put(MetricType.HEART_RATE, 78.0);
        currentTelemetry.put(MetricType.HRV, 62.0);
        currentTelemetry.put(MetricType.BLOOD_PRESSURE, 122.0);
        currentTelemetry.put(MetricType.BLOOD_GLUCOSE, 94.0);
        currentTelemetry.put(MetricType.BODY_TEMP, 36.7);
        currentTelemetry.put(MetricType.SPO2, 98.4);
        currentTelemetry.put(MetricType.ICP_OPTIC, 14.5);
        currentTelemetry.put(MetricType.RADIATION_DOSE, 48.2);
    }

    // --- ПАНЕЛЬ НАВІГАЦІЇ ТА ЛОКАЦІЇ МІСІЇ ---
    private HBox buildTopHeader(Stage stage) {
        HBox header = new HBox(20);
        header.setPadding(new Insets(12, 24, 12, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #0F1626; -fx-border-color: #1E293B; -fx-border-width: 0 0 1 0;");

        Label logoTitle = new Label("AEGIS-BIO // OS-26");
        logoTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 17px; -fx-font-weight: 900;");

        Label missionDesc = new Label("Deep-Space Clinical Decision Support System");
        missionDesc.setStyle("-fx-text-fill: #64748B; -fx-font-size: 13px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label locLabel = new Label("РЕЖИМ МІСІЇ:");
        locLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px; -fx-font-weight: bold;");

        ComboBox<MissionLocation> locationBox = new ComboBox<>();
        locationBox.getItems().addAll(MissionLocation.values());
        locationBox.setValue(currentLocation);
        locationBox.setStyle("-fx-font-size: 12px;");
        locationBox.setOnAction(e -> {
            this.currentLocation = locationBox.getValue();
            updateLatencyDisplay();
        });

        Button importBtn = new Button("Імпорт NASA Open Data (CSV)");
        importBtn.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4;");
        importBtn.setOnAction(e -> handleNasaCsvImport(stage));

        header.getChildren().addAll(logoTitle, missionDesc, spacer, locLabel, locationBox, importBtn);
        return header;
    }

    // --- ГОЛОВНИЙ ДАШБОРД (3 КОЛОНКИ) ---
    private GridPane buildMainDashboard() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(16));
        grid.setHgap(16);
        grid.setVgap(16);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(30);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(36);
        ColumnConstraints col3 = new ColumnConstraints();
        col3.setPercentWidth(34);
        grid.getColumnConstraints().addAll(col1, col2, col3);

        RowConstraints row = new RowConstraints();
        row.setVgrow(Priority.ALWAYS);
        grid.getRowConstraints().add(row);

        // Порядок важливий: спочатку панелі, потім загальне оновлення
        grid.add(buildTelemetryPanel(), 0, 0);
        grid.add(buildAnatomyHudPanel(), 1, 0);
        grid.add(buildTriageAndHabitPanel(), 2, 0);

        updateAllTelemetryViews();
        return grid;
    }

    // КОЛОНКА 1: Біометричні показники
    private VBox buildTelemetryPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color: #0E1726; -fx-border-color: #1E293B; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label title = new Label("РЕАЛЬНИЙ ЧАС // БІОМЕТРИЧНИЙ ПОТІК");
        title.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 13px; -fx-font-weight: bold;");
        box.getChildren().add(title);

        for (MetricType type : MetricType.values()) {
            VBox card = new VBox(4);
            card.setPadding(new Insets(8));
            card.setStyle("-fx-background-color: #151F32; -fx-background-radius: 6; -fx-border-color: #24324D; -fx-border-radius: 6;");

            BorderPane cardHead = new BorderPane();
            Label nameLbl = new Label(type.title);
            nameLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

            Label valLbl = new Label();
            valLbl.setStyle("-fx-text-fill: #F8FAFC; -fx-font-size: 13px; -fx-font-weight: bold;");
            valueLabels.put(type, valLbl);

            cardHead.setLeft(nameLbl);
            cardHead.setRight(valLbl);

            ProgressBar bar = new ProgressBar(0.5);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setStyle("-fx-accent: #0284C7;");
            progressBars.put(type, bar);

            card.getChildren().addAll(cardHead, bar);
            box.getChildren().add(card);
        }

        Label simLabel = new Label("СИМУЛЯЦІЯ РИЗИКІВ (RIDGE):");
        simLabel.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 8 0 0 0;");
        box.getChildren().add(simLabel);

        HBox btnRow = new HBox(8);
        Button sansTrigger = new Button("SANS Ризик");
        sansTrigger.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        sansTrigger.setOnAction(e -> simulateSansDegradation());

        Button radiationSpike = new Button("Сонячний спалах (SPE)");
        radiationSpike.setStyle("-fx-background-color: #EA580C; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        radiationSpike.setOnAction(e -> simulateRadiationEvent());

        Button resetBtn = new Button("Номінал");
        resetBtn.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-cursor: hand;");
        resetBtn.setOnAction(e -> {
            initDefaultTelemetry();
            updateAllTelemetryViews();
        });

        btnRow.getChildren().addAll(sansTrigger, radiationSpike, resetBtn);
        box.getChildren().add(btnRow);

        return box;
    }

    // КОЛОНКА 2: Голографічний анатомічний сканер тіла
    private VBox buildAnatomyHudPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setAlignment(Pos.TOP_CENTER);
        box.setStyle("-fx-background-color: #0E1726; -fx-border-color: #1E293B; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label title = new Label("ГОЛОГРАФІЧНИЙ СКАНЕР МІКРОГРАВІТАЦІЇ");
        title.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 13px; -fx-font-weight: bold;");

        bodyHudCanvas = new Canvas(340, 520);

        Label subtext = new Label("Ознаки синдромів: нейроокулярний (SANS), зміщення рідин до голови, втрата кальцію з кісток");
        subtext.setWrapText(true);
        subtext.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px; -fx-text-alignment: center;");

        box.getChildren().addAll(title, bodyHudCanvas, subtext);
        return box;
    }

    // КОЛОНКА 3: Автономні приписи CDSS та щоденні контрзаходи
    private VBox buildTriageAndHabitPanel() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color: #0E1726; -fx-border-color: #1E293B; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label cdssTitle = new Label("АВТОНОМНИЙ КЛІНІЧНИЙ ТРИАЖ (CDSS)");
        cdssTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 13px; -fx-font-weight: bold;");

        latencyNoticeLabel.setWrapText(true);
        latencyNoticeLabel.setMaxWidth(Double.MAX_VALUE);
        updateLatencyDisplay();

        Label habitTitle = new Label("ОБОВ'ЯЗКОВІ ПРОТОКОЛИ КОНТРЗАХОДІВ");
        habitTitle.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 13px; -fx-font-weight: bold; -fx-padding: 8 0 0 0;");

        VBox habitsBox = new VBox(8);
        habitsBox.setPadding(new Insets(10));
        habitsBox.setStyle("-fx-background-color: #151F32; -fx-background-radius: 6; -fx-border-color: #24324D; -fx-border-radius: 6;");

        workoutLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");
        workoutBar.setMaxWidth(Double.MAX_VALUE);
        workoutBar.setStyle("-fx-accent: #10B981;");

        Button addWorkoutBtn = new Button("+30 хв Силові (ARED)");
        addWorkoutBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        addWorkoutBtn.setOnAction(e -> {
            countermeasureMinutes = Math.min(150, countermeasureMinutes + 30);
            updateHabitViews();
        });

        hydrationLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");
        hydrationBar.setMaxWidth(Double.MAX_VALUE);
        hydrationBar.setStyle("-fx-accent: #06B6D4;");

        Button addWaterBtn = new Button("+400 мл Електроліти");
        addWaterBtn.setStyle("-fx-background-color: #0891B2; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        addWaterBtn.setOnAction(e -> {
            hydrationLiters = Math.min(2.8, hydrationLiters + 0.4);
            updateHabitViews();
        });

        habitsBox.getChildren().addAll(workoutLabel, workoutBar, addWorkoutBtn,
                new Separator(), hydrationLabel, hydrationBar, addWaterBtn);

        updateHabitViews();
        box.getChildren().addAll(cdssTitle, latencyNoticeLabel, protocolContainer, habitTitle, habitsBox);
        return box;
    }

    private HBox buildBottomStatusBar() {
        HBox status = new HBox(20);
        status.setPadding(new Insets(8, 24, 8, 24));
        status.setAlignment(Pos.CENTER_LEFT);
        status.setStyle("-fx-background-color: #0B111E; -fx-border-color: #1E293B; -fx-border-width: 1 0 0 0;");

        Label crewStatus = new Label("● ЕКІПАЖ: CDR M. VODKO (СТАТУС: АВТОНОМНИЙ)");
        crewStatus.setStyle("-fx-text-fill: #10B981; -fx-font-size: 11px; -fx-font-weight: bold;");

        Label linkStatus = new Label("ЗВ'ЯЗОК З ЦУП (Х'ЮСТОН): АСИНХРОННИЙ БОРТОВИЙ БУФЕР");
        linkStatus.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        clockLabel.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");

        status.getChildren().addAll(crewStatus, linkStatus, spacer, clockLabel);
        return status;
    }

    private void updateClock() {
        clockLabel.setText("MISSION TIME: SOL 142 // " + CLOCK_FORMAT.format(Instant.now()));
    }

    // --- МАЛЮВАННЯ ГОЛОГРАФІЧНОГО ТІЛА ТА ДАТЧИКІВ НА CANVAS ---

    /** Світна точка з центром у (cx, cy): пульсує від центру, а не зсувається. */
    private void glowDot(GraphicsContext gc, double cx, double cy, double size, Color color) {
        // Ореол
        gc.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.18));
        double halo = size * 2.6;
        gc.fillOval(cx - halo / 2, cy - halo / 2, halo, halo);
        // Ядро
        gc.setFill(color);
        gc.fillOval(cx - size / 2, cy - size / 2, size, size);
    }

    /** Підпис із напівпрозорою підкладкою, щоб не зливався з лініями силуету. */
    private void drawTag(GraphicsContext gc, String text, double x, double y, Color color) {
        double textWidth = text.length() * 6.6;
        gc.setFill(Color.rgb(8, 12, 20, 0.8));
        gc.fillRect(x - 3, y - 11, textWidth + 6, 15);
        gc.setFill(color);
        gc.fillText(text, x, y);
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

        double centerX = w / 2;
        double headY = 70;

        // Силует тіла
        gc.setStroke(Color.rgb(56, 189, 248, 0.6));
        gc.setLineWidth(2);
        gc.strokeOval(centerX - 24, headY - 24, 48, 48);                                   // голова
        gc.strokeLine(centerX, headY + 24, centerX, headY + 180);                          // хребет
        gc.strokeLine(centerX - 70, headY + 55, centerX + 70, headY + 55);                 // плечі
        gc.strokeLine(centerX - 70, headY + 55, centerX - 85, headY + 170);                // ліва рука
        gc.strokeLine(centerX + 70, headY + 55, centerX + 85, headY + 170);                // права рука
        gc.strokeLine(centerX - 40, headY + 180, centerX + 40, headY + 180);               // таз
        gc.strokeLine(centerX - 35, headY + 180, centerX - 45, headY + 360);               // ліва нога
        gc.strokeLine(centerX + 35, headY + 180, centerX + 45, headY + 360);               // права нога

        // Пульсація: розмір завжди додатний (4..8)
        double pulse = 6 + 2 * Math.sin(animationPulse);

        // 1. Очі / SANS
        double icp = currentTelemetry.get(MetricType.ICP_OPTIC);
        Color sansColor = icp > 18.0 ? Color.rgb(239, 68, 68, 0.95) : Color.rgb(16, 185, 129, 0.9);
        glowDot(gc, centerX - 8, headY - 3, pulse, sansColor);
        glowDot(gc, centerX + 8, headY - 3, pulse, sansColor);
        drawTag(gc, String.format(Locale.US, "SANS: %.1f mmHg", icp), centerX + 34, headY - 20, sansColor);

        // 2. Серцево-судинна зона
        boolean heartWarn = currentTelemetry.get(MetricType.HEART_RATE) > 100
                || currentTelemetry.get(MetricType.HRV) < 40;
        Color heartColor = heartWarn ? Color.rgb(249, 115, 22, 0.95) : Color.rgb(56, 189, 248, 0.9);
        glowDot(gc, centerX, headY + 75, pulse + 2, heartColor);
        drawTag(gc, String.format(Locale.US, "HRV: %.0f ms", currentTelemetry.get(MetricType.HRV)),
                8, headY + 79, heartColor);

        // 3. Хребет
        Color spineColor = Color.rgb(168, 85, 247, 0.9);
        glowDot(gc, centerX, headY + 135, pulse, spineColor);
        drawTag(gc, String.format(Locale.US, "Spine: +%.1f cm", SPINE_ELONGATION_CM),
                centerX + 12, headY + 139, spineColor);

        // 4. Кістки ніг
        Color boneColor = Color.rgb(234, 179, 8, 0.9);
        glowDot(gc, centerX - 42, headY + 285, pulse, boneColor);
        glowDot(gc, centerX + 42, headY + 285, pulse, boneColor);
        drawTag(gc, "Bone Mineral Density", centerX - 66, headY + 390, boneColor);
    }

    // --- ЛОГІКА АВТОНОМНОГО КЛІНІЧНОГО ТРИАЖУ (CDSS RULES) ---
    private void runAutonomousTriage() {
        activeProtocols.clear();

        if (currentTelemetry.get(MetricType.ICP_OPTIC) > 18.0) {
            activeProtocols.add(new ClinicalProtocol(
                    "MICRO-G / SANS",
                    "Підвищений набряк диска зорового нерва",
                    "Застосувати сесію LBNP (Lower Body Negative Pressure) на 45 хв для відтоку рідини з черепа. Одягти гіперопічні корекційні лінзи.",
                    "HIGH"
            ));
        }

        if (currentTelemetry.get(MetricType.RADIATION_DOSE) > 100.0) {
            activeProtocols.add(new ClinicalProtocol(
                    "RADIATION HAZARD",
                    "Кумулятивний поріг дози перевищено",
                    "Перейти до штормового відсіку корабля (Water-wall shelter). Розпочати прийом пероральних радіопротекторів / антиоксидантів.",
                    "CRITICAL"
            ));
        }

        if (currentTelemetry.get(MetricType.HRV) < 40.0) {
            activeProtocols.add(new ClinicalProtocol(
                    "ISOLATION / STRESS",
                    "Глибоке вегетативне виснаження",
                    "Знизити навантаження на ергометрі CEVIS на 20%. Провести 20-хвилинний керований сеанс дихальної біоадаптації, спектральний зсув освітлення в 480nm.",
                    "MEDIUM"
            ));
        }

        if (activeProtocols.isEmpty()) {
            activeProtocols.add(new ClinicalProtocol(
                    "NOMINAL FLIGHT",
                    "Усі вітальні системи в межах норми",
                    "Продовжувати дотримання графіка харчування та 150-хвилинного протоколу силових тренувань ARED.",
                    "NOMINAL"
            ));
        }
    }

    private void renderActiveProtocols() {
        protocolContainer.getChildren().clear();
        for (ClinicalProtocol p : activeProtocols) {
            VBox card = new VBox(4);
            card.setPadding(new Insets(10));
            card.setStyle(switch (p.urgency()) {
                case "CRITICAL" -> "-fx-background-color: #450A0A; -fx-border-color: #EF4444; -fx-border-radius: 6; -fx-background-radius: 6;";
                case "HIGH" -> "-fx-background-color: #3B1607; -fx-border-color: #F97316; -fx-border-radius: 6; -fx-background-radius: 6;";
                case "MEDIUM" -> "-fx-background-color: #2E1065; -fx-border-color: #A855F7; -fx-border-radius: 6; -fx-background-radius: 6;";
                default -> "-fx-background-color: #064E3B; -fx-border-color: #10B981; -fx-border-radius: 6; -fx-background-radius: 6;";
            });

            Label category = new Label("[" + p.hazardCategory() + "] " + p.urgency());
            category.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-font-weight: bold;");

            Label title = new Label(p.title());
            title.setWrapText(true);
            title.setStyle("-fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold;");

            Label action = new Label("ПРИПИС: " + p.action());
            action.setWrapText(true);
            action.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11px;");

            card.getChildren().addAll(category, title, action);
            protocolContainer.getChildren().add(card);
        }
    }

    private void updateLatencyDisplay() {
        boolean mars = currentLocation == MissionLocation.MARS_TRANSIT;
        String desc = String.format(Locale.US,
                "Локація: %s | Затримка сигналу із Землею: %.2f с (%s). Зв'язок у реальному часі %s. Рішення приймаються автономно на борту.",
                currentLocation.label,
                currentLocation.latencySec,
                mars ? "14 хв" : "майже миттєво",
                mars ? "НЕМОЖЛИВИЙ" : "ДОСТУПНИЙ");
        latencyNoticeLabel.setText(desc);
        latencyNoticeLabel.setStyle("-fx-text-fill: #F59E0B; -fx-font-size: 11px; -fx-background-color: #261E0A; -fx-padding: 8; -fx-background-radius: 4;");
    }

    private void updateAllTelemetryViews() {
        for (MetricType type : MetricType.values()) {
            double val = currentTelemetry.get(type);
            Label lbl = valueLabels.get(type);
            ProgressBar bar = progressBars.get(type);

            if (lbl != null) lbl.setText(String.format(Locale.US, "%.1f %s", val, type.unit));
            if (bar != null) {
                double range = type.maxNominal - type.minNominal;
                double normalized = range > 0 ? (val - type.minNominal) / range : 0;
                bar.setProgress(Math.max(0.05, Math.min(1.0, normalized)));
                boolean outOfRange = val > type.maxNominal || val < type.minNominal;
                bar.setStyle(outOfRange ? "-fx-accent: #EF4444;" : "-fx-accent: #0284C7;");
            }
        }
        runAutonomousTriage();
        renderActiveProtocols();
    }

    private void updateHabitViews() {
        workoutLabel.setText(String.format(Locale.US, "Фізичні контрзаходи (ARED/T2): %d / 150 хв", countermeasureMinutes));
        workoutBar.setProgress(countermeasureMinutes / 150.0);

        hydrationLabel.setText(String.format(Locale.US, "Гідратація та електроліти: %.1f / 2.8 л", hydrationLiters));
        hydrationBar.setProgress(hydrationLiters / 2.8);
    }

    // --- СЦЕНАРІЇ ТЕСТУВАННЯ ---
    private void simulateSansDegradation() {
        currentTelemetry.put(MetricType.ICP_OPTIC, 22.8);
        currentTelemetry.put(MetricType.BLOOD_PRESSURE, 142.0);
        updateAllTelemetryViews();
    }

    private void simulateRadiationEvent() {
        currentTelemetry.put(MetricType.RADIATION_DOSE, 125.4);
        currentTelemetry.put(MetricType.HRV, 36.0);
        updateAllTelemetryViews();
    }

    // --- ПАРСЕР ВІДКРИТИХ ДАНИХ NASA (CSV INGESTION) ---
    private void handleNasaCsvImport(Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Відкрити датасет NASA Open Data / OSDR (CSV)");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Data Files", "*.csv", "*.txt"));

        File file = fileChooser.showOpenDialog(stage);
        if (file == null) return;

        int updatedCount = 0;
        int skippedCount = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                if (isHeader) { isHeader = false; continue; }
                if (line.isBlank()) continue;

                String[] tokens = line.split("[,;]");
                if (tokens.length < 2) { skippedCount++; continue; }

                String rawName = tokens[0].trim();
                if (rawName.isEmpty()) { skippedCount++; continue; }
                String metricKey = rawName.toUpperCase().replace(' ', '_');

                double value;
                try {
                    value = Double.parseDouble(tokens[1].trim());
                } catch (NumberFormatException ex) {
                    skippedCount++;
                    continue;
                }

                boolean matched = false;
                for (MetricType type : MetricType.values()) {
                    if (metricKey.equals(type.name()) || rawName.equalsIgnoreCase(type.title)) {
                        currentTelemetry.put(type, value);
                        updatedCount++;
                        matched = true;
                        break;
                    }
                }
                if (!matched) skippedCount++;
            }

            updateAllTelemetryViews();
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Оновлено показників: " + updatedCount + "\nПропущено рядків: " + skippedCount
                            + "\nФайл: " + file.getName(), ButtonType.OK);
            alert.setHeaderText("NASA Data Ingest Pipeline");
            alert.showAndWait();
        } catch (Exception ex) {
            Alert errorAlert = new Alert(Alert.AlertType.ERROR, "Помилка читання файлу: " + ex.getMessage(), ButtonType.OK);
            errorAlert.showAndWait();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}