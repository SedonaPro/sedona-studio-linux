package ru.bccontrol.sedonastudio.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator screens of a project: pictures with live values, controls and equipment symbols bound to
 * components. Pure data, no drawing and no device access, so the same screens can be shown by the
 * editor, by a stand-alone operator window or by another program. The shape follows the shared
 * "mcs-sedona-ui" package, which the web editor reads and writes too.
 */
public final class Screens implements Project.Part {
  /** The key of this part in the project file. */
  public static final String KEY = "ui";

  /** This part of a project, or null while it has none (or cannot read the one it has). */
  public static Screens of(Project p) {
    return p.part(KEY, Screens.class);
  }

  /** Sets this part of a project; null removes it. */
  public static void put(Project p, Screens value) {
    p.part(KEY, value);
  }

  /** The part as a newer version wrote it, when this one cannot read it; else null. */
  public static Object foreign(Project p) {
    return p.foreign(KEY);
  }

  public static final List<String> KINDS =
      List.of(
          "value",
          "indicator",
          "setpoint",
          "toggle",
          "button",
          "bar",
          "gauge",
          "slider",
          "text",
          "trend",
          "bars",
          "table");
  public static final List<String> BOOL_KINDS = List.of("indicator", "toggle", "button");
  public static final List<String> WRITE_KINDS = List.of("setpoint", "toggle", "button", "slider");
  public static final List<String> DATA_KINDS = List.of("trend", "bars", "table");
  public static final List<String> STATES =
      List.of("off", "on", "fault", "unknown", "p25", "p50", "p75");

  public String id = "", title = "";
  public int version = 1;
  public final List<View> views = new ArrayList<>();

  /** Equipment pictures the screens use; null when the package carries no library. */
  public List<Asset> library;

  /** Graphic components (pictures with points inside) the screens use; null when none. */
  public List<Component> components;

  /** The path of a point nothing is bound to: no component can have this name. */
  public static final String UNBOUND = "?";

  /** A value of one component: the path is relative to the folder of the screen. */
  public record Binding(String path, String slot, String type) {}

  public static final class View {
    public String id = "", title = "", folder = "", svg = "";

    /** Null when the screen was saved without the list (older packages). */
    public List<Widget> widgets;

    public List<Symbol> objects;

    /** Graphic components placed on the screen; null when there are none. */
    public List<Part> parts;

    public View copy() {
      View v = new View();
      v.id = id;
      v.title = title;
      v.folder = folder;
      v.svg = svg;
      if (widgets != null) {
        v.widgets = new ArrayList<>();
        for (Widget w : widgets) v.widgets.add(w.copy());
      }
      if (objects != null) {
        v.objects = new ArrayList<>();
        for (Symbol o : objects) v.objects.add(o.copy());
      }
      if (parts != null) {
        v.parts = new ArrayList<>();
        for (Part p : parts) v.parts.add(p.copy());
      }
      return v;
    }

    public Widget widget(String id) {
      if (widgets != null) for (Widget w : widgets) if (w.id.equals(id)) return w;
      return null;
    }

    public Symbol object(String id) {
      if (objects != null) for (Symbol o : objects) if (o.id.equals(id)) return o;
      return null;
    }

    public Part part(String id) {
      if (parts != null) for (Part p : parts) if (p.id.equals(id)) return p;
      return null;
    }
  }

  /** A value, control, text or chart placed on a screen; x and y are per cent of the screen. */
  public static final class Widget {
    public String id = "", kind = "value", label = "", path = ".", slot = "", type = "", unit = "";
    public double x, y;
    public int decimals = 1;

    /** Optional settings by name (appearance, width, min, showLabel...), in the saved order. */
    public final Map<String, Object> options = new LinkedHashMap<>();

    /** Channels of a trend, bar chart or table; null for the other kinds. */
    public List<Series> series;

    public Binding binding() {
      return new Binding(path, slot, type);
    }

    public double number(String option, double fallback) {
      return options.get(option) instanceof Number n ? n.doubleValue() : fallback;
    }

    public boolean flag(String option, boolean fallback) {
      return options.get(option) instanceof Boolean b ? b : fallback;
    }

