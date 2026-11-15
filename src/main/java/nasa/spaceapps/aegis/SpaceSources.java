package nasa.spaceapps.aegis;

import java.util.Arrays;
import java.util.List;

/**
 * Наукова основа застосунку: небезпеки й ризики польоту (NASA Human Research Program),
 * споріднені дослідження та факти Канадського космічного агентства (CSA).
 * <p>
 * Усі пояснення викладені своїми словами, повні тексти за посиланнями. Посилання взято зі
 * сторінок NASA HRP і CSA, перелічених у README (розділ Sources). Цифри порогів у самому
 * застосунку демонстраційні і з цих сторінок не беруться.
 * <p>
 * Клас не залежить від JavaFX, тому легко перевіряється тестом.
 */
public final class SpaceSources {

    private SpaceSources() {
    }

    public record Link(String title, String url) {
    }

    /** Небезпека польоту за класифікацією NASA HRP. */
    public record Hazard(String letter, String name, String meaning, String inApp, Link source) {
    }

    /**
     * Ризик для здоров'я. metric: назви показників застосунку через кому (або null),
     * inApp: де це видно в застосунку, implemented: false, якщо поки лише напрям розвитку.
     */
    public record Risk(String id, String group, String title, String summary, String metric,
                       String inApp, boolean implemented, Link source) {
    }

    /** Споріднене дослідження NASA: що вивчає і чим схоже на нашу функцію. */
    public record Study(String name, String what, String relation, Link source) {
    }

    // ---------- групи ризиків (так їх групує сторінка NASA «Risks of Human Spaceflight») ----------
    public static final String GROUP_PHYSIO = "Physiology";
    public static final String GROUP_BEHAVIOR = "Behavior and human factors";
    public static final String GROUP_RADIATION = "Radiation";

    // ---------- посилання ----------
    public static final Link NASA_HAZARDS = new Link("NASA HRP: five hazards of spaceflight", "https://www.nasa.gov/hrp/hazards/");
    public static final Link NASA_RISKS = new Link("NASA HRP: spaceflight risks", "https://www.nasa.gov/hrp/risks/");
    public static final Link NASA_RESEARCH = new Link("NASA HRP: ongoing research", "https://www.nasa.gov/hrp/ongoing-hrp-research/");
    public static final Link NASA_ROADMAP = new Link("NASA Human Research Roadmap", "https://humanresearchroadmap.nasa.gov/");
    public static final Link CSA_RADIATION = new Link("CSA: radiation monitoring for astronaut health",
            "https://www.asc-csa.gc.ca/eng/news/articles/2026/2026-02-26-monitoring-radiation-astronaut-health.asp");
    public static final Link CSA_WHY_SCIENCE = new Link("CSA: why science is done in space",
            "https://www.asc-csa.gc.ca/eng/iss/science/why-do-we-conduct-science-experiments-in-space.asp");

    private static Link nasa(String title, String url) {
        return new Link(title, url);
    }

    // ---------- п'ять небезпек ----------
    public static final List<Hazard> HAZARDS = List.of(
            new Hazard("R", "Space radiation",
                    "You can't see it, but it is one of the most dangerous parts of the flight.",
                    "Accumulated dose, DNA helix, shelter and shields in blackout mode.",
                    nasa("NASA: radiation", "https://www.nasa.gov/hrp/hazard-space-radiation/")),
            new Hazard("I", "Isolation and confinement",
                    "A group of people lives far from Earth in a cramped space for a long time, and this changes behavior and well-being.",
                    "“Crew (Mesh)” screen: crew members watch over each other. Sleep and HRV as signs of exhaustion.",
                    nasa("NASA: isolation", "https://www.nasa.gov/hrp/hazard-isolation-and-confinement/")),
            new Hazard("D", "Distance from Earth",
                    "The farther from home, the harder it is to get instructions, supplies and medical help. A flight to Mars takes about three years.",
                    "“Mission” screen: communication delay, blackout mode and the autopilot doctor.",
                    nasa("NASA: distance", "https://www.nasa.gov/hrp/hazard-distance-from-earth/")),
            new Hazard("G", "Gravity and the lack of it",
                    "Muscles, bones, the inner ear and organs have to adapt to new gravity. On Mars it is about 3/8 of Earth's.",
                    "Bone strength, heart and blood pressure, pressure on the optic nerve; workouts and LBNP sessions.",
                    nasa("NASA: gravity", "https://www.nasa.gov/hrp/hazard-gravity-fields/")),
            new Hazard("E", "Closed and hostile environment",
                    "Inside the ship you have to maintain a safe ecosystem: temperature, pressure, light, noise, microbes and immune response.",
                    "Blood oxygen (SpO₂) and water. Microbes and immunity: planned.",
                    nasa("NASA: environment", "https://www.nasa.gov/hrp/hazard-hostile-closed-environments/")));

