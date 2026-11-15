package ru.bccontrol.sedonastudio.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What can be done to a screen while arranging it: add a widget from a template, place a library
 * picture, move, align, copy, remove. Positions are per cent of the screen, as they are stored.
 */
public final class ScreenEdits {
  private ScreenEdits() {}

  /** A ready-made widget of the palette; the same set the web editor offers. */
  public record Template(String id, String title, Screens.Widget widget) {}

  public static List<Template> templates() {
    List<Template> all = new ArrayList<>();
    all.add(
        make(
            "number",
            Tr.of("screens.t.number"),
            "value",
            "",
            1,
            "appearance",
            "compact",
            "width",
            150.0));
    all.add(
        make(
            "temperature",
            Tr.of("screens.t.temperature"),
            "value",
            "°C",
            1,
            "appearance",
            "compact",
            "width",
            145.0));
    all.add(make("pressure", Tr.of("screens.t.pressure"), "value", "Pa", 0));
    all.add(make("percent", Tr.of("screens.t.percent"), "value", "%", 1));
    all.add(
        make(
            "status",
            Tr.of("screens.t.status"),
            "indicator",
            "",
            0,
            "appearance",
            "compact",
            "width",
            150.0));
    all.add(make("setpoint", Tr.of("screens.t.setpoint"), "setpoint", "", 1));
    all.add(
        make(
            "bare",
            Tr.of("screens.t.bare"),
            "value",
            "",
            1,
            "appearance",
            "value",
            "width",
            90.0,
            "showLabel",
            false,
            "showQuality",
            false));
    all.add(
        make(
            "lamp",
            Tr.of("screens.t.lamp"),
            "indicator",
            "",
            0,
            "appearance",
            "lamp",
            "width",
            40.0,
            "showLabel",
            false,
            "showQuality",
            false));
    all.add(
        make(
            "toggle-button",
            Tr.of("screens.t.toggleButton"),
            "toggle",
            "",
            0,
            "appearance",
            "card",
            "width",
            150.0));
    all.add(
        make(
            "toggle",
            Tr.of("screens.t.toggle"),
            "toggle",
            "",
            0,
            "appearance",
            "compact",
            "width",
            160.0));
    all.add(
        make(
            "on",
            Tr.of("screens.t.on"),
            "button",
            "",
            0,
            "appearance",
            "compact",
            "width",
            140.0,
            "command",
            true));
    all.add(
        make(
            "off",
            Tr.of("screens.t.off"),
            "button",
            "",
            0,
            "appearance",
            "compact",
            "width",
            140.0,
            "command",
            false));
    all.add(
        make(
            "bar",
            Tr.of("screens.t.bar"),
            "bar",
            "%",
            1,
            "appearance",
            "compact",
            "width",
            180.0,
            "min",
            0.0,
            "max",
            100.0));
    all.add(
        make(
            "gauge",
            Tr.of("screens.t.gauge"),
            "gauge",
            "%",
            1,
            "width",
            140.0,
            "min",
            0.0,
            "max",
            100.0));
    all.add(
        make(
            "slider",
            Tr.of("screens.t.slider"),
            "slider",
            "",
            1,
            "width",
            180.0,
            "min",
            0.0,
            "max",
            100.0));
    all.add(
        make(
            "trend",
            Tr.of("screens.t.trend"),
            "trend",
            "",
            1,
            "width",
            420.0,
            "height",
            260.0,
            "windowSeconds",
            300.0,
            "sampleSeconds",
            1.0,
            "autoScale",
            true,
            "legend",
            true));
    all.add(
        make(
            "bars",
            Tr.of("screens.t.bars"),
            "bars",
            "",
            1,
            "width",
            360.0,
            "height",
            220.0,
            "min",
            0.0,
            "max",
            100.0,
            "legend",
            true));
    all.add(
        make("table", Tr.of("screens.t.table"), "table", "", 2, "width", 480.0, "height", 240.0));
    all.add(
        make(
            "text",
            Tr.of("screens.t.text"),
            "text",
            "",
            0,
            "appearance",
            "value",
            "width",
            180.0,
            "showQuality",
            false));
    return all;
  }

