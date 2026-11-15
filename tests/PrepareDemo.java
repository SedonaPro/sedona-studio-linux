import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Alarms;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.ScreenEdits;
import ru.bccontrol.sedonastudio.model.Screens;
import ru.bccontrol.sedonastudio.storage.ProjectStore;
import ru.bccontrol.sedonastudio.storage.SchemaCatalog;
import ru.bccontrol.sedonastudio.storage.ScreenLibraries;
import ru.bccontrol.sedonastudio.storage.ScreenStore;

/**
 * Writes the demo project: a small plant (an air handling unit and a heating loop) made of blocks
 * that change by themselves in the simulator, with three operator screens drawn with the HVAC
 * library. Usage: java -Dsedona.home=<SDK home> PrepareDemo <manifests dir> <hvac jar> <out file>
 */
public final class PrepareDemo {
  static final double W = 1200, H = 700;
  static Project p = new Project();
  static Map<String, Project.Node> types = new HashMap<>();
  static ScreenStore.Pack hvac;
  static int next = 1;

  public static void main(String[] args) throws Exception {
    List<sedona.KitPart> parts = new ArrayList<>();
    for (String kit : List.of("sys", "types", "func"))
      try (DirectoryStream<Path> files = Files.newDirectoryStream(Paths.get(args[0], kit), "*.xml")) {
        for (Path f : files) parts.add(sedona.KitPart.parse(f.getFileName().toString().replace(".xml", "")));
      }
    for (Project.Node n : SchemaCatalog.types(sedona.Schema.load(parts.toArray(new sedona.KitPart[0])))) types.put(n.type, n);
    hvac = ScreenLibraries.read(Paths.get(args[1]));
    p.name = "HvacDemo";

    String plant = folder("plant", "0", 120, 120);
    String ahu = folder("ahu1", plant, 120, 120);
    String heat = folder("heat", plant, 420, 120);
    // Air handling unit: what runs, what alarms, what is measured.
    flag("fan", ahu, 120, 100, true);
    ramp("fanSpd", ahu, 420, 100, 35, 90, 40);
    flag("fanFlt", ahu, 720, 100, false).note = "Поставьте out = true — вентилятор на экране станет красным";
    ramp("damper", ahu, 120, 260, 0, 1, 30);
    ramp("fltDp", ahu, 420, 260, 80, 240, 60);
    flag("dirty", ahu, 720, 260, false);
    flag("heater", ahu, 120, 420, true);
    flag("frost", ahu, 420, 420, false);
    ramp("tSup", ahu, 720, 420, 19, 23, 25);
    flag("recup", ahu, 120, 580, true);
    ramp("eff", ahu, 420, 580, 58, 74, 50);
    constant("tOut", ahu, 720, 580, -7.5);
    Project.Node set = block("types::WriteFloat", "setpt", ahu, 1020, 420);
    set.values.put("in", "21.0");
    // Heating loop.
    flag("boiler", heat, 120, 100, true);
    flag("boilFl", heat, 420, 100, false);
    ramp("tBoil", heat, 720, 100, 68, 76, 35);
    flag("pump", heat, 120, 260, true);
    flag("pumpFl", heat, 420, 260, false);
    ramp("valve", heat, 720, 260, 0, 1, 45);
    ramp("tRet", heat, 120, 420, 48, 55, 55);
    ramp("press", heat, 420, 420, 1.4, 1.9, 20);
    for (Project.Node n : p.nodes.values()) if (!p.parts.contains(n.kitPart)) p.parts.add(n.kitPart);

    Screens.put(p, ScreenStore.create("HvacDemo"));
    overview();
    airHandler();
    heating();
    ScreenStore.check(Screens.of(p));
    Alarms.put(p, new Alarms());
    alarm("app/plant/ahu1/fanFlt", "П1: авария вентилятора", Alarms.Kind.TRUE, Alarms.Severity.ALARM, 0, 0, 0);
    alarm("app/plant/ahu1/frost", "П1: угроза заморозки калорифера", Alarms.Kind.TRUE, Alarms.Severity.ALARM, 0, 0, 0);
    alarm("app/plant/ahu1/dirty", "П1: фильтр загрязнён", Alarms.Kind.TRUE, Alarms.Severity.WARNING, 0, 0, 5);
    alarm("app/plant/ahu1/fltDp", "П1: перепад на фильтре высокий", Alarms.Kind.HIGH, Alarms.Severity.WARNING, 200, 20, 0);
    alarm("app/plant/heat/boilFl", "ИТП: авария котла", Alarms.Kind.TRUE, Alarms.Severity.ALARM, 0, 0, 0);
    alarm("app/plant/heat/pumpFl", "ИТП: авария насоса", Alarms.Kind.TRUE, Alarms.Severity.ALARM, 0, 0, 0);
    alarm("app/plant/heat/press", "ИТП: давление в контуре низкое", Alarms.Kind.LOW, Alarms.Severity.WARNING, 1.5, 0.05, 0);
    ru.bccontrol.sedonastudio.storage.AlarmStore.check(Alarms.of(p));
    for (Alarms.Rule r : Alarms.of(p).rules) {
      Project.Node n = Alarms.node(p, r.path);
      if (n == null || n.port(r.slot) == null || !Alarms.fits(r.kind, n.port(r.slot).type)) throw new IllegalStateException("Alarm rule finds nothing: " + r.path);
    }
    ru.bccontrol.sedonastudio.model.Archive.put(p, new ru.bccontrol.sedonastudio.model.Archive());
    record("h1", "app/plant/ahu1/tSup", "П1: температура притока, °C");
    record("h2", "app/plant/ahu1/fltDp", "П1: перепад на фильтре, Па");
    record("h3", "app/plant/ahu1/fanSpd", "П1: скорость вентилятора, %");
    record("h4", "app/plant/heat/tBoil", "ИТП: подача, °C");
    record("h5", "app/plant/heat/tRet", "ИТП: обратка, °C");
    record("h6", "app/plant/heat/press", "ИТП: давление, бар");
    ru.bccontrol.sedonastudio.storage.ArchiveStore.check(ru.bccontrol.sedonastudio.model.Archive.of(p));
    List<String> errors = p.validate();
    if (!errors.isEmpty()) throw new IllegalStateException(errors.toString());
    ProjectStore.save(p, Paths.get(args[2]));
    System.out.println("PASS demo project: " + p.nodes.size() + " blocks, " + Screens.of(p).views.size() + " screens, " + Alarms.of(p).rules.size() + " alarm rules, " + ru.bccontrol.sedonastudio.model.Archive.of(p).channels.size() + " history channels, format " + Screens.of(p).version + ", " + Files.size(Paths.get(args[2])) / 1024 + " KiB");
  }

