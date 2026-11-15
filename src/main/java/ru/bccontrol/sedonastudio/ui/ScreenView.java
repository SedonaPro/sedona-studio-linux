package ru.bccontrol.sedonastudio.ui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.transform.Rotate;
import ru.bccontrol.sedonastudio.model.Screens;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.ScreenStore;
import ru.bccontrol.sedonastudio.storage.SvgSymbol;

/**
 * One operator screen drawn at its own size: the background picture, the equipment symbols and the
 * widgets. It only reads through {@link ScreenData}; call {@link #refresh} when values change and
 * {@link #dispose} when the screen goes away (running animations are stopped).
 */
final class ScreenView extends Pane {
  /** Size of a screen without a background picture, as in the web editor. */
  static final double DEFAULT_WIDTH = 1200, DEFAULT_HEIGHT = 700;

  private final Screens.View view;
  private final Screens screens;
  private final ScreenData data;
  private final double stageWidth, stageHeight;
  private final List<Runnable> updates = new ArrayList<>();
  private final List<SvgFx.Drawn> drawn = new ArrayList<>();
  private final Map<String, Node> nodes = new HashMap<>();

  /** Where the items being drawn go: the screen itself, or the box of a graphic component. */
  private javafx.scene.layout.Pane into = this;

  private double boxWidth, boxHeight;

  /** True when the background or a symbol has parts this editor cannot draw. */
  boolean partial;

  ScreenView(Screens.View view, Screens screens, ScreenData data) {
    this.view = view;
    this.screens = screens;
    this.data = data;
    double w = DEFAULT_WIDTH, h = DEFAULT_HEIGHT;
    SvgSymbol background = null;
    if (!view.svg.isEmpty())
      try {
        background =
            SvgSymbol.parseDrawing(view.svg.getBytes(StandardCharsets.UTF_8), ScreenStore.LIMIT);
        w = background.viewBox[2];
        h = background.viewBox[3];
        partial |= background.partial;
      } catch (IllegalArgumentException unreadable) {
        partial = true;
      }
    stageWidth = w;
    stageHeight = h;
    setPrefSize(w, h);
    setMinSize(w, h);
    setMaxSize(w, h);
    setStyle("-fx-background-color: #eef5f2;");
    setClip(new javafx.scene.shape.Rectangle(w, h));
    if (background != null) {
      SvgFx.Drawn picture = SvgFx.draw(background, w, h);
      drawn.add(picture);
      getChildren().add(picture.node());
    }
    boxWidth = w;
    boxHeight = h;
    if (view.objects != null) for (Screens.Symbol o : view.objects) addSymbol(o);
    if (view.parts != null) for (Screens.Part part : view.parts) addPart(part);
    if (view.widgets != null) for (Screens.Widget widget : view.widgets) addWidget(widget);
    refresh();
  }

  double stageWidth() {
    return stageWidth;
  }

  double stageHeight() {
    return stageHeight;
  }

  /** The node of a widget or symbol by its id (for selection and tests). */
  Node node(String id) {
    return nodes.get(id);
  }

  /** Shows the current values. */
  void refresh() {
    for (Runnable update : updates) update.run();
  }

  void dispose() {
    for (SvgFx.Drawn d : drawn) d.stop();
    drawn.clear();
    updates.clear();
  }

  // ------------------------------------------------------------------ graphic components

  /**
   * A graphic component is drawn as a box of its own with the symbols and widgets of its definition
   * inside, each reading the slot bound to its point. The box is one item for the editor: it is
   * selected and moved as a whole.
   */
  private void addPart(Screens.Part part) {
    Screens.Component def = screens.component(part.componentId);
    if (def == null) return;
    javafx.scene.layout.Pane box = new javafx.scene.layout.Pane();
    box.setId("screen-part-" + part.id);
    box.setLayoutX(part.x / 100 * stageWidth);
    box.setLayoutY(part.y / 100 * stageHeight);
    box.setPrefSize(def.width, def.height);
    box.setMinSize(def.width, def.height);
    box.setMaxSize(def.width, def.height);
    Tooltip.install(box, new Tooltip(part.label));
    Screens.View inner = Screens.inner(def, part);
    into = box;
    boxWidth = def.width;
    boxHeight = def.height;
    try {
      if (inner.objects != null)
        for (Screens.Symbol o : inner.objects) {
          o.id = part.id + "-" + o.id;
          addSymbol(o);
        }
      if (inner.widgets != null)
        for (Screens.Widget w : inner.widgets) {
          w.id = part.id + "-" + w.id;
          addWidget(w);
        }
    } finally {
      into = this;
      boxWidth = stageWidth;
      boxHeight = stageHeight;
    }
    nodes.put(part.id, box);
    getChildren().add(box);
  }

