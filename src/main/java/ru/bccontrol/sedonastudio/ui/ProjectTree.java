package ru.bccontrol.sedonastudio.ui;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javafx.application.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.stage.*;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.protocol.*;
import ru.bccontrol.sedonastudio.storage.*;

/** Left-hand project tree and the project search tab. */
final class ProjectTree {
  private final History history;
  private final EditorFocus focus;
  private final EditorShell shell;
  TreeView<String> tree;
  boolean treeRendering;
  Runnable refreshProjectSearch = () -> {};

  TextField projectSearch;

  /** Called after the user picked a folder or block in the tree; set by StudioApp. */
  Runnable onNavigate = () -> {};

  /**
   * A component with children that a click selects as a block instead of opening it as a folder
   * (a configurator with a kit widget: its inspector holds "Open widget"); set by StudioApp. Its
   * inside is still reached by expanding it and clicking a child.
   */
  java.util.function.Predicate<Project.Node> selectsAsBlock = n -> false;

  ProjectTree(History history, EditorFocus focus, EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.shell = shell;
  }

  /** Everything a block can be found by: name, type, id, its note and "port=value" pairs. */
  private static String searchText(Project.Node n) {
    StringBuilder text = new StringBuilder(n.name + " " + n.type + " " + n.id + " " + n.note);
    for (Map.Entry<String, String> value : n.values.entrySet())
      if (!value.getKey().equals("meta"))
        text.append(' ').append(value.getKey()).append('=').append(value.getValue());
    return text.toString().toLowerCase(Locale.ROOT);
  }

  /** The note or the "port=value" that matched, when the name, type and id did not. */
  private static String matchedField(Project.Node n, String q) {
    if (q.isEmpty() || (n.name + " " + n.type + " " + n.id).toLowerCase(Locale.ROOT).contains(q))
      return null;
    for (Map.Entry<String, String> value : n.values.entrySet()) {
      String pair = value.getKey() + "=" + value.getValue();
      if (!value.getKey().equals("meta") && pair.toLowerCase(Locale.ROOT).contains(q)) return pair;
    }
    return n.note.toLowerCase(Locale.ROOT).contains(q) ? "✎ " + n.note : null;
  }

  /** The editable fields of a block whose "port=value" contains the query. */
  private static List<Project.Port> matchedPorts(Project.Node n, String q) {
    List<Project.Port> ports = new ArrayList<>();
    if (q.isEmpty()) return ports;
    for (Project.Port port : n.ports) {
      boolean editable =
          !port.readonly
              && (port.asStr || port.type.matches("sys::(bool|byte|short|int|long|float|double)"));
      String pair = port.name + "=" + n.values.getOrDefault(port.name, "");
      if (editable && pair.toLowerCase(Locale.ROOT).contains(q)) ports.add(port);
    }
    return ports;
  }

  /**
   * Puts the new value into every found field as one undo step. Fields the value does not fit
   * (wrong type, too long) stay as they were.
   *
   * @return how many fields were changed and how many were left alone
   */
  int[] replaceFound(String query, String value) {
    String q = query.strip().toLowerCase(Locale.ROOT);
    Map<String, Map<String, String>> changes = new LinkedHashMap<>();
    int changed = 0, skipped = 0;
    for (Project.Node n : history.get().nodes.values())
      for (Project.Port port : matchedPorts(n, q))
        try {
          String literal =
              port.asStr
                  ? ScalarValues.validateText(value, port.textMax)
                  : ScalarValues.validateLiteral(port.type, value);
          if (literal.equals(n.values.get(port.name))) continue;
          changes.computeIfAbsent(n.id, id -> new LinkedHashMap<>()).put(port.name, literal);
          changed++;
        } catch (IllegalArgumentException wrongType) {
          skipped++;
        }
    if (changed > 0)
      shell.edit(p -> changes.forEach((id, values) -> p.nodes.get(id).values.putAll(values)));
    return new int[] {changed, skipped};
  }

  private void collectExpanded(TreeItem<String> item, Set<String> ids) {
    String label = item.getValue();
    if (item.isExpanded())
      ids.add(label.substring(label.lastIndexOf("[#") + 2, label.length() - 1));
    for (TreeItem<String> child : item.getChildren()) collectExpanded(child, ids);
  }

