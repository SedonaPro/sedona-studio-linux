package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import javafx.geometry.*;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.*;
import javafx.stage.Stage;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.KitUi;
import ru.bccontrol.sedonastudio.storage.SvgSymbol;
import ru.bccontrol.sedonastudio.storage.WidgetTemplate;
import sedona.Schema;
import sedona.Slot;
import sedona.Type;

/**
 * The window of one kit widget bound to one block: the "Setup" face (the component's options on the
 * picture, its information and actions) and the "Operation" face (the same picture with live values
 * and the controls). It only reads the draft and live values; every change goes through {@link
 * WidgetFeature} - into the draft, then to the device by Apply or by the widget's action.
 */
final class WidgetView {
  /** What a view needs from its feature. */
  interface Owner {
    Project project();

    /** The value to show: live when the block is on the device and unchanged, else the draft's. */
    String value(Project.Node n, String slot);

    /** The device's value, or null without a device snapshot. */
    String deviceValue(Project.Node n, String slot);

    boolean online();

    void setOption(String nodeId, String slot, String literal);

    void command(String nodeId, String slot, String literal);

    void action(WidgetView view, WidgetTemplate.Action action);

    void closed(WidgetView view);
  }

  String nodeId;
  /** Where the block sits ("/folder/name"): it keeps the widget on it when ids change. */
  final String path;

  final WidgetTemplate template;
  final KitUi.Kit kit;
  private final Owner owner;
  private final Schema schema;
  private final Type own;
  private final Map<String, SvgSymbol> symbols;
  private final String language, source;
  private final Stage stage = new Stage();
  private final Group canvas = new Group(), elementLayer = new Group(), valueLayer = new Group();
  private final Pane controlLayer = new Pane();
  private final Map<String, SvgFx.Drawn> drawn = new HashMap<>();
  private final Map<String, Runnable> optionUpdates = new LinkedHashMap<>();
  private final Map<String, Node> optionRows = new LinkedHashMap<>();
  private final List<Runnable> controlUpdates = new ArrayList<>();
  private final List<Button> actionButtons = new ArrayList<>();
  private final Label status = new Label(), pending = new Label();
  private final ToggleButton setup = new ToggleButton(Tr.of("widget.setup")),
      operation = new ToggleButton(Tr.of("widget.operation"));
  private final VBox side = new VBox(10);
  private List<String> focused = List.of();
  private boolean updating;

  WidgetView(
      Owner owner,
      String nodeId,
      KitUi.Kit kit,
      WidgetTemplate template,
      Schema schema,
      Map<String, SvgSymbol> symbols,
      String language,
      String source) {
    this.owner = owner;
    this.nodeId = nodeId;
    this.path = WidgetFeature.path(owner.project(), nodeId);
    this.kit = kit;
    this.template = template;
    this.schema = schema;
    this.own = schema.type(template.type);
    this.symbols = symbols;
    this.language = language;
    this.source = source;
  }

  String t(String english) {
    return english == null || english.isEmpty() ? "" : kit.text(language, english);
  }

  Stage stage() {
    return stage;
  }