  private static Template make(
      String id, String title, String kind, String unit, int decimals, Object... options) {
    Screens.Widget w = new Screens.Widget();
    w.kind = kind;
    w.label = title;
    w.unit = unit;
    w.decimals = decimals;
    w.path = ".";
    w.slot = "__unbound";
    w.type = Screens.BOOL_KINDS.contains(kind) ? "bool" : "float";
    // Saved in the order the shared format lists the settings.
    Map<String, Object> given = new LinkedHashMap<>();
    for (int i = 0; i < options.length; i += 2) given.put((String) options[i], options[i + 1]);
    for (String key :
        List.of(
            "appearance",
            "width",
            "height",
            "windowSeconds",
            "sampleSeconds",
            "fontSize",
            "min",
            "max",
            "showLabel",
            "showUnit",
            "showQuality",
            "border",
            "background",
            "command",
            "autoScale",
            "legend")) if (given.containsKey(key)) w.options.put(key, given.get(key));
    if (Screens.DATA_KINDS.contains(kind)) w.series = new ArrayList<>();
    return new Template(id, title, w);
  }

  private static String newId(String prefix) {
    return prefix
        + Long.toString(System.nanoTime(), 36)
        + Integer.toString((int) (Math.random() * 1296), 36);
  }

  private static double clamp(double v) {
    return Math.max(0, Math.min(100, v));
  }

  /** Puts a widget made from a template at x, y (per cent) and returns its id. */
  public static String addWidget(Screens.View view, Template template, double x, double y) {
    if (view.widgets == null) view.widgets = new ArrayList<>();
    Screens.Widget w = template.widget().copy();
    w.id = newId("w");
    w.x = clamp(x);
    w.y = clamp(y);
    view.widgets.add(w);
    return w.id;
  }

  /** Places a library picture; the picture is copied into the package if it is not there yet. */
  public static String addSymbol(
      Screens screens, Screens.View view, Screens.Asset asset, double x, double y) {
    if (screens.library == null) screens.library = new ArrayList<>();
    if (screens.asset(asset.id) == null) screens.library.add(asset.copy());
    if (view.objects == null) view.objects = new ArrayList<>();
    Screens.Symbol o = new Screens.Symbol();
    o.id = newId("o");
    o.assetId = asset.id;
    o.label = asset.title;
    o.x = clamp(x);
    o.y = clamp(y);
    view.objects.add(o);
    return o.id;
  }

  /**
   * Places a graphic component: the component and the pictures it is drawn with are copied into the
   * package if they are not there yet. Returns the id of the new part; its points are not bound.
   */
  public static String addPart(
      Screens screens,
      Screens.View view,
      Screens.Component def,
      List<Screens.Asset> pictures,
      double x,
      double y) {
    if (screens.component(def.id) == null) {
      for (String assetId : def.assets()) {
        if (screens.asset(assetId) != null) continue;
        Screens.Asset asset = null;
        for (Screens.Asset a : pictures) if (a.id.equals(assetId)) asset = a;
        if (asset == null) throw new IllegalArgumentException("No picture " + assetId);
        if (screens.library == null) screens.library = new ArrayList<>();
        screens.library.add(asset.copy());
      }
      if (screens.components == null) screens.components = new ArrayList<>();
      screens.components.add(def.copy());
    }
    if (view.parts == null) view.parts = new ArrayList<>();
    Screens.Part part = new Screens.Part();
    part.id = newId("c");
    part.componentId = def.id;
    part.label = def.title;
    part.x = clamp(x);
    part.y = clamp(y);
    view.parts.add(part);
    return part.id;
  }

  /**
   * Adds the user's own picture to the library of the package (one drawing, shown in every state)
   * and returns it. The id is made from the title; a picture with that id is replaced.
   */
  public static Screens.Asset addAsset(
      Screens screens, String title, String svg, double width, double height) {
    String id = title.replaceAll("[^A-Za-z0-9_-]", "-");
    if (id.length() > 60) id = id.substring(0, 60);
    if (id.replace("-", "").isEmpty()) id = newId("p");
    Screens.Asset asset = new Screens.Asset();
    asset.id = id;
    asset.title = title.length() > 128 ? title.substring(0, 128) : title;
    asset.width = Math.min(2000, width);
    asset.height = Math.min(2000, height);
    asset.states.put("off", svg);
    if (screens.library == null) screens.library = new ArrayList<>();
    String key = id;
    screens.library.removeIf(a -> a.id.equals(key));
    screens.library.add(asset);
    return asset;
  }