  // ---------------------------------------------------------------- blocks
  static Project.Node block(String type, String name, String parent, double x, double y) {
    Project.Node n = types.get(type).copy();
    n.id = "draft-" + next++;
    n.name = name;
    n.parent = parent;
    n.x = x;
    n.y = y;
    p.nodes.put(n.id, n);
    return n;
  }

  static String folder(String name, String parent, double x, double y) {
    return block("sys::Folder", name, parent, x, y).id;
  }

  static void alarm(String path, String label, Alarms.Kind kind, Alarms.Severity severity, double limit, double band, int delay) {
    Alarms.Rule r = new Alarms.Rule();
    r.id = Alarms.of(p).freeId();
    r.path = path;
    r.slot = "out";
    r.label = label;
    r.kind = kind;
    r.severity = severity;
    r.limit = limit;
    r.band = band;
    r.delay = delay;
    Alarms.of(p).rules.add(r);
  }

  static void record(String id, String path, String label) {
    ru.bccontrol.sedonastudio.model.Archive.Channel c = new ru.bccontrol.sedonastudio.model.Archive.Channel();
    c.id = id;
    c.path = path;
    c.slot = "out";
    c.label = label;
    c.period = 2;
    if (Alarms.node(p, path) == null || Alarms.node(p, path).port("out") == null) throw new IllegalStateException("History channel finds nothing: " + path);
    ru.bccontrol.sedonastudio.model.Archive.of(p).channels.add(c);
  }

