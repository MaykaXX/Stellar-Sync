package nasa.spaceapps.aegis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Стан одного члена екіпажу та логіка Buddy-Guard.
 * <p>
 * Клас не залежить від JavaFX: усе, що малює інтерфейс, лежить в CrewCardComponent.
 * Час передається ззовні (секунди з запуску панелі), тому таймер підтвердження
 * завжди йде у РЕАЛЬНИХ секундах і не залежить від швидкості часу симуляції.
 */
public class CrewMemberState {

    public enum CrewStatus {
        NOMINAL("Усе гаразд", "#10B981"),
        WARNING("Потрібна увага", "#F59E0B"),
        CRITICAL("Критично", "#EF4444"),
        OFFLINE("Немає сигналу", "#94A3B8");

        public final String label;
        public final String hex;

        CrewStatus(String label, String hex) {
            this.label = label;
            this.hex = hex;
        }
    }

    /** Аварійний пакет, який цей пристрій отримав від сусіда. */
    public static final class IncomingAlert {
        public final String senderId;
        public final String senderName;
        public final double receivedAt;
        public final double distanceM;
        public final int rssi;
        public String packetId = "";
        public String summary = "";
        public boolean muted;
        public boolean responding;
        public boolean arrived;
        public double respondStart;
        public double respondDuration;

        IncomingAlert(String senderId, String senderName, double receivedAt, double distanceM, int rssi) {
            this.senderId = senderId;
            this.senderName = senderName;
            this.receivedAt = receivedAt;
            this.distanceM = distanceM;
            this.rssi = rssi;
        }

        /** 0..1: скільки шляху до потерпілого вже пройдено. */
        public double progress(double now) {
            if (!responding) return 0;
            if (arrived) return 1;
            return Math.max(0, Math.min(1, (now - respondStart) / Math.max(0.1, respondDuration)));
        }
    }

    // ---------- ідентифікація ----------
    private final String id;
    private final String name;
    private final String role;
    /** true — це пристрій користувача: пульс, HRV і гідратація приходять із основної програми. */
    private final boolean local;
    private String compartment = "—";
    private double nx = 0.5, ny = 0.5;

    // ---------- показники ----------
    private final double homeHr, homeHrv, homeHyd;
    private double sHr, sHrv, sHyd;          // згладжені
    private double hr, hrv, hydration;       // що видно на екрані
    private double extHr, extHrv, extHyd;    // дані від основної програми (лише для local)
    private double jitterHr, jitterHrv;
    private double hrvSlow;
    private double simTime;
    private final double phase;
    private final Random rng;

    // ---------- демо-збій ----------
    private boolean anomalyActive;
    private double anomHr = 38, anomHrv = 12;

    // ---------- Buddy-Guard ----------
    private boolean manualSos;
    private boolean autoSos;
    private double anomalyStart = -1;
    private double confirmWindow = 60;
    private double graceUntil = -1;
    private boolean timerEndedByAck;
    private CrewStatus status = CrewStatus.NOMINAL;
    private String anomalyText = "";

    // ---------- mesh (керує CrewMeshNetwork) ----------
    boolean linkUp = true;
    boolean offline;
    double lastHeartbeat;
    boolean sosBroadcasted;
    double lastBeacon;
    boolean timerWasRunning;
    final Map<String, IncomingAlert> alerts = new LinkedHashMap<>();

