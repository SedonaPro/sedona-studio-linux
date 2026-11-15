package ru.bccontrol.sedonastudio.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import ru.bccontrol.sedonastudio.model.Screens;
import ru.bccontrol.sedonastudio.model.Tr;

/**
 * Reads and writes the shared screens package ("mcs-sedona-ui", versions 1 and 2). The rules and
 * limits are those of the web editor, so a package saved here opens there and the other way round;
 * a package that breaks them is refused as a whole and is never rewritten.
 */
public final class ScreenStore {
  public static final String FORMAT = "mcs-sedona-ui";

  /** Name of the package file on a device and on disk. */
  public static final String FILE = "mcs-ui.json";

  public static final int LIMIT = 512 * 1024;
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern SECRET = Pattern.compile("password|secret|cred|^key$", 2);
  private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
  private static final Object[][] RANGES = {
    {"width", 40.0, 900.0},
    {"height", 100.0, 600.0},
    {"windowSeconds", 10.0, 3600.0},
    {"sampleSeconds", 1.0, 60.0},
    {"fontSize", 10.0, 72.0},
    {"min", -1e9, 1e9},
    {"max", -1e9, 1e9}
  };
  private static final List<String> FLAGS =
      List.of(
          "showLabel",
          "showUnit",
          "showQuality",
          "border",
          "background",
          "command",
          "autoScale",
          "legend");
  private static final List<String> APPEARANCES = List.of("card", "compact", "value", "lamp");

  private ScreenStore() {}

  /** An empty package with a fresh id. */
  public static Screens create(String title) {
    Screens s = new Screens();
    s.id = java.util.UUID.randomUUID().toString();
    s.title = title;
    return s;
  }

  public static Screens read(String json) {
    if (json.length() > LIMIT) throw new IllegalArgumentException(Tr.of("screens.tooBig"));
    return decode(Json.object(json));
  }

  /** The package as text; refuses anything the shared format does not allow. */
  public static String write(Screens screens) {
    String json = Json.write(encode(screens));
    if (json.getBytes(StandardCharsets.UTF_8).length > LIMIT)
      throw new IllegalArgumentException(Tr.of("screens.tooBig"));
    return json;
  }

  /**
   * Throws when the screens could not be saved as they are (limits, names, bindings); sets the
   * format version they need.
   */
  public static void check(Screens screens) {
    write(screens);
    // What was added or removed decides the version the package is saved with.
    screens.version = parse(plain(screens)).version;
  }

  public static Screens decode(Map<?, ?> in) {
    Screens s = parse(in);
    write(s);
    return s;
  }

  /** The package as plain maps and lists, after the same checks a package from a file gets. */
  public static Map<String, Object> encode(Screens s) {
    return plain(parse(plain(s)));
  }

  private static Screens parse(Map<?, ?> probe) {
    Screens s = new Screens();
    Object version = probe.get("version");
    if (!FORMAT.equals(probe.get("format"))
        || !(version instanceof Number n
            && (n.doubleValue() == 1 || n.doubleValue() == 2 || n.doubleValue() == 3))
        || !id(probe.get("id"))
        || !(probe.get("views") instanceof List<?> views)
        || views.size() > 128) throw new IllegalArgumentException(Tr.of("screens.badPackage"));
    s.id = (String) probe.get("id");
    s.title = cut(truthy(probe.get("title")) ? probe.get("title") : "Визуализация", 128);
    if (probe.containsKey("library")) s.library = library(probe.get("library"), 64);
    if (probe.containsKey("components"))
      s.components =
          components(probe.get("components"), s.library == null ? List.of() : s.library, 64);
    Set<String> ids = new HashSet<>();
    for (Object raw : views) {
      if (!(raw instanceof Map<?, ?> v)
          || !id(v.get("id"))
          || !ids.add((String) v.get("id"))
          || !text(v.get("folder"), 2048)
          || !text(v.get("svg"), LIMIT))
        throw new IllegalArgumentException(Tr.of("screens.badView"));
      Screens.View view = new Screens.View();
      view.id = (String) v.get("id");
      view.title = cut(truthy(v.get("title")) ? v.get("title") : "Экран", 128);
      view.folder = (String) v.get("folder");
      view.svg = (String) v.get("svg");
      if (v.containsKey("widgets")) view.widgets = widgets(v.get("widgets"));
      if (v.containsKey("objects"))
        view.objects = objects(v.get("objects"), s.library == null ? List.of() : s.library, 128);
      if (v.containsKey("parts"))
        view.parts = parts(v.get("parts"), s.components == null ? List.of() : s.components);
      s.views.add(view);
    }
    boolean extended = s.library != null || s.views.stream().anyMatch(v -> v.objects != null);
    boolean composed = s.components != null || s.views.stream().anyMatch(v -> v.parts != null);
    s.version = composed ? 3 : extended ? 2 : Math.min(2, ((Number) version).intValue());
    return s;
  }

