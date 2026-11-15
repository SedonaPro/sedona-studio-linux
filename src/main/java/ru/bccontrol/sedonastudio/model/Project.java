package ru.bccontrol.sedonastudio.model;

import java.util.*;

/** Detached editor document. Never contains session objects or connection credentials. */
public final class Project {
  public String name = Tr.of("project.new"), platform = "";
  public final List<String> parts = new ArrayList<>();
  public final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
  public final List<Wire> wires = new ArrayList<>();
  /** Free text on the canvas; part of the drawing only, never sent to a device. */
  public final List<Caption> captions = new ArrayList<>();

  /** A part of the project that an optional module owns; see {@link ProjectExtension}. */
  public interface Part {
    Part copy();
  }

  /**
   * The parts of optional modules by their key in the project file. A value is a {@link Part} when
   * its module is installed and could read it; otherwise it is the part as it came from the file (a
   * newer format, or no such module here), kept and written back untouched.
   */
  public final LinkedHashMap<String, Object> extras = new LinkedHashMap<>();

  /** The part under a key as its module's own data, or null. */
  public <T extends Part> T part(String key, Class<T> type) {
    Object value = extras.get(key);
    return type.isInstance(value) ? type.cast(value) : null;
  }

  /** Sets or (with null) removes the part under a key. */
  public void part(String key, Part value) {
    if (value == null) extras.remove(key);
    else extras.put(key, value);
  }

  /** The part under a key as it came from the file, when nothing here can read it; else null. */
  public Object foreign(String key) {
    Object value = extras.get(key);
    return value instanceof Part ? null : value;
  }

  /** Takes over the parts of another project that this one does not have, as copies. */
  public void carryExtras(Project from) {
    for (Map.Entry<String, Object> e : from.extras.entrySet())
      if (!extras.containsKey(e.getKey()))
        extras.put(e.getKey(), e.getValue() instanceof Part part ? part.copy() : e.getValue());
  }

  /**
   * A caption on the canvas of one folder: text with an optional icon. With frame set it is a
   * titled outline of the given height, to put around a group of blocks.
   */
  public static final class Caption {
    public String id, folder = "0", text = "", icon = "";
    public double x, y, width = 240, height = 120;
    public boolean frame;

    public Caption copy() {
      Caption c = new Caption();
      c.id = id;
      c.folder = folder;
      c.text = text;
      c.icon = icon;
      c.x = x;
      c.y = y;
      c.width = width;
      c.height = height;
      c.frame = frame;
      return c;
    }
  }

  public Caption caption(String id) {
    for (Caption c : captions) if (c.id.equals(id)) return c;
    return null;
  }

  public static final class Port {
    public int id;
    public String name, type;
    public boolean input, config, readonly, asStr;
    public int textMax;

    public Port copy() {
      Port p = new Port();
      p.id = id;
      p.name = name;
      p.type = type;
      p.input = input;
      p.config = config;
      p.readonly = readonly;
      p.asStr = asStr;
      p.textMax = textMax;
      return p;
    }
  }

  public static final class Node {
    public String id, name, type, kitPart, parent = "0";
    public String note = "";
    public double x, y;
    /**
     * How the block looks in the editor; like the note, this is never sent to a device. width 0
     * means the standard width; icon is a short symbol or empty; info shows the note or the type's
     * help text on the block itself.
     */
    public double width;

    public String icon = "";
    public boolean info;
    public final List<Port> ports = new ArrayList<>();
    public final Map<String, String> values = new LinkedHashMap<>();

    public Port port(String n) {
      for (Port p : ports) if (p.name.equals(n)) return p;
      return null;
    }

    public Node copy() {
      Node n = new Node();
      n.note = note;
      n.width = width;
      n.icon = icon;
      n.info = info;
      n.id = id;
      n.name = name;
      n.type = type;
      n.kitPart = kitPart;
      n.parent = parent;
      n.x = x;
      n.y = y;
      for (Port p : ports) n.ports.add(p.copy());
      n.values.putAll(values);
      return n;
    }
  }

  public record Point(double x, double y) {}

  public static final class Wire {
    public final List<Point> bends = new ArrayList<>();
    public String source, sourcePort, target, targetPort;

    public Wire(String a, String b, String c, String d) {
      source = a;
      sourcePort = b;
      target = c;
      targetPort = d;
    }

    public Wire copy() {
      Wire w = new Wire(source, sourcePort, target, targetPort);
      w.bends.addAll(bends);
      return w;
    }

    public String key() {
      return source + "." + sourcePort + ">" + target + "." + targetPort;
    }
  }

