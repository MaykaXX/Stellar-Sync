package nasa.spaceapps.aegis;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;

/**
 * Локальна децентралізована мережа екіпажу (демо-модель Bluetooth LE / UWB).
 * <ul>
 *   <li>Кожен пристрій шле «heartbeat» сусідам і стежить, чи вони на зв'язку.</li>
 *   <li>Якщо показники критичні, а «Усе гаразд» не підтверджено за вікно (60 с),
 *       пристрій сам розсилає аварійний пакет усім іншим.</li>
 *   <li>Пакет шифрується AES-256-GCM спільним ключем екіпажу (ключ генерується при запуску,
 *       як імітація попереднього сполучення пристроїв). Отримувач розшифровує і перевіряє тег.</li>
 * </ul>
 * Радіоканал, відстані та RSSI змодельовані, це демонстрація, а не реальний Bluetooth-стек.
 * Клас не залежить від JavaFX.
 */
public final class CrewMeshNetwork {

    public enum Kind { INFO, OK, WARN, SOS, RX }

    public record MeshEvent(Kind kind, String text, CrewMemberState subject) {}

    public enum PacketType { HEARTBEAT, EMERGENCY, ACK, PING, PONG }

    public static final class Packet {
        public final CrewMemberState from, to;
        public final PacketType type;
        public final double born, dur;
        final Runnable onArrive, onDrop;

        Packet(CrewMemberState from, CrewMemberState to, PacketType type, double born, double dur,
               Runnable onArrive, Runnable onDrop) {
            this.from = from;
            this.to = to;
            this.type = type;
            this.born = born;
            this.dur = dur;
            this.onArrive = onArrive;
            this.onDrop = onDrop;
        }

        /** 0..1 по шляху; від'ємне значення: пакет ще не відправлено. */
        public double progress(double now) {
            return (now - born) / dur;
        }
    }

    public record Helper(CrewMemberState helper, CrewMemberState.IncomingAlert alert) {}

    private record Scheduled(double at, Runnable action) {}

    // ---------- налаштування ----------
    private static final double STEP = 0.1;
    private static final double HEARTBEAT_EVERY = 2.0;
    private static final double OFFLINE_AFTER = 6.0;
    private static final double BEACON_EVERY = 4.0;
    private static final double SHIP_W_M = 26, SHIP_H_M = 9;

    private final List<CrewMemberState> members = new ArrayList<>();
    private final List<Packet> packets = new ArrayList<>();
    private final List<Scheduled> scheduled = new ArrayList<>();
    private final List<Consumer<MeshEvent>> listeners = new ArrayList<>();
    private final SecretKey crewKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Random rng = new Random(42);

    private double clock = 0;
    private double accum = 0;
    private double nextHeartbeat = 0;
    private double confirmWindow = 60;

