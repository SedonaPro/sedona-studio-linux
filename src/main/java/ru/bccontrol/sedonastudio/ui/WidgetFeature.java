package ru.bccontrol.sedonastudio.ui;

import java.nio.file.*;
import java.util.*;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import ru.bccontrol.sedonastudio.model.History;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.protocol.ScalarValues;
import ru.bccontrol.sedonastudio.storage.KitUi;
import ru.bccontrol.sedonastudio.storage.Languages;
import ru.bccontrol.sedonastudio.storage.SvgSymbol;
import ru.bccontrol.sedonastudio.storage.WidgetTemplate;
import ru.bccontrol.sedonastudio.toolchain.Sdk;
import sedona.KitPart;
import sedona.Schema;
import sedona.manifest.KitManifest;
import sedona.manifest.ManifestDb;
import sedona.manifest.TypeManifest;

/**
 * Kit widgets (docs/widgets.md in SedonaPro): a block whose type names a widget (@widget) opens it
 * in its own window. The widget comes from the connected device ("m:ui.zip") or, without one, from
 * the SDK's "ui" folder, and is shown only if it matches the kit manifests. Options and controls
 * change the draft; controls are then sent with Apply (or "apply at once"); an action - such as
 * "build" - first applies the draft and then invokes the action, after a confirmation that names
 * both, with the usual journal and a fresh read of the device.
 */
final class WidgetFeature implements WidgetView.Owner {
  private final History history;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final LiveValues liveValues;
  private final ViewOptions view;
  private final DeviceConnection deviceConnection;
  private final DeviceChanges deviceChanges;
  private final EditorShell shell;
  private final Map<String, WidgetView> open = new LinkedHashMap<>();
  private final Map<String, String> widgetIds = new HashMap<>();
  private int pollingCursor;

  WidgetFeature(
      History history,
      DeviceLink deviceLink,
      Notices notices,
      LiveValues liveValues,
      ViewOptions view,
      DeviceConnection deviceConnection,
      DeviceChanges deviceChanges,
      EditorShell shell) {
    this.history = history;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.liveValues = liveValues;
    this.view = view;
    this.deviceConnection = deviceConnection;
    this.deviceChanges = deviceChanges;
    this.shell = shell;
  }

  /** The widget a block's type points to with @widget, or null. Reads the cached kit manifest. */
  String widgetOf(Project.Node n) {
    if (n == null || n.kitPart == null || n.kitPart.isEmpty()) return null;
    String key = n.kitPart + "|" + n.type;
    String known = widgetIds.get(key);
    if (known == null) {
      try {
        known = "";
        KitPart part = KitPart.parse(n.kitPart);
        if (!ManifestDb.toFile(part).isFile()) {
          // A drawing made offline: take the kit's manifest from the SDK, if there is one.
          String sdkPath = shell.prefs().get("sdkPath", "");
          Sdk sdk = Sdk.find(sdkPath.isEmpty() ? null : sdkPath).orElse(null);
          if (sdk == null) return null;
          try {
            KitUi.schema(List.of(n.kitPart), sdk.kitSource());
          } catch (Exception incomplete) {
            // Only the manifest of this kit is needed here.
          }
        }
        KitManifest m = ManifestDb.load(part);
        if (m != null)
          for (TypeManifest t : m.types)
            if (t.qname.equals(n.type)) known = t.facets.gets("widget", "");
        widgetIds.put(key, known);
      } catch (Exception notCached) {
        return null;
      }
    }
    return known.isEmpty() ? null : known;
  }

  private record Loaded(
      KitUi.Kit kit,
      WidgetTemplate template,
      Schema schema,
      Map<String, SvgSymbol> symbols,
      String source) {}

