package ru.bccontrol.sedonastudio.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import ru.bccontrol.sedonastudio.model.Alarms;
import ru.bccontrol.sedonastudio.model.Archive;
import ru.bccontrol.sedonastudio.model.History;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.ScreenBindings;
import ru.bccontrol.sedonastudio.model.Screens;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.ArchiveStore;

/**
 * History of values: the channels of the project are sampled while a device or the simulator is
 * polled and written to disk; the History tab draws any stretch of what was written. The list of
 * channels is part of the project (one undo step per change), the samples are not.
 */
final class ArchiveFeature {
  private static final int WATCH_PER_ROUND = 40;
  private static final int POINTS = 600;
  private static final int[] RANGES = {300, 900, 3600, 6 * 3600, 24 * 3600, 7 * 86400, 30 * 86400};
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault());

  private final History history;
  private final EditorFocus focus;
  private final LiveValues liveValues;
  private final Notices notices;
  private final EditorShell shell;
  private final Map<String, Long> lastAt = new HashMap<>();
  private final Tab tab = new Tab(Tr.of("tab.archive"));
  private final ListView<Archive.Channel> list = new ListView<>();
  private final ComboBox<Integer> range = new ComboBox<>();
  private final TextField period = new TextField();
  private final Label hint = new Label();
  private final NumberAxis time = new NumberAxis(), values = new NumberAxis();
  private final LineChart<Number, Number> chart = new LineChart<>(time, values);
  private int pollingCursor;
  private long drawnAt, drawnFrom, prunedDay = -1;
  private String listed = "";

  ArchiveFeature(
      History history,
      EditorFocus focus,
      LiveValues liveValues,
      Notices notices,
      EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.liveValues = liveValues;
    this.notices = notices;
    this.shell = shell;
  }

  private static Path directory() {
    return Paths.get(System.getProperty("studio.data"));
  }

  /** How many days of history are kept on disk. */
  int keepDays() {
    return Math.max(1, Math.min(3650, shell.prefs().getInt("archiveDays", 30)));
  }

  // ---------------------------------------------------------------- recording

  /** After the project or its values changed. */
  void refresh() {
    long now = System.currentTimeMillis();
    record(now);
    listChannels();
    // While the tab is looked at, the picture follows the recording.
    if (tab.isSelected() && now - drawnAt > 5000) draw();
  }

  /** Writes a sample of every channel that is due and has a fresh value; the time is for tests. */
  void record(long now) {
    Project p = history.get();
    if (Archive.of(p) == null) return;
    for (Archive.Channel c : Archive.of(p).channels) {
      Long last = lastAt.get(c.id);
      // A clock that went back must not stop the recording until it catches up.
      if (last != null && now >= last && now - last < c.period * 1000L) continue;
      Project.Node n = Alarms.node(p, c.path);
      if (n == null || n.port(c.slot) == null || !Archive.fits(n.port(c.slot).type)) continue;
      if (liveValues.state(n, c.slot) != ValueState.FRESH) continue;
      Double value = Archive.number(liveValues.fullValue(n, c.slot));
      if (value == null) continue;
      try {
        ArchiveStore.append(directory(), p.name, c.id, List.of(new Archive.Sample(now, value)));
        lastAt.put(c.id, now);
      } catch (IOException e) {
        notices.message(Tr.of("archive.writeFailed", e.getMessage()));
        return;
      }
    }
    long day = now / 86_400_000L;
    if (day != prunedDay) {
      prunedDay = day;
      try {
        int removed = ArchiveStore.prune(directory(), keepDays(), now);
        if (removed > 0) notices.message(Tr.of("archive.pruned", removed, keepDays()));
      } catch (IOException e) {
        notices.message(Tr.of("archive.writeFailed", e.getMessage()));
      }
    }
  }

  /** Let the recorded blocks take a few slots of each sampling round. */
  void addWatchedTo(Collection<String> ids) {
    Project p = history.get();
    if (Archive.of(p) == null) return;
    List<String> watched = new ArrayList<>();
    for (Archive.Channel c : Archive.of(p).channels) {
      Project.Node n = Alarms.node(p, c.path);
      if (n != null && !watched.contains(n.id)) watched.add(n.id);
    }
    int share = Math.min(WATCH_PER_ROUND, watched.size());
    for (int i = 0; i < share; i++)
      ids.add(watched.get(Math.floorMod(pollingCursor + i, watched.size())));
    pollingCursor += share;
  }

  /** The samples of a channel between two moments, thinned to what a chart can show. */
  List<Archive.Sample> samples(Archive.Channel c, long from, long to) throws IOException {
    return Archive.thin(ArchiveStore.read(directory(), history.get().name, c.id, from, to), POINTS);
  }

  /**
   * What the history holds for a value a screen shows, over the last seconds: lets a trend on a
   * screen start with its past instead of an empty chart. Empty when the value is not recorded.
   */
  List<Archive.Sample> past(String folder, Screens.Binding binding, double seconds) {
    Project p = history.get();
    Project.Node n = ScreenBindings.node(p, folder, binding.path());
    if (Archive.of(p) == null || n == null) return List.of();
    Archive.Channel c = Archive.of(p).channel(ScreenBindings.folderPath(p, n.id), binding.slot());
    if (c == null) return List.of();
    long now = System.currentTimeMillis();
    try {
      return Archive.thin(
          ArchiveStore.read(directory(), p.name, c.id, now - (long) (seconds * 1000), now), 300);
    } catch (IOException e) {
      return List.of();
    }
  }

  // ---------------------------------------------------------------- channels

  /** One undoable change of the channels; the result must be something the file can hold. */
  void change(Consumer<Archive> edit) {
    if (Archive.foreign(history.get()) != null)
      throw new IllegalStateException(Tr.of("archive.foreign"));
    shell.edit(
        p -> {
          if (Archive.of(p) == null) Archive.put(p, new Archive());
          edit.accept(Archive.of(p));
          ArchiveStore.check(Archive.of(p));
          if (Archive.of(p).channels.isEmpty()) Archive.put(p, null);
        });
  }

  /** The slots of a block that can be recorded and are not yet. */
  List<String> offered(String nodeId) {
    Project p = history.get();
    Project.Node n = p.nodes.get(nodeId);
    List<String> slots = new ArrayList<>();
    if (n == null || n.id.equals("0")) return slots;
    String path = ScreenBindings.folderPath(p, n.id);
    for (boolean outputs : new boolean[] {true, false})
      for (Project.Port port : n.ports)
        if (Archive.fits(port.type)
            && (!port.input && !port.config) == outputs
            && (Archive.of(p) == null || Archive.of(p).channel(path, port.name) == null))
          slots.add(port.name);
    return slots;
  }

  /** Starts recording a slot of a block. Returns the id of the new channel. */
  String add(String nodeId, String slot) {
    Project p = history.get();
    Project.Node n = p.nodes.get(nodeId);
    if (n == null || n.port(slot) == null || !Archive.fits(n.port(slot).type))
      throw new IllegalStateException(Tr.of("archive.pickBlock"));
    Archive.Channel c = new Archive.Channel();
    c.id =
        (Archive.of(p) == null ? new Archive() : Archive.of(p)).freeId(System.currentTimeMillis());
    c.path = ScreenBindings.folderPath(p, n.id);
    c.slot = slot;
    c.label = n.name + " " + slot;
    change(a -> a.channels.add(c));
    return c.id;
  }

  private void addSelected() {
    if (focus.selected == null) throw new IllegalStateException(Tr.of("archive.pickBlock"));
    List<String> slots = offered(focus.selected);
    if (slots.isEmpty()) throw new IllegalStateException(Tr.of("archive.nothingToRecord"));
    String slot = slots.get(0);
    if (slots.size() > 1) {
      ChoiceDialog<String> choose = new ChoiceDialog<>(slot, slots);
      choose.initOwner(shell.stage());
      choose.setTitle(Tr.of("archive.add"));
      choose.setHeaderText(Tr.of("archive.chooseSlot"));
      slot = choose.showAndWait().orElse(null);
      if (slot == null) return;
    }
    String id = add(focus.selected, slot);
    listChannels();
    for (Archive.Channel c : list.getItems())
      if (c.id.equals(id)) list.getSelectionModel().select(c);
  }

  private void removeSelected() {
    List<String> ids = new ArrayList<>();
    for (Archive.Channel c : list.getSelectionModel().getSelectedItems()) ids.add(c.id);
    if (ids.isEmpty()) return;
    change(a -> a.channels.removeIf(c -> ids.contains(c.id)));
    notices.message(Tr.of("archive.removed", ids.size()));
  }

  /** Sets how often the chosen channels are written. */
  void setPeriod(String text) {
    int seconds;
    try {
      seconds = Integer.parseInt(text.trim());
    } catch (NumberFormatException notNumber) {
      seconds = 0;
    }
    if (seconds < 1 || seconds > 3600)
      throw new IllegalArgumentException(Tr.of("archive.badPeriod"));
    List<String> ids = new ArrayList<>();
    for (Archive.Channel c : list.getSelectionModel().getSelectedItems()) ids.add(c.id);
    if (ids.isEmpty()) return;
    int every = seconds;
    change(
        a -> {
          for (Archive.Channel c : a.channels) if (ids.contains(c.id)) c.period = every;
        });
  }

  // ---------------------------------------------------------------- the History tab

  Tab tab() {
    list.setId("archive-channels");
    list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    list.setPlaceholder(new Label(Tr.of("archive.empty")));
    list.setCellFactory(
        v ->
            new ListCell<>() {
              @Override
              protected void updateItem(Archive.Channel c, boolean empty) {
                super.updateItem(c, empty);
                setText(
                    empty || c == null
                        ? null
                        : c.label
                            + "\n"
                            + c.path
                            + "."
                            + c.slot
                            + " · "
                            + Tr.of("archive.every", c.period));
              }
            });
    list.getSelectionModel()
        .getSelectedItems()
        .addListener(
            (javafx.collections.ListChangeListener<Archive.Channel>)
                change -> {
                  Archive.Channel first = list.getSelectionModel().getSelectedItem();
                  if (first != null) period.setText(String.valueOf(first.period));
                  draw();
                });
    list.setPrefWidth(300);
    range.setId("archive-range");
    for (int seconds : RANGES) range.getItems().add(seconds);
    range.setConverter(
        new StringConverter<>() {
          @Override
          public String toString(Integer seconds) {
            if (seconds == null) return "";
            if (seconds < 3600) return Tr.of("live.minutes", seconds / 60);
            if (seconds < 86400) return Tr.of("live.hours", seconds / 3600);
            return Tr.of("archive.days", seconds / 86400);
          }

          @Override
          public Integer fromString(String s) {
            return null;
          }
        });
    int saved = shell.prefs().getInt("archiveRange", 3600);
    range.setValue(range.getItems().contains(saved) ? saved : 3600);
    range.setOnAction(
        e -> {
          shell.prefs().putInt("archiveRange", range.getValue());
          draw();
        });
    period.setId("archive-period");
    period.setPrefColumnCount(4);
    chart.setId("archive-chart");
    chart.setAnimated(false);
    chart.setCreateSymbols(false);
    time.setAutoRanging(false);
    time.setMinorTickVisible(false);
    values.setForceZeroInRange(false);
    time.setTickLabelFormatter(
        new StringConverter<>() {
          @Override
          public String toString(Number seconds) {
            long at = drawnFrom + Math.round(seconds.doubleValue() * 1000);
            return CLOCK.format(Instant.ofEpochMilli(at));
          }

          @Override
          public Number fromString(String s) {
            return 0;
          }
        });
    FlowPane actions =
        new FlowPane(
            6,
            4,
            new Label(Tr.of("archive.range")),
            range,
            button(Tr.of("archive.add"), "archive-add", this::addSelected),
            button(Tr.of("archive.remove"), "archive-remove", this::removeSelected),
            new Label(Tr.of("archive.periodLabel")),
            period,
            button(
                Tr.of("archive.setPeriod"),
                "archive-set-period",
                () -> setPeriod(period.getText())),
            button(Tr.of("archive.export"), "archive-export", this::askExport));
    actions.setPadding(new Insets(6));
    hint.setWrapText(true);
    hint.setPadding(new Insets(2, 8, 4, 8));
    hint.setText(Tr.of("archive.hint", keepDays(), directory().resolve("archive")));
    VBox side = new VBox(list);
    VBox.setVgrow(list, Priority.ALWAYS);
    BorderPane pane = new BorderPane(chart);
    pane.setTop(actions);
    pane.setLeft(side);
    pane.setBottom(hint);
    tab.setContent(pane);
    tab.setId("archive-tab");
    tab.setOnSelectionChanged(
        e -> {
          if (tab.isSelected()) draw();
        });
    listChannels();
    return tab;
  }

  private Button button(String text, String id, Runnable action) {
    Button b = new Button(text);
    b.setId(id);
    b.setOnAction(e -> notices.guard(action));
    return b;
  }

  private void listChannels() {
    Archive archive = Archive.of(history.get());
    StringBuilder key = new StringBuilder();
    if (archive != null)
      for (Archive.Channel c : archive.channels)
        key.append(c.id).append(c.label).append(c.path).append(c.slot).append(c.period).append(';');
    if (key.toString().equals(listed)) return;
    listed = key.toString();
    List<String> chosen = new ArrayList<>();
    for (Archive.Channel c : list.getSelectionModel().getSelectedItems()) chosen.add(c.id);
    list.getItems().setAll(archive == null ? List.of() : archive.channels);
    list.getSelectionModel().clearSelection();
    for (Archive.Channel c : list.getItems())
      if (chosen.contains(c.id)) list.getSelectionModel().select(c);
    // Nothing chosen: show the first channels, so the tab is not an empty chart.
    if (list.getSelectionModel().isEmpty())
      for (int i = 0; i < Math.min(4, list.getItems().size()); i++)
        list.getSelectionModel().select(i);
    draw();
  }

  /** Draws the chosen channels over the chosen stretch that ends now. */
  void draw() {
    long now = System.currentTimeMillis();
    int seconds = range.getValue() == null ? 3600 : range.getValue();
    drawnAt = now;
    drawnFrom = now - seconds * 1000L;
    time.setLowerBound(0);
    time.setUpperBound(seconds);
    time.setTickUnit(seconds / 6.0);
    List<XYChart.Series<Number, Number>> lines = new ArrayList<>();
    try {
      for (Archive.Channel c : list.getSelectionModel().getSelectedItems()) {
        if (c == null) continue;
        XYChart.Series<Number, Number> line = new XYChart.Series<>();
        line.setName(c.label);
        for (Archive.Sample s : samples(c, drawnFrom, now))
          line.getData().add(new XYChart.Data<>((s.time() - drawnFrom) / 1000.0, s.value()));
        lines.add(line);
      }
    } catch (IOException e) {
      notices.message(Tr.of("archive.readFailed", e.getMessage()));
    }
    chart.getData().setAll(lines);
  }

  private void askExport() {
    if (list.getSelectionModel().getSelectedItems().isEmpty())
      throw new IllegalStateException(Tr.of("archive.chooseChannels"));
    FileChooser chooser = new FileChooser();
    chooser.setInitialFileName("sedona-history.csv");
    File file = chooser.showSaveDialog(shell.stage());
    if (file != null) export(file.toPath());
  }

  /** Writes every stored sample of the chosen channels over the chosen stretch, not thinned. */
  int export(Path file) {
    long now = System.currentTimeMillis();
    long from = now - (range.getValue() == null ? 3600 : range.getValue()) * 1000L;
    StringBuilder csv = new StringBuilder("﻿time,channel,value\r\n");
    int rows = 0;
    try {
      for (Archive.Channel c : list.getSelectionModel().getSelectedItems())
        for (Archive.Sample s :
            ArchiveStore.read(directory(), history.get().name, c.id, from, now)) {
          csv.append(Instant.ofEpochMilli(s.time()))
              .append(",\"")
              .append(c.label.replace("\"", "\"\""))
              .append("\",")
              .append(s.value())
              .append("\r\n");
          rows++;
        }
      Files.write(file, csv.toString().getBytes(StandardCharsets.UTF_8));
      notices.message(Tr.of("archive.exported", rows, file.getFileName()));
    } catch (IOException e) {
      notices.error(e);
    }
    return rows;
  }

  /** Brings the History tab forward. */
  void bringForward() {
    tab.getTabPane().getSelectionModel().select(tab);
  }
}
