package ru.bccontrol.sedonastudio.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import ru.bccontrol.sedonastudio.model.Screens;

/**
 * Libraries of pictures and graphic components that are plugged in as files: every *.jar in a
 * "libraries" folder (beside the application, in a development tree, in the user's data folder)
 * that holds a "screen-library.json". A library is data only - nothing in the jar is ever run. What
 * the user places is copied into the project's own package, so a project does not need the library
 * to be opened elsewhere.
 */
public final class ScreenLibraries {
  private ScreenLibraries() {}

  private static final int MAX_FILE = 8 * 1024 * 1024;
  private static List<ScreenStore.Pack> packs;
  private static final Map<String, String> refused = new LinkedHashMap<>();

  /** The folders libraries are looked for in; "studio.libraries" names one more. */
  static List<Path> folders() {
    List<Path> dirs = new ArrayList<>();
    try {
      Path home =
          Paths.get(
              ScreenLibraries.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path app = Files.isDirectory(home) ? home : home.getParent();
      for (int up = 0; app != null && up < 2; up++, app = app.getParent())
        dirs.add(app.resolve("libraries"));
    } catch (Exception noHome) {
      // Looked for elsewhere.
    }
    if (System.getProperty("studio.data") != null)
      dirs.add(Paths.get(System.getProperty("studio.data"), "libraries"));
    if (System.getProperty("studio.libraries") != null)
      dirs.add(Paths.get(System.getProperty("studio.libraries")));
    return dirs;
  }

  /** Every library found, in the order of their file names; one id is taken once. */
  public static synchronized List<ScreenStore.Pack> packs() {
    if (packs == null) packs = List.copyOf(load(folders()));
    return packs;
  }

  /** Why a library file was not taken: file name to the reason. */
  public static synchronized Map<String, String> refused() {
    packs();
    return new LinkedHashMap<>(refused);
  }

  /** Looks again: after a library was added while the editor runs. */
  public static synchronized void reload() {
    packs = null;
    refused.clear();
  }

  static List<ScreenStore.Pack> load(List<Path> dirs) {
    Map<String, ScreenStore.Pack> found = new LinkedHashMap<>();
    for (Path dir : dirs) {
      if (!Files.isDirectory(dir)) continue;
      List<Path> jars = new ArrayList<>();
      try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.jar")) {
        for (Path jar : files) jars.add(jar);
      } catch (IOException unreadable) {
        continue;
      }
      jars.sort(null);
      for (Path jar : jars)
        try {
          ScreenStore.Pack pack = read(jar);
          if (pack != null) found.putIfAbsent(pack.id(), pack);
        } catch (IOException | RuntimeException bad) {
          refused.put(jar.getFileName().toString(), String.valueOf(bad.getMessage()));
        }
    }
    return new ArrayList<>(found.values());
  }

  /** The library of one jar, or null when the jar is not a library. */
  public static ScreenStore.Pack read(Path jar) throws IOException {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry(ScreenStore.LIBRARY_FILE);
      if (entry == null) return null;
      if (entry.getSize() > MAX_FILE) throw new IOException("Library is too big");
      try (InputStream in = zip.getInputStream(entry)) {
        byte[] bytes = in.readNBytes(MAX_FILE + 1);
        if (bytes.length > MAX_FILE) throw new IOException("Library is too big");
        return ScreenStore.readLibrary(new String(bytes, StandardCharsets.UTF_8));
      }
    }
  }

  /** A picture by id: of the pictures that come with the editor or of any library. */
  public static Screens.Asset asset(String id) {
    for (Screens.Asset a : ScreenStore.baseLibrary()) if (a.id.equals(id)) return a;
    for (ScreenStore.Pack pack : packs())
      for (Screens.Asset a : pack.assets()) if (a.id.equals(id)) return a;
    return null;
  }

  /** A graphic component of a library by id, or null. */
  public static Screens.Component component(String id) {
    for (ScreenStore.Pack pack : packs())
      for (Screens.Component c : pack.components()) if (c.id.equals(id)) return c;
    return null;
  }

  /** The pictures of the library a component belongs to (it is drawn with some of them). */
  public static List<Screens.Asset> picturesOf(String componentId) {
    for (ScreenStore.Pack pack : packs())
      for (Screens.Component c : pack.components())
        if (c.id.equals(componentId)) return pack.assets();
    return List.of();
  }
}