  static Project.Node flag(String name, String parent, double x, double y, boolean on) {
    Project.Node n = block("types::ConstBool", name, parent, x, y);
    n.values.put("out", String.valueOf(on));
    return n;
  }

  static void constant(String name, String parent, double x, double y, double v) {
    block("types::ConstFloat", name, parent, x, y).values.put("out", String.valueOf(v));
  }

  static void ramp(String name, String parent, double x, double y, double min, double max, double period) {
    Project.Node n = block("func::Ramp", name, parent, x, y);
    n.values.put("min", String.valueOf(min));
    n.values.put("max", String.valueOf(max));
    n.values.put("period", String.valueOf(period));
  }

  // ---------------------------------------------------------------- screen pieces
  static Screens.View view(String id, String title, String folder, String subtitle) {
    Screens.View v = new Screens.View();
    v.id = id;
    v.title = title;
    v.folder = folder;
    v.svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1200 700\"><defs><linearGradient id=\"sky\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">"
            + "<stop offset=\"0\" stop-color=\"#eef0ef\"/><stop offset=\"1\" stop-color=\"#eef0ef\"/></linearGradient></defs>"
            + "<rect width=\"1200\" height=\"700\" fill=\"url(#sky)\"/><rect width=\"1200\" height=\"64\" fill=\"#2f3439\"/>"
            + "<rect y=\"64\" width=\"1200\" height=\"4\" fill=\"#1f4e79\"/>"
            + "<text x=\"28\" y=\"41\" font-family=\"sans-serif\" font-size=\"24\" font-weight=\"bold\" fill=\"#ffffff\">" + title + "</text>"
            + "<text x=\"1172\" y=\"40\" text-anchor=\"end\" font-family=\"sans-serif\" font-size=\"14\" fill=\"#b4bac0\">" + subtitle + "</text></svg>";
    v.widgets = new ArrayList<>();
    Screens.of(p).views.add(v);
    return v;
  }

  static Screens.Asset asset(String id) {
    for (Screens.Asset a : hvac.assets()) if (a.id.equals(id)) return a;
    throw new IllegalArgumentException(id);
  }

  /** A plain picture at x, y in pixels. */
  static void picture(Screens.View v, String asset, double x, double y, int rotation) {
    String id = ScreenEdits.addSymbol(Screens.of(p), v, asset("sl-" + asset), x / W * 100, y / H * 100);
    v.object(id).rotation = rotation;
    v.object(id).label = asset(("sl-" + asset)).title;
  }

  /** A graphic component at x, y in pixels; bind is "point=block.slot" with the block's path. */
  static void part(Screens.View v, String component, String label, double x, double y, String... bind) {
    Screens.Component def = null;
    for (Screens.Component c : hvac.components()) if (c.id.equals("sl-c-" + component)) def = c;
    String id = ScreenEdits.addPart(Screens.of(p), v, def, hvac.assets(), x / W * 100, y / H * 100);
    Screens.Part part = v.part(id);
    part.label = label;
    for (String b : bind) {
      String point = b.substring(0, b.indexOf('=')), path = b.substring(b.indexOf('=') + 1, b.lastIndexOf('.')), slot = b.substring(b.lastIndexOf('.') + 1);
      part.bind.put(point, new Screens.Binding(path, slot, type(v.folder, path, slot)));
    }
  }

  static String type(String folder, String path, String slot) {
    Project.Node n = ru.bccontrol.sedonastudio.model.ScreenBindings.node(p, folder, path);
    if (n == null || n.port(slot) == null) throw new IllegalArgumentException(folder + " " + path + "." + slot);
    return n.port(slot).type;
  }

