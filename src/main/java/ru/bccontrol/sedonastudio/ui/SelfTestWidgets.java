package ru.bccontrol.sedonastudio.ui;

import java.io.File;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.ProjectStore;

/**
 * --widget-smoke=project: a kit widget end to end. Offline the widget comes from the SDK and its
 * options change the draft (the picture follows); in the simulator the widget's "build" action
 * makes the unit on the VM, the operation face shows it live, a setpoint written from the widget
 * reaches the VM. Test code only.
 */
final class SelfTestWidgets {
  private final StudioApp app;

  SelfTestWidgets(StudioApp app) {
    this.app = app;
  }

  private Node find(WidgetView v, String id) {
    return v.stage().getScene().lookup("#" + id);
  }

  private void picture(WidgetView v, String suffix) throws Exception {
    javax.imageio.ImageIO.write(
        javafx.embed.swing.SwingFXUtils.fromFXImage(v.stage().getScene().snapshot(null), null),
        "png",
        new File(System.getProperty("studio.smokeOutput", "build/widget") + suffix));
  }

  private static javafx.scene.control.TreeItem<String> treeItem(
      javafx.scene.control.TreeItem<String> item, String id) {
    if (item == null) return null;
    if (item.getValue() != null && item.getValue().endsWith("[#" + id + "]")) return item;
    for (javafx.scene.control.TreeItem<String> c : item.getChildren()) {
      javafx.scene.control.TreeItem<String> found = treeItem(c, id);
      if (found != null) return found;
    }
    return null;
  }

  private static Project.Node child(Project p, String parent, String name) {
    for (Project.Node n : p.nodes.values())
      if (n.parent.equals(parent) && n.name.equals(name)) return n;
    return null;
  }

