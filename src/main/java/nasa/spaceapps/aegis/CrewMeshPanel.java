package nasa.spaceapps.aegis;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Екран «Команда (Mesh)»: схема мережі, живі картки екіпажу та журнал mesh-пакетів.
 * Основна програма викликає лише frame() кожен кадр і setLocalVitals() раз на секунду.
 */
public final class CrewMeshPanel extends VBox {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int LOG_LIMIT = 16;

    private final CrewMeshNetwork net;
    private final MeshMapCanvas map;
    private final List<CrewCardComponent> cards = new ArrayList<>();
    private final VBox logBox = new VBox(4);
    private final Label onlineChip = new Label();
    private final Label sosChip = new Label();
    private final Button commanderSos = new Button();
    private final IntConsumer onSosCount;
    private int lastSosCount = -1;
    private double lastChipUpdate = -1;
    private double clock = 0;
    private boolean mapToggle = false;

    /**
     * @param appLog    куди дублювати важливі події (журнал дій основної програми)
     * @param onSosCount викликається, коли змінилась кількість активних SOS (для червоного значка в меню)
     */
    public CrewMeshPanel(CrewMeshNetwork network, Consumer<String> appLog, IntConsumer onSosCount) {
        super(14);
        this.net = network;
        this.onSosCount = onSosCount;
        setPadding(new Insets(2, 4, 2, 2));

        this.map = new MeshMapCanvas(net);

        net.addListener(e -> {
            addLogLine(e);
            if (appLog != null && (e.kind() == CrewMeshNetwork.Kind.SOS
                    || e.kind() == CrewMeshNetwork.Kind.WARN || e.kind() == CrewMeshNetwork.Kind.OK)) {
                appLog.accept("[MESH] " + e.text());
            }
        });

        HBox top = new HBox(14, buildMapBlock(), buildLogBlock());
        VBox cardsBlock = buildCardsBlock();
        getChildren().addAll(top, cardsBlock);

        addLogLine(new CrewMeshNetwork.MeshEvent(CrewMeshNetwork.Kind.INFO,
                "🕸️ Mesh started: " + net.members().size() + " nodes, crew AES-256 key agreed.", null));
        refreshChips();
    }

    // ============ ПОБУДОВА ============
    private VBox buildMapBlock() {
        Label hint = new Label("Crew devices are connected directly (Bluetooth), with no central server. "
                + "Each one watches its neighbors and, in danger, sends them an encrypted signal.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px;");

        onlineChip.setMinWidth(Region.USE_PREF_SIZE);
        sosChip.setMinWidth(Region.USE_PREF_SIZE);
        Label crypto = HoloUi.chip("🔐 AES-256-GCM", HoloUi.PURPLE);

        CheckBox fast = new CheckBox("Fast demo timer (15 s instead of 60 s)");
        fast.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 22px;");
        fast.setOnAction(e -> net.setConfirmWindow(fast.isSelected() ? 15 : 60));

        ToggleButton sound = new ToggleButton("🔊 Sound");
        sound.setSelected(true);
        sound.setStyle("-fx-font-size: 22px; -fx-cursor: hand;");
        sound.setOnAction(e -> {
            MeshSound.setMuted(!sound.isSelected());
            sound.setText(sound.isSelected() ? "🔊 Sound" : "🔇 Muted");
        });

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox chips = new HBox(8, onlineChip, sosChip, crypto, sp, fast, sound);
        chips.setAlignment(Pos.CENTER_LEFT);

        commanderSos.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(commanderSos, Priority.ALWAYS);
        commanderSos.setOnAction(e -> {
            CrewMemberState me = net.local();
            if (me.isSosActive()) net.cancelSos(me, me);
            else net.setManualSos(me, true);
        });
        // «Усе гаразд» для вас (Командира): завжди під рукою, поруч із SOS
        Button commanderOk = new Button("✅ All good (I'm fine)");
        commanderOk.setMinWidth(Region.USE_PREF_SIZE);
        commanderOk.setStyle("-fx-background-color: #047857; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-cursor: hand; -fx-padding: 10 16; -fx-background-radius: 8;");
        commanderOk.setOnAction(e -> net.acknowledge(net.local()));
        HBox sosRow = new HBox(10, commanderSos, commanderOk);
        sosRow.setAlignment(Pos.CENTER_LEFT);

        Pane mapHolder = HoloUi.fill(map, 270);
        mapHolder.setStyle("-fx-border-color: #1E293B; -fx-border-radius: 8; -fx-background-radius: 8;");

        HBox legend = new HBox(8,
                HoloUi.chip("● heartbeat", HoloUi.CYAN),
                HoloUi.chip("● SOS packet", HoloUi.RED),
                HoloUi.chip("● request / reply", HoloUi.PURPLE),
                HoloUi.chip("● help on the way", HoloUi.GREEN));

        String how = "How to demo this:\n"
                + "1) On the Engineer's card press “⚡ Fault (demo)”: pulse and HRV will drop sharply.\n"
                + "2) A timer appears. If you don't press “All good”, the device sends the crypto packet by itself.\n"
                + "3) The others get vibration, sound and a beacon on the map. Press “On my way to help”: the astronaut walks to the victim,\n"
                + "   helps on site and returns to their post after stand-down.\n"
                + "4) “📡 Link” turns the node's radio off, and neighbors will notice the silence.";
        VBox block = HoloUi.block("🕸️", "Ship mesh network", "Live map: packets fly between devices",
                how, hint, chips, mapHolder, legend,
                SourceLinks.row("Based on:", SpaceSources.hazard("I").source(), SpaceSources.risk("TEAM").source()),
                sosRow);
        block.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(block, Priority.ALWAYS);
        return block;
    }

