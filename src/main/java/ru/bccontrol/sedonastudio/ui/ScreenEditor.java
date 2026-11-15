package ru.bccontrol.sedonastudio.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.ScreenBindings;
import ru.bccontrol.sedonastudio.model.ScreenEdits;
import ru.bccontrol.sedonastudio.model.Screens;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.ScreenLibraries;
import ru.bccontrol.sedonastudio.storage.ScreenStore;

/**
 * Arranging one screen: the palette of widgets and library pictures, selection, dragging, aligning
 * and the settings of the selected item. It changes the project only through the given "change"
 * call, so every step can be undone like any other edit.
 */
final class ScreenEditor {
  private final String viewId;
  private final Supplier<Project> project;
  private final Consumer<Consumer<Screens>> change;
  private final Notices notices;
  final Set<String> selection = new LinkedHashSet<>();
  final CheckBox arrange = new CheckBox(Tr.of("screens.arrange"));
  private final ComboBox<Integer> grid = new ComboBox<>();
  private final VBox properties = new VBox(6);
  private final VBox palette = new VBox(6);
  private final FlowPane tools = new FlowPane(6, 4);
  private ScreenView view;
  private double pressX, pressY;
  private final Map<String, double[]> origin = new LinkedHashMap<>();
  private boolean dragged;
  private int placed;

  ScreenEditor(
      String viewId,
      Supplier<Project> project,
      Consumer<Consumer<Screens>> change,
      Notices notices) {
    this.viewId = viewId;
    this.project = project;
    this.change = change;
    this.notices = notices;
    arrange.setId("screen-arrange");
    arrange.selectedProperty().addListener((o, a, b) -> modeChanged());
    grid.getItems().addAll(0, 5, 10, 20, 25);
    grid.setValue(10);
    grid.setTooltip(new javafx.scene.control.Tooltip(Tr.of("screens.gridTip")));
    tools
        .getChildren()
        .addAll(
            arrange,
            new Label(Tr.of("screens.grid")),
            grid,
            tool(Tr.of("screens.alignLeft"), () -> align("left")),
            tool(Tr.of("screens.alignCenterX"), () -> align("centerX")),
            tool(Tr.of("screens.alignRight"), () -> align("right")),
            tool(Tr.of("screens.alignTop"), () -> align("top")),
            tool(Tr.of("screens.alignCenterY"), () -> align("centerY")),
            tool(Tr.of("screens.alignBottom"), () -> align("bottom")),
            tool(Tr.of("screens.distributeX"), () -> align("distributeX")),
            tool(Tr.of("screens.distributeY"), () -> align("distributeY")),
            tool(Tr.of("screens.duplicate"), this::duplicate),
            tool(Tr.of("screens.removeItems"), this::remove));
    tools.setPadding(new Insets(6, 10, 6, 10));
    buildPalette();
    properties.setPadding(new Insets(10));
    properties.setPrefWidth(280);
    palette.setPadding(new Insets(10));
    palette.setPrefWidth(280);
    modeChanged();
  }

  Node toolbar() {
    return tools;
  }

  Node paletteSide() {
    return palette;
  }

  Node propertiesSide() {
    ScrollPane scroll = new ScrollPane(properties);
    scroll.setFitToWidth(true);
    scroll.managedProperty().bind(arrange.selectedProperty());
    scroll.visibleProperty().bind(arrange.selectedProperty());
    return scroll;
  }

  private Button tool(String text, Runnable action) {
    Button b = new Button(text);
    b.setOnAction(e -> notices.guard(action));
    b.disableProperty().bind(arrange.selectedProperty().not());
    return b;
  }

  private void modeChanged() {
    palette.setManaged(arrange.isSelected());
    palette.setVisible(arrange.isSelected());
    if (!arrange.isSelected()) selection.clear();
    if (view != null) attach(view);
  }

  private Screens.View source() {
    Screens screens = Screens.of(project.get());
    return screens == null ? null : screens.view(viewId);
  }

  // ------------------------------------------------------------------ palette

