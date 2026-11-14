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
    public static final String GROUP_PHYSIO = "Фізіологія";
    public static final String GROUP_BEHAVIOR = "Поведінка і людський фактор";
    public static final String GROUP_RADIATION = "Радіація";

    // ---------- посилання ----------
    public static final Link NASA_HAZARDS = new Link("NASA HRP: п'ять небезпек польоту", "https://www.nasa.gov/hrp/hazards/");
    public static final Link NASA_RISKS = new Link("NASA HRP: ризики польоту", "https://www.nasa.gov/hrp/risks/");
    public static final Link NASA_RESEARCH = new Link("NASA HRP: дослідження, що тривають", "https://www.nasa.gov/hrp/ongoing-hrp-research/");
    public static final Link NASA_ROADMAP = new Link("NASA Human Research Roadmap", "https://humanresearchroadmap.nasa.gov/");
    public static final Link CSA_RADIATION = new Link("CSA: моніторинг радіації для здоров'я астронавтів",
            "https://www.asc-csa.gc.ca/eng/news/articles/2026/2026-02-26-monitoring-radiation-astronaut-health.asp");
    public static final Link CSA_WHY_SCIENCE = new Link("CSA: навіщо науку проводять у космосі",
            "https://www.asc-csa.gc.ca/eng/iss/science/why-do-we-conduct-science-experiments-in-space.asp");

    private static Link nasa(String title, String url) {
        return new Link(title, url);
    }

    // ---------- п'ять небезпек ----------
    public static final List<Hazard> HAZARDS = List.of(
            new Hazard("R", "Космічна радіація",
                    "Її не видно, але це одна з найнебезпечніших частин польоту.",
                    "Накопичена доза, спіраль ДНК, укриття та щити в режимі блекауту.",
                    nasa("NASA: радіація", "https://www.nasa.gov/hrp/hazard-space-radiation/")),
            new Hazard("I", "Ізоляція та обмежений простір",
                    "Група людей довго живе далеко від Землі в тісному просторі, і це змінює поведінку та самопочуття.",
                    "Екран «Команда (Mesh)»: екіпаж стежить одне за одним. Сон і HRV як ознаки виснаження.",
                    nasa("NASA: ізоляція", "https://www.nasa.gov/hrp/hazard-isolation-and-confinement/")),
            new Hazard("D", "Відстань від Землі",
                    "Що далі від дому, то важче отримати інструкції, припаси та медичну допомогу. Політ до Марса триває близько трьох років.",
                    "Екран «Місія»: затримка зв'язку, режим блекауту та лікар-автопілот.",
                    nasa("NASA: відстань", "https://www.nasa.gov/hrp/hazard-distance-from-earth/")),
            new Hazard("G", "Гравітація та її відсутність",
                    "М'язи, кістки, внутрішнє вухо й органи мають пристосовуватися до нової гравітації. На Марсі це приблизно 3/8 земної.",
                    "Міцність кісток, серце й тиск, тиск на зоровий нерв; тренування та сеанс LBNP.",
                    nasa("NASA: гравітація", "https://www.nasa.gov/hrp/hazard-gravity-fields/")),
            new Hazard("E", "Закрите та вороже середовище",
                    "Усередині корабля треба тримати безпечну екосистему: температуру, тиск, світло, шум, мікроби та імунну відповідь.",
                    "Кисень у крові (SpO₂) і вода. Мікроби та імунітет: у планах.",
                    nasa("NASA: середовище", "https://www.nasa.gov/hrp/hazard-hostile-closed-environments/")));

    // ---------- ризики для здоров'я ----------
    public static final List<Risk> RISKS = List.of(
            new Risk("SANS", GROUP_PHYSIO, "Зміни очей і мозку (SANS)",
                    "У невагомості будова очей і мозку може змінюватися. Вчені вивчають довгі наслідки та способи цьому запобігти.",
                    "ICP_OPTIC", "Показник «Тиск на зоровий нерв», сеанс LBNP, підсвітка очей на голограмі.", true,
                    nasa("NASA: SANS", "https://www.nasa.gov/reference/risk-of-spaceflight-associated-neuro-ocular-syndrome-sans/")),
            new Risk("BONE", GROUP_PHYSIO, "Втрата кісткової маси",
                    "Без гравітації кістки слабшають і стають крихкішими, тож переломи ймовірніші. Допомагають харчування, вправи та ліки.",
                    "BONE_DENSITY", "Показник «Міцність кісток», силові тренування.", true,
                    nasa("NASA: кістки", "https://www.nasa.gov/reference/risk-of-spaceflight-induced-bone-changes/")),
            new Risk("CARDIO", GROUP_PHYSIO, "Зміни серця й судин",
                    "Невагомість змінює кровообіг і роботу серця та судин, а це може підвищувати ризик серцевих подій.",
                    "HEART_RATE,HRV,BLOOD_PRESSURE", "Група «Серце і судини» та живе ЕКГ. HRV наш показник стресу: за змістом споріднений.", true,
                    nasa("NASA: серце і судини", "https://www.nasa.gov/reference/risk-of-cardiovascular-adaptations/")),
            new Risk("HYPOXIA", GROUP_PHYSIO, "Нестача кисню (гіпоксія)",
                    "Щоб під час виходів у космос уникнути «кесонної хвороби», астронавти виконують кроки, які підвищують ризик легкої гіпоксії.",
                    "SPO2", "Показник SpO₂ і підказка перевірити вентиляцію.", true,
                    nasa("NASA: гіпоксія", "https://www.nasa.gov/reference/risk-of-hypoxia/")),
            new Risk("FITNESS", GROUP_PHYSIO, "Втрата фізичної форми",
                    "Щоб не втрачати кістки, м'язи та витривалість серця, астронавти тренуються щодня. Досліджують, які вправи найкращі.",
                    null, "Дії «Силові» та «Кардіо» й щоденні цілі.", true,
                    nasa("NASA: фізична форма", "https://www.nasa.gov/reference/risk-of-reduced-cardiorespiratory-and-musculoskeletal-fitness/")),
            new Risk("NUTRITION", GROUP_PHYSIO, "Недостатнє харчування",
                    "Збалансована їжа потрібна для здоров'я та працездатності. Працюють над смачними, довговічними й поживними раціонами.",
                    null, "Дії «Поїсти» та «Випити води»; показники цукру й гідратації.", true,
                    nasa("NASA: харчування", "https://www.nasa.gov/reference/risk-of-inadequate-food-and-nutrition/")),
            new Risk("IMMUNE", GROUP_PHYSIO, "Зміни імунітету",
                    "Стреси польоту можуть «будити» приховані віруси, що дають висипи й інші симптоми.",
                    null, "Поки немає в застосунку. Напрям розвитку.", false,
                    nasa("NASA: імунітет", "https://www.nasa.gov/reference/risk-of-altered-immune-system-responses/")),
            new Risk("SLEEP", GROUP_BEHAVIOR, "Недосип і збитий графік",
                    "Хронічний недосип, постійне навантаження й збій внутрішнього годинника впливають на здоров'я та безпеку екіпажу.",
                    null, "Показник сну: мало сну означає вищий пульс і нижчий HRV.", true,
                    nasa("NASA: сон", "https://www.nasa.gov/reference/risk-from-inadequate-sleep-and-irregular-schedules/")),
            new Risk("BEHAVIOR", GROUP_BEHAVIOR, "Зміни поведінки та психіки",
                    "Ізоляція, радіація, відстань від Землі та інші стреси можуть позначатися на психічному здоров'ї екіпажу.",
                    null, "Екран «Команда (Mesh)»: Buddy-Guard і запити «Усе гаразд?».", true,
                    nasa("NASA: психіка", "https://www.nasa.gov/reference/risk-of-behavioral-conditions-and-psychiatric-disorders/")),
            new Risk("TEAM", GROUP_BEHAVIOR, "Слабка командна робота",
                    "Команду треба добирати так, щоб вона працювала разом, і допомагати їй швидко оговтуватися після збоїв у довгій ізоляції.",
                    null, "Екран «Команда (Mesh)»: взаємодопомога без центрального сервера.", true,
                    nasa("NASA: команда", "https://www.nasa.gov/reference/risk-of-inadequate-teamwork/")),
            new Risk("OPS", GROUP_BEHAVIOR, "Брак підтримки під час роботи систем",
                    "У далеких місіях екіпаж має сам упоратися з несподіваними збоями програм чи техніки. Вивчають, коли й яка підтримка потрібна.",
                    null, "Режим блекауту: автопілот діє сам, коли зв'язку із Землею немає.", true,
                    nasa("NASA: підтримка систем", "https://www.nasa.gov/reference/risk-of-inadequate-systems-operations-support/")),
            new Risk("CANCER", GROUP_RADIATION, "Онкологічний ризик від радіації",
                    "Опромінення в космосі може підвищувати ймовірність раку. Шукають найкращі способи зменшити цей ризик.",
                    "RADIATION_DOSE", "Накопичена доза, спіраль ДНК, укриття та водяні щити під час блекауту.", true,
                    nasa("NASA: рак від радіації", "https://www.nasa.gov/reference/risk-of-radiation-induced-cancers/")));

    // ---------- споріднені дослідження NASA ----------
    public static final List<Study> STUDIES = List.of(
            new Study("ARCHeR (Artemis II)",
                    "Стежило за самопочуттям, активністю та сном астронавтів під час польоту навколо Місяця.",
                    "Наш показник сну та його вплив на пульс і HRV.",
                    nasa("NASA: ARCHeR", "https://www.nasa.gov/reference/archer/")),
            new Study("Tempus Pro",
                    "Комерційний телемедичний пристрій збирає й об'єднує дані здоров'я, щоб діагностувати віддалено майже в реальному часі.",
                    "Режим блекауту: коли зв'язку немає, лікар-автопілот працює на борту.",
                    nasa("NASA: Tempus Pro", "https://www.nasa.gov/reference/tempus-pro/")),
            new Study("Standard Measures",
                    "Єдиний набір фізіологічних і психологічних вимірів, щоб дані різних польотів можна було порівнювати.",
                    "Імпорт CSV: єдині коди показників (METRIC_CODE).",
                    nasa("NASA: Standard Measures", "https://www.nasa.gov/reference/standard-measures/")),
            new Study("Brain Fluid Pressure",
                    "Досліджує зв'язок між тиском рідини в черепі та змінами очей і мозку астронавтів.",
                    "Наш показник «Тиск на зоровий нерв».",
                    nasa("NASA: тиск рідини в мозку", "https://www.nasa.gov/reference/brain-fluid-pressure/")),
            new Study("Thigh Cuff",
                    "Перевіряє, чи тугі манжети на стегнах утримають більше рідини в ногах і допоможуть уникнути проблем із зором.",
                    "Схожа ідея з нашим сеансом LBNP: відтягнути рідину від голови.",
                    nasa("NASA: Thigh Cuff", "https://www.nasa.gov/reference/thigh-cuff/")),
            new Study("Zero T2",
                    "Вивчає, як вправи без бігової доріжки впливають на здоров'я й працездатність перед далекими місіями.",
                    "Наші тренування та щоденні цілі екіпажу.",
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
