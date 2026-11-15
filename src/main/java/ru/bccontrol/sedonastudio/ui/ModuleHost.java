package ru.bccontrol.sedonastudio.ui;

import ru.bccontrol.sedonastudio.model.History;

/**
 * What the main window gives to an optional module: the same building blocks its own features get.
 */
public final class ModuleHost {
  final History history;
  final EditorFocus focus;
  final LiveValues liveValues;
  final Notices notices;
  final DeviceLink deviceLink;
  final DeviceChanges deviceChanges;
  final ViewOptions options;
  final EditorShell shell;

  ModuleHost(
      History history,
      EditorFocus focus,
      LiveValues liveValues,
      Notices notices,
      DeviceLink deviceLink,
      DeviceChanges deviceChanges,
      ViewOptions options,
      EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.liveValues = liveValues;
    this.notices = notices;
    this.deviceLink = deviceLink;
    this.deviceChanges = deviceChanges;
    this.options = options;
    this.shell = shell;
  }
}
