package nasa.spaceapps.aegis;

import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Орбітальне головне меню VITAL: розділи розташовані по колу навколо центрального диска.
 * Колір вузла показує стан розділу (синій, зелений, бурштиновий, червоний), чорний із білим обідком: усе спокійно.
 * У центрі диска: логотип VITAL, назва, загальний вердикт і підказка.
 * Голограма екіпажу (космонавт) винесена в окреме акцентне коло на орбіті.
 */
public final class OrbitMenu extends Pane {

    public enum State {
        NEUTRAL(null),
        INFO(Color.web("#4D8BFF")),
        OK(Color.web("#3CC48A")),
        WARN(Color.web("#F2B33D")),
        DANGER(Color.web("#E5535F"));

        final Color color;

        State(Color color) {
            this.color = color;
        }
    }

    public record Item(String id, String label, String hint, boolean accent) {
        public Item(String id, String label, String hint) {
            this(id, label, hint, false);
        }
    }

    private static final Color LABEL_NEUTRAL = Color.web("#E5E9F0");
    private static final Color ACCENT = Color.web("#A78BFA");
    private static final Color ACCENT_DEEP = Color.web("#6D28D9");

    private final class NodeView {
        final Item item;
        final Circle circle = new Circle(20);
        final Label label = new Label();
        final Label badge = new Label();
        State state = State.NEUTRAL;
        final Tooltip tip = new Tooltip();
        /** Малюнок космонавта всередині акцентного кола (лише для accent-вузла). */
        final Canvas icon;

        NodeView(Item item) {
            this.item = item;
            this.icon = item.accent() ? new Canvas() : null;
            if (icon != null) icon.setMouseTransparent(true);
            label.setText(item.label());
            if (item.accent()) label.setTextFill(Color.web("#C4B5FD"));
            label.setWrapText(false);
            badge.setVisible(false);
            badge.setMouseTransparent(true);
            badge.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-size: 24px; "
                    + "-fx-font-weight: bold; -fx-padding: 1 6 1 6; -fx-background-radius: 9;");
            circle.setCursor(Cursor.HAND);
            label.setCursor(Cursor.HAND);
            tip.setShowDelay(Duration.millis(150));
            tip.setStyle("-fx-font-size: 28px;");
            tip.setText(item.hint());
            Tooltip.install(circle, tip);
            Tooltip.install(label, tip);
            Runnable open = () -> {
                if (onSelect != null) onSelect.accept(item.id());
            };
            circle.setOnMouseClicked(e -> open.run());
            label.setOnMouseClicked(e -> open.run());
            circle.setOnMouseEntered(e -> circle.setScaleX(1.12));
            circle.setOnMouseExited(e -> circle.setScaleX(1.0));
            circle.scaleXProperty().addListener((o, a, b) -> {
                circle.setScaleY(b.doubleValue());
                if (icon != null) {
                    icon.setScaleX(b.doubleValue());
                    icon.setScaleY(b.doubleValue());
                }
            });
            applyStyle();
        }

