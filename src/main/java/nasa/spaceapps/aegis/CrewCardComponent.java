package nasa.spaceapps.aegis;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;

/**
 * Жива картка члена екіпажу. Створюється один раз, далі лише оновлюється (update):
 * пульс, HRV, гідратація, ЕКГ, таймер Buddy-Guard, вібрація та вхідні SOS-пакети.
 */
public final class CrewCardComponent extends VBox {

    private final CrewMemberState m;
    private final CrewMeshNetwork net;

    // шапка
    private final Circle statusDot = new Circle(7);
    private final Label subLabel = new Label();
    private final Label statusChip = new Label();
    private final Button anomalyBtn = new Button();
    private final Button linkBtn = new Button();
    private final Button sosBtn = new Button();
    private final Button pingBtn = new Button("💬 Усе гаразд?");

    // показники
    private final Label hrVal = new Label(), hrvVal = new Label(), hydVal = new Label();
    private final HeartMonitor ecg = new HeartMonitor();

    // смуга 1: таймер підтвердження
    private final HBox timerStrip = new HBox(12);
    private final Label timerText = new Label();
    private final ProgressBar timerBar = new ProgressBar(1);
    private final Button ackBtn = new Button("✅ Усе гаразд");

    // смуга 2: власний SOS
    private final HBox sosStrip = new HBox(12);
    private final Label sosText = new Label();
    private final Button cancelBtn = new Button("Відбій SOS");

    // смуга 3: вхідний SOS від сусіда
    private final HBox rxStrip = new HBox(12);
    private final Label rxText = new Label();
    private final Button goBtn = new Button("🏃 Іду на допомогу");
    private final Button muteBtn = new Button("🔇 Заглушити");
    private final Button doneBtn = new Button("✅ Допомогу надано");

    private final DropShadow glow = new DropShadow();
    private String lastCardStyle = "";
    private String lastChipStyle = "";
    private String lastTimerStyle = "";
    private double lastTextUpdate = -1;
    private double lastBuzz = -100;
    private String rxSenderId = null;

    public CrewCardComponent(CrewMemberState member, CrewMeshNetwork network) {
        super(10);
        this.m = member;
        this.net = network;
        setPadding(new Insets(14));

        getChildren().addAll(buildHeader(), buildMetrics(), buildTimerStrip(), buildSosStrip(), buildRxStrip());

        glow.setColor(Color.web("#EF4444"));
        glow.setSpread(0.15);
        wireActions();
        update(0, net.now(), true);
    }

