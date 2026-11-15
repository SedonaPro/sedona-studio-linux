package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import java.util.prefs.Preferences;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.toolchain.KitHelp;
import ru.bccontrol.sedonastudio.toolchain.Sdk;

/**
 * Help on component types, in the words of the kit authors: the short text for the palette and the
 * blocks, and a window with the whole description and every property.
 */
final class TypeHelp {
  private final Preferences prefs;
  private KitHelp kitHelp;
  private String kitHelpSdk;

  TypeHelp(Preferences prefs) {
    this.prefs = prefs;
  }

  /** The kit sources of the SDK chosen in the settings; re-read when another SDK is chosen. */
  private KitHelp kit() {
    String chosen = prefs.get("sdkPath", "");
    if (kitHelp == null || !chosen.equals(kitHelpSdk)) {
      kitHelp = KitHelp.of(Sdk.find(chosen.isEmpty() ? null : chosen).orElse(null));
      kitHelpSdk = chosen;
    }
    return kitHelp;
  }

  /** Help text of a type cut to fit a block; "" without the kit sources. */
  String text(String type) {
    return kit().text(type);
  }

  /** The first paragraph of the help, for the palette. */
  String brief(String type) {
    String text = kit().full(type);
    int end = text.indexOf('\n');
    if (end > 0) text = text.substring(0, end);
    return text.length() > 240 ? text.substring(0, 240) + "…" : text;
  }

  /** One row per property: name, type, kind, default value, the author's comment. */
  List<String[]> rows(Project.Node template) {
    Map<String, String> about = kit().slots(template.type);
    List<String[]> rows = new ArrayList<>();
    for (Project.Port port : template.ports)
      rows.add(
          new String[] {
            port.name,
            port.type,
            port.readonly
                ? Tr.of("typeHelp.readonly")
                : port.config ? Tr.of("typeHelp.config") : Tr.of("typeHelp.runtime"),
            template.values.getOrDefault(port.name, ""),
            about.getOrDefault(port.name, "")
          });
    return rows;
  }

  /** Opens the window with the whole description of a type. */
  Stage show(Project.Node template, Window owner) {
    String text = kit().full(template.type);
    Label title = new Label(template.type);
    title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
    Label description = new Label(text.isEmpty() ? Tr.of("typeHelp.none") : text);
    description.setId("type-help-text");
    description.setWrapText(true);
    TableView<String[]> slots = new TableView<>();
    slots.setId("type-help-slots");
    String[] heads = {
      Tr.of("typeHelp.slot"),
      Tr.of("typeHelp.type"),
      Tr.of("typeHelp.kind"),
      Tr.of("typeHelp.default"),
      Tr.of("typeHelp.about")
    };
    double[] widths = {110, 110, 110, 90, 360};
    for (int i = 0; i < heads.length; i++) {
      int column = i;
      TableColumn<String[], String> c = new TableColumn<>(heads[i]);
      c.setCellValueFactory(row -> new SimpleStringProperty(row.getValue()[column]));
      c.setPrefWidth(widths[i]);
      if (i == heads.length - 1)
        c.setCellFactory(
            col ->
                new TableCell<>() {
                  private final Label wrapped = new Label();

                  {
                    wrapped.setWrapText(true);
                    wrapped.setMaxWidth(340);
                  }

                  @Override
                  protected void updateItem(String value, boolean empty) {
                    super.updateItem(value, empty);
                    wrapped.setText(empty || value == null ? "" : value);
                    setGraphic(empty ? null : wrapped);
                  }
                });
      slots.getColumns().add(c);
    }
    slots.getItems().addAll(rows(template));
    slots.setPlaceholder(new Label(Tr.of("typeHelp.noSlots")));
    VBox box =
        new VBox(10, title, new Label(Tr.of("typeHelp.kit", template.kitPart)), description, slots);
    box.setPadding(new Insets(14));
    VBox.setVgrow(slots, Priority.ALWAYS);
    Stage stage = new Stage();
    if (owner != null) stage.initOwner(owner);
    stage.setTitle(Tr.of("typeHelp.title", template.type));
    stage.setScene(new Scene(box, 820, 560));
    stage.show();
    return stage;
  }
}
