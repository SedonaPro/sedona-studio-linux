package ru.bccontrol.sedonastudio.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import ru.bccontrol.sedonastudio.model.History;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.ScreenBindings;
import ru.bccontrol.sedonastudio.model.Screens;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.ScreenStore;

/**
 * Operator screens of the project: the list on the Screens tab and a window per open screen that
 * shows it with the values of the open project (live while a device or the simulator is polled).
 * Screens are part of the project, so every change is one undo step like any other edit.
 */
final class ScreensFeature implements ScreenData {
  private final History history;
  private final EditorFocus focus;
  private final LiveValues liveValues;
  private final Notices notices;
  private final DeviceLink deviceLink;
  private final DeviceChanges deviceChanges;
  private final ViewOptions options;
  private final EditorShell shell;
  private final ListView<Screens.View> list = new ListView<>();
  private final Label hint = new Label();
  private final CheckBox here = new CheckBox(Tr.of("screens.here"));
  private final Button folderScreens = new Button();
  private final CheckBox follow = new CheckBox(Tr.of("screens.follow"));

  /** The screen shown by the walk mode; it is replaced as the diagram goes to other folders. */
  private String followed;

  private final Map<String, Window> open = new LinkedHashMap<>();

  /** What to say about the alarms in a folder and below ("" when quiet); set by the window. */
  java.util.function.Function<String, String> alarmsUnder = folder -> "";

  /** What the history holds for a value over the last seconds; set by the window. */
  PastValues pastValues = (folder, binding, seconds) -> List.of();

  interface PastValues {
    List<ru.bccontrol.sedonastudio.model.Archive.Sample> get(
        String folder, Screens.Binding binding, double seconds);
  }

  @Override
  public List<ru.bccontrol.sedonastudio.model.Archive.Sample> past(
      String folder, Screens.Binding binding, double seconds) {
    return pastValues.get(folder, binding, seconds);
  }

  /** Brings the list of alarms forward. */
  Runnable showAlarms = () -> {};

  private int pollingCursor;

  /** An open screen window; rebuilt when the project changes under it. */
  private final class Window {
    final String id;
    final Stage stage = new Stage();
    final ScrollPane scroll = new ScrollPane();
    final Label note = new Label();
    final FlowPane nav = new FlowPane(6, 4);
    final Button alarm = new Button();
    String links = "";
    ScreenView view;
    String shown;
    final ScreenEditor editor;

    Window(String id) {
      this.id = id;
      note.setPadding(new Insets(4, 10, 4, 10));
      editor =
          new ScreenEditor(
              id,
              history::get,
              edit ->
                  change(
                      screens -> {
                        if (screens.view(id) != null) edit.accept(screens);
                      }),
              notices);
      BorderPane root = new BorderPane(scroll);
      nav.setId("screen-nav");
      nav.setPadding(new Insets(4, 10, 4, 10));
      alarm.setId("screen-alarms");
      alarm.getStyleClass().add("alarm-alarm");
      alarm.setOnAction(e -> showAlarms.run());
      alarm.setVisible(false);
      alarm.setManaged(false);
      HBox alarmRow = new HBox(alarm);
      alarmRow.setPadding(new Insets(0, 10, 0, 10));
      root.setTop(new VBox(editor.toolbar(), nav, alarmRow));
      root.setLeft(editor.paletteSide());
      root.setRight(editor.propertiesSide());
      root.setBottom(note);
      stage.initOwner(shell.stage());
      stage.setScene(new Scene(root, 1280, 780));
      stage.setOnHidden(
          e -> {
            if (view != null) view.dispose();
            open.remove(id);
            // Closing the walk window by hand ends the walk; otherwise it would open again at once.
            if (id.equals(followed)) {
              followed = null;
              follow.setSelected(false);
            }
          });
      scroll.viewportBoundsProperty().addListener((o, a, b) -> fit());
    }

