package ru.bccontrol.sedonastudio.model;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** The project parts of the installed modules; none in an edition without modules. */
public final class ProjectExtensions {
  private static volatile List<ProjectExtension> found;

  private ProjectExtensions() {}

  public static List<ProjectExtension> all() {
    List<ProjectExtension> list = found;
    if (list == null) {
      list = new ArrayList<>();
      for (ProjectExtension e : ServiceLoader.load(ProjectExtension.class)) list.add(e);
      found = list = List.copyOf(list);
    }
    return list;
  }

  /** The extension that owns a key, or null. */
  public static ProjectExtension of(String key) {
    for (ProjectExtension e : all()) if (e.key().equals(key)) return e;
    return null;
  }
}