  private void buildPalette() {
    ListView<ScreenEdits.Template> widgets = new ListView<>();
    widgets.setId("screen-templates");
    widgets.getItems().addAll(ScreenEdits.templates());
    widgets.setCellFactory(
        v ->
            new ListCell<>() {
              @Override
              protected void updateItem(ScreenEdits.Template item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title());
              }
            });
    widgets.setOnMouseClicked(
        e -> {
          ScreenEdits.Template t = widgets.getSelectionModel().getSelectedItem();
          if (e.getClickCount() == 2 && t != null) notices.guard(() -> addWidget(t.id(), -1, -1));
        });
    widgets.setOnDragDetected(
        e -> {
          ScreenEdits.Template t = widgets.getSelectionModel().getSelectedItem();
          if (t == null) return;
          ClipboardContent content = new ClipboardContent();
          content.putString("screen-widget:" + t.id());
          widgets.startDragAndDrop(TransferMode.COPY).setContent(content);
          e.consume();
        });
    pictures = tree("screen-pictures", "screen-picture:", id -> addSymbol(id, -1, -1));
    parts = tree("screen-components", "screen-part:", id -> addPart(id, -1, -1));
    preview.setId("screen-preview");
    preview.setMinHeight(150);
    preview.setPrefHeight(150);
    preview.setStyle(
        "-fx-background-color: #eef0ef; -fx-border-color: #c9ced2; -fx-border-radius: 6;"
            + " -fx-background-radius: 6;");
    previewTitle.setWrapText(true);
    previewTitle.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
    previewTitle.setStyle("-fx-font-size: 11px; -fx-text-fill: #4a5158;");
    Button own = new Button(Tr.of("screens.ownPicture"));
    own.setId("screen-own-picture");
    own.setOnAction(e -> notices.guard(this::askOwnPicture));
    Label hint = new Label(Tr.of("screens.paletteHint"));
    hint.setWrapText(true);
    hint.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
    TitledPane widgetPane = new TitledPane(Tr.of("screens.widgets"), widgets),
        picturePane = new TitledPane(Tr.of("screens.pictures"), pictures),
        partPane = new TitledPane(Tr.of("screens.components"), parts);
    widgetPane.setExpanded(false);
    picturePane.setExpanded(false);
    VBox.setVgrow(widgetPane, Priority.ALWAYS);
    VBox.setVgrow(picturePane, Priority.ALWAYS);
    VBox.setVgrow(partPane, Priority.ALWAYS);
    palette
        .getChildren()
        .addAll(hint, partPane, widgetPane, picturePane, preview, previewTitle, own);
  }

  /** One line of a palette tree: a folder (item null) or a picture / graphic component. */
  private record Entry(String title, Object item) {}

  private final javafx.scene.layout.StackPane preview = new javafx.scene.layout.StackPane();
  private final Label previewTitle = new Label();
  private ScreenView previewView;

  /** A palette tree: folders within folders; double click or drag puts the chosen item. */
  private javafx.scene.control.TreeView<Entry> tree(
      String id, String dragPrefix, Consumer<String> add) {
    javafx.scene.control.TreeView<Entry> tree =
        new javafx.scene.control.TreeView<>(
            new javafx.scene.control.TreeItem<>(new Entry("", null)));
    tree.setId(id);
    tree.setShowRoot(false);
    tree.setCellFactory(
        v ->
            new javafx.scene.control.TreeCell<>() {
              @Override
              protected void updateItem(Entry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title());
                setStyle(
                    !empty && item != null && item.item() == null ? "-fx-font-weight: bold;" : "");
              }
            });
    java.util.function.Supplier<String> chosen =
        () -> {
          javafx.scene.control.TreeItem<Entry> at = tree.getSelectionModel().getSelectedItem();
          Object item = at == null ? null : at.getValue().item();
          return item instanceof Screens.Asset a
              ? a.id
              : item instanceof Screens.Component c ? c.id : null;
        };
    tree.setOnMouseClicked(
        e -> {
          String itemId = chosen.get();
          if (e.getClickCount() == 2 && itemId != null) notices.guard(() -> add.accept(itemId));
        });
    tree.setOnDragDetected(
        e -> {
          String itemId = chosen.get();
          if (itemId == null) return;
          ClipboardContent content = new ClipboardContent();
          content.putString(dragPrefix + itemId);
          tree.startDragAndDrop(TransferMode.COPY).setContent(content);
          e.consume();
        });
    tree.getSelectionModel()
        .selectedItemProperty()
        .addListener((o, was, now) -> showPreview(now == null ? null : now.getValue().item()));
    return tree;
  }

  /** The folder of a tree for a path like "Air/Fans"; made on the way if it is not there. */
  private static javafx.scene.control.TreeItem<Entry> folder(
      javafx.scene.control.TreeItem<Entry> root, String path) {
    javafx.scene.control.TreeItem<Entry> at = root;
    for (String name : path.split("/")) {
      if (name.isBlank()) continue;
      javafx.scene.control.TreeItem<Entry> next = null;
      for (javafx.scene.control.TreeItem<Entry> child : at.getChildren())
        if (child.getValue().item() == null && child.getValue().title().equals(name)) next = child;
      if (next == null) {
        next = new javafx.scene.control.TreeItem<>(new Entry(name, null));
        at.getChildren().add(next);
      }
      at = next;
    }
    return at;
  }

  /**
   * Shows what the chosen palette item looks like before it is placed: a picture in its "running"
   * drawing, a graphic component with sample values in its points.
   */
  private void showPreview(Object item) {
    if (previewView != null) previewView.dispose();
    previewView = null;
    preview.getChildren().clear();
    previewTitle.setText("");
    if (item == null) return;
    Screens sample = new Screens();
    sample.library = new ArrayList<>();
    // Points that drive a 0..1 position get a fraction as their sample value, the others a number.
    java.util.Set<String> fractions = new java.util.HashSet<>();
    Screens.View v = new Screens.View();
    v.id = "preview";
    double w, h;
    if (item instanceof Screens.Asset a) {
      Screens.Asset shown = a.copy();
      if (shown.states.containsKey("on")) shown.states.put("off", shown.states.get("on"));
      sample.library.add(shown);
      Screens.Symbol o = new Screens.Symbol();
      o.id = "o";
      o.assetId = a.id;
      v.objects = new ArrayList<>(List.of(o));
      w = a.width;
      h = a.height;
      previewTitle.setText(a.title + "\n" + String.join(", ", a.states.keySet()));
    } else {
      Screens.Component c = (Screens.Component) item;
      Screens own = Screens.of(project.get());
      for (String assetId : c.assets()) {
        Screens.Asset a = own == null ? null : own.asset(assetId);
        if (a == null) a = ScreenLibraries.asset(assetId);
        if (a != null) sample.library.add(a);
      }
      sample.components = new ArrayList<>(List.of(c));
      Screens.Part part = new Screens.Part();
      part.id = "p";
      part.componentId = c.id;
      // Fault signals stay unbound, so the preview shows the component at work.
      java.util.Set<String> faults = new java.util.HashSet<>();
      if (c.objects != null)
        for (Screens.Symbol o : c.objects) if (o.fault != null) faults.add(o.fault.slot());
      if (c.objects != null)
        for (Screens.Symbol o : c.objects)
          if (o.mode.equals("position") && o.binding != null) fractions.add(o.binding.slot());
      StringBuilder points = new StringBuilder();
      for (Screens.Point point : c.points) {
        points.append(points.length() == 0 ? "" : " · ").append(point.label);
        if (!faults.contains(point.id))
          part.bind.put(
              point.id,
              new Screens.Binding("x", point.id, point.kind.equals("bool") ? "bool" : "float"));
      }
      v.parts = new ArrayList<>(List.of(part));
      w = c.width;
      h = c.height;
      previewTitle.setText(c.title + "\n" + points);
    }
    v.svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "
            + (w + 8)
            + " "
            + (h + 8)
            + "\"><rect width=\"1\" height=\"1\" fill=\"#eef0ef\"/></svg>";
    ScreenData values =
        new ScreenData() {
          @Override
          public Reading read(String folder, Screens.Binding b) {
            return new Reading(
                b.type().equals("bool") ? "true" : fractions.contains(b.slot()) ? "0.5" : "42.5",
                null,
                true);
          }

          @Override
          public String writeProblem(String folder, Screens.Binding b) {
            return Tr.of("screens.previewOnly");
          }

          @Override
          public void write(String folder, Screens.Binding b, String value) {}
        };
    try {
      previewView = new ScreenView(v, sample, values);
    } catch (RuntimeException unreadable) {
      return;
    }
    previewView.setStyle("-fx-background-color: transparent;");
    previewView.setMouseTransparent(true);
    double scale = Math.min(1, Math.min(250 / (w + 8), 140 / (h + 8)));
    previewView.setScaleX(scale);
    previewView.setScaleY(scale);
    preview.getChildren().add(new javafx.scene.Group(previewView));
  }

  private javafx.scene.control.TreeView<Entry> pictures;
  private javafx.scene.control.TreeView<Entry> parts;
  private String listed = "";

  /**
   * The pictures and components on offer, as folders within folders: the pictures that come with
   * the editor, every plugged-in library by its own groups, then what only the project has.
   */
  private void listPictures() {
    Screens own = Screens.of(project.get());
    StringBuilder key = new StringBuilder();
    for (ScreenStore.Pack pack : ScreenLibraries.packs()) key.append(pack.id()).append(';');
    if (own != null && own.library != null) for (Screens.Asset a : own.library) key.append(a.id);
    key.append('|');
    if (own != null && own.components != null)
      for (Screens.Component c : own.components) key.append(c.id);
    if (key.toString().equals(listed)) return;
    listed = key.toString();
    javafx.scene.control.TreeItem<Entry> pictureRoot = pictures.getRoot(),
        partRoot = parts.getRoot();
    pictureRoot.getChildren().clear();
    partRoot.getChildren().clear();
    java.util.Set<String> seenPictures = new java.util.HashSet<>(),
        seenParts = new java.util.HashSet<>();
    javafx.scene.control.TreeItem<Entry> basic =
        folder(pictureRoot, Tr.of("screens.basicPictures"));
    for (Screens.Asset a : ScreenStore.baseLibrary())
      if (seenPictures.add(a.id))
        basic.getChildren().add(new javafx.scene.control.TreeItem<>(new Entry(a.title, a)));
    for (ScreenStore.Pack pack : ScreenLibraries.packs()) {
      for (Screens.Asset a : pack.assets())
        if (seenPictures.add(a.id))
          folder(pictureRoot, pack.title() + "/" + pack.group(a.id))
              .getChildren()
              .add(new javafx.scene.control.TreeItem<>(new Entry(a.title, a)));
      for (Screens.Component c : pack.components())
        if (seenParts.add(c.id))
          folder(partRoot, pack.title() + "/" + pack.group(c.id))
              .getChildren()
              .add(new javafx.scene.control.TreeItem<>(new Entry(c.title, c)));
    }
    if (own != null && own.library != null)
      for (Screens.Asset a : own.library)
        if (seenPictures.add(a.id))
          folder(pictureRoot, Tr.of("screens.inProject"))
              .getChildren()
              .add(new javafx.scene.control.TreeItem<>(new Entry(a.title, a)));
    if (own != null && own.components != null)
      for (Screens.Component c : own.components)
        if (seenParts.add(c.id))
          folder(partRoot, Tr.of("screens.inProject"))
              .getChildren()
              .add(new javafx.scene.control.TreeItem<>(new Entry(c.title, c)));
    // Libraries open one level, so the groups are seen at once and the items on demand.
    for (javafx.scene.control.TreeItem<Entry> top : pictureRoot.getChildren())
      top.setExpanded(true);
    for (javafx.scene.control.TreeItem<Entry> top : partRoot.getChildren()) top.setExpanded(true);
  }

  /**
   * Places a graphic component (of the package or of a library). With a block given its points take
   * the block's slots that fit by name; the rest are chosen in the settings.
   */
  String addPart(String componentId, double x, double y) {
    Screens own = Screens.of(project.get());
    Screens.Component def = own == null ? null : own.component(componentId);
    if (def == null) def = ScreenLibraries.component(componentId);
    if (def == null) throw new IllegalArgumentException(componentId);
    Screens.Component chosen = def;
    List<Screens.Asset> pictures = ScreenLibraries.picturesOf(componentId);
    double[] at = x < 0 ? nextPlace() : new double[] {x, y};
    String[] id = {null};
    change.accept(
        screens ->
            id[0] =
                ScreenEdits.addPart(screens, screens.view(viewId), chosen, pictures, at[0], at[1]));
    select(List.of(id[0]));
    return id[0];
  }

  /** Feeds the points of a placed component from one block; returns how many points got a slot. */
  int attachPart(String partId, String path) {
    Screens.View source = source();
    Screens.Component def = Screens.of(project.get()).component(source.part(partId).componentId);
    Map<String, Screens.Binding> bind =
        ScreenBindings.autoBind(project.get(), source.folder, def, path);
    change.accept(
        screens -> {
          Screens.Part part = screens.view(viewId).part(partId);
          part.bind.putAll(bind);
          if (part.label.equals(def.title)) part.label = path.substring(path.lastIndexOf('/') + 1);
        });
    return bind.size();
  }

  private void askOwnPicture() {
    javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
    chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("SVG", "*.svg"));
    java.io.File file = chooser.showOpenDialog(palette.getScene().getWindow());
    if (file == null) return;
    try {
      if (file.length() > ScreenStore.LIMIT)
        throw new IllegalArgumentException(Tr.of("screens.tooBig"));
      String name = file.getName().replaceFirst("\\.svg$", "");
      ownPicture(name, java.nio.file.Files.readString(file.toPath()));
    } catch (java.io.IOException e) {
      notices.error(e);
    }
  }

  /** Puts a picture of the user's own into the package library; returns its id. */
  String ownPicture(String title, String svg) {
    String safe = ScreenStore.safeSvg(svg);
    ru.bccontrol.sedonastudio.storage.SvgSymbol size =
        ru.bccontrol.sedonastudio.storage.SvgSymbol.parseDrawing(
            safe.getBytes(java.nio.charset.StandardCharsets.UTF_8), ScreenStore.LIMIT);
    String[] id = {null};
    change.accept(
        screens ->
            id[0] =
                ScreenEdits.addAsset(screens, title, safe, size.viewBox[2], size.viewBox[3]).id);
    listPictures();
    return id[0];
  }

  /** Where the next item goes when it is added without a place: a short staircase. */
  private double[] nextPlace() {
    int step = placed++ % 8;
    return new double[] {4 + step * 3, 4 + step * 4};
  }

  /** Adds a widget from the palette; x and y in per cent, negative for "anywhere free". */
  String addWidget(String templateId, double x, double y) {
    ScreenEdits.Template template =
        ScreenEdits.templates().stream()
            .filter(t -> t.id().equals(templateId))
            .findFirst()
            .orElseThrow();
    double[] at = x < 0 ? nextPlace() : new double[] {x, y};
    String[] id = {null};
    change.accept(
        screens -> id[0] = ScreenEdits.addWidget(screens.view(viewId), template, at[0], at[1]));
    select(List.of(id[0]));
    return id[0];
  }

  /** Places a picture of the base library (or of the package's own library). */
  String addSymbol(String assetId, double x, double y) {
    Screens own = Screens.of(project.get());
    Screens.Asset asset = own == null ? null : own.asset(assetId);
    if (asset == null) asset = ScreenLibraries.asset(assetId);
    if (asset == null) throw new IllegalArgumentException(assetId);
    Screens.Asset chosen = asset;
    double[] at = x < 0 ? nextPlace() : new double[] {x, y};
    String[] id = {null};
    change.accept(
        screens ->
            id[0] = ScreenEdits.addSymbol(screens, screens.view(viewId), chosen, at[0], at[1]));
    select(List.of(id[0]));
    return id[0];
  }

  // ------------------------------------------------------------------ selection and dragging

  void select(List<String> ids) {
    selection.clear();
    selection.addAll(ids);
    if (view != null) attach(view);
  }

  /** Call after the screen was drawn (again): installs the handlers and shows the selection. */
  void attach(ScreenView drawn) {
    view = drawn;
    Screens.View source = source();
    if (source == null) return;
    List<String> ids = new ArrayList<>();
    if (source.widgets != null) for (Screens.Widget w : source.widgets) ids.add(w.id);
    if (source.objects != null) for (Screens.Symbol o : source.objects) ids.add(o.id);
    if (source.parts != null) for (Screens.Part p : source.parts) ids.add(p.id);
    selection.retainAll(ids);
    listPictures();
    boolean on = arrange.isSelected();
    for (String id : ids) {
      Node node = view.node(id);
      if (node == null) continue;
      node.setEffect(
          on && selection.contains(id) ? new DropShadow(10, Color.web("#1f6feb")) : null);
      node.setOnMousePressed(on ? e -> press(id, e) : null);
      node.setOnMouseDragged(on ? this::drag : null);
      node.setOnMouseReleased(on ? e -> release() : null);
      node.setCursor(on ? javafx.scene.Cursor.MOVE : null);
    }
    view.setOnMousePressed(
        on
            ? e -> {
              if (e.getTarget() == view) select(List.of());
              view.requestFocus();
            }
            : null);
    view.setOnDragOver(
        on
            ? e -> {
              if (e.getDragboard().hasString()
                  && e.getDragboard().getString().startsWith("screen-"))
                e.acceptTransferModes(TransferMode.COPY);
              e.consume();
            }
            : null);
    view.setOnDragDropped(
        on
            ? e -> {
              String what = e.getDragboard().getString();
              double x = e.getX() / view.stageWidth() * 100,
                  y = e.getY() / view.stageHeight() * 100;
              notices.guard(
                  () -> {
                    if (what.startsWith("screen-widget:"))
                      addWidget(what.substring("screen-widget:".length()), x, y);
                    else if (what.startsWith("screen-part:"))
                      addPart(what.substring("screen-part:".length()), x, y);
                    else addSymbol(what.substring("screen-picture:".length()), x, y);
                  });
              e.setDropCompleted(true);
              e.consume();
            }
            : null);
    view.setFocusTraversable(on);
    view.setOnKeyPressed(on ? this::key : null);
    showProperties();
  }

  private void press(String id, javafx.scene.input.MouseEvent e) {
    if (e.isShiftDown()) {
      if (!selection.remove(id)) selection.add(id);
    } else if (!selection.contains(id)) {
      selection.clear();
      selection.add(id);
    }
    pressX = e.getSceneX();
    pressY = e.getSceneY();
    dragged = false;
    origin.clear();
    for (String s : selection) {
      Node n = view.node(s);
      if (n != null) origin.put(s, new double[] {n.getLayoutX(), n.getLayoutY()});
    }
    for (Map.Entry<String, double[]> o : origin.entrySet())
      view.node(o.getKey()).setEffect(new DropShadow(10, Color.web("#1f6feb")));
    view.requestFocus();
    e.consume();
  }

  private void drag(javafx.scene.input.MouseEvent e) {
    // Screen coordinates: the view may be shown smaller than it is.
    double scale = view.getScaleX() == 0 ? 1 : view.getScaleX();
    double dx = (e.getSceneX() - pressX) / scale, dy = (e.getSceneY() - pressY) / scale;
    if (Math.abs(dx) + Math.abs(dy) > 2) dragged = true;
    for (Map.Entry<String, double[]> o : origin.entrySet()) {
      Node n = view.node(o.getKey());
      n.setLayoutX(o.getValue()[0] + dx);
      n.setLayoutY(o.getValue()[1] + dy);
    }
    e.consume();
  }

  private void release() {
    if (!dragged) {
      attach(view);
      return;
    }
    int step = grid.getValue() == null ? 0 : grid.getValue();
    Map<String, double[]> places = new LinkedHashMap<>();
    for (String id : origin.keySet()) {
      Node n = view.node(id);
      double x = n.getLayoutX(), y = n.getLayoutY();
      if (step > 0) {
        x = Math.round(x / step) * (double) step;
        y = Math.round(y / step) * (double) step;
      }
      places.put(id, new double[] {x / view.stageWidth() * 100, y / view.stageHeight() * 100});
    }
    dragged = false;
    notices.guard(() -> change.accept(screens -> ScreenEdits.place(screens.view(viewId), places)));
  }

  private void key(KeyEvent e) {
    if (e.getCode() == KeyCode.DELETE) notices.guard(this::remove);
    else if (e.getCode() == KeyCode.D && e.isShortcutDown()) notices.guard(this::duplicate);
    else if (e.getCode() == KeyCode.A && e.isShortcutDown()) {
      Screens.View source = source();
      List<String> all = new ArrayList<>();
      if (source.widgets != null) for (Screens.Widget w : source.widgets) all.add(w.id);
      if (source.objects != null) for (Screens.Symbol o : source.objects) all.add(o.id);
      select(all);
    } else if (e.getCode().isArrowKey() && !selection.isEmpty()) {
      double px = e.isShiftDown() ? 10 : 1;
      double dx = e.getCode() == KeyCode.LEFT ? -px : e.getCode() == KeyCode.RIGHT ? px : 0,
          dy = e.getCode() == KeyCode.UP ? -px : e.getCode() == KeyCode.DOWN ? px : 0;
      Map<String, double[]> places = new LinkedHashMap<>();
      for (String id : selection) {
        Node n = view.node(id);
        places.put(
            id,
            new double[] {
              (n.getLayoutX() + dx) / view.stageWidth() * 100,
              (n.getLayoutY() + dy) / view.stageHeight() * 100
            });
      }
      notices.guard(
          () -> change.accept(screens -> ScreenEdits.place(screens.view(viewId), places)));
    } else return;
    e.consume();
  }

  void remove() {
    if (selection.isEmpty()) return;
    List<String> ids = List.copyOf(selection);
    change.accept(screens -> ScreenEdits.remove(screens.view(viewId), ids));
  }

  void duplicate() {
    if (selection.isEmpty()) return;
    List<String> ids = List.copyOf(selection), made = new ArrayList<>();
    change.accept(screens -> made.addAll(ScreenEdits.duplicate(screens.view(viewId), ids)));
    select(made);
  }

  void align(String action) {
    List<ScreenEdits.Box> boxes = new ArrayList<>();
    for (String id : selection) {
      Node n = view.node(id);
      if (n == null) continue;
      javafx.geometry.Bounds b = n.getBoundsInParent();
      boxes.add(
          new ScreenEdits.Box(
              id,
              n.getLayoutX() / view.stageWidth() * 100,
              n.getLayoutY() / view.stageHeight() * 100,
              b.getWidth() / view.stageWidth() * 100,
              b.getHeight() / view.stageHeight() * 100));
    }
    Map<String, double[]> places = ScreenEdits.align(boxes, action);
    if (places.isEmpty()) throw new IllegalStateException(Tr.of("screens.alignNeeds"));
    change.accept(screens -> ScreenEdits.place(screens.view(viewId), places));
  }

  // ------------------------------------------------------------------ settings of the selection

  private void showProperties() {
    properties.getChildren().clear();
    Screens.View source = source();
    if (source == null || !arrange.isSelected()) return;
    if (selection.size() != 1) {
      Label hint =
          new Label(
              selection.isEmpty()
                  ? Tr.of("screens.selectHint")
                  : Tr.of("screens.selectedCount", selection.size()));
      hint.setWrapText(true);
      properties.getChildren().add(hint);
      return;
    }
    String id = selection.iterator().next();
    Screens.Widget w = source.widget(id);
    Screens.Symbol o = source.object(id);
    if (w != null) widgetProperties(source, w);
    else if (o != null) symbolProperties(source, o);
    else if (source.part(id) != null) partProperties(source, source.part(id));
  }

  /** A graphic component: its name, the block it is attached to and the slot of every point. */
  private void partProperties(Screens.View source, Screens.Part part) {
    String id = part.id;
    Screens.Component def = Screens.of(project.get()).component(part.componentId);
    if (def == null) return;
    properties.getChildren().add(new Label(Tr.of("screens.componentOf", def.title)));
    row(
        Tr.of("screens.label"),
        text(part.label, v -> change.accept(screens -> screens.view(viewId).part(id).label = v)));
    ComboBox<String> block = new ComboBox<>();
    block.setId("screen-part-block");
    block.getItems().addAll(components(source));
    block.setPromptText(Tr.of("screens.attachPrompt"));
    block.setOnAction(
        e -> {
          String path = block.getValue();
          if (path == null) return;
          notices.guard(
              () -> {
                int bound = attachPart(id, path);
                notices.message(Tr.of("screens.partAttached", bound, def.points.size(), path));
              });
        });
    row(Tr.of("screens.attachTo"), block);
    Label hint = new Label(Tr.of("screens.attachHint"));
    hint.setWrapText(true);
    properties.getChildren().add(hint);
    for (Screens.Point point : def.points)
      bindingRows(
          source,
          point.label,
          part.bind.get(point.id),
          point.kind,
          false,
          true,
          b ->
              change.accept(
                  screens -> {
                    Screens.Part p = screens.view(viewId).part(id);
                    if (b == null) p.bind.remove(point.id);
                    else p.bind.put(point.id, b);
                  }));
  }

  private void row(String title, Node control) {
    Label l = new Label(title);
    l.setMinWidth(90);
    HBox.setHgrow(control, Priority.ALWAYS);
    if (control instanceof javafx.scene.layout.Region r) r.setMaxWidth(Double.MAX_VALUE);
    properties.getChildren().add(new HBox(8, l, control));
  }

  private TextField text(String value, Consumer<String> commit) {
    TextField f = new TextField(value);
    Runnable apply =
        () -> {
          if (!f.getText().equals(value)) notices.guard(() -> commit.accept(f.getText()));
        };
    f.setOnAction(e -> apply.run());
    f.focusedProperty()
        .addListener(
            (obs, was, now) -> {
              if (!now) apply.run();
            });
    return f;
  }

  private void editWidget(String id, Consumer<Screens.Widget> edit) {
    change.accept(screens -> edit.accept(screens.view(viewId).widget(id)));
  }

  private void editSymbol(String id, Consumer<Screens.Symbol> edit) {
    change.accept(screens -> edit.accept(screens.view(viewId).object(id)));
  }

  /** Components of the screen's folder a value can be taken from, by their relative path. */
  private List<String> components(Screens.View source) {
    Project p = project.get();
    List<String> paths = new ArrayList<>();
    if (ScreenBindings.folderId(p, source.folder) == null) return paths;
    for (Project.Node n : p.nodes.values()) {
      if (n.id.equals("0")) continue;
      String path = ScreenBindings.relative(p, source.folder, n.id);
      if (path != null && paths.size() < 2000) paths.add(path);
    }
    paths.sort(String::compareTo);
    return paths;
  }

  /** Slots of a component that fit: "bool" only, "number" only, or any scalar. */
  private List<Project.Port> slots(
      Screens.View source, String path, String want, boolean writable) {
    Project.Node n = path == null ? null : ScreenBindings.node(project.get(), source.folder, path);
    List<Project.Port> out = new ArrayList<>();
    if (n == null) return out;
    for (Project.Port port : n.ports) {
      String t = port.type.replace("sys::", "");
      boolean bool = t.equals("bool"),
          number = List.of("byte", "short", "int", "long", "float", "double").contains(t);
      if (port.name.equals("meta") || !(bool || number)) continue;
      if (want.equals("bool") && !bool || want.equals("number") && !number) continue;
      if (writable && port.readonly) continue;
      out.add(port);
    }
    return out;
  }

  /** Two boxes, component and slot; the chosen pair is handed over as a binding. */
  private void bindingRows(
      Screens.View source,
      String titleKeyText,
      Screens.Binding now,
      String want,
      boolean writable,
      boolean optional,
      Consumer<Screens.Binding> commit) {
    ComboBox<String> component = new ComboBox<>(), slot = new ComboBox<>();
    component.setId("screen-bind-component");
    slot.setId("screen-bind-slot");
    component.getItems().addAll(components(source));
    if (optional) component.getItems().add(0, "");
    boolean bound = now != null && !now.slot().equals("__unbound");
    component.setValue(bound ? now.path() : optional ? "" : null);
    Runnable fill =
        () -> {
          slot.getItems().clear();
          for (Project.Port port : slots(source, component.getValue(), want, writable))
            slot.getItems().add(port.name);
        };
    fill.run();
    if (bound) slot.setValue(now.slot());
    component.setOnAction(
        e -> {
          fill.run();
          if (optional && "".equals(component.getValue())) notices.guard(() -> commit.accept(null));
        });
    slot.setOnAction(
        e -> {
          String path = component.getValue(), name = slot.getValue();
          if (path == null || name == null) return;
          Project.Node n = ScreenBindings.node(project.get(), source.folder, path);
          Project.Port port = n == null ? null : n.port(name);
          if (port != null)
            notices.guard(() -> commit.accept(new Screens.Binding(path, name, port.type)));
        });
    row(titleKeyText, component);
    row(Tr.of("screens.slot"), slot);
  }

  private void widgetProperties(Screens.View source, Screens.Widget w) {
    String id = w.id;
    properties.getChildren().add(new Label(Tr.of("screens.widgetOf", w.kind)));
    row(Tr.of("screens.label"), text(w.label, v -> editWidget(id, x -> x.label = v)));
    if (!w.kind.equals("text") && !Screens.DATA_KINDS.contains(w.kind)) {
      bindingRows(
          source,
          Tr.of("screens.component"),
          w.binding(),
          Screens.BOOL_KINDS.contains(w.kind)
              ? "bool"
              : w.kind.equals("setpoint") ? "any" : "number",
          Screens.WRITE_KINDS.contains(w.kind),
          false,
          b ->
              editWidget(
                  id,
                  x -> {
                    x.path = b.path();
                    x.slot = b.slot();
                    x.type = b.type();
                  }));
      row(Tr.of("screens.unit"), text(w.unit, v -> editWidget(id, x -> x.unit = v)));
      row(
          Tr.of("screens.decimals"),
          text(
              String.valueOf(w.decimals),
              v -> editWidget(id, x -> x.decimals = Integer.parseInt(v.trim()))));
    }
    if (Screens.DATA_KINDS.contains(w.kind)) channelRows(source, w);
    row(Tr.of("screens.width"), numberOption(id, w, "width"));
    if (Screens.DATA_KINDS.contains(w.kind))
      row(Tr.of("screens.height"), numberOption(id, w, "height"));
    if (w.kind.equals("trend")) {
      row(Tr.of("screens.window"), numberOption(id, w, "windowSeconds"));
      row(Tr.of("screens.sample"), numberOption(id, w, "sampleSeconds"));
      properties
          .getChildren()
          .addAll(
              flag(id, w, "autoScale", Tr.of("screens.autoScale"), true),
              flag(id, w, "legend", Tr.of("screens.legend"), true));
    }
    if (List.of("bar", "gauge", "slider", "bars").contains(w.kind)) {
      row(Tr.of("screens.min"), numberOption(id, w, "min"));
      row(Tr.of("screens.max"), numberOption(id, w, "max"));
    }
    if (!Screens.DATA_KINDS.contains(w.kind)) {
      row(Tr.of("screens.fontSize"), numberOption(id, w, "fontSize"));
      ComboBox<String> look = new ComboBox<>();
      look.getItems().addAll("card", "compact", "value", "lamp");
      look.setValue(w.options.get("appearance") instanceof String a ? a : "card");
      look.setOnAction(
          e ->
              notices.guard(
                  () -> editWidget(id, x -> x.options.put("appearance", look.getValue()))));
      row(Tr.of("screens.appearance"), look);
    }
    properties
        .getChildren()
        .addAll(
            new Separator(),
            flag(id, w, "showLabel", Tr.of("screens.showLabel"), true),
            flag(id, w, "showUnit", Tr.of("screens.showUnit"), true),
            flag(id, w, "showQuality", Tr.of("screens.showQuality"), true),
            flag(id, w, "border", Tr.of("screens.border"), true),
            flag(id, w, "background", Tr.of("screens.background"), true));
  }

  /** The channels of a trend, bar chart or table: remove one, or add a value of the folder. */
  private void channelRows(Screens.View source, Screens.Widget w) {
    String id = w.id;
    properties.getChildren().add(new Label(Tr.of("screens.channels")));
    if (w.series != null)
      for (int i = 0; i < w.series.size(); i++) {
        int index = i;
        Screens.Series s = w.series.get(i);
        Button drop = new Button(Tr.of("screens.removeChannel"));
        drop.setOnAction(e -> notices.guard(() -> editWidget(id, x -> x.series.remove(index))));
        Label name = new Label(s.path + "." + s.slot);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        properties.getChildren().add(new HBox(8, name, drop));
      }
    if (w.series != null && w.series.size() >= 8) return;
    bindingRows(
        source,
        Tr.of("screens.addChannel"),
        null,
        "number",
        false,
        false,
        b ->
            editWidget(
                id,
                x -> {
                  Screens.Series s = new Screens.Series();
                  s.path = b.path();
                  s.slot = b.slot();
                  s.type = b.type();
                  s.label = (b.path().equals(".") ? "" : b.path() + ".") + b.slot();
                  if (x.series == null) x.series = new ArrayList<>();
                  x.series.add(s);
                }));
    properties.getChildren().add(new Separator());
  }

  private TextField numberOption(String id, Screens.Widget w, String key) {
    Object now = w.options.get(key);
    String shown = now instanceof Number n ? trim(n.doubleValue()) : "";
    return text(
        shown,
        v ->
            editWidget(
                id,
                x -> {
                  if (v.isBlank()) x.options.remove(key);
                  else x.options.put(key, Double.parseDouble(v.trim().replace(',', '.')));
                }));
  }

  private CheckBox flag(String id, Screens.Widget w, String key, String title, boolean fallback) {
    CheckBox box = new CheckBox(title);
    box.setSelected(w.flag(key, fallback));
    box.setOnAction(
        e -> notices.guard(() -> editWidget(id, x -> x.options.put(key, box.isSelected()))));
    return box;
  }

  private void symbolProperties(Screens.View source, Screens.Symbol o) {
    String id = o.id;
    properties.getChildren().add(new Label(Tr.of("screens.pictureOf", o.assetId)));
    row(Tr.of("screens.label"), text(o.label, v -> editSymbol(id, x -> x.label = v)));
    ComboBox<String> mode = new ComboBox<>();
    mode.setId("screen-symbol-mode");
    mode.getItems().addAll("static", "bool", "position");
    mode.setValue(o.mode);
    mode.setTooltip(new javafx.scene.control.Tooltip(Tr.of("screens.modeTip")));
    row(Tr.of("screens.mode"), mode);
    if (!o.mode.equals("static") || o.binding != null)
      bindingRows(
          source,
          Tr.of("screens.stateFrom"),
          o.binding,
          o.mode.equals("position") ? "number" : "bool",
          false,
          false,
          b -> editSymbol(id, x -> x.binding = b));
    mode.setOnAction(
        e -> {
          String chosen = mode.getValue();
          if (chosen.equals("static"))
            notices.guard(
                () ->
                    editSymbol(
                        id,
                        x -> {
                          x.mode = "static";
                          x.binding = null;
                        }));
          else {
            // The package may not hold a bound mode without a value: pick the first that fits.
            String want = chosen.equals("position") ? "number" : "bool";
            Screens.Binding first = null;
            for (String path : components(source)) {
              List<Project.Port> ports = slots(source, path, want, false);
              if (!ports.isEmpty()) {
                first = new Screens.Binding(path, ports.get(0).name, ports.get(0).type);
                break;
              }
            }
            Screens.Binding pick = first;
            notices.guard(
                () -> {
                  if (pick == null) throw new IllegalStateException(Tr.of("screens.nothingToBind"));
                  editSymbol(
                      id,
                      x -> {
                        x.mode = chosen;
                        x.binding = pick;
                      });
                });
          }
        });
    bindingRows(
        source,
        Tr.of("screens.faultFrom"),
        o.fault,
        "bool",
        false,
        true,
        b -> editSymbol(id, x -> x.fault = b));
    ComboBox<Integer> rotation = new ComboBox<>();
    rotation.getItems().addAll(0, 90, 180, 270);
    rotation.setValue(o.rotation);
    rotation.setOnAction(
        e -> notices.guard(() -> editSymbol(id, x -> x.rotation = rotation.getValue())));
    row(Tr.of("screens.rotation"), rotation);
  }

  private static String trim(double v) {
    return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
  }
}
