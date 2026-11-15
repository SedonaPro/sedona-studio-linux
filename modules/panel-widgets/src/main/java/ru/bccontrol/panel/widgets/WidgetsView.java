package ru.bccontrol.panel.widgets;

import static ru.bccontrol.panel.api.PanelData.*;

import java.util.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import ru.bccontrol.panel.api.*;

final class WidgetsView implements PanelModule.PanelView {
  private final PanelHost host;
  private final VBox root = new VBox(10), tiles = new VBox(8);
  private final Label status = new Label();
  private final List<PanelDocument.Widget> widgets = new ArrayList<>();
  private final Map<Label, PanelDocument.Widget> values = new HashMap<>();
  private final ComboBox<Component> components = new ComboBox<>();
  private final ComboBox<String> fields = new ComboBox<>(), kind = new ComboBox<>();
  private final CheckBox edit = new CheckBox(PanelText.of("panelmodule.setup", "Panel setup"));
  private final PanelHost.Subscription subscription;
  private boolean closed, broken;
  private final Set<Ref> booleanFields = new HashSet<>();

  WidgetsView(PanelHost host) {
    this.host = host;
    root.setPadding(new Insets(12));
    root.setId("independent-panel");
    status.setWrapText(true);
    try {
      widgets.addAll(PanelDocument.read(host.loadDocument()));
    } catch (Exception ex) {
      broken = true;
      status.setText(PanelText.of("panelmodule.notRead", "Panel not read: {0}", ex.getMessage()));
    }
    kind.getItems()
        .addAll(
            PanelText.of("panelmodule.kindValue", "Value"),
            PanelText.of("panelmodule.kindIndicator", "Indicator"));
    kind.setValue(PanelText.of("panelmodule.kindValue", "Value"));
    components.setMaxWidth(Double.MAX_VALUE);
    fields.setMaxWidth(Double.MAX_VALUE);
    components
        .valueProperty()
        .addListener(
            (o, a, b) -> {
              fields.getItems().clear();
              if (b != null)
                for (Field f : b.fields()) if (f.readable()) fields.getItems().add(f.name());
              if (!fields.getItems().isEmpty()) fields.getSelectionModel().selectFirst();
            });
    Button add = new Button(PanelText.of("panelmodule.addWidget", "Add widget"));
    add.setId("panel-add-widget");
    add.setOnAction(
        e -> {
          Component c = components.getValue();
          String f = fields.getValue();
          if (c == null || f == null || broken) return;
          if (kind.getValue().equals(PanelText.of("panelmodule.kindIndicator", "Indicator"))
              && c.fields().stream()
                  .noneMatch(
                      field ->
                          field.name().equals(f)
                              && (field.type().equals("bool")
                                  || field.type().equals("sys::bool")))) {
            status.setText(
                PanelText.of(
                    "panelmodule.indicatorNeedsBoolean", "An indicator needs a Boolean field"));
            return;
          }
          if (widgets.size() >= 32) {
            status.setText(PanelText.of("panelmodule.widgetLimit", "No more than 32 widgets"));
            return;
          }
          widgets.add(
              new PanelDocument.Widget(
                  kind.getValue().equals(PanelText.of("panelmodule.kindIndicator", "Indicator"))
                      ? "indicator"
                      : "value",
                  c.name() + " · " + f,
                  new Ref(c.path(), f)));
          save();
          render();
          update(host.snapshot());
        });
    VBox config =
        new VBox(
            7,
            new Label(PanelText.of("panelmodule.component", "Component")),
            components,
            new Label(PanelText.of("panelmodule.field", "Field")),
            fields,
            kind,
            add);
    config.visibleProperty().bind(edit.selectedProperty());
    config.managedProperty().bind(edit.selectedProperty());
    edit.setSelected(widgets.isEmpty());
    ScrollPane scroll = new ScrollPane(tiles);
    scroll.setFitToWidth(true);
    VBox.setVgrow(scroll, Priority.ALWAYS);
    root.getChildren().addAll(edit, config, status, scroll);
    render();
    subscription = host.subscribe(this::update);
  }

