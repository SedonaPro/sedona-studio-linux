import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.protocol.SoxDeviceSession;
import ru.bccontrol.sedonastudio.storage.KitUi;
import ru.bccontrol.sedonastudio.storage.WidgetTemplate;

/**
 * Kit widget against a loopback VM (tests/widget_fixture.py): the widget comes from the device's
 * m:ui.zip and matches the device's kits; options are applied, the "build" action is invoked with
 * a journal, the unit appears in the read-back project, the widget's bindings find their blocks.
 * Args: SOX port, journal folder.
 */
public final class WidgetDeviceTests {
  static int failed;

  static void check(boolean ok, String what) {
    System.out.println((ok ? "PASS " : "FAIL ") + what);
    if (!ok) failed++;
  }

  static Project.Node child(Project p, String parent, String name) {
    for (Project.Node n : p.nodes.values())
      if (n.parent.equals(parent) && n.name.equals(name)) return n;
    return null;
  }

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    Path journal = Paths.get(args[1]);
    SoxDeviceSession s = new SoxDeviceSession();
    Project p = null;
    for (int i = 0; i < 40 && p == null; i++)
      try {
        p = s.connect("127.0.0.1", port, "admin", "localJarTest".toCharArray());
      } catch (Exception notYet) {
        Thread.sleep(250);
      }
    check(p != null, "connected to the loopback VM");
    try {
      KitUi.Kit kit = KitUi.fromZip(s.fetch("m:ui.zip")).get("sproAhu");
      check(kit != null && kit.widgets().containsKey("ahu"), "m:ui.zip from the device has the ahu widget");
      WidgetTemplate t = kit.widgets().get("ahu");
      Project.Node unit =
          p.nodes.values().stream().filter(n -> n.type.equals("sproAhu::Ahu")).findFirst().orElse(null);
      check(unit != null, "the app has an Ahu block");
      sedona.Schema schema = KitUi.schema(p.parts, null);
      check(t.problems(schema).isEmpty(), "widget matches the device's kits " + t.problems(schema));
      Project read = p;
      check(read.nodes.values().stream().noneMatch(n -> n.parent.equals(unit.id)), "not built yet");

      Project after = p.copy();
      after.nodes.get(unit.id).values.put("layout", "1");
      after.nodes.get(unit.id).values.put("recovery", "1");
      Project applied = s.apply(p, after, false, journal.resolve("apply"));
      check("1".equals(applied.nodes.get(unit.id).values.get("layout")), "options applied with readback");

      Project built = s.invoke(unit.id, "build", false, journal.resolve("build"));
      long parts = built.nodes.values().stream().filter(n -> n.parent.equals(unit.id)).count();
      check(parts >= 15, "build made the unit: " + parts + " blocks");
      check(child(built, unit.id, "rec") != null && child(built, unit.id, "rec").type.equals("sproHvac::Recuperator"), "heat recovery is there");
      check(child(built, unit.id, "fanE") != null, "exhaust fan is there");
      Project.Node u = built.nodes.get(unit.id);
      check(!u.values.getOrDefault("ioList", "").isBlank(), "I/O list filled");
      check(WidgetTemplate.Condition.parse("layout == 'supplyExhaust' && recovery != 'none'").test(schema.type(t.type), u.values), "the picture's condition for recovery holds");
      int bound = 0;
      for (String part : t.boundParts()) {
        Project.Node n = child(built, unit.id, part);
        if (n == null) continue;
        check(t.parts.get(part).contains(n.type), "part " + part + " has an allowed type " + n.type);
        bound++;
      }
      check(bound >= 12, bound + " parts of the widget found");
      check(Files.readString(journal.resolve("build/completed.log")).contains("INVOKE " + unit.id + ".build"), "invocation journalled");

      Project electric = built.copy();
      electric.nodes.get(unit.id).values.put("heater", "2");
      s.apply(built, electric, false, journal.resolve("apply2"));
      Project rebuilt = s.invoke(unit.id, "build", true, journal.resolve("build2"));
      check(child(rebuilt, unit.id, "heater") != null && child(rebuilt, unit.id, "heater").type.equals("sproHvac::ElectricHeater"), "rebuilt with an electric heater");
      check(Files.readString(journal.resolve("build2/completed.log")).contains("APP_SAVE"), "saved when asked");

      try {
        s.invoke(unit.id, "nosuch", false, journal.resolve("bad"));
        check(false, "unknown action refused");
      } catch (IllegalArgumentException e) {
        check(true, "unknown action refused: " + e.getMessage());
      }
      try {
        s.invoke("99999", "build", false, journal.resolve("bad2"));
        check(false, "unknown component refused");
      } catch (IllegalArgumentException e) {
        check(true, "unknown component refused");
      }
    } finally {
      s.close();
    }
    System.out.println(failed == 0 ? "PASS widget on a device" : "FAIL widget on a device: " + failed);
    System.exit(failed == 0 ? 0 : 1);
  }
}