    // ============ ПОБУДОВА ============
    private Node buildHeader() {
        StackPane avatarPane = new StackPane();
        avatarPane.setMinSize(54, 54);
        avatarPane.setMaxSize(54, 54);
        Node avatarNode;
        var stream = CrewCardComponent.class.getResourceAsStream("/images/" + m.getId().toLowerCase() + ".png");
        if (stream != null) {
            ImageView iv = new ImageView(new Image(stream));
            iv.setFitWidth(50);
            iv.setFitHeight(50);
            iv.setPreserveRatio(false);
            iv.setClip(new Circle(25, 25, 25));
            avatarNode = iv;
        } else {
            Label emoji = new Label("👨‍🚀");
            emoji.setStyle("-fx-font-size: 26px;");
            avatarNode = emoji;
        }
        statusDot.setStroke(Color.web("#0B1322"));
        statusDot.setStrokeWidth(2);
        StackPane.setAlignment(statusDot, Pos.BOTTOM_RIGHT);
        avatarPane.getChildren().addAll(avatarNode, statusDot);

        Label nameLabel = new Label(m.getName() + (m.isLocal() ? "  · це ваш пристрій" : ""));
        nameLabel.setStyle("-fx-text-fill: #F1F5F9; -fx-font-weight: bold; -fx-font-size: 14px;");
        Label roleLabel = new Label(m.getRole() + "  ·  " + m.getId());
        roleLabel.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
        subLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");
        VBox info = new VBox(2, nameLabel, roleLabel, subLabel);
        HBox.setHgrow(info, Priority.ALWAYS);

        statusChip.setMinWidth(Region.USE_PREF_SIZE);

        HBox buttons = new HBox(6);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        if (!m.isLocal()) buttons.getChildren().add(pingBtn);
        buttons.getChildren().addAll(anomalyBtn, linkBtn, sosBtn);
        for (Button b : new Button[]{pingBtn, anomalyBtn, linkBtn, sosBtn}) {
            b.setMinWidth(Region.USE_PREF_SIZE);
        }

        VBox right = new VBox(8, statusChip, buttons);
        right.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(12, avatarPane, info, right);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Node buildMetrics() {
        VBox tiles = new VBox(6,
                tile("❤", "Пульс", hrVal),
                tile("📊", "HRV (стрес)", hrvVal),
                tile("💧", "Гідратація", hydVal));
        tiles.setMinWidth(190);
        tiles.setPrefWidth(190);

        ecg.setManaged(false);
        Pane ecgHolder = HoloUi.fill(ecg, 76);
        ecgHolder.setStyle("-fx-background-color: #080C14; -fx-border-color: #1E293B; "
                + "-fx-border-radius: 8; -fx-background-radius: 8;");
        HBox.setHgrow(ecgHolder, Priority.ALWAYS);

        HBox row = new HBox(14, tiles, ecgHolder);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox tile(String icon, String caption, Label value) {
        Label ic = new Label(icon);
        ic.setMinWidth(22);
        ic.setStyle("-fx-font-size: 14px;");
        Label cap = new Label(caption);
        cap.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
        cap.setMinWidth(78);
        value.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 13px; -fx-font-weight: bold; -fx-font-family: 'Consolas', monospace;");
        HBox r = new HBox(6, ic, cap, value);
        r.setAlignment(Pos.CENTER_LEFT);
        return r;
    }

    private Node buildTimerStrip() {
        timerText.setWrapText(true);
        timerText.setStyle("-fx-text-fill: #FDBA74; -fx-font-weight: bold; -fx-font-size: 12px;");
        timerBar.setPrefWidth(150);
        timerBar.setMinWidth(110);
        timerText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(timerText, Priority.ALWAYS);
        styleButton(ackBtn, "#059669", "#34D399");
        timerStrip.setAlignment(Pos.CENTER_LEFT);
        timerStrip.setPadding(new Insets(9, 12, 9, 12));
        timerStrip.getChildren().addAll(timerText, timerBar, ackBtn);
        hide(timerStrip);
        return timerStrip;
    }

    private Node buildSosStrip() {
        sosText.setWrapText(true);
        sosText.setStyle("-fx-text-fill: #FCA5A5; -fx-font-weight: bold; -fx-font-size: 12px;");
        sosText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sosText, Priority.ALWAYS);
        styleButton(cancelBtn, "#7F1D1D", "#F87171");
        sosStrip.setAlignment(Pos.CENTER_LEFT);
        sosStrip.setPadding(new Insets(9, 12, 9, 12));
        sosStrip.setStyle("-fx-background-color: rgba(127,29,29,0.35); -fx-border-color: #EF4444; "
                + "-fx-border-radius: 8; -fx-background-radius: 8;");
        sosStrip.getChildren().addAll(sosText, cancelBtn);
        hide(sosStrip);
        return sosStrip;
    }

    private Node buildRxStrip() {
        rxText.setWrapText(true);
        rxText.setStyle("-fx-text-fill: #FDE68A; -fx-font-weight: bold; -fx-font-size: 12px;");
        rxText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(rxText, Priority.ALWAYS);
        styleButton(goBtn, "#B45309", "#FBBF24");
        styleButton(muteBtn, "#1E293B", "#64748B");
        styleButton(doneBtn, "#059669", "#34D399");
        rxStrip.setAlignment(Pos.CENTER_LEFT);
        rxStrip.setPadding(new Insets(9, 12, 9, 12));
        rxStrip.setStyle("-fx-background-color: rgba(120,53,15,0.40); -fx-border-color: #F59E0B; "
                + "-fx-border-radius: 8; -fx-background-radius: 8;");
        HBox btns = new HBox(6, goBtn, muteBtn, doneBtn);
        btns.setAlignment(Pos.CENTER_RIGHT);
        rxStrip.getChildren().addAll(rxText, btns);
        hide(rxStrip);
        return rxStrip;
    }

    private void wireActions() {
        styleButton(pingBtn, "#4C1D95", "#A855F7");
        styleButton(anomalyBtn, "#1E293B", "#F59E0B");
        styleButton(linkBtn, "#1E293B", "#38BDF8");
        styleButton(sosBtn, "#1E293B", "#EF4444");

        pingBtn.setOnAction(e -> net.ping(net.local(), m));
        anomalyBtn.setOnAction(e -> net.toggleAnomaly(m));
        linkBtn.setOnAction(e -> net.toggleLink(m));
        sosBtn.setOnAction(e -> {
            if (m.isSosActive()) net.cancelSos(m, m);
            else net.setManualSos(m, true);
        });
        ackBtn.setOnAction(e -> net.acknowledge(m));
        cancelBtn.setOnAction(e -> net.cancelSos(m, m));
        goBtn.setOnAction(e -> {
            if (rxSenderId != null) net.respond(m, rxSenderId);
        });
        muteBtn.setOnAction(e -> {
            if (rxSenderId != null) net.mute(m, rxSenderId);
        });
        doneBtn.setOnAction(e -> {
            if (rxSenderId != null) {
                CrewMemberState victim = findMember(rxSenderId);
                if (victim != null) net.cancelSos(victim, m);
            }
        });
    }

    private CrewMemberState findMember(String id) {
        for (CrewMemberState x : net.members()) if (x.getId().equals(id)) return x;
        return null;
    }

    private static void styleButton(Button b, String bg, String border) {
        b.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 5 10; "
                + "-fx-background-radius: 6; -fx-border-radius: 6; -fx-border-color: " + border + ";");
    }