  private void save() {
    try {
      host.saveDocument(PanelDocument.write(widgets));
      status.setText(PanelText.of("panelmodule.saved", "Panel saved"));
    } catch (Exception ex) {
      status.setText(PanelText.of("panelmodule.notSaved", "Could not save: {0}", ex.getMessage()));
    }
  }

  private void render() {
    tiles.getChildren().clear();
    values.clear();
    for (PanelDocument.Widget w : widgets) {
      Label title = new Label(w.title()), value = new Label("—");
      value.setId("panel-value-" + values.size());
      value.setStyle("-fx-font-size: 22px;");
      value.setWrapText(true);
      Button remove = new Button(PanelText.of("panelmodule.remove", "Remove"));
      remove.visibleProperty().bind(edit.selectedProperty());
      remove.managedProperty().bind(edit.selectedProperty());
      remove.setOnAction(
          e -> {
            widgets.remove(w);
            save();
            render();
            update(host.snapshot());
          });
      VBox tile =
          new VBox(
              6,
              title,
              value,
              new Label(w.ref().componentPath() + " · " + w.ref().field()),
              remove);
      tile.setPadding(new Insets(10));
      tile.setStyle(
          "-fx-background-color: #f3f7f8; -fx-border-color: #c4d4da; -fx-border-radius: 6;");
      tiles.getChildren().add(tile);
      values.put(value, w);
    }
    host.watch(new HashSet<>(widgets.stream().map(PanelDocument.Widget::ref).toList()));
  }

  private void update(Snapshot snapshot) {
    if (closed) return;
    String selected = components.getValue() == null ? null : components.getValue().path();
    if (!components.getItems().equals(snapshot.components())) {
      booleanFields.clear();
      for (Component component : snapshot.components())
        for (Field field : component.fields())
          if (field.type().equals("bool") || field.type().equals("sys::bool"))
            booleanFields.add(new Ref(component.path(), field.name()));
      components.getItems().setAll(snapshot.components());
      components.getItems().stream()
          .filter(c -> c.path().equals(selected))
          .findFirst()
          .ifPresentOrElse(
              components::setValue, () -> components.getSelectionModel().selectFirst());
    }
    for (var entry : values.entrySet()) {
      Sample sample = snapshot.values().get(entry.getValue().ref());
      Label label = entry.getKey();
      if (sample == null) {
        label.setText(PanelText.of("panelmodule.unavailable", "Unavailable"));
        label.setStyle("-fx-font-size: 20px; -fx-text-fill: #956427;");
        continue;
      }
      if (entry.getValue().kind().equals("indicator")
          && !booleanFields.contains(entry.getValue().ref())) {
        label.setText(PanelText.of("panelmodule.incompatibleType", "Incompatible type"));
        label.setStyle("-fx-font-size: 20px; -fx-text-fill: #956427;");
        continue;
      }
      String text =
          entry.getValue().kind().equals("indicator")
              ? (sample.value().equalsIgnoreCase("true") || sample.value().equals("1")
                  ? PanelText.of("panelmodule.on", "● On")
                  : PanelText.of("panelmodule.off", "○ Off"))
              : sample.value();
      label.setText(text);
      label.setStyle(
          "-fx-font-size: 22px; -fx-text-fill: "
              + (sample.quality() == Quality.GOOD ? "#287e73" : "#956427")
              + ";");
      label.setTooltip(
          new Tooltip(
              switch (sample.quality()) {
                    case GOOD -> PanelText.of("panelmodule.fresh", "Fresh");
                    case STALE -> PanelText.of("panelmodule.stale", "Stale");
                    case OFFLINE -> PanelText.of("panelmodule.offline", "Offline");
                    case LOCAL -> PanelText.of("panelmodule.local", "Local");
                    case ERROR -> PanelText.of("panelmodule.error", "Error");
                  }
                  + " · "
                  + sample.detail()));
    }
    if (!broken)
      status.setText(
          snapshot.connected()
              ? PanelText.of("panelmodule.connected", "Connected")
              : PanelText.of("panelmodule.offlineSaved", "Offline · saved data is shown"));
  }

  public Node content() {
    return root;
  }

  public void close() {
    if (closed) return;
    closed = true;
    subscription.close();
    host.watch(Set.of());
  }
}
