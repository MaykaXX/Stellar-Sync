package nasa.spaceapps.aegis;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * Екран «Ризики і джерела»: наукова основа застосунку (NASA HRP, CSA) і те, де кожен ризик
 * відображено в VITAL. Дані лежать у SpaceSources.
 */
public final class SourcesPanel extends VBox {

    public SourcesPanel() {
        super(14);
        setPadding(new Insets(2, 4, 2, 2));
        getChildren().addAll(introBlock(), hazardsBlock(), risksBlock(), radiationBlock(),
                studiesBlock(), whyBlock(), sourcesBlock());
    }

    // ============ БЛОКИ ============
    private VBox introBlock() {
        Label note = text("Here is what the app is based on: the five hazards of spaceflight as classified by the "
                + "NASA Human Research Program, specific health risks, and where they show up in VITAL. "
                + "The explanations are in our own words; full texts are at the links.", "#94A3B8", 12);
        Label disclaimer = text("Important: the threshold values in the app are for demonstration and are not NASA standards. "
                + "NASA and CSA are not affiliated with this project and have not endorsed it.", "#FCD34D", 11);
        return HoloUi.block("📚", "Risks and sources", "The scientific basis of VITAL",
                "Every metric in the app is tied to a real spaceflight risk. "
                        + "Next to the metrics on the “Status & Actions” screen there is a 📎 icon with a link to the source.",
                note, disclaimer);
    }

    private VBox hazardsBlock() {
        FlowPane flow = new FlowPane(12, 12);
        for (SpaceSources.Hazard h : SpaceSources.HAZARDS) flow.getChildren().add(hazardCard(h));
        return HoloUi.block("🛡️", "Five spaceflight hazards", "How NASA groups the difficulties astronauts face",
                "Hazards do not act alone: they amplify each other. That is why the app looks at body, team and communication together.",
                flow);
    }

    private VBox risksBlock() {
        VBox all = new VBox(12);
        String[][] groups = {
                {SpaceSources.GROUP_PHYSIO, "🫀"},
                {SpaceSources.GROUP_BEHAVIOR, "🧠"},
                {SpaceSources.GROUP_RADIATION, "☢️"}};
        for (String[] g : groups) {
            Label head = new Label(g[1] + "  " + g[0]);
            head.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 24px; -fx-font-weight: bold;");
            FlowPane flow = new FlowPane(12, 12);
            for (SpaceSources.Risk r : SpaceSources.RISKS) {
                if (r.group().equals(g[0])) flow.getChildren().add(riskCard(r));
            }
            all.getChildren().addAll(head, flow);
        }
        all.getChildren().add(SourceLinks.row("Full list:", SpaceSources.NASA_RISKS));
        return HoloUi.block("🩺", "Health risks", "What exactly can happen and where it shows in the app",
                "The risk groups are taken from NASA's “Risks of Human Spaceflight” page. The “planned” label "
                        + "means the app does not track this risk yet.",
                all);
    }

    private VBox radiationBlock() {
        int pct = (int) Math.round(SpaceSources.NEUTRON_SHARE_ISS * 100);
        GridPane bar = new GridPane();
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(pct);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(100 - pct);
        bar.getColumnConstraints().addAll(c1, c2);
        bar.add(barSegment("Neutrons ≈ " + pct + "%", HoloUi.ORANGE), 0, 0);
        bar.add(barSegment("Other radiation ≈ " + (100 - pct) + "%", HoloUi.CYAN), 1, 0);

        Label info = text("According to the Canadian Space Agency, neutrons make up about " + pct
                + "% of all radiation on the ISS. They are especially harmful and need separate shielding. "
                + "Canada is developing a compact autonomous instrument, CANS, that measures them continuously. "
                + "The data may also be useful on Earth: for radiation therapy and protecting aircrews.", "#94A3B8", 12);
        Label caveat = text("These figures are for the ISS. On a flight to Mars the share may differ, so the app "
                + "shows the dose as a single number and does not model the breakdown.", "#A3B1C6", 11);
        return HoloUi.block("☢️", "Radiation: where it comes from", "Canadian Space Agency (CSA) facts",
                null, bar, info, caveat, SourceLinks.row("Source:", SpaceSources.CSA_RADIATION));
    }

    private VBox studiesBlock() {
        FlowPane flow = new FlowPane(12, 12);
        for (SpaceSources.Study s : SpaceSources.STUDIES) flow.getChildren().add(studyCard(s));
        return HoloUi.block("🔬", "Related NASA research", "Real projects that resemble our features",
                "We do not repeat these studies; we only show the direction NASA is working in and how it echoes the app.",
                flow, SourceLinks.row("All studies:", SpaceSources.NASA_RESEARCH));
    }

    private VBox whyBlock() {
        Label a = text("•  About " + SpaceSources.ISS_EXPERIMENTS_AT_ONCE
                + " international experiments run on the ISS at the same time.", "#CBD5E1", 12);
        Label b = text("•  Changes in the body in weightlessness resemble accelerated aging and the effects of a sedentary life. "
                + "Six months of observation in space give data that would take years on Earth.", "#CBD5E1", 12);
        Label c = text("•  The knowledge helps people with balance problems, osteoporosis, heart and vascular disease "
                + "and type 2 diabetes.", "#CBD5E1", 12);
        return HoloUi.block("🌍", "Why this matters to people on Earth", "Based on CSA materials", null,
                a, b, c, SourceLinks.row("Source:", SpaceSources.CSA_WHY_SCIENCE));
    }