    private static void hide(Node n) {
        n.setVisible(false);
        n.setManaged(false);
    }

    private static void show(Node n, boolean on) {
        n.setVisible(on);
        n.setManaged(on);
    }

    // ============ ОНОВЛЕННЯ ============
    /** Викликається кожен кадр. visible: чи показано зараз екран «Команда». */
    public void update(double dt, double now, boolean visible) {
        CrewMemberState.CrewStatus st = m.status();
        Color sc = Color.web(st.hex);

        // ЕКГ біжить завжди, малюється лише коли екран видно
        ecg.setAccent(st == CrewMemberState.CrewStatus.NOMINAL ? null
                : st == CrewMemberState.CrewStatus.WARNING ? Color.web("#F59E0B")
                : st == CrewMemberState.CrewStatus.OFFLINE ? Color.web("#64748B")
                : Color.web("#EF4444"));
        ecg.update(dt, m.getHr(), st != CrewMemberState.CrewStatus.NOMINAL, visible);

        // вібрація та звук працюють і тоді, коли відкрито інший екран
        CrewMemberState.IncomingAlert rx = firstAlert();
        rxSenderId = rx == null ? null : rx.senderId;
        if (rx != null && !rx.muted && now - lastBuzz > 3.5) {
            lastBuzz = now;
            buzz();
            MeshSound.beacon();
        }

        if (!visible) return;

        // колір і світіння картки
        boolean danger = st == CrewMemberState.CrewStatus.CRITICAL;
        boolean attention = rx != null;
        String cardStyle = danger
                ? "-fx-background-color: linear-gradient(to bottom, #2A1015, #1A0B10); -fx-border-color: #EF4444;"
                : attention
                ? "-fx-background-color: linear-gradient(to bottom, #2A1E0C, #181208); -fx-border-color: #F59E0B;"
                : "-fx-background-color: #121C30; -fx-border-color: #22334F;";
        cardStyle += " -fx-border-width: 1.5; -fx-border-radius: 12; -fx-background-radius: 12;";
        if (!cardStyle.equals(lastCardStyle)) {
            lastCardStyle = cardStyle;
            setStyle(cardStyle);
        }
        if (danger || attention) {
            glow.setColor(danger ? Color.web("#EF4444") : Color.web("#F59E0B"));
            glow.setRadius(12 + 9 * (0.5 + 0.5 * Math.sin(now * 5)));
            setEffect(glow);
        } else {
            setEffect(null);
        }

        statusDot.setFill(sc);

        // таймер і прогрес-бар оновлюємо щокадру, текст: 5 разів на секунду
        boolean timer = m.isTimerRunning();
        double remain = m.getRemainingConfirmationSec(now);
        if (timer) {
            timerBar.setProgress(Math.max(0, Math.min(1, remain / m.getConfirmWindow())));
            boolean urgent = remain < Math.min(15, m.getConfirmWindow() * 0.3);
            String ts = urgent ? "-fx-accent: #EF4444;" : "-fx-accent: #F59E0B;";
            if (!ts.equals(lastTimerStyle)) {
                lastTimerStyle = ts;
                timerBar.setStyle(ts);
                timerStrip.setStyle("-fx-background-color: " + (urgent ? "rgba(127,29,29,0.40)" : "rgba(120,53,15,0.40)")
                        + "; -fx-border-color: " + (urgent ? "#EF4444" : "#F59E0B")
                        + "; -fx-border-radius: 8; -fx-background-radius: 8;");
            }
        }

        if (now - lastTextUpdate < 0.2 && lastTextUpdate >= 0) return;
        lastTextUpdate = now;

        hrVal.setText(m.isOffline() ? "— (немає сигналу)" : String.format(Locale.US, "%.0f уд/хв", m.getHr()));
        hrvVal.setText(m.isOffline() ? "—" : String.format(Locale.US, "%.0f мс", m.getHrv()));
        hydVal.setText(m.isOffline() ? "—" : String.format(Locale.US, "%.0f %%", m.getHydration()));
        hrVal.setStyle(valueStyle(m.getHr() > 105 || m.getHr() < 55, m.getHr() > 125 || m.getHr() < 45));
        hrvVal.setStyle(valueStyle(m.getHrv() < 40, m.getHrv() < 25));
        hydVal.setStyle(valueStyle(m.getHydration() < 82, m.getHydration() < 70));

        // статус
        String chipText = "● " + st.label;
        String chipStyle = "-fx-background-color: " + HoloUi.rgba(sc, 0.16) + "; -fx-text-fill: " + st.hex
                + "; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 3 10 3 10; "
                + "-fx-background-radius: 10; -fx-border-radius: 10; -fx-border-color: " + st.hex + ";";
        statusChip.setText(chipText);
        if (!chipStyle.equals(lastChipStyle)) {
            lastChipStyle = chipStyle;
            statusChip.setStyle(chipStyle);
        }

        double d = m.isLocal() ? 0 : net.distanceM(net.local(), m);
        subLabel.setText("📍 " + m.getCompartment()
                + (m.isLocal() ? "" : String.format(Locale.US, "  ·  ≈%.0f м від вас", d))
                + "  ·  📶 Mesh: " + (m.isOffline() ? "втрачено" : m.isLinkUp() ? "активний" : "передавач вимкнено"));

        anomalyBtn.setText(m.isAnomalyActive() ? "⏹ Зупинити збій" : "⚡ Збій (демо)");
        linkBtn.setText(m.isLinkUp() ? "📡 Зв'язок: ВКЛ" : "📡 Зв'язок: ВИКЛ");
        sosBtn.setText(m.isSosActive() ? "🚨 SOS АКТИВНИЙ" : "🚨 Маячок SOS");
        sosBtn.setStyle("-fx-background-color: " + (m.isSosActive() ? "#B91C1C" : "#1E293B") + "; -fx-text-fill: white; "
                + "-fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 5 10; "
                + "-fx-background-radius: 6; -fx-border-radius: 6; -fx-border-color: #EF4444;");

        // смуга: таймер підтвердження
        show(timerStrip, timer);
        if (timer) {
            timerText.setText("⚠️ Аномалія: " + m.getAnomalyText() + ". Авто-SOS через "
                    + (int) Math.ceil(remain) + " с, якщо не підтвердити");
        }

        // смуга: власний SOS
        show(sosStrip, m.isSosActive());
        if (m.isSosActive()) {
            int total = net.members().size() - 1;
            StringBuilder sb = new StringBuilder(m.isAutoSos()
                    ? "🚨 АВТО-SOS: підтвердження не надійшло. " : "🚨 Ручний SOS-маячок. ");
            sb.append("Крипто-пакет (AES-256-GCM) отримали ").append(net.deliveredCount(m)).append(" з ")
                    .append(total).append(" пристроїв.");
            List<CrewMeshNetwork.Helper> helpers = net.helpersOf(m);
            for (CrewMeshNetwork.Helper h : helpers) {
                sb.append(h.alert().arrived
                        ? "\n🤝 " + h.helper().shortName() + " на місці."
                        : "\n🏃 " + h.helper().shortName() + " в дорозі, ще "
                        + Math.max(0, Math.round(h.alert().respondDuration * (1 - h.alert().progress(now)))) + " с.");
            }
            sosText.setText(sb.toString());
        }

        // смуга: вхідний SOS
        show(rxStrip, rx != null);
        if (rx != null) {
            int more = m.getAlerts().size() - 1;
            rxText.setText("📳🔊 SOS від " + rx.senderName + (more > 0 ? "  (+" + more + ")" : "")
                    + "\n" + rx.summary
                    + "\n🔐 AES-GCM ✔ · пакет #" + rx.packetId
                    + String.format(Locale.US, " · ≈%.0f м · %d dBm", rx.distanceM, rx.rssi));
            show(goBtn, !rx.responding);
            show(muteBtn, !rx.responding && !rx.muted);
            show(doneBtn, rx.arrived);
            if (rx.responding && !rx.arrived) {
                rxText.setText(rxText.getText() + "\n🏃 Ви в дорозі: ще "
                        + Math.max(0, Math.round(rx.respondDuration * (1 - rx.progress(now)))) + " с");
            }
        }
    }

    private static String valueStyle(boolean warn, boolean crit) {
        String c = crit ? "#F87171" : warn ? "#FBBF24" : "#E2E8F0";
        return "-fx-text-fill: " + c + "; -fx-font-size: 13px; -fx-font-weight: bold; -fx-font-family: 'Consolas', monospace;";
    }

    private CrewMemberState.IncomingAlert firstAlert() {
        for (CrewMemberState.IncomingAlert al : m.getAlerts().values()) return al;
        return null;
    }

    /** «Вібрація» пристрою: картка коротко тремтить. */
    private void buzz() {
        double[] xs = {0, -7, 7, -6, 6, -4, 4, -2, 0};
        Timeline tl = new Timeline();
        for (int i = 0; i < xs.length; i++) {
            tl.getKeyFrames().add(new KeyFrame(Duration.millis(i * 55), new KeyValue(translateXProperty(), xs[i])));
        }
        tl.play();
    }
}