    private VBox buildLogBlock() {
        VBox block = HoloUi.block("📡", "Mesh packet log", "What is happening on the network right now",
                null, logBox);
        block.setMinWidth(330);
        block.setPrefWidth(360);
        block.setMaxWidth(380);
        return block;
    }

    private VBox buildCardsBlock() {
        VBox list = new VBox(12);
        for (CrewMemberState m : net.members()) {
            CrewCardComponent c = new CrewCardComponent(m, net);
            cards.add(c);
            list.getChildren().add(c);
        }
        return HoloUi.block(HoloUi.iconTeam(), "Crew online", "Pulse, stress balance, water and mutual protection update live",
                "Buddy-Guard: if vitals are critical and a crew member hasn't confirmed “All good” "
                        + "within 60 seconds, their device autonomously broadcasts an alarm packet to everyone else.", list);
    }

    // ============ ЖУРНАЛ ============
    private void addLogLine(CrewMeshNetwork.MeshEvent e) {
        String color = switch (e.kind()) {
            case SOS -> "#FCA5A5";
            case WARN -> "#FCD34D";
            case OK -> "#6EE7B7";
            case RX -> "#FDBA74";
            default -> "#94A3B8";
        };
        Label l = new Label(LocalTime.now().format(TIME) + "  " + e.text());
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 22px;");
        logBox.getChildren().add(0, l);
        while (logBox.getChildren().size() > LOG_LIMIT) {
            logBox.getChildren().remove(logBox.getChildren().size() - 1);
        }
    }

    /** Доступ до мережі (потрібен для глобальної плашки тривоги в головному вікні). */
    public CrewMeshNetwork network() {
        return net;
    }

    /** Піднімає ручний SOS Командира (наприклад, із вікна MindCare). */
    public void raiseCommanderSos() {
        net.setManualSos(net.local(), true);
    }

    // ============ ОНОВЛЕННЯ ============
    /** Дані пристрою користувача з основної програми. */
    public void setLocalVitals(double hr, double hrv, double hydration) {
        net.setLocalVitals(hr, hrv, hydration);
    }

    /** Кожен кадр. visible: чи відкрито цей екран. */
    public void frame(double dt, boolean visible) {
        double d = Math.min(Math.max(dt, 0), 0.25);
        clock += d;
        net.advance(d);
        for (CrewCardComponent c : cards) c.update(d, net.now(), visible);

        int sos = net.activeSosCount();
        if (sos != lastSosCount) {
            lastSosCount = sos;
            if (onSosCount != null) onSosCount.accept(sos);
        }
        if (!visible) return;
        mapToggle = !mapToggle;
        if (mapToggle) map.render(net.now());
        if (clock - lastChipUpdate > 0.4) {
            lastChipUpdate = clock;
            refreshChips();
        }
    }

    private void refreshChips() {
        int online = net.onlineCount(), total = net.members().size();
        HoloUi.styleChip(onlineChip, "📶 Nodes online: " + online + "/" + total,
                online == total ? HoloUi.GREEN : HoloUi.AMBER);
        int sos = net.activeSosCount();
        HoloUi.styleChip(sosChip, sos > 0 ? "🚨 Active SOS: " + sos : "● No alarms",
                sos > 0 ? HoloUi.RED : HoloUi.GREEN);

        boolean mine = net.local().isSosActive();
        commanderSos.setText(mine ? "🚨 SOS ACTIVE: press to turn the beacon off"
                : "🚨 TURN ON COMMANDER SOS (emergency beacon)");
        commanderSos.setStyle("-fx-background-color: " + (mine ? "#7F1D1D" : "#B91C1C")
                + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; "
                + "-fx-padding: 10; -fx-background-radius: 8;");
    }
}