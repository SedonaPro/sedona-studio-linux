package ru.bccontrol.sedonastudio.ui;

import ru.bccontrol.sedonastudio.model.Screens;

/**
 * Where a screen gets its values from. The screen itself knows nothing about projects, devices or
 * polling: today the editor answers from the open project and its live values, later the same
 * screens can be fed by a station that talks to many controllers.
 */
interface ScreenData {
  /**
   * One value as the screen shows it.
   *
   * @param value text of the value, null when there is none
   * @param problem why the value cannot be shown (component gone, slot gone, type changed), or null
   * @param live true while the value is being refreshed from a running device
   */
  record Reading(String value, String problem, boolean live) {}

  /** Reads the value a binding points to, seen from the folder of the screen. */
  Reading read(String folder, Screens.Binding binding);

  /**
   * What is stored of a value over the last seconds, oldest first; empty when the source keeps no
   * history of it. A trend starts from this instead of an empty chart.
   */
  default java.util.List<ru.bccontrol.sedonastudio.model.Archive.Sample> past(
      String folder, Screens.Binding binding, double seconds) {
    return java.util.List.of();
  }

  /** Why the value cannot be written right now, or null when it can. */
  String writeProblem(String folder, Screens.Binding binding);

  /**
   * Writes a value where the binding points. The source decides how: confirmation, checks and the
   * journal are its business. Throws with a message for the operator when the write is refused.
   */
  void write(String folder, Screens.Binding binding, String value);
}
