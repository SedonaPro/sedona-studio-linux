import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import ru.bccontrol.sedonastudio.storage.KitUi;
import ru.bccontrol.sedonastudio.storage.SvgSymbol;
import ru.bccontrol.sedonastudio.storage.WidgetTemplate;
import ru.bccontrol.sedonastudio.toolchain.Sdk;

/**
 * Kit widgets without a device: the SDK's ui folder and a built ui.zip read the same, every symbol
 * of the sample widget is in the SVG subset, the widget matches the kit manifests, and broken or
 * hostile input is refused. Args: the SDK tree (a built SedonaPro checkout).
 */
public final class KitWidgetTests {
  static int failed;

  static void check(boolean ok, String what) {
    System.out.println((ok ? "PASS " : "FAIL ") + what);
    if (!ok) failed++;
  }

  static void refused(String what, Runnable r) {
    try {
      r.run();
      check(false, what + " refused");
    } catch (RuntimeException e) {
      check(true, what + " refused: " + e.getMessage());
    }
  }

  static byte[] zip(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(out)) {
      for (var e : entries.entrySet()) {
        z.putNextEntry(new ZipEntry(e.getKey()));
        z.write(e.getValue());
        z.closeEntry();
      }
    }
    return out.toByteArray();
  }

  static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  public static void main(String[] args) throws Exception {
    Sdk sdk = Sdk.at(Paths.get(args[0])).orElseThrow();
    Map<String, KitUi.Kit> folder = KitUi.fromFolder(sdk.home.resolve("ui"));
    KitUi.Kit ahu = folder.get("sproAhu");
    check(ahu != null && ahu.widgets().containsKey("ahu") && ahu.broken().isEmpty(), "SDK ui folder: sproAhu has widget ahu");
    WidgetTemplate t = ahu.widgets().get("ahu");
    check(t.type.equals("sproAhu::Ahu") && t.elements.size() > 10 && !t.actions.isEmpty(), "template parsed: " + t.elements.size() + " elements");
    check(ahu.text("ru", "Supply fan").equals("Приточный вентилятор") && ahu.text("de", "Supply fan").equals("Supply fan"), "translations, English fallback");

    Path zipFile = null;
    try (var dirs = Files.list(sdk.tree.resolve("build"))) {
      for (Path d : dirs.toList()) {
        Path z = d.resolve("run/manifests/ui.zip");
        if (Files.isRegularFile(z)) zipFile = z;
      }
    }
    check(zipFile != null, "a built image has manifests/ui.zip");
    Map<String, KitUi.Kit> device = KitUi.fromZip(Files.readAllBytes(zipFile));
    KitUi.Kit fromZip = device.get("sproAhu");
    check(fromZip != null && fromZip.widgets().keySet().equals(ahu.widgets().keySet()) && fromZip.checksum().matches("[0-9a-f]{8}"), "ui.zip reads like the SDK folder, with the kit checksum");

    int symbols = 0;
    for (String file : t.files()) {
      SvgSymbol s = SvgSymbol.parse(ahu.files().get(file));
      check(s.viewBox[2] > 0 && s.root != null, "symbol " + file);
      symbols++;
    }
    check(symbols >= 20, symbols + " symbols in the subset");

    // Against the kit manifests: the SDK's builds, its dependencies pulled in, manifests cached.
    sedona.Schema schema = KitUi.schema(List.of(sdk.kitPart("sproAhu")), sdk.kitSource());
    List<String> problems = t.problems(schema);
    check(problems.isEmpty(), "widget matches the manifests " + problems);
    String json = new String(ahu.files().get("ahu.widget.json"), StandardCharsets.UTF_8);
    List<String> renamed = WidgetTemplate.parse(json.replace("\"fanS.out\"", "\"fanS.noSuchSlot\"")).problems(schema);
    check(renamed.stream().anyMatch(p -> p.contains("fanS.noSuchSlot")), "a renamed slot is reported: " + renamed);
    List<String> notConfig = WidgetTemplate.parse(json.replace("\"slot\": \"layout\"", "\"slot\": \"parts\"")).problems(schema);
    check(notConfig.stream().anyMatch(p -> p.contains("parts is not @config")), "an option that is not @config is reported");
    List<String> action = WidgetTemplate.parse(json.replace("\"invoke\": \"build\"", "\"invoke\": \"reset\"")).problems(schema);
    check(action.stream().anyMatch(p -> p.contains("no action reset")), "an unknown action is reported");
    List<String> range = WidgetTemplate.parse(json.replace("'supplyExhaust'", "'both'")).problems(schema);
    check(range.stream().anyMatch(p -> p.contains("'both'")), "an unknown range name is reported");

    // Conditions.
    sedona.Type type = schema.type("sproAhu::Ahu");
    WidgetTemplate.Condition c = WidgetTemplate.Condition.parse("layout == 'supplyExhaust' && recovery != 'none' || heater == 'electric'");
    check(c.test(type, Map.of("layout", "1", "recovery", "2", "heater", "1")), "condition true");
    check(!c.test(type, Map.of("layout", "1", "recovery", "0", "heater", "1")), "condition false");
    check(c.test(type, Map.of("layout", "0", "recovery", "0", "heater", "2")), "|| branch");
    check(WidgetTemplate.Condition.parse("").test(type, Map.of()), "empty condition is true");
    refused("condition with a call", () -> WidgetTemplate.Condition.parse("x() == 1"));

    // Hostile or broken templates.
    refused("unknown format", () -> WidgetTemplate.parse(json.replace("sedonapro-widget/1", "x/2")));
    refused("symbol outside ui", () -> WidgetTemplate.parse(json.replace("svg/fan-on.svg", "../fan-on.svg")));
    refused("binding to a child not in parts", () -> WidgetTemplate.parse(json.replace("\"fanS.out\"", "\"other.out\"")));
    refused("huge coordinates", () -> WidgetTemplate.parse(json.replace("\"x\": 610", "\"x\": 1e9")));

    // Hostile symbols.
    String head = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">";
    refused("svg script", () -> SvgSymbol.parse(utf8(head + "<script>x()</script></svg>")));
    refused("svg handler", () -> SvgSymbol.parse(utf8(head + "<rect onclick=\"x()\"/></svg>")));
    refused("svg style", () -> SvgSymbol.parse(utf8(head + "<rect style=\"fill:red\"/></svg>")));
    refused("svg image", () -> SvgSymbol.parse(utf8(head + "<image href=\"#a\"/></svg>")));
    refused("svg gradient", () -> SvgSymbol.parse(utf8(head + "<rect fill=\"url(#g)\"/></svg>")));
    refused("svg external entity", () -> SvgSymbol.parse(utf8("<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + head + "<text>&x;</text></svg>")));
    refused("svg without viewBox", () -> SvgSymbol.parse(utf8("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>")));
    check(SvgSymbol.parse(utf8(head + "<g transform=\"rotate(45 5 5)\"><rect x=\"1\" y=\"1\" width=\"2\" height=\"2\" fill=\"#abc\"/></g></svg>")).root.children().size() == 1, "svg subset accepted");

    // Hostile archives.
    byte[] index = utf8("{\"format\":\"sedonapro-ui/1\",\"kits\":{\"k\":{\"version\":\"1\",\"checksum\":\"00000000\",\"widgets\":[]}}}");
    for (String bad : List.of("../evil.json", "k/../../evil.svg", "/abs/x.svg", "k\\x.svg", "k/a/b/c/d/e.svg"))
      try {
        KitUi.fromZip(zip(Map.of("index.json", index, bad, utf8("x"))));
        check(false, "zip entry " + bad + " refused");
      } catch (IOException e) {
        check(true, "zip entry " + bad + " refused");
      }
    try {
      KitUi.fromZip(zip(Map.of("k/a.widget.json", utf8("{}"))));
      check(false, "zip without index refused");
    } catch (IOException e) {
      check(true, "zip without index refused");
    }
    try {
      KitUi.fromZip(zip(Map.of("index.json", index, "k/big.svg", new byte[KitUi.MAX_FILE + 1])));
      check(false, "oversized file refused");
    } catch (IOException e) {
      check(true, "oversized file refused");
    }
    KitUi.Kit broken = KitUi.fromZip(zip(Map.of("index.json", index, "k/w.widget.json", utf8("{\"format\":\"sedonapro-widget/1\"}")))).get("k");
    check(broken.widgets().isEmpty() && broken.broken().containsKey("w"), "a broken widget is listed as broken, not shown");

    System.out.println(failed == 0 ? "PASS kit widgets" : "FAIL kit widgets: " + failed);
    System.exit(failed == 0 ? 0 : 1);
  }
}