  static Screens.Widget widget(Screens.View v, String template, String label, double x, double y, String path, String slot) {
    ScreenEdits.Template t = ScreenEdits.templates().stream().filter(c -> c.id().equals(template)).findFirst().orElseThrow();
    Screens.Widget w = v.widget(ScreenEdits.addWidget(v, t, x / W * 100, y / H * 100));
    w.label = label;
    if (path != null) {
      w.path = path;
      w.slot = slot;
      w.type = type(v.folder, path, slot);
    }
    return w;
  }

  static void channel(Screens.View v, Screens.Widget w, String label, String unit, String path, String slot, String color) {
    Screens.Series s = new Screens.Series();
    s.label = label;
    s.unit = unit;
    s.path = path;
    s.slot = slot;
    s.type = type(v.folder, path, slot);
    s.color = color;
    w.series.add(s);
  }

  // ---------------------------------------------------------------- the three screens
  static void overview() {
    Screens.View v = view("overview", "Объект · обзор", "app/plant", "Sedona Studio · демонстрация");
    picture(v, "zone", 40, 110, 0);
    picture(v, "zone", 320, 110, 0);
    { Screens.Widget caption = widget(v, "text", "Приточная установка ПВУ-1", 44, 82, null, null); caption.options.put("fontSize", 20.0); caption.options.put("width", 260.0); }
    { Screens.Widget caption = widget(v, "text", "Тепловой узел", 324, 82, null, null); caption.options.put("fontSize", 20.0); caption.options.put("width", 260.0); }
    part(v, "sensor-t", "Приток", 60, 148, "value=ahu1/tSup.out");
    part(v, "sensor-t", "Улица", 60, 204, "value=ahu1/tOut.out");
    part(v, "sensor-t", "Котёл", 340, 148, "value=heat/tBoil.out");
    part(v, "sensor-t", "Обратка", 340, 204, "value=heat/tRet.out");
    part(v, "fan", "Приточный вентилятор", 620, 110, "run=ahu1/fan.out", "fault=ahu1/fanFlt.out", "speed=ahu1/fanSpd.out");
    part(v, "pump", "Насос", 780, 120, "run=heat/pump.out", "fault=heat/pumpFl.out", "value=heat/press.out");
    part(v, "boiler", "Котёл", 920, 100, "run=heat/boiler.out", "fault=heat/boilFl.out", "supply=heat/tBoil.out");
    Screens.Widget trend = widget(v, "trend", "Температуры", 40, 320, null, null);
    trend.options.put("width", 560.0);
    trend.options.put("height", 300.0);
    channel(v, trend, "Приток", "°C", "ahu1/tSup", "out", "#c2542d");
    channel(v, trend, "Уставка", "°C", "ahu1/setpt", "out", "#2f7d4f");
    Screens.Widget table = widget(v, "table", "Сводка", 640, 320, null, null);
    table.options.put("width", 520.0);
    table.options.put("height", 300.0);
    channel(v, table, "Приток", "°C", "ahu1/tSup", "out", null);
    channel(v, table, "Улица", "°C", "ahu1/tOut", "out", null);
    channel(v, table, "Скорость вентилятора", "%", "ahu1/fanSpd", "out", null);
    channel(v, table, "Перепад на фильтре", "Па", "ahu1/fltDp", "out", null);
    channel(v, table, "КПД рекуператора", "%", "ahu1/eff", "out", null);
    channel(v, table, "Подача котла", "°C", "heat/tBoil", "out", null);
    channel(v, table, "Обратка", "°C", "heat/tRet", "out", null);
    channel(v, table, "Клапан", "", "heat/valve", "out", null);
  }

