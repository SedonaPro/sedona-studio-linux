import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.History;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.ProjectExtensions;
import ru.bccontrol.sedonastudio.storage.Json;
import ru.bccontrol.sedonastudio.storage.ProjectStore;

/**
 * The engineering edition: the editor without lib/sedona-scada.jar on the class path. A project
 * with screens, alarm rules and history channels must pass through it untouched.
 */
public class EngineeringTests {
  static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

  public static void main(String[] args) throws Exception {
    for (String name : List.of("model.Screens", "model.Alarms", "model.Archive", "storage.ScreenStore", "ui.ScadaModule", "ui.ScreensFeature"))
      try {
        Class.forName("ru.bccontrol.sedonastudio." + name);
        throw new AssertionError("The SCADA module is on the class path: " + name);
      } catch (ClassNotFoundException expected) {
        // As it must be.
      }
    check(ProjectExtensions.all().isEmpty(), "No project extensions without a module");
    String text = Files.readString(Paths.get(args[0]));
    Map<String, Object> file = Json.object(text);
    check(file.containsKey("ui") && file.containsKey("alarms") && file.containsKey("archive"), "The demo project carries all three SCADA parts");
    Project p = ProjectStore.decode(text);
    check(p.extras.keySet().equals(new LinkedHashSet<>(List.of("ui", "alarms", "archive"))) && p.foreign("ui") != null && p.foreign("alarms") != null, "Parts without their module are kept as they came");
    Map<String, Object> written = Json.object(ProjectStore.encode(p));
    for (String key : List.of("ui", "alarms", "archive")) check(Json.write(written.get(key)).equals(Json.write(file.get(key))), "Part written back untouched: " + key);
    check(written.keySet().equals(file.keySet()), "Same keys in the file");
    // Editing the diagram does not touch the parts either.
    History h = new History();
    h.reset(p);
    String plant = h.get().nodes.values().stream().filter(n -> n.name.equals("plant")).findFirst().get().id;
    h.edit(x -> x.nodes.get(plant).name = "site");
    Map<String, Object> edited = Json.object(ProjectStore.encode(h.get()));
    check(Json.write(edited.get("ui")).equals(Json.write(file.get("ui"))) && Json.write(edited.get("alarms")).equals(Json.write(file.get("alarms"))), "An edit keeps the parts");
    h.undo();
    check(ProjectStore.encode(h.get()).equals(ProjectStore.encode(p)), "Undo");
    Project copy = p.copy();
    copy.extras.remove("ui");
    check(p.extras.containsKey("ui"), "A copy has its own list of parts");
    System.out.println("PASS engineering edition: no SCADA classes, screens/alarms/history of a project kept untouched through read, edit and write");
  }
}