  public static final String LIBRARY_FORMAT = "mcs-sedona-ui-library",
      LIBRARY_FILE = "screen-library.json";

  /** A library of pictures and graphic components that comes as a file of its own. */
  public record Pack(
      String id,
      String title,
      List<Screens.Asset> assets,
      List<Screens.Component> components,
      Map<String, String> groups) {
    /** Where the palette shows a picture or a component: "Folder/Subfolder", or "" at the top. */
    public String group(String itemId) {
      return groups.getOrDefault(itemId, "");
    }
  }

  /**
   * The optional "group" of each library item ("A/B"): a place in the palette, not package data.
   */
  private static void groups(Object raw, Map<String, String> into) {
    if (raw instanceof List<?> list)
      for (Object item : list)
        if (item instanceof Map<?, ?> m
            && m.get("id") instanceof String itemId
            && m.get("group") instanceof String g
            && g.length() <= 128) into.put(itemId, g);
  }

  /**
   * Reads a library ("mcs-sedona-ui-library" version 1): pictures and graphic components under the
   * same rules as in a package, so whatever is taken from it can be saved in one.
   */
  public static Pack readLibrary(String json) {
    Map<String, Object> in = Json.object(json);
    if (!LIBRARY_FORMAT.equals(in.get("format"))
        || !(in.get("version") instanceof Number n && n.doubleValue() == 1)
        || !id(in.get("id"))
        || !text(in.get("title"), 128))
      throw new IllegalArgumentException(Tr.of("screens.badPackage"));
    List<Screens.Asset> assets =
        in.containsKey("assets") ? library(in.get("assets"), 256) : List.of();
    for (Screens.Asset a : assets) a.states.replaceAll((state, svg) -> safeSvg(svg));
    List<Screens.Component> components =
        in.containsKey("components") ? components(in.get("components"), assets, 256) : List.of();
    Map<String, String> groups = new java.util.HashMap<>();
    groups(in.get("assets"), groups);
    groups(in.get("components"), groups);
    return new Pack((String) in.get("id"), (String) in.get("title"), assets, components, groups);
  }

  private static final List<String> POINT_KINDS = List.of("bool", "number", "any");

