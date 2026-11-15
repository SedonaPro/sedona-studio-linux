package ru.bccontrol.sedonastudio.ui;

import java.util.Collection;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import ru.bccontrol.sedonastudio.model.Tr;

/**
 * The SCADA part of the editor: operator screens, alarms and the history of values. It ships as
 * lib/sedona-scada.jar; an engineering edition is the same editor without that file.
 */
public final class ScadaModule implements StudioModule {
  ScreensFeature screens;
  AlarmsFeature alarms;
  ArchiveFeature archive;

  @Override
  public void attach(ModuleHost host) {
    screens =
        new ScreensFeature(
            host.history,
            host.focus,
            host.liveValues,
            host.notices,
            host.deviceLink,
            host.deviceChanges,
            host.options,
            host.shell);
    alarms = new AlarmsFeature(host.history, host.focus, host.liveValues, host.notices, host.shell);
    archive =
        new ArchiveFeature(host.history, host.focus, host.liveValues, host.notices, host.shell);
    screens.pastValues = archive::past;
    screens.alarmsUnder = alarms::summary;
    screens.showAlarms = alarms::bringForward;
  }

  @Override
  public List<Tab> leftTabs() {
    return List.of(new Tab(Tr.of("tab.screens"), screens.pane()));
  }

  @Override
  public List<Tab> bottomTabs() {
    return List.of(alarms.tab(), archive.tab());
  }

  @Override
  public List<Node> navigation() {
    return List.of(screens.folderButton(), alarms.bell());
  }

  @Override
  public void valuesChanged() {
    alarms.refresh();
    archive.refresh();
    screens.refresh();
  }

  @Override
  public void rendered() {
    valuesChanged();
  }

  @Override
  public void canvasRendered() {
    screens.folderShown();
  }

  @Override
  public void addWatchedTo(Collection<String> ids) {
    screens.addWatchedTo(ids);
    alarms.addWatchedTo(ids);
    archive.addWatchedTo(ids);
  }

  @Override
  public void closing() {
    screens.closeAll();
    alarms.closeAll();
  }
}
