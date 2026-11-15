package ru.bccontrol.sedonastudio.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Alarms;
import ru.bccontrol.sedonastudio.model.Tr;

/**
 * Alarm rules in the project file (key "alarms", format "sedona-studio-alarms" version 1) and the
 * alarm journal on disk. Anything outside the rules is refused as a whole.
 */
public final class AlarmStore {
  public static final String FORMAT = "sedona-studio-alarms";
  public static final int VERSION = 1;
  private static final long JOURNAL_LIMIT = 1024 * 1024;

  private AlarmStore() {}

  public static Map<String, Object> encode(Alarms alarms) {
    check(alarms);
    List<Object> rules = new ArrayList<>();
    for (Alarms.Rule r : alarms.rules) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", r.id);
      m.put("label", r.label);
      m.put("path", r.path);
      m.put("slot", r.slot);
      m.put("kind", r.kind.name().toLowerCase());
      m.put("severity", r.severity.name().toLowerCase());
      if (r.kind.numeric()) {
        m.put("limit", r.limit);
        m.put("band", r.band);
      }
      m.put("delay", r.delay);
      rules.add(m);
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("format", FORMAT);
    doc.put("version", VERSION);
    doc.put("rules", rules);
    return doc;
  }

  public static Alarms decode(Map<?, ?> doc) {
    if (!FORMAT.equals(doc.get("format"))
        || !(doc.get("version") instanceof Number v)
        || v.intValue() != VERSION
        || !(doc.get("rules") instanceof List<?> list))
      throw new IllegalArgumentException(Tr.of("alarms.badPackage"));
    Alarms alarms = new Alarms();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> m)) throw new IllegalArgumentException(bad("?"));
      Alarms.Rule r = new Alarms.Rule();
      r.id = m.get("id") instanceof String s ? s : "";
      try {
        r.label = (String) m.get("label");
        r.path = (String) m.get("path");
        r.slot = (String) m.get("slot");
        r.kind = Alarms.Kind.valueOf(((String) m.get("kind")).toUpperCase(Locale.ROOT));
        r.severity = Alarms.Severity.valueOf(((String) m.get("severity")).toUpperCase(Locale.ROOT));
        if (r.kind.numeric()) {
          r.limit = ((Number) m.get("limit")).doubleValue();
          r.band = ((Number) m.get("band")).doubleValue();
        }
        Number delay = (Number) m.get("delay");
        if (delay.doubleValue() != delay.intValue()) throw new IllegalArgumentException();
        r.delay = delay.intValue();
      } catch (RuntimeException broken) {
        throw new IllegalArgumentException(bad(r.id));
      }
      alarms.rules.add(r);
    }
    check(alarms);
    return alarms;
  }

  /** Refuses rules a project file must not hold. */
  public static void check(Alarms alarms) {
    if (alarms.rules.size() > Alarms.MAX_RULES)
      throw new IllegalArgumentException(Tr.of("alarms.tooMany", Alarms.MAX_RULES));
    Set<String> ids = new HashSet<>();
    for (Alarms.Rule r : alarms.rules) {
      boolean ok =
          r.id != null
              && r.id.matches("[A-Za-z0-9_-]{1,32}")
              && ids.add(r.id)
              && r.label != null
              && r.label.length() <= 80
              && r.label.chars().noneMatch(c -> c < 32)
              && r.path != null
              && !r.path.isEmpty()
              && r.path.length() <= 512
              && r.slot != null
              && r.slot.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
              && r.kind != null
              && r.severity != null
              && Double.isFinite(r.limit)
              && Double.isFinite(r.band)
              && r.band >= 0
              && r.delay >= 0
              && r.delay <= 3600;
      if (!ok) throw new IllegalArgumentException(bad(r.id));
    }
  }

  private static String bad(String id) {
    return Tr.of("alarms.badRule", id == null ? "?" : id);
  }

  // ---------------------------------------------------------------- journal

  private static Path journal(Path directory) {
    return directory.resolve("alarms.jsonl");
  }

  /** Adds events to the journal of this data folder; one JSON object per line, bounded in size. */
  public static void append(Path directory, List<Alarms.Event> events) throws IOException {
    if (events.isEmpty()) return;
    Files.createDirectories(directory);
    Path file = journal(directory);
    if (Files.exists(file) && Files.size(file) > JOURNAL_LIMIT)
      Files.move(
          file, directory.resolve("alarms.previous.jsonl"), StandardCopyOption.REPLACE_EXISTING);
    StringBuilder text = new StringBuilder();
    for (Alarms.Event e : events) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("time", e.time());
      m.put("rule", e.rule());
      m.put("label", e.label());
      m.put("path", e.path());
      m.put("what", e.what());
      m.put("value", e.value());
      m.put("severity", e.severity());
      text.append(Json.write(m)).append('\n');
    }
    Files.writeString(
        file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  /** The last events of the journal, oldest first. A damaged line is skipped. */
  public static List<Alarms.Event> journal(Path directory, int max) throws IOException {
    List<Alarms.Event> events = new ArrayList<>();
    for (String name : List.of("alarms.previous.jsonl", "alarms.jsonl")) {
      Path file = directory.resolve(name);
      if (!Files.exists(file)) continue;
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
        try {
          Map<String, Object> m = Json.object(line);
          events.add(
              new Alarms.Event(
                  ((Number) m.get("time")).longValue(),
                  (String) m.get("rule"),
                  (String) m.get("label"),
                  (String) m.get("path"),
                  (String) m.get("what"),
                  String.valueOf(m.get("value")),
                  (String) m.get("severity")));
        } catch (RuntimeException damaged) {
          // One unreadable line does not hide the rest of the journal.
        }
    }
    return events.size() > max ? events.subList(events.size() - max, events.size()) : events;
  }
}