  void show(Stage ownerWindow) {
    stage.initOwner(ownerWindow);
    ToggleGroup faces = new ToggleGroup();
    setup.setToggleGroup(faces);
    operation.setToggleGroup(faces);
    setup.setId("widget-setup");
    operation.setId("widget-operation");
    faces
        .selectedToggleProperty()
        .addListener(
            (o, a, b) -> {
              if (b == null) a.setSelected(true);
              else refresh();
            });
    Project.Node n = owner.project().nodes.get(nodeId);
    // A block that is not on the device yet is set up first; one that runs is watched first.
    (owner.deviceValue(n, "status") == null ? setup : operation).setSelected(true);
    Label from = new Label(source);
    from.setStyle("-fx-text-fill: #718391;");
    Region gap = new Region();
    HBox.setHgrow(gap, Priority.ALWAYS);
    HBox top = new HBox(8, setup, operation, gap, from);
    top.setAlignment(Pos.CENTER_LEFT);
    top.setPadding(new Insets(8, 12, 8, 12));

    Rectangle back = new Rectangle(template.width, template.height, Color.web("#fbfcfd"));
    back.setStroke(Color.web("#d7dde2"));
    back.setOnMouseClicked(e -> focus(List.of()));
    controlLayer.setPickOnBounds(false);
    canvas.getChildren().addAll(back, valueLayer, elementLayer, controlLayer);
    for (WidgetTemplate.Text text : template.texts) {
      Text label = new Text(text.x(), text.y() + 14, t(text.text()));
      label.setFont(Font.font(14));
      label.setFill(Color.web("#4f5b63"));
      label.setUserData(text);
      valueLayer.getChildren().add(label);
    }
    StackPane stageArea = new StackPane(new Group(canvas));
    stageArea.setMinSize(200, 150);
    stageArea.setPadding(new Insets(8));
    // Fit the picture into the window, keeping its proportions.
    javafx.beans.InvalidationListener fit =
        o -> {
          double s =
              Math.min(
                  Math.max(0.2, (stageArea.getWidth() - 16) / template.width),
                  Math.max(0.2, (stageArea.getHeight() - 16) / template.height));
          canvas.setScaleX(s);
          canvas.setScaleY(s);
        };
    stageArea.widthProperty().addListener(fit);
    stageArea.heightProperty().addListener(fit);
    buildControls();
    buildSide();
    ScrollPane sideScroll = new ScrollPane(side);
    sideScroll.setFitToWidth(true);
    sideScroll.setPrefWidth(330);
    sideScroll.setId("widget-side");
    status.setWrapText(true);
    pending.setWrapText(true);
    pending.setStyle("-fx-text-fill: #b26a00;");
    VBox bottom = new VBox(4, pending, status);
    bottom.setPadding(new Insets(6, 12, 8, 12));
    BorderPane root = new BorderPane(stageArea, top, sideScroll, bottom, null);
    root.setId("widget-window");
    sideScroll.visibleProperty().bind(setup.selectedProperty());
    sideScroll.managedProperty().bind(setup.selectedProperty());
    stage.setScene(new Scene(root, 1280, 760));
    stage.setTitle(t(template.title) + " · " + n.name);
    stage.setOnHidden(
        e -> {
          for (SvgFx.Drawn d : drawn.values()) d.stop();
          drawn.clear();
          owner.closed(this);
        });
    refresh();
    stage.show();
  }

  void toFront() {
    stage.show();
    stage.toFront();
  }

  void close() {
    stage.close();
  }

  boolean setupFace() {
    return setup.isSelected();
  }

  // ---------------------------------------------------------------- bindings

  /** The block a binding reads: the component itself or its child of that name and type. */
  private Project.Node target(Project p, String ref) {
    Project.Node n = p.nodes.get(nodeId);
    if (n == null || ref.indexOf('.') < 0) return n;
    String part = ref.substring(0, ref.indexOf('.'));
    List<String> types = template.parts.getOrDefault(part, List.of());
    for (Project.Node c : p.nodes.values())
      if (c.parent.equals(nodeId) && c.name.equals(part) && types.contains(c.type)) return c;
    return null;
  }

  private static String slotOf(String ref) {
    return ref.substring(ref.indexOf('.') + 1);
  }

  /** Ids of the blocks this widget shows (for live sampling). */
  List<String> watchedIds() {
    Project p = owner.project();
    List<String> ids = new ArrayList<>();
    if (p.nodes.containsKey(nodeId)) ids.add(nodeId);
    for (String part : template.boundParts()) {
      Project.Node c = target(p, part + ".x");
      if (c != null) ids.add(c.id);
    }
    return ids;
  }

  private String read(Project p, String ref) {
    Project.Node n = target(p, ref);
    if (n == null || n.port(slotOf(ref)) == null) return null;
    return owner.value(n, slotOf(ref));
  }

  private Slot slot(Project p, String ref) {
    Project.Node n = target(p, ref);
    Type type = n == null ? null : schema.type(n.type);
    return type == null ? null : type.slot(slotOf(ref));
  }

  private boolean when(Project p, String expr) {
    Project.Node n = p.nodes.get(nodeId);
    Map<String, String> values = new HashMap<>();
    for (String key : n.values.keySet()) values.put(key, owner.value(n, key));
    return WidgetTemplate.Condition.parse(expr).test(own, values);
  }

  private static boolean truthy(String v) {
    double d = WidgetTemplate.number(v);
    return !Double.isNaN(d) && d > 0;
  }

  /** A value for people: a range name, a number with its unit, on/off. */
  private String format(Slot slot, String v, int decimals, String unit) {
    if (v == null) return "—";
    if (slot != null) {
      List<String> range = WidgetTemplate.range(slot);
      double d = WidgetTemplate.number(v);
      if (!range.isEmpty() && !Double.isNaN(d) && d >= 0 && d < range.size())
        return t(range.get((int) d));
      if (slot.type.qname.endsWith("bool"))
        return v.equals("true") ? Tr.of("widget.on") : Tr.of("widget.off");
    }
    double d = WidgetTemplate.number(v);
    if (Double.isNaN(d) || v.equals("true") || v.equals("false")) return v.isBlank() ? "—" : v;
    String text = String.format(Locale.ROOT, "%." + decimals + "f", d);
    return unit.isEmpty() ? text : text + " " + unit;
  }