  private static List<Screens.Component> components(
      Object raw, List<Screens.Asset> library, int limit) {
    if (!(raw instanceof List<?> list) || list.size() > limit)
      throw new IllegalArgumentException(Tr.of("screens.badComponent"));
    Set<String> ids = new HashSet<>();
    List<Screens.Component> components = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> c)
          || !id(c.get("id"))
          || !ids.add((String) c.get("id"))
          || !text(c.get("title"), 128)
          || !size(c.get("width"))
          || !size(c.get("height"))
          || !(c.get("points") instanceof List<?> points)
          || points.size() > 16) throw new IllegalArgumentException(Tr.of("screens.badComponent"));
      Screens.Component def = new Screens.Component();
      def.id = (String) c.get("id");
      def.title = (String) c.get("title");
      def.width = ((Number) c.get("width")).doubleValue();
      def.height = ((Number) c.get("height")).doubleValue();
      for (Object rawPoint : points) {
        if (!(rawPoint instanceof Map<?, ?> p)
            || !id(p.get("id"))
            || !slot(p.get("id"), 64)
            || def.point((String) p.get("id")) != null
            || !text(p.get("label"), 128)
            || !POINT_KINDS.contains(p.get("kind"))
            || !(p.get("slots") instanceof List<?> slots)
            || slots.size() > 8) throw new IllegalArgumentException(Tr.of("screens.badComponent"));
        Screens.Point point = new Screens.Point();
        point.id = (String) p.get("id");
        point.label = (String) p.get("label");
        point.kind = (String) p.get("kind");
        for (Object name : slots) {
          if (!slot(name, 128)) throw new IllegalArgumentException(Tr.of("screens.badComponent"));
          point.slots.add((String) name);
        }
        def.points.add(point);
      }
      if (c.containsKey("objects")) {
        def.objects = objects(c.get("objects"), library, 16);
        for (Screens.Symbol o : def.objects) {
          inside(def, o.binding);
          inside(def, o.fault);
        }
      }
      if (c.containsKey("widgets")) {
        def.widgets = widgets(c.get("widgets"));
        if (def.widgets.size() > 16)
          throw new IllegalArgumentException(Tr.of("screens.badComponent"));
        for (Screens.Widget w : def.widgets) {
          if (Screens.DATA_KINDS.contains(w.kind))
            throw new IllegalArgumentException(Tr.of("screens.badComponent"));
          if (!w.kind.equals("text")) inside(def, w.binding());
        }
      }
      components.add(def);
    }
    return components;
  }

  /** Inside a component a binding names a point: path ".", the point's id as the slot. */
  private static void inside(Screens.Component def, Screens.Binding b) {
    if (b != null && !(b.path().equals(".") && def.point(b.slot()) != null))
      throw new IllegalArgumentException(Tr.of("screens.badComponent"));
  }

  private static List<Screens.Part> parts(Object raw, List<Screens.Component> components) {
    if (!(raw instanceof List<?> list) || list.size() > 64)
      throw new IllegalArgumentException(Tr.of("screens.badPart"));
    Set<String> ids = new HashSet<>();
    List<Screens.Part> parts = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> p)
          || !id(p.get("id"))
          || !ids.add((String) p.get("id"))
          || !text(p.get("label"), 128)
          || !percent(p.get("x"))
          || !percent(p.get("y"))
          || !(p.get("bind") instanceof Map<?, ?> bind))
        throw new IllegalArgumentException(Tr.of("screens.badPart"));
      Screens.Component def = null;
      for (Screens.Component c : components) if (c.id.equals(p.get("componentId"))) def = c;
      if (def == null) throw new IllegalArgumentException(Tr.of("screens.badPart"));
      Screens.Part part = new Screens.Part();
      part.id = (String) p.get("id");
      part.componentId = def.id;
      part.label = (String) p.get("label");
      part.x = ((Number) p.get("x")).doubleValue();
      part.y = ((Number) p.get("y")).doubleValue();
      for (Screens.Point point : def.points) {
        if (!bind.containsKey(point.id)) continue;
        Screens.Binding b = binding(bind.get(point.id));
        if (!point.accepts(b.type()))
          throw new IllegalArgumentException(Tr.of("screens.badBinding"));
        part.bind.put(point.id, b);
      }
      if (part.bind.size() != bind.size())
        throw new IllegalArgumentException(Tr.of("screens.badPart"));
      parts.add(part);
    }
    return parts;
  }

  private static List<Screens.Asset> base;

  /** Equipment pictures that come with the editor (duct, fan, damper, heater, filter). */
  public static synchronized List<Screens.Asset> baseLibrary() {
    if (base == null)
      try (java.io.InputStream in =
          ScreenStore.class.getResourceAsStream("/screens/base-library.json")) {
        base =
            in == null
                ? List.of()
                : List.copyOf(
                    library(
                        Json.object(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                            .get("assets"),
                        64));
      } catch (java.io.IOException | IllegalArgumentException unreadable) {
        base = List.of();
      }
    return base;
  }

  private static final Set<String> SVG_ELEMENTS =
      Set.of(
          "svg",
          "g",
          "defs",
          "symbol",
          "use",
          "path",
          "rect",
          "circle",
          "ellipse",
          "line",
          "polyline",
          "polygon",
          "text",
          "tspan",
          "title",
          "desc",
          "style",
          "linearGradient",
          "radialGradient",
          "stop",
          "clipPath",
          "mask",
          "pattern",
          "marker",
          "animateTransform");
  private static final Pattern ACTIVE =
      Pattern.compile("javascript:|data:|https?:|@import|expression\\(|\\\\", 2);
  private static final Pattern LOCAL_URL =
      Pattern.compile("url\\(\\s*['\"]?#[A-Za-z0-9_.:-]+['\"]?\\s*\\)");

  /**
   * Makes a drawing fit to be stored in a package: a picture and nothing else. Scripts, event
   * handlers, links to anything outside the file and elements the shared format does not allow are
   * refused (the message names the first one); editor bookkeeping (metadata) is dropped. The rules
   * are those of the web editor, so what passes here opens there.
   */
  public static String safeSvg(String text) {
    if (text.length() > LIMIT || text.contains("<!DOCTYPE") || text.contains("<!ENTITY"))
      throw new IllegalArgumentException(Tr.of("screens.svgRefused", "DOCTYPE"));
    org.w3c.dom.Document doc;
    try {
      javax.xml.parsers.DocumentBuilderFactory f =
          javax.xml.parsers.DocumentBuilderFactory.newInstance();
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      f.setNamespaceAware(true);
      javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
      builder.setErrorHandler(
          new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException e)
                throws org.xml.sax.SAXException {
              throw e;
            }
          });
      doc = builder.parse(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalArgumentException(Tr.of("screens.svgRefused", "XML"));
    }
    org.w3c.dom.Element root = doc.getDocumentElement();
    if (!"svg".equals(root.getLocalName()))
      throw new IllegalArgumentException(Tr.of("screens.svgRefused", root.getNodeName()));
    cleanSvg(root);
    try {
      javax.xml.transform.Transformer t =
          javax.xml.transform.TransformerFactory.newInstance().newTransformer();
      t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
      java.io.StringWriter out = new java.io.StringWriter();
      t.transform(
          new javax.xml.transform.dom.DOMSource(root),
          new javax.xml.transform.stream.StreamResult(out));
      return out.toString();
    } catch (Exception e) {
      throw new IllegalArgumentException(Tr.of("screens.svgRefused", "XML"));
    }
  }

  private static void cleanSvg(org.w3c.dom.Element e) {
    String tag = e.getLocalName();
    if (!"http://www.w3.org/2000/svg".equals(e.getNamespaceURI()) || !SVG_ELEMENTS.contains(tag))
      throw new IllegalArgumentException(Tr.of("screens.svgRefused", "<" + e.getNodeName() + ">"));
    if (tag.equals("style")) {
      String css = e.getTextContent();
      if (css.contains("@") || css.contains("/*") || active(css))
        throw new IllegalArgumentException(Tr.of("screens.svgRefused", "<style>"));
    }
    org.w3c.dom.NamedNodeMap attrs = e.getAttributes();
    List<org.w3c.dom.Attr> foreign = new ArrayList<>();
    for (int i = 0; i < attrs.getLength(); i++) {
      org.w3c.dom.Attr a = (org.w3c.dom.Attr) attrs.item(i);
      String ns = a.getNamespaceURI(),
          name = a.getLocalName() == null ? a.getName() : a.getLocalName();
      String value = a.getValue().trim();
      if ("http://www.w3.org/2000/xmlns/".equals(ns)) continue;
      if (ns != null
          && !ns.equals("http://www.w3.org/XML/1998/namespace")
          && !ns.equals("http://www.w3.org/1999/xlink")) {
        foreign.add(a);
        continue;
      }
      boolean refused =
          name.toLowerCase(java.util.Locale.ROOT).startsWith("on")
              || name.toLowerCase(java.util.Locale.ROOT).endsWith("href")
                  && !value.matches("#[A-Za-z0-9_.:-]+")
              || name.equals("base")
              || !List.of("id", "class", "label", "lang", "space").contains(name) && active(value);
      if (refused)
        throw new IllegalArgumentException(
            Tr.of("screens.svgRefused", e.getNodeName() + " " + a.getName()));
    }
    for (org.w3c.dom.Attr a : foreign) e.removeAttributeNode(a);
    List<org.w3c.dom.Node> drop = new ArrayList<>();
    for (org.w3c.dom.Node c = e.getFirstChild(); c != null; c = c.getNextSibling())
      if (c instanceof org.w3c.dom.Element child) {
        String name = child.getLocalName();
        if (name.equals("metadata") || name.equals("namedview")) drop.add(child);
        else cleanSvg(child);
      } else if (c.getNodeType() == org.w3c.dom.Node.COMMENT_NODE
          || c.getNodeType() == org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE) drop.add(c);
    for (org.w3c.dom.Node c : drop) e.removeChild(c);
  }

  /** Anything that could run code or reach outside the file. */
  private static boolean active(String value) {
    if (ACTIVE.matcher(value).find()) return true;
    // url(#id) points inside the file; any other url(...) points out of it.
    return LOCAL_URL
        .matcher(value)
        .replaceAll("")
        .toLowerCase(java.util.Locale.ROOT)
        .contains("url(");
  }

  private static List<Screens.Widget> widgets(Object raw) {
    if (!(raw instanceof List<?> list) || list.size() > 64)
      throw new IllegalArgumentException(Tr.of("screens.tooManyWidgets"));
    Set<String> seen = new HashSet<>();
    List<Screens.Widget> widgets = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> w)
          || !id(w.get("id"))
          || !seen.add((String) w.get("id"))
          || !Screens.KINDS.contains(w.get("kind"))
          || !path(w.get("path"))
          || !slot(w.get("slot"), 4096)
          || !text(w.get("type"), 160)
          || !percent(w.get("x"))
          || !percent(w.get("y"))
          || !(w.get("decimals") instanceof Number d)
          || d.doubleValue() != Math.rint(d.doubleValue())
          || d.doubleValue() < 0
          || d.doubleValue() > 6) throw new IllegalArgumentException(Tr.of("screens.badWidget"));
      Screens.Widget widget = new Screens.Widget();
      widget.id = (String) w.get("id");
      widget.kind = (String) w.get("kind");
      widget.path = (String) w.get("path");
      widget.slot = (String) w.get("slot");
      widget.type = (String) w.get("type");
      widget.label = cut(truthy(w.get("label")) ? w.get("label") : widget.slot, 128);
      widget.unit = cut(truthy(w.get("unit")) ? w.get("unit") : "", 32);
      widget.x = ((Number) w.get("x")).doubleValue();
      widget.y = ((Number) w.get("y")).doubleValue();
      widget.decimals = d.intValue();
      options(w, widget);
      widgets.add(widget);
    }
    return widgets;
  }

  private static void options(Map<?, ?> w, Screens.Widget out) {
    if (w.containsKey("appearance")) {
      if (!APPEARANCES.contains(w.get("appearance")))
        throw new IllegalArgumentException(Tr.of("screens.badOption", "appearance"));
      out.options.put("appearance", w.get("appearance"));
    }
    for (Object[] range : RANGES) {
      String key = (String) range[0];
      if (!w.containsKey(key)) continue;
      if (!(w.get(key) instanceof Number n)
          || !Double.isFinite(n.doubleValue())
          || n.doubleValue() < (double) range[1]
          || n.doubleValue() > (double) range[2])
        throw new IllegalArgumentException(Tr.of("screens.badOption", key));
      out.options.put(key, n.doubleValue());
    }
    if (out.number("max", 100) <= out.number("min", 0))
      throw new IllegalArgumentException(Tr.of("screens.minMax"));
    for (String key : FLAGS) {
      if (!w.containsKey(key)) continue;
      if (!(w.get(key) instanceof Boolean))
        throw new IllegalArgumentException(Tr.of("screens.badOption", key));
      out.options.put(key, w.get(key));
    }
    if (!w.containsKey("series")) return;
    if (!(w.get("series") instanceof List<?> list) || list.size() > 8)
      throw new IllegalArgumentException(Tr.of("screens.badSeries"));
    out.series = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> s)
          || !path(s.get("path"))
          || !slot(s.get("slot"), 128)
          || !text(s.get("type"), 160)
          || s.containsKey("color")
              && !(s.get("color") instanceof String c && COLOR.matcher(c).matches()))
        throw new IllegalArgumentException(Tr.of("screens.badSeries"));
      Screens.Series series = new Screens.Series();
      series.path = (String) s.get("path");
      series.slot = (String) s.get("slot");
      series.type = (String) s.get("type");
      series.label = cut(truthy(s.get("label")) ? s.get("label") : series.slot, 128);
      series.unit = cut(truthy(s.get("unit")) ? s.get("unit") : "", 32);
      series.color = truthy(s.get("color")) ? (String) s.get("color") : null;
      out.series.add(series);
    }
  }

  private static List<Screens.Asset> library(Object raw, int limit) {
    if (!(raw instanceof List<?> list) || list.size() > limit)
      throw new IllegalArgumentException(Tr.of("screens.badAsset"));
    Set<String> ids = new HashSet<>();
    List<Screens.Asset> assets = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> a)
          || !id(a.get("id"))
          || !ids.add((String) a.get("id"))
          || !text(a.get("title"), 128)
          || !size(a.get("width"))
          || !size(a.get("height"))
          || !(a.get("states") instanceof Map<?, ?> states)
          || !(states.get("off") instanceof String))
        throw new IllegalArgumentException(Tr.of("screens.badAsset"));
      Screens.Asset asset = new Screens.Asset();
      asset.id = (String) a.get("id");
      asset.title = (String) a.get("title");
      asset.width = ((Number) a.get("width")).doubleValue();
      asset.height = ((Number) a.get("height")).doubleValue();
      for (Map.Entry<?, ?> state : states.entrySet()) {
        if (!Screens.STATES.contains(state.getKey())
            || !text(state.getValue(), LIMIT)
            || ((String) state.getValue()).isEmpty())
          throw new IllegalArgumentException(Tr.of("screens.badAsset"));
        asset.states.put((String) state.getKey(), (String) state.getValue());
      }
      assets.add(asset);
    }
    return assets;
  }

  private static List<Screens.Symbol> objects(Object raw, List<Screens.Asset> library, int limit) {
    if (!(raw instanceof List<?> list) || list.size() > limit)
      throw new IllegalArgumentException(Tr.of("screens.badObject"));
    Set<String> ids = new HashSet<>(), assets = new HashSet<>();
    for (Screens.Asset a : library) assets.add(a.id);
    List<Screens.Symbol> objects = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> o)
          || !id(o.get("id"))
          || !ids.add((String) o.get("id"))
          || !assets.contains(o.get("assetId"))
          || !text(o.get("label"), 128)
          || !percent(o.get("x"))
          || !percent(o.get("y"))
          || !(o.get("rotation") instanceof Number r)
          || !List.of(0.0, 90.0, 180.0, 270.0).contains(r.doubleValue())
          || !List.of("static", "bool", "position").contains(o.get("mode")))
        throw new IllegalArgumentException(Tr.of("screens.badObject"));
      Screens.Symbol object = new Screens.Symbol();
      object.id = (String) o.get("id");
      object.assetId = (String) o.get("assetId");
      object.label = (String) o.get("label");
      object.x = ((Number) o.get("x")).doubleValue();
      object.y = ((Number) o.get("y")).doubleValue();
      object.rotation = r.intValue();
      object.mode = (String) o.get("mode");
      if (truthy(o.get("binding"))) object.binding = binding(o.get("binding"));
      if (truthy(o.get("fault"))) object.fault = binding(o.get("fault"));
      if (!object.mode.equals("static") && object.binding == null)
        throw new IllegalArgumentException(Tr.of("screens.badBinding"));
      if (object.binding != null) {
        String t = object.binding.type().replace("sys::", "");
        boolean fits =
            object.mode.equals("position")
                ? List.of("float", "double", "int", "short", "byte").contains(t)
                : t.equals("bool");
        if (!fits) throw new IllegalArgumentException(Tr.of("screens.badBinding"));
      }
      if (object.fault != null && !object.fault.type().replace("sys::", "").equals("bool"))
        throw new IllegalArgumentException(Tr.of("screens.badBinding"));
      objects.add(object);
    }
    return objects;
  }

  private static Screens.Binding binding(Object raw) {
    if (!(raw instanceof Map<?, ?> b)
        || !path(b.get("path"))
        || !slot(b.get("slot"), 128)
        || !text(b.get("type"), 160)
        || ((String) b.get("type")).isEmpty())
      throw new IllegalArgumentException(Tr.of("screens.badBinding"));
    return new Screens.Binding(
        (String) b.get("path"), (String) b.get("slot"), (String) b.get("type"));
  }

  /** The package as plain maps and lists, in the key order the web editor writes. */
  private static Map<String, Object> plain(Screens s) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("format", FORMAT);
    doc.put("version", s.version);
    doc.put("id", s.id);
    doc.put("title", s.title);
    List<Object> views = new ArrayList<>();
    for (Screens.View v : s.views) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", v.id);
      view.put("title", v.title);
      view.put("folder", v.folder);
      view.put("svg", v.svg);
      if (v.widgets != null) view.put("widgets", plainWidgets(v.widgets));
      if (v.objects != null) view.put("objects", plainObjects(v.objects));
      if (v.parts != null) {
        List<Object> parts = new ArrayList<>();
        for (Screens.Part p : v.parts) {
          Map<String, Object> part = new LinkedHashMap<>();
          part.put("id", p.id);
          part.put("componentId", p.componentId);
          part.put("label", p.label);
          part.put("x", number(p.x));
          part.put("y", number(p.y));
          Map<String, Object> bind = new LinkedHashMap<>();
          p.bind.forEach((point, binding) -> bind.put(point, plain(binding)));
          part.put("bind", bind);
          parts.add(part);
        }
        view.put("parts", parts);
      }
      views.add(view);
    }
    doc.put("views", views);
    if (s.library != null) {
      List<Object> library = new ArrayList<>();
      for (Screens.Asset a : s.library) {
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("id", a.id);
        asset.put("title", a.title);
        asset.put("width", number(a.width));
        asset.put("height", number(a.height));
        asset.put("states", new LinkedHashMap<>(a.states));
        library.add(asset);
      }
      doc.put("library", library);
    }
    if (s.components != null) {
      List<Object> components = new ArrayList<>();
      for (Screens.Component c : s.components) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("id", c.id);
        def.put("title", c.title);
        def.put("width", number(c.width));
        def.put("height", number(c.height));
        List<Object> points = new ArrayList<>();
        for (Screens.Point p : c.points) {
          Map<String, Object> point = new LinkedHashMap<>();
          point.put("id", p.id);
          point.put("label", p.label);
          point.put("kind", p.kind);
          point.put("slots", new ArrayList<Object>(p.slots));
          points.add(point);
        }
        def.put("points", points);
        if (c.objects != null) def.put("objects", plainObjects(c.objects));
        if (c.widgets != null) def.put("widgets", plainWidgets(c.widgets));
        components.add(def);
      }
      doc.put("components", components);
    }
    return doc;
  }

  private static List<Object> plainWidgets(List<Screens.Widget> list) {
    List<Object> widgets = new ArrayList<>();
    for (Screens.Widget w : list) {
      Map<String, Object> widget = new LinkedHashMap<>();
      widget.put("id", w.id);
      widget.put("kind", w.kind);
      widget.put("label", w.label);
      widget.put("path", w.path);
      widget.put("slot", w.slot);
      widget.put("type", w.type);
      widget.put("x", number(w.x));
      widget.put("y", number(w.y));
      widget.put("unit", w.unit);
      widget.put("decimals", w.decimals);
      w.options.forEach(
          (k, x) -> widget.put(k, x instanceof Number n ? number(n.doubleValue()) : x));
      if (w.series != null) {
        List<Object> series = new ArrayList<>();
        for (Screens.Series c : w.series) {
          Map<String, Object> channel = new LinkedHashMap<>();
          channel.put("path", c.path);
          channel.put("slot", c.slot);
          channel.put("type", c.type);
          channel.put("label", c.label);
          channel.put("unit", c.unit);
          if (c.color != null) channel.put("color", c.color);
          series.add(channel);
        }
        widget.put("series", series);
      }
      widgets.add(widget);
    }
    return widgets;
  }

  private static List<Object> plainObjects(List<Screens.Symbol> list) {
    List<Object> objects = new ArrayList<>();
    for (Screens.Symbol o : list) {
      Map<String, Object> object = new LinkedHashMap<>();
      object.put("id", o.id);
      object.put("assetId", o.assetId);
      object.put("label", o.label);
      object.put("x", number(o.x));
      object.put("y", number(o.y));
      object.put("rotation", o.rotation);
      object.put("mode", o.mode);
      if (o.binding != null) object.put("binding", plain(o.binding));
      if (o.fault != null) object.put("fault", plain(o.fault));
      objects.add(object);
    }
    return objects;
  }

  private static Map<String, Object> plain(Screens.Binding b) {
    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("path", b.path());
    binding.put("slot", b.slot());
    binding.put("type", b.type());
    return binding;
  }

  /** Whole numbers are written without a fraction, as the web editor writes them. */
  private static Object number(double v) {
    return v == Math.rint(v) && Math.abs(v) < 1e15 ? (Object) (long) v : (Object) v;
  }

  private static boolean id(Object v) {
    return v instanceof String s && ID.matcher(s).matches();
  }

  private static boolean text(Object v, int max) {
    return v instanceof String s && s.length() <= max;
  }

  private static boolean truthy(Object v) {
    return v != null && !"".equals(v) && !Boolean.FALSE.equals(v);
  }

  private static String cut(Object v, int max) {
    String s = v instanceof Number n ? String.valueOf(number(n.doubleValue())) : String.valueOf(v);
    return s.length() > max ? s.substring(0, max) : s;
  }

  private static boolean path(Object v) {
    if (!(v instanceof String s) || s.length() > 2048) return false;
    for (String part : s.split("/", -1)) if (part.isEmpty() || part.equals("..")) return false;
    return true;
  }

  private static boolean slot(Object v, int max) {
    return v instanceof String s
        && !s.isEmpty()
        && s.length() <= max
        && !s.equals("meta")
        && !SECRET.matcher(s).find();
  }

  private static boolean percent(Object v) {
    return v instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 100;
  }

  private static boolean size(Object v) {
    return v instanceof Number n && n.doubleValue() > 0 && n.doubleValue() <= 2000;
  }
}
