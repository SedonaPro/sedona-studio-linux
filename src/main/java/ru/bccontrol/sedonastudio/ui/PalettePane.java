package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;

/** Type selection is separate from the project's component tree. */
public final class PalettePane extends VBox {
  private record Entry(String label, Project.Node template) {
    public String toString() {
      return label;
    }
  }

  private final TreeView<Entry> tree = new TreeView<>();
  private final ComboBox<String> kits = new ComboBox<>();
  private final Label count = new Label(), details = new Label(Tr.of("palette.chooseType"));
  private Set<String> projectParts = Set.of();
  private final Set<String> expanded = new HashSet<>();
  private List<Project.Node> all = List.of();
  private String query = "";
  private boolean updating;
  private final Hyperlink fullHelp = new Hyperlink(Tr.of("palette.fullHelp"));

  /** What a type is for, in a sentence or two; set by the editor. */
  public java.util.function.Function<String, String> help = type -> "";

  /** Opens the whole description of a type; set by the editor. */
  public java.util.function.Consumer<Project.Node> more = template -> {};

  public PalettePane() {
    super(7);
    kits.setMaxWidth(Double.MAX_VALUE);
    kits.setPromptText(Tr.of("palette.allKits"));
    tree.setShowRoot(false);
    tree.addEventFilter(
        javafx.scene.input.MouseEvent.MOUSE_PRESSED,
        e -> {
          javafx.scene.Node target = e.getTarget() instanceof javafx.scene.Node node ? node : null;
          while (target != null && !(target instanceof TreeCell<?>)) target = target.getParent();
          if (target instanceof TreeCell<?> cell && !cell.isEmpty())
            tree.getSelectionModel().select(cell.getIndex());
        });
    VBox.setVgrow(tree, Priority.ALWAYS);
    details.setWrapText(true);
    details.setMinWidth(0);
    details.setId("palette-details");
    details.setMinHeight(60);
    details.setMaxHeight(190);
    fullHelp.setId("palette-help");
    fullHelp.setDisable(true);
    fullHelp.setOnAction(
        e -> {
          if (selected() != null) more.accept(selected());
        });
    tree.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              Project.Node n = selected();
              String about = n == null ? "" : help.apply(n.type);
              fullHelp.setDisable(n == null);
              details.setText(
                  n == null
                      ? Tr.of("palette.chooseInKit")
                      : (about.isEmpty() ? "" : about + "\n\n")
                          + Tr.of(
                              "palette.details",
                              n.type,
                              n.kitPart,
                              n.ports.size(),
                              n.ports.stream().filter(p -> p.config).count()));
              if (n != null) {
                StringBuilder preview = new StringBuilder(n.type + "\n" + n.kitPart);
                for (Project.Port p : n.ports)
                  preview
                      .append("\n")
                      .append(p.name)
                      .append(" : ")
                      .append(p.type)
                      .append(p.config ? " [config]" : "")
                      .append(p.readonly ? " [RO]" : "")
                      .append(" = ")
                      .append(n.values.getOrDefault(p.name, "—"));
                details.setTooltip(new Tooltip(preview.toString()));
              } else details.setTooltip(null);
            });
    kits.valueProperty()
        .addListener(
            (o, a, b) -> {
              if (!updating) rebuild();
            });
    getChildren().addAll(kits, count, tree, details, fullHelp);
  }

  public int visibleTypes() {
    int n = 0;
    for (TreeItem<Entry> kit : tree.getRoot().getChildren()) n += kit.getChildren().size();
    return n;
  }

  public Project.Node selected() {
    TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
    return item == null ? null : item.getValue().template;
  }

  public void selectFirst() {
    for (TreeItem<Entry> kit : tree.getRoot().getChildren())
      if (!kit.getChildren().isEmpty()) {
        tree.getSelectionModel().select(kit.getChildren().get(0));
        return;
      }
  }

  public void update(List<Project.Node> types, String filter, List<String> parts) {
    if (tree.getRoot() != null) {
      expanded.clear();
      for (TreeItem<Entry> item : tree.getRoot().getChildren())
        if (item.isExpanded()) expanded.add(item.getValue().label);
    }
    projectParts = new HashSet<>(parts);
    Project.Node previous = selected();
    String selectedKit = kits.getValue();
    all = List.copyOf(types);
    query = filter.toLowerCase(Locale.ROOT);
    updating = true;
    kits.getItems().clear();
    kits.getItems().add(Tr.of("palette.allKits"));
    all.stream().map(n -> n.kitPart).distinct().sorted().forEach(kits.getItems()::add);
    kits.setValue(kits.getItems().contains(selectedKit) ? selectedKit : Tr.of("palette.allKits"));
    updating = false;
    rebuild();
    if (previous != null)
      for (TreeItem<Entry> kit : tree.getRoot().getChildren())
        for (TreeItem<Entry> item : kit.getChildren()) {
          Project.Node n = item.getValue().template;
          if (n.type.equals(previous.type) && n.kitPart.equals(previous.kitPart)) {
            kit.setExpanded(true);
            tree.getSelectionModel().select(item);
          }
        }
  }

  private void rebuild() {
    TreeItem<Entry> root = new TreeItem<>(new Entry(Tr.of("palette.kits"), null));
    Map<String, TreeItem<Entry>> groups = new TreeMap<>();
    int total = 0;
    for (Project.Node n : all) {
      if (!Tr.of("palette.allKits").equals(kits.getValue()) && !n.kitPart.equals(kits.getValue()))
        continue;
      if (!(n.type + " " + n.kitPart).toLowerCase(Locale.ROOT).contains(query)) continue;
      TreeItem<Entry> kit =
          groups.computeIfAbsent(
              n.kitPart,
              k ->
                  new TreeItem<>(
                      new Entry(
                          k
                              + (projectParts.contains(k)
                                  ? Tr.of("palette.inProject")
                                  : Tr.of("palette.local")),
                          null)));
      kit.getChildren()
          .add(new TreeItem<>(new Entry(n.type.substring(n.type.indexOf("::") + 2), n)));
      total++;
    }
    for (TreeItem<Entry> kit : groups.values()) {
      kit.getChildren().sort(Comparator.comparing(i -> i.getValue().label));
      kit.setExpanded(
          !query.isBlank() || groups.size() < 4 || expanded.contains(kit.getValue().label));
      root.getChildren().add(kit);
    }
    tree.setRoot(root);
    count.setText(Tr.of("palette.counts", groups.size(), total));
  }
}
