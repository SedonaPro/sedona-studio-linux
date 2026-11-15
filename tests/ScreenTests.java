import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Screens;
import ru.bccontrol.sedonastudio.model.ScreenEdits;
import ru.bccontrol.sedonastudio.model.ScreenBindings;
import ru.bccontrol.sedonastudio.storage.*;

/** Screens package: the web editor's own examples survive a round trip; broken ones are refused. */
public class ScreenTests {
  static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

  static void refused(String name, Map<String, Object> doc) {
    try { ScreenStore.decode(doc); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Accepted: " + name);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> widgetOf(Map<String, Object> doc) {
    return (Map<String, Object>) ((List<Object>) ((Map<String, Object>) ((List<Object>) doc.get("views")).get(0)).get("widgets")).get(0);
  }

  public static void main(String[] args) throws Exception {
    Path examples = Paths.get(args[0]), out = Paths.get(args[1]);
    Files.createDirectories(out);
    int packages = 0, widgets = 0, objects = 0, pictures = 0;
    List<Path> files;
    try (var walk = Files.walk(examples)) { files = walk.filter(p -> p.toString().endsWith(".ui.json")).sorted().toList(); }
    for (Path file : files) {
      Screens s = ScreenStore.read(Files.readString(file));
      String written = ScreenStore.write(s);
      check(ScreenStore.write(ScreenStore.read(written)).equals(written), "Stable round trip " + file.getFileName());
      check(ScreenStore.write(s.copy()).equals(written), "Copy keeps everything " + file.getFileName());
      Files.writeString(out.resolve(file.getFileName()), written);
      packages++;
      if (s.library != null) for (Screens.Asset a : s.library) for (String svg : a.states.values()) { pictures++; check(!SvgSymbol.parseDrawing(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8), ScreenStore.LIMIT).partial, "Library picture drawn in full: " + a.id); }
      for (Screens.View v : s.views) if (!v.svg.isEmpty()) { pictures++; SvgSymbol.parseDrawing(v.svg.getBytes(java.nio.charset.StandardCharsets.UTF_8), ScreenStore.LIMIT); }
      for (Screens.View v : s.views) { widgets += v.widgets == null ? 0 : v.widgets.size(); objects += v.objects == null ? 0 : v.objects.size(); check(!Screens.interest(v).isEmpty() || (v.widgets == null && v.objects == null), "Interest " + v.id); }
    }
    SvgSymbol loose = SvgSymbol.parseDrawing("<svg xmlns='http://www.w3.org/2000/svg' width='10px' height='20'><style>.a{fill:#ff0000}</style><rect class='a' style='stroke:blue' width='5' height='5'/><image href='x.png'/></svg>".getBytes(), 1000);
    check(loose.partial && loose.viewBox[3] == 20 && loose.root.children().get(1).attrs().get("fill").equals("#ff0000") && loose.root.children().get(1).attrs().get("stroke").equals("blue"), "Free drawing: size, class and style rules, unknown parts reported");
    check(packages >= 4 && widgets > 10 && objects > 3, "Examples read: " + packages + "/" + widgets + "/" + objects);
    String base = "{\"format\":\"mcs-sedona-ui\",\"version\":1,\"id\":\"t\",\"title\":\"T\",\"views\":[{\"id\":\"v\",\"title\":\"V\",\"folder\":\"app\",\"svg\":\"\",\"widgets\":[{\"id\":\"w\",\"kind\":\"value\",\"path\":\"a/b\",\"slot\":\"out\",\"type\":\"sys::float\",\"x\":10,\"y\":20.5,\"decimals\":1}]}]}";
    Screens ok = ScreenStore.read(base);
    check(ok.views.get(0).widgets.get(0).label.equals("out") && ok.version == 1, "Defaults as in the web editor");
    check(ScreenStore.write(ok).contains("\"x\":10,\"y\":20.5"), "Whole numbers without a fraction");
    String[][] breaks = {{"kind", "dial"}, {"slot", "password"}, {"slot", "meta"}, {"path", "a/../b"}, {"path", ""}, {"id", "bad id"}};
    for (String[] b : breaks) { Map<String, Object> doc = Json.object(base); widgetOf(doc).put(b[0], b[1]); refused(b[0] + "=" + b[1], doc); }
    Map<String, Object> doc = Json.object(base); widgetOf(doc).put("x", 101.0); refused("x", doc);
    doc = Json.object(base); widgetOf(doc).put("decimals", 1.5); refused("decimals", doc);
    doc = Json.object(base); widgetOf(doc).put("min", 5.0); widgetOf(doc).put("max", 5.0); refused("min=max", doc);
    doc = Json.object(base); widgetOf(doc).put("width", 10.0); refused("width", doc);
    doc = Json.object(base); doc.put("version", 4.0); refused("version", doc);
    doc = Json.object(base); doc.put("format", "other"); refused("format", doc);
    Screens.Symbol fan = new Screens.Symbol();
    fan.mode = "bool"; fan.binding = new Screens.Binding("fan", "run", "sys::bool"); fan.fault = new Screens.Binding("fan", "alarm", "sys::bool");
    check(Screens.state(fan, "true", "false", true).equals("on") && Screens.state(fan, "true", "true", true).equals("fault") && Screens.state(fan, "true", null, true).equals("unknown") && Screens.state(fan, "true", "false", false).equals("unknown"), "Symbol states");
    Screens.Symbol damper = new Screens.Symbol();
    damper.mode = "position"; damper.binding = new Screens.Binding("d", "out", "sys::float");
    check(Screens.state(damper, "0.5", null, true).equals("p50") && Screens.state(damper, "1", null, true).equals("on") && Screens.state(damper, "1.2", null, true).equals("unknown"), "Position states");
    ru.bccontrol.sedonastudio.model.Project project = new ru.bccontrol.sedonastudio.model.Project();
    project.name = "Screens";
    Screens.put(project, ScreenStore.read(Files.readString(files.get(0))));
    String saved = ProjectStore.encode(project);
    check(ScreenStore.write(Screens.of(ProjectStore.decode(saved)).copy()).equals(ScreenStore.write(Screens.of(project))), "Screens saved in the project file");
    String newer = saved.replace("\"format\":\"mcs-sedona-ui\",\"version\":2", "\"format\":\"mcs-sedona-ui\",\"version\":9");
    check(!newer.equals(saved), "Fixture for a newer package");
    ru.bccontrol.sedonastudio.model.Project kept = ProjectStore.decode(newer);
    check(Screens.of(kept) == null && Screens.foreign(kept) != null && Json.object(ProjectStore.encode(kept.copy())).get("ui").equals(Json.object(newer).get("ui")), "A newer package is kept untouched");
    Screens.View arranged = new Screens.View();
    arranged.id = "a";
    for (ScreenEdits.Template template : ScreenEdits.templates()) ScreenEdits.addWidget(arranged, template, 10, 10);
    Screens all = ScreenStore.create("Templates");
    all.views.add(arranged);
    check(ScreenStore.read(ScreenStore.write(all)).views.get(0).widgets.size() == 19, "Every palette template fits the shared format");
    Map<String, double[]> spread = ScreenEdits.align(List.of(new ScreenEdits.Box("a", 0, 0, 10, 5), new ScreenEdits.Box("b", 20, 0, 10, 5), new ScreenEdits.Box("c", 90, 0, 10, 5)), "distributeX");
    check(spread.get("b")[0] == 45 && Double.isNaN(spread.get("b")[1]), "Equal gaps");
    check(ScreenEdits.align(List.of(new ScreenEdits.Box("a", 5, 0, 10, 5), new ScreenEdits.Box("b", 20, 0, 30, 5)), "right").get("a")[0] == 40, "Align right");
    check(ScreenStore.baseLibrary().size() == 7, "Base library of pictures");
    // Screens stay on their folder: a rename or a move rewrites the paths, navigation skips empty folders.
    ru.bccontrol.sedonastudio.model.Project plant = new ru.bccontrol.sedonastudio.model.Project();
    String[][] tree = {{"1", "plant", "0"}, {"2", "ahu1", "1"}, {"3", "fan", "2"}, {"4", "zone", "1"}, {"5", "vav", "4"}, {"6", "t", "5"}};
    for (String[] n : tree) { ru.bccontrol.sedonastudio.model.Project.Node node = new ru.bccontrol.sedonastudio.model.Project.Node(); node.id = n[0]; node.name = n[1]; node.parent = n[2]; node.type = "sys::Folder"; plant.nodes.put(node.id, node); }
    Screens.put(plant, ScreenStore.create("Plant"));
    String[][] homes = {{"top", "app/plant"}, {"top2", "app/plant"}, {"ahu", "app/plant/ahu1"}, {"vav", "app/plant/zone/vav"}};
    for (String[] h : homes) { Screens.View v = new Screens.View(); v.id = h[0]; v.title = h[0]; v.folder = h[1]; v.widgets = new ArrayList<>(); Screens.of(plant).views.add(v); }
    Screens.Widget speed = new Screens.Widget(); speed.id = "w"; speed.path = "fan"; speed.slot = "out"; speed.type = "float";
    Screens.of(plant).view("ahu").widgets.add(speed);
    Screens.Widget deep = new Screens.Widget(); deep.id = "d"; deep.path = "zone/vav/t"; deep.slot = "out"; deep.type = "float";
    Screens.of(plant).view("top").widgets.add(deep);
    StringBuilder ways = new StringBuilder();
    for (ScreenBindings.Link l : ScreenBindings.links(plant, "top")) ways.append(l.kind()).append(':').append(l.view().id).append(' ');
    check(ways.toString().equals("same:top2 down:ahu down:vav "), "Ways from the top screen: " + ways);
    ways.setLength(0);
    for (ScreenBindings.Link l : ScreenBindings.links(plant, "vav")) ways.append(l.kind()).append(':').append(l.view().id).append(' ');
    check(ways.toString().equals("up:top up:top2 "), "Ways from a deep screen: " + ways);
    check(ScreenBindings.screensOf(plant, "1").size() == 2 && ScreenBindings.screensOf(plant, "4").isEmpty(), "Screens of a folder");
    ru.bccontrol.sedonastudio.model.Project renamed = plant.copy();
    renamed.nodes.get("2").name = "ahu2"; renamed.nodes.get("3").name = "fan2"; renamed.nodes.get("5").parent = "1";
    ScreenBindings.follow(plant, renamed);
    check(Screens.of(renamed).view("ahu").folder.equals("app/plant/ahu2") && Screens.of(renamed).view("ahu").widgets.get(0).path.equals("fan2"), "A screen follows its renamed folder and block");
    check(Screens.of(renamed).view("vav").folder.equals("app/plant/vav") && Screens.of(renamed).view("top").widgets.get(0).path.equals("vav/t"), "A screen follows its moved folder");
    check(Screens.of(plant).view("ahu").folder.equals("app/plant/ahu1"), "The earlier project is untouched");
    // Format 3: graphic components with points inside, taken from a plugged-in library.
    ScreenStore.Pack hvac = ScreenLibraries.read(Paths.get(args[2]));
    check(hvac != null && hvac.id().equals("sedona-hvac") && hvac.assets().size() >= 30 && hvac.components().size() >= 20, "HVAC library read from its jar");
    Screens composed = ScreenStore.create("Composed");
    Screens.View hall = new Screens.View(); hall.id = "hall"; hall.title = "Hall"; hall.folder = "app/plant";
    composed.views.add(hall);
    int placedParts = 0;
    for (Screens.Component def : hvac.components()) {
      String partId = ScreenEdits.addPart(composed, hall, def, hvac.assets(), 5 + placedParts % 10 * 9, 5 + placedParts / 10 * 30);
      for (Screens.Point point : def.points) hall.part(partId).bind.put(point.id, new Screens.Binding("ahu1/fan", point.id, point.kind.equals("bool") ? "bool" : "float"));
      placedParts++;
    }
    String composedText = ScreenStore.write(composed);
    Screens back = ScreenStore.read(composedText);
    check(back.version == 3 && back.views.get(0).parts.size() == placedParts && back.components.size() == placedParts && ScreenStore.write(back).equals(composedText), "Components round trip as format 3");
    Files.writeString(Paths.get(args[1]).resolve("composed.ui.json"), composedText);
    Screens.Component fanDef = back.component("sl-c-fan");
    Screens.View inner = Screens.inner(fanDef, back.views.get(0).parts.get(0));
    check(inner.objects.get(0).binding.path().equals("ahu1/fan") && inner.widgets.get(0).slot.equals("speed"), "A part reads the slots bound to its points");
    Screens.Part unfed = back.views.get(0).parts.get(0).copy(); unfed.bind.clear();
    check(Screens.inner(fanDef, unfed).objects.get(0).binding.path().equals(Screens.UNBOUND), "An unbound point leads nowhere");
    check(Screens.interest(back.views.get(0)).size() >= placedParts * 2, "Bound points are sampled");
    Map<String, Object> wrong = Json.object(composedText);
    ((Map<String, Object>) ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) ((List<Object>) wrong.get("views")).get(0)).get("parts")).get(0)).get("bind")).put("nope", Map.of("path", "a", "slot", "b", "type", "bool"));
    refused("bind to an unknown point", wrong);
    wrong = Json.object(composedText);
    ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) ((List<Object>) wrong.get("views")).get(0)).get("parts")).get(0)).get("bind")).get("run")).put("type", "float");
    refused("a number bound to a bool point", wrong);
    wrong = Json.object(composedText);
    ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) ((List<Object>) wrong.get("views")).get(0)).get("parts")).get(0)).put("componentId", "missing");
    refused("a part of a missing component", wrong);
    ru.bccontrol.sedonastudio.model.Project.Node fanNode = plant.nodes.get("3");
    for (String[] slotDef : new String[][] {{"run", "bool"}, {"alarm", "bool"}, {"out", "float"}}) { ru.bccontrol.sedonastudio.model.Project.Port port = new ru.bccontrol.sedonastudio.model.Project.Port(); port.name = slotDef[0]; port.type = slotDef[1]; fanNode.ports.add(port); }
    Map<String, Screens.Binding> fed = ScreenBindings.autoBind(plant, "app/plant", fanDef, "ahu1/fan");
    check(fed.get("run").slot().equals("run") && fed.get("fault").slot().equals("alarm") && fed.get("speed").slot().equals("out") && fed.get("speed").path().equals("ahu1/fan"), "Points take the slots of a block by name");
    Screens.of(plant).components = new ArrayList<>(List.of(fanDef.copy())); Screens.of(plant).library = new ArrayList<>(hvac.assets().subList(0, 0));
    Screens.Part onPlant = new Screens.Part(); onPlant.id = "p"; onPlant.componentId = fanDef.id; onPlant.bind.putAll(fed);
    Screens.of(plant).view("top").parts = new ArrayList<>(List.of(onPlant));
    ru.bccontrol.sedonastudio.model.Project moved = plant.copy();
    moved.nodes.get("3").name = "sf";
    ScreenBindings.follow(plant, moved);
    check(Screens.of(moved).view("top").parts.get(0).bind.get("run").path().equals("ahu1/sf"), "A part follows its renamed block");
    System.out.println("PASS screens package: " + packages + " web editor examples (" + widgets + " widgets, " + objects + " symbols) round trip, " + pictures + " pictures read, broken packages refused, symbol states, graphic components (format 3) from the HVAC library jar");
  }
}