  // ------------------------------------------------------------------ symbols

  private void addSymbol(Screens.Symbol o) {
    Screens.Asset asset = screens.asset(o.assetId);
    if (asset == null) return;
    Group box = new Group();
    box.setId("screen-object-" + o.id);
    box.setLayoutX(o.x / 100 * boxWidth);
    box.setLayoutY(o.y / 100 * boxHeight);
    if (o.rotation != 0)
      box.getTransforms().add(new Rotate(o.rotation, asset.width / 2, asset.height / 2));
    Label badge = new Label();
    badge.setStyle(
        "-fx-font-weight: bold; -fx-text-fill: white; -fx-background-radius: 9; -fx-padding: 0 6 0"
            + " 6;");
    Tooltip tip = new Tooltip();
    Tooltip.install(box, tip);
    String[] shown = {null};
    SvgFx.Drawn[] current = {null};
    updates.add(
        () -> {
          ScreenData.Reading state = o.binding == null ? null : data.read(view.folder, o.binding),
              fault = o.fault == null ? null : data.read(view.folder, o.fault);
          boolean live =
              (state == null || state.live() && state.problem() == null)
                  && (fault == null || fault.live() && fault.problem() == null);
          String wanted =
              Screens.state(
                  o,
                  state == null ? null : state.value(),
                  fault == null ? null : fault.value(),
                  live);
          String problem =
              state != null && state.problem() != null
                  ? state.problem()
                  : fault != null && fault.problem() != null ? fault.problem() : null;
          tip.setText(o.label + (problem == null ? "" : "\n" + problem));
          if (wanted.equals(shown[0])) return;
          shown[0] = wanted;
          if (current[0] != null) {
            current[0].stop();
            drawn.remove(current[0]);
          }
          // A library picture need not have every state: fall back to Off and mark it.
          String svg = asset.states.getOrDefault(wanted, asset.states.get("off"));
          box.getChildren().clear();
          try {
            SvgSymbol symbol =
                SvgSymbol.parseDrawing(svg.getBytes(StandardCharsets.UTF_8), ScreenStore.LIMIT);
            partial |= symbol.partial;
            current[0] = SvgFx.draw(symbol, asset.width, asset.height);
            drawn.add(current[0]);
            box.getChildren().add(current[0].node());
          } catch (IllegalArgumentException unreadable) {
            partial = true;
          }
          boolean marked = !asset.states.containsKey(wanted) || wanted.equals("unknown");
          if (wanted.equals("fault") || wanted.equals("unknown")) {
            badge.setText(wanted.equals("fault") ? "!" : "?");
            badge.setStyle(
                badge.getStyle().replaceAll("-fx-background-color: [^;]*;", "")
                    + "-fx-background-color: "
                    + (wanted.equals("fault") ? "#d6453d" : "#8a949b")
                    + ";");
            box.getChildren().add(badge);
          }
          box.setOpacity(wanted.equals("unknown") && marked ? 0.6 : 1);
        });
    nodes.put(o.id, box);
    into.getChildren().add(box);
  }

  // ------------------------------------------------------------------ widgets

  private void addWidget(Screens.Widget w) {
    String appearance = w.options.get("appearance") instanceof String a ? a : "card";
    boolean plain = appearance.equals("value") || appearance.equals("lamp");
    VBox card = new VBox(2);
    card.setId("screen-widget-" + w.id);
    card.setLayoutX(w.x / 100 * boxWidth);
    card.setLayoutY(w.y / 100 * boxHeight);
    double width =
        w.number(
            "width", appearance.equals("card") ? 185 : appearance.equals("compact") ? 150 : 90);
    card.setPrefWidth(width);
    card.setMaxWidth(width);
    boolean background = w.flag("background", !plain), border = w.flag("border", !plain);
    card.setPadding(
        plain
            ? new Insets(2, 4, 2, 4)
            : appearance.equals("compact") ? new Insets(5, 8, 5, 8) : new Insets(10, 12, 10, 12));
    card.setStyle(
        (background ? "-fx-background-color: #fffffff2;" : "")
            + (border ? "-fx-border-color: #b8cfc5; -fx-border-radius: 8;" : "")
            + "-fx-background-radius: 8;");
    Label label = new Label(w.label);
    label.setStyle("-fx-text-fill: #4a5f57; -fx-font-size: 11px;");
    label.setWrapText(true);
    if (w.flag("showLabel", true)) card.getChildren().add(label);
    double font = w.number("fontSize", appearance.equals("card") ? 25 : 16);
    Tooltip tip = new Tooltip();
    Tooltip.install(card, tip);
    if (w.kind.equals("text")) {
      label.setStyle("-fx-text-fill: #22302b; -fx-font-size: " + font * 0.7 + "px;");
      if (!card.getChildren().contains(label)) card.getChildren().add(label);
    } else if (Screens.DATA_KINDS.contains(w.kind)) addChannels(w, card);
    else addReading(w, card, tip, appearance, font);
    nodes.put(w.id, card);
    into.getChildren().add(card);
  }

