package ru.bccontrol.sedonastudio.model;

import java.util.*;

/**
 * Finds the components the screens talk about. A screen names its folder by the component names
 * from the root ("app/plant/ahu1") and each value by a path relative to that folder, so screens
 * survive a fresh read of the device, where numeric ids may differ.
 */
public final class ScreenBindings {
  private ScreenBindings() {}

  /** The folder path of a block or folder: "app" for the root, "app/a/b" below it. */
  public static String folderPath(Project p, String id) {
    StringBuilder b = new StringBuilder();
    Project.Node n = p.nodes.get(id);
    for (int depth = 0; n != null && !n.id.equals("0") && depth < 64; depth++) {
      b.insert(0, "/" + n.name);
      n = p.nodes.get(n.parent);
    }
    return "app" + b;
  }

  /** The id of the folder a screen belongs to ("0" for the root), or null when it is gone. */
  public static String folderId(Project p, String folder) {
    String[] names = folder.split("/");
    // The first name is the root itself, whatever it is called.
    return walk(p, "0", names, 1);
  }

  /** The component a binding points to, or null when there is none by that path. */
  public static Project.Node node(Project p, String folder, String path) {
    String start = folderId(p, folder);
    if (start == null) return null;
    String id = path.equals(".") ? start : walk(p, start, path.split("/"), 0);
    return id == null ? null : p.nodes.get(id);
  }

  /** The path to put into a binding for a block seen from a screen's folder; null if outside. */
  public static String relative(Project p, String folder, String id) {
    String base = folder, full = folderPath(p, id);
    String start = folderId(p, folder);
    if (start == null) return null;
    base = folderPath(p, start);
    if (full.equals(base)) return ".";
    return full.startsWith(base + "/") ? full.substring(base.length() + 1) : null;
  }

  private static String walk(Project p, String from, String[] names, int first) {
    String at = from;
    for (int i = first; i < names.length; i++) {
      String next = null;
      for (Project.Node n : p.nodes.values())
        if (n.parent.equals(at) && n.name.equals(names[i]) && !n.id.equals("0")) {
          next = n.id;
          break;
        }
      if (next == null) return null;
      at = next;
    }
    return at;
  }

  /** The screens that belong to a folder, in the order of the project. */
  public static List<Screens.View> screensOf(Project p, String folderId) {
    List<Screens.View> found = new ArrayList<>();
    if (Screens.of(p) != null)
      for (Screens.View v : Screens.of(p).views)
        if (folderId.equals(folderId(p, v.folder))) found.add(v);
    return found;
  }

  /** A screen one can go to from another: of the folder above ("up"), the same one or below. */
  public record Link(String kind, Screens.View view) {}

  /**
   * Where an operator can go from a screen. Folders without screens are passed through: "up" are
   * the screens of the nearest folder above that has any, "down" the screens of the folders below
   * with no screen in between.
   */
  public static List<Link> links(Project p, String viewId) {
    List<Link> links = new ArrayList<>();
    if (Screens.of(p) == null || Screens.of(p).view(viewId) == null) return links;
    Map<String, String> home = new LinkedHashMap<>();
    for (Screens.View v : Screens.of(p).views) {
      String id = folderId(p, v.folder);
      if (id != null) home.put(v.id, id);
    }
    String mine = home.get(viewId);
    if (mine == null) return links;
    Set<String> hosts = new HashSet<>(home.values());
    String above = above(p, mine, hosts);
    for (String kind : List.of("up", "same", "down"))
      for (Screens.View v : Screens.of(p).views) {
        String at = home.get(v.id);
        if (at == null || v.id.equals(viewId)) continue;
        boolean fits =
            switch (kind) {
              case "up" -> at.equals(above);
              case "same" -> at.equals(mine);
              default -> !at.equals(mine) && mine.equals(above(p, at, hosts));
            };
        if (fits) links.add(new Link(kind, v));
      }
    return links;
  }

  /** The nearest folder above the given one that has screens, or null. */
  private static String above(Project p, String folder, Set<String> hosts) {
    Project.Node n = p.nodes.get(folder);
    for (int depth = 0; n != null && !n.id.equals("0") && depth < 64; depth++) {
      if (hosts.contains(n.parent)) return n.parent;
      n = p.nodes.get(n.parent);
    }
    return null;
  }

  /**
   * Keeps the screens on their folders and values when an edit renames or moves components: a
   * screen names everything by path, so without this a renamed folder would lose its screens. What
   * the edit deleted, or moved out of the screen's folder, is left as it was and shows as missing.
   */
  public static void follow(Project before, Project after) {
    if (Screens.of(before) == null || Screens.of(after) == null) return;
    boolean moved = false;
    for (Project.Node n : before.nodes.values()) {
      Project.Node now = after.nodes.get(n.id);
      if (now != null && !(now.name.equals(n.name) && now.parent.equals(n.parent))) moved = true;
    }
    if (!moved) return;
    for (Screens.View v : Screens.of(after).views) {
      Screens.View old = Screens.of(before).view(v.id);
      // A screen the edit itself re-attached is the user's choice.
      if (old == null || !old.folder.equals(v.folder)) continue;
      String home = folderId(before, old.folder);
      if (home == null || !after.nodes.containsKey(home)) continue;
      String oldFolder = old.folder;
      v.folder = folderPath(after, home);
      java.util.function.UnaryOperator<String> path =
          was -> {
            Project.Node n = node(before, oldFolder, was);
            String now = n == null ? null : relative(after, v.folder, n.id);
            return now == null ? was : now;
          };
      if (v.widgets != null)
        for (Screens.Widget w : v.widgets) {
          w.path = path.apply(w.path);
          if (w.series != null) for (Screens.Series s : w.series) s.path = path.apply(s.path);
        }
      if (v.objects != null)
        for (Screens.Symbol o : v.objects) {
          if (o.binding != null)
            o.binding =
                new Screens.Binding(
                    path.apply(o.binding.path()), o.binding.slot(), o.binding.type());
          if (o.fault != null)
            o.fault =
                new Screens.Binding(path.apply(o.fault.path()), o.fault.slot(), o.fault.type());
        }
      if (v.parts != null)
        for (Screens.Part part : v.parts)
          part.bind.replaceAll(
              (point, b) -> new Screens.Binding(path.apply(b.path()), b.slot(), b.type()));
    }
  }

  /**
   * The slots of a block that feed the points of a graphic component: for each point the first of
   * its suggested slot names the block has with a fitting type. Points without a match are left
   * out. The path is the block's, relative to the screen's folder.
   */
  public static Map<String, Screens.Binding> autoBind(
      Project p, String folder, Screens.Component def, String path) {
    Map<String, Screens.Binding> bind = new LinkedHashMap<>();
    Project.Node n = node(p, folder, path);
    if (n == null) return bind;
    for (Screens.Point point : def.points)
      for (String name : point.slots) {
        Project.Port found = null;
        for (Project.Port port : n.ports)
          if (port.name.equals(name) && port.type != null && point.accepts(port.type)) found = port;
        if (found != null) {
          bind.put(point.id, new Screens.Binding(path, found.name, found.type));
          break;
        }
      }
    return bind;
  }

  /** "sys::float" and "float" are the same type for a binding. */
  public static boolean sameType(String a, String b) {
    return a.replace("sys::", "").equals(b.replace("sys::", ""));
  }
}