  void scenario(Path projectFile) {
    final int[] step = {-1, 0};
    final javafx.animation.Timeline[] clock = new javafx.animation.Timeline[1];
    final WidgetView[] view = new WidgetView[1];
    Runnable finish =
        () -> {
          clock[0].stop();
          app.projectFiles.dirty = false;
          app.deviceLink.timer.shutdownNow();
          app.deviceLink.io.shutdown();
          app.stage.close();
          Platform.exit();
        };
    clock[0] =
        new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(250),
                e -> {
                  try {
                    if (++step[1] > 600)
                      throw new AssertionError("Widget scenario timed out at step " + step[0]);
                    if (app.deviceLink.busy.get()) return;
                    if (app.notices.log.getText().contains(Tr.of("notice.error", "")))
                      throw new AssertionError("Error: " + app.notices.log.getText());
                    Project now = app.history.get();
                    if (step[0] == -1) {
                      app.history.reset(ProjectStore.loadRecovering(projectFile));
                      app.projectFiles.file = projectFile;
                      app.render();
                      Project.Node unit = app.history.get().nodes.get("draft-ahu");
                      if (app.widgets.widgetOf(unit) == null)
                        throw new AssertionError("The Ahu block does not name its widget");
                      app.focus.selected = unit.id;
                      app.inspectorPane.renderInspector();
                      if (app.inspectorPane.content.lookup("#open-widget") == null)
                        throw new AssertionError("No widget button in the inspector");
                      app.widgets.open(unit.id);
                      step[0] = 0;
                    } else if (step[0] == 0) {
                      WidgetView v = view[0] = app.widgets.view("draft-ahu");
                      if (v == null) throw new AssertionError("Widget window did not open");
                      if (!v.setupFace())
                        throw new AssertionError("Offline the widget opens on Setup");
                      Node rec = find(v, "widget-element-rec"),
                          fanE = find(v, "widget-element-fanE"),
                          fanS = find(v, "widget-element-fanS");
                      if (rec == null
                          || fanE == null
                          || fanS == null
                          || rec.getOpacity() > 0.5
                          || fanE.getOpacity() > 0.5
                          || fanS.getOpacity() < 1)
                        throw new AssertionError(
                            "Supply unit: recovery and exhaust fan should be pale choices");
                      if (find(v, "widget-element-ductE") != null)
                        throw new AssertionError(
                            "The exhaust duct is not a choice and must be hidden");
                      if (find(v, "widget-option-elStages").getParent().getParent().isVisible()
                          || find(v, "widget-option-recovery").getParent().getParent().isVisible()
                          || !find(v, "widget-option-valve").getParent().getParent().isVisible())
                        throw new AssertionError("Only the options that apply should be listed");
                      picture(v, ".setup-supply.png");
                      // Clicking the pale recovery highlights its options; choosing them changes
                      // the draft.
                      v.focus(java.util.List.of("recovery", "layout"));
                      if (!v.focusedOptions().contains("recovery"))
                        throw new AssertionError("Options not highlighted");
                      app.widgets.setOption("draft-ahu", "layout", "1");
                      app.widgets.setOption("draft-ahu", "recovery", "2");
                      Project.Node unit = app.history.get().nodes.get("draft-ahu");
                      if (!"1".equals(unit.values.get("layout"))
                          || !"2".equals(unit.values.get("recovery")))
                        throw new AssertionError("Options did not reach the draft");
                      if (find(v, "widget-element-rec").getOpacity() < 1
                          || find(v, "widget-element-fanE").getOpacity() < 1
                          || find(v, "widget-element-ductE") == null)
                        throw new AssertionError("The picture did not follow the options");
                      if (!find(v, "widget-option-recovery").getParent().getParent().isVisible())
                        throw new AssertionError(
                            "Heat recovery option not offered for a supply-exhaust unit");
                      picture(v, ".setup-supply-exhaust.png");
                      app.undo();
                      if (!"0"
                          .equals(app.history.get().nodes.get("draft-ahu").values.get("recovery")))
                        throw new AssertionError("An option change is one undo step");
                      app.widgets.setOption("draft-ahu", "recovery", "2");
                      app.simulator.start();
                      step[0] = 1;
                    } else if (step[0] == 1) {
                      if (!app.deviceLink.session.connected() || app.deviceLink.baseline == null)
                        return;
                      app.widgets.refresh();
                      WidgetView v = view[0];
                      Project.Node unit = now.nodes.get(v.nodeId);
                      if (unit == null
                          || !unit.type.equals("sproAhu::Ahu")
                          || v.nodeId.equals("draft-ahu"))
                        throw new AssertionError(
                            "The widget did not follow its block into the simulator");
                      if (child(now, unit.id, "fanS") != null)
                        throw new AssertionError("Built too early");
                      app.widgets.execute(v, v.template.actions.get(0), false);
                      step[0] = 2;
                    } else if (step[0] == 2) {
                      WidgetView v = view[0];
                      long parts =
                          now.nodes.values().stream()
                              .filter(n -> n.parent.equals(v.nodeId))
                              .count();
                      if (parts < 15)
                        throw new AssertionError("Build did not make the unit: " + parts);
                      if (!child(now, v.nodeId, "rec").type.equals("sproHvac::Recuperator"))
                        throw new AssertionError("No heat recovery after build");
                      // The built unit has children: a click on it in the tree selects it with
                      // its widget button instead of opening it as a folder.
                      app.projectTree.renderTree();
                      javafx.scene.control.TreeItem<String> item =
                          treeItem(app.projectTree.tree.getRoot(), v.nodeId);
                      if (item == null || item.getChildren().isEmpty())
                        throw new AssertionError("The built unit is not a branch of the tree");
                      app.projectTree.tree.getSelectionModel().clearSelection();
                      app.projectTree.tree.getSelectionModel().select(item);
                      if (!v.nodeId.equals(app.focus.selected)
                          || app.inspectorPane.content.lookup("#open-widget") == null)
                        throw new AssertionError(
                            "A click on the unit in the tree must select it, with its widget button");
                      ((ToggleButton) find(v, "widget-operation")).fire();
                      if (v.setupFace()) throw new AssertionError("Face did not switch");
                      app.liveValues.polling.setSelected(true);
                      step[0] = 3;
                    } else if (step[0] == 3) {
                      WidgetView v = view[0];
                      Project.Node temp = child(now, v.nodeId, "temp");
                      Node sp = find(v, "widget-control-temp-spSupply"),
                          mode = find(v, "widget-control-ctl-mode");
                      if (sp == null || mode == null || !sp.getParent().getParent().isVisible())
                        throw new AssertionError("Operation controls missing");
                      if (app.liveValues
                          .fullValue(child(now, v.nodeId, "season"), "season")
                          .equals("—")) return;
                      app.view.autoApply = true;
                      app.widgets.command(temp.id, "spSupply", "21.5");
                      step[0] = 4;
                    } else if (step[0] == 4) {
                      WidgetView v = view[0];
                      Project.Node temp = child(app.deviceLink.baseline, v.nodeId, "temp");
                      if (temp == null
                          || !temp.values.getOrDefault("spSupply", "").startsWith("21.5")) return;
                      app.view.autoApply = false;
                      v.refresh();
                      picture(v, ".operation.png");
                      app.simulator.stop();
                      step[0] = 5;
                    } else if (step[0] == 5) {
                      if (app.simulator.running() || app.deviceLink.session.connected()) return;
                      System.out.println(
                          "PASS kit widget: inspector button (also from the tree for a built unit),"
                              + " widget from the SDK checked against"
                              + " the manifests, pale choices, options into the draft with undo,"
                              + " the picture follows; in the simulator the widget follows its"
                              + " block, build makes the unit, operation face, setpoint from the"
                              + " widget reaches the VM");
                      finish.run();
                    }
                  } catch (Throwable ex) {
                    System.out.println("FAIL kit widget: " + ex);
                    ex.printStackTrace(System.out);
                    try {
                      if (view[0] != null) picture(view[0], ".failed.png");
                    } catch (Exception ignored) {
                    }
                    try {
                      app.simulator.stop();
                    } catch (Exception ignored) {
                    }
                    finish.run();
                    System.exit(1);
                  }
                }));
    clock[0].setCycleCount(javafx.animation.Animation.INDEFINITE);
    clock[0].play();
  }
}