  /** A single value: number, lamp, bar or gauge, with its quality line. */
  private void addReading(
      Screens.Widget w, VBox card, Tooltip tip, String appearance, double font) {
    Label reading = new Label("—");
    reading.setId("screen-reading-" + w.id);
    reading.setStyle("-fx-font-size: " + font + "px; -fx-text-fill: #1c2a25;");
    Label unit = new Label(w.flag("showUnit", true) ? w.unit : "");
    unit.setStyle("-fx-text-fill: #5c6f68;");
    Circle lamp = new Circle(7);
    HBox line = new HBox(6);
    line.setAlignment(Pos.BASELINE_LEFT);
    boolean boolKind = Screens.BOOL_KINDS.contains(w.kind);
    if (boolKind) {
      line.setAlignment(Pos.CENTER_LEFT);
      line.getChildren().add(lamp);
      if (!appearance.equals("lamp")) line.getChildren().add(reading);
    } else line.getChildren().addAll(reading, unit);
    card.getChildren().add(line);
    ProgressBar bar = new ProgressBar(0);
    bar.setMaxWidth(Double.MAX_VALUE);
    if (w.kind.equals("bar") || w.kind.equals("gauge") || w.kind.equals("slider")) {
      Label range = new Label(trim(w.number("min", 0)) + " … " + trim(w.number("max", 100)));
      range.setStyle("-fx-text-fill: #5c6f68; -fx-font-size: 10px;");
      card.getChildren().addAll(bar, range);
    }
    Label quality = new Label();
    quality.setStyle("-fx-text-fill: #6c7d76; -fx-font-size: 10px;");
    if (w.flag("showQuality", true)) card.getChildren().add(quality);
    // Controls of the kinds that write; each hands the new value to the data source.
    javafx.scene.control.Button act = new javafx.scene.control.Button();
    act.setId("screen-write-" + w.id);
    act.setMaxWidth(Double.MAX_VALUE);
    javafx.scene.control.TextField entry = new javafx.scene.control.TextField();
    entry.setId("screen-entry-" + w.id);
    javafx.scene.control.Slider slider =
        new javafx.scene.control.Slider(
            w.number("min", 0), w.number("max", 100), w.number("min", 0));
    Label failure = new Label();
    failure.setWrapText(true);
    failure.setStyle("-fx-text-fill: #b3261e; -fx-font-size: 10px;");
    failure.setManaged(false);
    String[] last = {null};
    java.util.function.Consumer<String> send =
        value -> {
          try {
            data.write(view.folder, w.binding(), value);
            failure.setText("");
            failure.setManaged(false);
          } catch (RuntimeException refused) {
            failure.setText(refused.getMessage());
            failure.setManaged(true);
          }
        };
    switch (w.kind) {
      case "setpoint" -> {
        entry.setPromptText(Tr.of("screens.newValue"));
        act.setText(Tr.of("screens.write"));
        act.setOnAction(e -> send.accept(entry.getText().trim()));
        entry.setOnAction(e -> act.fire());
        card.getChildren().addAll(entry, act, failure);
      }
      case "slider" -> {
        act.setText(Tr.of("screens.write"));
        slider
            .valueProperty()
            .addListener(
                (o, a, b) ->
                    act.setText(
                        Tr.of("screens.write")
                            + " "
                            + String.format(
                                Locale.ROOT, "%." + w.decimals + "f", b.doubleValue())));
        act.setOnAction(
            e ->
                send.accept(
                    String.format(Locale.ROOT, "%." + w.decimals + "f", slider.getValue())));
        card.getChildren().addAll(slider, act, failure);
      }
      case "toggle" -> {
        act.setOnAction(e -> send.accept("true".equals(last[0]) ? "false" : "true"));
        card.getChildren().addAll(act, failure);
      }
      case "button" -> {
        act.setText(w.label);
        act.setOnAction(e -> send.accept(String.valueOf(w.flag("command", true))));
        card.getChildren().addAll(act, failure);
      }
      default -> {}
    }
    boolean writes = Screens.WRITE_KINDS.contains(w.kind);
    updates.add(
        () -> {
          boolean unbound = w.slot.equals("__unbound");
          ScreenData.Reading r =
              unbound
                  ? new ScreenData.Reading(null, Tr.of("screens.chooseValue"), false)
                  : data.read(view.folder, w.binding());
          boolean ok = r.problem() == null && r.value() != null;
          String state =
              r.problem() != null
                  ? r.problem()
                  : r.value() == null
                      ? Tr.of("screens.noValue")
                      : r.live() ? Tr.of("screens.online") : Tr.of("screens.snapshot");
          quality.setText(state);
          tip.setText(w.label + " · " + state);
          if (writes) {
            last[0] = ok ? r.value() : null;
            String blocked = unbound ? state : data.writeProblem(view.folder, w.binding());
            act.setDisable(blocked != null || w.kind.equals("toggle") && !ok);
            entry.setDisable(blocked != null);
            slider.setDisable(blocked != null);
            act.setTooltip(blocked == null ? null : new Tooltip(blocked));
            if (w.kind.equals("toggle"))
              act.setText(
                  !ok
                      ? Tr.of("screens.noValue")
                      : "true".equals(r.value())
                          ? Tr.of("screens.switchOff")
                          : Tr.of("screens.switchOn"));
          }
          card.setOpacity(ok && !r.live() ? 0.75 : 1);
          Double number = ok ? number(r.value()) : null;
          if (boolKind) {
            Boolean on =
                !ok
                    ? null
                    : r.value().equals("true")
                        ? Boolean.TRUE
                        : r.value().equals("false") ? Boolean.FALSE : null;
            lamp.setFill(
                on == null
                    ? Color.web("#b6bfc3")
                    : on ? Color.web("#2a9d6f") : Color.web("#7d8a90"));
            reading.setText(
                !ok
                    ? "—"
                    : on == null
                        ? Tr.of("screens.notBool")
                        : on ? Tr.of("screens.on") : Tr.of("screens.off"));
          } else
            reading.setText(
                !ok
                    ? "—"
                    : number != null && numeric(w.type)
                        ? String.format(Locale.ROOT, "%." + w.decimals + "f", number)
                        : r.value());
          double min = w.number("min", 0), max = w.number("max", 100);
          bar.setProgress(
              number == null ? 0 : Math.max(0, Math.min(1, (number - min) / (max - min))));
        });
  }

