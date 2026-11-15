package ru.bccontrol.panel.demo;

import static ru.bccontrol.panel.api.PanelData.*;

import java.util.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import ru.bccontrol.panel.api.*;

public final class DemoApp extends Application {
  private PanelModule.PanelView view;
  private Timeline timer;

  public void start(Stage stage) throws Exception {
    DemoHost host = new DemoHost();
    PanelModule module = ServiceLoader.load(PanelModule.class).findFirst().orElseThrow();
    if (module.apiMajor() != PanelModule.API_MAJOR)
      throw new IllegalStateException("API major mismatch");
    view = module.create(host);
    Button start = new Button(PanelText.of("panelmodule.demo.start", "Start (demo)")),
        reset = new Button(PanelText.of("panelmodule.demo.resetButton", "Reset (demo)"));
    start.setOnAction(e -> host.write(new Ref("/demo", "enabled"), "true"));
    reset.setOnAction(e -> host.invoke(new Ref("/demo", "reset"), ""));
    BorderPane root = new BorderPane(view.content());
    root.setTop(
        new VBox(
            8,
            new Label(
                PanelText.of(
                    "panelmodule.demo.banner",
                    "Standalone bench · commands change only demonstration data")),
            new HBox(8, start, reset)));
    Scene scene = new Scene(root, 600, 750);
    stage.setTitle(PanelText.of("panelmodule.demo.title", "Sedona Studio · panel bench"));
    stage.setScene(scene);
    stage.show();
    timer = new Timeline(new KeyFrame(Duration.millis(250), e -> host.tick()));
    timer.setCycleCount(Timeline.INDEFINITE);
    timer.play();
    if (getParameters().getRaw().contains("--smoke"))
      Platform.runLater(
          () -> {
            try {
              ((Button) scene.lookup("#panel-add-widget")).fire();
              host.tick();
              if (!host.loadDocument().contains("version=1"))
                throw new AssertionError("Widget document");
              start.fire();
              reset.fire();
              if (!host.snapshot().values().get(new Ref("/demo", "value")).value().equals("0.00"))
                throw new AssertionError("Mock action");
              view.close();
              view = module.create(host);
              root.setCenter(view.content());
              root.applyCss();
              root.layout();
              if (scene.lookup("#panel-value-0") == null)
                throw new AssertionError("Document reload");
              var file =
                  java.nio.file.Path.of(System.getProperty("panel.snapshot", "build/demo.png"));
              java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
              javax.imageio.ImageIO.write(
                  javafx.embed.swing.SwingFXUtils.fromFXImage(scene.snapshot(null), null),
                  "png",
                  file.toFile());
              System.out.println(
                  "PASS independent panel demo: ServiceLoader, widget add/save, live mock values,"
                      + " document reload and explicit demo commands; no Studio/Sedona dependency");
              stop();
              Platform.exit();
            } catch (Throwable ex) {
              ex.printStackTrace();
              System.exit(1);
            }
          });
  }

  public void stop() {
    if (timer != null) timer.stop();
    if (view != null) view.close();
  }
}
