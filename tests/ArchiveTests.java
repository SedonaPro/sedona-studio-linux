import java.nio.file.*;
import java.time.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Archive;
import ru.bccontrol.sedonastudio.model.History;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.storage.ArchiveStore;
import ru.bccontrol.sedonastudio.storage.Json;
import ru.bccontrol.sedonastudio.storage.ProjectStore;

/** History of values: channels in the project file, samples on disk by day, thinning, pruning. */
public class ArchiveTests {
  static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

  static Archive.Channel channel(String id, String path, int period) {
    Archive.Channel c = new Archive.Channel();
    c.id = id; c.label = "Channel " + id; c.path = path; c.slot = "out"; c.period = period;
    return c;
  }

  static void node(Project p, String id, String name, String parent) {
    Project.Node n = new Project.Node();
    n.id = id; n.name = name; n.type = "sys::Folder"; n.kitPart = "sys"; n.parent = parent;
    p.nodes.put(id, n);
  }

  public static void main(String[] args) throws Exception {
    Path dir = Paths.get(args[0]);
    if (Files.exists(dir)) try (var walk = Files.walk(dir)) { for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(f); }
    Project p = new Project();
    p.name = "Котельная №1";
    node(p, "1", "plant", "0");
    node(p, "2", "tSup", "1");
    Archive.put(p, new Archive());
    Archive.of(p).channels.add(channel("h1", "app/plant/tSup", 5));
    Archive.of(p).channels.add(channel("h2", "app/plant", 60));
    String written = ProjectStore.encode(p);
    Project back = ProjectStore.decode(written);
    check(Archive.of(back).channels.size() == 2 && Archive.of(back).channel("h1").period == 5 && Archive.of(back).channel("app/plant/tSup", "out").id.equals("h1") && ProjectStore.encode(back).equals(written), "Channels survive the project file");
    check(!ProjectStore.encode(new Project()).contains("archive"), "No channels: no key in the file");
    Map<String, Object> newer = Json.object(written);
    @SuppressWarnings("unchecked") Map<String, Object> doc = (Map<String, Object>) newer.get("archive");
    doc.put("version", 2);
    Project kept = ProjectStore.decode(Json.write(newer));
    check(Archive.of(kept) == null && Archive.foreign(kept) != null && ProjectStore.encode(kept).contains("\"sedona-studio-archive\",\"version\":2") && ProjectStore.encode(kept.copy()).equals(ProjectStore.encode(kept)), "Channels of a newer format are kept untouched");
    for (java.util.function.Consumer<Archive.Channel> damage : List.<java.util.function.Consumer<Archive.Channel>>of(c -> c.id = "../x", c -> c.id = "h2", c -> c.period = 0, c -> c.period = 3601, c -> c.slot = "", c -> c.path = "app/plant")) {
      Archive broken = Archive.of(p).copy();
      damage.accept(broken.channels.get(0));
      boolean refused = false;
      try { ArchiveStore.check(broken); } catch (IllegalArgumentException expected) { refused = true; }
      check(refused, "Damaged channel refused");
    }
    boolean refused = false;
    try { ArchiveStore.folder(dir, "x", "../../etc"); } catch (IllegalArgumentException expected) { refused = true; }
    check(refused, "A channel id cannot leave the archive folder");
    check(Archive.fits("sys::float") && Archive.fits("bool") && !Archive.fits("sys::Str") && Archive.number("true") == 1 && Archive.number("false") == 0 && Archive.number("21.5") == 21.5 && Archive.number("nan") == null && Archive.number("—") == null && Archive.number(null) == null, "What can be recorded and as which number");
    String first = Archive.of(p).freeId(1000);
    check(Archive.of(p).channel(first) == null && first.matches("[A-Za-z0-9_-]{1,32}"), "New id");

    // Samples over three local days.
    ZoneId zone = ZoneId.systemDefault();
    long day2 = LocalDate.of(2026, 3, 10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
    long day1 = day2 - 86_400_000L, day3 = day2 + 86_400_000L;
    List<Archive.Sample> all = new ArrayList<>();
    for (long base : new long[] {day1, day2, day3}) for (int i = 0; i < 100; i++) all.add(new Archive.Sample(base + i * 5000L, 20 + Math.sin(i / 7.0)));
    ArchiveStore.append(dir, p.name, "h1", all.subList(0, 150));
    ArchiveStore.append(dir, p.name, "h1", all.subList(150, 300));
    Path folder = ArchiveStore.folder(dir, p.name, "h1");
    try (var files = Files.list(folder)) { check(files.count() == 3, "One file per local day"); }
    check(folder.startsWith(dir.resolve("archive")) && dir.resolve("archive").relativize(folder).getNameCount() == 2, "Samples lie under archive/<project>/<channel>");
    check(ArchiveStore.read(dir, p.name, "h1", day1, day3 + 1_000_000).equals(all), "Everything written is read back in order");
    List<Archive.Sample> part = ArchiveStore.read(dir, p.name, "h1", day2 + 10_000, day2 + 20_000);
    check(part.size() == 3 && part.get(0).time() == day2 + 10_000, "A stretch inside one day");
    check(ArchiveStore.read(dir, p.name, "h2", day1, day3).isEmpty() && ArchiveStore.read(dir, "Other", "h1", day1, day3).isEmpty(), "Another channel or project has its own samples");
    Files.writeString(folder.resolve(LocalDate.of(2026, 3, 10) + ".csv"), "broken line\n,5\n" + (day2 + 600_000) + ",abc\n", StandardOpenOption.APPEND);
    check(ArchiveStore.read(dir, p.name, "h1", day2, day2 + 3_600_000).size() == 100, "Damaged lines are skipped");
    List<Archive.Sample> thin = Archive.thin(all, 60);
    double low = all.stream().mapToDouble(Archive.Sample::value).min().getAsDouble(), high = all.stream().mapToDouble(Archive.Sample::value).max().getAsDouble();
    boolean ordered = true;
    for (int i = 1; i < thin.size(); i++) if (thin.get(i).time() < thin.get(i - 1).time()) ordered = false;
    check(thin.size() <= 60 && thin.size() >= 30 && ordered && thin.stream().anyMatch(s -> s.value() == low) && thin.stream().anyMatch(s -> s.value() == high) && Archive.thin(part, 60) == part, "Thinning keeps the order and the extremes");
    check(ArchiveStore.prune(dir, 1, day3) == 1 && ArchiveStore.read(dir, p.name, "h1", day1, day3 + 1_000_000).size() == 200, "Pruning drops the days that are too old");
    check(ArchiveStore.prune(dir, 1, day3 + 40L * 86_400_000L) == 2 && !Files.exists(folder) && Files.exists(dir.resolve("archive")), "Pruning removes the folders it emptied");

    // Renames and moves.
    History h = new History();
    h.reset(back);
    h.edit(x -> x.nodes.get("1").name = "site");
    check(Archive.of(h.get()).channel("h1").path.equals("app/site/tSup") && Archive.of(h.get()).channel("h2").path.equals("app/site"), "A channel follows a renamed folder");
    h.undo();
    check(Archive.of(h.get()).channel("h1").path.equals("app/plant/tSup"), "Undo brings the path back");
    System.out.println("PASS history: project file, newer format kept, files by day, stretch read, damaged lines, thinning, pruning, renames followed");
  }
}
