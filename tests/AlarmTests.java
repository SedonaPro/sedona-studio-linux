import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Alarms;
import ru.bccontrol.sedonastudio.model.History;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.storage.AlarmStore;
import ru.bccontrol.sedonastudio.storage.Json;
import ru.bccontrol.sedonastudio.storage.ProjectStore;

/** Alarm rules: the state machine, the project file, the journal, following renames. */
public class AlarmTests {
  static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

  static Alarms.Rule rule(String id, Alarms.Kind kind, double limit, double band, int delay) {
    Alarms.Rule r = new Alarms.Rule();
    r.id = id; r.label = "Rule " + id; r.path = "app/plant/fan"; r.slot = "out";
    r.kind = kind; r.limit = limit; r.band = band; r.delay = delay;
    return r;
  }

  static Project.Node node(Project p, String id, String name, String parent) {
    Project.Node n = new Project.Node();
    n.id = id; n.name = name; n.type = "sys::Folder"; n.kitPart = "sys"; n.parent = parent;
    p.nodes.put(id, n);
    return n;
  }

  public static void main(String[] args) throws Exception {
    Path dir = Paths.get(args[0]);
    if (Files.exists(dir)) try (var walk = Files.walk(dir)) { for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(f); }
    Alarms alarms = new Alarms();
    Alarms.Rule high = rule("a1", Alarms.Kind.HIGH, 28, 1, 0), flag = rule("a2", Alarms.Kind.TRUE, 0, 0, 10), low = rule("a3", Alarms.Kind.LOW, 5, 0, 0);
    alarms.rules.addAll(List.of(high, flag, low));
    Map<String, String> values = new HashMap<>(Map.of("a1", "20", "a2", "false", "a3", "9"));
    Alarms.Tracker t = new Alarms.Tracker();
    List<Alarms.Event> all = new ArrayList<>();
    java.util.function.LongConsumer step = now -> all.addAll(t.step(alarms, r -> values.get(r.id), now));
    step.accept(1000);
    check(all.isEmpty() && t.state("a1").phase == Alarms.Phase.NORMAL, "Quiet while the values are normal");
    values.put("a1", "28.5");
    step.accept(2000);
    check(all.size() == 1 && all.get(0).what().equals("raised") && all.get(0).value().equals("28.5") && t.state("a1").phase == Alarms.Phase.ACTIVE && !t.state("a1").acked, "Raised above the limit");
    values.put("a1", "27.5");
    step.accept(3000);
    check(all.size() == 1 && t.state("a1").phase == Alarms.Phase.ACTIVE, "Stays active inside the return band");
    values.put("a1", null);
    step.accept(3500);
    check(all.size() == 1 && t.state("a1").phase == Alarms.Phase.ACTIVE, "No value: neither raised nor cleared");
    values.put("a1", "26.9");
    step.accept(4000);
    check(all.size() == 2 && all.get(1).what().equals("cleared") && t.state("a1").phase == Alarms.Phase.RETURNED, "Back to normal without an acknowledgement is kept on the list");
    check(t.ack(high, 4500).what().equals("acked") && t.state("a1").phase == Alarms.Phase.NORMAL && t.ack(high, 4600) == null, "Acknowledging a returned alarm removes it");
    values.put("a1", "30");
    step.accept(5000);
    check(t.ack(high, 5100) != null && t.state("a1").acked && t.state("a1").phase == Alarms.Phase.ACTIVE, "An acknowledged alarm stays active");
    values.put("a1", "10");
    step.accept(6000);
    check(t.state("a1").phase == Alarms.Phase.NORMAL, "An acknowledged alarm leaves when the value is back");
    int before = all.size();
    values.put("a2", "true");
    step.accept(10000);
    step.accept(19000);
    check(all.size() == before && t.state("a2").phase == Alarms.Phase.NORMAL, "Delay: not raised early");
    values.put("a2", "false");
    step.accept(19500);
    values.put("a2", "true");
    step.accept(20000);
    step.accept(29000);
    check(all.size() == before, "Delay starts again after the condition went away");
    step.accept(30000);
    check(all.size() == before + 1 && t.state("a2").phase == Alarms.Phase.ACTIVE && t.state("a2").since == 30000, "Delay: raised after it held long enough");
    values.put("a3", "4.9");
    step.accept(31000);
    check(t.state("a3").phase == Alarms.Phase.ACTIVE, "Raised below the limit");
    values.put("a3", "abc");
    step.accept(32000);
    check(t.state("a3").phase == Alarms.Phase.ACTIVE, "Not a number says nothing");
    low.slot = "other";
    values.put("a3", "4");
    step.accept(33000);
    check(t.state("a3").phase == Alarms.Phase.ACTIVE && t.state("a3").since == 33000, "A rule pointed at another value starts from scratch");
    alarms.rules.remove(low);
    step.accept(34000);
    check(t.state("a3").phase == Alarms.Phase.NORMAL, "A removed rule leaves no state");
    check(Alarms.fits(Alarms.Kind.TRUE, "sys::bool") && !Alarms.fits(Alarms.Kind.TRUE, "float") && Alarms.fits(Alarms.Kind.LOW, "sys::float") && !Alarms.fits(Alarms.Kind.HIGH, "sys::Str"), "Which types a rule can watch");

    // Project file.
    Project p = new Project();
    node(p, "1", "plant", "0");
    node(p, "2", "fan", "1");
    Alarms.put(p, alarms.copy());
    String written = ProjectStore.encode(p);
    Project back = ProjectStore.decode(written);
    check(Alarms.of(back) != null && Alarms.of(back).rules.size() == 2 && ProjectStore.encode(back).equals(written), "Rules survive the project file");
    Alarms.Rule r1 = Alarms.of(back).rule("a1");
    check(r1.kind == Alarms.Kind.HIGH && r1.limit == 28 && r1.band == 1 && r1.path.equals("app/plant/fan") && Alarms.of(back).rule("a2").delay == 10, "Rule fields are kept");
    check(Alarms.node(back, "app/plant/fan").id.equals("2") && Alarms.node(back, "app/plant/none") == null && Alarms.node(back, "app") == null, "A rule finds its component by path");
    Project none = new Project();
    check(!ProjectStore.encode(none).contains("alarms"), "No rules: no key in the file");
    Map<String, Object> newer = Json.object(written);
    @SuppressWarnings("unchecked") Map<String, Object> doc = (Map<String, Object>) newer.get("alarms");
    doc.put("version", 2);
    Project kept = ProjectStore.decode(Json.write(newer));
    check(Alarms.of(kept) == null && Alarms.foreign(kept) != null && ProjectStore.encode(kept).contains("\"sedona-studio-alarms\",\"version\":2") && ProjectStore.encode(kept.copy()).equals(ProjectStore.encode(kept)), "Rules of a newer format are kept untouched");
    for (java.util.function.Consumer<Alarms.Rule> damage : List.<java.util.function.Consumer<Alarms.Rule>>of(r -> r.id = "bad id", r -> r.id = "a2", r -> r.delay = 4000, r -> r.band = -1, r -> r.limit = Double.NaN, r -> r.slot = "a b", r -> r.path = "", r -> r.label = "x".repeat(81))) {
      Alarms broken = alarms.copy();
      damage.accept(broken.rules.get(0));
      boolean refused = false;
      try { AlarmStore.check(broken); } catch (IllegalArgumentException expected) { refused = true; }
      check(refused, "Damaged rule refused");
    }
    Alarms many = new Alarms();
    for (int i = 0; i <= Alarms.MAX_RULES; i++) many.rules.add(rule("r" + i, Alarms.Kind.TRUE, 0, 0, 0));
    boolean refused = false;
    try { AlarmStore.check(many); } catch (IllegalArgumentException expected) { refused = true; }
    check(refused, "Too many rules refused");

    // Renames and moves.
    History h = new History();
    h.reset(back);
    h.edit(x -> x.nodes.get("1").name = "site");
    check(Alarms.of(h.get()).rule("a1").path.equals("app/site/fan"), "A rule follows a renamed folder");
    h.edit(x -> x.nodes.get("2").parent = "0");
    check(Alarms.of(h.get()).rule("a1").path.equals("app/fan") && Alarms.under(Alarms.of(h.get()).rule("a1"), "app") && !Alarms.under(Alarms.of(h.get()).rule("a1"), "app/site"), "A rule follows a moved block");
    h.edit(x -> x.remove("2"));
    check(Alarms.of(h.get()).rule("a1").path.equals("app/fan") && Alarms.node(h.get(), "app/fan") == null, "A rule of a deleted block stays and finds nothing");
    h.undo();
    h.undo();
    check(Alarms.of(h.get()).rule("a1").path.equals("app/site/fan"), "Undo brings the path back");

    // Journal.
    AlarmStore.append(dir, all);
    List<Alarms.Event> read = AlarmStore.journal(dir, 500);
    check(read.equals(all), "Journal keeps the events as they were");
    Files.writeString(dir.resolve("alarms.jsonl"), "not json\n", java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    AlarmStore.append(dir, List.of(new Alarms.Event(99, "a1", "Тест \"x\"", "app/fan.out", "acked", "1", "alarm")));
    read = AlarmStore.journal(dir, 2);
    check(read.size() == 2 && read.get(1).label().equals("Тест \"x\"") && read.get(1).time() == 99, "Journal: a damaged line is skipped, the last events are returned");
    System.out.println("PASS alarms: state machine, delay and return band, project file, newer format kept, renames followed, journal");
  }
}