    /** Draws the screen again if it changed in the project; closes when it is gone. */
    void sync() {
      Screens screens = Screens.of(history.get());
      Screens.View source = screens == null ? null : screens.view(id);
      if (source == null) {
        stage.close();
        return;
      }
      String now = ScreenStore.write(screens).hashCode() + "/" + id;
      if (!now.equals(shown)) {
        if (view != null) view.dispose();
        view = new ScreenView(source, screens, ScreensFeature.this);
        view.setId("screen-view");
        shown = now;
        scroll.setContent(new Group(view));
        stage.setTitle(Tr.of("screens.windowTitle", source.title, source.folder));
        note.setText(view.partial ? Tr.of("screens.partial") : "");
        note.setManaged(view.partial);
        fit();
        editor.attach(view);
      } else view.refresh();
      String alarms = alarmsUnder.apply(source.folder);
      alarm.setText(alarms);
      alarm.setVisible(!alarms.isEmpty());
      alarm.setManaged(!alarms.isEmpty());
      route();
    }

    /** The screens one can go to from here: of the folder above, of this one, of those below. */
    void route() {
      List<ScreenBindings.Link> found = ScreenBindings.links(history.get(), id);
      StringBuilder key = new StringBuilder();
      for (ScreenBindings.Link l : found)
        key.append(l.kind()).append(l.view().id).append(l.view().title).append('\n');
      if (key.toString().equals(links)) return;
      links = key.toString();
      nav.getChildren().clear();
      for (ScreenBindings.Link l : found) {
        String text =
            switch (l.kind()) {
              case "up" -> Tr.of("screens.goUp", l.view().title);
              case "same" -> l.view().title;
              default -> Tr.of("screens.goDown", l.view().title);
            };
        Button go = shell.button(text, () -> go(this, l.view().id));
        go.setId("screen-go-" + l.view().id);
        nav.getChildren().add(go);
      }
      nav.setVisible(!found.isEmpty());
      nav.setManaged(!found.isEmpty());
    }

    /** Scales the screen to the window, never above its own size. */
    void fit() {
      if (view == null) return;
      double w = scroll.getViewportBounds().getWidth(), h = scroll.getViewportBounds().getHeight();
      double scale =
          w <= 0 || h <= 0
              ? 1
              : Math.min(1, Math.min(w / view.stageWidth(), h / view.stageHeight()));
      view.setScaleX(scale);
      view.setScaleY(scale);
    }
  }

  ScreensFeature(
      History history,
      EditorFocus focus,
      LiveValues liveValues,
      Notices notices,
      DeviceLink deviceLink,
      DeviceChanges deviceChanges,
      ViewOptions options,
      EditorShell shell) {
    this.options = options;
    this.deviceLink = deviceLink;
    this.deviceChanges = deviceChanges;
    this.history = history;
    this.focus = focus;
    this.liveValues = liveValues;
    this.notices = notices;
    this.shell = shell;
  }

