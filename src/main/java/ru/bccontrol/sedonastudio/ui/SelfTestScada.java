package ru.bccontrol.sedonastudio.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.storage.*;

/** The self-checks of the SCADA module (screens, alarms, history); they ship with the module. */
final class SelfTestScada implements SelfTest.Extra {
  private final StudioApp app;
  private ScadaModule scada;
  private String screensSent;

  SelfTestScada(StudioApp app) {
    this.app = app;
  }

  /** The module is looked up late: the checks are made before the window has its modules. */
  private void bind() {
    if (scada == null) scada = app.module(ScadaModule.class);
    if (scada == null) throw new AssertionError("The SCADA module is not loaded");
  }

  private static Project.Node named(Project p, String name) {
    for (Project.Node n : p.nodes.values()) if (n.name.equals(name)) return n;
    throw new AssertionError("No block " + name);
  }

  @Override
  public boolean launch(String flag) {
    if (!flag.startsWith("--demo-smoke=")) return false;
    String file = flag.substring("--demo-smoke=".length());
    Platform.runLater(
        () -> {
          bind();
          demoScenario(Paths.get(file));
        });
    return true;
  }

  @Override
  public void stage(String name, Project now) throws Exception {
    bind();
    switch (name) {
      case "live" -> {
        Project.Node sum = named(now, "sum1");
        checkAlarms(sum);
        checkArchive(sum);
        checkScreen(now, sum);
      }
      case "write" -> {
        Project.Node source = named(now, "src1");
        // A setpoint on an operator screen writes one value, after a confirmation.
        ru.bccontrol.sedonastudio.model.Screens.View shown =
            Screens.of(app.history.get()).views.get(0);
        String sourcePath =
            ru.bccontrol.sedonastudio.model.ScreenBindings.relative(now, shown.folder, source.id);
        app.edit(
            p -> {
              ru.bccontrol.sedonastudio.model.Screens.Widget set =
                  new ru.bccontrol.sedonastudio.model.Screens.Widget();
              set.id = "set1";
              set.kind = "setpoint";
              set.label = "Source";
              set.path = sourcePath;
              set.slot = "out";
              set.type = "sys::float";
              set.x = 40;
              set.y = 70;
              Screens.of(p).views.get(0).widgets.add(set);
            });
        scada.screens.refresh();
        scada.screens.open(shown.id);
        String[] asked = {null};
        scada.screens.confirmWrite =
            (what, change) -> {
              asked[0] = what + " " + change;
              return true;
            };
        ScreenView screen = scada.screens.view(shown.id);
        javafx.scene.control.Button write =
            (javafx.scene.control.Button) screen.lookup("#screen-write-set1");
        if (write.isDisabled())
          throw new AssertionError(
              "Setpoint is disabled: "
                  + scada.screens.writeProblem(
                      shown.folder,
                      new ru.bccontrol.sedonastudio.model.Screens.Binding(
                          sourcePath, "out", "sys::float")));
        ((javafx.scene.control.TextField) screen.lookup("#screen-entry-set1")).setText("40.5");
        write.fire();
        if (asked[0] == null || !asked[0].contains("40.5"))
          throw new AssertionError("Write was not confirmed: " + asked[0]);
      }
      case "send" -> {
        scada.screens.closeAll();
        // The screens go to the device as a file and come back the same.
        scada.screens.confirmReplace = text -> true;
        screensSent = ScreenStore.write(Screens.of(app.history.get()));
        scada.screens.toDevice();
      }
      case "receive" -> {
        app.edit(p -> Screens.of(p).views.get(0).title = "Changed locally");
        scada.screens.fromDevice();
      }
      case "verify" -> {
        if (!ScreenStore.write(Screens.of(app.history.get())).equals(screensSent))
          throw new AssertionError("Screens from the device differ from those sent");
      }
      case "kept" -> {
        if (Screens.of(app.history.get()) == null
            || Screens.of(app.history.get()).views.size() != 1)
          throw new AssertionError("Screens lost on the way through the device");
        System.out.println(
            "PASS operator screen: live value and symbol on a screen in the"
                + " simulator, missing component named, palette/align/copy/remove"
                + " with undo, setpoint written from the screen (live value 40.5),"
                + " package stored on the device and read back,"
                + " screen kept through upload and re-read");
      }
      default -> throw new AssertionError("Unknown stage " + name);
    }
  }