    public CrewMemberState(String id, String name, String role,
                           double hr, double hrv, double hydration, boolean local) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.local = local;
        this.homeHr = hr;
        this.homeHrv = hrv;
        this.homeHyd = hydration;
        this.sHr = this.hr = this.extHr = hr;
        this.sHrv = this.hrv = this.extHrv = hrv;
        this.sHyd = this.hydration = this.extHyd = hydration;
        this.hrvSlow = hrv;
        this.rng = new Random(id.hashCode() * 31L + 7);
        this.phase = rng.nextDouble() * Math.PI * 2;
    }

    // ============ КРОК СИМУЛЯЦІЇ ============
    void step(double dt, double now, double confirmWindowSec) {
        this.confirmWindow = confirmWindowSec;
        simTime += dt;

        double tHr, tHrv, tHyd;
        if (local) {
            tHr = extHr;
            tHrv = extHrv;
            tHyd = extHyd;
        } else {
            tHr = homeHr + 5.0 * Math.sin(simTime * 0.13 + phase) + 2.5 * Math.sin(simTime * 0.41 + phase * 2);
            tHrv = homeHrv - 0.9 * (tHr - homeHr) + 3.0 * Math.sin(simTime * 0.09 + phase);
            tHyd = homeHyd + 1.5 * Math.sin(simTime * 0.03 + phase);
        }
        double rate = 0.6;
        if (anomalyActive) {
            tHr = anomHr;
            tHrv = anomHrv;
            rate = 0.9;
        }
        double k = 1 - Math.exp(-rate * dt);
        sHr += (tHr - sHr) * k;
        sHrv += (tHrv - sHrv) * k;
        sHyd += (tHyd - sHyd) * (1 - Math.exp(-0.5 * dt));

        // «живий» мікрошум, щоб цифри не стояли на місці
        jitterHr = jitterHr * 0.92 + rng.nextGaussian() * 0.55;
        jitterHrv = jitterHrv * 0.92 + rng.nextGaussian() * 0.8;
        hr = clamp(sHr + jitterHr, 25, 200);
        hrv = clamp(sHrv + jitterHrv, 5, 150);
        hydration = clamp(sHyd, 0, 100);

        boolean sudden = hrvSlow > 35 && hrv < hrvSlow * 0.55;
        hrvSlow += (hrv - hrvSlow) * (1 - Math.exp(-dt / 25.0));

        boolean critical = hr > 125 || hr < 45 || hrv < 25 || hydration < 70 || sudden;
        boolean warning = hr > 105 || hr < 55 || hrv < 40 || hydration < 82;
        boolean inGrace = now < graceUntil;

        anomalyText = describe(sudden);

        // Таймер підтвердження «Усе гаразд»
        if (!isSosActive()) {
            if (critical && !inGrace) {
                if (anomalyStart < 0) {
                    anomalyStart = now;
                } else if (now - anomalyStart >= confirmWindow) {
                    autoSos = true;
                    anomalyStart = -1;
                }
            } else {
                anomalyStart = -1;
            }
        }

        if (offline) status = CrewStatus.OFFLINE;
        else if (isSosActive() || (critical && !inGrace)) status = CrewStatus.CRITICAL;
        else if (warning || critical) status = CrewStatus.WARNING;
        else status = CrewStatus.NOMINAL;
    }

    private String describe(boolean sudden) {
        StringBuilder sb = new StringBuilder();
        if (hr > 125 || hr < 45) sb.append("пульс ").append((int) Math.round(hr)).append(" уд/хв");
        if (hrv < 25 || sudden) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("HRV ").append((int) Math.round(hrv)).append(" мс");
        }
        if (hydration < 70) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("гідратація ").append((int) Math.round(hydration)).append("%");
        }
        return sb.length() == 0 ? "показники поза нормою" : sb.toString();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ============ ДІЇ ============
    /** Член екіпажу натиснув «Усе гаразд». Повертає true, якщо щось справді скасовано. */
    boolean acknowledge(double now) {
        boolean something = anomalyStart >= 0 || isSosActive();
        timerEndedByAck = anomalyStart >= 0;
        anomalyStart = -1;
        autoSos = false;
        manualSos = false;
        anomalyActive = false;
        graceUntil = now + 30;
        return something;
    }

    /** Відбій SOS: так само, як підтвердження, але без позначки «сам підтвердив». */
    void clearSos(double now) {
        anomalyStart = -1;
        autoSos = false;
        manualSos = false;
        anomalyActive = false;
        graceUntil = now + 30;
    }

    void raiseManualSos() {
        manualSos = true;
    }

    boolean consumeTimerEndedByAck() {
        boolean v = timerEndedByAck;
        timerEndedByAck = false;
        return v;
    }

    // ============ ДОСТУП ============
    public boolean isSosActive() { return manualSos || autoSos; }
    public boolean isAutoSos() { return autoSos; }
    public boolean isManualSos() { return manualSos; }
    public boolean isTimerRunning() { return anomalyStart >= 0 && !isSosActive(); }
    public double getRemainingConfirmationSec(double now) {
        if (anomalyStart < 0) return -1;
        return Math.max(0, confirmWindow - (now - anomalyStart));
    }
    public double getConfirmWindow() { return confirmWindow; }
    public CrewStatus status() { return status; }
    public String getAnomalyText() { return anomalyText; }

    public boolean isLinkUp() { return linkUp; }
    public boolean isOffline() { return offline; }
    public boolean isAnomalyActive() { return anomalyActive; }
    public void setAnomalyActive(boolean on) { this.anomalyActive = on; }
    public void setDemoAnomaly(double hr, double hrv) { this.anomHr = hr; this.anomHrv = hrv; }
    public boolean isHealthy() {
        return !isSosActive() && !offline && status != CrewStatus.CRITICAL;
    }

    public boolean isLocal() { return local; }
    public void setExternalVitals(double hr, double hrv, double hyd) {
        this.extHr = hr;
        this.extHrv = hrv;
        this.extHyd = hyd;
    }

    public void setPlacement(double nx, double ny, String compartment) {
        this.nx = nx;
        this.ny = ny;
        this.compartment = compartment;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getRole() { return role; }
    public String shortName() {
        int i = name.indexOf(" (");
        return i > 0 ? name.substring(0, i) : name;
    }
    public String getCompartment() { return compartment; }
    public double getNx() { return nx; }
    public double getNy() { return ny; }
    public double getHr() { return hr; }
    public double getHrv() { return hrv; }
    public double getHydration() { return hydration; }
    public Map<String, IncomingAlert> getAlerts() { return alerts; }
}
