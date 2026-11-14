package nasa.spaceapps.aegis;

import javafx.application.HostServices;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;

/**
 * Клікабельні посилання на джерела. Відкриваються у браузері; якщо система не змогла
 * відкрити, адреса копіюється в буфер обміну.
 */
public final class SourceLinks {

    private static HostServices host;

    private static final String LINK_STYLE = "-fx-text-fill: #38BDF8; -fx-font-size: 11px; -fx-padding: 0; "
            + "-fx-border-color: transparent; -fx-cursor: hand;";

    private SourceLinks() {
    }

    /** Викликається один раз при старті застосунку. */
    public static void init(HostServices hostServices) {
        host = hostServices;
    }

    public static void open(String url) {
        try {
            if (host != null) {
                host.showDocument(url);
                return;
            }
        } catch (Exception ignored) {
            // нижче скопіюємо адресу
        }
        ClipboardContent c = new ClipboardContent();
        c.putString(url);
        Clipboard.getSystemClipboard().setContent(c);
    }

    public static Hyperlink link(String text, String url) {
        Hyperlink h = new Hyperlink(text);
        h.setStyle(LINK_STYLE);
        h.setFocusTraversable(false);
        h.setTooltip(new Tooltip(url + "\nКлік відкриє сторінку в браузері"));
        h.setOnAction(e -> open(url));
        return h;
    }

    public static Hyperlink link(SpaceSources.Link l) {
        return link(l.title() + " ↗", l.url());
    }

    /** Рядок «Основа: 📎 джерело ↗ 📎 джерело ↗» для вставки під блоками застосунку. */
    public static FlowPane row(String caption, SpaceSources.Link... links) {
        FlowPane p = new FlowPane(10, 2);
        p.setAlignment(Pos.CENTER_LEFT);
        if (caption != null && !caption.isEmpty()) {
            Label c = new Label(caption);
            c.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
            p.getChildren().add(c);
        }
        for (SpaceSources.Link l : links) {
            p.getChildren().add(link("📎 " + l.title() + " ↗", l.url()));
        }
        return p;
    }
}
