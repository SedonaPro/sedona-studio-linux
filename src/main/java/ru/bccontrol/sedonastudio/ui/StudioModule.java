package ru.bccontrol.sedonastudio.ui;

import java.util.Collection;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Tab;

/**
 * An optional part of the editor that ships as its own jar beside the main one (lib/…) and is named
 * in that jar's META-INF/services. The main window knows nothing about what a module does: it gives
 * it the building blocks, shows its tabs and tells it when things change. Without the jar the
 * editor is simply an edition without that part.
 *
 * <p>A module is our own code built from this source tree, on the same class path; it is not a
 * sandboxed plug-in like the panel modules.
 */
public interface StudioModule {
  /** Called once, before the window is built. */
  void attach(ModuleHost host);

  /** Tabs for the left side, after the editor's own. */
  default List<Tab> leftTabs() {
    return List.of();
  }

  /** Tabs for the bottom side, after the editor's own. */
  default List<Tab> bottomTabs() {
    return List.of();
  }

  /** Controls for the row with the folder path, right after it. */
  default List<Node> navigation() {
    return List.of();
  }

  /** The live values changed. */
  default void valuesChanged() {}

  /** The draft was redrawn (an edit, undo, another project). */
  default void rendered() {}

  /** The diagram was redrawn (also when only the open folder changed). */
  default void canvasRendered() {}

  /** Lets the module add blocks to the next sampling round. */
  default void addWatchedTo(Collection<String> ids) {}

  /** The main window closes. */
  default void closing() {}
}
