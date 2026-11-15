package ru.bccontrol.sedonastudio.model;

import java.util.*;
import java.util.function.Function;

/**
 * Alarm rules of a project and the state machine that follows them. Pure data and logic: no JavaFX,
 * no device. Today the editor feeds it with the values it samples; a station that watches many
 * controllers can feed the same rules later.
 */
public final class Alarms implements Project.Part {
  /** The key of this part in the project file. */
  public static final String KEY = "alarms";

  /** This part of a project, or null while it has none (or cannot read the one it has). */
  public static Alarms of(Project p) {
    return p.part(KEY, Alarms.class);
  }

  /** Sets this part of a project; null removes it. */
  public static void put(Project p, Alarms value) {
    p.part(KEY, value);
  }

  /** The part as a newer version wrote it, when this one cannot read it; else null. */
  public static Object foreign(Project p) {
    return p.foreign(KEY);
  }

  public static final int MAX_RULES = 256;

  /** What makes a rule fire: a boolean being true or false, a number above or below a limit. */
  public enum Kind {
    TRUE,
    FALSE,
    HIGH,
    LOW;

    public boolean numeric() {
      return this == HIGH || this == LOW;
    }
  }

  public enum Severity {
    ALARM,
    WARNING
  }

  /** One watched value. The component is named by its full path ("app/plant/ahu1/fan"). */
  public static final class Rule {
    public String id, label = "", path = "", slot = "";
    public Kind kind = Kind.TRUE;
    public Severity severity = Severity.ALARM;

    /** For HIGH and LOW: the limit, and how far the value must come back before it clears. */
    public double limit, band;

    /** Seconds the condition must hold before the alarm is raised. */
    public int delay;

    public Rule copy() {
      Rule r = new Rule();
      r.id = id;
      r.label = label;
      r.path = path;
      r.slot = slot;
      r.kind = kind;
      r.severity = severity;
      r.limit = limit;
      r.band = band;
      r.delay = delay;
      return r;
    }

    /**
     * Whether the condition holds for a value; null when the value says nothing (not a number, not
     * a boolean). An alarm that is already active clears only past the band.
     */
    public Boolean holds(String value, boolean active) {
      if (value == null) return null;
      if (!kind.numeric()) {
        if (!value.equals("true") && !value.equals("false")) return null;
        return value.equals("true") == (kind == Kind.TRUE);
      }
      double v;
      try {
        v = Double.parseDouble(value);
      } catch (NumberFormatException notNumber) {
        return null;
      }
      if (Double.isNaN(v)) return null;
      double back = active ? band : 0;
      return kind == Kind.HIGH ? v > limit - back : v < limit + back;
    }
  }

  public final List<Rule> rules = new ArrayList<>();

  @Override
  public Alarms copy() {
    Alarms a = new Alarms();
    for (Rule r : rules) a.rules.add(r.copy());
    return a;
  }

  public Rule rule(String id) {
    for (Rule r : rules) if (r.id.equals(id)) return r;
    return null;
  }

  /** An id no rule has yet. */
  public String freeId() {
    for (int i = 1; ; i++) if (rule("a" + i) == null) return "a" + i;
  }

  /** The component a rule watches, or null when there is none by that path. */
  public static Project.Node node(Project p, String path) {
    String id = ScreenBindings.folderId(p, path);
    return id == null || id.equals("0") ? null : p.nodes.get(id);
  }

  /** Whether a slot type can feed a rule of that kind. */
  public static boolean fits(Kind kind, String type) {
    String t = type == null ? "" : type.replace("sys::", "");
    if (!kind.numeric()) return t.equals("bool");
    return t.equals("float")
        || t.equals("double")
        || t.equals("int")
        || t.equals("long")
        || t.equals("short")
        || t.equals("byte");
  }

  /** Whether a rule lies in a folder or below it. */
  public static boolean under(Rule r, String folder) {
    return r.path.equals(folder) || r.path.startsWith(folder + "/");
  }

