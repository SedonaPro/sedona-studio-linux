package ru.bccontrol.sedonastudio.model;

import java.util.*;

/**
 * Which values of a project are written to the history, and how often. Pure data: no JavaFX, no
 * device, no files. The samples themselves live on disk beside the program's data, never in the
 * project.
 */
public final class Archive implements Project.Part {
  /** The key of this part in the project file. */
  public static final String KEY = "archive";

  /** This part of a project, or null while it has none (or cannot read the one it has). */
  public static Archive of(Project p) {
    return p.part(KEY, Archive.class);
  }

  /** Sets this part of a project; null removes it. */
  public static void put(Project p, Archive value) {
    p.part(KEY, value);
  }

  /** The part as a newer version wrote it, when this one cannot read it; else null. */
  public static Object foreign(Project p) {
    return p.foreign(KEY);
  }

  public static final int MAX_CHANNELS = 128;

  /** One recorded value. The component is named by its full path ("app/plant/ahu1/tSup"). */
  public static final class Channel {
    public String id, label = "", path = "", slot = "";

    /** Seconds between two samples. */
    public int period = 10;

    public Channel copy() {
      Channel c = new Channel();
      c.id = id;
      c.label = label;
      c.path = path;
      c.slot = slot;
      c.period = period;
      return c;
    }
  }

  /** One point of the history: wall-clock milliseconds and the value (a boolean is 0 or 1). */
  public record Sample(long time, double value) {}

  public final List<Channel> channels = new ArrayList<>();

  @Override
  public Archive copy() {
    Archive a = new Archive();
    for (Channel c : channels) a.channels.add(c.copy());
    return a;
  }

  public Channel channel(String id) {
    for (Channel c : channels) if (c.id.equals(id)) return c;
    return null;
  }

  /** The channel that records a value, or null. */
  public Channel channel(String path, String slot) {
    for (Channel c : channels) if (c.path.equals(path) && c.slot.equals(slot)) return c;
    return null;
  }

  /**
   * An id for a new channel. The samples on disk are kept under the id, so an id is never given
   * twice: a removed channel's history must not continue under a new one.
   */
  public String freeId(long now) {
    for (long n = now; ; n++) {
      String id = "h" + Long.toString(n, 36);
      if (channel(id) == null) return id;
    }
  }

  /** Whether a slot of that type can be recorded: booleans and numbers. */
  public static boolean fits(String type) {
    return Alarms.fits(Alarms.Kind.TRUE, type) || Alarms.fits(Alarms.Kind.HIGH, type);
  }

  /** The number to record for a value as the device gives it, or null when there is none. */
  public static Double number(String value) {
    if (value == null) return null;
    if (value.equals("true")) return 1.0;
    if (value.equals("false")) return 0.0;
    try {
      double v = Double.parseDouble(value.trim());
      return Double.isFinite(v) ? v : null;
    } catch (NumberFormatException notNumber) {
      return null;
    }
  }

  /**
   * At most about max points that still show the shape of the line: the samples are cut into equal
   * groups and each gives its lowest and highest value, in the order they came.
   */
  public static List<Sample> thin(List<Sample> samples, int max) {
    if (samples.size() <= max || max < 2) return samples;
    int groups = max / 2, size = (samples.size() + groups - 1) / groups;
    List<Sample> out = new ArrayList<>();
    for (int from = 0; from < samples.size(); from += size) {
      Sample low = samples.get(from), high = low;
      for (int i = from; i < Math.min(samples.size(), from + size); i++) {
        Sample s = samples.get(i);
        if (s.value() < low.value()) low = s;
        if (s.value() > high.value()) high = s;
      }
      if (low == high) out.add(low);
      else if (low.time() <= high.time()) {
        out.add(low);
        out.add(high);
      } else {
        out.add(high);
        out.add(low);
      }
    }
    return out;
  }

  /** Keeps the channels on their components when an edit renames or moves them. */
  public static void follow(Project before, Project after) {
    if (Archive.of(before) == null || Archive.of(after) == null) return;
    boolean moved = false;
    for (Project.Node n : before.nodes.values()) {
      Project.Node now = after.nodes.get(n.id);
      if (now != null && !(now.name.equals(n.name) && now.parent.equals(n.parent))) moved = true;
    }
    if (!moved) return;
    for (Channel c : Archive.of(after).channels) {
      Channel old = Archive.of(before).channel(c.id);
      if (old == null || !old.path.equals(c.path)) continue;
      Project.Node n = Alarms.node(before, old.path);
      if (n != null && after.nodes.containsKey(n.id))
        c.path = ScreenBindings.folderPath(after, n.id);
    }
  }
}