  /**
   * --demo-smoke=project: the demo plant in the simulator. Every screen is opened, every graphic
   * component must show a live value, and each screen is saved as a picture beside smokeOutput.
   */
  private void demoScenario(java.nio.file.Path projectFile) {
    final int[] step = {-1, 0};
    final javafx.animation.Timeline[] clock = new javafx.animation.Timeline[1];
    clock[0] =
        new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(250),
                e -> {
                  try {
                    if (++step[1] > 360) throw new AssertionError("Demo scenario timed out");
                    if (app.deviceLink.busy.get()) return;
                    if (step[0] == -1) {
                      app.history.reset(ProjectStore.loadRecovering(projectFile));
                      app.projectFiles.file = projectFile;
                      app.render();
                      app.simulator.start();
                      step[0] = 0;
                      return;
                    }
                    Project now = app.history.get();
                    if (step[0] == 0) {
                      if (!app.simulator.running() || !app.deviceLink.session.connected())
                        throw new AssertionError(
                            "Simulator did not start: " + app.notices.log.getText());
                      if (Screens.of(now) == null || Screens.of(now).views.size() != 3)
                        throw new AssertionError("Screens lost on the way through the simulator");
                      app.liveValues.polling.setSelected(true);
                      app.liveValues.nextSample = 0;
                      for (ru.bccontrol.sedonastudio.model.Screens.View v : Screens.of(now).views)
                        scada.screens.open(v.id);
                      step[0] = 1;
                      step[1] = 0;
                    } else if (step[0] == 1) {
                      // Wait until every point of every part has a fresh value (or time out).
                      int shown = 0, wanted = 0;
                      StringBuilder waiting = new StringBuilder();
                      for (ru.bccontrol.sedonastudio.model.Screens.View v : Screens.of(now).views)
                        for (ru.bccontrol.sedonastudio.model.Screens.Binding b :
                            ru.bccontrol.sedonastudio.model.Screens.interest(v)) {
                          wanted++;
                          ScreenData.Reading r = scada.screens.read(v.folder, b);
                          if (r.problem() == null && r.live() && r.value() != null) shown++;
                          else if (waiting.length() < 200)
                            waiting
                                .append(v.id)
                                .append(' ')
                                .append(b.path())
                                .append('.')
                                .append(b.slot())
                                .append(": ")
                                .append(r.problem())
                                .append("; ");
                        }
                      if (shown < wanted) {
                        if (step[1] > 200)
                          throw new AssertionError(
                              "Values not live: " + shown + " of " + wanted + " - " + waiting);
                        return;
                      }
                      if (step[1] < 24) return; // let the trends collect a few samples
                      // The demo's alarm rules: the loop pressure dips below its limit in time.
                      if (Alarms.of(now) == null || Alarms.of(now).rules.size() != 7)
                        throw new AssertionError(
                            "Alarm rules lost on the way through the simulator");
                      if (scada.alarms.active().isEmpty()) {
                        if (step[1] > 330) throw new AssertionError("No demo alarm was raised");
                        return;
                      }
                      // The demo's history: every channel has been written while we waited.
                      if (Archive.of(now) == null || Archive.of(now).channels.size() != 6)
                        throw new AssertionError("History channels lost on the way");
                      long at = System.currentTimeMillis();
                      for (ru.bccontrol.sedonastudio.model.Archive.Channel c :
                          Archive.of(now).channels)
                        if (scada.archive.samples(c, at - 600_000, at).size() < 3)
                          throw new AssertionError("Nothing recorded for " + c.path);
                      scada.archive.bringForward();
                      scada.archive.draw();
                      app.root.applyCss();
                      app.root.layout();
                      javax.imageio.ImageIO.write(
                          javafx.embed.swing.SwingFXUtils.fromFXImage(
                              app.scene.snapshot(null), null),
                          "png",
                          new File(
                              System.getProperty("studio.smokeOutput", "build/demo")
                                  + ".history.png"));
                      scada.screens.refresh();
                      javafx.scene.Node onScreen =
                          scada.screens.view("overview").getScene().lookup("#screen-alarms");
                      if (onScreen == null || !onScreen.isVisible())
                        throw new AssertionError("The overview does not show the active alarm");
                      scada.alarms.bringForward();
                      app.root.applyCss();
                      app.root.layout();
                      javax.imageio.ImageIO.write(
                          javafx.embed.swing.SwingFXUtils.fromFXImage(
                              app.scene.snapshot(null), null),
                          "png",
                          new File(
                              System.getProperty("studio.smokeOutput", "build/demo")
                                  + ".alarms.png"));
                      for (ru.bccontrol.sedonastudio.model.Screens.View v : Screens.of(now).views) {
                        ScreenView view = scada.screens.view(v.id);
                        view.refresh();
                        view.applyCss();
                        view.layout();
                        java.nio.file.Path out =
                            Paths.get(
                                System.getProperty("studio.smokeOutput", "build/demo")
                                    + "."
                                    + v.id
                                    + ".png");
                        Files.createDirectories(out.toAbsolutePath().getParent());
                        javax.imageio.ImageIO.write(
                            javafx.embed.swing.SwingFXUtils.fromFXImage(
                                view.snapshot(null, null), null),
                            "png",
                            out.toFile());
                        if (view.partial)
                          throw new AssertionError("Screen " + v.id + " is not drawn in full");
                      }
                      if (scada.screens.view("overview").getScene().lookup("#screen-go-ahu")
                          == null)
                        throw new AssertionError("No way from the overview to the unit's screen");
                      scada.screens.closeAll();
                      app.simulator.stop();
                      System.out.println(
                          "PASS demo plant: 3 screens in the simulator, "
                              + wanted
                              + " values live, pictures saved");
                      clock[0].stop();
                      app.projectFiles.dirty = false;
                      app.deviceLink.timer.shutdownNow();
                      app.deviceLink.io.shutdown();
                      app.stage.close();
                      Platform.exit();
                    }
                  } catch (Throwable failure) {
                    failure.printStackTrace();
                    app.simulator.stop();
                    System.exit(1);
                  }
                }));
    clock[0].setCycleCount(javafx.animation.Animation.INDEFINITE);
    clock[0].play();
  }

  /**
   * Alarms end to end in the simulator: a rule made for the selected block, changed in the rules
   * window, raised by the live value, shown, acknowledged, cleared, written to the journal.
   */
  private void checkAlarms(Project.Node sum) throws IOException {
    java.nio.file.Path data = Paths.get(System.getProperty("studio.data"));
    app.focus.selected = sum.id;
    javafx.scene.Scene rules = scada.alarms.rulesWindow().getScene();
    ((Button) rules.lookup("#alarm-new")).fire();
    Project p = app.history.get();
    if (Alarms.of(p) == null || Alarms.of(p).rules.size() != 1)
      throw new AssertionError("No rule for the selected block: " + app.notices.log.getText());
    ru.bccontrol.sedonastudio.model.Alarms.Rule made = Alarms.of(p).rules.get(0);
    if (!made.slot.equals("out")
        || made.kind != ru.bccontrol.sedonastudio.model.Alarms.Kind.HIGH
        || scada.alarms.problem(made) != null)
      throw new AssertionError("New rule: " + made.path + "." + made.slot + " " + made.kind);
    ((TextField) rules.lookup("#alarm-label")).setText("Sum high");
    ((TextField) rules.lookup("#alarm-limit")).setText("10,5");
    ((TextField) rules.lookup("#alarm-band")).setText("1");
    ((Button) rules.lookup("#alarm-save")).fire();
    made = Alarms.of(app.history.get()).rules.get(0);
    if (made.limit != 10.5 || made.band != 1 || !made.label.equals("Sum high"))
      throw new AssertionError("Rule not saved from the form: " + made.limit);
    scada.alarms.evaluate(System.currentTimeMillis());
    @SuppressWarnings("unchecked")
    javafx.scene.control.TableView<AlarmsFeature.Row> table =
        (javafx.scene.control.TableView<AlarmsFeature.Row>) app.scene.lookup("#alarms-table");
    javafx.scene.Node bell = app.scene.lookup("#alarm-bell");
    if (table == null || table.getItems().size() != 1 || bell == null || !bell.isVisible())
      throw new AssertionError("Alarm not shown: " + app.notices.log.getText());
    if (!table.getItems().get(0).state().value.startsWith("14.5")
        || scada.alarms.summary("app").isEmpty()
        || !scada.alarms.summary("app/nowhere").isEmpty())
      throw new AssertionError("Alarm value or folder summary");
    table.getSelectionModel().select(0);
    ((Button) app.scene.lookup("#alarms-ack")).fire();
    if (!table.getItems().get(0).state().acked) throw new AssertionError("Not acknowledged");
    ((TextField) rules.lookup("#alarm-limit")).setText("20");
    ((Button) rules.lookup("#alarm-save")).fire();
    scada.alarms.evaluate(System.currentTimeMillis());
    if (!table.getItems().isEmpty() || bell.isVisible())
      throw new AssertionError("Alarm did not leave after the limit was raised");
    java.util.List<ru.bccontrol.sedonastudio.model.Alarms.Event> journal =
        ru.bccontrol.sedonastudio.storage.AlarmStore.journal(data, 500);
    StringBuilder seen = new StringBuilder();
    for (ru.bccontrol.sedonastudio.model.Alarms.Event e : journal)
      seen.append(e.what()).append(' ');
    if (!seen.toString().equals("raised acked cleared "))
      throw new AssertionError("Journal: " + seen);
    javafx.scene.control.TableView<?> shownJournal =
        (javafx.scene.control.TableView<?>)
            scada.alarms.journalWindow().getScene().lookup("#alarm-journal");
    if (shownJournal.getItems().size() != 3) throw new AssertionError("Journal window");
    app.undo();
    if (Alarms.of(app.history.get()).rules.get(0).limit != 10.5)
      throw new AssertionError("Undo of a rule change");
    ((javafx.scene.control.ListView<?>) rules.lookup("#alarm-rules")).getSelectionModel().select(0);
    ((Button) rules.lookup("#alarm-remove")).fire();
    if (Alarms.of(app.history.get()) != null) throw new AssertionError("Rule not removed");
    scada.alarms.evaluate(System.currentTimeMillis());
    scada.alarms.closeAll();
    System.out.println("PASS alarms in the simulator: rule, raise, acknowledge, clear, journal");
  }

  /**
   * History end to end in the simulator: a channel for a block, samples written from the live
   * value, drawn on the tab, given to a screen as its past, exported, removed.
   */
  private void checkArchive(Project.Node sum) throws IOException {
    java.nio.file.Path data = Paths.get(System.getProperty("studio.data"));
    if (!scada.archive.offered(sum.id).contains("out")) throw new AssertionError("Nothing offered");
    String id = scada.archive.add(sum.id, "out");
    Project p = app.history.get();
    ru.bccontrol.sedonastudio.model.Archive.Channel c = Archive.of(p).channel(id);
    if (c == null || c.period != 10 || scada.archive.offered(sum.id).contains("out"))
      throw new AssertionError("Channel not added");
    long now = System.currentTimeMillis();
    scada.archive.record(now - 30_000);
    scada.archive.record(now - 25_000); // too early for a period of 10 s
    scada.archive.record(now - 20_000);
    scada.archive.record(now - 10_000);
    java.util.List<ru.bccontrol.sedonastudio.model.Archive.Sample> written =
        ArchiveStore.read(data, p.name, id, now - 60_000, now);
    if (written.size() != 4 || Math.abs(written.get(0).value() - 14.5) > 0.01)
      throw new AssertionError("Recorded " + written.size() + " samples");
    scada.archive.bringForward();
    @SuppressWarnings("unchecked")
    javafx.scene.control.ListView<ru.bccontrol.sedonastudio.model.Archive.Channel> channels =
        (javafx.scene.control.ListView<ru.bccontrol.sedonastudio.model.Archive.Channel>)
            app.scene.lookup("#archive-channels");
    @SuppressWarnings("unchecked")
    javafx.scene.chart.LineChart<Number, Number> chart =
        (javafx.scene.chart.LineChart<Number, Number>) app.scene.lookup("#archive-chart");
    scada.archive.refresh();
    scada.archive.draw();
    if (channels.getItems().size() != 1
        || chart.getData().size() != 1
        || chart.getData().get(0).getData().size() < 3)
      throw new AssertionError("History not drawn: " + chart.getData().size());
    String folder = ScreenBindings.folderPath(p, sum.parent);
    java.util.List<ru.bccontrol.sedonastudio.model.Archive.Sample> past =
        scada.screens.past(
            folder,
            new ru.bccontrol.sedonastudio.model.Screens.Binding(sum.name, "out", "sys::float"),
            300);
    if (past.size() < 3) throw new AssertionError("A screen does not get the past of a value");
    java.nio.file.Path csv = data.resolve("history-export.csv");
    if (scada.archive.export(csv) < 3 || !Files.readString(csv).contains("sum1 out"))
      throw new AssertionError("History export");
    channels.getSelectionModel().clearSelection();
    channels.getSelectionModel().select(0);
    scada.archive.setPeriod("3");
    if (Archive.of(app.history.get()).channel(id).period != 3) throw new AssertionError("Period");
    app.undo();
    if (Archive.of(app.history.get()).channel(id).period != 10)
      throw new AssertionError("Undo of a period change");
    scada.archive.refresh();
    channels.getSelectionModel().clearSelection();
    channels.getSelectionModel().select(0);
    ((Button) app.scene.lookup("#archive-remove")).fire();
    if (Archive.of(app.history.get()) != null) throw new AssertionError("Channel not removed");
    System.out.println("PASS history in the simulator: channel, samples, chart, past, export");
  }

  private void checkScreen(Project now, Project.Node sum) throws IOException {
    String id = scada.screens.create("Smoke");
    String folder =
        ru.bccontrol.sedonastudio.model.ScreenBindings.folderPath(now, app.focus.folder);
    String path = ru.bccontrol.sedonastudio.model.ScreenBindings.relative(now, folder, sum.id);
    if (path == null) throw new AssertionError("sum1 is not under " + folder);
    scada.screens.picture(
        id,
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 800 400'><defs><linearGradient"
            + " id='g'><stop offset='0' stop-color='#ffffff'/><stop offset='1'"
            + " stop-color='#cfe0d8'/></linearGradient></defs><rect width='800' height='400'"
            + " fill='url(#g)'/></svg>");
    boolean refused = false;
    try {
      scada.screens.picture(id, "<svg xmlns='http://www.w3.org/2000/svg'><script>1</script></svg>");
    } catch (IllegalArgumentException expected) {
      refused = true;
    }
    if (!refused) throw new AssertionError("A picture with a script was accepted");
    app.edit(
        p -> {
          ru.bccontrol.sedonastudio.model.Screens screens = Screens.of(p);
          ru.bccontrol.sedonastudio.model.Screens.View v = screens.view(id);
          ru.bccontrol.sedonastudio.model.Screens.Widget value =
              new ru.bccontrol.sedonastudio.model.Screens.Widget();
          value.id = "w1";
          value.label = "Sum";
          value.path = path;
          value.slot = "out";
          value.type = "sys::float";
          value.x = 10;
          value.y = 10;
          ru.bccontrol.sedonastudio.model.Screens.Widget lost = value.copy();
          lost.id = "w2";
          lost.path = "nowhere";
          lost.y = 50;
          v.widgets.add(value);
          v.widgets.add(lost);
          ru.bccontrol.sedonastudio.model.Screens.Asset asset =
              new ru.bccontrol.sedonastudio.model.Screens.Asset();
          asset.id = "box";
          asset.title = "Box";
          asset.width = 80;
          asset.height = 60;
          asset.states.put(
              "off",
              "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 80 60'><rect width='80'"
                  + " height='60' fill='#8899aa'/></svg>");
          screens.library = new java.util.ArrayList<>(java.util.List.of(asset));
          ru.bccontrol.sedonastudio.model.Screens.Symbol symbol =
              new ru.bccontrol.sedonastudio.model.Screens.Symbol();
          symbol.id = "o1";
          symbol.assetId = "box";
          symbol.label = "Box";
          symbol.x = 50;
          symbol.y = 20;
          v.objects = new java.util.ArrayList<>(java.util.List.of(symbol));
          ru.bccontrol.sedonastudio.storage.ScreenStore.check(screens);
        });
    scada.screens.refresh();
    scada.screens.open(id);
    ScreenView view = scada.screens.view(id);
    if (view == null || view.stageWidth() != 800 || view.partial)
      throw new AssertionError("Screen window");
    javafx.scene.control.Label
        reading = (javafx.scene.control.Label) view.lookup("#screen-reading-w1"),
        missing = (javafx.scene.control.Label) view.lookup("#screen-reading-w2");
    if (!reading.getText().startsWith("14.5"))
      throw new AssertionError("Screen shows " + reading.getText());
    if (!missing.getText().equals("—")) throw new AssertionError("Lost binding shows a value");
    if (view.node("o1") == null || ((javafx.scene.Group) view.node("o1")).getChildren().isEmpty())
      throw new AssertionError("Symbol not drawn");
    java.util.Set<String> watched = new java.util.HashSet<>();
    scada.screens.addWatchedTo(watched);
    if (!watched.contains(sum.id)) throw new AssertionError("Screen values are not polled");
    // Arranging: add from the palette, bind, align, copy, remove; each step is in the project.
    ScreenEditor editor = scada.screens.editor(id);
    editor.arrange.setSelected(true);
    String added = editor.addWidget("temperature", 30, 40);
    String fan = editor.addSymbol("mcs-fan-right", 60, 60);
    ru.bccontrol.sedonastudio.model.Screens.View edited = Screens.of(app.history.get()).view(id);
    if (edited.widget(added) == null
        || !edited.widget(added).unit.equals("°C")
        || edited.object(fan) == null
        || Screens.of(app.history.get()).asset("mcs-fan-right") == null)
      throw new AssertionError("Palette items were not added");
    view = scada.screens.view(id);
    if (view.node(added) == null || view.node(fan) == null || view.partial)
      throw new AssertionError("Added items are not drawn");
    String mine =
        editor.ownPicture(
            "My pump",
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 60 40'><circle cx='30' cy='20'"
                + " r='15' fill='#3378bf'/></svg>");
    String pump = editor.addSymbol(mine, 70, 20);
    if (scada.screens.view(id).node(pump) == null)
      throw new AssertionError("Own picture not drawn");
    editor.select(java.util.List.of(pump));
    editor.remove();
    editor.select(java.util.List.of(added, fan));
    editor.align("left");
    edited = Screens.of(app.history.get()).view(id);
    if (Math.abs(edited.widget(added).x - edited.object(fan).x) > 0.01)
      throw new AssertionError("Align left");
    editor.duplicate();
    if (Screens.of(app.history.get()).view(id).widgets.size() != 4
        || Screens.of(app.history.get()).view(id).objects.size() != 3)
      throw new AssertionError("Duplicate");
    editor.remove();
    if (Screens.of(app.history.get()).view(id).widgets.size() != 3)
      throw new AssertionError("Remove");
    app.undo();
    if (Screens.of(app.history.get()).view(id).widgets.size() != 4)
      throw new AssertionError("Undo of a screen edit");
    app.redo();
    editor.arrange.setSelected(false);
    // A graphic component of the plugged-in library: placed, fed from a block, drawn, removed.
    if (ru.bccontrol.sedonastudio.storage.ScreenLibraries.component("sl-c-fan") == null)
      throw new AssertionError(
          "HVAC library not found: " + ru.bccontrol.sedonastudio.storage.ScreenLibraries.refused());
    editor.arrange.setSelected(true);
    String part = editor.addPart("sl-c-fan", 50, 50);
    int fed = editor.attachPart(part, path);
    // The palette: library folders within folders, and a preview of the chosen item.
    javafx.scene.Scene editorScene = scada.screens.view(id).getScene();
    @SuppressWarnings("unchecked")
    javafx.scene.control.TreeView<Object> offered =
        (javafx.scene.control.TreeView<Object>) editorScene.lookup("#screen-components");
    javafx.scene.control.TreeItem<Object> leaf = offered.getRoot();
    int depth = 0;
    while (!leaf.getChildren().isEmpty()) {
      leaf.setExpanded(true);
      leaf = leaf.getChildren().get(0);
      depth++;
    }
    if (depth < 3) throw new AssertionError("Palette has no nested folders: depth " + depth);
    offered.getSelectionModel().select(leaf);
    javafx.scene.layout.Pane shownPreview =
        (javafx.scene.layout.Pane) editorScene.lookup("#screen-preview");
    if (shownPreview.getChildren().isEmpty()) throw new AssertionError("No preview of a component");
    editorScene.getRoot().applyCss();
    editorScene.getRoot().layout();
    javax.imageio.ImageIO.write(
        javafx.embed.swing.SwingFXUtils.fromFXImage(editorScene.snapshot(null), null),
        "png",
        Paths.get(
                System.getProperty("studio.smokeOutput", "build/studio-preview.png")
                    + ".editor.png")
            .toFile());
    ru.bccontrol.sedonastudio.model.Screens withPart = Screens.of(app.history.get());
    if (fed < 1
        || withPart.version != 3
        || withPart.component("sl-c-fan") == null
        || withPart.asset("sl-fan") == null
        || withPart.view(id).part(part).bind.get("speed") == null)
      throw new AssertionError("Component not placed or not fed: " + fed);
    javafx.scene.Node partNode = scada.screens.view(id).node(part);
    javafx.scene.control.Label speed =
        partNode == null
            ? null
            : (javafx.scene.control.Label) partNode.lookup("#screen-reading-" + part + "-val");
    if (speed == null || speed.getText().equals("—"))
      throw new AssertionError(
          "Component value not shown: " + (speed == null ? null : speed.getText()));
    editor.select(java.util.List.of(part));
    editor.remove();
    if (Screens.of(app.history.get()).view(id).parts.size() != 0)
      throw new AssertionError("Part kept");
    editor.arrange.setSelected(false);
    // Screens of one folder lead to each other; the folder's button on the diagram counts them.
    String second = scada.screens.create("Second");
    javafx.scene.control.Button go =
        (javafx.scene.control.Button)
            scada.screens.view(id).getScene().lookup("#screen-go-" + second);
    if (go == null) throw new AssertionError("No way from a screen to its neighbour");
    go.fire();
    if (scada.screens.view(second) == null || scada.screens.view(id) != null)
      throw new AssertionError("Going to the neighbour screen");
    if (scada.screens.view(second).getScene().lookup("#screen-go-" + id) == null)
      throw new AssertionError("No way back");
    if (!scada.screens.folderButton().getText().contains("2"))
      throw new AssertionError("Folder button: " + scada.screens.folderButton().getText());
    if (scada.screens.attach(second) != 0) throw new AssertionError("Attach of an empty screen");
    app.undo();
    // Walk mode: the window follows the folder on the diagram.
    scada.screens.closeAll();
    scada.screens.follow(true);
    if (scada.screens.view(id) == null)
      throw new AssertionError("Walk mode did not open the screen");
    scada.screens.follow(false);
    scada.screens.open(second);
    app.undo();
    if (Screens.of(app.history.get()).views.size() != 1 || scada.screens.view(second) != null)
      throw new AssertionError("Second screen not undone");
    scada.screens.closeAll();
    if (scada.screens.view(id) != null) throw new AssertionError("Screen window not closed");
  }
}
