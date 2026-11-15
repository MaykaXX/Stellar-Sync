package nasa.spaceapps.aegis;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.Locale;

/**
 * Готові «цеглинки» інтерфейсу: блоки, мітки, кольори.
 * Нічого не знає про логіку програми.
 */
public final class HoloUi {

    private HoloUi() {
    }

    // ============ КОЛЬОРИ ============
    public static final Color CYAN = Color.web("#38BDF8");
    public static final Color GREEN = Color.web("#10B981");
    public static final Color AMBER = Color.web("#F59E0B");
    public static final Color ORANGE = Color.web("#F97316");
    public static final Color RED = Color.web("#EF4444");
    public static final Color PURPLE = Color.web("#A855F7");
    public static final Color TEXT_DIM = Color.web("#94A3B8");

    public static final String BLOCK_STYLE =
            "-fx-background-color: linear-gradient(to bottom, #0F1A2E, #0B1322);"
                    + "-fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: #1E3A5F;"
                    + "-fx-effect: dropshadow(gaussian, rgba(56,189,248,0.14), 18, 0.15, 0, 0);";

    public static final String CARD_STYLE =
            "-fx-background-color: #121C30; -fx-background-radius: 10; "
                    + "-fx-border-color: #22334F; -fx-border-radius: 10;";

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    public static String rgba(Color c, double a) {
        return String.format(Locale.US, "rgba(%d,%d,%d,%.2f)",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255), a);
    }

    public static Color alpha(Color c, double a) {
        return Color.color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(1, a)));
    }

    // ============ ЦЕГЛИНКИ ІНТЕРФЕЙСУ ============

    /** Логічний блок: іконка, заголовок, пояснення, кнопка «?» і вміст. */
    public static VBox block(String icon, String title, String subtitle, String help, Node... content) {
        Label ic = new Label(icon);
        ic.setStyle("-fx-font-size: 40px;");
        return block((Node) ic, title, subtitle, help, content);
    }

    /** Те саме, але з намальованою іконкою (не залежить від шрифтів emoji у системі). */
    public static VBox block(Node ic, String title, String subtitle, String help, Node... content) {

        Label t = new Label(title);
        t.setStyle("-fx-text-fill: #E0F2FE; -fx-font-size: 28px; -fx-font-weight: bold;");

        VBox titles = new VBox(1, t);
        if (subtitle != null && !subtitle.isEmpty()) {
            Label s = new Label(subtitle);
            s.setWrapText(true);
            s.setStyle("-fx-text-fill: #A3B1C6; -fx-font-size: 22px;");
            titles.getChildren().add(s);
        }
        HBox.setHgrow(titles, Priority.ALWAYS);

        HBox head = new HBox(10, ic, titles);
        head.setAlignment(Pos.CENTER_LEFT);
        if (help != null && !help.isEmpty()) head.getChildren().add(helpBadge(help));

        Region line = new Region();
        line.setMinHeight(1);
        line.setPrefHeight(1);
        line.setMaxHeight(1);
        line.setStyle("-fx-background-color: linear-gradient(to right, #38BDF8, transparent);");

        VBox box = new VBox(10, head, line);
        box.getChildren().addAll(content);
        box.setPadding(new Insets(14));
        box.setStyle(BLOCK_STYLE);
        return box;
    }

    /** Кругла кнопка «?»: наведіть курсор, щоб прочитати пояснення простими словами. */
    public static Label helpBadge(String text) {
        Label q = new Label("?");
        q.setMinSize(20, 20);
        q.setAlignment(Pos.CENTER);
        q.setStyle("-fx-background-color: #0C4A6E; -fx-text-fill: #E0F2FE; -fx-font-weight: bold; "
                + "-fx-font-size: 24px; -fx-background-radius: 10; -fx-cursor: help;");
        Tooltip tip = new Tooltip(text);
        tip.setWrapText(true);
        tip.setMaxWidth(320);
        tip.setShowDelay(Duration.millis(60));
        tip.setShowDuration(Duration.seconds(40));
        tip.setStyle("-fx-font-size: 24px;");
        Tooltip.install(q, tip);
        return q;
    }

    /** Маленька кольорова позначка стану: «Норма», «Увага»... */
    public static void styleChip(Label chip, String text, Color c) {
        chip.setText(text);
        chip.setStyle("-fx-background-color: " + rgba(c, 0.16)
                + "; -fx-text-fill: " + hex(c) + "; -fx-font-size: 20px; -fx-font-weight: bold; "
                + "-fx-padding: 2 8 2 8; -fx-background-radius: 10; -fx-border-radius: 10; "
                + "-fx-border-color: " + hex(c) + ";");
    }

    public static Label chip(String text, Color c) {
        Label l = new Label();
        styleChip(l, text, c);
        return l;
    }

    /** Полотно, яке розтягується разом із контейнером. */
    public static Pane fill(Canvas canvas, double height) {
        canvas.setManaged(false);
        Pane holder = new Pane(canvas);
        holder.setMinSize(0, height);
        holder.setPrefHeight(height);
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        return holder;
    }

    // ============ НАМАЛЬОВАНІ ІКОНКИ (працюють на будь-якій системі) ============
    private static Pane iconPane(double w, double h, Node... nodes) {
        Pane p = new Pane(nodes);
        p.setMinSize(w, h);
        p.setPrefSize(w, h);
        p.setMaxSize(w, h);
        p.setMouseTransparent(true);
        return p;
    }

    private static javafx.scene.shape.Circle dot(double x, double y, double r, Color fill) {
        javafx.scene.shape.Circle c = new javafx.scene.shape.Circle(x, y, r);
        c.setFill(fill);
        return c;
    }

    /** Іконка «Команда»: троє людей. */
    public static Node iconTeam() {
        Color c = CYAN;
        javafx.scene.shape.Arc l = new javafx.scene.shape.Arc(5.5, 22, 4.5, 6, 0, 180);
        javafx.scene.shape.Arc r = new javafx.scene.shape.Arc(18.5, 22, 4.5, 6, 0, 180);
        javafx.scene.shape.Arc m = new javafx.scene.shape.Arc(12, 23, 6.5, 8.5, 0, 180);
        for (javafx.scene.shape.Arc a : new javafx.scene.shape.Arc[]{l, r, m}) {
            a.setType(javafx.scene.shape.ArcType.ROUND);
            a.setFill(a == m ? c : alpha(c, 0.6));
        }
        return iconPane(24, 24, l, r, m, dot(5.5, 11.5, 2.8, alpha(c, 0.6)), dot(18.5, 11.5, 2.8, alpha(c, 0.6)),
                dot(12, 8.5, 4, c));
    }

    /** Іконка «Психологічна підтримка»: усміхнене обличчя. */
    public static Node iconMind() {
        Color c = Color.web("#2DD4BF");
        javafx.scene.shape.Circle face = new javafx.scene.shape.Circle(12, 12, 10);
        face.setFill(Color.TRANSPARENT);
        face.setStroke(c);
        face.setStrokeWidth(2);
        javafx.scene.shape.Arc smile = new javafx.scene.shape.Arc(12, 12.5, 5.5, 4.8, 205, 130);
        smile.setType(javafx.scene.shape.ArcType.OPEN);
        smile.setFill(Color.TRANSPARENT);
        smile.setStroke(c);
        smile.setStrokeWidth(2);
        smile.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        return iconPane(24, 24, face, smile, dot(8.5, 9.5, 1.5, c), dot(15.5, 9.5, 1.5, c));
    }

    /** Іконка «Голограма екіпажу»: шолом космонавта. */
    public static Node iconAstronaut() {
        javafx.scene.shape.Rectangle body = new javafx.scene.shape.Rectangle(5, 19.5, 14, 5);
        body.setFill(Color.web("#CBD5E1"));
        body.setArcWidth(5);
        body.setArcHeight(5);
        javafx.scene.shape.Circle helmet = dot(12, 11, 9.5, Color.web("#F8FAFC"));
        javafx.scene.shape.Ellipse visor = new javafx.scene.shape.Ellipse(12, 11.5, 6.6, 5.4);
        visor.setFill(Color.web("#4C1D95"));
        javafx.scene.shape.Ellipse glint = new javafx.scene.shape.Ellipse(9.6, 9.6, 2.2, 1.3);
        glint.setFill(Color.web("#C4B5FD"));
        return iconPane(24, 26, body, helmet, visor, glint);
    }

    /** Намальоване обличчя настрою 1..5 (червоне = дуже погано, зелене = чудово). Видно на будь-якій системі. */
    public static Node moodFace(int value, double k) {
        Color[] cols = {Color.web("#F87171"), Color.web("#FB923C"), Color.web("#FACC15"),
                Color.web("#A3E635"), Color.web("#4ADE80")};
        Color c = cols[Math.max(0, Math.min(4, value - 1))];
        javafx.scene.shape.Circle face = new javafx.scene.shape.Circle(12 * k, 12 * k, 10 * k);
        face.setFill(alpha(c, 0.12));
        face.setStroke(c);
        face.setStrokeWidth(2 * k);
        Node mouth;
        if (value == 3) {
            javafx.scene.shape.Line ln = new javafx.scene.shape.Line(8 * k, 15.5 * k, 16 * k, 15.5 * k);
            ln.setStroke(c);
            ln.setStrokeWidth(2 * k);
            ln.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            mouth = ln;
        } else {
            javafx.scene.shape.Arc a = switch (value) {
                case 1 -> new javafx.scene.shape.Arc(12 * k, 19.5 * k, 5.5 * k, 4.5 * k, 25, 130);
                case 2 -> new javafx.scene.shape.Arc(12 * k, 17.5 * k, 5 * k, 3 * k, 25, 130);
                case 4 -> new javafx.scene.shape.Arc(12 * k, 12 * k, 5 * k, 3.6 * k, 205, 130);
                default -> new javafx.scene.shape.Arc(12 * k, 11 * k, 6 * k, 5.6 * k, 200, 140);
            };
            a.setType(value == 5 ? javafx.scene.shape.ArcType.CHORD : javafx.scene.shape.ArcType.OPEN);
            a.setFill(value == 5 ? c : Color.TRANSPARENT);
            a.setStroke(c);
            a.setStrokeWidth(2 * k);
            a.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            mouth = a;
        }
        return new javafx.scene.Group(iconPane(24 * k, 24 * k, face, mouth,
                dot(8.5 * k, 9.5 * k, 1.6 * k, c), dot(15.5 * k, 9.5 * k, 1.6 * k, c)));
    }

    // ============ КОРИСНЕ ============
    public static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Число без зайвих нулів: 60 -> "60", 36.2 -> "36.2". */
    public static String num(double v) {
        return v == Math.rint(v) ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v);
    }
}