    private VBox sourcesBlock() {
        String[] descs = {
                "five hazards and an explanation of each",
                "list of health risks",
                "what NASA is studying now",
                "map of risks and knowledge gaps",
                "neutrons and the CANS instrument",
                "why space science is useful on Earth"};
        VBox list = new VBox(6);
        List<SpaceSources.Link> links = SpaceSources.mainSources();
        for (int i = 0; i < links.size(); i++) {
            Label d = new Label("  " + descs[Math.min(i, descs.length - 1)]);
            d.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 22px;");
            HBox row = new HBox(6, SourceLinks.link(links.get(i)), d);
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }
        Label foot = text("Pages for individual risks and studies open from the links above and from the cards on this screen.",
                "#A3B1C6", 11);
        return HoloUi.block("🔗", "All sources", "Main pages the material comes from", null, list, foot);
    }

    // ============ КАРТКИ ============
    private Node hazardCard(SpaceSources.Hazard h) {
        Color c = hazardColor(h.letter());
        Label badge = new Label(h.letter());
        badge.setMinSize(32, 32);
        badge.setAlignment(Pos.CENTER);
        badge.setStyle("-fx-background-color: " + HoloUi.rgba(c, 0.20) + "; -fx-text-fill: " + HoloUi.hex(c)
                + "; -fx-font-size: 32px; -fx-font-weight: bold; -fx-background-radius: 8; "
                + "-fx-border-color: " + HoloUi.hex(c) + "; -fx-border-radius: 8;");
        Label name = text(h.name(), "#F1F5F9", 13);
        name.setStyle(name.getStyle() + " -fx-font-weight: bold;");
        HBox top = new HBox(10, badge, name);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);

        VBox card = new VBox(8, top, text(h.meaning(), "#94A3B8", 11),
                text("In the app: " + h.inApp(), "#7DD3FC", 11),
                SourceLinks.link(h.source().title() + " ↗", h.source().url()));
        return styled(card, 350);
    }

    private Node riskCard(SpaceSources.Risk r) {
        Label title = text(r.title(), "#F1F5F9", 13);
        title.setStyle(title.getStyle() + " -fx-font-weight: bold;");
        HBox.setHgrow(title, Priority.ALWAYS);

        HBox head = new HBox(8, title,
                r.implemented() ? HoloUi.chip("● in the app", HoloUi.GREEN) : HoloUi.chip("○ planned", HoloUi.AMBER));
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, head, text(r.summary(), "#94A3B8", 11));
        if (r.metric() != null) {
            FlowPane chips = new FlowPane(6, 4);
            for (String m : r.metric().split(",")) {
                String t;
                try {
                    t = AegisBioApp.MetricType.valueOf(m).title;
                } catch (IllegalArgumentException ex) {
                    t = m;
                }
                chips.getChildren().add(HoloUi.chip("📊 " + t, HoloUi.CYAN));
            }
            card.getChildren().add(chips);
        }
        card.getChildren().addAll(text("In the app: " + r.inApp(), "#7DD3FC", 11),
                SourceLinks.link(r.source().title() + " ↗", r.source().url()));
        return styled(card, 440);
    }

    private Node studyCard(SpaceSources.Study s) {
        Label name = text(s.name(), "#F1F5F9", 13);
        name.setStyle(name.getStyle() + " -fx-font-weight: bold;");
        VBox card = new VBox(8, name, text(s.what(), "#94A3B8", 11),
                text("Similar to ours: " + s.relation(), "#7DD3FC", 11),
                SourceLinks.link(s.source().title() + " ↗", s.source().url()));
        return styled(card, 350);
    }

    // ============ ДОПОМІЖНЕ ============
    private static VBox styled(VBox card, double width) {
        card.setPadding(new Insets(12));
        card.setPrefWidth(width);
        card.setMinWidth(280);
        card.setStyle(HoloUi.CARD_STYLE);
        return card;
    }

    private static Label barSegment(String txt, Color c) {
        Label l = new Label(txt);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setMinHeight(34);
        l.setAlignment(Pos.CENTER);
        l.setStyle("-fx-background-color: " + HoloUi.rgba(c, 0.28) + "; -fx-text-fill: " + HoloUi.hex(c)
                + "; -fx-font-size: 24px; -fx-font-weight: bold; -fx-border-color: " + HoloUi.hex(c) + ";");
        return l;
    }

    private static Color hazardColor(String letter) {
        return switch (letter) {
            case "R" -> HoloUi.RED;
            case "I" -> HoloUi.PURPLE;
            case "D" -> HoloUi.CYAN;
            case "G" -> HoloUi.GREEN;
            default -> HoloUi.AMBER;
        };
    }

    private static Label text(String s, String color, int size) {
        Label l = new Label(s);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setStyle("-fx-text-fill: " + color + "; -fx-font-size: " + (size * 2) + "px;");
        return l;
    }
}
