package ru.bccontrol.sedonastudio.ui;

import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;

/** Scene-graph view. Domain mutations and transport access belong to the host controller. */
public final class DiagramPane extends Pane {
  public interface Host {
    Project project();

    default String nodeDelta(String id) {
      return "";
    }

    default String wireDelta(Project.Wire wire) {
      return "";
    }

    String folder();

    String selected();

    Project.Wire selectedWire();

    double zoom();

    boolean snap();

    boolean showValues();

    boolean avoidOverlap();

    String display(Project.Node n, String port);

    default ValueState valueState(Project.Node n, String port) {
      return ValueState.LOCAL;
    }

    /** What the block's type is for, to show on the block; empty when unknown. */
    default String help(Project.Node n) {
      return "";
    }

    /** The user dragged the block's right edge to this width. */
    default void resize(String id, double width) {}

    /** The user wrote a comment on the block (empty = removed it). */
    default void comment(String id, String text) {}

    /** The user picked an icon for the block (empty = none). */
    default void icon(String id, String icon) {}

    /** Captions to draw: those of the open folder. */
    default java.util.List<Project.Caption> captions() {
      return java.util.List.of();
    }

    /** A new empty caption at this place of the open folder; returns its id, or null if refused. */
    default String addCaption(double x, double y) {
      return null;
    }

    /** One undoable change to a caption. */
    default void changeCaption(String id, java.util.function.Consumer<Project.Caption> change) {}

    default void removeCaption(String id) {}

    default String quality(Project.Node n, String port) {
      return Tr.of("common.project");
    }

    void select(String id);

    void select(String id, boolean additive);

    java.util.Set<String> selection();

    boolean highlighted();

    void selectGroup(java.util.Set<String> ids);

    void moveGroup(java.util.Map<String, double[]> positions);

    void selectWire(Project.Wire wire);

    default String signal(Project.Wire wire) {
      return wire.key();
    }

    void navigateWire(Project.Wire wire, boolean source);

    void move(String id, double x, double y);

    void connect(String a, String ap, String b, String bp);

    void bend(Project.Wire wire, int index, double x, double y);

    void clearBends(Project.Wire wire);

    void openFolder(String id);

    void duplicate(String id);

    void delete(String id);

    void message(String text);

    void error(Exception e);
  }

  private final Host host;
  private final Pane wires = new Pane(), nodes = new Pane(), captions = new Pane();
  private String source, sourcePort;
  private final java.util.Map<String, Node> anchors = new java.util.HashMap<>();
  private Path preview;
  private final java.util.Map<Project.Wire, Tooltip> wireTips = new java.util.HashMap<>();

  private record WireVisual(Project.Wire wire, java.util.List<Node> shapes, Color color) {}

  private record FastWire(
      Project.Wire wire,
      MoveTo first,
      LineTo[] points,
      double[] fixedSource,
      double[] fixedTarget) {}

  private final java.util.Map<String, java.util.List<WireVisual>> adjacent =
      new java.util.HashMap<>();
  private final java.util.List<FastWire> fastWires = new java.util.ArrayList<>();
  private final Pane dragOverlay = new Pane();
  private String dragging;
  private java.util.Set<String> draggedIds = java.util.Set.of();
  private final java.util.Set<WireVisual> activeVisuals = new java.util.LinkedHashSet<>();
  private Rectangle marquee;
  private Point2D marqueeStart;
  private boolean keepMarquee, ignoreClick;
  private boolean previewDirty;
  private long routePasses, previewPasses;
  private final javafx.animation.AnimationTimer dragTimer =
      new javafx.animation.AnimationTimer() {
        public void handle(long now) {
          updateFastWires();
        }
      };

  public long routePasses() {
    return routePasses;
  }

  public long previewPasses() {
    return previewPasses;
  }

  private void beginFastDrag(String id) {
    previewDirty = true;
    if (id.equals(dragging)) return;
    endFastDrag();
    dragging = id;
    previewDirty = true;
    draggedIds = host.selection().contains(id) ? host.selection() : java.util.Set.of(id);
    for (String member : draggedIds)
      activeVisuals.addAll(adjacent.getOrDefault(member, java.util.List.of()));
    for (WireVisual visual : activeVisuals) {
      for (Node shape : visual.shapes) shape.setVisible(false);
      MoveTo first = new MoveTo();
      LineTo[] lines = {new LineTo(), new LineTo(), new LineTo(), new LineTo(), new LineTo()};
      Path path = new Path(first);
      path.getElements().addAll(lines);
      path.setFill(null);
      path.setStroke(visual.color);
      path.setStrokeWidth(1.8);
      path.getStrokeDashArray().addAll(5.0, 3.0);
      path.setMouseTransparent(true);
      dragOverlay.getChildren().add(path);
      fastWires.add(
          new FastWire(
              visual.wire,
              first,
              lines,
              point(visual.wire.source, visual.wire.sourcePort, true),
              point(visual.wire.target, visual.wire.targetPort, false)));
    }
    dragTimer.start();
  }

  private void endFastDrag() {
    dragTimer.stop();
    if (dragging != null)
      for (WireVisual visual : activeVisuals)
        for (Node shape : visual.shapes) shape.setVisible(true);
    dragging = null;
    draggedIds = java.util.Set.of();
    activeVisuals.clear();
    fastWires.clear();
    dragOverlay.getChildren().clear();
  }

  public int dragPreviewCount() {
    return fastWires.size();
  }

  public void updateFastWires() {
    if (dragging == null || !previewDirty) return;
    previewDirty = false;
    previewPasses++;
    for (FastWire fast : fastWires) {
      Project.Wire w = fast.wire;
      double[]
          a =
              draggedIds.contains(w.source)
                  ? point(w.source, w.sourcePort, true)
                  : fast.fixedSource,
          b =
              draggedIds.contains(w.target)
                  ? point(w.target, w.targetPort, false)
                  : fast.fixedTarget;
      if (a == null || b == null) continue;
      fast.first.setX(a[0]);
      fast.first.setY(a[1]);
      double exit, entry, y;
      if (b[0] > a[0] + 48) {
        exit = entry = (a[0] + b[0]) / 2;
        y = b[1];
      } else {
        exit = a[0] + 24;
        entry = b[0] - 24;
        y = Math.max(a[1], b[1]) + 24;
      }
      setPoint(fast.points[0], exit, a[1]);
      setPoint(fast.points[1], exit, y);
      setPoint(fast.points[2], entry, y);
      setPoint(fast.points[3], entry, b[1]);
      setPoint(fast.points[4], b[0], b[1]);
    }
  }