  /** Opens (or raises) the widget window of a block. */
  void open(String nodeId) {
    WidgetView already = open.get(nodeId);
    if (already != null) {
      already.toFront();
      return;
    }
    Project p = history.get();
    Project.Node n = p.nodes.get(nodeId);
    String widget = widgetOf(n);
    if (widget == null) throw new IllegalStateException(Tr.of("widget.none"));
    String kitName = KitPart.parse(n.kitPart).name;
    boolean device = deviceLink.session.connected();
    String sdkPath = shell.prefs().get("sdkPath", "");
    Set<String> parts = new LinkedHashSet<>(p.parts);
    for (Project.Node x : p.nodes.values())
      if (x.kitPart != null && !x.kitPart.isEmpty()) parts.add(x.kitPart);
    deviceLink.work(
        Tr.of("widget.loading", kitName),
        () -> {
          Map<String, KitUi.Kit> bundle = Map.of();
          String source = "";
          Exception deviceProblem = null;
          if (device)
            try {
              bundle = KitUi.fromZip(deviceLink.session.fetch("m:ui.zip"));
              source = Tr.of("widget.fromDevice");
            } catch (Exception e) {
              deviceProblem = e;
            }
          Sdk sdk = Sdk.find(sdkPath.isEmpty() ? null : sdkPath).orElse(null);
          if (!bundle.containsKey(kitName)) {
            if (sdk != null) {
              bundle = KitUi.fromFolder(sdk.home.resolve("ui"));
              source = Tr.of("widget.fromSdk", sdk.home.resolve("ui"));
            }
          }
          KitUi.Kit kit = bundle.get(kitName);
          if (kit == null)
            throw new IllegalStateException(
                Tr.of(
                    "widget.notFound",
                    kitName,
                    deviceProblem == null ? "" : String.valueOf(deviceProblem.getMessage())));
          WidgetTemplate t = kit.widgets().get(widget);
          if (t == null)
            throw new IllegalStateException(
                Tr.of("widget.broken", widget, kit.broken().getOrDefault(widget, "")));
          Schema schema = KitUi.schema(parts, sdk == null ? null : sdk.kitSource());
          List<String> problems = t.problems(schema);
          if (!t.type.equals(n.type)) problems = List.of(t.type + " ≠ " + n.type);
          if (!problems.isEmpty())
            throw new IllegalStateException(
                Tr.of(
                    "widget.mismatch",
                    widget,
                    String.join("; ", problems.subList(0, Math.min(4, problems.size())))));
          Map<String, SvgSymbol> symbols = new HashMap<>();
          for (String file : t.files())
            try {
              symbols.put(file, SvgSymbol.parse(kit.files().get(file)));
            } catch (RuntimeException badSymbol) {
              // Drawn as an empty box; the rest of the widget still works.
            }
          return new Loaded(kit, t, schema, symbols, source);
        },
        loaded -> {
          WidgetView v =
              new WidgetView(
                  this,
                  nodeId,
                  loaded.kit(),
                  loaded.template(),
                  loaded.schema(),
                  loaded.symbols(),
                  Languages.active(),
                  loaded.source());
          open.put(nodeId, v);
          v.show(shell.stage());
        });
  }

  /** Redraw open widgets (after an edit or a new sample). */
  void refresh() {
    Project p = history.get();
    for (WidgetView v : List.copyOf(open.values())) {
      Project.Node n = p.nodes.get(v.nodeId);
      if (n == null || !n.type.equals(v.template.type) || !path(p, v.nodeId).equals(v.path))
        // Read from a device or the simulator: the same block may now have another id.
        for (Project.Node other : p.nodes.values())
          if (other.type.equals(v.template.type)
              && path(p, other.id).equals(v.path)
              && !open.containsKey(other.id)) {
            open.remove(v.nodeId, v);
            v.nodeId = other.id;
            open.put(other.id, v);
            break;
          }
      v.refresh();
    }
  }

  /** "/folder/name" of a block, by names (ids change between a draft and a device). */
  static String path(Project p, String id) {
    StringBuilder b = new StringBuilder();
    Project.Node n = p.nodes.get(id);
    for (int depth = 0; n != null && !n.id.equals("0") && depth < 64; depth++) {
      b.insert(0, "/" + n.name);
      n = p.nodes.get(n.parent);
    }
    return b.length() == 0 ? "/" : b.toString();
  }

  /** Let the open widgets' blocks take a few slots of each sampling round. */
  void addWatchedTo(Collection<String> ids) {
    List<String> watched = new ArrayList<>();
    for (WidgetView v : open.values()) watched.addAll(v.watchedIds());
    for (int i = 0; i < Math.min(8, watched.size()); i++)
      ids.add(watched.get(Math.floorMod(pollingCursor + i, watched.size())));
    pollingCursor += 8;
  }

  void closeAll() {
    for (WidgetView v : List.copyOf(open.values())) v.close();
  }

  /** Ids of the blocks with an open widget window. */
  Set<String> openIds() {
    return Set.copyOf(open.keySet());
  }

