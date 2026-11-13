package nasa.spaceapps.aegis;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Merkle-Delta Sync Vault: локальний буфер телеметрії для радіоблекауту.
 *
 * <p>Кожну хвилину місії корабель робить один «мікро-пакет». Пакет не містить повних значень:
 * лише ДЕЛЬТУ відносно попереднього пакета (змінені показники, цілі числа з кроком 0,01,
 * zig-zag + varint). Перший пакет — ключовий кадр (повні значення).
 *
 * <p>Хеш кожного пакета — листок дерева Меркла (SHA-256). Після відновлення зв'язку на Землю
 * йде лише корінь (32 байти). Якщо корені збігаються, усі пакети цілі. Якщо ні, Земля
 * спускається по дереву, порівнюючи хеші лише тих піддерев, що розійшлися, знаходить
 * пошкоджений пакет і просить повторно передати тільки його.
 *
 * <p>Клас не залежить від JavaFX.
 */
public final class MerkleDeltaVault {

    /** Крок квантування: значення зберігаються з точністю до 1/SCALE. */
    public static final int SCALE = 100;
    public static final int HASH_BYTES = 32;
    /** «Сирий» пакет: 8 байт часу + по 8 байт (double) на показник. */
    private static final int RAW_TIME_BYTES = 8;
    private static final int RAW_VALUE_BYTES = 8;

    private final int metricCount;
    private final List<byte[]> payloads = new ArrayList<>();
    private final List<byte[]> leafHashes = new ArrayList<>();
    private int[] previous;
    private long packedBytes = 0;

    private List<byte[][]> levelsCache = null;

    public MerkleDeltaVault(int metricCount) {
        if (metricCount <= 0) throw new IllegalArgumentException("metricCount");
        this.metricCount = metricCount;
    }

    // ------------------------------------------------------------------ буфер

    public synchronized void clear() {
        payloads.clear();
        leafHashes.clear();
        previous = null;
        packedBytes = 0;
        levelsCache = null;
    }

    public synchronized int size() {
        return payloads.size();
    }

    /** Розмір усіх дельта-пакетів, байт. */
    public synchronized long packedBytes() {
        return packedBytes;
    }

    /** Скільки зайняли б ті самі пакети без стиснення, байт. */
    public synchronized long rawBytes() {
        return (long) payloads.size() * (RAW_TIME_BYTES + (long) RAW_VALUE_BYTES * metricCount);
    }

    /** Коефіцієнт стиснення (сирий розмір / дельта-розмір). */
    public synchronized double ratio() {
        return packedBytes == 0 ? 0 : (double) rawBytes() / packedBytes;
    }

    /** Додає новий пакет зі знімка показників. */
    public synchronized void append(double[] values) {
        if (values.length != metricCount) throw new IllegalArgumentException("values.length");
        int[] q = new int[metricCount];
        for (int i = 0; i < metricCount; i++) q[i] = (int) Math.round(values[i] * SCALE);

        byte[] payload = encode(previous, q, metricCount);
        int index = payloads.size();
        payloads.add(payload);
        leafHashes.add(leafHash(index, payload));
        packedBytes += payload.length;
        previous = q;
        levelsCache = null;
    }

    // ------------------------------------------------------------------ кодування

    static byte[] encode(int[] prev, int[] cur, int n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (prev == null) { // ключовий кадр
            for (int i = 0; i < n; i++) writeVarint(out, cur[i]);
            return out.toByteArray();
        }
        int maskBytes = (n + 7) / 8;
        byte[] mask = new byte[maskBytes];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < n; i++) {
            int d = cur[i] - prev[i];
            if (d != 0) {
                mask[i / 8] |= (byte) (1 << (i % 8));
                writeVarint(body, d);
            }
        }
        out.writeBytes(mask);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /**
     * Відновлює квантовані значення з пакетів (так це робить Земля).
     * Повертає [пакет][показник]; значення треба поділити на {@link #SCALE}.
     */
    public static int[][] decode(List<byte[]> packets, int n) {
        int[][] res = new int[packets.size()][];
        int[] prev = null;
        for (int p = 0; p < packets.size(); p++) {
            byte[] b = packets.get(p);
            int[] cur = new int[n];
            int[] pos = {0};
            if (prev == null) {
                for (int i = 0; i < n; i++) cur[i] = (int) readVarint(b, pos);
            } else {
                int maskBytes = (n + 7) / 8;
                pos[0] = maskBytes;
                for (int i = 0; i < n; i++) {
                    boolean changed = (b[i / 8] & (1 << (i % 8))) != 0;
                    cur[i] = prev[i] + (changed ? (int) readVarint(b, pos) : 0);
                }
            }
            res[p] = cur;
            prev = cur;
        }
        return res;
    }