  private static void setPoint(LineTo point, double x, double y) {
    point.setX(x);
    point.setY(y);
  }

  private boolean wiring, wireDragged;

  private double snap(double value) {
    return host.snap() ? Math.round(value / 12) * 12 : Math.round(value);
  }

  private String key(String id, String port, boolean output) {
    return id + ":" + port + ":" + output;
  }

  public void cancelWire() {
    source = null;
    sourcePort = null;
    wiring = false;
    if (preview != null) getChildren().remove(preview);
    preview = null;
  }

  private void finishWire(MouseEvent e) {
    if (!wiring) return;
    wiring = false;
    String a = source, ap = sourcePort;
    source = null;
    sourcePort = null;
    if (preview != null) getChildren().remove(preview);
    preview = null;
    for (Project.Node n : host.project().nodes.values()) {
      if (!n.parent.equals(host.folder())) continue;
      for (Project.Port p : n.ports) {
        Node anchor = anchors.get(key(n.id, p.name, false));
        if (anchor == null || !p.input || p.readonly) continue;
        Point2D point = anchor.sceneToLocal(e.getSceneX(), e.getSceneY());
        if (Math.abs(point.getX()) <= 14 && Math.abs(point.getY()) <= 14) {
          attempt(() -> host.connect(a, ap, n.id, p.name));
          e.consume();
          return;
        }
      }
    }
    host.message(Tr.of("diagram.wireCancelled"));
    e.consume();
  }

  private final java.util.Map<String, java.util.Map<String, Label>> valueLabels =
      new java.util.HashMap<>();

  public DiagramPane(Host host) {
    this.host = host;
    setPrefSize(3600, 3600);
    getStyleClass().add("sheet");
    wires.setPickOnBounds(false);
    nodes.setPickOnBounds(false);
    dragOverlay.setMouseTransparent(true);
    dragOverlay.setPickOnBounds(false);
    captions.setPickOnBounds(false);
    // Captions lie under the links and the blocks.
    getChildren().addAll(captions, wires, dragOverlay, nodes);
    ContextMenu sheetMenu = new ContextMenu();
    MenuItem newCaption = new MenuItem(Tr.of("caption.add"));
    final double[] at = new double[2];
    newCaption.setOnAction(
        e ->
            attempt(
                () -> {
                  String id = host.addCaption(snap(at[0]), snap(at[1]));
                  if (id != null) editCaption(id);
                }));
    sheetMenu.getItems().add(newCaption);
    setOnContextMenuRequested(
        e -> {
          if (e.getTarget() != this) return;
          Point2D point = sceneToLocal(e.getSceneX(), e.getSceneY());
          at[0] = point.getX();
          at[1] = point.getY();
          sheetMenu.show(this, e.getScreenX(), e.getScreenY());
          e.consume();
        });
    setOnMouseClicked(
        e -> {
          if (ignoreClick) {
            ignoreClick = false;
            e.consume();
            return;
          }
          if (e.getTarget() == this) {
            host.select(null);
            source = null;
            sourcePort = null;
          }
        });
    setOnMousePressed(
        e -> {
          if (e.getButton() != MouseButton.PRIMARY || e.getTarget() != this) return;
          requestFocus();
          marqueeStart = sceneToLocal(e.getSceneX(), e.getSceneY());
          keepMarquee = e.isShiftDown() || e.isControlDown();
          marquee = new Rectangle();
          marquee.setFill(Color.web("#4099b4", .12));
          marquee.setStroke(Color.web("#4099b4"));
          marquee.setMouseTransparent(true);
          getChildren().add(marquee);
          e.consume();
        });
    setOnMouseDragged(
        e -> {
          if (marquee == null) return;
          Point2D end = sceneToLocal(e.getSceneX(), e.getSceneY());
          marquee.setX(Math.min(end.getX(), marqueeStart.getX()));
          marquee.setY(Math.min(end.getY(), marqueeStart.getY()));
          marquee.setWidth(Math.abs(end.getX() - marqueeStart.getX()));
          marquee.setHeight(Math.abs(end.getY() - marqueeStart.getY()));
          e.consume();
        });
    setOnMouseReleased(
        e -> {
          if (marquee == null) return;
          java.util.Set<String> ids =
              new java.util.LinkedHashSet<>(keepMarquee ? host.selection() : java.util.Set.of());
          for (Node box : nodes.getChildren())
            if (marquee.getBoundsInLocal().intersects(box.localToParent(box.getLayoutBounds())))
              ids.add((String) box.getUserData());
          ignoreClick = marquee.getWidth() > 3 || marquee.getHeight() > 3;
          getChildren().remove(marquee);
          marquee = null;
          host.selectGroup(ids);
          selectionStyles();
          e.consume();
        });
    setFocusTraversable(true);
  }

  public void selectionStyles() {
    java.util.Set<String> selected = host.selection();
    java.util.Set<String> path =
        host.highlighted() && host.selected() != null
            ? ru.bccontrol.sedonastudio.model.EditorCommands.signalPath(
                host.project(), host.selected())
            : java.util.Set.of();
    for (Node box : nodes.getChildren()) {
      if (selected.contains((String) box.getUserData())) {
        if (!box.getStyleClass().contains("selected-block"))
          box.getStyleClass().add("selected-block");
      } else box.getStyleClass().remove("selected-block");
      box.setOpacity(path.isEmpty() || path.contains((String) box.getUserData()) ? 1 : .35);
    }
    java.util.Set<WireVisual> visuals = new java.util.HashSet<>();
    for (var list : adjacent.values()) visuals.addAll(list);
    for (WireVisual visual : visuals)
      for (Node shape : visual.shapes) {
        shape.setOpacity(
            path.isEmpty() || path.contains(visual.wire.source) && path.contains(visual.wire.target)
                ? 1
                : .15);
        boolean active =
            host.selectedWire() != null && host.selectedWire().key().equals(visual.wire.key());
        if (shape instanceof Path stroke && !shape.getStyleClass().contains("wire-hit")) {
          stroke.setStroke(active ? Color.web("#e58b26") : visual.color);
          if (shape.getStyleClass().contains("wire-line")) stroke.setStrokeWidth(active ? 3 : 1.8);
        }
      }
  }

  private void attempt(Runnable action) {
    try {
      action.run();
    } catch (Exception e) {
      host.error(e);
    }
  }

