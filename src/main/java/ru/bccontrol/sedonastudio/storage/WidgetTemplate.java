package ru.bccontrol.sedonastudio.storage;

import java.util.*;
import java.util.regex.*;
import sedona.Schema;
import sedona.Slot;
import sedona.Type;

/**
 * A widget shipped with a kit ("sedonapro-widget/1", see docs/widgets.md in SedonaPro): a picture
 * of one component and its children, with the options, controls and actions the picture may touch.
 * It is data only. It comes from a device, so it is parsed with limits and checked against the kit
 * manifest ({@link #problems}) before it is shown.
 */
public final class WidgetTemplate {
  public static final String FORMAT = "sedonapro-widget/1";

  public record Value(
      String bind, String unit, int decimals, double dx, double dy, String label, String when) {}

  public record Element(
      String id,
      String symbol,
      double x,
      double y,
      double w,
      double h,
      String when,
      boolean ghost,
      String state,
      String fault,
      String label,
      List<String> configure,
      List<Value> values) {}

  public record Text(String text, double x, double y, String when) {}

  public record Control(
      String kind, String bind, String label, double x, double y, String when, String unit) {}

  public record Option(String slot, String label, String when) {}

  public record Info(String bind, String label) {}

  public record Action(String id, String invoke, String label, String confirm) {}

  public final String id, type, title;
  public final double width, height;
  /** Child name -> the types it may have. */
  public final Map<String, List<String>> parts;
  /** Symbol -> state ("off", "on", "fault") -> file inside the kit's ui folder. */
  public final Map<String, Map<String, String>> symbols;

  public final List<Option> options;
  public final List<Element> elements;
  public final List<Text> texts;
  public final List<Control> controls;
  public final List<Info> info;
  public final List<Action> actions;