  /** A table of current values, bars against a common scale, or a trend over the last minutes. */
  private void addChannels(Screens.Widget w, VBox card) {
    if (w.series == null) return;
    double width = w.number("width", 360);
    card.setPrefWidth(width);
    card.setMaxWidth(width);
    if (w.series.isEmpty()) {
      Label empty = new Label(Tr.of("screens.noChannels"));
      empty.setWrapText(true);
      empty.setStyle("-fx-text-fill: #6c7d76;");
      card.getChildren().add(empty);
      return;
    }
    if (w.kind.equals("trend")) {
      addTrend(w, card);
      return;
    }
    double min = w.number("min", 0), max = w.number("max", 100);
    for (Screens.Series s : w.series) {
      Label name = new Label(s.label), value = new Label("—"), unit = new Label(s.unit);
      name.setStyle("-fx-text-fill: " + (s.color == null ? "#33443d" : s.color) + ";");
      name.setMaxWidth(Double.MAX_VALUE);
      HBox.setHgrow(name, javafx.scene.layout.Priority.ALWAYS);
      HBox row = new HBox(8, name, value, unit);
      Tooltip rowTip = new Tooltip(s.label);
      Tooltip.install(row, rowTip);
      card.getChildren().add(row);
      ProgressBar bar = new ProgressBar(0);
      bar.setMaxWidth(Double.MAX_VALUE);
      if (s.color != null) bar.setStyle("-fx-accent: " + s.color + ";");
      if (w.kind.equals("bars")) card.getChildren().add(bar);
      updates.add(
          () -> {
            ScreenData.Reading r = data.read(view.folder, s.binding());
            Double number = r.problem() == null && r.value() != null ? number(r.value()) : null;
            value.setText(
                r.problem() != null || r.value() == null
                    ? "—"
                    : number != null
                        ? String.format(Locale.ROOT, "%." + w.decimals + "f", number)
                        : r.value());
            bar.setProgress(
                number == null ? 0 : Math.max(0, Math.min(1, (number - min) / (max - min))));
            row.setOpacity(r.live() ? 1 : 0.75);
            rowTip.setText(r.problem() == null ? s.label : r.problem());
          });
    }
  }

