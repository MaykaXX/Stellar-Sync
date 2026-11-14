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
 * відображено в AEGIS-BIO. Дані лежать у SpaceSources.
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
        Label note = text("Тут зібрано, на що спирається застосунок: п'ять небезпек польоту за класифікацією "
                + "Програми досліджень людини NASA, конкретні ризики для здоров'я та місця, де вони видно в AEGIS-BIO. "
                + "Пояснення викладені своїми словами, повні тексти за посиланнями.", "#94A3B8", 12);
        Label disclaimer = text("Важливо: порогові значення показників у застосунку демонстраційні, а не нормативи NASA. "
                + "NASA та CSA не пов'язані з цим проєктом і його не схвалювали.", "#FCD34D", 11);
        return HoloUi.block("📚", "Ризики і джерела", "Наукова основа AEGIS-BIO",
                "Кожен показник застосунку пов'язаний із реальним ризиком польоту. "
                        + "Біля показників на екрані «Показники» є значок 📎 із посиланням на джерело.",
                note, disclaimer);
    }

    private VBox hazardsBlock() {
        FlowPane flow = new FlowPane(12, 12);
        for (SpaceSources.Hazard h : SpaceSources.HAZARDS) flow.getChildren().add(hazardCard(h));
        return HoloUi.block("🛡️", "П'ять небезпек польоту", "Так NASA групує труднощі, з якими стикаються астронавти",
                "Небезпеки не діють поодинці: вони підсилюють одна одну. Тому застосунок дивиться на тіло, команду й зв'язок разом.",
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
            head.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 12px; -fx-font-weight: bold;");
            FlowPane flow = new FlowPane(12, 12);
            for (SpaceSources.Risk r : SpaceSources.RISKS) {
                if (r.group().equals(g[0])) flow.getChildren().add(riskCard(r));
            }
            all.getChildren().addAll(head, flow);
        }
        all.getChildren().add(SourceLinks.row("Повний перелік:", SpaceSources.NASA_RISKS));
        return HoloUi.block("🩺", "Ризики для здоров'я", "Що саме може статися і де це видно в застосунку",
                "Групи ризиків узято зі сторінки NASA «Risks of Human Spaceflight». Позначка «у планах» "
                        + "означає, що застосунок цей ризик поки не відстежує.",
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
        bar.add(barSegment("Нейтрони ≈ " + pct + "%", HoloUi.ORANGE), 0, 0);
        bar.add(barSegment("Решта випромінювання ≈ " + (100 - pct) + "%", HoloUi.CYAN), 1, 0);

        Label info = text("За даними Канадського космічного агентства, нейтрони дають близько " + pct
                + "% усієї радіації на МКС. Вони особливо шкідливі й потребують окремого захисту. "
                + "Канада розвиває компактний автономний прилад CANS, який вимірює їх безперервно. "
                + "Дані можуть знадобитися і на Землі: для променевої терапії та захисту екіпажів літаків.", "#94A3B8", 12);
        Label caveat = text("Це цифри для МКС. У польоті до Марса частка може бути іншою, тому застосунок "
                + "показує дозу однією цифрою і розбивку не моделює.", "#64748B", 11);
        return HoloUi.block("☢️", "Радіація: звідки вона береться", "Факти Канадського космічного агентства (CSA)",
                null, bar, info, caveat, SourceLinks.row("Джерело:", SpaceSources.CSA_RADIATION));
    }

    private VBox studiesBlock() {
        FlowPane flow = new FlowPane(12, 12);
        for (SpaceSources.Study s : SpaceSources.STUDIES) flow.getChildren().add(studyCard(s));
        return HoloUi.block("🔬", "Споріднені дослідження NASA", "Реальні проєкти, на які схожі наші функції",
                "Ми не повторюємо ці дослідження, а лише показуємо, у якому напрямку працює NASA і як це перегукується із застосунком.",
                flow, SourceLinks.row("Усі дослідження:", SpaceSources.NASA_RESEARCH));
    }

    private VBox whyBlock() {
        Label a = text("•  На МКС одночасно триває близько " + SpaceSources.ISS_EXPERIMENTS_AT_ONCE
                + " міжнародних експериментів.", "#CBD5E1", 12);
        Label b = text("•  Зміни в тілі за невагомості нагадують прискорене старіння та наслідки малорухливого життя. "
                + "Півроку спостережень у космосі дають дані, на які на Землі пішли б роки.", "#CBD5E1", 12);
        Label c = text("•  Знання допомагають людям із проблемами рівноваги, остеопорозом, хворобами серця та судин "
                + "і діабетом 2 типу.", "#CBD5E1", 12);
        return HoloUi.block("🌍", "Навіщо це людям на Землі", "За матеріалами CSA", null,
                a, b, c, SourceLinks.row("Джерело:", SpaceSources.CSA_WHY_SCIENCE));
    }

    private VBox sourcesBlock() {
        String[] descs = {
                "п'ять небезпек і пояснення до кожної",
                "перелік ризиків для здоров'я",
                "що NASA вивчає зараз",
                "карта ризиків і прогалин у знаннях",
                "нейтрони та прилад CANS",
                "чому космічна наука корисна на Землі"};
        VBox list = new VBox(6);
        List<SpaceSources.Link> links = SpaceSources.mainSources();
        for (int i = 0; i < links.size(); i++) {
            Label d = new Label("  " + descs[Math.min(i, descs.length - 1)]);
            d.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
            HBox row = new HBox(6, SourceLinks.link(links.get(i)), d);
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }
        Label foot = text("Сторінки окремих ризиків і досліджень відкриваються з посилань вище та з карток цього екрана.",
                "#64748B", 11);
        return HoloUi.block("🔗", "Усі джерела", "Основні сторінки, звідки взято матеріал", null, list, foot);
    }

    // ============ КАРТКИ ============
    private Node hazardCard(SpaceSources.Hazard h) {
        Color c = hazardColor(h.letter());
        Label badge = new Label(h.letter());
        badge.setMinSize(32, 32);
        badge.setAlignment(Pos.CENTER);
        badge.setStyle("-fx-background-color: " + HoloUi.rgba(c, 0.20) + "; -fx-text-fill: " + HoloUi.hex(c)
                + "; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-radius: 8; "
                + "-fx-border-color: " + HoloUi.hex(c) + "; -fx-border-radius: 8;");
        Label name = text(h.name(), "#F1F5F9", 13);
        name.setStyle(name.getStyle() + " -fx-font-weight: bold;");
        HBox top = new HBox(10, badge, name);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);

        VBox card = new VBox(8, top, text(h.meaning(), "#94A3B8", 11),
                text("В застосунку: " + h.inApp(), "#7DD3FC", 11),
                SourceLinks.link(h.source().title() + " ↗", h.source().url()));
        return styled(card, 350);
    }

    private Node riskCard(SpaceSources.Risk r) {
        Label title = text(r.title(), "#F1F5F9", 13);
        title.setStyle(title.getStyle() + " -fx-font-weight: bold;");
        HBox.setHgrow(title, Priority.ALWAYS);

        HBox head = new HBox(8, title,
                r.implemented() ? HoloUi.chip("● у застосунку", HoloUi.GREEN) : HoloUi.chip("○ у планах", HoloUi.AMBER));
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
        card.getChildren().addAll(text("В застосунку: " + r.inApp(), "#7DD3FC", 11),
                SourceLinks.link(r.source().title() + " ↗", r.source().url()));
        return styled(card, 440);
    }

    private Node studyCard(SpaceSources.Study s) {
        Label name = text(s.name(), "#F1F5F9", 13);
        name.setStyle(name.getStyle() + " -fx-font-weight: bold;");
        VBox card = new VBox(8, name, text(s.what(), "#94A3B8", 11),
                text("Схоже на наше: " + s.relation(), "#7DD3FC", 11),
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
                + "; -fx-font-size: 12px; -fx-font-weight: bold; -fx-border-color: " + HoloUi.hex(c) + ";");
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
        l.setStyle("-fx-text-fill: " + color + "; -fx-font-size: " + size + "px;");
        return l;
    }
}