    public CrewMeshNetwork() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            crewKey = kg.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("AES-256 unavailable", e);
        }
    }

    /** Три члени екіпажу з готовим розташуванням у відсіках. Перший: користувач. */
    public static CrewMeshNetwork createDefaultCrew() {
        CrewMeshNetwork n = new CrewMeshNetwork();
        CrewMemberState a = new CrewMemberState("CREW_01", "Commander (Alpha)", "Crew commander", 78, 62, 95, true);
        CrewMemberState b = new CrewMemberState("CREW_02", "Engineer (Beta)", "Systems engineer", 72, 70, 92, false);
        CrewMemberState c = new CrewMemberState("CREW_03", "Medic (Gamma)", "Biochemist / Doctor", 81, 55, 88, false);
        a.setPlacement(0.14, 0.50, "Command module");
        b.setPlacement(0.80, 0.24, "Engineering bay");
        c.setPlacement(0.64, 0.80, "Medical bay");
        a.setDemoAnomaly(40, 13);
        b.setDemoAnomaly(38, 12);
        c.setDemoAnomaly(138, 20);
        n.members.add(a);
        n.members.add(b);
        n.members.add(c);
        return n;
    }

    // ============ ПІДПИСКА І ДОСТУП ============
    public void addListener(Consumer<MeshEvent> l) { listeners.add(l); }
    public List<CrewMemberState> members() { return Collections.unmodifiableList(members); }
    public List<Packet> packets() { return packets; }
    public double now() { return clock; }
    public double getConfirmWindow() { return confirmWindow; }
    public void setConfirmWindow(double sec) { this.confirmWindow = Math.max(5, sec); }

    public CrewMemberState local() {
        for (CrewMemberState m : members) if (m.isLocal()) return m;
        return members.get(0);
    }

    public int onlineCount() {
        int n = 0;
        for (CrewMemberState m : members) if (!m.isOffline()) n++;
        return n;
    }

    public int activeSosCount() {
        int n = 0;
        for (CrewMemberState m : members) if (m.isSosActive()) n++;
        return n;
    }

    public int incomingAlertCount() {
        int n = 0;
        for (CrewMemberState m : members) n += m.alerts.size();
        return n;
    }

    /** Хто вже поспішає на допомогу потерпілому. */
    public List<Helper> helpersOf(CrewMemberState victim) {
        List<Helper> res = new ArrayList<>();
        for (CrewMemberState m : members) {
            CrewMemberState.IncomingAlert al = m.alerts.get(victim.getId());
            if (al != null && al.responding) res.add(new Helper(m, al));
        }
        return res;
    }

    public int deliveredCount(CrewMemberState victim) {
        int n = 0;
        for (CrewMemberState m : members) if (m.alerts.containsKey(victim.getId())) n++;
        return n;
    }


    // ============ ДІЇ КОРИСТУВАЧА ============
    public void setLocalVitals(double hr, double hrv, double hyd) {
        local().setExternalVitals(hr, hrv, hyd);
    }

    /** «Усе гаразд». Повертає true, якщо була тривога (таймер або SOS), яку скасовано. */
    public boolean acknowledge(CrewMemberState m) {
        boolean something = m.acknowledge(clock);
        if (something) {
            event(Kind.OK, "✅ " + m.shortName() + " confirmed “All good”: alarm cancelled.", m);
        } else {
            event(Kind.INFO, "✅ " + m.shortName() + ": status confirmed, all good.", m);
        }
        return something;
    }

    public void setManualSos(CrewMemberState m, boolean on) {
        if (on && !m.isSosActive()) {
            m.raiseManualSos();
        } else if (!on && m.isSosActive()) {
            m.clearSos(clock);
        }
    }

    public void cancelSos(CrewMemberState victim, CrewMemberState by) {
        if (!victim.isSosActive()) return;
        victim.clearSos(clock);
        if (by != null && by != victim) {
            event(Kind.OK, "✅ " + by.shortName() + " finished helping: SOS from "
                    + victim.shortName() + " cleared.", victim);
        }
    }

    public void toggleAnomaly(CrewMemberState m) {
        boolean on = !m.isAnomalyActive();
        if (on) m.resetGrace();   // новий збій після «Усе гаразд» має знову запускати таймер
        m.setAnomalyActive(on);
        event(on ? Kind.WARN : Kind.INFO,
                on ? "⚡ [DEMO] " + m.shortName() + ": simulating a sudden vitals failure."
                        : "⚡ [DEMO] " + m.shortName() + ": failure simulation stopped.", m);
    }

    public void toggleLink(CrewMemberState m) {
        m.linkUp = !m.linkUp;
        event(m.linkUp ? Kind.INFO : Kind.WARN,
                m.linkUp ? "📡 [DEMO] " + m.shortName() + ": radio module turned on."
                        : "📡 [DEMO] " + m.shortName() + ": radio module turned off (simulated battery drain / leaving the zone).", m);
    }

    public void mute(CrewMemberState receiver, String senderId) {
        CrewMemberState.IncomingAlert al = receiver.alerts.get(senderId);
        if (al != null) al.muted = true;
    }

    /** Отримувач натиснув «Іду на допомогу». */
    public void respond(CrewMemberState receiver, String senderId) {
        CrewMemberState.IncomingAlert al = receiver.alerts.get(senderId);
        if (al == null || al.responding) return;
        CrewMemberState victim = byId(senderId);
        if (victim == null) return;
        al.muted = true;
        al.responding = true;
        al.respondStart = clock;
        al.respondDuration = Math.max(6, Math.min(14, al.distanceM * 0.7));
        event(Kind.OK, "🏃 " + receiver.shortName() + " set off toward " + victim.shortName()
                + " (≈" + Math.round(al.distanceM) + " m, ~" + Math.round(al.respondDuration) + " s).", receiver);
        send(receiver, victim, PacketType.ACK, 0.8, () ->
                event(Kind.INFO, "📨 " + victim.shortName() + ": help is already on the way ("
                        + receiver.shortName() + ").", victim), null);
    }

    /** Запит «Усе гаразд?»: якщо сусід здоровий, він відповість; якщо ні, повідомимо про тишу. */
    public void ping(CrewMemberState from, CrewMemberState to) {
        event(Kind.INFO, "💬 " + from.shortName() + " → " + to.shortName() + ": “All good?”", from);
        boolean[] got = {false};
        send(from, to, PacketType.PING, 0.7, () -> {
            if (to.isHealthy()) {
                schedule(clock + 1.2, () -> send(to, from, PacketType.PONG, 0.7, () -> {
                    got[0] = true;
                    event(Kind.OK, "✅ " + to.shortName() + ": “All good” (pulse "
                            + Math.round(to.getHr()) + ", HRV " + Math.round(to.getHrv()) + ").", to);
                }, null));
            }
        }, null);
        schedule(clock + 6, () -> {
            if (!got[0]) {
                event(Kind.WARN, "⏳ " + to.shortName() + " is not responding to the request. Check the neighboring node.", to);
            }
        });
    }

    // ============ ГОЛОВНИЙ КРОК ============
    public void advance(double dt) {
        accum += Math.min(Math.max(dt, 0), 0.5);
        while (accum >= STEP) {
            accum -= STEP;
            clock += STEP;
            stepOnce();
        }
    }

    private void stepOnce() {
        for (CrewMemberState m : members) {
            m.step(STEP, clock, confirmWindow);
        }

        // heartbeat і втрата зв'язку
        if (clock >= nextHeartbeat) {
            nextHeartbeat = clock + HEARTBEAT_EVERY;
            for (CrewMemberState a : members) {
                if (!a.linkUp) continue;
                a.lastHeartbeat = clock;
                for (CrewMemberState b : members) {
                    if (a != b) send(a, b, PacketType.HEARTBEAT, 0.9, null, null);
                }
            }
        }
        for (CrewMemberState m : members) {
            boolean off = clock - m.lastHeartbeat > OFFLINE_AFTER && clock > OFFLINE_AFTER;
            if (off != m.offline) {
                m.offline = off;
                if (off) {
                    event(Kind.WARN, "📡 Signal lost from " + m.shortName() + " (" + (int) OFFLINE_AFTER
                            + " s without heartbeat). Last data: pulse " + Math.round(m.getHr())
                            + ", HRV " + Math.round(m.getHrv()) + ".", m);
                } else {
                    event(Kind.OK, "📡 Connection with " + m.shortName() + " restored.", m);
                }
            }
        }

        // стан таймера підтвердження і SOS
        for (CrewMemberState m : members) {
            boolean running = m.isTimerRunning();
            if (running && !m.timerWasRunning) {
                event(Kind.WARN, "⚠️ " + m.shortName() + ": " + m.getAnomalyText() + ". Waiting for “All good” for "
                        + Math.round(confirmWindow) + " s.", m);
            } else if (!running && m.timerWasRunning && !m.isSosActive()) {
                if (!m.consumeTimerEndedByAck()) {
                    event(Kind.OK, "💚 " + m.shortName() + ": vitals recovered on their own, alarm cleared.", m);
                }
            }
            m.timerWasRunning = running;

            boolean sos = m.isSosActive();
            if (sos && !m.sosBroadcasted) {
                m.sosBroadcasted = true;
                m.lastBeacon = clock;
                String why = m.isAutoSos()
                        ? "AUTO-SOS: no “All good” confirmation received within " + Math.round(confirmWindow) + " s"
                        : "manual SOS signal";
                event(Kind.SOS, "🚨 " + m.shortName() + ": " + why + ". Broadcasting crypto packet to neighbors.", m);
                broadcastEmergency(m);
            } else if (sos && clock - m.lastBeacon >= BEACON_EVERY) {
                m.lastBeacon = clock;
                broadcastEmergency(m);
            } else if (!sos && m.sosBroadcasted) {
                m.sosBroadcasted = false;
                packets.removeIf(p -> p.from == m && p.type == PacketType.EMERGENCY);
                for (CrewMemberState o : members) o.alerts.remove(m.getId());
                event(Kind.OK, "🔕 Stand down: beacon of " + m.shortName() + " switched off on all devices.", m);
            }
        }

        // страховка: сигнал, чиє джерело вже не в SOS, не має лишатися на пристроях
        for (CrewMemberState m : members) {
            m.alerts.entrySet().removeIf(en -> {
                CrewMemberState src = byId(en.getKey());
                return src == null || !src.isSosActive();
            });
        }

        // допомога прибула
        for (CrewMemberState m : members) {
            for (CrewMemberState.IncomingAlert al : m.alerts.values()) {
                if (al.responding && !al.arrived && clock - al.respondStart >= al.respondDuration) {
                    al.arrived = true;
                    // Допомога на місці: на ВСІХ пристроях вимикаємо звук і вібрацію цього SOS
                    for (CrewMemberState o : members) {
                        CrewMemberState.IncomingAlert x = o.alerts.get(al.senderId);
                        if (x != null) {
                            x.handled = true;
                            x.muted = true;
                        }
                    }
                    CrewMemberState victim = byId(al.senderId);
                    if (victim != null) {
                        victim.setAnomalyActive(false); // у демо після допомоги показники повертаються до норми
                        event(Kind.OK, "🤝 " + m.shortName() + " is next to " + victim.shortName()
                                + ". Help is being given, vitals are stabilizing.", victim);
                    }
                }
            }
        }

        // Авто-SOS знімається сам: допомога на місці, а показники вже стабільні 8 секунд
        for (CrewMemberState v : members) {
            if (v.isSosActive() && v.isAutoSos() && isHandled(v)) {
                if (!v.isVitalsCritical()) {
                    if (v.stableSince < 0) {
                        v.stableSince = clock;
                    } else if (clock - v.stableSince >= 8) {
                        v.stableSince = -1;
                        v.clearSos(clock);
                        event(Kind.OK, "🏁 " + v.shortName() + ": vitals stable, SOS cleared automatically.", v);
                    }
                } else {
                    v.stableSince = -1;
                }
            } else {
                v.stableSince = -1;
            }
        }

        // заплановані дії
        for (Iterator<Scheduled> it = scheduled.iterator(); it.hasNext(); ) {
            Scheduled s = it.next();
            if (clock >= s.at()) {
                it.remove();
                s.action().run();
            }
        }

        // пакети: доставка
        for (Iterator<Packet> it = packets.iterator(); it.hasNext(); ) {
            Packet p = it.next();
            if (p.progress(clock) >= 1.0) {
                it.remove();
                boolean ok = p.from.linkUp && p.to.linkUp;
                if (ok) {
                    if (p.onArrive != null) p.onArrive.run();
                } else if (p.onDrop != null) {
                    p.onDrop.run();
                }
            }
        }
    }

    // ============ АВАРІЙНИЙ ПАКЕТ ============
    private void broadcastEmergency(CrewMemberState src) {
        if (!src.linkUp) {
            if (src.lastBeacon == clock) {
                event(Kind.WARN, "⚠️ SOS packet from " + src.shortName()
                        + " did not go on air: no radio link. Will retry as soon as a signal appears.", src);
            }
            return;
        }
        String reason = src.isAutoSos() ? "AUTO" : "MANUAL";
        String plain = String.join("|", "SOS", src.getId(), src.getName(),
                String.valueOf(Math.round(src.getHr())), String.valueOf(Math.round(src.getHrv())),
                String.valueOf(Math.round(src.getHydration())), reason);
        byte[] sealed = seal(plain);
        for (CrewMemberState dst : members) {
            if (dst == src) continue;
            send(src, dst, PacketType.EMERGENCY, 1.0, () -> receiveEmergency(src, dst, sealed), null);
        }
    }

    private void receiveEmergency(CrewMemberState src, CrewMemberState dst, byte[] sealed) {
        if (!src.isSosActive()) return;   // пакет запізнився: SOS уже знято
        String plain = open(sealed);
        if (plain == null) {
            event(Kind.WARN, "🔐 " + dst.shortName() + ": packet rejected (failed AES-GCM check).", dst);
            return;
        }
        String[] f = plain.split("\\|");
        String summary = "pulse " + f[3] + " bpm · HRV " + f[4] + " ms · hydration " + f[5] + "%";
        String pid = packetId(sealed);

        CrewMemberState.IncomingAlert al = dst.alerts.get(src.getId());
        boolean fresh = al == null;
        if (fresh) {
            double d = distanceM(src, dst);
            int rssi = (int) Math.round(-40 - 20 * Math.log10(Math.max(1, d)) + (rng.nextDouble() * 4 - 2));
            al = new CrewMemberState.IncomingAlert(src.getId(), src.getName(), clock, d, rssi);
            dst.alerts.put(src.getId(), al);
        }
        al.packetId = pid;
        al.summary = summary;
        if (fresh) {
            event(Kind.RX, "📳🔊 " + dst.shortName() + ": received SOS from " + src.shortName()
                    + " · AES-256-GCM ✔ · packet #" + pid + " · ≈" + Math.round(al.distanceM) + " m", dst);
        }
    }

    // ============ КРИПТОГРАФІЯ ============
    private byte[] seal(String plain) {
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, crewKey, new GCMParameterSpec(128, iv));
            c.updateAAD("AEGIS-MESH-SOS".getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt the packet", e);
        }
    }

    private String open(byte[] sealed) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, crewKey, new GCMParameterSpec(128, sealed, 0, 12));
            c.updateAAD("AEGIS-MESH-SOS".getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(sealed, 12, sealed.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String packetId(byte[] sealed) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(sealed);
            return String.format("%02X%02X%02X%02X", h[0], h[1], h[2], h[3]);
        } catch (Exception e) {
            return "00000000";
        }
    }

    // ============ ДОПОМІЖНЕ ============
    private void send(CrewMemberState from, CrewMemberState to, PacketType type, double dur,
                      Runnable onArrive, Runnable onDrop) {
        packets.add(new Packet(from, to, type, clock, dur, onArrive, onDrop));
    }

    private void schedule(double at, Runnable r) {
        scheduled.add(new Scheduled(at, r));
    }

    private void event(Kind kind, String text, CrewMemberState subject) {
        MeshEvent e = new MeshEvent(kind, text, subject);
        for (Consumer<MeshEvent> l : listeners) l.accept(e);
    }

    private boolean isHandled(CrewMemberState victim) {
        for (CrewMemberState o : members) {
            CrewMemberState.IncomingAlert al = o.alerts.get(victim.getId());
            if (al != null && al.handled) return true;
        }
        return false;
    }

    private CrewMemberState byId(String id) {
        for (CrewMemberState m : members) if (m.getId().equals(id)) return m;
        return null;
    }

    public double distanceM(CrewMemberState a, CrewMemberState b) {
        double dx = (a.getNx() - b.getNx()) * SHIP_W_M;
        double dy = (a.getNy() - b.getNy()) * SHIP_H_M;
        return Math.hypot(dx, dy);
    }
}
