import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.storage.KitUi;
import ru.bccontrol.sedonastudio.storage.ProjectStore;
import ru.bccontrol.sedonastudio.storage.SchemaCatalog;
import ru.bccontrol.sedonastudio.toolchain.Sdk;

/** A drawing with one air handling unit configurator (sproAhu::Ahu), not built, on the SDK's kits. Args: SDK tree, output file. */
public final class PrepareWidgetProject {
  public static void main(String[] args) throws Exception {
    Sdk sdk = Sdk.at(Paths.get(args[0])).orElseThrow();
    sedona.Schema schema = KitUi.schema(List.of(sdk.kitPart("sproAhu")), sdk.kitSource());
    Project p = new Project();
    p.name = "WidgetTest";
    for (Project.Node n : SchemaCatalog.types(schema))
      if (n.type.equals("sproAhu::Ahu")) {
        n.id = "draft-ahu"; n.name = "unit"; n.x = 160; n.y = 120;
        n.values.put("buildOnStart", "false");
        n.values.put("layout", "0");
        n.values.put("recovery", "0");
        p.nodes.put(n.id, n);
      }
    for (sedona.Kit k : schema.kits) p.parts.add(k.manifest.part().toString());
    ProjectStore.save(p, Paths.get(args[1]));
    System.out.println("PASS widget project prepared: " + p.nodes.size() + " block, " + p.parts.size() + " kits");
  }
}