    public Widget copy() {
      Widget w = new Widget();
      w.id = id;
      w.kind = kind;
      w.label = label;
      w.path = path;
      w.slot = slot;
      w.type = type;
      w.unit = unit;
      w.x = x;
      w.y = y;
      w.decimals = decimals;
      w.options.putAll(options);
      if (series != null) {
        w.series = new ArrayList<>();
        for (Series s : series) w.series.add(s.copy());
      }
      return w;
    }
  }

  public static final class Series {
    public String path = ".", slot = "", type = "", label = "", unit = "";

    /** "#RRGGBB", or null for the default colour. */
    public String color;

    public Binding binding() {
      return new Binding(path, slot, type);
    }

    public Series copy() {
      Series s = new Series();
      s.path = path;
      s.slot = slot;
      s.type = type;
      s.label = label;
      s.unit = unit;
      s.color = color;
      return s;
    }
  }

  /** A picture of a piece of equipment with one drawing per state. */
  public static final class Asset {
    public String id = "", title = "";
    public double width, height;
    public final Map<String, String> states = new LinkedHashMap<>();

    public Asset copy() {
      Asset a = new Asset();
      a.id = id;
      a.title = title;
      a.width = width;
      a.height = height;
      a.states.putAll(states);
      return a;
    }
  }

  /** A library picture placed on a screen; its state follows a bool or a 0..1 position. */
  public static final class Symbol {
    public String id = "", assetId = "", label = "", mode = "static";
    public double x, y;
    public int rotation;
    public Binding binding, fault;

    public Symbol copy() {
      Symbol o = new Symbol();
      o.id = id;
      o.assetId = assetId;
      o.label = label;
      o.mode = mode;
      o.x = x;
      o.y = y;
      o.rotation = rotation;
      o.binding = binding;
      o.fault = fault;
      return o;
    }
  }

  /** A named value a graphic component shows: "bool", "number" or "any". */
  public static final class Point {
    public String id = "", label = "", kind = "any";

    /** Slot names to try when the component is attached to a block, the likeliest first. */
    public final List<String> slots = new ArrayList<>();

    public Point copy() {
      Point p = new Point();
      p.id = id;
      p.label = label;
      p.kind = kind;
      p.slots.addAll(slots);
      return p;
    }

    /** Whether a slot of this type can feed the point. */
    public boolean accepts(String type) {
      String t = type.replace("sys::", "");
      return switch (kind) {
        case "bool" -> t.equals("bool");
        case "number" -> List.of("float", "double", "int", "long", "short", "byte").contains(t);
        default -> true;
      };
    }
  }

  /**
   * A graphic component: a small picture of its own (symbols and widgets, placed in per cent of its
   * box) whose values are named points instead of slots. Inside, a binding has the path "." and the
   * point's id as its slot; a part on a screen says which slot feeds which point.
   */
  public static final class Component {
    public String id = "", title = "";
    public double width, height;
    public final List<Point> points = new ArrayList<>();
    public List<Symbol> objects;
    public List<Widget> widgets;

    public Point point(String id) {
      for (Point p : points) if (p.id.equals(id)) return p;
      return null;
    }

    public Component copy() {
      Component c = new Component();
      c.id = id;
      c.title = title;
      c.width = width;
      c.height = height;
      for (Point p : points) c.points.add(p.copy());
      if (objects != null) {
        c.objects = new ArrayList<>();
        for (Symbol o : objects) c.objects.add(o.copy());
      }
      if (widgets != null) {
        c.widgets = new ArrayList<>();
        for (Widget w : widgets) c.widgets.add(w.copy());
      }
      return c;
    }

    /** The library pictures the component is drawn with. */
    public List<String> assets() {
      List<String> ids = new ArrayList<>();
      if (objects != null)
        for (Symbol o : objects) if (!ids.contains(o.assetId)) ids.add(o.assetId);
      return ids;
    }
  }

  /** A graphic component placed on a screen, with the slots that feed its points. */
  public static final class Part {
    public String id = "", componentId = "", label = "";
    public double x, y;
    public final Map<String, Binding> bind = new LinkedHashMap<>();

    public Part copy() {
      Part p = new Part();
      p.id = id;
      p.componentId = componentId;
      p.label = label;
      p.x = x;
      p.y = y;
      p.bind.putAll(bind);
      return p;
    }
  }