  public void renderTree() {
    Set<String> expanded = new HashSet<>();
    if (tree.getRoot() != null) collectExpanded(tree.getRoot(), expanded);
    treeRendering = true;
    TreeItem<String> rootItem = new TreeItem<>(history.get().name + " [#0]");
    rootItem.setExpanded(true);
    Map<String, TreeItem<String>> items = new HashMap<>();
    items.put("0", rootItem);
    for (Project.Node n : history.get().nodes.values())
      if (!n.id.equals("0"))
        items.put(n.id, new TreeItem<>(n.name + " · " + n.type + " [#" + n.id + "]"));
    for (Project.Node n : history.get().nodes.values())
      if (!n.id.equals("0"))
        items.getOrDefault(n.parent, rootItem).getChildren().add(items.get(n.id));
    for (Map.Entry<String, TreeItem<String>> item : items.entrySet())
      item.getValue().setExpanded(item.getKey().equals("0") || expanded.contains(item.getKey()));
    tree.setRoot(rootItem);
    TreeItem<String> active = items.get(focus.selected == null ? focus.folder : focus.selected);
    if (active != null) {
      for (TreeItem<String> parent = active.getParent();
          parent != null;
          parent = parent.getParent()) parent.setExpanded(true);
      tree.getSelectionModel().select(active);
    }
    treeRendering = false;
  }

  VBox projectSearchPane() {
    projectSearch = new TextField();
    projectSearch.setPromptText(Tr.of("tree.searchPrompt"));
    ListView<Project.Node> results = new ListView<>();
    results.setCellFactory(
        v ->
            new ListCell<Project.Node>() {
              protected void updateItem(Project.Node n, boolean empty) {
                super.updateItem(n, empty);
                String q = projectSearch.getText().strip().toLowerCase(Locale.ROOT);
                String hit = empty || n == null ? null : matchedField(n, q);
                setText(
                    empty || n == null
                        ? null
                        : Tr.of("tree.item", n.name, n.type, n.parent)
                            + (hit == null ? "" : "\n" + hit));
              }
            });
    TextField replacement = new TextField();
    replacement.setId("search-replacement");
    replacement.setPromptText(Tr.of("tree.replaceWith"));
    Button replace = new Button();
    replace.setId("search-replace");
    Label replaced = new Label();
    replaced.setWrapText(true);
    Runnable find =
        () -> {
          String q = projectSearch.getText().strip().toLowerCase(Locale.ROOT);
          results.getItems().clear();
          int fields = 0;
          if (!q.isEmpty())
            for (Project.Node n : history.get().nodes.values()) {
              if (searchText(n).contains(q)) results.getItems().add(n);
              fields += matchedPorts(n, q).size();
            }
          replace.setText(Tr.of("tree.replace", fields));
          replace.setDisable(fields == 0);
        };
    replace.setOnAction(
        e -> {
          int[] done = replaceFound(projectSearch.getText(), replacement.getText());
          replaced.setText(
              done[1] == 0
                  ? Tr.of("tree.replaced", done[0])
                  : Tr.of("tree.replacedSome", done[0], done[1]));
          refreshProjectSearch.run();
        });
    refreshProjectSearch = find;
    projectSearch.textProperty().addListener((o, a, b) -> find.run());
    Button open =
        shell.button(
            Tr.of("tree.showOnDiagram"),
            () -> {
              Project.Node found = results.getSelectionModel().getSelectedItem();
              if (found != null && history.get().nodes.containsKey(found.id)) {
                focus.selected = found.id;
                focus.folder = found.parent;
                focus.selectedWire = null;
                shell.render();
                shell.focusSelected();
              }
            });
    results.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) open.fire();
        });
    projectSearch.setOnAction(
        e -> {
          find.run();
          results.getSelectionModel().selectFirst();
          open.fire();
        });
    find.run();
    VBox pane = new VBox(8, projectSearch, results, open, replacement, replace, replaced);
    pane.setPadding(new Insets(10));
    VBox.setVgrow(results, Priority.ALWAYS);
    return pane;
  }

  /** Creates the tree; picking an item opens its folder or selects the block. */
  TreeView<String> buildTree() {
    tree = new TreeView<>();
    tree.setShowRoot(true);
    tree.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (!treeRendering && b != null) {
                String id =
                    (String)
                        b.getValue()
                            .substring(
                                b.getValue().lastIndexOf("[#") + 2, b.getValue().length() - 1);
                Project.Node n = history.get().nodes.get(id);
                if (n == null
                    || n.type.equals("sys::Folder")
                    || (!b.getChildren().isEmpty() && !selectsAsBlock.test(n))
                    || id.equals("0")) {
                  focus.folder = id;
                  focus.selected = null;
                  focus.selectedWire = null;
                  shell.renderCanvas();
                  onNavigate.run();
                } else {
                  focus.selection.clear();
                  focus.selected = id;
                  focus.selectedWire = null;
                  focus.folder = n.parent;
                  shell.renderCanvas();
                  onNavigate.run();
                }
              }
            });
    return tree;
  }
}