    private static void writeVarint(ByteArrayOutputStream o, long v) {
        long z = (v << 1) ^ (v >> 63); // zig-zag
        while ((z & ~0x7FL) != 0) {
            o.write((int) ((z & 0x7F) | 0x80));
            z >>>= 7;
        }
        o.write((int) z);
    }

    private static long readVarint(byte[] b, int[] pos) {
        long z = 0;
        int shift = 0;
        while (true) {
            int x = b[pos[0]++] & 0xFF;
            z |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return (z >>> 1) ^ -(z & 1);
    }

    // ------------------------------------------------------------------ дерево Меркла

    private static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 є в кожній JVM
        }
    }

    /** Листок: SHA-256(0x00 || індекс || пакет). Індекс прив'язує хеш до місця в послідовності. */
    static byte[] leafHash(int index, byte[] payload) {
        byte[] idx = {(byte) (index >>> 24), (byte) (index >>> 16), (byte) (index >>> 8), (byte) index};
        return sha256(new byte[]{0x00}, idx, payload);
    }

    /** Вузол: SHA-256(0x01 || ліво || право). Префікси 0x00/0x01 не дають підмінити листок вузлом. */
    static byte[] nodeHash(byte[] l, byte[] r) {
        return sha256(new byte[]{0x01}, l, r);
    }

    /** Рівні дерева: [0] = листки, [останній] = корінь. Непарний вузол піднімається нагору без дублювання. */
    static List<byte[][]> buildLevels(List<byte[]> leaves) {
        List<byte[][]> levels = new ArrayList<>();
        if (leaves.isEmpty()) return levels;
        byte[][] cur = leaves.toArray(new byte[0][]);
        levels.add(cur);
        while (cur.length > 1) {
            byte[][] next = new byte[(cur.length + 1) / 2][];
            for (int i = 0; i < next.length; i++) {
                int l = 2 * i, r = l + 1;
                next[i] = r < cur.length ? nodeHash(cur[l], cur[r]) : cur[l];
            }
            levels.add(next);
            cur = next;
        }
        return levels;
    }

    /** Рівні дерева (кеш скидається при додаванні пакета). Порожній список, якщо пакетів немає. */
    public synchronized List<byte[][]> levels() {
        if (levelsCache == null) levelsCache = buildLevels(leafHashes);
        return levelsCache;
    }

    /** Корінь дерева (хеш-proof цілісності всього блекауту) або null, якщо пакетів немає. */
    public synchronized byte[] root() {
        List<byte[][]> lv = levels();
        return lv.isEmpty() ? null : lv.get(lv.size() - 1)[0];
    }

    public static String shortHex(byte[] h) {
        if (h == null) return "—";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(String.format("%02x", h[i]));
        return sb.toString();
    }

    public static String fullHex(byte[] h) {
        if (h == null) return "—";
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ------------------------------------------------------------------ передача та перевірка

    /** Копія пакетів «в ефірі»: саме її отримує Земля (її можуть пошкодити). */
    public synchronized List<byte[]> transmissionCopy() {
        List<byte[]> copy = new ArrayList<>(payloads.size());
        for (byte[] p : payloads) copy.add(p.clone());
        return copy;
    }

    /** Імітує космічну радіацію: перевертає один біт у пакеті. */
    public static void flipBit(List<byte[]> received, int packetIndex, int bit) {
        byte[] p = received.get(packetIndex);
        int k = Math.floorMod(bit, p.length * 8);
        p[k / 8] ^= (byte) (1 << (k % 8));
    }

    /**
     * Підсумок перевірки.
     *
     * @param hashesFetched  скільки хешів Земля запитала в корабля, спускаючись по дереву
     * @param proofBytes     корінь + запитані хеші, байт (вартість пошуку пошкоджень)
     * @param repairBytes    повторно передані пакети, байт
     * @param fullResendBytes скільки коштувала б повна повторна передача всіх дельта-пакетів
     * @param rawResendBytes  скільки коштувала б повна передача без стиснення
     */
    public record VerifyReport(boolean intactOnFirstCheck, boolean intactAfterRepair, int packets,
                               byte[] senderRoot, byte[] receiverRoot, List<Integer> corrupted,
                               int hashesFetched, long proofBytes, long repairBytes,
                               long fullResendBytes, long rawResendBytes) {}

    /**
     * Перевірка на стороні Землі. Земля має лише отримані пакети та корінь корабля.
     * 1) Будує дерево з отриманого й порівнює корінь.
     * 2) Якщо корені різні, спускається лише у піддерева з різними хешами (запитує їх у корабля).
     * 3) Просить повторно передати тільки знайдені пакети, знову порівнює корінь.
     * Список {@code received} виправляється на місці.
     */
    public synchronized VerifyReport verify(List<byte[]> received) {
        if (received.size() != payloads.size()) throw new IllegalArgumentException("size mismatch");
        int n = payloads.size();
        List<byte[][]> sender = levels();
        if (n == 0) {
            return new VerifyReport(true, true, 0, null, null, List.of(), 0, 0, 0, 0, 0);
        }
        byte[] senderRoot = sender.get(sender.size() - 1)[0];

        List<byte[]> rxLeaves = new ArrayList<>(n);
        for (int i = 0; i < n; i++) rxLeaves.add(leafHash(i, received.get(i)));
        List<byte[][]> rx = buildLevels(rxLeaves);
        byte[] rxRoot = rx.get(rx.size() - 1)[0];

        List<Integer> bad = new ArrayList<>();
        int[] fetched = {0};
        boolean intact = Arrays.equals(senderRoot, rxRoot);
        if (!intact) {
            descend(sender, rx, sender.size() - 1, 0, bad, fetched);
        }
        List<Integer> corrupted = new ArrayList<>(new TreeSet<>(bad));

        long repair = 0;
        for (int idx : corrupted) {
            byte[] good = payloads.get(idx);
            received.set(idx, good.clone());
            repair += good.length + 4; // пакет + його номер
        }

        // Повторна перевірка після ремонту
        List<byte[]> fixedLeaves = new ArrayList<>(n);
        for (int i = 0; i < n; i++) fixedLeaves.add(leafHash(i, received.get(i)));
        List<byte[][]> fixed = buildLevels(fixedLeaves);
        boolean ok = Arrays.equals(senderRoot, fixed.get(fixed.size() - 1)[0]);

        long proof = HASH_BYTES + (long) fetched[0] * HASH_BYTES;
        return new VerifyReport(intact, ok, n, senderRoot, rxRoot, corrupted, fetched[0],
                proof, repair, packedBytes, rawBytes());
    }

    /** Спуск по дереву: заходимо лише у вузли, хеш яких у Землі та в корабля не збігається. */
    private static void descend(List<byte[][]> sender, List<byte[][]> rx, int level, int idx,
                                List<Integer> bad, int[] fetched) {
        if (Arrays.equals(sender.get(level)[idx], rx.get(level)[idx])) return;
        if (level == 0) {
            bad.add(idx);
            return;
        }
        int children = sender.get(level - 1).length;
        int l = 2 * idx, r = l + 1;
        fetched[0] += (r < children) ? 2 : 1; // Земля запитує хеші дітей у корабля
        descend(sender, rx, level - 1, l, bad, fetched);
        if (r < children) descend(sender, rx, level - 1, r, bad, fetched);
    }
}
