package ru.bccontrol.sedonastudio.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import ru.bccontrol.sedonastudio.model.Alarms;
import ru.bccontrol.sedonastudio.model.History;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.ScreenBindings;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.AlarmStore;

/**
 * Alarms: rules on values of the project, followed while a device or the simulator is polled. The
 * rules are part of the project (one undo step per change); what is active right now is not, and
 * every raise, return to normal and acknowledgement goes to the journal on disk.
 */
final class AlarmsFeature {
  private static final int WATCH_PER_ROUND = 40;
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault());

  private final History history;
  private final EditorFocus focus;
  private final LiveValues liveValues;
  private final Notices notices;
  private final EditorShell shell;
  final Alarms.Tracker tracker = new Alarms.Tracker();
  private final TableView<Row> table = new TableView<>();
  private final Tab tab = new Tab(Tr.of("tab.alarms"));
  private final Button bell = new Button();
  private int pollingCursor;
  private Stage rulesStage, journalStage;
  private final ListView<Alarms.Rule> ruleList = new ListView<>();
  private final TableView<Alarms.Event> journalTable = new TableView<>();
  private final TextField label = new TextField(), limit = new TextField();
  private final TextField band = new TextField(), delay = new TextField();
  private final ComboBox<String> slot = new ComboBox<>();
  private final ComboBox<Alarms.Kind> kind = new ComboBox<>();
  private final ComboBox<Alarms.Severity> severity = new ComboBox<>();
  private final Label where = new Label();

  /** One active alarm as the table shows it. */
  record Row(Alarms.Rule rule, Alarms.State state) {}

  AlarmsFeature(
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

  // ---------------------------------------------------------------- following the values

  /** The value a rule watches, or null while there is no fresh one. */
  private String value(Alarms.Rule r) {
    if (problem(r) != null) return null;
    Project.Node n = Alarms.node(history.get(), r.path);
    if (liveValues.state(n, r.slot) != ValueState.FRESH) return null;
    String value = liveValues.fullValue(n, r.slot);
    return value.equals("—") ? null : value;
  }

  /** Why a rule cannot be followed (component gone, slot gone, wrong type), or null. */
  String problem(Alarms.Rule r) {
    Project.Node n = Alarms.node(history.get(), r.path);
    if (n == null) return Tr.of("alarms.noComponent");
    Project.Port port = n.port(r.slot);
    if (port == null) return Tr.of("alarms.noSlot");
    if (!Alarms.fits(r.kind, port.type)) return Tr.of("alarms.wrongType", port.type);
    return null;
  }

  /** After the project or its values changed. */
  void refresh() {
    evaluate(System.currentTimeMillis());
  }

  /** Looks at every rule with the values as they are now; the time is given for tests. */
  void evaluate(long now) {
    record(tracker.step(Alarms.of(history.get()), this::value, now));
    show();
  }

  private void record(List<Alarms.Event> events) {
    if (events.isEmpty()) return;
    for (Alarms.Event e : events)
      if (e.what().equals("raised")) notices.message(Tr.of("alarms.raised", e.label(), e.value()));
      else if (e.what().equals("cleared"))
        notices.message(Tr.of("alarms.cleared", e.label(), e.value()));
    try {
      AlarmStore.append(directory(), events);
    } catch (IOException e) {
      notices.message(Tr.of("alarms.journalFailed", e.getMessage()));
    }
    if (journalStage != null && journalStage.isShowing()) loadJournal();
  }

  private static Path directory() {
    return Paths.get(System.getProperty("studio.data"));
  }

  /** The alarms that are active or wait for an acknowledgement, newest first. */
  List<Row> active() {
    List<Row> rows = new ArrayList<>();
    Alarms alarms = Alarms.of(history.get());
    if (alarms != null)
      for (Alarms.Rule r : alarms.rules) {
        Alarms.State s = tracker.state(r.id);
        if (s.phase != Alarms.Phase.NORMAL) rows.add(new Row(r, s));
      }
    rows.sort((a, b) -> Long.compare(b.state().since, a.state().since));
    return rows;
  }

  /** Let the watched blocks take a few slots of each sampling round. */
  void addWatchedTo(Collection<String> ids) {
    Project p = history.get();
    if (Alarms.of(p) == null) return;
    List<String> watched = new ArrayList<>();
    for (Alarms.Rule r : Alarms.of(p).rules) {
      Project.Node n = Alarms.node(p, r.path);
      if (n != null && !watched.contains(n.id)) watched.add(n.id);
    }
    int share = Math.min(WATCH_PER_ROUND, watched.size());
    for (int i = 0; i < share; i++)
      ids.add(watched.get(Math.floorMod(pollingCursor + i, watched.size())));
    pollingCursor += share;
  }

  /** What a screen of a folder says about the alarms in it and below, or "" when all is quiet. */
  String summary(String folder) {
    int active = 0, unacked = 0;
    for (Row row : active())
      if (Alarms.under(row.rule(), folder)) {
        active++;
        if (!row.state().acked) unacked++;
      }
    return active == 0 ? "" : Tr.of("alarms.bell", active, unacked);
  }

  // ---------------------------------------------------------------- acknowledging

  void ack(String rule) {
    Alarms alarms = Alarms.of(history.get());
    Alarms.Rule r = alarms == null ? null : alarms.rule(rule);
    if (r == null) return;
    Alarms.Event e = tracker.ack(r, System.currentTimeMillis());
    if (e != null) record(List.of(e));
    show();
  }

  private void ackAll() {
    for (Row row : active()) ack(row.rule().id);
  }

  private void ackSelected() {
    Row row = table.getSelectionModel().getSelectedItem();
    if (row != null) ack(row.rule().id);
  }

  private void goToSelected() {
    Row row = table.getSelectionModel().getSelectedItem();
    if (row != null) goTo(row.rule());
  }

  /** Shows the watched block on the diagram. */
  private void goTo(Alarms.Rule r) {
    Project.Node n = Alarms.node(history.get(), r.path);
    if (n == null) {
      notices.message(Tr.of("alarms.noComponent"));
      return;
    }
    focus.selected = n.id;
    focus.folder = n.parent;
    focus.selectedWire = null;
    shell.render();
    shell.focusSelected();
  }

  // ---------------------------------------------------------------- the Alarms tab

  Tab tab() {
    table.setId("alarms-table");
    table.setPlaceholder(new Label(Tr.of("alarms.quiet")));
    table.getColumns().add(column(Tr.of("alarms.col.time"), 130, r -> time(r.state().since)));
    table.getColumns().add(column(Tr.of("alarms.col.severity"), 110, r -> text(r.rule().severity)));
    table.getColumns().add(column(Tr.of("alarms.col.label"), 240, r -> r.rule().label));
    table
        .getColumns()
        .add(column(Tr.of("alarms.col.where"), 260, r -> r.rule().path + "." + r.rule().slot));
    table.getColumns().add(column(Tr.of("alarms.col.value"), 100, r -> r.state().value));
    table.getColumns().add(column(Tr.of("alarms.col.state"), 260, r -> text(r.state())));
    table.setRowFactory(
        t ->
            new TableRow<>() {
              @Override
              protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("alarm-alarm", "alarm-warning", "alarm-returned");
                if (empty || item == null) return;
                if (item.state().phase == Alarms.Phase.RETURNED || item.state().acked)
                  getStyleClass().add("alarm-returned");
                else
                  getStyleClass()
                      .add(
                          item.rule().severity == Alarms.Severity.ALARM
                              ? "alarm-alarm"
                              : "alarm-warning");
              }
            });
    table.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) notices.guard(this::goToSelected);
        });
    HBox actions =
        new HBox(
            6,
            button(Tr.of("alarms.ack"), "alarms-ack", this::ackSelected),
            button(Tr.of("alarms.ackAll"), "alarms-ack-all", this::ackAll),
            button(Tr.of("alarms.show"), "alarms-go", this::goToSelected),
            button(Tr.of("alarms.rules"), "alarms-rules", this::showRules),
            button(Tr.of("alarms.journal"), "alarms-journal", this::showJournal));
    actions.setPadding(new Insets(6));
    VBox pane = new VBox(actions, table);
    VBox.setVgrow(table, Priority.ALWAYS);
    tab.setContent(pane);
    tab.setId("alarms-tab");
    show();
    return tab;
  }

  /** The button beside the breadcrumb: seen only while something is active. */
  Button bell() {
    bell.setId("alarm-bell");
    bell.setOnAction(e -> tab.getTabPane().getSelectionModel().select(tab));
    return bell;
  }

  /** Brings the Alarms tab forward; screens call it from their own windows. */
  void bringForward() {
    tab.getTabPane().getSelectionModel().select(tab);
    shell.stage().toFront();
  }

  private void show() {
    List<Row> rows = active();
    Row chosen = table.getSelectionModel().getSelectedItem();
    table.getItems().setAll(rows);
    if (chosen != null)
      for (Row row : rows)
        if (row.rule().id.equals(chosen.rule().id)) table.getSelectionModel().select(row);
    int unacked = 0;
    boolean alarm = false;
    for (Row row : rows)
      if (!row.state().acked) {
        unacked++;
        if (row.rule().severity == Alarms.Severity.ALARM) alarm = true;
      }
    tab.setText(rows.isEmpty() ? Tr.of("tab.alarms") : Tr.of("tab.alarmsCount", rows.size()));
    bell.setText(Tr.of("alarms.bell", rows.size(), unacked));
    bell.setVisible(!rows.isEmpty());
    bell.setManaged(!rows.isEmpty());
    bell.getStyleClass().removeAll("alarm-alarm", "alarm-warning", "alarm-returned");
    bell.getStyleClass()
        .add(unacked == 0 ? "alarm-returned" : alarm ? "alarm-alarm" : "alarm-warning");
    if (rulesStage != null && rulesStage.isShowing()) listRules();
  }

  private <T> TableColumn<T, String> column(
      String title, double width, java.util.function.Function<T, String> text) {
    TableColumn<T, String> c = new TableColumn<>(title);
    c.setPrefWidth(width);
    c.setSortable(false);
    c.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
    return c;
  }

  private Button button(String text, String id, Runnable action) {
    Button b = new Button(text);
    b.setId(id);
    b.setOnAction(e -> notices.guard(action));
    return b;
  }

  private static String time(long at) {
    return TIME.format(Instant.ofEpochMilli(at));
  }

  private static String text(Alarms.Severity s) {
    return s == Alarms.Severity.ALARM ? Tr.of("alarms.sev.alarm") : Tr.of("alarms.sev.warning");
  }

  private static String text(Alarms.Kind k) {
    switch (k) {
      case TRUE:
        return Tr.of("alarms.kind.true");
      case FALSE:
        return Tr.of("alarms.kind.false");
      case HIGH:
        return Tr.of("alarms.kind.high");
      default:
        return Tr.of("alarms.kind.low");
    }
  }

  private static String text(Alarms.State s) {
    if (s.phase == Alarms.Phase.RETURNED) return Tr.of("alarms.state.returned");
    return s.acked ? Tr.of("alarms.state.acked") : Tr.of("alarms.state.active");
  }

  // ---------------------------------------------------------------- rules

  /** One undoable change of the rules; the result must be something the project file can hold. */
  void change(Consumer<Alarms> edit) {
    if (Alarms.foreign(history.get()) != null)
      throw new IllegalStateException(Tr.of("alarms.foreign"));
    shell.edit(
        p -> {
          if (Alarms.of(p) == null) Alarms.put(p, new Alarms());
          edit.accept(Alarms.of(p));
          AlarmStore.check(Alarms.of(p));
          if (Alarms.of(p).rules.isEmpty()) Alarms.put(p, null);
        });
  }

  /** A new rule for a block: its first boolean output, else its first number. Returns the id. */
  String addFor(String nodeId) {
    Project p = history.get();
    Project.Node n = p.nodes.get(nodeId);
    if (n == null || n.id.equals("0")) throw new IllegalStateException(Tr.of("alarms.pickBlock"));
    Project.Port found = null;
    for (Alarms.Kind k : List.of(Alarms.Kind.TRUE, Alarms.Kind.HIGH))
      for (boolean outputs : new boolean[] {true, false})
        for (Project.Port port : n.ports)
          if (found == null
              && Alarms.fits(k, port.type)
              && (outputs ? !port.input && !port.config : true)) found = port;
    if (found == null) throw new IllegalStateException(Tr.of("alarms.nothingToWatch", n.name));
    Alarms.Rule r = new Alarms.Rule();
    r.id = Alarms.of(p) == null ? "a1" : Alarms.of(p).freeId();
    r.path = ScreenBindings.folderPath(p, n.id);
    r.slot = found.name;
    r.kind = Alarms.fits(Alarms.Kind.TRUE, found.type) ? Alarms.Kind.TRUE : Alarms.Kind.HIGH;
    r.label = n.name + " " + found.name;
    change(a -> a.rules.add(r));
    return r.id;
  }

  private void showRules() {
    if (rulesStage == null) {
      rulesStage = new Stage();
      rulesStage.initOwner(shell.stage());
      rulesStage.setTitle(Tr.of("alarms.rulesTitle"));
      rulesStage.setScene(new Scene(rulesPane(), 860, 460));
    }
    listRules();
    rulesStage.show();
    rulesStage.toFront();
  }

  private BorderPane rulesPane() {
    ruleList.setId("alarm-rules");
    ruleList.setCellFactory(
        v ->
            new ListCell<>() {
              @Override
              protected void updateItem(Alarms.Rule r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                  setText(null);
                  return;
                }
                String problem = problem(r);
                setText(
                    r.label
                        + "\n"
                        + r.path
                        + "."
                        + r.slot
                        + "  "
                        + condition(r)
                        + (problem == null ? "" : "\n" + problem));
              }
            });
    ruleList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> fillForm(b));
    label.setId("alarm-label");
    slot.setId("alarm-slot");
    kind.setId("alarm-kind");
    limit.setId("alarm-limit");
    band.setId("alarm-band");
    delay.setId("alarm-delay");
    severity.setId("alarm-severity");
    where.setId("alarm-where");
    where.setWrapText(true);
    kind.getItems().setAll(Alarms.Kind.values());
    kind.setConverter(converter(AlarmsFeature::text));
    kind.valueProperty().addListener((o, a, b) -> limitsFollowKind());
    severity.getItems().setAll(Alarms.Severity.values());
    severity.setConverter(converter(AlarmsFeature::text));
    GridPane form = new GridPane();
    form.setHgap(8);
    form.setVgap(8);
    form.setPadding(new Insets(10));
    int row = 0;
    form.add(where, 0, row++, 2, 1);
    form.addRow(row++, new Label(Tr.of("alarms.f.label")), label);
    form.addRow(row++, new Label(Tr.of("alarms.f.slot")), slot);
    form.addRow(row++, new Label(Tr.of("alarms.f.kind")), kind);
    form.addRow(row++, new Label(Tr.of("alarms.f.limit")), limit);
    form.addRow(row++, new Label(Tr.of("alarms.f.band")), band);
    form.addRow(row++, new Label(Tr.of("alarms.f.delay")), delay);
    form.addRow(row++, new Label(Tr.of("alarms.f.severity")), severity);
    form.add(button(Tr.of("alarms.save"), "alarm-save", this::saveForm), 1, row);
    form.setMinWidth(380);
    HBox actions =
        new HBox(
            6,
            button(Tr.of("alarms.new"), "alarm-new", this::newForSelected),
            button(Tr.of("alarms.show"), "alarm-go", this::goToRule),
            button(Tr.of("alarms.remove"), "alarm-remove", this::removeRule));
    actions.setPadding(new Insets(6));
    BorderPane pane = new BorderPane(ruleList);
    pane.setTop(actions);
    pane.setRight(form);
    pane.setBottom(hint(Tr.of("alarms.rulesHint")));
    fillForm(null);
    return pane;
  }

  private static Label hint(String text) {
    Label hint = new Label(text);
    hint.setWrapText(true);
    hint.setPadding(new Insets(6, 10, 8, 10));
    return hint;
  }

  private static <T> StringConverter<T> converter(java.util.function.Function<T, String> text) {
    return new StringConverter<>() {
      @Override
      public String toString(T value) {
        return value == null ? "" : text.apply(value);
      }

      @Override
      public T fromString(String s) {
        return null;
      }
    };
  }

  /** "above 28 (back at 27), after 10 s" - the condition of a rule in words. */
  private static String condition(Alarms.Rule r) {
    String text = text(r.kind);
    if (r.kind.numeric()) {
      text += " " + number(r.limit);
      if (r.band > 0) text += " " + Tr.of("alarms.bandNote", number(r.band));
    }
    if (r.delay > 0) text += ", " + Tr.of("alarms.delayNote", r.delay);
    return text + " · " + text(r.severity);
  }

  private static String number(double v) {
    return v == Math.rint(v) && Math.abs(v) < 1e9 ? String.valueOf((long) v) : String.valueOf(v);
  }

  private void listRules() {
    Alarms alarms = Alarms.of(history.get());
    Alarms.Rule chosen = ruleList.getSelectionModel().getSelectedItem();
    ruleList.getItems().setAll(alarms == null ? List.of() : alarms.rules);
    if (chosen != null) select(chosen.id);
  }

  private void select(String id) {
    for (Alarms.Rule r : ruleList.getItems())
      if (r.id.equals(id)) ruleList.getSelectionModel().select(r);
  }

  private void fillForm(Alarms.Rule r) {
    for (javafx.scene.Node field : List.of(label, slot, kind, limit, band, delay, severity))
      field.setDisable(r == null);
    if (r == null) {
      where.setText(Tr.of("alarms.noRule"));
      return;
    }
    where.setText(r.path);
    label.setText(r.label);
    Project.Node n = Alarms.node(history.get(), r.path);
    List<String> slots = new ArrayList<>();
    if (n != null)
      for (Project.Port port : n.ports)
        if (Alarms.fits(Alarms.Kind.TRUE, port.type) || Alarms.fits(Alarms.Kind.HIGH, port.type))
          slots.add(port.name);
    if (!slots.contains(r.slot)) slots.add(r.slot);
    slot.getItems().setAll(slots);
    slot.setValue(r.slot);
    kind.setValue(r.kind);
    limit.setText(number(r.limit));
    band.setText(number(r.band));
    delay.setText(String.valueOf(r.delay));
    severity.setValue(r.severity);
    limitsFollowKind();
  }

  private void limitsFollowKind() {
    boolean numeric = kind.getValue() != null && kind.getValue().numeric();
    limit.setDisable(!numeric || kind.isDisabled());
    band.setDisable(!numeric || kind.isDisabled());
  }

  private void newForSelected() {
    if (focus.selected == null) throw new IllegalStateException(Tr.of("alarms.pickBlock"));
    String id = addFor(focus.selected);
    listRules();
    select(id);
  }

  private void goToRule() {
    Alarms.Rule r = ruleList.getSelectionModel().getSelectedItem();
    if (r != null) goTo(r);
  }

  private void removeRule() {
    Alarms.Rule r = ruleList.getSelectionModel().getSelectedItem();
    if (r == null) return;
    change(a -> a.rules.removeIf(x -> x.id.equals(r.id)));
    listRules();
  }

  private void saveForm() {
    Alarms.Rule chosen = ruleList.getSelectionModel().getSelectedItem();
    if (chosen == null) return;
    Alarms.Rule r = chosen.copy();
    r.label = label.getText().trim();
    r.slot = slot.getValue();
    r.kind = kind.getValue();
    r.severity = severity.getValue();
    r.limit = r.kind.numeric() ? parse(limit.getText(), Tr.of("alarms.f.limit")) : 0;
    r.band = r.kind.numeric() ? parse(band.getText(), Tr.of("alarms.f.band")) : 0;
    double seconds = parse(delay.getText(), Tr.of("alarms.f.delay"));
    if (seconds < 0 || seconds > 3600 || seconds != Math.rint(seconds))
      throw new IllegalArgumentException(Tr.of("alarms.badDelay"));
    r.delay = (int) seconds;
    if (r.band < 0) throw new IllegalArgumentException(Tr.of("alarms.badBand"));
    Project.Node n = Alarms.node(history.get(), r.path);
    Project.Port port = n == null ? null : n.port(r.slot);
    if (port != null && !Alarms.fits(r.kind, port.type))
      throw new IllegalArgumentException(Tr.of("alarms.kindNeedsType", text(r.kind), port.type));
    change(
        a -> {
          for (int i = 0; i < a.rules.size(); i++)
            if (a.rules.get(i).id.equals(r.id)) a.rules.set(i, r);
        });
    listRules();
    select(r.id);
  }

  private static double parse(String text, String field) {
    try {
      double v = Double.parseDouble(text.trim().replace(',', '.'));
      if (Double.isFinite(v)) return v;
    } catch (NumberFormatException notNumber) {
      // Reported below.
    }
    throw new IllegalArgumentException(Tr.of("alarms.notNumber", field));
  }

  // ---------------------------------------------------------------- journal

  private void showJournal() {
    if (journalStage == null) {
      journalStage = new Stage();
      journalStage.initOwner(shell.stage());
      journalStage.setTitle(Tr.of("alarms.journalTitle"));
      journalTable.setId("alarm-journal");
      journalTable.setPlaceholder(new Label(Tr.of("alarms.journalEmpty")));
      journalTable
          .getColumns()
          .add(this.<Alarms.Event>column(Tr.of("alarms.col.time"), 130, e -> time(e.time())));
      journalTable
          .getColumns()
          .add(this.<Alarms.Event>column(Tr.of("alarms.col.event"), 170, e -> what(e.what())));
      journalTable
          .getColumns()
          .add(this.<Alarms.Event>column(Tr.of("alarms.col.label"), 240, Alarms.Event::label));
      journalTable
          .getColumns()
          .add(this.<Alarms.Event>column(Tr.of("alarms.col.where"), 260, Alarms.Event::path));
      journalTable
          .getColumns()
          .add(this.<Alarms.Event>column(Tr.of("alarms.col.value"), 100, Alarms.Event::value));
      BorderPane pane = new BorderPane(journalTable);
      pane.setBottom(hint(Tr.of("alarms.journalHint", directory().resolve("alarms.jsonl"))));
      journalStage.setScene(new Scene(pane, 940, 480));
    }
    loadJournal();
    journalStage.show();
    journalStage.toFront();
  }

  private void loadJournal() {
    try {
      List<Alarms.Event> events = new ArrayList<>(AlarmStore.journal(directory(), 500));
      java.util.Collections.reverse(events);
      journalTable.getItems().setAll(events);
    } catch (IOException e) {
      notices.message(Tr.of("alarms.journalFailed", e.getMessage()));
    }
  }

  private static String what(String what) {
    switch (what) {
      case "raised":
        return Tr.of("alarms.ev.raised");
      case "cleared":
        return Tr.of("alarms.ev.cleared");
      default:
        return Tr.of("alarms.ev.acked");
    }
  }

  void closeAll() {
    if (rulesStage != null) rulesStage.close();
    if (journalStage != null) journalStage.close();
  }

  /** Test hooks: the rules and journal windows as the user opens them. */
  Stage rulesWindow() {
    showRules();
    return rulesStage;
  }

  Stage journalWindow() {
    showJournal();
    return journalStage;
  }
}