        void applyStyle() {
            if (item.accent()) {
                circle.setFill(new RadialGradient(0, 0, 0.35, 0.3, 0.9, true, CycleMethod.NO_CYCLE,
                        new Stop(0, ACCENT), new Stop(1, ACCENT_DEEP)));
                circle.setStroke(Color.web("#EDE9FE"));
                circle.setStrokeWidth(2.6);
                circle.setEffect(new DropShadow(30, HoloUi.alpha(ACCENT, 0.9)));
            } else if (state == State.NEUTRAL) {
                circle.setFill(Color.BLACK);
                circle.setStroke(Color.WHITE);
                circle.setStrokeWidth(2.2);
                circle.setEffect(null);
            } else {
                Color c = state.color;
                circle.setFill(c);
                circle.setStroke(c);
                circle.setStrokeWidth(1);
                circle.setEffect(new DropShadow(22, HoloUi.alpha(c, 0.75)));
            }
        }
    }

    private final Canvas bg = new Canvas();
    private final List<NodeView> nodes = new ArrayList<>();
    private final Circle disc = new Circle(100);
    private final Circle dashRing = new Circle(95);
    private final Canvas logo = new Canvas();
    private final Label title = new Label("VITAL");
    private final Label verdict = new Label("Усе гаразд");
    private final Label hint = new Label("Оберіть розділ");

    private Consumer<String> onSelect;

    private double time = 0;
    private double orbitAngle = -0.45;
    private double[][] stars;
    private double ringR = 180;
    private double cx, cy;
    private double accentX = Double.NaN, accentY, accentR;

    public OrbitMenu(List<Item> items) {
        setMinSize(0, 0);
        getChildren().add(bg);
        bg.setMouseTransparent(true);

        disc.setFill(Color.BLACK);
        disc.setStroke(Color.WHITE);
        disc.setStrokeWidth(3);
        getChildren().add(disc);
        dashRing.setFill(Color.TRANSPARENT);
        dashRing.setStroke(Color.color(1, 1, 1, 0.22));
        dashRing.setStrokeWidth(1);
        dashRing.getStrokeDashArray().addAll(2.0, 5.0);
        dashRing.setMouseTransparent(true);
        getChildren().add(dashRing);

        getChildren().add(logo);
        logo.setMouseTransparent(true);

        for (Label l : new Label[]{title, verdict, hint}) {
            l.setMouseTransparent(true);
            l.setTextAlignment(TextAlignment.CENTER);
            l.setAlignment(javafx.geometry.Pos.CENTER);
            getChildren().add(l);
        }
        title.setTextFill(Color.WHITE);
        title.setEffect(new DropShadow(20, HoloUi.alpha(HoloUi.CYAN, 0.6)));
        hint.setTextFill(Color.web("#9AA7B8"));
        verdict.setTextFill(Color.web("#3CC48A"));

        for (Item it : items) {
            NodeView nv = new NodeView(it);
            nodes.add(nv);
            getChildren().addAll(nv.circle, nv.label, nv.badge);
            if (nv.icon != null) getChildren().add(nv.icon);
        }

        stars = new double[170][4];
        java.util.Random rnd = new java.util.Random(7);
        for (double[] s : stars) {
            s[0] = rnd.nextDouble();
            s[1] = rnd.nextDouble();
            s[2] = 0.5 + rnd.nextDouble() * 1.3;
            s[3] = rnd.nextDouble() * Math.PI * 2;
        }
    }

    // ============ ПУБЛІЧНЕ API ============
    public void setOnSelect(Consumer<String> handler) {
        this.onSelect = handler;
    }

    public void setItemState(String id, State state, String hintText) {
        for (NodeView nv : nodes) {
            if (nv.item.id().equals(id)) {
                nv.state = state;
                nv.applyStyle();
                nv.label.setTextFill(state == State.NEUTRAL ? LABEL_NEUTRAL : state.color);
                nv.tip.setText(hintText == null || hintText.isEmpty() ? nv.item.hint() : nv.item.hint() + "\n" + hintText);
            }
        }
    }

    /** Лічильник біля вузла; null або порожній текст ховає його. */
    public void setBadge(String id, String text) {
        for (NodeView nv : nodes) {
            if (nv.item.id().equals(id)) {
                boolean show = text != null && !text.isEmpty();
                nv.badge.setText(show ? text : "");
                nv.badge.setVisible(show);
            }
        }
    }

    public void setVerdict(String text, Color color) {
        verdict.setText(text);
        verdict.setTextFill(color);
    }

    /** Анімація: зірки, рух точки по орбіті, логотип у центрі, пульсація акцентного кола. */
    public void frame(double dt) {
        time += dt;
        orbitAngle += dt * 0.12;
        drawLogo();
        drawBackground();
    }

    /** Підбирає найбільший шрифт (не більше desired), щоб напис вмістився в maxWidth. */
    private static void fitFont(Label l, FontWeight weight, double desired, double maxWidth) {
        double size = Math.max(12, desired);
        javafx.scene.text.Text probe = new javafx.scene.text.Text(l.getText());
        for (int i = 0; i < 40; i++) {
            probe.setFont(Font.font("Segoe UI", weight, size));
            if (probe.getLayoutBounds().getWidth() <= maxWidth || size <= 12) break;
            size *= 0.94;
        }
        l.setFont(Font.font("Segoe UI", weight, size));
        // inline-стиль має пріоритет над aegis.css, інакше CSS ігнорує setFont і шрифт лишається дрібним
        l.setStyle(String.format(java.util.Locale.US, "-fx-font-family: 'Segoe UI', 'DejaVu Sans', sans-serif; -fx-font-size: %.1fpx; -fx-font-weight: %s;",
                size, weight.getWeight() >= 600 ? "bold" : "normal"));
    }

    // ============ РОЗКЛАДКА ============
    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        if (w < 50 || h < 50) return;
        bg.setWidth(w);
        bg.setHeight(h);
        cx = w / 2;
        cy = h / 2 + 4;
        ringR = Math.max(110, Math.min(h * 0.345, w * 0.27));
        double nodeR = ringR * 0.148;
        double discR = ringR * 0.76;

        disc.setCenterX(cx);
        disc.setCenterY(cy);
        disc.setRadius(discR);
        dashRing.setCenterX(cx);
        dashRing.setCenterY(cy);
        dashRing.setRadius(discR * 0.93);

        // логотип VITAL у верхній частині диска
        double logoW = discR * 0.60, logoH = discR * 0.36;
        logo.setWidth(logoW);
        logo.setHeight(logoH);
        logo.relocate(cx - logoW / 2, cy - discR * 0.64 - logoH / 2);
        drawLogo();

        fitFont(title, FontWeight.EXTRA_BOLD, discR * 0.52, discR * 1.45);
        fitFont(verdict, FontWeight.BOLD, discR * 0.24, discR * 1.45);
        fitFont(hint, FontWeight.BOLD, discR * 0.20, discR * 1.30);
        placeCentered(title, cx, cy - discR * 0.10);
        placeCentered(verdict, cx, cy + discR * 0.42);
        placeCentered(hint, cx, cy + discR * 0.70);

        double fontSize = Math.max(30, Math.min(44, ringR * 0.20));
        int n = nodes.size();
        for (int i = 0; i < n; i++) {
            NodeView nv = nodes.get(i);
            double ang = Math.toRadians(-90 + i * 360.0 / n);
            double nx = cx + ringR * Math.cos(ang);
            double ny = cy + ringR * Math.sin(ang);
            nv.circle.setCenterX(nx);
            nv.circle.setCenterY(ny);
            double r = nv.item.accent() ? nodeR * 1.3 : nodeR;
            nv.circle.setRadius(r);
            if (nv.icon != null) {
                double is = r * 1.5;
                nv.icon.setWidth(is);
                nv.icon.setHeight(is);
                nv.icon.relocate(nx - is / 2, ny - is / 2);
                drawAstronautIcon(nv.icon);
                accentX = nx;
                accentY = ny;
                accentR = r;
            }
            double cosA = Math.cos(ang);
            // вільне місце для підпису: від вузла до краю вікна (по горизонталі)
            double free = Math.abs(cosA) < 0.1 ? Math.min(nx, w - nx) * 2 - 16
                    : (cosA > 0 ? w - (nx + r + 12) : nx - r - 12) - 12;
            fitFont(nv.label, FontWeight.BOLD, nv.item.accent() ? fontSize + 2 : fontSize, Math.max(120, free));
            nv.label.autosize();
            double lw = nv.label.getWidth(), lh = nv.label.getHeight();
            double cos = Math.cos(ang);
            double lx, ly;
            if (Math.abs(cos) < 0.1) {
                lx = nx - lw / 2;
                // верхній вузол: підпис над ним, нижній: під ним
                ly = Math.sin(ang) < 0 ? ny - r - lh - 8 : ny + r + 8;
            } else if (cos > 0) {
                lx = nx + r + 12;
                ly = ny - lh / 2;
            } else {
                lx = nx - r - 12 - lw;
                ly = ny - lh / 2;
            }
            nv.label.relocate(lx, ly);
            nv.badge.autosize();
            nv.badge.relocate(nx + r * 0.55, ny - r * 1.15);
        }
        drawBackground();
    }

    private void placeCentered(Label l, double x, double y) {
        l.autosize();
        l.relocate(x - l.getWidth() / 2, y - l.getHeight() / 2);
    }

    // ============ ФОН: ЗІРКИ, ОРБІТА ============
    private void drawBackground() {
        double w = bg.getWidth(), h = bg.getHeight();
        if (w < 10 || h < 10) return;
        GraphicsContext g = bg.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        for (double[] s : stars) {
            double a = 0.25 + 0.55 * (0.5 + 0.5 * Math.sin(time * 0.9 + s[3]));
            g.setFill(Color.color(0.85, 0.9, 1.0, a * 0.8));
            g.fillOval(s[0] * w, s[1] * h, s[2], s[2]);
        }

        // орбіта
        g.setLineDashes((double[]) null);
        g.setStroke(Color.color(1, 1, 1, 0.20));
        g.setLineWidth(1.2);
        g.strokeOval(cx - ringR, cy - ringR, ringR * 2, ringR * 2);

        // дрібні поділки
        g.setStroke(Color.color(1, 1, 1, 0.14));
        g.setLineWidth(1);
        double tickIn = ringR * 0.965, tickOut = ringR * 1.0;
        for (int i = 0; i < 120; i++) {
            double a = i * Math.PI * 2 / 120;
            g.strokeLine(cx + tickIn * Math.cos(a), cy + tickIn * Math.sin(a),
                    cx + tickOut * Math.cos(a), cy + tickOut * Math.sin(a));
        }

        // точка, що рухається по орбіті
        g.setFill(Color.WHITE);
        double dx = cx + ringR * Math.cos(orbitAngle), dy = cy + ringR * Math.sin(orbitAngle);
        g.fillOval(dx - 2.5, dy - 2.5, 5, 5);

        // пульсуючі кільця навколо акцентного кола (голограма екіпажу)
        if (!Double.isNaN(accentX)) {
            g.setLineWidth(2);
            for (int k = 0; k < 2; k++) {
                double ph = (time * 0.45 + k * 0.5) % 1.0;
                double rr = accentR * (1.15 + 0.85 * ph);
                g.setStroke(HoloUi.alpha(ACCENT, 0.55 * (1 - ph)));
                g.strokeOval(accentX - rr, accentY - rr, rr * 2, rr * 2);
            }
        }
    }

    // ============ ЛОГОТИП VITAL (атом) ============
    private void drawLogo() {
        double w = logo.getWidth(), h = logo.getHeight();
        if (w < 10 || h < 10) return;
        GraphicsContext g = logo.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        double x = w / 2, y = h / 2;
        double rx = Math.min(w * 0.48, h * 0.95), ry = rx * 0.38;
        g.setLineWidth(Math.max(2, h * 0.05));
        g.setStroke(Color.WHITE);
        for (int k = 0; k < 2; k++) {
            double rot = k == 0 ? 30 : -30;
            g.save();
            g.translate(x, y);
            g.rotate(rot);
            g.strokeOval(-rx, -ry, rx * 2, ry * 2);
            // «електрон», що біжить по орбіті
            double a = time * (k == 0 ? 1.4 : -1.1) + k * 2;
            g.setFill(Color.web("#7DD3FC"));
            g.fillOval(rx * Math.cos(a) - 3, ry * Math.sin(a) - 3, 6, 6);
            g.restore();
        }
        g.setFill(Color.WHITE);
        double nr = Math.max(3.5, h * 0.06);
        g.fillOval(x - nr, y - nr, nr * 2, nr * 2);
    }

    // ============ ІКОНКА КОСМОНАВТА (шолом) ============
    private void drawAstronautIcon(Canvas c) {
        double s = c.getWidth();
        if (s < 8) return;
        GraphicsContext g = c.getGraphicsContext2D();
        g.clearRect(0, 0, s, s);
        double m = s * 0.5;
        // шолом
        g.setFill(Color.web("#F8FAFC"));
        g.fillOval(m - s * 0.42, m - s * 0.44, s * 0.84, s * 0.84);
        // плечі
        g.setFill(Color.web("#E2E8F0"));
        g.fillRoundRect(m - s * 0.36, m + s * 0.28, s * 0.72, s * 0.2, s * 0.14, s * 0.14);
        // скло
        g.setFill(Color.web("#1E1B4B"));
        g.fillOval(m - s * 0.31, m - s * 0.30, s * 0.62, s * 0.50);
        // відблиск
        g.setFill(Color.color(0.65, 0.55, 1.0, 0.85));
        g.fillOval(m - s * 0.22, m - s * 0.22, s * 0.20, s * 0.12);
    }
}