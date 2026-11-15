package ru.bccontrol.sedonastudio.model;

import java.util.Map;

/**
 * A part of the project that an optional module owns: operator screens, alarm rules, history
 * channels. The core knows such a part only by its key in the project file. With the module
 * installed the part is read into the module's own data; without it the part stays as it came and
 * is written back untouched, so a project never loses what another edition put into it.
 *
 * <p>Modules name their extensions in {@code META-INF/services} of their jar.
 */
public interface ProjectExtension {
  /** The top-level key of the part in the project file. */
  String key();

  /**
   * Reads the part. Throws IllegalArgumentException when this version cannot read it (a newer
   * format): the part is then kept as it came.
   */
  Project.Part decode(Map<?, ?> doc);

  /** What to write into the project file, or null when the part holds nothing worth a key. */
  Object encode(Project.Part part);

  /** Lets the part follow an edit that renamed or moved components. */
  default void follow(Project before, Project after) {}
}