  // ---------------------------------------------------------------- building

  private void buildSide() {
    side.setPadding(new Insets(10, 12, 10, 12));
    side.setId("widget-options");
    Label title = new Label(Tr.of("widget.options"));
    title.setStyle("-fx-font-weight: bold;");
    side.getChildren().add(title);
    Project.Node comp = owner.project().nodes.get(nodeId);
    for (WidgetTemplate.Option o : template.options) {
      Slot s = own.slot(o.slot());
      Project.Port port = comp.port(o.slot());
      if (s == null || port == null) continue;
      Label label = new Label(t(o.label()));
      label.setWrapText(true);
      Node input;
      List<String> range = WidgetTemplate.range(s);
      if (!range.isEmpty()) {
        ComboBox<String> box = new ComboBox<>();
        for (String r : range) box.getItems().add(t(r));
        box.setMaxWidth(Double.MAX_VALUE);
        box.valueProperty()
            .addListener(
                (ob, a, b) -> {
                  int i = box.getSelectionModel().getSelectedIndex();
                  if (!updating && i >= 0) owner.setOption(nodeId, o.slot(), Integer.toString(i));
                });
        optionUpdates.put(
            o.slot(),
            () -> {
              double d =
                  WidgetTemplate.number(owner.project().nodes.get(nodeId).values.get(o.slot()));
              if (!Double.isNaN(d) && d >= 0 && d < box.getItems().size())
                box.getSelectionModel().select((int) d);
            });
        input = box;
      } else if (s.type.qname.endsWith("bool")) {
        CheckBox box = new CheckBox();
        box.selectedProperty()
            .addListener(
                (ob, a, b) -> {
                  if (!updating) owner.setOption(nodeId, o.slot(), Boolean.toString(b));
                });
        optionUpdates.put(
            o.slot(),
            () ->
                box.setSelected(
                    "true".equals(owner.project().nodes.get(nodeId).values.get(o.slot()))));
        input = box;
      } else {
        TextField field = new TextField();
        Runnable commit =
            () -> {
              if (!updating) owner.setOption(nodeId, o.slot(), field.getText().trim());
            };
        field.setOnAction(e -> commit.run());
        field
            .focusedProperty()
            .addListener(
                (ob, a, b) -> {
                  if (!b) commit.run();
                });
        optionUpdates.put(
            o.slot(),
            () -> {
              if (!field.isFocused())
                field.setText(owner.project().nodes.get(nodeId).values.getOrDefault(o.slot(), ""));
            });
        input = field;
      }
      if (input instanceof Control c) c.setId("widget-option-" + o.slot());
      Label mark = new Label();
      mark.setUserData("mark");
      HBox line = new HBox(6, input, mark);
      line.setAlignment(Pos.CENTER_LEFT);
      HBox.setHgrow(input, Priority.ALWAYS);
      VBox row = new VBox(3, label, line);
      row.setPadding(new Insets(4, 6, 4, 6));
      optionRows.put(o.slot(), row);
      side.getChildren().add(row);
    }
    if (!template.info.isEmpty()) {
      Label info = new Label(Tr.of("widget.info"));
      info.setStyle("-fx-font-weight: bold;");
      side.getChildren().add(info);
    }
    for (WidgetTemplate.Info i : template.info) {
      Label label = new Label(t(i.label()));
      Node value;
      Slot s = slot(owner.project(), i.bind());
      if (s != null && s.isAsStr()) {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(7);
        area.setStyle("-fx-font-family: monospace;");
        controlUpdates.add(
            () -> {
              String v = read(owner.project(), i.bind());
              String text = v == null ? "" : v.replace("\\n", "\n");
              if (!text.equals(area.getText())) area.setText(text);
            });
        value = area;
      } else {
        Label v = new Label();
        controlUpdates.add(
            () ->
                v.setText(
                    format(
                        slot(owner.project(), i.bind()), read(owner.project(), i.bind()), 0, "")));
        value = v;
      }
      value.setId("widget-info-" + i.bind().replace('.', '-'));
      side.getChildren().add(new VBox(3, label, value));
    }
    for (WidgetTemplate.Action a : template.actions) {
      Button b = new Button(t(a.label()) + "…");
      b.setId("widget-action-" + a.id());
      b.setMaxWidth(Double.MAX_VALUE);
      b.setOnAction(e -> owner.action(this, a));
      actionButtons.add(b);
      side.getChildren().add(b);
    }
  }