  static void airHandler() {
    Screens.View v = view("ahu", "ПВУ-1 · приточная установка", "app/plant/ahu1", "приток снизу, вытяжка сверху");
    double top = 150, low = top + 96; // exhaust deck and supply deck of the recuperator
    // Exhaust (upper) line: from the room on the right, out on the left.
    picture(v, "exhaust-left", 172, top, 0);
    picture(v, "duct", 252, top, 0);
    picture(v, "duct-long", 452, top, 0);
    picture(v, "duct-long", 692, top, 0);
    // Supply (lower) line, left to right.
    picture(v, "intake", 60, low, 0);
    part(v, "damper", "Заслонка наружного воздуха", 140, low, "position=damper.out");
    part(v, "filter", "Фильтр", 236, low, "dirty=dirty.out", "dp=fltDp.out");
    part(v, "recuperator", "Рекуператор", 332, top, "active=recup.out", "efficiency=eff.out");
    part(v, "heater", "Водяной нагреватель", 452, low, "active=heater.out", "frost=frost.out", "temp=tSup.out");
    part(v, "fan", "Приточный вентилятор", 548, low, "run=fan.out", "fault=fanFlt.out", "speed=fanSpd.out");
    picture(v, "duct-flow", 668, low, 0);
    picture(v, "duct-long", 748, low, 0);
    part(v, "sensor-t", "Наружный воздух", 40, 130, "value=tOut.out");
    part(v, "sensor-t", "Приток", 1010, low + 8, "value=tSup.out");
    part(v, "setpoint", "Уставка притока", 960, 400, "setpoint=setpt.in", "actual=tSup.out");
    Screens.Widget trend = widget(v, "trend", "Приток и вентилятор", 40, 420, null, null);
    trend.options.put("width", 520.0);
    trend.options.put("height", 215.0);
    channel(v, trend, "Приток", "°C", "tSup", "out", "#c2542d");
    channel(v, trend, "Скорость", "%", "fanSpd", "out", "#2e6da4");
    Screens.Widget bars = widget(v, "bars", "Сейчас", 590, 420, null, null);
    bars.options.put("width", 340.0);
    bars.options.put("height", 215.0);
    bars.options.put("max", 250.0);
    channel(v, bars, "Скорость, %", "", "fanSpd", "out", "#2e6da4");
    channel(v, bars, "КПД, %", "", "eff", "out", "#2f7d4f");
    channel(v, bars, "Фильтр, Па", "", "fltDp", "out", "#6b5b95");
  }

  static void heating() {
    Screens.View v = view("heat", "Тепловой узел", "app/plant/heat", "подача сверху, обратка снизу");
    double x = 80, y = 170, supply = y + 28 - 32, back = y + 124 - 32;
    part(v, "boiler", "Котёл", x, y, "run=boiler.out", "fault=boilFl.out", "supply=tBoil.out");
    picture(v, "pipe", 200, supply, 0);
    part(v, "pump", "Циркуляционный насос", 264, supply, "run=pump.out", "fault=pumpFl.out", "value=press.out");
    picture(v, "pipe", 360, supply, 0);
    part(v, "valve", "Регулирующий клапан", 424, supply - 8, "position=valve.out");
    picture(v, "pipe", 520, supply, 0);
    picture(v, "pipe", 584, supply, 0);
    picture(v, "heat-exchanger", 648, y, 0);
    for (double px = 200; px < 648; px += 64) picture(v, "pipe", px, back, 0);
    part(v, "sensor-t", "Подача", 740, y - 10, "value=tBoil.out");
    part(v, "sensor-t", "Обратка", 740, y + 100, "value=tRet.out");
    widget(v, "gauge", "Клапан", 960, 130, "valve", "out").options.put("max", 1.0);
    Screens.Widget trend = widget(v, "trend", "Подача и обратка", 80, 410, null, null);
    trend.options.put("width", 640.0);
    trend.options.put("height", 235.0);
    channel(v, trend, "Подача", "°C", "tBoil", "out", "#c7302b");
    channel(v, trend, "Обратка", "°C", "tRet", "out", "#2e6da4");
    widget(v, "status", "Котёл в работе", 780, 420, "boiler", "out");
    widget(v, "status", "Насос в работе", 780, 520, "pump", "out");
  }
}
