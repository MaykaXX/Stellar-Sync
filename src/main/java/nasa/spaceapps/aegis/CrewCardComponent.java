package nasa.spaceapps.aegis;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

public class CrewCardComponent {

    public static VBox createAdvancedCard(CrewMemberState member) {
        VBox card = new VBox(10);
        card.setPadding(new Insets(14));

        long currentSec = System.currentTimeMillis() / 1000;
        CrewMemberState.CrewStatus status = member.evaluateStatus(currentSec);
        long remainingSec = member.getRemainingConfirmationSec(currentSec);

        // Динамічний стиль картки: якщо SOS або критично — підсвічуємо червоним градієнтом
        String borderColor = (status == CrewMemberState.CrewStatus.CRITICAL || member.isManualSos()) ? "#f85149" : "#30363d";
        String bgColor = (status == CrewMemberState.CrewStatus.CRITICAL || member.isManualSos()) ? "#220e10" : "#161b22";

        card.setStyle(
                "-fx-background-color: " + bgColor + "; " +
                        "-fx-border-color: " + borderColor + "; " +
                        "-fx-border-width: 1.5; " +
                        "-fx-border-radius: 10; -fx-background-radius: 10;"
        );

        // Верхній рядок: Аватар, Ім'я, Статус та Кнопки керування
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        // Аватарка з фотографією або емодзі-запасним варіантом
        StackPane avatarPane = new StackPane();
        avatarPane.setPrefSize(52, 52);
        avatarPane.setStyle("-fx-background-color: #21262d; -fx-background-radius: 26; -fx-border-color: #58a6ff; -fx-border-radius: 26;");

        Node avatarNode;
        String imagePath = "/images/" + member.getId().toLowerCase() + ".png";
        try {
            var stream = CrewCardComponent.class.getResourceAsStream(imagePath);
            if (stream != null) {
                ImageView iv = new ImageView(new Image(stream));
                iv.setFitWidth(52);
                iv.setFitHeight(52);
                iv.setPreserveRatio(false);
                iv.setClip(new Circle(26, 26, 26));
                avatarNode = iv;
            } else {
                Label emoji = new Label("👨‍🚀");
                emoji.setStyle("-fx-font-size: 24px;");
                avatarNode = emoji;
            }
        } catch (Exception e) {
            Label emoji = new Label("👨‍🚀");
            emoji.setStyle("-fx-font-size: 24px;");
            avatarNode = emoji;
        }

        Circle statusDot = new Circle(7);
        statusDot.setFill(status.color);
        StackPane.setAlignment(statusDot, Pos.BOTTOM_RIGHT);
        avatarPane.getChildren().addAll(avatarNode, statusDot);

        // Текстова інформація про члена екіпажу
        VBox infoBox = new VBox(3);
        Label nameLabel = new Label(member.getName() + " — " + member.getRole());
        nameLabel.setStyle("-fx-text-fill: #f0f6fc; -fx-font-weight: bold; -fx-font-size: 14px;");

        Label idLabel = new Label("ID: " + member.getId() + " | Mesh Node: Active | Статус: " + status.label);
        idLabel.setStyle("-fx-text-fill: #8b949e; -fx-font-size: 11px;");
        infoBox.getChildren().addAll(nameLabel, idLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Кнопки взаємодії (сучасний привабливий дизайн)
        HBox buttonsBox = new HBox(8);
        buttonsBox.setAlignment(Pos.CENTER_RIGHT);

        Button commsBtn = new Button("💬 Захищений зв'язок");
        commsBtn.setStyle(
                "-fx-background-color: #238636; -fx-text-fill: white; -fx-font-weight: bold; " +
                        "-fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 6 12;"
        );
        commsBtn.setOnAction(e -> System.out.println("Підключення до ширліту зв'язку: " + member.getName()));

        Button sosBtn = new Button(member.isManualSos() ? "🚨 SOS АКТИВНИЙ" : "🚨 Маяк SOS");
        sosBtn.setStyle(
                "-fx-background-color: " + (member.isManualSos() ? "#da3633" : "#21262d") + "; " +
                        "-fx-text-fill: white; -fx-border-color: #f85149; -fx-border-radius: 6; -fx-background-radius: 6; " +
                        "-fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 6 12;"
        );
        sosBtn.setOnAction(e -> {
            member.setManualSos(!member.isManualSos());
            System.out.println("Змінено стан SOS для " + member.getName());
        });

        buttonsBox.getChildren().addAll(commsBtn, sosBtn);
        header.getChildren().addAll(avatarPane, infoBox, spacer, buttonsBox);

        // Середня частина: Блок показників здоров'я та живий графік ECG
        HBox metricsAndGraph = new HBox(15);
        metricsAndGraph.setAlignment(Pos.CENTER_LEFT);

        VBox statsBox = new VBox(6);
        statsBox.setPrefWidth(220);
        Label hrLabel = new Label("❤ Пульс: " + (int)member.getHr() + " уд/хв");
        Label hrvLabel = new Label("📊 HRV (Стрес): " + String.format("%.1f", member.getHrv()) + " мс");
        Label hydLabel = new Label("💧 Гідратація: " + (int)member.getHydration() + "%");

        hrLabel.setStyle("-fx-text-fill: #58a6ff; -fx-font-size: 12px; -fx-font-weight: bold;");
        hrvLabel.setStyle("-fx-text-fill: #3fb950; -fx-font-size: 12px; -fx-font-weight: bold;");
        hydLabel.setStyle("-fx-text-fill: #a371f7; -fx-font-size: 12px; -fx-font-weight: bold;");
        statsBox.getChildren().addAll(hrLabel, hrvLabel, hydLabel);

        // Живий кардіограмний графік (Canvas)
        Pane graphContainer = new Pane();
        graphContainer.setPrefSize(300, 65);
        graphContainer.setStyle("-fx-background-color: #0d1117; -fx-border-color: #30363d; -fx-border-radius: 6; -fx-background-radius: 6;");
        Canvas ecgCanvas = new Canvas(300, 65);
        drawEcgWaveform(ecgCanvas, member.getHr());
        graphContainer.getChildren().add(ecgCanvas);

        metricsAndGraph.getChildren().addAll(statsBox, graphContainer);
        card.getChildren().addAll(header, metricsAndGraph);

        // --- Логіка Buddy-Guard: якщо є попередження або критика, показуємо інтерактивний таймер та кнопку підтвердження ---
        if (remainingSec >= 0 && !member.isManualSos()) {
            HBox buddyGuardAlert = new HBox(12);
            buddyGuardAlert.setAlignment(Pos.CENTER_LEFT);
            buddyGuardAlert.setPadding(new Insets(8, 12, 8, 12));
            buddyGuardAlert.setStyle("-fx-background-color: #3b2308; -fx-border-color: #d29922; -fx-border-radius: 6; -fx-background-radius: 6;");

            Label alertText = new Label("⚠️ Аномалію зафіксовано! Авто-SOS через " + remainingSec + "с");
            alertText.setStyle("-fx-text-fill: #f0883e; -fx-font-weight: bold; -fx-font-size: 12px;");

            Region alertSpacer = new Region();
            HBox.setHgrow(alertSpacer, Priority.ALWAYS);

            Button ackBtn = new Button("✅ Усе гаразд (Підтвердити)");
            ackBtn.setStyle(
                    "-fx-background-color: #238636; -fx-text-fill: white; -fx-font-weight: bold; " +
                            "-fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 5 10; -fx-font-size: 11px;"
            );
            ackBtn.setOnAction(e -> {
                member.acknowledgeAnomaly();
                System.out.println("Космонавт " + member.getName() + " підтвердив статус 'Усе гаразд'!");
            });

            buddyGuardAlert.getChildren().addAll(alertText, alertSpacer, ackBtn);
            card.getChildren().add(buddyGuardAlert);
        } else if (member.isManualSos() || status == CrewMemberState.CrewStatus.CRITICAL) {
            // Якщо час вийшов або активовано екстрений SOS
            HBox sosActiveAlert = new HBox(10);
            sosActiveAlert.setAlignment(Pos.CENTER_LEFT);
            sosActiveAlert.setPadding(new Insets(8, 12, 8, 12));
            sosActiveAlert.setStyle("-fx-background-color: #450a0a; -fx-border-color: #f85149; -fx-border-radius: 6; -fx-background-radius: 6;");

            Label sosText = new Label("🚨 MESH BUDDY-GUARD: Екстрений аварійний пакет розіслано всій команді!");
            sosText.setStyle("-fx-text-fill: #fca5a5; -fx-font-weight: bold; -fx-font-size: 12px;");

            sosActiveAlert.getChildren().add(sosText);
            card.getChildren().add(sosActiveAlert);
        }

        return card;
    }

    private static void drawEcgWaveform(Canvas canvas, double heartRate) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        gc.setStroke(Color.web("#3fb950"));
        gc.setLineWidth(1.8);

        double w = canvas.getWidth();
        double h = canvas.getHeight();
        double yMid = h / 2;

        gc.beginPath();
        gc.moveTo(0, yMid);

        for (int x = 0; x < w; x++) {
            double y = yMid;
            if (x % 50 > 20 && x % 50 < 25) {
                y = yMid - (x % 2 == 0 ? 22 : -18); // R-зубець
            } else if (x % 50 == 15 || x % 50 == 30) {
                y = yMid + 6; // P / T зубці
            }
            gc.lineTo(x, y);
        }
        gc.stroke();
    }
}