    // ---------- ризики для здоров'я ----------
    public static final List<Risk> RISKS = List.of(
            new Risk("SANS", GROUP_PHYSIO, "Eye and brain changes (SANS)",
                    "In weightlessness the structure of the eyes and brain can change. Scientists study the long-term effects and ways to prevent them.",
                    "ICP_OPTIC", "The “Optic nerve pressure” metric, LBNP session, eye highlight on the hologram.", true,
                    nasa("NASA: SANS", "https://www.nasa.gov/reference/risk-of-spaceflight-associated-neuro-ocular-syndrome-sans/")),
            new Risk("BONE", GROUP_PHYSIO, "Bone loss",
                    "Without gravity bones weaken and become more brittle, so fractures are more likely. Nutrition, exercise and medication help.",
                    "BONE_DENSITY", "The “Bone strength” metric, strength training.", true,
                    nasa("NASA: bones", "https://www.nasa.gov/reference/risk-of-spaceflight-induced-bone-changes/")),
            new Risk("CARDIO", GROUP_PHYSIO, "Heart and vascular changes",
                    "Weightlessness changes blood circulation and the work of the heart and vessels, which can raise the risk of cardiac events.",
                    "HEART_RATE,HRV,BLOOD_PRESSURE", "The “Heart and vessels” group and live ECG. HRV is our stress metric: related in meaning.", true,
                    nasa("NASA: heart and vessels", "https://www.nasa.gov/reference/risk-of-cardiovascular-adaptations/")),
            new Risk("HYPOXIA", GROUP_PHYSIO, "Lack of oxygen (hypoxia)",
                    "To avoid “decompression sickness” during spacewalks, astronauts take steps that raise the risk of mild hypoxia.",
                    "SPO2", "The SpO₂ metric and a hint to check ventilation.", true,
                    nasa("NASA: hypoxia", "https://www.nasa.gov/reference/risk-of-hypoxia/")),
            new Risk("FITNESS", GROUP_PHYSIO, "Loss of physical fitness",
                    "To avoid losing bone, muscle and heart endurance, astronauts train every day. Researchers study which exercises work best.",
                    null, "The “Strength” and “Cardio” actions and daily goals.", true,
                    nasa("NASA: fitness", "https://www.nasa.gov/reference/risk-of-reduced-cardiorespiratory-and-musculoskeletal-fitness/")),
            new Risk("NUTRITION", GROUP_PHYSIO, "Inadequate nutrition",
                    "Balanced food is needed for health and performance. Work is under way on tasty, long-lasting and nutritious menus.",
                    null, "The “Eat” and “Drink water” actions; glucose and hydration metrics.", true,
                    nasa("NASA: nutrition", "https://www.nasa.gov/reference/risk-of-inadequate-food-and-nutrition/")),
            new Risk("IMMUNE", GROUP_PHYSIO, "Immune system changes",
                    "Spaceflight stress can “wake up” dormant viruses that cause rashes and other symptoms.",
                    null, "Not in the app yet. A direction for development.", false,
                    nasa("NASA: immunity", "https://www.nasa.gov/reference/risk-of-altered-immune-system-responses/")),
            new Risk("SLEEP", GROUP_BEHAVIOR, "Sleep loss and a disrupted schedule",
                    "Chronic sleep loss, constant workload and a disrupted body clock affect crew health and safety.",
                    null, "The sleep metric: less sleep means a higher pulse and lower HRV.", true,
                    nasa("NASA: sleep", "https://www.nasa.gov/reference/risk-from-inadequate-sleep-and-irregular-schedules/")),
            new Risk("BEHAVIOR", GROUP_BEHAVIOR, "Behavioral and mental changes",
                    "Isolation, radiation, distance from Earth and other stresses can affect the crew's mental health.",
                    null, "“Crew (Mesh)” screen: Buddy-Guard and “All good?” requests.", true,
                    nasa("NASA: mental health", "https://www.nasa.gov/reference/risk-of-behavioral-conditions-and-psychiatric-disorders/")),
            new Risk("TEAM", GROUP_BEHAVIOR, "Weak teamwork",
                    "A team must be selected so that it works together, and helped to recover quickly after setbacks during long isolation.",
                    null, "“Crew (Mesh)” screen: mutual help without a central server.", true,
                    nasa("NASA: team", "https://www.nasa.gov/reference/risk-of-inadequate-teamwork/")),
            new Risk("OPS", GROUP_BEHAVIOR, "Lack of support while systems operate",
                    "On distant missions the crew has to handle unexpected software or equipment failures on its own. Researchers study when and what support is needed.",
                    null, "Blackout mode: the autopilot acts on its own when there is no link with Earth.", true,
                    nasa("NASA: systems support", "https://www.nasa.gov/reference/risk-of-inadequate-systems-operations-support/")),
            new Risk("CANCER", GROUP_RADIATION, "Radiation-induced cancer risk",
                    "Radiation exposure in space can raise the probability of cancer. Researchers look for the best ways to reduce this risk.",
                    "RADIATION_DOSE", "Accumulated dose, DNA helix, shelter and water shields during blackout.", true,
                    nasa("NASA: radiation cancer", "https://www.nasa.gov/reference/risk-of-radiation-induced-cancers/")));

