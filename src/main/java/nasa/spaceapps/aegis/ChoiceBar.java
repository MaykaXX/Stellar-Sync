package nasa.spaceapps.aegis;

import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.paint.Color;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Панель кнопок-варіантів замість випадаючого списку: усі варіанти видно одразу,
 * вибраний підсвічений. Перенесеться на новий рядок, якщо не вміщається.
 */
public final class ChoiceBar<T> extends FlowPane {

    private final ToggleGroup group = new ToggleGroup();
    private final Map<T, ToggleButton> buttons = new LinkedHashMap<>();
    private final Function<T, Color> colorOf;
    private T value;
    private Consumer<T> onChange;

    public ChoiceBar(List<T> items, Function<T, String> label, Function<T, Color> color, Function<T, String> tip) {
        super(8, 8);
        this.colorOf = color;
        for (T item : items) {
            ToggleButton b = new ToggleButton(label.apply(item));
            b.setToggleGroup(group);
            b.setFocusTraversable(false);
            b.setMinHeight(34);
            if (tip != null) {
                String t = tip.apply(item);
                if (t != null && !t.isEmpty()) b.setTooltip(new Tooltip(t));
            }
            b.setOnAction(e -> {
                if (!b.isSelected()) {      // повторний клік по вибраному: лишаємо вибір
                    b.setSelected(true);
                    return;
                }
                value = item;
                if (onChange != null) onChange.accept(item);
            });
            b.selectedProperty().addListener((o, a, s) -> restyle(b, item));
            b.hoverProperty().addListener((o, a, s) -> restyle(b, item));
            buttons.put(item, b);
            restyle(b, item);
            getChildren().add(b);
        }
    }

    public ChoiceBar(List<T> items, Function<T, String> label) {
        this(items, label, i -> HoloUi.CYAN, null);
    }

    /** Обробник спрацьовує лише від кліку користувача, а не від setValue. */
    public void setOnChange(Consumer<T> handler) {
        this.onChange = handler;
    }

    public T getValue() {
        return value;
    }

    /** Програмно вибирає варіант (обробник не викликається). */
    public void setValue(T v) {
        value = v;
        ToggleButton b = buttons.get(v);
        if (b != null) b.setSelected(true);
    }

    private void restyle(ToggleButton b, T item) {
        Color c = colorOf.apply(item);
        if (b.isSelected()) {
            b.setStyle("-fx-background-color: " + HoloUi.rgba(c, 0.25) + "; -fx-border-color: " + HoloUi.hex(c)
                    + "; -fx-border-width: 2; -fx-border-radius: 18; -fx-background-radius: 18; "
                    + "-fx-text-fill: white; -fx-font-size: 26px; -fx-font-weight: bold; "
                    + "-fx-padding: 6 16 6 16; -fx-cursor: hand;");
        } else {
            b.setStyle("-fx-background-color: " + (b.isHover() ? "#16263F" : "#0F1A2E")
                    + "; -fx-border-color: " + (b.isHover() ? HoloUi.hex(c) : "#27406A")
                    + "; -fx-border-width: 1.5; -fx-border-radius: 18; -fx-background-radius: 18; "
                    + "-fx-text-fill: #CBD5E1; -fx-font-size: 26px; -fx-font-weight: normal; "
                    + "-fx-padding: 7 16 7 16; -fx-cursor: hand;");
        }
    }
}
