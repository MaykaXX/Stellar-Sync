package nasa.spaceapps.aegis;

import javafx.beans.binding.Bindings;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * Вбудований переглядач джерел: сторінки NASA та CSA відкриваються у вікні застосунку.
 * Одне вікно використовується повторно: наступне посилання завантажується в нього ж.
 * Якщо сторінка не завантажилась (немає інтернету, сайт не підтримує вбудований рушій),
 * на екрані з'являється повідомлення з кнопкою «Відкрити у браузері».
 */
public final class SourceBrowserWindow {

    private static final String BTN = "-fx-background-color: #1E293B; -fx-text-fill: #E2E8F0; "
            + "-fx-background-radius: 6; -fx-cursor: hand;";

    private static SourceBrowserWindow instance;

    private final Stage stage = new Stage();
    private final WebView view = new WebView();
    private final WebEngine engine = view.getEngine();
    private final VBox errorBox = new VBox(10);
    private final Label errorText = new Label();
    private final Consumer<String> openExternally;
    private String requestedUrl = "";

    /** Відкриває (або повторно використовує) вікно й завантажує сторінку. */
    public static void open(String url, Window owner, Consumer<String> openExternally) {
        if (instance == null) {
            instance = new SourceBrowserWindow(owner, openExternally);
        }
        instance.show(url);
    }

    private SourceBrowserWindow(Window owner, Consumer<String> openExternally) {
        this.openExternally = openExternally;
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("AEGIS-BIO: джерела");
        stage.setWidth(1100);
        stage.setHeight(760);

        // Посилання з target="_blank" відкриваємо в цьому ж вікні
        engine.setCreatePopupHandler(cfg -> engine);

        WebHistory history = engine.getHistory();

        Button back = new Button("◀");
        back.setStyle(BTN);
        back.setTooltip(new Tooltip("Назад"));
        back.disableProperty().bind(history.currentIndexProperty().lessThanOrEqualTo(0));
        back.setOnAction(e -> {
            if (history.getCurrentIndex() > 0) history.go(-1);
        });

        Button forward = new Button("▶");
        forward.setStyle(BTN);
        forward.setTooltip(new Tooltip("Уперед"));
        forward.disableProperty().bind(history.currentIndexProperty()
                .greaterThanOrEqualTo(Bindings.size(history.getEntries()).subtract(1)));
        forward.setOnAction(e -> {
            if (history.getCurrentIndex() < history.getEntries().size() - 1) history.go(1);
        });

        Button reload = new Button("⟳");
        reload.setStyle(BTN);
        reload.setTooltip(new Tooltip("Оновити"));
        reload.setOnAction(e -> engine.reload());

        Label address = new Label();
        address.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 12px;");
        address.textProperty().bind(engine.locationProperty());
        address.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(address, Priority.ALWAYS);

        Button external = new Button("↗ У браузері");
        external.setStyle(BTN);
        external.setTooltip(new Tooltip("Відкрити цю сторінку у системному браузері"));
        external.setOnAction(e -> openExternally.accept(currentUrl()));

        Button close = new Button("✕");
        close.setStyle(BTN);
        close.setOnAction(e -> stage.close());

        HBox bar = new HBox(8, back, forward, reload, address, external, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));
        bar.setStyle("-fx-background-color: #0B111E; -fx-border-color: #1E293B; -fx-border-width: 0 0 1 0;");

        ProgressBar progress = new ProgressBar();
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(4);
        progress.setStyle("-fx-accent: #38BDF8;");
        progress.progressProperty().bind(engine.getLoadWorker().progressProperty());
        progress.visibleProperty().bind(engine.getLoadWorker().stateProperty().isEqualTo(Worker.State.RUNNING));
        progress.managedProperty().bind(progress.visibleProperty());

        errorText.setWrapText(true);
        errorText.setMaxWidth(520);
        errorText.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 14px;");
        Button retry = new Button("Спробувати ще раз");
        retry.setStyle(BTN);
        retry.setOnAction(e -> engine.load(requestedUrl));
        Button openOut = new Button("Відкрити у браузері");
        openOut.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-background-radius: 6; -fx-cursor: hand;");
        openOut.setOnAction(e -> openExternally.accept(currentUrl()));
        HBox errButtons = new HBox(10, retry, openOut);
        errButtons.setAlignment(Pos.CENTER);
        errorBox.getChildren().addAll(new Label("⚠️"), errorText, errButtons);
        errorBox.setAlignment(Pos.CENTER);
        errorBox.setStyle("-fx-background-color: #060A12;");
        errorBox.setVisible(false);
        errorBox.getChildren().get(0).setStyle("-fx-font-size: 36px;");

        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.FAILED) {
                errorText.setText("Не вдалося завантажити сторінку.\nПеревірте інтернет-з'єднання: "
                        + "застосунок працює офлайн, а джерела потребують мережі. "
                        + "Деякі сайти можуть не відкриватись у вбудованому переглядачі.");
                errorBox.setVisible(true);
            } else if (state == Worker.State.RUNNING || state == Worker.State.SUCCEEDED) {
                errorBox.setVisible(false);
            }
        });
        engine.titleProperty().addListener((obs, old, t) -> {
            if (t != null && !t.isBlank()) stage.setTitle("AEGIS-BIO: " + t);
        });

        StackPane center = new StackPane(view, errorBox);
        VBox top = new VBox(bar, progress);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(center);
        root.setStyle("-fx-background-color: #060A12;");
        stage.setScene(new Scene(root));

        // Закрили вікно: зупиняємо сторінку (відео, скрипти) і звільняємо ресурси
        stage.setOnHidden(e -> engine.load("about:blank"));
    }

    private void show(String url) {
        requestedUrl = url;
        errorBox.setVisible(false);
        if (!stage.isShowing()) {
            // Нове відкриття: стираємо історію, щоб кнопка «Назад» не вела на порожню сторінку
            WebHistory h = engine.getHistory();
            h.setMaxSize(0);
            h.setMaxSize(100);
        }
        engine.load(url);
        if (!stage.isShowing()) stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    private String currentUrl() {
        String loc = engine.getLocation();
        return (loc == null || loc.isBlank() || loc.startsWith("about:")) ? requestedUrl : loc;
    }
}