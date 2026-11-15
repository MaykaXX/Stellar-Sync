package nasa.spaceapps.aegis;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.List;
import java.util.Set;

/**
 * Візуалізація дерева Меркла з буфера {@link MerkleDeltaVault}.
 *
 * <p>Показано корінь і до трьох рівнів під ним (до 8 вузлів унизу). Кожен вузол підписано
 * скороченим хешем SHA-256. Вузли на шляху до пошкодженого пакета підсвічуються червоним:
 * саме так Земля знаходить пошкодження, спускаючись лише по піддеревах, що розійшлися.
 */
public final class MerkleTreeView extends Canvas {

    private static final int MAX_ROWS = 4;
    private static final double ROW_H = 62;
    private static final double NODE_H = 36;
    private static final double CAPTION_H = 34;

    private List<byte[][]> levels = List.of();
    private Set<Integer> corrupted = Set.of();

    public MerkleTreeView(double width) {
        super(width, MAX_ROWS * ROW_H + CAPTION_H);
        draw();
    }

    /** Оновлює дерево. corrupted: номери пакетів, що були пошкоджені (порожньо: усе гаразд). */
    public void show(List<byte[][]> levels, Set<Integer> corrupted) {
        this.levels = levels;
        this.corrupted = corrupted;
        draw();
    }

    private void draw() {
        double w = getWidth(), h = getHeight();
        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setTextAlign(TextAlignment.CENTER);

        if (levels.isEmpty()) {
            g.setFill(Color.web("#94A3B8"));
            g.setFont(Font.font("Consolas", FontWeight.NORMAL, 20));
            g.fillText("The tree will appear once the buffer has its first packet", w / 2, h / 2);
            return;
        }

        int top = levels.size() - 1;
        int rows = Math.min(MAX_ROWS, levels.size());
        int bottomLevel = top - (rows - 1);

        // лінії від батьківських вузлів до дітей
        g.setLineWidth(1.6);
        for (int r = 1; r < rows; r++) {
            int level = top - r;
            byte[][] row = levels.get(level);
            for (int i = 0; i < row.length; i++) {
                boolean bad = isBad(level, i);
                g.setStroke(bad ? Color.web("#EF4444") : Color.web("#475A78"));
                g.strokeLine(x(r, i / 2, w), y(r - 1) + NODE_H, x(r, i, w), y(r));
            }
        }

        // вузли
        double nodeW = Math.min(112, w / (1 << (rows - 1)) - 8);
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 17));
        for (int r = 0; r < rows; r++) {
            int level = top - r;
            byte[][] row = levels.get(level);
            for (int i = 0; i < row.length; i++) {
                boolean bad = isBad(level, i);
                double cx = x(r, i, w), ny = y(r);
                g.setFill(bad ? Color.web("#450A0A") : r == 0 ? Color.web("#1E1B4B") : Color.web("#0F172A"));
                g.fillRoundRect(cx - nodeW / 2, ny, nodeW, NODE_H, 8, 8);
                g.setStroke(bad ? Color.web("#EF4444") : r == 0 ? Color.web("#A78BFA") : Color.web("#38BDF8"));
                g.setLineWidth(bad || r == 0 ? 2.4 : 1.4);
                g.strokeRoundRect(cx - nodeW / 2, ny, nodeW, NODE_H, 8, 8);
                g.setFill(bad ? Color.web("#FCA5A5") : Color.web("#E2E8F0"));
                g.fillText(MerkleDeltaVault.shortHex(row[i]), cx, ny + NODE_H / 2 + 6);
            }
        }

        // підпис: що означає нижній ряд
        g.setFont(Font.font("Consolas", FontWeight.NORMAL, 17));
        g.setFill(Color.web("#A3B1C6"));
        long perNode = 1L << bottomLevel;
        String caption = bottomLevel == 0
                ? "Bottom row: hashes of individual packets"
                : "Bottom row: each node = hash of a group of " + perNode + " packets";
        g.fillText(caption, w / 2, h - 10);
    }

    private boolean isBad(int level, int idx) {
        for (int p : corrupted) {
            if ((p >> level) == idx) return true;
        }
        return false;
    }

    private static double x(int row, int idx, double w) {
        int slots = 1 << row;
        return (idx + 0.5) * w / slots;
    }

    private static double y(int row) {
        return 6 + row * ROW_H;
    }
}
