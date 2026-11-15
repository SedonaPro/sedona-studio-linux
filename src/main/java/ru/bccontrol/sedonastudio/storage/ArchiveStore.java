package ru.bccontrol.sedonastudio.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Archive;
import ru.bccontrol.sedonastudio.model.Tr;

/**
 * The history of values. The list of channels is in the project file (key "archive", format
 * "sedona-studio-archive" version 1). The samples are plain text on disk: {@code
 * <data>/archive/<project>/<channel id>/<yyyy-MM-dd>.csv}, one "milliseconds,value" line per
 * sample, a file per local day, so old days are dropped by deleting files.
 */
public final class ArchiveStore {
  public static final String FORMAT = "sedona-studio-archive";
  public static final int VERSION = 1;

  private ArchiveStore() {}

  public static Map<String, Object> encode(Archive archive) {
    check(archive);
    List<Object> channels = new ArrayList<>();
    for (Archive.Channel c : archive.channels) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", c.id);
      m.put("label", c.label);
      m.put("path", c.path);
      m.put("slot", c.slot);
      m.put("period", c.period);
      channels.add(m);
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("format", FORMAT);
    doc.put("version", VERSION);
    doc.put("channels", channels);
    return doc;
  }

  public static Archive decode(Map<?, ?> doc) {
    if (!FORMAT.equals(doc.get("format"))
        || !(doc.get("version") instanceof Number v)
        || v.intValue() != VERSION
        || !(doc.get("channels") instanceof List<?> list))
      throw new IllegalArgumentException(Tr.of("archive.badPackage"));
    Archive archive = new Archive();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> m)) throw new IllegalArgumentException(bad("?"));
      Archive.Channel c = new Archive.Channel();
      c.id = m.get("id") instanceof String s ? s : "";
      try {
        c.label = (String) m.get("label");
        c.path = (String) m.get("path");
        c.slot = (String) m.get("slot");
        Number period = (Number) m.get("period");
        if (period.doubleValue() != period.intValue()) throw new IllegalArgumentException();
        c.period = period.intValue();
      } catch (RuntimeException broken) {
        throw new IllegalArgumentException(bad(c.id));
      }
      archive.channels.add(c);
    }
    check(archive);
    return archive;
  }

  /** Refuses channels a project file must not hold. */
  public static void check(Archive archive) {
    if (archive.channels.size() > Archive.MAX_CHANNELS)
      throw new IllegalArgumentException(Tr.of("archive.tooMany", Archive.MAX_CHANNELS));
    Set<String> ids = new HashSet<>(), watched = new HashSet<>();
    for (Archive.Channel c : archive.channels) {
      boolean ok =
          c.id != null
              && c.id.matches("[A-Za-z0-9_-]{1,32}")
              && ids.add(c.id)
              && c.label != null
              && c.label.length() <= 80
              && c.label.chars().noneMatch(ch -> ch < 32)
              && c.path != null
              && !c.path.isEmpty()
              && c.path.length() <= 512
              && c.slot != null
              && c.slot.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
              && c.period >= 1
              && c.period <= 3600;
      if (!ok) throw new IllegalArgumentException(bad(c.id));
      if (!watched.add(c.path + "." + c.slot))
        throw new IllegalArgumentException(Tr.of("archive.twice", c.path + "." + c.slot));
    }
  }

  private static String bad(String id) {
    return Tr.of("archive.badChannel", id == null ? "?" : id);
  }

  // ---------------------------------------------------------------- samples on disk

  /** A folder name made of a project name: letters, digits and a few signs, the rest as "_". */
  static String safe(String name) {
    StringBuilder b = new StringBuilder();
    for (char ch : name.toCharArray())
      b.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' ? ch : '_');
    String text = b.length() > 60 ? b.substring(0, 60) : b.toString();
    if (text.isEmpty()) text = "_";
    // Different names must not land in one folder after the replacement.
    return text.equals(name) ? text : text + "-" + Integer.toHexString(name.hashCode());
  }

  /** Where the samples of a channel are. The id was checked: it is a plain file name. */
  public static Path folder(Path data, String project, String channel) {
    if (!channel.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException(bad(channel));
    return data.resolve("archive").resolve(safe(project)).resolve(channel);
  }

  private static LocalDate day(long time) {
    return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate();
  }

  public static void append(Path data, String project, String channel, List<Archive.Sample> samples)
      throws IOException {
    if (samples.isEmpty()) return;
    Path folder = folder(data, project, channel);
    Files.createDirectories(folder);
    Map<LocalDate, StringBuilder> byDay = new LinkedHashMap<>();
    for (Archive.Sample s : samples)
      byDay
          .computeIfAbsent(day(s.time()), d -> new StringBuilder())
          .append(s.time())
          .append(',')
          .append(s.value())
          .append('\n');
    for (Map.Entry<LocalDate, StringBuilder> e : byDay.entrySet())
      Files.writeString(
          folder.resolve(e.getKey() + ".csv"),
          e.getValue(),
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
  }

  /** The samples of a channel between two moments, oldest first. A damaged line is skipped. */
  public static List<Archive.Sample> read(
      Path data, String project, String channel, long from, long to) throws IOException {
    List<Archive.Sample> samples = new ArrayList<>();
    Path folder = folder(data, project, channel);
    if (!Files.isDirectory(folder) || to < from) return samples;
    LocalDate last = day(to);
    for (LocalDate d = day(from); !d.isAfter(last); d = d.plusDays(1)) {
      Path file = folder.resolve(d + ".csv");
      if (!Files.exists(file)) continue;
      for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
        int comma = line.indexOf(',');
        if (comma < 1) continue;
        try {
          long time = Long.parseLong(line.substring(0, comma));
          double value = Double.parseDouble(line.substring(comma + 1));
          if (time >= from && time <= to && Double.isFinite(value))
            samples.add(new Archive.Sample(time, value));
        } catch (NumberFormatException damaged) {
          // One unreadable line does not hide the rest of the day.
        }
      }
    }
    samples.sort(Comparator.comparingLong(Archive.Sample::time));
    return samples;
  }

  /**
   * Deletes the day files older than keepDays, in every project of this data folder, and the
   * folders that became empty. Returns how many files went.
   */
  public static int prune(Path data, int keepDays, long now) throws IOException {
    Path root = data.resolve("archive");
    if (!Files.isDirectory(root)) return 0;
    LocalDate oldest = day(now).minusDays(Math.max(1, keepDays));
    int removed = 0;
    List<Path> all;
    try (var walk = Files.walk(root, 3)) {
      all = walk.sorted(Comparator.reverseOrder()).toList();
    }
    for (Path p : all) {
      String name = p.getFileName().toString();
      if (Files.isRegularFile(p) && name.matches("\\d{4}-\\d{2}-\\d{2}\\.csv")) {
        try {
          if (LocalDate.parse(name.substring(0, 10)).isBefore(oldest)) {
            Files.delete(p);
            removed++;
          }
        } catch (java.time.format.DateTimeParseException notDay) {
          // Not one of ours: left alone.
        }
      } else if (Files.isDirectory(p) && !p.equals(root)) {
        try (var inside = Files.list(p)) {
          if (inside.findAny().isEmpty()) Files.delete(p);
        }
      }
    }
    return removed;
  }
}