  WidgetView view(String nodeId) {
    return open.get(nodeId);
  }

  // ---------------------------------------------------------------- WidgetView.Owner

  public Project project() {
    return history.get();
  }

  public String value(Project.Node n, String slot) {
    return liveValues.fullValue(n, slot);
  }

  public String deviceValue(Project.Node n, String slot) {
    if (deviceLink.baseline == null || n == null) return null;
    Project.Node old = deviceLink.baseline.nodes.get(n.id);
    return old == null ? null : old.values.get(slot);
  }

  public boolean online() {
    return deviceLink.baseline != null && deviceLink.session.connected();
  }

  public void setOption(String nodeId, String slot, String literal) {
    notices.guard(() -> put(nodeId, slot, literal));
  }

  public void command(String nodeId, String slot, String literal) {
    notices.guard(
        () -> {
          if (!put(nodeId, slot, literal)) return;
          // With "apply at once" the edit itself is sent; otherwise review and apply it now.
          if (online() && !view.autoApply) deviceChanges.apply();
        });
  }

  /** Puts a checked value into the draft as one undo step; false when nothing changed. */
  private boolean put(String nodeId, String slot, String literal) {
    Project.Node n = history.get().nodes.get(nodeId);
    Project.Port port = n == null ? null : n.port(slot);
    if (port == null || port.readonly)
      throw new IllegalArgumentException(Tr.of("widget.notWritable", slot));
    String value =
        port.asStr
            ? ScalarValues.validateText(literal, port.textMax)
            : ScalarValues.validateLiteral(port.type, literal);
    if (value.equals(n.values.get(slot))) return false;
    shell.edit(p -> p.nodes.get(nodeId).values.put(slot, value));
    return true;
  }

  public void action(WidgetView v, WidgetTemplate.Action a) {
    notices.guard(() -> run(v, a));
  }

  private void run(WidgetView v, WidgetTemplate.Action a) {
    if (!online()) throw new IllegalStateException(Tr.of("widget.needDevice"));
    Project before = deviceLink.baseline.copy(), after = history.get().copy();
    List<String> steps = deviceLink.session.plan(before, after);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(v.stage());
    dialog.setTitle(v.t(a.label()));
    Label question = new Label(v.t(a.confirm()));
    question.setWrapText(true);
    VBox body = new VBox(10, question);
    if (!steps.isEmpty()) {
      Label first = new Label(Tr.of("widget.applyFirst", steps.size()));
      first.setWrapText(true);
      TextArea list = new TextArea(String.join("\n", steps));
      list.setEditable(false);
      list.setPrefRowCount(Math.min(8, steps.size() + 1));
      body.getChildren().addAll(first, list);
    }
    CheckBox save = new CheckBox(Tr.of("widget.saveAfter"));
    save.setSelected(true);
    body.getChildren().add(save);
    body.setPrefWidth(520);
    dialog.getDialogPane().setContent(body);
    ButtonType run = new ButtonType(v.t(a.label()), ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(run, ButtonType.CANCEL);
    dialog.getDialogPane().lookupButton(run).setId("widget-confirm");
    if (dialog.showAndWait().orElse(ButtonType.CANCEL) != run) return;
    execute(v, a, save.isSelected());
  }

  /** After the confirmation: apply the draft if it differs, invoke the action, read back. */
  void execute(WidgetView v, WidgetTemplate.Action a, boolean persist) {
    if (!online()) throw new IllegalStateException(Tr.of("widget.needDevice"));
    Project before = deviceLink.baseline.copy(), after = history.get().copy();
    List<String> steps = deviceLink.session.plan(before, after);
    Path journal =
        Paths.get(
            System.getProperty("studio.data"),
            "operations",
            java.time.LocalDateTime.now().toString().replace(':', '-'));
    String nodeId = v.nodeId;
    liveValues.pause();
    deviceLink.work(
        Tr.of("widget.running", v.t(a.label()), journal),
        () -> {
          if (!steps.isEmpty())
            deviceLink.session.apply(before, after, false, journal.resolve("apply"));
          return deviceLink.session.invoke(nodeId, a.invoke(), persist, journal.resolve("invoke"));
        },
        p -> {
          deviceConnection.acceptRead(p, true);
          notices.message(Tr.of("widget.done", v.t(a.label()), journal));
          refresh();
        });
  }

  public void closed(WidgetView v) {
    open.remove(v.nodeId, v);
  }
}
