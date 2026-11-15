package ru.bccontrol.sedonastudio.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Widgets shipped with kits (docs/widgets.md in SedonaPro). A device serves them as "m:ui.zip"
 * (index.json plus each kit's ui folder); an SDK has the same folders in "ui/<kit>/" beside its
 * manifests. Read with limits on sizes, counts and names; nothing in them is executed.
 */
public final class KitUi {
  public static final String FORMAT = "sedonapro-ui/1";
  public static final int MAX_ZIP = 4 * 1024 * 1024, MAX_FILE = 256 * 1024, MAX_FILES = 1000;
  private static final long MAX_TOTAL = 16L * 1024 * 1024;
  private static final Pattern PATH =
      Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}(/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}){1,3}");

  /** One kit's widgets: id -> template, the files they draw with, translations by language. */
  public record Kit(
      String name,
      String version,
      String checksum,
      Map<String, WidgetTemplate> widgets,
      Map<String, String> broken,
      Map<String, byte[]> files,
      Map<String, Map<String, String>> texts) {
    /** The translation of a template text, or the text itself. */
    public String text(String language, String english) {
      return texts.getOrDefault(language, Map.of()).getOrDefault(english, english);
    }
  }

  private KitUi() {}

  /**
   * The schema to check a widget against: the given kit builds plus the builds of everything they
   * depend on. Manifests the session lacks come from the source (an SDK) when there is one; a
   * dependency missing from the given builds is taken from the source, else from the one build the
   * session has.
   */
  public static sedona.Schema schema(Collection<String> parts, AppFiles.KitSource source)
      throws Exception {
    Map<String, sedona.KitPart> chosen = new LinkedHashMap<>();
    Deque<sedona.KitPart> todo = new ArrayDeque<>();
    for (String part : parts) {
      sedona.KitPart p = sedona.KitPart.parse(part);
      if (chosen.putIfAbsent(p.name, p) == null) todo.add(p);
    }
    while (!todo.isEmpty()) {
      sedona.KitPart p = todo.removeFirst();
      if (!sedona.manifest.ManifestDb.toFile(p).isFile() && source != null) {
        byte[] xml = source.manifest(p.toString());
        if (xml != null)
          try {
            ManifestArchive.loadSchema(
                new sedona.KitPart[] {p},
                uri -> {
                  if (!uri.equals("m:" + p + ".xml")) throw new IOException(uri);
                  return xml;
                });
          } catch (Exception dependenciesNotYetCached) {
            // The manifest itself is cached by now; its dependencies follow below.
          }
      }
      sedona.manifest.KitManifest m = sedona.manifest.ManifestDb.load(p);
      if (m == null) continue;
      for (sedona.Depend d : m.depends) {
        if (d == null || chosen.containsKey(d.name())) continue;
        String fromSource = source == null ? null : source.part(d.name());
        sedona.KitPart dep = fromSource == null ? null : sedona.KitPart.parse(fromSource);
        if (dep == null) {
          sedona.KitPart[] installed = sedona.manifest.ManifestDb.listInstalledParts(d.name());
          if (installed != null && installed.length == 1) dep = installed[0];
        }
        if (dep != null) {
          chosen.put(dep.name, dep);
          todo.add(dep);
        }
      }
    }
    return sedona.Schema.load(chosen.values().toArray(new sedona.KitPart[0]));
  }

  /** Reads a device's ui.zip. Kits that are not in index.json are ignored. */
  public static Map<String, Kit> fromZip(byte[] zip) throws IOException {
    if (zip.length > MAX_ZIP) throw new IOException("ui.zip larger than " + MAX_ZIP + " bytes");
    Map<String, byte[]> entries = new LinkedHashMap<>();
    long total = 0;
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        if (e.isDirectory()) continue;
        if (entries.size() >= MAX_FILES) throw new IOException("ui.zip has too many files");
        String name = e.getName();
        if (!name.equals("index.json") && !safe(name))
          throw new IOException("ui.zip: bad file name " + name);
        byte[] data = readLimited(in);
        total += data.length;
        if (total > MAX_TOTAL) throw new IOException("ui.zip too large unpacked");
        if (entries.put(name, data) != null) throw new IOException("ui.zip: duplicate " + name);
      }
    }
    byte[] index = entries.get("index.json");
    if (index == null) throw new IOException("ui.zip has no index.json");
    Map<String, Object> root = Json.object(new String(index, StandardCharsets.UTF_8));
    if (!FORMAT.equals(root.get("format")))
      throw new IOException("ui.zip: unknown format " + root.get("format"));
    if (!(root.get("kits") instanceof Map<?, ?> kits)) throw new IOException("ui.zip: no kits");
    Map<String, Kit> out = new TreeMap<>();
    for (var k : kits.entrySet()) {
      String kit = String.valueOf(k.getKey());
      if (!(k.getValue() instanceof Map<?, ?> info)) continue;
      Map<String, byte[]> files = new TreeMap<>();
      String prefix = kit + "/";
      for (var e : entries.entrySet())
        if (e.getKey().startsWith(prefix))
          files.put(e.getKey().substring(prefix.length()), e.getValue());
      out.put(
          kit,
          kit(
              kit,
              String.valueOf(info.get("version")),
              String.valueOf(info.get("checksum")),
              files));
    }
    return out;
  }

  /** Reads an SDK's "ui" folder: one sub-folder per kit. */
  public static Map<String, Kit> fromFolder(Path ui) throws IOException {
    Map<String, Kit> out = new TreeMap<>();
    if (!Files.isDirectory(ui)) return out;
    try (var kits = Files.list(ui)) {
      for (Path dir : kits.sorted().toList()) {
        if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) continue;
        String kit = dir.getFileName().toString();
        Map<String, byte[]> files = new TreeMap<>();
        try (var walk = Files.walk(dir, 3)) {
          for (Path f : walk.filter(Files::isRegularFile).toList()) {
            String rel = dir.relativize(f).toString().replace('\\', '/');
            if (!safe(kit + "/" + rel) || Files.isSymbolicLink(f)) continue;
            if (Files.size(f) > MAX_FILE || files.size() >= MAX_FILES) continue;
            files.put(rel, Files.readAllBytes(f));
          }
        }
        out.put(kit, kit(kit, "", "", files));
      }
    }
    return out;
  }

  private static Kit kit(String name, String version, String checksum, Map<String, byte[]> files) {
    Map<String, WidgetTemplate> widgets = new TreeMap<>();
    Map<String, String> broken = new TreeMap<>();
    Map<String, Map<String, String>> texts = new TreeMap<>();
    for (var f : files.entrySet()) {
      String file = f.getKey();
      if (file.endsWith(".widget.json") && !file.contains("/")) {
        String id = file.substring(0, file.length() - ".widget.json".length());
        try {
          WidgetTemplate t = WidgetTemplate.parse(new String(f.getValue(), StandardCharsets.UTF_8));
          if (!t.id.equals(id)) throw new IllegalArgumentException("id differs from the file name");
          for (String symbol : t.files())
            if (!files.containsKey(symbol)) throw new IllegalArgumentException("missing " + symbol);
          widgets.put(id, t);
        } catch (RuntimeException e) {
          broken.put(id, String.valueOf(e.getMessage()));
        }
      } else if (file.matches("texts\\.[a-z]{2,3}\\.json"))
        try {
          Map<String, String> map = new HashMap<>();
          Json.object(new String(f.getValue(), StandardCharsets.UTF_8))
              .forEach((k, v) -> map.put(k, String.valueOf(v)));
          texts.put(file.substring(6, file.length() - 5), Map.copyOf(map));
        } catch (RuntimeException ignored) {
          // A broken translation leaves the English texts.
        }
    }
    return new Kit(
        name,
        version,
        checksum,
        Collections.unmodifiableMap(widgets),
        Collections.unmodifiableMap(broken),
        Collections.unmodifiableMap(files),
        Collections.unmodifiableMap(texts));
  }

  private static boolean safe(String path) {
    return PATH.matcher(path).matches() && !path.contains("..");
  }

  private static byte[] readLimited(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    for (int n; (n = in.read(buf)) > 0; ) {
      out.write(buf, 0, n);
      if (out.size() > MAX_FILE) throw new IOException("ui.zip: a file larger than " + MAX_FILE);
    }
    return out.toByteArray();
  }
}