  /**
   * Keeps the rules on their components when an edit renames or moves them: a rule names its
   * component by path. What the edit deleted is left as it was and shows as missing.
   */
  public static void follow(Project before, Project after) {
    if (Alarms.of(before) == null || Alarms.of(after) == null) return;
    boolean moved = false;
    for (Project.Node n : before.nodes.values()) {
      Project.Node now = after.nodes.get(n.id);
      if (now != null && !(now.name.equals(n.name) && now.parent.equals(n.parent))) moved = true;
    }
    if (!moved) return;
    for (Rule r : Alarms.of(after).rules) {
      Rule old = Alarms.of(before).rule(r.id);
      // A rule the edit itself pointed elsewhere is the user's choice.
      if (old == null || !old.path.equals(r.path)) continue;
      Project.Node n = node(before, old.path);
      if (n != null && after.nodes.containsKey(n.id))
        r.path = ScreenBindings.folderPath(after, n.id);
    }
  }

  /** Where an alarm stands. RETURNED: the value is back to normal but nobody acknowledged it. */
  public enum Phase {
    NORMAL,
    ACTIVE,
    RETURNED
  }

  public static final class State {
    public Phase phase = Phase.NORMAL;
    public boolean acked;

    /** When it was raised, and the value that raised it or was seen last while active. */
    public long since;

    public String value = "";
    long pending = -1;
    String watch = "";
  }

  /** One line of the journal: "raised", "cleared" or "acked". */
  public record Event(
      long time,
      String rule,
      String label,
      String path,
      String what,
      String value,
      String severity) {}

  /** The running state of every rule. It is not part of the project and is never undone. */
  public static final class Tracker {
    private final Map<String, State> states = new LinkedHashMap<>();

    public State state(String rule) {
      State s = states.get(rule);
      return s == null ? new State() : s;
    }

    /**
     * Looks at every rule once. read gives the current value of a rule, or null while there is no
     * trustworthy one: then the rule keeps its state, an alarm is neither raised nor cleared.
     */
    public List<Event> step(Alarms alarms, Function<Rule, String> read, long now) {
      List<Event> events = new ArrayList<>();
      Set<String> known = new HashSet<>();
      if (alarms != null)
        for (Rule r : alarms.rules) {
          known.add(r.id);
          State s = states.computeIfAbsent(r.id, id -> new State());
          // A rule pointed at another value starts from scratch.
          String watch = r.path + "." + r.slot + " " + r.kind;
          if (!s.watch.equals(watch)) {
            s = new State();
            s.watch = watch;
            states.put(r.id, s);
          }
          String value = read.apply(r);
          Boolean holds = r.holds(value, s.phase == Phase.ACTIVE);
          if (holds == null) continue;
          if (holds) {
            if (s.phase == Phase.ACTIVE) {
              s.value = value;
              continue;
            }
            if (s.pending < 0) s.pending = now;
            if (now - s.pending < r.delay * 1000L) continue;
            s.phase = Phase.ACTIVE;
            s.acked = false;
            s.since = now;
            s.value = value;
            s.pending = -1;
            events.add(event(now, r, "raised", value));
          } else {
            s.pending = -1;
            if (s.phase != Phase.ACTIVE) continue;
            s.phase = s.acked ? Phase.NORMAL : Phase.RETURNED;
            s.value = value;
            events.add(event(now, r, "cleared", value));
          }
        }
      states.keySet().retainAll(known);
      return events;
    }

    /** Acknowledges one alarm; null when there was nothing to acknowledge. */
    public Event ack(Rule r, long now) {
      State s = states.get(r.id);
      if (s == null || s.phase == Phase.NORMAL || (s.phase == Phase.ACTIVE && s.acked)) return null;
      if (s.phase == Phase.RETURNED) s.phase = Phase.NORMAL;
      s.acked = true;
      return event(now, r, "acked", s.value);
    }

    /** Forgets everything: another project or another device. */
    public void clear() {
      states.clear();
    }

    private static Event event(long now, Rule r, String what, String value) {
      return new Event(
          now, r.id, r.label, r.path + "." + r.slot, what, value, r.severity.name().toLowerCase());
    }
  }
}