  /** Lines of the last windowSeconds, one per channel; a point is added every sampleSeconds. */
  private void addTrend(Screens.Widget w, VBox card) {
    double window = w.number("windowSeconds", 300), every = w.number("sampleSeconds", 1);
    javafx.scene.chart.NumberAxis time = new javafx.scene.chart.NumberAxis(-window, 0, window / 5),
        scale = new javafx.scene.chart.NumberAxis();
    time.setTickLabelFormatter(
        new javafx.util.StringConverter<>() {
          @Override
          public String toString(Number seconds) {
            long s = Math.round(-seconds.doubleValue());
            return s == 0
                ? "0"
                : s >= 120
                    ? "-" + s / 60 + Tr.of("screens.minutes")
                    : "-" + s + Tr.of("screens.seconds");
          }

          @Override
          public Number fromString(String text) {
            return 0;
          }
        });
    scale.setForceZeroInRange(false);
    if (!w.flag("autoScale", true)) {
      scale.setAutoRanging(false);
      scale.setLowerBound(w.number("min", 0));
      scale.setUpperBound(w.number("max", 100));
      scale.setTickUnit((w.number("max", 100) - w.number("min", 0)) / 5);
    }
    javafx.scene.chart.LineChart<Number, Number> chart =
        new javafx.scene.chart.LineChart<>(time, scale);
    chart.setId("screen-trend-" + w.id);
    chart.setAnimated(false);
    chart.setCreateSymbols(false);
    chart.setLegendVisible(w.flag("legend", true));
    chart.setPrefHeight(w.number("height", 260));
    chart.setMinHeight(120);
    List<double[]>[] history = new List[w.series.size()];
    for (int i = 0; i < w.series.size(); i++) {
      Screens.Series s = w.series.get(i);
      javafx.scene.chart.XYChart.Series<Number, Number> line =
          new javafx.scene.chart.XYChart.Series<>();
      line.setName(s.label + (s.unit.isEmpty() ? "" : ", " + s.unit));
      chart.getData().add(line);
      if (s.color != null && line.getNode() != null)
        line.getNode().setStyle("-fx-stroke: " + s.color + ";");
      history[i] = new ArrayList<>();
      // What the source has stored of this value: the trend opens with its past.
      long wall = System.currentTimeMillis();
      double base = System.nanoTime() / 1e9;
      for (ru.bccontrol.sedonastudio.model.Archive.Sample past :
          data.past(view.folder, s.binding(), window))
        history[i].add(new double[] {base - (wall - past.time()) / 1000.0, past.value()});
    }
    card.getChildren().add(chart);
    long[] lastSample = {0};
    updates.add(
        () -> {
          long now = System.nanoTime();
          boolean due = lastSample[0] == 0 || (now - lastSample[0]) / 1e9 >= every;
          if (due) lastSample[0] = now;
          for (int i = 0; i < w.series.size(); i++) {
            ScreenData.Reading r = data.read(view.folder, w.series.get(i).binding());
            Double number =
                r.problem() == null && r.value() != null && r.live() ? number(r.value()) : null;
            List<double[]> points = history[i];
            if (due && number != null) points.add(new double[] {now / 1e9, number});
            points.removeIf(point -> now / 1e9 - point[0] > window);
            while (points.size() > 600) points.remove(0);
            List<javafx.scene.chart.XYChart.Data<Number, Number>> shown = new ArrayList<>();
            for (double[] point : points)
              shown.add(new javafx.scene.chart.XYChart.Data<>(point[0] - now / 1e9, point[1]));
            chart.getData().get(i).getData().setAll(shown);
          }
        });
  }

  private static boolean numeric(String type) {
    return List.of("byte", "short", "int", "long", "float", "double")
        .contains(type.replace("sys::", ""));
  }

  private static Double number(String v) {
    try {
      double n = Double.parseDouble(v.trim());
      return Double.isFinite(n) ? n : null;
    } catch (NumberFormatException notNumber) {
      return null;
    }
  }

  private static String trim(double v) {
    return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
  }
}