  public void render() {
    endFastDrag();
    cancelWire();
    anchors.clear();
    nodes.getChildren().clear();
    valueLabels.clear();
    captions.getChildren().clear();
    for (Project.Caption c : host.captions()) captions.getChildren().add(caption(c));
    for (Project.Node n : host.project().nodes.values())
      if (!n.id.equals("0") && n.parent.equals(host.folder())) nodes.getChildren().add(block(n));
    applyCss();
    layout();
    nodes.layout();
    if (host.avoidOverlap()) separateBlocks();
    updateExtent();
    renderWires();
    selectionStyles();
  }

  public java.util.Map<String, Bounds> blockBounds() {
    java.util.Map<String, Bounds> result = new java.util.LinkedHashMap<>();
    for (Node box : nodes.getChildren())
      result.put((String) box.getUserData(), box.localToParent(box.getLayoutBounds()));
    return result;
  }

  private void separateBlocks() {
    java.util.List<Node> sorted = new java.util.ArrayList<>(nodes.getChildren());
    sorted.sort(
        java.util.Comparator.comparingDouble(Node::getLayoutY)
            .thenComparingDouble(Node::getLayoutX)
            .thenComparing(n -> (String) n.getUserData()));
    java.util.List<Bounds> placed = new java.util.ArrayList<>();
    for (Node box : sorted) {
      box.relocate(Math.max(60, box.getLayoutX()), Math.max(24, box.getLayoutY()));
      double ox = box.getLayoutX(), oy = box.getLayoutY();
      for (int attempt = 0; attempt < sorted.size() * 3; attempt++) {
        Bounds current = box.localToParent(box.getLayoutBounds()), collision = null;
        for (Bounds b : placed)
          if (current.getMinX() < b.getMaxX() + 48
              && current.getMaxX() + 48 > b.getMinX()
              && current.getMinY() < b.getMaxY() + 24
              && current.getMaxY() + 24 > b.getMinY()) {
            collision = b;
            break;
          }
        if (collision == null) break;
        double right = Math.ceil((collision.getMaxX() + 48) / 12) * 12,
            down = Math.ceil((collision.getMaxY() + 24) / 12) * 12;
        if (right <= 3060 && right - ox <= down - oy) box.relocate(right, box.getLayoutY());
        else box.relocate(box.getLayoutX(), down);
      }
      placed.add(box.localToParent(box.getLayoutBounds()));
    }
  }

  private void updateExtent() {
    double w = 3600, h = 3600;
    for (Bounds b : blockBounds().values()) {
      w = Math.max(w, b.getMaxX() + 180);
      h = Math.max(h, b.getMaxY() + 180);
    }
    for (Project.Caption c : host.captions()) {
      w = Math.max(w, c.x + c.width + 180);
      h = Math.max(h, c.y + c.height + 180);
    }
    setPrefSize(w, h);
    resize(w, h);
  }

  /**
   * One colour per kind of value, used for the port dots and for the links: logical, whole number,
   * real number, text or bytes, anything else.
   */
  static Color typeColor(String type) {
    return KIND_COLORS.get(kind(type));
  }

  /** The kinds of values that get their own colour: bool, whole, real, text, other. */
  static final String[] KINDS = {"bool", "whole", "real", "text", "other"};

  static final String[] DEFAULT_COLORS = {"#2f9e7f", "#4a86c5", "#d2872c", "#8a6fc0", "#7d8a96"};
  /** Colour of each kind; the settings dialog changes it. */
  static final java.util.Map<String, Color> KIND_COLORS = new java.util.HashMap<>();

  static {
    for (int i = 0; i < KINDS.length; i++) KIND_COLORS.put(KINDS[i], Color.web(DEFAULT_COLORS[i]));
  }

  static String kind(String type) {
    String name = type.substring(type.lastIndexOf(':') + 1);
    switch (name) {
      case "bool":
        return "bool";
      case "byte":
      case "short":
      case "int":
      case "long":
        return "whole";
      case "float":
      case "double":
        return "real";
      case "Buf":
      case "Str":
        return "text";
      default:
        return "other";
    }
  }

  static final double MIN_WIDTH = 156, MAX_WIDTH = 960;

  private static String deltaColor(String delta) {
    return delta.equals("added") ? "#23816b" : delta.equals("removed") ? "#b44444" : "#b37718";
  }

  private boolean hasChildren(Project.Node n) {
    for (Project.Node c : host.project().nodes.values()) if (c.parent.equals(n.id)) return true;
    return false;
  }