    // ---------- споріднені дослідження NASA ----------
    public static final List<Study> STUDIES = List.of(
            new Study("ARCHeR (Artemis II)",
                    "Tracked astronauts' well-being, activity and sleep during the flight around the Moon.",
                    "Our sleep metric and its effect on pulse and HRV.",
                    nasa("NASA: ARCHeR", "https://www.nasa.gov/reference/archer/")),
            new Study("Tempus Pro",
                    "A commercial telemedicine device collects and combines health data to diagnose remotely in near real time.",
                    "Blackout mode: when there is no link, the autopilot doctor works on board.",
                    nasa("NASA: Tempus Pro", "https://www.nasa.gov/reference/tempus-pro/")),
            new Study("Standard Measures",
                    "A single set of physiological and psychological measures so data from different flights can be compared.",
                    "CSV import: unified metric codes (METRIC_CODE).",
                    nasa("NASA: Standard Measures", "https://www.nasa.gov/reference/standard-measures/")),
            new Study("Brain Fluid Pressure",
                    "Studies the link between fluid pressure in the skull and changes in astronauts' eyes and brain.",
                    "Our “Optic nerve pressure” metric.",
                    nasa("NASA: brain fluid pressure", "https://www.nasa.gov/reference/brain-fluid-pressure/")),
            new Study("Thigh Cuff",
                    "Tests whether tight thigh cuffs can keep more fluid in the legs and help avoid vision problems.",
                    "A similar idea to our LBNP session: draw fluid away from the head.",
                    nasa("NASA: Thigh Cuff", "https://www.nasa.gov/reference/thigh-cuff/")),
            new Study("Zero T2",
                    "Studies how exercise without a treadmill affects health and performance before long missions.",
                    "Our workouts and the crew's daily goals.",
                    nasa("NASA: Zero T2", "https://www.nasa.gov/reference/zero-t2/")));

    // ---------- факти CSA ----------
    /** За даними CSA, частка нейтронів у загальній радіації на МКС. Це лише про МКС. */
    public static final double NEUTRON_SHARE_ISS = 0.30;
    /** Скільки міжнародних експериментів у середньому одночасно йде на МКС (за CSA). */
    public static final int ISS_EXPERIMENTS_AT_ONCE = 200;

    // ---------- пошук ----------
    /** Ризик NASA, який пояснює саме цей показник, або null, якщо прямого немає. */
    public static Risk forMetric(String metricName) {
        for (Risk r : RISKS) {
            if (r.metric() != null && Arrays.asList(r.metric().split(",")).contains(metricName)) return r;
        }
        return null;
    }

    public static Risk risk(String id) {
        for (Risk r : RISKS) if (r.id().equals(id)) return r;
        return null;
    }

    public static Hazard hazard(String letter) {
        for (Hazard h : HAZARDS) if (h.letter().equals(letter)) return h;
        return null;
    }

    /** Усі унікальні джерела для підсумкового списку. */
    public static List<Link> mainSources() {
        return List.of(NASA_HAZARDS, NASA_RISKS, NASA_RESEARCH, NASA_ROADMAP, CSA_RADIATION, CSA_WHY_SCIENCE);
    }
}
