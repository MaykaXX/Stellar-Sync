<div align="center">

# 🛰️ AEGIS-BIO // OS-26
### Deep-Space Autonomous Clinical Decision Support System (CDSS)
**NASA Space Apps Challenge 2026 | Human Health & Exploration Challenge**

[![Java](https://img.shields.io/badge/Java-21_LTS-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21-0284C7?style=for-the-badge)](https://openjfx.io/)
[![Maven](https://img.shields.io/badge/Maven-3.9-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![NASA OSDR](https://img.shields.io/badge/NASA-OSDR-0B3D91?style=for-the-badge)](https://osdr.nasa.gov/)
[![License](https://img.shields.io/badge/License-MIT-10B981?style=for-the-badge)](LICENSE)

*“When the round-trip signal delay to Earth exceeds 14 minutes, a flight surgeon cannot triage an acute crisis in real time. The spacecraft's onboard system must become the doctor.”*

---

[System Overview](#-system-overview) •
[NASA RIDGE Hazard Model](#-nasa-ridge-hazard-model) •
[Core Features](#-core-features) •
[Architecture & Design](#-architecture--design) •
[Quick Start](#-quick-start) •
[NASA Open Data Pipeline](#-nasa-open-data-pipeline) •
[Roadmap](#-roadmap)

---

</div>

## 🌌 System Overview

During long-duration missions under the **Artemis** program and future crewed transits to **Mars**, astronauts face an unprecedented operational constraint: **deep-space communications latency**. At distances reaching hundreds of millions of kilometers, real-time telemedicine with Mission Control (Houston) is physically impossible.

**AEGIS-BIO** is an offline, autonomous desktop clinical terminal engineered in **Java 21 (LTS)** and **JavaFX**. Running entirely on edge hardware aboard the spacecraft, the system continuously aggregates vital biometric telemetry, assesses microgravity physiological strain against individual baseline profiles, maps syndromes onto an interactive holographic body scanner, and triggers deterministic clinical action protocols (**Actionable CDSS Protocols**) without external network dependencies.

```
       [ WEARABLE SENSORS ]           [ RADIATION DOSIMETERS ]         [ NASA OSDR / CSV ]
                │                                │                              │
                └────────────────────────────────┼──────────────────────────────┘
                                                 ▼
                        ┌────────────────────────────────────────────────┐
                        │        AEGIS-BIO AUTONOMOUS CORE (JAVA)        │
                        │  • Offline Deterministic CDSS Triage Engine    │
                        │  • Microgravity Vector Modeling (SANS / ICP)   │
                        │  • Communications Latency Buffer & Sync Queue  │
                        └────────────────────────────────────────────────┘
                                                 │
                        ┌────────────────────────┴───────────────────────┐
                        ▼                                                ▼
        [ HOLOGRAPHIC BIOMETRIC HUD ]                   [ ACTIONABLE CLINICAL CDSS ]
      • Optic nerve sheath edema (SANS)               • Lower Body Negative Pressure (LBNP)
      • Spinal column elongation (+4.2 cm)            • Radiation storm shelter evacuation
      • Bone mineral calcium excretion                • Circadian lighting shifts (480nm)
```

---

## 🛡️ NASA RIDGE Hazard Model

AEGIS-BIO is architected directly around the 5 fundamental hazards defined by the **NASA Human Research Program (HRP)**:

| NASA Hazard | Physiological Impact | AEGIS-BIO Mitigation & Logic |
|---|---|---|
| **R** — Space Radiation | DNA damage, acute solar particle events (SPE), elevated career cancer risk | Tracks cumulative absorbed ionizing dose (mSv). An acute threshold breach (>100 mSv) automatically issues an emergency evacuation order to the reinforced storm shelter (water-wall bunker). |
| **I** — Isolation & Confinement | Circadian desynchrony, sleep deprivation, autonomic exhaustion | Evaluates heart rate variability (HRV) trends alongside sleep architecture. Detects neurovegetative breakdown and prescribes 480nm spectrum lighting protocols. |
| **D** — Distance from Earth | Round-trip signal delay ranging from 1.3 seconds (Moon) up to 22 minutes (Mars) | Operates 100% offline. Clinical rules, symptom evaluation, and pharmacopeia dosages run strictly within local Java runtime memory without external cloud endpoints. |
| **G** — Gravity Fields | Cephalic fluid shift, cardiovascular deconditioning, bone mineral loss | Vector HUD highlights bone mineral density attrition in lower limbs and tracks spinal elongation (+4.2 cm under zero-g spinal decompression). |
| **E** — Hostile / Closed Env | Spaceflight-Associated Neuro-ocular Syndrome (**SANS**), intracranial hypertension | Monitors optic nerve sheath diameter and intracranial pressure (ICP). Issues prescriptive protocols including targeted Lower Body Negative Pressure (LBNP) sessions and corrective hyperopic lenses. |

---

## ⚡ Core Features

* **🩺 Real-Time Biometric Stream:** Live multi-channel tracking for 8 mission-critical telemetry parameters: Heart Rate (bpm), HRV (ms), Systolic Blood Pressure (mmHg), Blood Glucose (mg/dL), Core Body Temp (°C), SpO2 (%), Optic Nerve Sheath Pressure / SANS (mmHg), and Cumulative Radiation Dose (mSv).
* **🧬 Holographic Vector Anatomy Scanner:** Custom rendering engine built on `JavaFX Canvas` displaying real-time anatomical overlays with dynamic pulse animations at active pathophysiological sites.
* **🧠 Autonomous CDSS Engine:** Rule-based triage system generating tiered clinical advisories (`NOMINAL`, `MEDIUM`, `HIGH`, `CRITICAL`) with specific pharmacological dosages and physical countermeasure orders.
* **🏋️ NASA Countermeasure & Habit Hub:** Gamified tracking of the mandatory daily 2.5-hour countermeasure regimen (Advanced Resistive Exercise Device [ARED], T2 Treadmill, CEVIS Cycle Ergometer) paired with electrolyte-balanced hydration logging.
* **🛰️ Mission Latency Engine:** Instant simulation toggling between **LEO (ISS)**, **Lunar Gateway**, and **Mars Transit**, adapting operational interfaces based on physical signal propagation delays.

---

## 🏛️ Architecture & Design

The project follows clean, decoupled architectural patterns, avoiding heavy runtime frameworks in favor of a lean, performant standard Java stack:

```
src/main/java/nasa/spaceapps/aegis/
│
├── AegisBioApp.java               # Main entry point, JavaFX application lifecycle, UI layout
│
├── [Domain Layer]                 # Immutable domain records & types
│   ├── MetricType                 # Physiological parameters, normal ranges, and metric units
│   ├── MissionLocation            # Mission profiles and communication latency models
│   ├── TelemetryPoint             # Timestamped immutable biometric sensor readings
│   └── ClinicalProtocol           # CDSS diagnostic directives and action cards
│
├── [Engine & Triage]              # Autonomous decision logic
│   ├── AutonomousTriageEngine     # Microgravity pathophysiology heuristic engine
│   └── CanvasAnatomyRenderer      # Vector geometry & pulse animation HUD pipeline
│
└── [Ingestion Pipeline]           # NASA Open Data adapters
    └── NasaCsvArchiveAdapter      # File parser for NASA OSDR / LSDA datasets
```

### Visual Interface Standards (NASA Human Integration Design)
* **Background Canvas:** Deep Space Obsidian (`#080C14` / `#0E1726`) to preserve dark-adaptation in low-lux cabin environments.
* **Nominal Status:** Bioluminescent Emerald (`#10B981`) denoting stable homeostatic balance.
* **Warning Indicator:** High-Orbit Amber (`#F59E0B`) signaling physiological trending toward degradation.
* **Critical Alarm:** Solar Flare Crimson (`#EF4444`) demanding immediate crew intervention.

---

## 🚀 Quick Start

### Prerequisites
* **Java Development Kit (JDK):** Version 21 LTS or newer
* **Apache Maven:** Version 3.9+
* **IDE:** IntelliJ IDEA, VS Code, or Eclipse

### Installation & Launch

1. Clone the repository:
   ```bash
   git clone [https://github.com/MaykaXX/aegis-bio-space-health.git](https://github.com/MaykaXX/aegis-bio-space-health.git)
   cd aegis-bio-space-health
   ```

2. Compile via Maven:
   ```bash
   mvn clean compile
   ```

3. Run the desktop application:
   ```bash
   mvn javafx:run
   ```

---

## 📊 NASA Open Data Pipeline

AEGIS-BIO features a built-in **NASA Open Data Ingestion Pipeline** designed to ingest research telemetry from the **NASA Open Science Data Repository (OSDR)** and **Life Sciences Data Archive (LSDA)**.

Click the **"Імпорт NASA Open Data (CSV)"** button in the top navigation bar to ingest flight records.

### Sample CSV Specification (`nasa_telemetry_sample.csv`):
```csv
METRIC_CODE,VALUE,UNIT,SOURCE_MISSION
ICP_OPTIC,22.4,mmHg,Artemis-II-DeepSpace-Sim
RADIATION_DOSE,118.5,mSv,SPE-Coronal-Mass-Ejection
HEART_RATE,95.0,bpm,CEVIS-Cycle-Test
HRV,32.0,ms,Cardiovascular-Stress-Study
BLOOD_GLUCOSE,102.0,mg/dL,Metabolic-Log-01
SPO2,98.1,%,ECLSS-Cabin-Telemetry
```

---

## 🔮 Roadmap

- [ ] **Edge AI Vision Engine:** On-device computer vision models for automated optical coherence tomography (OCT) retinal scan analysis.
- [ ] **SQLite Flight Blackbox:** Encrypted local persistent storage engine for 1000+ day mission telemetry archiving.
- [ ] **Multi-Crew Dashboard:** Concurrent multi-astronaut vitals telemetry matrix with crew role separation.

---

<div align="center">

**Developed for the NASA International Space Apps Challenge 2026**  
*Safeguarding Human Health on the Journey to the Moon and Mars*

⭐ *Star this repository if you find it helpful for deep-space exploration software design!*

</div>