  private static final int MAX_ITEMS = 256;
  private static final Set<String> STATES = Set.of("off", "on", "fault");
  static final Set<String> CONTROL_KINDS = Set.of("mode", "setpoint", "toggle", "status");
  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
  private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_.-]+)*\\.svg");

  private WidgetTemplate(Map<String, Object> m) {
    if (!FORMAT.equals(m.get("format")))
      throw new IllegalArgumentException("Unknown widget format: " + m.get("format"));
    id = name(str(m, "id", true));
    type = str(m, "type", true);
    title = str(m, "title", false);
    List<Object> size = list(m, "size");
    if (size.size() != 2) throw new IllegalArgumentException("size: [width, height]");
    width = range(size.get(0), 50, 4000);
    height = range(size.get(1), 50, 4000);
    Map<String, List<String>> p = new LinkedHashMap<>();
    for (var e : map(m, "parts").entrySet()) {
      List<String> types = new ArrayList<>();
      for (Object t : asList(e.getValue())) types.add(text(t, 160));
      p.put(name(e.getKey()), List.copyOf(types));
    }
    parts = Collections.unmodifiableMap(p);
    Map<String, Map<String, String>> s = new LinkedHashMap<>();
    for (var e : map(m, "symbols").entrySet()) {
      Map<String, String> states = new LinkedHashMap<>();
      for (var st : asMap(e.getValue()).entrySet()) {
        if (!STATES.contains(st.getKey()))
          throw new IllegalArgumentException("Unknown symbol state " + st.getKey());
        String file = text(st.getValue(), 200);
        if (!FILE.matcher(file).matches() || file.contains(".."))
          throw new IllegalArgumentException("Bad symbol file " + file);
        states.put(st.getKey(), file);
      }
      if (!states.containsKey("off"))
        throw new IllegalArgumentException("Symbol " + e.getKey() + " needs an off state");
      s.put(name(e.getKey()), Collections.unmodifiableMap(states));
    }
    symbols = Collections.unmodifiableMap(s);
    List<Option> o = new ArrayList<>();
    for (Map<String, Object> x : items(m, "options"))
      o.add(new Option(name(str(x, "slot", true)), str(x, "label", false), cond(x)));
    options = List.copyOf(o);
    List<Element> el = new ArrayList<>();
    for (Map<String, Object> x : items(m, "elements")) {
      List<Value> values = new ArrayList<>();
      for (Map<String, Object> v : items(x, "values"))
        values.add(
            new Value(
                bind(str(v, "bind", true)),
                str(v, "unit", false),
                (int) range(v.getOrDefault("decimals", 1.0), 0, 6),
                num(v, "dx"),
                num(v, "dy"),
                str(v, "label", false),
                cond(v)));
      List<String> configure = new ArrayList<>();
      for (Object c : x.containsKey("configure") ? asList(x.get("configure")) : List.of())
        configure.add(name(text(c, 64)));
      String symbol = str(x, "symbol", false);
      if (!symbol.isEmpty() && !symbols.containsKey(symbol))
        throw new IllegalArgumentException("Unknown symbol " + symbol);
      el.add(
          new Element(
              name(str(x, "id", true)),
              symbol,
              num(x, "x"),
              num(x, "y"),
              range(x.getOrDefault("w", 40.0), 1, 4000),
              range(x.getOrDefault("h", 40.0), 1, 4000),
              cond(x),
              Boolean.TRUE.equals(x.get("ghost")),
              optBind(x, "state"),
              optBind(x, "fault"),
              str(x, "label", false),
              List.copyOf(configure),
              List.copyOf(values)));
    }
    elements = List.copyOf(el);
    List<Text> t = new ArrayList<>();
    for (Map<String, Object> x : items(m, "texts"))
      t.add(new Text(str(x, "text", true), num(x, "x"), num(x, "y"), cond(x)));
    texts = List.copyOf(t);
    List<Control> c = new ArrayList<>();
    for (Map<String, Object> x : items(m, "controls")) {
      String kind = str(x, "kind", true);
      if (!CONTROL_KINDS.contains(kind)) throw new IllegalArgumentException("Control kind " + kind);
      c.add(
          new Control(
              kind,
              bind(str(x, "bind", true)),
              str(x, "label", false),
              num(x, "x"),
              num(x, "y"),
              cond(x),
              str(x, "unit", false)));
    }
    controls = List.copyOf(c);
    List<Info> in = new ArrayList<>();
    for (Map<String, Object> x : items(m, "info"))
      in.add(new Info(bind(str(x, "bind", true)), str(x, "label", false)));
    info = List.copyOf(in);
    List<Action> a = new ArrayList<>();
    for (Map<String, Object> x : items(m, "actions")) {
      String confirm = str(x, "confirm", false);
      a.add(
          new Action(
              name(str(x, "id", true)),
              name(str(x, "invoke", true)),
              str(x, "label", false),
              confirm));
    }
    actions = List.copyOf(a);
  }

  /** Parses a template; throws IllegalArgumentException with the reason when it is not one. */
  public static WidgetTemplate parse(String json) {
    if (json.length() > 256 * 1024) throw new IllegalArgumentException("Widget too large");
    return new WidgetTemplate(Json.object(json));
  }

  /** Every file the template draws with. */
  public Set<String> files() {
    Set<String> out = new TreeSet<>();
    for (Map<String, String> states : symbols.values()) out.addAll(states.values());
    return out;
  }

  /** Every child the template reads or writes, by the name it has under the component. */
  public Set<String> boundParts() {
    Set<String> out = new TreeSet<>();
    for (String ref : bindings())
      if (ref.contains(".")) out.add(ref.substring(0, ref.indexOf('.')));
    return out;
  }

  private List<String> bindings() {
    List<String> refs = new ArrayList<>();
    for (Element e : elements) {
      if (!e.state.isEmpty()) refs.add(e.state);
      if (!e.fault.isEmpty()) refs.add(e.fault);
      for (Value v : e.values) refs.add(v.bind);
    }
    for (Control c : controls) refs.add(c.bind);
    for (Info i : info) refs.add(i.bind);
    return refs;
  }

  /**
   * What does not match the kit manifests: bindings to missing slots, options and writable controls
   * that are not @config, unknown actions, conditions on unknown slots or range names. A widget
   * with problems is not shown. Part types missing from the schema are not checked (no such child
   * can exist in the application).
   */
  public List<String> problems(Schema schema) {
    List<String> out = new ArrayList<>();
    Type own = schema.type(type);
    if (own == null) return List.of("type " + type + " is not in the application's kits");
    String facet = own.facets.gets("widget", "");
    if (!facet.equals(id)) out.add(type + " does not point to widget " + id + " (@widget)");
    for (String ref : bindings()) {
      List<Slot> found = slots(schema, own, ref);
      if (found != null && found.isEmpty()) out.add("no slot " + ref);
    }
    for (Option o : options) {
      Slot s = own.slot(o.slot);
      if (s == null || !s.isProp() || !s.isConfig())
        out.add("option " + o.slot + " is not @config");
    }
    for (Control c : controls)
      if (!c.kind.equals("status"))
        for (Slot s : Objects.requireNonNullElse(slots(schema, own, c.bind), List.<Slot>of()))
          if (!s.isConfig()) out.add("control " + c.bind + " is not @config");
    for (Element e : elements)
      for (String o : e.configure)
        if (options.stream().noneMatch(x -> x.slot.equals(o)))
          out.add("element " + e.id + " configures " + o + ", which is not an option");
    for (Action a : actions) {
      Slot s = own.slot(a.invoke);
      if (s == null || !s.isAction()) out.add("no action " + a.invoke);
      if (a.confirm.isBlank()) out.add("action " + a.id + " has no confirmation text");
    }
    List<String> conditions = new ArrayList<>();
    for (Element e : elements) {
      conditions.add(e.when);
      for (Value v : e.values) conditions.add(v.when);
    }
    for (Text t : texts) conditions.add(t.when);
    for (Option o : options) conditions.add(o.when);
    for (Control c : controls) conditions.add(c.when);
    for (String when : conditions)
      try {
        Condition.parse(when).check(own);
      } catch (IllegalArgumentException ex) {
        out.add(ex.getMessage());
      }
    return out;
  }

  /**
   * The slots a binding may mean: one of the component's own, or one per allowed child type. Null
   * when it cannot be checked because a child type is not in the schema.
   */
  List<Slot> slots(Schema schema, Type own, String ref) {
    List<Slot> out = new ArrayList<>();
    int dot = ref.indexOf('.');
    if (dot < 0) {
      Slot s = own.slot(ref);
      if (s != null && s.isProp()) out.add(s);
      return out;
    }
    boolean unknown = false;
    for (String t : parts.getOrDefault(ref.substring(0, dot), List.of())) {
      Type type = schema.type(t);
      if (type == null) {
        unknown = true;
        continue;
      }
      Slot s = type.slot(ref.substring(dot + 1));
      if (s != null && s.isProp()) out.add(s);
    }
    return out.isEmpty() && unknown ? null : out;
  }

  // ---------------------------------------------------------------- conditions

  /**
   * A "when" condition: "slot op value" terms joined by && and || (&& binds tighter). Slots are the
   * component's own; values are a range name in quotes, a number, true or false.
   */
  public static final class Condition {
    private static final Pattern TERM =
        Pattern.compile(
            "\\s*(\\w+)\\s*(==|!=|<=|>=|<|>)\\s*('[\\w-]*'|-?\\d+(\\.\\d+)?|true|false)\\s*");

    private record Term(String slot, String op, String value) {}

    private final List<List<Term>> any;

    private Condition(List<List<Term>> any) {
      this.any = any;
    }

    public static Condition parse(String expr) {
      String e = expr == null ? "" : expr.trim();
      if (e.isEmpty() || e.equals("true")) return new Condition(List.of());
      if (e.length() > 512) throw new IllegalArgumentException("condition too long");
      List<List<Term>> any = new ArrayList<>();
      for (String or : e.split("\\|\\|")) {
        List<Term> all = new ArrayList<>();
        for (String and : or.split("&&")) {
          Matcher m = TERM.matcher(and);
          if (!m.matches()) throw new IllegalArgumentException("condition '" + expr + "'");
          all.add(new Term(m.group(1), m.group(2), m.group(3)));
        }
        any.add(all);
      }
      return new Condition(any);
    }

    /** Throws when a slot or a range name is unknown. */
    void check(Type own) {
      for (List<Term> all : any)
        for (Term t : all) {
          Slot s = own.slot(t.slot);
          if (s == null || !s.isProp())
            throw new IllegalArgumentException("condition on unknown slot " + t.slot);
          if (t.value.startsWith("'") && rangeIndex(s, t.value) < 0)
            throw new IllegalArgumentException("condition: " + t.value + " is not in " + t.slot);
        }
    }

    /** Evaluates against the component's values (encoded strings, as stored in a block). */
    public boolean test(Type own, Map<String, String> values) {
      if (any.isEmpty()) return true;
      for (List<Term> all : any) {
        boolean ok = true;
        for (Term t : all) ok &= term(own, values, t);
        if (ok) return true;
      }
      return false;
    }

    private static boolean term(Type own, Map<String, String> values, Term t) {
      Slot s = own == null ? null : own.slot(t.slot);
      double left = number(values.get(t.slot)), right;
      if (t.value.startsWith("'")) right = s == null ? Double.NaN : rangeIndex(s, t.value);
      else right = number(t.value);
      if (Double.isNaN(left) || Double.isNaN(right)) return t.op.equals("!=");
      return switch (t.op) {
        case "==" -> left == right;
        case "!=" -> left != right;
        case "<" -> left < right;
        case ">" -> left > right;
        case "<=" -> left <= right;
        default -> left >= right;
      };
    }

    private static int rangeIndex(Slot s, String quoted) {
      return range(s).indexOf(quoted.substring(1, quoted.length() - 1));
    }
  }

  /** The names of a byte/int slot's values from its "range" facet; empty when it has none. */
  public static List<String> range(Slot s) {
    String r = s.facets.gets("range", "");
    if (r.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    for (String x : r.split(",")) out.add(x.trim());
    return out;
  }

  /** A stored value as a number: booleans are 1 and 0, anything else unreadable is NaN. */
  public static double number(String v) {
    if (v == null) return Double.NaN;
    v = v.trim();
    if (v.equals("true")) return 1;
    if (v.equals("false")) return 0;
    try {
      return Double.parseDouble(v);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  // ---------------------------------------------------------------- JSON helpers

  private static String name(String s) {
    if (!NAME.matcher(s).matches()) throw new IllegalArgumentException("Bad name " + s);
    return s;
  }

  private String bind(String ref) {
    int dot = ref.indexOf('.');
    if (dot < 0) return name(ref);
    String part = name(ref.substring(0, dot));
    name(ref.substring(dot + 1));
    if (!parts.containsKey(part)) throw new IllegalArgumentException(part + " is not in parts");
    return ref;
  }

  private String optBind(Map<String, Object> m, String key) {
    String v = str(m, key, false);
    return v.isEmpty() ? "" : bind(v);
  }

  private static String cond(Map<String, Object> m) {
    String w = str(m, "when", false);
    Condition.parse(w);
    return w;
  }

  private static String text(Object v, int max) {
    if (!(v instanceof String s) || s.length() > max)
      throw new IllegalArgumentException("Expected text up to " + max + " characters");
    return s;
  }

  private static String str(Map<String, Object> m, String key, boolean required) {
    Object v = m.get(key);
    if (v == null) {
      if (required) throw new IllegalArgumentException(key + " missing");
      return "";
    }
    return text(v, 1024);
  }

  private static double num(Map<String, Object> m, String key) {
    return range(m.getOrDefault(key, 0.0), -4000, 4000);
  }

  private static double range(Object v, double min, double max) {
    if (!(v instanceof Double d) || d < min || d > max)
      throw new IllegalArgumentException("Number out of range " + min + ".." + max);
    return d;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object v) {
    if (!(v instanceof Map)) throw new IllegalArgumentException("Expected an object");
    if (((Map<?, ?>) v).size() > MAX_ITEMS) throw new IllegalArgumentException("Too many items");
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object v) {
    if (!(v instanceof List)) throw new IllegalArgumentException("Expected a list");
    if (((List<?>) v).size() > MAX_ITEMS) throw new IllegalArgumentException("Too many items");
    return (List<Object>) v;
  }

  private static Map<String, Object> map(Map<String, Object> m, String key) {
    return m.containsKey(key) ? asMap(m.get(key)) : Map.of();
  }

  private static List<Object> list(Map<String, Object> m, String key) {
    return m.containsKey(key) ? asList(m.get(key)) : List.of();
  }

  private static List<Map<String, Object>> items(Map<String, Object> m, String key) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object v : list(m, key)) out.add(asMap(v));
    return out;
  }
}