  /**
   * What a part consists of, ready to be drawn like the items of a screen: the symbols and widgets
   * of its component with every point replaced by the slot bound to it. A point nothing is bound to
   * gets a path no component has, so it shows as missing; an unbound fault signal is left out.
   */
  public static View inner(Component def, Part part) {
    View v = new View();
    v.id = part.id;
    v.title = part.label;
    if (def.objects != null) {
      v.objects = new ArrayList<>();
      for (Symbol o : def.objects) {
        Symbol c = o.copy();
        c.binding = bound(part, c.binding);
        // A fault signal nobody wired is no fault signal: the picture just follows its state.
        c.fault = c.fault == null ? null : part.bind.get(c.fault.slot());
        v.objects.add(c);
      }
    }
    if (def.widgets != null) {
      v.widgets = new ArrayList<>();
      for (Widget w : def.widgets) {
        Widget c = w.copy();
        if (!c.kind.equals("text")) {
          Binding b = bound(part, c.binding());
          c.path = b.path();
          c.slot = b.slot();
          c.type = b.type();
        }
        v.widgets.add(c);
      }
    }
    return v;
  }

  private static Binding bound(Part part, Binding inside) {
    if (inside == null) return null;
    Binding real = part.bind.get(inside.slot());
    return real != null ? real : new Binding(UNBOUND, inside.slot(), inside.type());
  }

  public Component component(String id) {
    if (components != null) for (Component c : components) if (c.id.equals(id)) return c;
    return null;
  }

  @Override
  public Screens copy() {
    Screens s = new Screens();
    s.id = id;
    s.title = title;
    s.version = version;
    for (View v : views) s.views.add(v.copy());
    if (library != null) {
      s.library = new ArrayList<>();
      for (Asset a : library) s.library.add(a.copy());
    }
    if (components != null) {
      s.components = new ArrayList<>();
      for (Component c : components) s.components.add(c.copy());
    }
    return s;
  }

  public View view(String id) {
    for (View v : views) if (v.id.equals(id)) return v;
    return null;
  }

  public Asset asset(String id) {
    if (library != null) for (Asset a : library) if (a.id.equals(id)) return a;
    return null;
  }

  /** Every value a screen needs while it is shown. */
  public static List<Binding> interest(View view) {
    List<Binding> wanted = new ArrayList<>();
    if (view.widgets != null)
      for (Widget w : view.widgets) {
        if (w.kind.equals("text")) continue;
        if (DATA_KINDS.contains(w.kind)) {
          if (w.series != null) for (Series s : w.series) wanted.add(s.binding());
        } else wanted.add(w.binding());
      }
    if (view.objects != null)
      for (Symbol o : view.objects) {
        if (!o.mode.equals("static") && o.binding != null) wanted.add(o.binding);
        if (o.fault != null) wanted.add(o.fault);
      }
    if (view.parts != null) for (Part p : view.parts) wanted.addAll(p.bind.values());
    return wanted;
  }

  /**
   * The drawing of a symbol for the values it is bound to.
   *
   * @param state value of the state binding as text, null when it is not known
   * @param fault value of the fault binding as text, null when it is not known
   * @param live false when there is no fresh data at all
   */
  public static String state(Symbol o, String state, String fault, boolean live) {
    boolean bound = o.binding != null || o.fault != null;
    if (!bound || o.mode.equals("static") && o.fault == null) return "off";
    if (!live) return "unknown";
    if (o.fault != null) {
      Boolean f = bool(fault);
      if (f == null) return "unknown";
      if (f) return "fault";
    }
    if (o.mode.equals("static")) return "off";
    if (o.mode.equals("bool")) {
      Boolean v = bool(state);
      return v == null ? "unknown" : v ? "on" : "off";
    }
    try {
      double n = Double.parseDouble(state == null ? "" : state.trim());
      if (!(n >= 0 && n <= 1)) return "unknown";
      return List.of("off", "p25", "p50", "p75", "on").get((int) Math.round(n * 4));
    } catch (NumberFormatException notNumber) {
      return "unknown";
    }
  }

  private static Boolean bool(String v) {
    if (v == null) return null;
    if (v.equals("true") || v.equals("1")) return true;
    if (v.equals("false") || v.equals("0")) return false;
    return null;
  }
}