  /** The Screens tab: the list of screens and what can be done with them. */
  VBox pane() {
    list.setId("screens-list");
    list.setCellFactory(
        v ->
            new ListCell<>() {
              @Override
              protected void updateItem(Screens.View item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title + "\n" + item.folder);
              }
            });
    list.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) notices.guard(this::openSelected);
        });
    hint.setWrapText(true);
    here.setId("screens-here");
    here.setOnAction(e -> refresh());
    follow.setId("screens-follow");
    follow.setTooltip(new javafx.scene.control.Tooltip(Tr.of("screens.followTip")));
    follow.setOnAction(e -> notices.guard(this::refresh));
    FlowPane actions =
        new FlowPane(
            6,
            6,
            button(Tr.of("screens.new"), "screens-new", this::askNew),
            button(Tr.of("screens.open"), "screens-open", this::openSelected),
            button(Tr.of("screens.picture"), "screens-picture", this::askPicture),
            button(Tr.of("screens.rename"), "screens-rename", this::askRename),
            button(Tr.of("screens.attach"), "screens-attach", this::attachSelected),
            button(Tr.of("screens.remove"), "screens-remove", this::removeSelected),
            button(Tr.of("screens.import"), "screens-import", this::askImport),
            button(Tr.of("screens.export"), "screens-export", this::askExport),
            button(Tr.of("screens.toDevice"), "screens-to-device", this::toDevice),
            button(Tr.of("screens.fromDevice"), "screens-from-device", this::fromDevice));
    VBox pane = new VBox(8, follow, here, list, actions, hint);
    pane.setPadding(new Insets(10));
    VBox.setVgrow(list, Priority.ALWAYS);
    refresh();
    return pane;
  }

  private Button button(String text, String id, Runnable action) {
    Button b = shell.button(text, action);
    b.setId(id);
    return b;
  }

  /** After the project or its values changed: the list and every open window follow. */
  void refresh() {
    folderShown();
    for (Window w : List.copyOf(open.values())) w.sync();
  }

  /** The diagram shows a folder (maybe another one): the list and the folder's button follow. */
  void folderShown() {
    Project p = history.get();
    Screens.View kept = list.getSelectionModel().getSelectedItem();
    List<Screens.View> all = Screens.of(p) == null ? List.of() : Screens.of(p).views;
    List<Screens.View> local = ScreenBindings.screensOf(p, focus.folder);
    List<Screens.View> views = here.isSelected() ? local : all;
    folderScreens.setText(
        local.isEmpty()
            ? Tr.of("screens.folderNone")
            : local.size() == 1
                ? Tr.of("screens.folderOne", local.get(0).title)
                : Tr.of("screens.folderMany", local.size()));
    boolean same = list.getItems().size() == views.size();
    for (int i = 0; same && i < views.size(); i++) same = list.getItems().get(i) == views.get(i);
    if (!same) {
      list.getItems().setAll(views);
      if (kept != null)
        for (Screens.View v : views) if (v.id.equals(kept.id)) list.getSelectionModel().select(v);
    }
    hint.setText(
        Screens.foreign(p) != null
            ? Tr.of("screens.foreign")
            : all.isEmpty() ? Tr.of("screens.empty") : Tr.of("screens.hint"));
    walk(local);
  }

  /**
   * The walk mode: going through the tree or the diagram shows the screen of each folder in one
   * window, which stays where the user put it. A folder without screens leaves the last one shown.
   */
  private void walk(List<Screens.View> local) {
    if (!follow.isSelected() || local.isEmpty()) return;
    Window shown = followed == null ? null : open.get(followed);
    if (shown != null && local.stream().anyMatch(v -> v.id.equals(followed))) return;
    String next = local.get(0).id;
    if (shown == null) open(next);
    else go(shown, next);
    followed = next;
  }

  /** Turns the walk mode on or off (the checkbox on the Screens tab). */
  void follow(boolean on) {
    follow.setSelected(on);
    folderShown();
  }

  /**
   * The button beside the breadcrumb: the screens of the folder shown on the diagram. One screen
   * opens at once, several are offered in a menu, none offers to make one.
   */
  Button folderButton() {
    folderScreens.setId("folder-screens");
    folderScreens.setOnAction(
        e ->
            notices.guard(
                () -> {
                  List<Screens.View> local = ScreenBindings.screensOf(history.get(), focus.folder);
                  if (local.isEmpty()) askNew();
                  else if (local.size() == 1) open(local.get(0).id);
                  else {
                    ContextMenu menu = new ContextMenu();
                    for (Screens.View v : local) {
                      MenuItem item = new MenuItem(v.title);
                      item.setOnAction(x -> notices.guard(() -> open(v.id)));
                      menu.getItems().add(item);
                    }
                    menu.show(folderScreens, javafx.geometry.Side.BOTTOM, 0, 0);
                  }
                }));
    return folderScreens;
  }

  /** Shows another screen in place of this window: same place and size. */
  private void go(Window from, String id) {
    boolean fresh = !open.containsKey(id);
    open(id);
    Window to = open.get(id);
    if (to == null) return;
    if (fresh) {
      to.stage.setX(from.stage.getX());
      to.stage.setY(from.stage.getY());
      to.stage.setWidth(from.stage.getWidth());
      to.stage.setHeight(from.stage.getHeight());
    }
    if (from.id.equals(followed)) followed = id;
    from.stage.close();
  }

  /**
   * Moves a screen to the folder shown on the diagram. Its values keep their paths, now counted
   * from the new folder; returns how many of them no longer lead to a component.
   */
  int attach(String id) {
    guardForeign();
    String folder = ScreenBindings.folderPath(history.get(), focus.folder);
    change(screens -> screens.view(id).folder = folder);
    Project p = history.get();
    int lost = 0;
    for (Screens.Binding b : Screens.interest(Screens.of(p).view(id)))
      if (ScreenBindings.node(p, folder, b.path()) == null) lost++;
    return lost;
  }

  private void attachSelected() {
    Screens.View v = selected();
    int lost = attach(v.id);
    String folder = ScreenBindings.folderPath(history.get(), focus.folder);
    notices.message(
        lost == 0
            ? Tr.of("screens.attached", v.title, folder)
            : Tr.of("screens.attachedLost", v.title, folder, lost));
  }

  /** Let the blocks of the open screens take a few slots of each sampling round. */
  void addWatchedTo(Collection<String> ids) {
    List<String> watched = new ArrayList<>();
    Project p = history.get();
    for (Window w : open.values()) {
      Screens.View v = Screens.of(p) == null ? null : Screens.of(p).view(w.id);
      if (v == null) continue;
      for (Screens.Binding b : Screens.interest(v)) {
        Project.Node n = ScreenBindings.node(p, v.folder, b.path());
        if (n != null && !watched.contains(n.id)) watched.add(n.id);
      }
    }
    // Every block of the open screens is read each round while they fit; a bigger set takes turns.
    int share = Math.min(WATCH_PER_ROUND, watched.size());
    for (int i = 0; i < share; i++)
      ids.add(watched.get(Math.floorMod(pollingCursor + i, watched.size())));
    pollingCursor += share;
    rounds = watched.isEmpty() ? 1 : (watched.size() + WATCH_PER_ROUND - 1) / WATCH_PER_ROUND;
  }

  private static final int WATCH_PER_ROUND = 40;

  /** How many sampling rounds it takes to read every block of the open screens once. */
  private int rounds = 1;

  /**
   * Whether a value on a screen counts as live. A screen is looked at, not debugged: a value that
   * waits for its turn in the sampling, or for the device to take a write, must not make the
   * picture blink grey. It goes grey only when nothing has come for several rounds.
   */
  private boolean live(Project.Node n, String slot) {
    ValueState state = liveValues.state(n, slot);
    if (state == ValueState.FRESH || state == ValueState.PENDING) return true;
    if (state != ValueState.STALE || !liveValues.polling.isSelected()) return false;
    return liveValues.ageMs(n) <= Math.max(5000L, liveValues.intervalMs() * 4L * rounds);
  }

  void closeAll() {
    for (Window w : List.copyOf(open.values())) w.stage.close();
  }

  /** The arranging tools of an open window, or null. */
  ScreenEditor editor(String id) {
    Window w = open.get(id);
    return w == null ? null : w.editor;
  }

  /** The drawn screen of an open window, or null. */
  ScreenView view(String id) {
    Window w = open.get(id);
    return w == null ? null : w.view;
  }

  // ---------------------------------------------------------------- ScreenData

  @Override
  public Reading read(String folder, Screens.Binding binding) {
    Project p = history.get();
    Project.Node n = ScreenBindings.node(p, folder, binding.path());
    if (n == null) return new Reading(null, Tr.of("screens.noComponent", binding.path()), false);
    Project.Port port = n.port(binding.slot());
    if (port == null) return new Reading(null, Tr.of("screens.noSlot", binding.slot()), false);
    if (!ScreenBindings.sameType(port.type, binding.type()))
      return new Reading(null, Tr.of("screens.typeChanged", port.type), false);
    String value = liveValues.fullValue(n, binding.slot());
    return new Reading(value.equals("—") ? null : value, null, live(n, binding.slot()));
  }

  @Override
  public String writeProblem(String folder, Screens.Binding binding) {
    Project p = history.get();
    Project.Node n = ScreenBindings.node(p, folder, binding.path());
    Project.Port port = n == null ? null : n.port(binding.slot());
    if (port == null) return Tr.of("screens.noComponent", binding.path());
    if (port.readonly) return Tr.of("screens.readOnly");
    if (deviceLink.baseline == null || !deviceLink.session.connected())
      return Tr.of("screens.writeNeedsDevice");
    if (deviceLink.busy.get()) return Tr.of("screens.deviceBusy");
    return null;
  }

  /** Asks before a write: (label of the value, "was -> will be") -> go on or not. */
  java.util.function.BiPredicate<String, String> confirmWrite = this::askWrite;

  private boolean askWrite(String what, String change) {
    Alert confirm =
        new Alert(Alert.AlertType.CONFIRMATION, change, ButtonType.OK, ButtonType.CANCEL);
    confirm.setHeaderText(what);
    confirm.initOwner(shell.stage());
    return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
  }

  @Override
  public void write(String folder, Screens.Binding binding, String value) {
    String problem = writeProblem(folder, binding);
    if (problem != null) throw new IllegalStateException(problem);
    // A write from a screen sends this one value; it must not drag other unsent edits along.
    if (deviceChanges.pending()) throw new IllegalStateException(Tr.of("screens.writePending"));
    Project.Node n = ScreenBindings.node(history.get(), folder, binding.path());
    Project.Port port = n.port(binding.slot());
    String literal =
        port.asStr
            ? ru.bccontrol.sedonastudio.protocol.ScalarValues.validateText(value, port.textMax)
            : ru.bccontrol.sedonastudio.protocol.ScalarValues.validateLiteral(port.type, value);
    String before = liveValues.fullValue(n, binding.slot());
    if (!options.autoApply
        && !confirmWrite.test(
            n.name + "." + binding.slot(), Tr.of("screens.writeConfirm", before, literal))) return;
    String id = n.id;
    shell.edit(p -> p.nodes.get(id).values.put(binding.slot(), literal));
    deviceChanges.applyNow();
  }

  // ---------------------------------------------------------------- commands

  private void change(Consumer<Screens> edit) {
    shell.edit(
        p -> {
          if (Screens.of(p) == null) Screens.put(p, ScreenStore.create(p.name));
          edit.accept(Screens.of(p));
          ScreenStore.check(Screens.of(p));
        });
    refresh();
  }

  private void guardForeign() {
    if (Screens.foreign(history.get()) != null)
      throw new IllegalStateException(Tr.of("screens.foreign"));
  }

  /** Adds an empty screen for the folder shown on the diagram and returns its id. */
  String create(String title) {
    guardForeign();
    String id = "v" + Long.toString(System.nanoTime(), 36);
    String folder = ScreenBindings.folderPath(history.get(), focus.folder);
    change(
        screens -> {
          Screens.View v = new Screens.View();
          v.id = id;
          v.title = title.isBlank() ? Tr.of("screens.defaultTitle") : title.strip();
          v.folder = folder;
          v.widgets = new ArrayList<>();
          screens.views.add(v);
        });
    for (Screens.View v : list.getItems()) if (v.id.equals(id)) list.getSelectionModel().select(v);
    return id;
  }

  /** Sets (or with "" removes) the background picture of a screen. */
  void picture(String id, String svg) {
    String safe = svg.isEmpty() ? "" : ScreenStore.safeSvg(svg);
    change(screens -> screens.view(id).svg = safe);
  }

  /** Takes the screens of a package file in place of the project's own. */
  void importPackage(String json) {
    Screens incoming = ScreenStore.read(json);
    shell.edit(
        p -> {
          Screens.put(p, incoming);
        });
    refresh();
  }

  void open(String id) {
    Window w = open.get(id);
    if (w == null) {
      w = new Window(id);
      open.put(id, w);
      w.sync();
      if (open.containsKey(id)) w.stage.show();
    } else w.stage.toFront();
  }

  private Screens.View selected() {
    Screens.View v = list.getSelectionModel().getSelectedItem();
    if (v == null) throw new IllegalStateException(Tr.of("screens.chooseScreen"));
    return v;
  }

  private void openSelected() {
    open(selected().id);
  }

  private void askNew() {
    guardForeign();
    TextInputDialog dialog = new TextInputDialog(Tr.of("screens.defaultTitle"));
    dialog.initOwner(shell.stage());
    dialog.setTitle(Tr.of("screens.new"));
    dialog.setHeaderText(
        Tr.of("screens.newHeader", ScreenBindings.folderPath(history.get(), focus.folder)));
    dialog.showAndWait().ifPresent(title -> open(create(title)));
  }

  private void askRename() {
    Screens.View v = selected();
    TextInputDialog dialog = new TextInputDialog(v.title);
    dialog.initOwner(shell.stage());
    dialog.setTitle(Tr.of("screens.rename"));
    dialog.setHeaderText(null);
    dialog
        .showAndWait()
        .ifPresent(
            title -> {
              if (!title.isBlank()) change(screens -> screens.view(v.id).title = title.strip());
            });
  }

  private void removeSelected() {
    Screens.View v = selected();
    Alert confirm =
        new Alert(
            Alert.AlertType.CONFIRMATION,
            Tr.of("screens.removeConfirm", v.title),
            ButtonType.OK,
            ButtonType.CANCEL);
    confirm.initOwner(shell.stage());
    if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
    change(screens -> screens.views.removeIf(x -> x.id.equals(v.id)));
  }

  private void askPicture() {
    Screens.View v = selected();
    FileChooser chooser = new FileChooser();
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG", "*.svg"));
    java.io.File file = chooser.showOpenDialog(shell.stage());
    if (file == null) return;
    try {
      if (file.length() > ScreenStore.LIMIT)
        throw new IllegalArgumentException(Tr.of("screens.tooBig"));
      picture(v.id, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      notices.error(e);
    }
  }

  private void askImport() {
    FileChooser chooser = packageChooser();
    java.io.File file = chooser.showOpenDialog(shell.stage());
    if (file == null) return;
    try {
      if (file.length() > ScreenStore.LIMIT)
        throw new IllegalArgumentException(Tr.of("screens.tooBig"));
      String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
      Project p = history.get();
      if (Screens.of(p) != null && !Screens.of(p).views.isEmpty() || Screens.foreign(p) != null) {
        Alert confirm =
            new Alert(
                Alert.AlertType.CONFIRMATION,
                Tr.of("screens.importConfirm"),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(shell.stage());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
      }
      importPackage(json);
      notices.message(Tr.of("screens.imported", Screens.of(history.get()).views.size()));
    } catch (java.io.IOException e) {
      notices.error(e);
    }
  }

  private void askExport() {
    Project p = history.get();
    if (Screens.of(p) == null) throw new IllegalStateException(Tr.of("screens.empty"));
    FileChooser chooser = packageChooser();
    chooser.setInitialFileName(ScreenStore.FILE);
    java.io.File file = chooser.showSaveDialog(shell.stage());
    if (file == null) return;
    try {
      Files.writeString(file.toPath(), ScreenStore.write(Screens.of(p)), StandardCharsets.UTF_8);
      notices.message(Tr.of("screens.exported", file.getName()));
    } catch (java.io.IOException e) {
      notices.error(e);
    }
  }

  /** Asks before the screens on the device or in the project are replaced; set by tests. */
  java.util.function.Predicate<String> confirmReplace =
      text -> {
        Alert confirm =
            new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
      };

  private void requireDevice() {
    if (deviceLink.baseline == null || !deviceLink.session.connected())
      throw new IllegalStateException(Tr.of("screens.writeNeedsDevice"));
  }

  /** Stores the screens on the connected device as "mcs-ui.json"; the copy is read back. */
  void toDevice() {
    requireDevice();
    Project p = history.get();
    if (Screens.of(p) == null) throw new IllegalStateException(Tr.of("screens.empty"));
    byte[] payload = ScreenStore.write(Screens.of(p)).getBytes(StandardCharsets.UTF_8);
    if (!confirmReplace.test(
        Tr.of("screens.toDeviceConfirm", Screens.of(p).views.size(), payload.length))) return;
    deviceLink.work(
        Tr.of("screens.sending"),
        () -> {
          deviceLink.session.store(ScreenStore.FILE, payload);
          return payload.length;
        },
        bytes -> notices.message(Tr.of("screens.sent", bytes)));
  }

  /** Takes the screens the connected device holds in place of the project's own. */
  void fromDevice() {
    requireDevice();
    deviceLink.work(
        Tr.of("screens.receiving"),
        () -> deviceLink.session.fetch(ScreenStore.FILE),
        bytes -> {
          Screens incoming = ScreenStore.read(new String(bytes, StandardCharsets.UTF_8));
          Project p = history.get();
          boolean has =
              Screens.of(p) != null && !Screens.of(p).views.isEmpty() || Screens.foreign(p) != null;
          if (has && !confirmReplace.test(Tr.of("screens.importConfirm"))) return;
          shell.edit(
              project -> {
                Screens.put(project, incoming);
              });
          refresh();
          notices.message(Tr.of("screens.imported", incoming.views.size()));
        });
  }

  private FileChooser packageChooser() {
    FileChooser chooser = new FileChooser();
    chooser
        .getExtensionFilters()
        .add(new FileChooser.ExtensionFilter(Tr.of("screens.packageType"), "*.json"));
    return chooser;
  }
}
