package nasa.spaceapps.aegis;

import javafx.scene.paint.Color;

public class CrewMemberState {

    public enum CrewStatus {
        NOMINAL("Усе гаразд", Color.web("#3fb950")),
        WARNING("Потрібна увага", Color.web("#d29922")),
        CRITICAL("Критично / SOS", Color.web("#f85149")),
        OFFLINE("⚠️ Немає сигналу (Mesh Lost)", Color.web("#8b949e"));

        public final String label;
        public final Color color;

        CrewStatus(String label, Color color) {
            this.label = label;
            this.color = color;
        }
    }

    private final String id;
    private final String name;
    private final String role;
    private double hr;
    private double hrv;
    private double hydration;
    private boolean manualSos;
    private long lastPacketClockSec;

    // Логіка Mesh Buddy-Guard таймера (60 секунд на підтвердження)
    private long anomalyStartClockSec = -1;
    private boolean acknowledged = false;

    public CrewMemberState(String id, String name, String role, double hr, double hrv, double hydration) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.hr = hr;
        this.hrv = hrv;
        this.hydration = hydration;
        this.manualSos = false;
        this.lastPacketClockSec = 0;
    }

    public CrewStatus evaluateStatus(long currentClockSec) {
        if (currentClockSec - lastPacketClockSec > 30 && lastPacketClockSec > 0) {
            return CrewStatus.OFFLINE;
        }

        boolean isBadCondition = hr > 120 || hr < 45 || hrv < 30 || hydration < 75;

        if (isBadCondition || manualSos) {
            if (anomalyStartClockSec == -1) {
                anomalyStartClockSec = currentClockSec;
                acknowledged = false;
            } else if (!acknowledged && (currentClockSec - anomalyStartClockSec >= 60)) {
                // Час вийшов, а підтвердження "Усе гаразд" не було -> Автоматичний Mesh SOS!
                manualSos = true;
            }
            return CrewStatus.CRITICAL;
        }

        boolean isWarningCondition = hr > 100 || hrv < 45 || hydration < 85;
        if (isWarningCondition) {
            if (anomalyStartClockSec == -1) {
                anomalyStartClockSec = currentClockSec;
                acknowledged = false;
            } else if (!acknowledged && (currentClockSec - anomalyStartClockSec >= 60)) {
                manualSos = true;
                return CrewStatus.CRITICAL;
            }
            return CrewStatus.WARNING;
        }

        // Показники в нормі — скидаємо таймер тривоги
        anomalyStartClockSec = -1;
        acknowledged = false;
        if (!manualSos) {
            return CrewStatus.NOMINAL;
        }
        return CrewStatus.CRITICAL;
    }

    // Залишок секунд до авто-SOS
    public long getRemainingConfirmationSec(long currentClockSec) {
        if (anomalyStartClockSec == -1 || acknowledged) return -1;
        long elapsed = currentClockSec - anomalyStartClockSec;
        return Math.max(0, 60 - elapsed);
    }

    // Космонавт натиснув "Усе гаразд" і підтвердив стан
    public void acknowledgeAnomaly() {
        this.acknowledged = true;
        this.manualSos = false;
        this.anomalyStartClockSec = -1;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getRole() { return role; }
    public double getHr() { return hr; }
    public double getHrv() { return hrv; }
    public double getHydration() { return hydration; }
    public boolean isManualSos() { return manualSos; }

    public void setManualSos(boolean manualSos) {
        this.manualSos = manualSos;
        if (manualSos) {
            this.acknowledged = false;
        }
    }

    public void updateTelemetry(double hr, double hrv, double hydration, long currentClockSec) {
        this.hr = hr;
        this.hrv = hrv;
        this.hydration = hydration;
        this.lastPacketClockSec = currentClockSec;
    }
}