  public Project copy() {
    Project p = new Project();
    p.name = name;
    p.platform = platform;
    p.parts.addAll(parts);
    for (Node n : nodes.values()) p.nodes.put(n.id, n.copy());
    for (Wire w : wires) p.wires.add(w.copy());
    for (Caption c : captions) p.captions.add(c.copy());
    p.carryExtras(this);
    return p;
  }

  public void connect(String a, String ap, String b, String bp) {
    Node from = nodes.get(a), to = nodes.get(b);
    if (from == null || to == null)
      throw new IllegalArgumentException(Tr.of("project.blockNotFound"));
    Port x = from.port(ap), y = to.port(bp);
    if (x == null || y == null || !y.input || y.readonly || !x.type.equals(y.type))
      throw new IllegalArgumentException(Tr.of("project.incompatiblePorts"));
    for (Wire w : wires)
      if (w.target.equals(b) && w.targetPort.equals(bp))
        throw new IllegalArgumentException(Tr.of("project.inputConnected"));
    wires.add(new Wire(a, ap, b, bp));
  }

  public void remove(String id) {
    if (id.equals("0")) throw new IllegalArgumentException(Tr.of("project.cannotRemoveRoot"));
    Set<String> remove = new HashSet<>();
    remove.add(id);
    boolean changed;
    do {
      changed = false;
      for (Node n : nodes.values())
        if (remove.contains(n.parent) && remove.add(n.id)) changed = true;
    } while (changed);
    nodes.keySet().removeAll(remove);
    wires.removeIf(w -> remove.contains(w.source) || remove.contains(w.target));
  }

  public List<String> validate() {
    List<String> errors = new ArrayList<>();
    Set<String> names = new HashSet<>(), inputs = new HashSet<>(), links = new HashSet<>();
    if (nodes.size() > 512) errors.add(Tr.of("project.blockLimit"));
    if (captions.size() > 300) errors.add(Tr.of("project.captionLimit"));
    Set<String> captionIds = new HashSet<>();
    for (Caption c : captions)
      if (c.id == null
          || !captionIds.add(c.id)
          || c.text == null
          || c.text.length() > 2000
          || !(c.x >= 0 && c.x <= 10000 && c.y >= 0 && c.y <= 10000)
          || !(c.width >= 60 && c.width <= 3000 && c.height >= 24 && c.height <= 3000))
        errors.add(Tr.of("project.badCaption", c.id));
    for (Wire w : wires) {
      if (w.bends.size() > 64) errors.add(Tr.of("project.bendLimit"));
      for (Point point : w.bends)
        if (!Double.isFinite(point.x)
            || !Double.isFinite(point.y)
            || point.x < 0
            || point.y < 0
            || point.x > 10000
            || point.y > 10000) errors.add(Tr.of("project.badBend"));
    }
    for (Node n : nodes.values()) {
      if (n.note == null || n.note.length() > 4000) errors.add(Tr.of("store.noteTooLong"));
      if (n.id == null || n.name == null || !n.name.matches("[A-Za-z_][A-Za-z0-9_]{0,6}"))
        errors.add(Tr.of("project.badName", n.name));
      if (!Double.isFinite(n.x)
          || !Double.isFinite(n.y)
          || n.x < 0
          || n.y < 0
          || n.x > 10000
          || n.y > 10000) errors.add(Tr.of("project.coordinates", n.name));
      if (!n.id.equals("0") && !names.add(n.parent + "/" + n.name))
        errors.add(Tr.of("project.duplicateName", n.name));
      if (!n.parent.equals("0") && !nodes.containsKey(n.parent))
        errors.add(Tr.of("project.noParent", n.name));
      Set<String> chain = new HashSet<>();
      Node at = n;
      while (at != null && !at.id.equals("0")) {
        if (!chain.add(at.id)) {
          errors.add(Tr.of("project.folderCycle", n.name));
          break;
        }
        at = nodes.get(at.parent);
      }
    }
    for (Wire w : wires) {
      Node a = nodes.get(w.source), b = nodes.get(w.target);
      Port x = a == null ? null : a.port(w.sourcePort), y = b == null ? null : b.port(w.targetPort);
      if (x == null || y == null || !x.type.equals(y.type))
        errors.add(Tr.of("project.brokenLink", w.key()));
      if (!links.add(w.key())) errors.add(Tr.of("project.duplicateLink"));
      if (!inputs.add(w.target + "." + w.targetPort)) errors.add(Tr.of("project.severalSources"));
    }
    return errors;
  }
}