  private Node block(Project.Node n) {
    VBox box = new VBox();
    box.getStyleClass().add("block");
    String delta = host.nodeDelta(n.id);
    if (!delta.isEmpty()) {
      box.getStyleClass().add("review-" + delta);
      if (!delta.equals("same")) box.setStyle("-fx-border-color: " + deltaColor(delta) + ";");
    }
    if (host.selection().contains(n.id)) box.getStyleClass().add("selected-block");
    double width = n.width > 0 ? Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, n.width)) : 220;
    box.setMinWidth(width);
    box.setPrefWidth(width);
    box.setMaxWidth(width);
    box.setId("block-" + n.id);
    box.relocate(n.x, n.y);
    box.setUserData(n.id);
    Label name = new Label(n.name);
    name.getStyleClass().add("block-title");
    name.setId("title-" + n.id);
    // The icon stands before the name; a click on it offers another one.
    Label icon = new Label(n.icon);
    icon.getStyleClass().add("block-title");
    icon.setId("icon-" + n.id);
    icon.setVisible(!n.icon.isEmpty());
    icon.setManaged(!n.icon.isEmpty());
    icon.setCursor(javafx.scene.Cursor.HAND);
    icon.setOnMouseClicked(
        e -> {
          iconMenu(n).show(icon, e.getScreenX(), e.getScreenY());
          e.consume();
        });
    icon.setOnMousePressed(e -> e.consume());
    Label type = new Label(n.type);
    type.getStyleClass().add("block-type");
    if (!n.note.isBlank()) {
      Tooltip.install(box, new Tooltip(n.note));
      name.setText(name.getText() + " ✎");
    }
    HBox titleRow = new HBox(5, icon, name);
    VBox header = new VBox(3, titleRow, type);
    header.setPadding(new Insets(9, 12, 9, 12));
    header.setMinHeight(59);
    header.setPrefHeight(59);
    header.setMaxHeight(59);
    // A grip on the right edge of the header changes the block's width.
    Region grip = new Region();
    grip.setMinWidth(7);
    grip.setPrefWidth(7);
    grip.setCursor(javafx.scene.Cursor.H_RESIZE);
    grip.setId("grip-" + n.id);
    HBox.setHgrow(header, Priority.ALWAYS);
    box.getChildren().add(new HBox(header, grip));
    final double[] resize = new double[2];
    grip.setOnMousePressed(
        e -> {
          resize[0] = e.getSceneX();
          resize[1] = box.getPrefWidth();
          e.consume();
        });
    grip.setOnMouseDragged(
        e -> {
          double scale = Math.max(.05, host.zoom());
          double wanted = resize[1] + (e.getSceneX() - resize[0]) / scale;
          double snapped = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Math.round(wanted / 12) * 12));
          box.setMinWidth(snapped);
          box.setPrefWidth(snapped);
          box.setMaxWidth(snapped);
          e.consume();
        });
    grip.setOnMouseReleased(
        e -> {
          e.consume();
          if (box.getPrefWidth() != resize[1]) attempt(() -> host.resize(n.id, box.getPrefWidth()));
        });
    if (n.info) {
      // The note when there is one, otherwise what the type's author wrote about it.
      String text = n.note.isBlank() ? host.help(n) : n.note;
      if (text != null && !text.isBlank()) {
        Label info = new Label(text);
        info.setWrapText(true);
        info.setId("info-" + n.id);
        info.setPadding(new Insets(0, 12, 8, 12));
        info.setStyle("-fx-font-size: 11px; -fx-text-fill: #5b6a78;");
        info.setMaxWidth(width);
        // Written right here: a double click turns the text into an editor.
        info.setOnMouseClicked(
            e -> {
              if (e.getClickCount() == 2) {
                editComment(n.id);
                e.consume();
              }
            });
        box.getChildren().add(info);
      }
    }
    installBlockDrag(box, n);
    header.setOnMouseClicked(
        e -> {
          // A double click on the header opens a folder or any block with blocks inside (a
          // configurator), as the tree does.
          if (e.getClickCount() == 2 && (n.type.equals("sys::Folder") || hasChildren(n)))
            host.openFolder(n.id);
        });
    for (Project.Port p : n.ports) box.getChildren().add(portRow(n, p));
    installBlockMenu(box, n);
    return box;
  }

  /** Dragging a block moves the whole selection; the move is committed on release. */
  private void installBlockDrag(VBox box, Project.Node n) {
    final double[] drag = new double[4];
    final java.util.Map<String, Point2D> starts = new java.util.LinkedHashMap<>();
    box.setOnMousePressed(
        e -> {
          if (e.getButton() != MouseButton.PRIMARY) return;
          requestFocus();
          if (e.isControlDown() || e.isShiftDown()) host.select(n.id, true);
          else if (!host.selection().contains(n.id)) host.select(n.id, false);
          selectionStyles();
          starts.clear();
          for (Node member : nodes.getChildren())
            if (host.selection().contains((String) member.getUserData()))
              starts.put(
                  (String) member.getUserData(),
                  new Point2D(member.getLayoutX(), member.getLayoutY()));
          Point2D local = sceneToLocal(e.getSceneX(), e.getSceneY());
          drag[0] = local.getX();
          drag[1] = local.getY();
          drag[2] = box.getLayoutX();
          drag[3] = box.getLayoutY();
          e.consume();
        });
    box.setOnMouseDragged(
        e -> {
          if (!e.isPrimaryButtonDown()) return;
          Point2D local = sceneToLocal(e.getSceneX(), e.getSceneY());
          double dx = local.getX() - drag[0], dy = local.getY() - drag[1];
          for (Point2D origin : starts.values()) {
            dx = Math.max(dx, -origin.getX());
            dx = Math.min(dx, 3060 - origin.getX());
            dy = Math.max(dy, -origin.getY());
            dy = Math.min(dy, 3060 - origin.getY());
          }
          for (Node member : nodes.getChildren()) {
            Point2D origin = starts.get((String) member.getUserData());
            if (origin != null) member.relocate(origin.getX() + dx, origin.getY() + dy);
          }
          beginFastDrag(n.id);
          e.consume();
        });
    box.setOnMouseReleased(
        e -> {
          if (e.getButton() != MouseButton.PRIMARY) return;
          e.consume();
          boolean moved = dragging != null;
          endFastDrag();
          if (!moved) return;
          double dx = snap(box.getLayoutX()) - drag[2], dy = snap(box.getLayoutY()) - drag[3];
          java.util.Map<String, double[]> positions = new java.util.LinkedHashMap<>();
          for (var entry : starts.entrySet())
            positions.put(
                entry.getKey(),
                new double[] {entry.getValue().getX() + dx, entry.getValue().getY() + dy});
          attempt(() -> host.moveGroup(positions));
        });
  }

  /** One port of a block: input and output anchors for wiring, the name and the value. */
  private HBox portRow(Project.Node n, Project.Port p) {
    // Colour says the type; shape says the direction: a filled dot takes a link, a ring gives one.
    Color kind = typeColor(p.type);
    Circle input = new Circle(4, p.input ? kind : Color.TRANSPARENT),
        output = new Circle(4, Color.WHITE);
    output.setStroke(kind);
    output.setStrokeWidth(2.5);
    output.setStrokeType(javafx.scene.shape.StrokeType.INSIDE);
    anchors.put(key(n.id, p.name, false), input);
    anchors.put(key(n.id, p.name, true), output);
    Tooltip.install(output, new Tooltip(Tr.of("diagram.outputTip", p.name, p.type)));
    Tooltip.install(
        input,
        new Tooltip(
            p.name
                + " · "
                + p.type
                + (p.input && !p.readonly
                    ? Tr.of("diagram.inputTip")
                    : Tr.of("diagram.inputUnavailable"))));
    input.setId("input-" + n.id + "-" + p.name);
    output.setId("output-" + n.id + "-" + p.name);
    input.setRadius(7);
    output.setRadius(7);
    input.setOnMousePressed(e -> e.consume());
    input.setOnMouseReleased(this::finishWire);
    output.setOnMousePressed(
        e -> {
          if (e.getButton() != MouseButton.PRIMARY) return;
          source = n.id;
          sourcePort = p.name;
          wiring = true;
          wireDragged = false;
          preview = new Path();
          preview.setStroke(typeColor(p.type));
          preview.setStrokeWidth(3);
          preview.setMouseTransparent(true);
          preview.setFill(null);
          getChildren().add(preview);
          e.consume();
        });
    output.setOnMouseDragged(
        e -> {
          if (!wiring) return;
          wireDragged = true;
          double[] a = point(n.id, p.name, true);
          Point2D b = sceneToLocal(e.getSceneX(), e.getSceneY());
          if (a != null)
            preview.getElements().setAll(new MoveTo(a[0], a[1]), new LineTo(b.getX(), b.getY()));
          e.consume();
        });
    output.setOnMouseReleased(this::finishWire);
    input.setOnMouseClicked(
        e -> {
          if (source != null) {
            String a = source, ap = sourcePort;
            source = null;
            sourcePort = null;
            attempt(() -> host.connect(a, ap, n.id, p.name));
          }
          e.consume();
        });
    output.setOnMouseClicked(
        e -> {
          if (wireDragged) {
            wireDragged = false;
            e.consume();
            return;
          }
          source = n.id;
          sourcePort = p.name;
          host.message(Tr.of("diagram.chooseInput", n.name, p.name));
          e.consume();
        });
    Label field = new Label(p.name), value = new Label(host.display(n, p.name));
    field.setMinWidth(0);
    field.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(field, Priority.ALWAYS);
    Tooltip.install(field, new Tooltip(p.name + " · " + p.type));
    value.setMinWidth(0);
    value.setPrefWidth(82);
    value.setMaxWidth(82);
    value.setAlignment(Pos.CENTER_RIGHT);
    Tooltip.install(value, new Tooltip(n.values.getOrDefault(p.name, "—")));
    value.getStyleClass().add("field-value");
    value.setVisible(host.showValues());
    value.setManaged(host.showValues());
    valueLabels.computeIfAbsent(n.id, id -> new java.util.HashMap<>()).put(p.name, value);
    Region spacer = new Region();
    spacer.setMaxWidth(0);
    HBox row = new HBox(6, input, field, spacer, value, output);
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(4, 8, 4, 8));
    row.setMinHeight(24);
    row.setPrefHeight(24);
    row.setMaxHeight(24);
    return row;
  }

  /**
   * A caption: free text on the sheet, or - as a frame - a titled outline around a group of blocks.
   * It is dragged by its text, widened by the grip on its right edge (a frame also at its bottom),
   * edited by a double click and has its own menu.
   */
  private Node caption(Project.Caption c) {
    Label text = new Label((c.icon.isEmpty() ? "" : c.icon + " ") + c.text);
    text.setId("caption-text-" + c.id);
    text.setWrapText(true);
    text.setPadding(new Insets(5, 9, 5, 9));
    if (c.text.isEmpty() && c.icon.isEmpty()) text.setText(Tr.of("caption.empty"));
    Region grip = new Region();
    grip.setId("caption-grip-" + c.id);
    final Region whole;
    // What "this wide and this high" means for the two kinds.
    final java.util.function.BiConsumer<Double, Double> sizeTo;
    if (c.frame) {
      // Only the title and the corner grip take the mouse: blocks inside the frame, and the
      // empty sheet between them, stay as reachable as without it.
      Pane holder = new Pane();
      holder.setPickOnBounds(false);
      Region outline = new Region();
      outline.setMouseTransparent(true);
      outline.setStyle(
          "-fx-border-color: #8a97a6; -fx-border-style: dashed; -fx-border-radius: 8;"
              + " -fx-background-color: rgba(120,140,170,0.07); -fx-background-radius: 8;");
      text.setStyle("-fx-font-weight: bold; -fx-text-fill: #46566a;");
      grip.setPrefSize(14, 14);
      grip.setStyle("-fx-background-color: #8a97a6; -fx-background-radius: 3; -fx-opacity: .7;");
      grip.setCursor(javafx.scene.Cursor.SE_RESIZE);
      sizeTo =
          (width, height) -> {
            outline.setPrefSize(width, height);
            text.setMaxWidth(Math.max(40, width - 24));
            grip.relocate(width - 15, height - 15);
          };
      holder.getChildren().addAll(outline, text, grip);
      whole = holder;
    } else {
      VBox box = new VBox();
      box.setStyle(
          "-fx-background-color: rgba(255,246,196,0.92); -fx-background-radius: 5;"
              + " -fx-border-color: #d9c66a; -fx-border-radius: 5;");
      text.setStyle("-fx-text-fill: #4a4320;");
      text.setMaxWidth(Double.MAX_VALUE);
      grip.setMinWidth(7);
      grip.setPrefWidth(7);
      grip.setCursor(javafx.scene.Cursor.H_RESIZE);
      HBox.setHgrow(text, Priority.ALWAYS);
      box.getChildren().add(new HBox(text, grip));
      sizeTo =
          (width, height) -> {
            box.setMinWidth(width);
            box.setPrefWidth(width);
            box.setMaxWidth(width);
          };
      whole = box;
    }
    whole.setId("caption-" + c.id);
    whole.relocate(c.x, c.y);
    sizeTo.accept(c.width, c.height);
    // Move: by the text.
    final double[] drag = new double[4];
    final boolean[] moved = {false};
    text.setOnMousePressed(
        e -> {
          if (e.getButton() != MouseButton.PRIMARY) return;
          Point2D local = sceneToLocal(e.getSceneX(), e.getSceneY());
          drag[0] = local.getX();
          drag[1] = local.getY();
          drag[2] = whole.getLayoutX();
          drag[3] = whole.getLayoutY();
          moved[0] = false;
          e.consume();
        });
    text.setOnMouseDragged(
        e -> {
          if (!e.isPrimaryButtonDown()) return;
          Point2D local = sceneToLocal(e.getSceneX(), e.getSceneY());
          whole.relocate(
              Math.max(0, drag[2] + local.getX() - drag[0]),
              Math.max(0, drag[3] + local.getY() - drag[1]));
          moved[0] = true;
          e.consume();
        });
    text.setOnMouseReleased(
        e -> {
          e.consume();
          if (!moved[0]) return;
          double x = snap(whole.getLayoutX()), y = snap(whole.getLayoutY());
          attempt(
              () ->
                  host.changeCaption(
                      c.id,
                      caption -> {
                        caption.x = x;
                        caption.y = y;
                      }));
        });
    text.setOnMouseClicked(
        e -> {
          if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !moved[0])
            editCaption(c.id);
          e.consume();
        });
    // Resize: by the grip.
    final double[] size = {0, 0, c.width, c.height};
    grip.setOnMousePressed(
        e -> {
          size[0] = e.getSceneX();
          size[1] = e.getSceneY();
          e.consume();
        });
    grip.setOnMouseDragged(
        e -> {
          double scale = Math.max(.05, host.zoom());
          size[2] =
              Math.max(
                  60,
                  Math.min(
                      3000, Math.round((c.width + (e.getSceneX() - size[0]) / scale) / 12) * 12));
          if (c.frame)
            size[3] =
                Math.max(
                    24,
                    Math.min(
                        3000,
                        Math.round((c.height + (e.getSceneY() - size[1]) / scale) / 12) * 12));
          sizeTo.accept(size[2], size[3]);
          e.consume();
        });
    grip.setOnMouseReleased(
        e -> {
          e.consume();
          double width = size[2], height = size[3];
          if (width != c.width || height != c.height)
            attempt(
                () ->
                    host.changeCaption(
                        c.id,
                        caption -> {
                          caption.width = width;
                          caption.height = height;
                        }));
        });
    ContextMenu menu = new ContextMenu();
    MenuItem edit = new MenuItem(Tr.of("caption.edit"));
    edit.setOnAction(e -> editCaption(c.id));
    Menu icons = new Menu(Tr.of("inspector.icon"));
    MenuItem none = new MenuItem(Tr.of("inspector.noIcon"));
    none.setOnAction(e -> attempt(() -> host.changeCaption(c.id, caption -> caption.icon = "")));
    icons.getItems().add(none);
    for (String symbol : ICONS) {
      MenuItem item = new MenuItem(symbol);
      item.setOnAction(
          e -> attempt(() -> host.changeCaption(c.id, caption -> caption.icon = symbol)));
      icons.getItems().add(item);
    }
    MenuItem frame = new MenuItem(c.frame ? Tr.of("caption.asText") : Tr.of("caption.asFrame"));
    frame.setOnAction(
        e -> attempt(() -> host.changeCaption(c.id, caption -> caption.frame = !caption.frame)));
    MenuItem remove = new MenuItem(Tr.of("sessions.remove"));
    remove.setOnAction(e -> attempt(() -> host.removeCaption(c.id)));
    menu.getItems().addAll(edit, icons, frame, new SeparatorMenuItem(), remove);
    javafx.event.EventHandler<javafx.scene.input.ContextMenuEvent> showMenu =
        e -> {
          menu.show(text, e.getScreenX(), e.getScreenY());
          e.consume();
        };
    text.setOnContextMenuRequested(showMenu);
    grip.setOnContextMenuRequested(showMenu);
    return whole;
  }

  /** Opens an editor in place of a caption's text. Ctrl+Enter or a click elsewhere keeps it. */
  void editCaption(String id) {
    Project.Caption c = host.project().caption(id);
    if (c == null
        || !(lookup("#caption-text-" + id) instanceof Label shown)
        || !(shown.getParent() instanceof Pane parent)) return;
    TextArea area = new TextArea(c.text);
    area.setId("caption-editor");
    area.setWrapText(true);
    area.setPrefRowCount(3);
    area.setPrefWidth(Math.max(120, c.width - 10));
    area.setPromptText(Tr.of("diagram.commentPrompt"));
    parent.getChildren().set(parent.getChildren().indexOf(shown), area);
    area.setOnMousePressed(e -> e.consume());
    area.setOnMouseDragged(e -> e.consume());
    area.setOnMouseReleased(e -> e.consume());
    area.setOnMouseClicked(e -> e.consume());
    boolean[] done = {false};
    Runnable keep =
        () -> {
          if (done[0]) return;
          done[0] = true;
          String text = area.getText().strip();
          if (text.equals(c.text)) render();
          else attempt(() -> host.changeCaption(id, caption -> caption.text = text));
        };
    area.focusedProperty()
        .addListener(
            (o, was, now) -> {
              if (!now) keep.run();
            });
    area.setOnKeyPressed(
        e -> {
          if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
            done[0] = true;
            render();
            e.consume();
          } else if (e.getCode() == javafx.scene.input.KeyCode.ENTER && e.isControlDown()) {
            keep.run();
            e.consume();
          }
        });
    javafx.application.Platform.runLater(area::requestFocus);
  }

  /** Symbols a block can carry before its name. */
  static final String[] ICONS = {
    "★", "⚙", "⏱", "⚠", "♨", "❄", "⚡", "☀", "⏻", "◉", "▲", "▼", "∑", "≥", "&", "⇄", "✓", "✗", "♦",
    "⌂"
  };

  private ContextMenu iconMenu(Project.Node n) {
    ContextMenu menu = new ContextMenu();
    MenuItem none = new MenuItem(Tr.of("inspector.noIcon"));
    none.setOnAction(e -> attempt(() -> host.icon(n.id, "")));
    menu.getItems().add(none);
    for (String symbol : ICONS) {
      MenuItem item = new MenuItem(symbol);
      item.setOnAction(e -> attempt(() -> host.icon(n.id, symbol)));
      menu.getItems().add(item);
    }
    return menu;
  }

  /**
   * Opens an editor inside the block, where its comment is shown. Ctrl+Enter or a click elsewhere
   * keeps the text, Esc drops the change.
   */
  void editComment(String id) {
    Project.Node n = host.project().nodes.get(id);
    if (n == null || !(lookup("#block-" + id) instanceof VBox box)) return;
    TextArea area = new TextArea(n.note);
    area.setId("comment-editor");
    area.setWrapText(true);
    area.setPrefRowCount(3);
    area.setPromptText(Tr.of("diagram.commentPrompt"));
    Node shown = box.lookup("#info-" + id);
    if (shown != null) box.getChildren().set(box.getChildren().indexOf(shown), area);
    else box.getChildren().add(Math.min(1, box.getChildren().size()), area);
    // Typing and selecting in the editor must not drag the block.
    area.setOnMousePressed(e -> e.consume());
    area.setOnMouseDragged(e -> e.consume());
    area.setOnMouseReleased(e -> e.consume());
    area.setOnMouseClicked(e -> e.consume());
    boolean[] done = {false};
    Runnable keep =
        () -> {
          if (done[0]) return;
          done[0] = true;
          String text = area.getText().strip();
          if (text.equals(n.note) && (n.info || text.isEmpty())) render();
          else attempt(() -> host.comment(id, text));
        };
    area.focusedProperty()
        .addListener(
            (o, was, now) -> {
              if (!now) keep.run();
            });
    area.setOnKeyPressed(
        e -> {
          if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
            done[0] = true;
            render();
            e.consume();
          } else if (e.getCode() == javafx.scene.input.KeyCode.ENTER && e.isControlDown()) {
            keep.run();
            e.consume();
          }
        });
    javafx.application.Platform.runLater(area::requestFocus);
  }

  private void installBlockMenu(VBox box, Project.Node n) {
    ContextMenu menu = new ContextMenu();
    MenuItem comment = new MenuItem(Tr.of("diagram.comment"));
    comment.setOnAction(e -> editComment(n.id));
    Menu icons = new Menu(Tr.of("inspector.icon"));
    icons.getItems().addAll(iconMenu(n).getItems());
    MenuItem duplicate = new MenuItem(Tr.of("diagram.duplicate")),
        delete = new MenuItem(Tr.of("sessions.remove"));
    duplicate.setOnAction(e -> attempt(() -> host.duplicate(n.id)));
    delete.setOnAction(e -> attempt(() -> host.delete(n.id)));
    menu.getItems().addAll(comment, icons, new SeparatorMenuItem(), duplicate, delete);
    box.setOnContextMenuRequested(
        e -> {
          host.select(n.id);
          menu.show(box, e.getScreenX(), e.getScreenY());
        });
  }

  public java.util.List<String> visibleIds(Bounds viewportInScene) {
    java.util.List<String> ids = new java.util.ArrayList<>();
    for (Node box : nodes.getChildren())
      if (box.localToScene(box.getLayoutBounds()).intersects(viewportInScene))
        ids.add((String) box.getUserData());
    return ids;
  }

  public String displayedValue(String id, String port) {
    return valueLabels.get(id).get(port).getText();
  }

  public void refreshValues() {
    for (var entry : wireTips.entrySet())
      if (entry.getValue().isShowing())
        entry
            .getValue()
            .setText(
                wireTip(
                    entry.getKey(),
                    !cachedRoutes
                        .getOrDefault(entry.getKey().key(), java.util.List.of())
                        .isEmpty()));
    for (java.util.Map.Entry<String, java.util.Map<String, Label>> item : valueLabels.entrySet()) {
      Project.Node n = host.project().nodes.get(item.getKey());
      if (n != null)
        for (java.util.Map.Entry<String, Label> value : item.getValue().entrySet()) {
          Label label = value.getValue();
          label.setText(host.display(n, value.getKey()));
          String quality = host.quality(n, value.getKey());
          ValueState state = host.valueState(n, value.getKey());
          label.setStyle(
              "-fx-text-fill: "
                  + (state == ValueState.PENDING
                      ? "#bd6a10"
                      : state == ValueState.FRESH
                          ? "#268773"
                          : state == ValueState.LOCAL ? "#57859a" : "#967545")
                  + ";");
          if (label.getTooltip() == null) label.setTooltip(new Tooltip());
          label.getTooltip().setText(quality + "\n" + host.display(n, value.getKey()));
        }
    }
  }

  private String wireTip(Project.Wire w, boolean routed) {
    return host.signal(w) + (routed ? "" : Tr.of("diagram.noRoute"));
  }

  public String signalTooltip(Project.Wire w) {
    return wireTip(w, true);
  }

  private double[] point(String id, String port, boolean output) {
    Project.Node n = host.project().nodes.get(id);
    if (n == null || !n.parent.equals(host.folder())) return null;
    Node anchor = anchors.get(key(id, port, output));
    if (anchor != null) {
      Point2D point = new Point2D(0, 0);
      Node cursor = anchor;
      while (cursor != this) {
        if (cursor == null) return null;
        point = cursor.localToParent(point);
        cursor = cursor.getParent();
      }
      return new double[] {point.getX(), point.getY()};
    }
    return null;
  }

  private final java.util.Map<String, java.util.List<Point2D>> cachedRoutes =
      new java.util.HashMap<>();

  private java.util.List<Object> cachedGeometry = java.util.List.of();

  private void renderWires() {
    renderWires(false);
  }

  private void renderWires(boolean requestedReuse) {
    adjacent.clear();
    for (Tooltip tip : wireTips.values()) tip.hide();
    wireTips.clear();
    wires.getChildren().clear();
    java.util.Map<String, Bounds> boxes = blockBounds();
    java.util.List<Object> geometry = wireGeometry(boxes);
    boolean reuseRoutes =
        requestedReuse && !cachedGeometry.isEmpty() || geometry.equals(cachedGeometry);
    if (!reuseRoutes) {
      cachedGeometry = geometry;
      cachedRoutes.clear();
      routePasses++;
    }
    WireRouter router =
        reuseRoutes ? null : new WireRouter(boxes.values(), getPrefWidth(), getPrefHeight());
    for (Project.Wire w : host.project().wires) {
      double[] a = point(w.source, w.sourcePort, true), b = point(w.target, w.targetPort, false);
      if (a == null || b == null) continue;
      java.util.List<Point2D> route =
          reuseRoutes
              ? cachedRoutes.getOrDefault(w.key(), java.util.List.of())
              : w.bends.isEmpty()
                  ? router.route(
                      new Point2D(a[0], a[1]),
                      new Point2D(b[0], b[1]),
                      boxes.get(w.source),
                      boxes.get(w.target))
                  : java.util.List.of();
      if (!reuseRoutes && !w.bends.isEmpty()) {
        route = manualRoute(w, a, b, boxes);
      }
      if (!reuseRoutes) cachedRoutes.put(w.key(), java.util.List.copyOf(route));
      boolean routed = !route.isEmpty();
      if (!routed) route = java.util.List.of(new Point2D(a[0], a[1]), new Point2D(a[0] + 24, a[1]));
      Path curve = new Path(), hit = new Path();
      for (int i = 0; i < route.size(); i++) {
        Point2D p = route.get(i);
        curve
            .getElements()
            .add(i == 0 ? new MoveTo(p.getX(), p.getY()) : new LineTo(p.getX(), p.getY()));
        hit.getElements()
            .add(i == 0 ? new MoveTo(p.getX(), p.getY()) : new LineTo(p.getX(), p.getY()));
      }
      Project.Port port = host.project().nodes.get(w.source).port(w.sourcePort);
      Color baseColor = port == null ? Color.web("#7d8a96") : typeColor(port.type);
      String delta = host.wireDelta(w);
      if (!delta.isEmpty() && !delta.equals("same")) baseColor = Color.web(deltaColor(delta));
      Color color = w == host.selectedWire() ? Color.web("#e58b26") : baseColor;
      curve.setFill(null);
      curve.setStroke(color);
      curve.setStrokeWidth(w == host.selectedWire() ? 3 : 1.8);
      curve.setStrokeLineJoin(StrokeLineJoin.ROUND);
      curve.setMouseTransparent(true);
      java.util.List<Node> shapes = new java.util.ArrayList<>();
      if (!routed) {
        curve.getStrokeDashArray().addAll(4.0, 4.0);
        Path stub = new Path(new MoveTo(b[0] - 24, b[1]), new LineTo(b[0], b[1]));
        stub.setStroke(color);
        stub.setMouseTransparent(true);
        wires.getChildren().add(stub);
        shapes.add(stub);
      }
      hit.getStyleClass().add("wire-hit");
      curve.getStyleClass().add("wire-line");
      hit.setFill(null);
      hit.setStroke(Color.TRANSPARENT);
      hit.setStrokeWidth(12);
      Project.Node from = host.project().nodes.get(w.source),
          to = host.project().nodes.get(w.target);
      Tooltip signal = new Tooltip(wireTip(w, routed));
      signal.setOnShowing(e -> signal.setText(wireTip(w, routed)));
      Tooltip.install(hit, signal);
      wireTips.put(w, signal);
      installWireClick(hit, w);
      installWireMenu(hit, w);
      Path arrow =
          new Path(
              new MoveTo(b[0] - 28, b[1] - 4),
              new LineTo(b[0] - 21, b[1]),
              new LineTo(b[0] - 28, b[1] + 4));
      arrow.setStroke(color);
      arrow.setStrokeWidth(1.8);
      arrow.setFill(null);
      arrow.setMouseTransparent(true);
      wires.getChildren().addAll(hit, curve, arrow);
      shapes.addAll(java.util.List.of(hit, curve, arrow));
      if (w == host.selectedWire()) shapes.addAll(bendHandles(w));
      WireVisual visual = new WireVisual(w, shapes, baseColor);
      adjacent.computeIfAbsent(w.source, k -> new java.util.ArrayList<>()).add(visual);
      if (!w.source.equals(w.target))
        adjacent.computeIfAbsent(w.target, k -> new java.util.ArrayList<>()).add(visual);
    }
  }

  /** Everything wire routes depend on; equal fingerprints mean the cached routes still fit. */
  private java.util.List<Object> wireGeometry(java.util.Map<String, Bounds> boxes) {
    java.util.List<Object> geometry = new java.util.ArrayList<>();
    geometry.add(host.folder());
    geometry.add(getPrefWidth());
    geometry.add(getPrefHeight());
    boxes.entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(
            e -> {
              geometry.add(e.getKey());
              Bounds b = e.getValue();
              geometry.add(
                  java.util.List.of(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()));
            });
    for (Project.Wire wire : host.project().wires) {
      double[] a = point(wire.source, wire.sourcePort, true),
          b = point(wire.target, wire.targetPort, false);
      if (a == null || b == null) continue;
      geometry.add(wire.key());
      geometry.add(java.util.List.of(a[0], a[1], b[0], b[1]));
      geometry.add(java.util.List.copyOf(wire.bends));
    }
    return geometry;
  }

  /** A wire with user-placed bends: orthogonal segments through each bend, no auto-routing. */
  private java.util.List<Point2D> manualRoute(
      Project.Wire w, double[] a, double[] b, java.util.Map<String, Bounds> boxes) {
    java.util.List<Point2D> manual = new java.util.ArrayList<>();
    manual.add(new Point2D(a[0], a[1]));
    Point2D cursor = new Point2D(boxes.get(w.source).getMaxX() + 24, a[1]);
    manual.add(cursor);
    for (Project.Point bend : w.bends) {
      manual.add(new Point2D(bend.x(), cursor.getY()));
      cursor = new Point2D(bend.x(), bend.y());
      manual.add(cursor);
    }
    double entry = boxes.get(w.target).getMinX() - 24;
    manual.add(new Point2D(entry, cursor.getY()));
    manual.add(new Point2D(entry, b[1]));
    manual.add(new Point2D(b[0], b[1]));
    return manual;
  }

  private void installWireClick(Path hit, Project.Wire w) {
    hit.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) {
            Point2D point = sceneToLocal(e.getSceneX(), e.getSceneY());
            host.bend(w, -1, point.getX(), point.getY());
            e.consume();
            return;
          }
          host.selectWire(w);
          for (Node box : nodes.getChildren()) box.getStyleClass().remove("selected-block");
          renderWires(true);
          selectionStyles();
          e.consume();
        });
  }

  private void installWireMenu(Path hit, Project.Wire w) {
    ContextMenu menu = new ContextMenu();
    MenuItem remove = new MenuItem(Tr.of("inspector.removeLink"));
    remove.setOnAction(
        e -> {
          host.selectWire(w);
          host.delete(null);
        });
    MenuItem auto = new MenuItem(Tr.of("diagram.autoRoute"));
    auto.setOnAction(e -> host.clearBends(w));
    MenuItem source = new MenuItem(Tr.of("diagram.goToSource")),
        targetItem = new MenuItem(Tr.of("diagram.goToTarget"));
    source.setOnAction(e -> host.navigateWire(w, true));
    targetItem.setOnAction(e -> host.navigateWire(w, false));
    menu.getItems().addAll(source, targetItem, new SeparatorMenuItem(), remove, auto);
    hit.setOnContextMenuRequested(
        e -> {
          host.selectWire(w);
          menu.show(hit, e.getScreenX(), e.getScreenY());
          e.consume();
        });
  }

  /** Draggable handles on the bends of the selected wire. */
  private java.util.List<Node> bendHandles(Project.Wire w) {
    java.util.List<Node> handles = new java.util.ArrayList<>();
    for (int index = 0; index < w.bends.size(); index++) {
      Project.Point point = w.bends.get(index);
      Circle handle = new Circle(point.x(), point.y(), 5, Color.web("#e58b26"));
      handle.getStyleClass().add("manual-bend");
      int bendIndex = index;
      handle.setOnMousePressed(e -> e.consume());
      handle.setOnMouseDragged(
          e -> {
            Point2D target = sceneToLocal(e.getSceneX(), e.getSceneY());
            handle.setCenterX(target.getX());
            handle.setCenterY(target.getY());
            e.consume();
          });
      handle.setOnMouseReleased(
          e -> {
            Point2D target = sceneToLocal(e.getSceneX(), e.getSceneY());
            attempt(() -> host.bend(w, bendIndex, target.getX(), target.getY()));
            e.consume();
          });
      wires.getChildren().add(handle);
      handles.add(handle);
    }
    return handles;
  }
}