  /** Operation controls, placed on the picture where the template puts them. */
  private void buildControls() {
    for (WidgetTemplate.Control c : template.controls) {
      Label label = new Label(t(c.label()));
      label.setStyle("-fx-text-fill: #4f5b63;");
      Node input;
      String id = "widget-control-" + c.bind().replace('.', '-');
      switch (c.kind()) {
        case "mode" -> {
          ComboBox<String> box = new ComboBox<>();
          box.setId(id);
          box.valueProperty()
              .addListener(
                  (ob, a, b) -> {
                    int i = box.getSelectionModel().getSelectedIndex();
                    Project.Node n = target(owner.project(), c.bind());
                    if (!updating && i >= 0 && n != null)
                      owner.command(n.id, slotOf(c.bind()), Integer.toString(i));
                  });
          controlUpdates.add(
              () -> {
                Slot s = slot(owner.project(), c.bind());
                List<String> range = s == null ? List.of() : WidgetTemplate.range(s);
                if (box.getItems().size() != range.size()) {
                  box.getItems().clear();
                  for (String r : range) box.getItems().add(t(r));
                }
                if (box.isShowing()) return;
                double d = WidgetTemplate.number(read(owner.project(), c.bind()));
                if (!Double.isNaN(d) && d >= 0 && d < box.getItems().size())
                  box.getSelectionModel().select((int) d);
              });
          input = box;
        }
        case "setpoint" -> {
          TextField field = new TextField();
          field.setPrefColumnCount(5);
          field.setId(id);
          Button set = new Button(Tr.of("widget.set"));
          set.setOnAction(
              e -> {
                Project.Node n = target(owner.project(), c.bind());
                if (n != null) owner.command(n.id, slotOf(c.bind()), field.getText().trim());
              });
          field.setOnAction(e -> set.fire());
          controlUpdates.add(
              () -> {
                if (!field.isFocused()) {
                  String v = read(owner.project(), c.bind());
                  double d = WidgetTemplate.number(v);
                  field.setText(Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.1f", d));
                }
              });
          input = new HBox(4, field, set, new Label(c.unit()));
        }
        case "toggle" -> {
          CheckBox box = new CheckBox();
          box.setId(id);
          box.selectedProperty()
              .addListener(
                  (ob, a, b) -> {
                    Project.Node n = target(owner.project(), c.bind());
                    if (!updating && n != null)
                      owner.command(n.id, slotOf(c.bind()), Boolean.toString(b));
                  });
          controlUpdates.add(() -> box.setSelected(truthy(read(owner.project(), c.bind()))));
          input = box;
        }
        default -> {
          Label value = new Label();
          value.setId(id);
          value.setStyle("-fx-font-weight: bold;");
          controlUpdates.add(
              () ->
                  value.setText(
                      format(
                          slot(owner.project(), c.bind()),
                          read(owner.project(), c.bind()),
                          1,
                          c.unit())));
          input = value;
        }
      }
      VBox box = new VBox(2, label, input);
      box.setLayoutX(c.x());
      box.setLayoutY(c.y());
      box.setUserData(c);
      controlLayer.getChildren().add(box);
    }
  }

  // ---------------------------------------------------------------- refresh

  /** Redraw from the draft and the latest values. */
  void refresh() {
    Project p = owner.project();
    Project.Node comp = p.nodes.get(nodeId);
    if (comp == null || !comp.type.equals(template.type)) {
      status.setText(Tr.of("widget.gone"));
      canvas.setDisable(true);
      side.setDisable(true);
      return;
    }
    updating = true;
    try {
      boolean setupFace = setup.isSelected();
      drawElements(p, setupFace);
      for (Node n : valueLayer.getChildren())
        if (n.getUserData() instanceof WidgetTemplate.Text text) n.setVisible(when(p, text.when()));
      for (Node n : controlLayer.getChildren())
        if (n.getUserData() instanceof WidgetTemplate.Control c) {
          boolean show = !setupFace && when(p, c.when()) && target(p, c.bind()) != null;
          n.setVisible(show);
          n.setDisable(!owner.online() && !c.kind().equals("status"));
        }
      for (Runnable r : optionUpdates.values()) r.run();
      for (Runnable r : controlUpdates) r.run();
      List<String> changed = new ArrayList<>();
      for (WidgetTemplate.Option o : template.options) {
        Node row = optionRows.get(o.slot());
        if (row == null) continue;
        boolean applies = when(p, o.when());
        row.setVisible(applies);
        row.setManaged(applies);
        String device = owner.deviceValue(comp, o.slot());
        boolean differs = device != null && !device.equals(comp.values.get(o.slot()));
        if (differs) changed.add(t(o.label()));
        row.setStyle(
            focused.contains(o.slot())
                ? "-fx-background-color: #e3f0fb; -fx-background-radius: 4;"
                : "");
        for (Node x : ((Pane) ((VBox) row).getChildren().get(1)).getChildren())
          if ("mark".equals(x.getUserData())) ((Label) x).setText(differs ? "●" : "");
      }
      pending.setText(changed.isEmpty() ? "" : Tr.of("widget.pending", String.join(", ", changed)));
      for (Button b : actionButtons) b.setDisable(!owner.online());
      status.setText(owner.online() ? "" : Tr.of("widget.offline"));
    } finally {
      updating = false;
    }
  }

  private void drawElements(Project p, boolean setupFace) {
    List<Node> nodes = new ArrayList<>();
    List<Node> values = new ArrayList<>();
    Set<String> used = new HashSet<>();
    for (WidgetTemplate.Element e : template.elements) {
      boolean shown = when(p, e.when());
      boolean ghost = !shown && e.ghost() && setupFace;
      if (!shown && !ghost) continue;
      String state = "off";
      if (shown && !e.fault().isEmpty() && truthy(read(p, e.fault()))) state = "fault";
      else if (shown && !e.state().isEmpty() && truthy(read(p, e.state()))) state = "on";
      Map<String, String> files = template.symbols.getOrDefault(e.symbol(), Map.of());
      String file = files.getOrDefault(state, files.getOrDefault("off", ""));
      String key = e.id() + "|" + file;
      used.add(key);
      SvgFx.Drawn d = drawn.get(key);
      if (d == null) {
        SvgSymbol symbol = symbols.get(file);
        d =
            symbol == null
                ? new SvgFx.Drawn(placeholder(e), List.of())
                : SvgFx.draw(symbol, e.w(), e.h());
        drawn.put(key, d);
      }
      Group place = new Group(d.node());
      place.setLayoutX(e.x());
      place.setLayoutY(e.y());
      place.setOpacity(ghost ? 0.28 : 1);
      if (ghost || (setupFace && focused.size() > 0 && focused.equals(e.configure()))) {
        Rectangle outline = new Rectangle(-3, -3, e.w() + 6, e.h() + 6);
        outline.setFill(null);
        outline.setStroke(Color.web(ghost ? "#718391" : "#2f80c4"));
        outline.getStrokeDashArray().setAll(6.0, 4.0);
        place.getChildren().add(outline);
      }
      place.setId("widget-element-" + e.id());
      String tip = t(e.label());
      if (!tip.isEmpty())
        Tooltip.install(place, new Tooltip(ghost ? Tr.of("widget.ghostTip", tip) : tip));
      if (setupFace && !e.configure().isEmpty()) {
        place.setCursor(javafx.scene.Cursor.HAND);
        place.setOnMouseClicked(ev -> focus(e.configure()));
      } else place.setOnMouseClicked(null);
      nodes.add(place);
      if (!shown) continue;
      for (WidgetTemplate.Value v : e.values()) {
        if (!when(p, v.when())) continue;
        String text = format(slot(p, v.bind()), read(p, v.bind()), v.decimals(), v.unit());
        if (!v.label().isEmpty()) text = t(v.label()) + " " + text;
        Text label = new Text(text);
        label.setFont(Font.font(13));
        label.setFill(Color.web("#1f2a33"));
        label.setTextOrigin(VPos.TOP);
        label.setX(e.x() + e.w() / 2 + v.dx() - label.getLayoutBounds().getWidth() / 2);
        label.setY(e.y() + v.dy());
        values.add(label);
      }
    }
    for (var it = drawn.entrySet().iterator(); it.hasNext(); ) {
      var entry = it.next();
      if (!used.contains(entry.getKey())) {
        entry.getValue().stop();
        it.remove();
      }
    }
    elementLayer.getChildren().setAll(nodes);
    elementLayer.getChildren().addAll(values);
  }

  private static Node placeholder(WidgetTemplate.Element e) {
    Rectangle r = new Rectangle(e.w(), e.h(), Color.web("#eef1f3"));
    r.setStroke(Color.web("#c3cbd1"));
    return r;
  }

  /** Setup face: highlight the options an element is configured with. */
  void focus(List<String> slots) {
    focused = List.copyOf(slots);
    if (!slots.isEmpty()) {
      Node row = optionRows.get(slots.get(0));
      if (row != null) row.requestFocus();
    }
    refresh();
  }

  List<String> focusedOptions() {
    return focused;
  }
}
