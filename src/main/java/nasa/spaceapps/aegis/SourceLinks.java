package nasa.spaceapps.aegis;

import javafx.application.HostServices;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.stage.Window;

/**
 * Клікабельні посилання на джерела. Відкриваються у вбудованому переглядачі застосунку
 * (SourceBrowserWindow). Якщо він недоступний, сторінка відкривається у системному браузері;
 * якщо не вийшло і так, адреса копіюється в буфер обміну.
 */
public final class SourceLinks {

    private static HostServices host;
    private static Window owner;

    private static final String LINK_STYLE = "-fx-text-fill: #38BDF8; -fx-font-size: 11px; -fx-padding: 0; "
            + "-fx-border-color: transparent; -fx-cursor: hand;";

    private SourceLinks() {
    }

    /** Викликається один раз при старті застосунку. */
    public static void init(HostServices hostServices) {
        host = hostServices;
    }

    /** Те саме, але вікно переглядача прив'язується до головного вікна (закривається разом із ним). */
    public static void init(HostServices hostServices, Window ownerWindow) {
        host = hostServices;
        owner = ownerWindow;
    }

    /** Відкриває сторінку у вбудованому переглядачі. */
    public static void open(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        try {
            SourceBrowserWindow.open(url, owner, SourceLinks::openExternal);
            return;
        } catch (Exception | LinkageError e) {
            // WebView недоступний (немає javafx-web або системних бібліотек): відкриємо у браузері
        }
        openExternal(url);
    }

    /** Відкриває сторінку у системному браузері; якщо не вийшло, копіює адресу в буфер обміну. */
    public static void openExternal(String url) {
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
        h.setTooltip(new Tooltip(url + "\nКлік відкриє сторінку у вікні застосунку"));
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