  /** Moves the items to new places: id to {x, y} in per cent (NaN leaves a coordinate as it is). */
  public static void place(Screens.View view, Map<String, double[]> places) {
    places.forEach(
        (id, xy) -> {
          Screens.Widget w = view.widget(id);
          Screens.Symbol o = view.object(id);
          if (w != null) {
            if (!Double.isNaN(xy[0])) w.x = clamp(xy[0]);
            if (!Double.isNaN(xy[1])) w.y = clamp(xy[1]);
          } else if (o != null) {
            if (!Double.isNaN(xy[0])) o.x = clamp(xy[0]);
            if (!Double.isNaN(xy[1])) o.y = clamp(xy[1]);
          } else if (view.part(id) != null) {
            Screens.Part part = view.part(id);
            if (!Double.isNaN(xy[0])) part.x = clamp(xy[0]);
            if (!Double.isNaN(xy[1])) part.y = clamp(xy[1]);
          }
        });
  }

  public static void remove(Screens.View view, Collection<String> ids) {
    if (view.widgets != null) view.widgets.removeIf(w -> ids.contains(w.id));
    if (view.objects != null) view.objects.removeIf(o -> ids.contains(o.id));
    if (view.parts != null) view.parts.removeIf(p -> ids.contains(p.id));
  }

  /** Copies the items a little to the right and down; returns the ids of the copies. */
  public static List<String> duplicate(Screens.View view, Collection<String> ids) {
    List<String> made = new ArrayList<>();
    if (view.widgets != null)
      for (Screens.Widget w : List.copyOf(view.widgets))
        if (ids.contains(w.id)) {
          Screens.Widget c = w.copy();
          c.id = newId("w");
          c.x = clamp(w.x + 2);
          c.y = clamp(w.y + 3);
          view.widgets.add(c);
          made.add(c.id);
        }
    if (view.objects != null)
      for (Screens.Symbol o : List.copyOf(view.objects))
        if (ids.contains(o.id)) {
          Screens.Symbol c = o.copy();
          c.id = newId("o");
          c.x = clamp(o.x + 2);
          c.y = clamp(o.y + 3);
          view.objects.add(c);
          made.add(c.id);
        }
    if (view.parts != null)
      for (Screens.Part p : List.copyOf(view.parts))
        if (ids.contains(p.id)) {
          Screens.Part c = p.copy();
          c.id = newId("c");
          c.x = clamp(p.x + 2);
          c.y = clamp(p.y + 3);
          view.parts.add(c);
          made.add(c.id);
        }
    return made;
  }

  /** An item with its place and size in per cent of the screen. */
  public record Box(String id, double x, double y, double w, double h) {}

  /**
   * New places for aligned items. Actions: left, right, centerX, top, bottom, centerY (two items or
   * more), distributeX, distributeY (three or more: equal gaps between the outer two).
   */
  public static Map<String, double[]> align(List<Box> boxes, String action) {
    Map<String, double[]> out = new LinkedHashMap<>();
    if (action.startsWith("distribute")) {
      if (boxes.size() < 3) return out;
      boolean horizontal = action.equals("distributeX");
      List<Box> ordered = new ArrayList<>(boxes);
      ordered.sort(Comparator.comparingDouble(b -> horizontal ? b.x() : b.y()));
      Box first = ordered.get(0), last = ordered.get(ordered.size() - 1);
      double sizes = 0;
      for (Box b : ordered) sizes += horizontal ? b.w() : b.h();
      double span = horizontal ? last.x() + last.w() - first.x() : last.y() + last.h() - first.y();
      double gap = (span - sizes) / (ordered.size() - 1),
          cursor = horizontal ? first.x() : first.y();
      for (Box b : ordered) {
        out.put(
            b.id(),
            horizontal ? new double[] {cursor, Double.NaN} : new double[] {Double.NaN, cursor});
        cursor += (horizontal ? b.w() : b.h()) + gap;
      }
      return out;
    }
    if (boxes.size() < 2) return out;
    double left = 100, right = 0, top = 100, bottom = 0;
    for (Box b : boxes) {
      left = Math.min(left, b.x());
      right = Math.max(right, b.x() + b.w());
      top = Math.min(top, b.y());
      bottom = Math.max(bottom, b.y() + b.h());
    }
    for (Box b : boxes) {
      double x = Double.NaN, y = Double.NaN;
      switch (action) {
        case "left" -> x = left;
        case "right" -> x = right - b.w();
        case "centerX" -> x = (left + right - b.w()) / 2;
        case "top" -> y = top;
        case "bottom" -> y = bottom - b.h();
        default -> y = (top + bottom - b.h()) / 2;
      }
      out.put(b.id(), new double[] {x, y});
    }
    return out;
  }
}
