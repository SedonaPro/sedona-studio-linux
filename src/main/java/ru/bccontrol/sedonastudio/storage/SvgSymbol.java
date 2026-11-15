package ru.bccontrol.sedonastudio.storage;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * A widget symbol: the SVG subset of docs/widgets.md (SedonaPro) as a plain tree, ready to be
 * turned into shapes. Anything outside the subset - other elements, attributes, styles, paints,
 * DOCTYPE - makes the whole symbol invalid; nothing is fetched or executed.
 */
public final class SvgSymbol {
  public static final int MAX_BYTES = 64 * 1024;

  /** One element: its tag, its attributes from the subset, its children and its text. */
  public record Node(String tag, Map<String, String> attrs, List<Node> children, String text) {}

  public final double[] viewBox;
  /** preserveAspectRatio="none": stretch to the box instead of fitting it. */
  public final boolean stretch;

  public final Node root;

  /**
   * True for a free drawing (see {@link #parseDrawing}) that has parts this editor does not draw:
   * masks, patterns, embedded images and the like. The rest is still shown.
   */
  public final boolean partial;

  /** Elements by their id, for gradients and "use" references. */
  public final Map<String, Node> ids;

  static final Set<String> COMMON =
      Set.of(
          "id",
          "fill",
          "stroke",
          "stroke-width",
          "opacity",
          "fill-opacity",
          "stroke-opacity",
          "stroke-linecap",
          "stroke-linejoin",
          "stroke-dasharray",
          "transform");
  static final Map<String, Set<String>> ELEMENTS =
      Map.ofEntries(
          Map.entry(
              "svg",
              Set.of("xmlns", "viewBox", "width", "height", "version", "preserveAspectRatio")),
          Map.entry("g", Set.of()),
          Map.entry("title", Set.of()),
          Map.entry("desc", Set.of()),
          Map.entry("path", Set.of("d")),
          Map.entry("rect", Set.of("x", "y", "width", "height", "rx", "ry")),
          Map.entry("circle", Set.of("cx", "cy", "r")),
          Map.entry("ellipse", Set.of("cx", "cy", "rx", "ry")),
          Map.entry("line", Set.of("x1", "y1", "x2", "y2")),
          Map.entry("polyline", Set.of("points")),
          Map.entry("polygon", Set.of("points")),
          Map.entry(
              "text", Set.of("x", "y", "font-size", "font-family", "font-weight", "text-anchor")),
          Map.entry(
              "animateTransform",
              Set.of("attributeName", "type", "from", "to", "dur", "repeatCount")));
  private static final Pattern PAINT =
      Pattern.compile(
          "none|#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}|black|white|red|green|blue|yellow|orange|gray|grey");
  private static final Pattern TRANSFORM =
      Pattern.compile(
          "(\\s*(translate|rotate|scale|matrix)\\(\\s*-?[\\d.]+([\\s,]+-?[\\d.]+)*\\s*\\)\\s*)+");

  private SvgSymbol(double[] viewBox, boolean stretch, Node root) {
    this(viewBox, stretch, root, false, Map.of());
  }

  private SvgSymbol(
      double[] viewBox, boolean stretch, Node root, boolean partial, Map<String, Node> ids) {
    this.viewBox = viewBox;
    this.stretch = stretch;
    this.root = root;
    this.partial = partial;
    this.ids = ids;
  }

  /** Elements of a free drawing that are understood; everything else is left out. */
  private static final Set<String> DRAWING =
      Set.of(
          "svg",
          "g",
          "defs",
          "title",
          "desc",
          "path",
          "rect",
          "circle",
          "ellipse",
          "line",
          "polyline",
          "polygon",
          "text",
          "tspan",
          "use",
          "symbol",
          "style",
          "linearGradient",
          "radialGradient",
          "stop",
          "animateTransform");

  /** Elements that carry no picture of their own; leaving them out loses nothing. */
  private static final Set<String> SILENT = Set.of("metadata", "namedview", "title", "desc");

  private static final Pattern RULE = Pattern.compile("\\.([A-Za-z0-9_-]+)\\s*\\{([^}]*)\\}");

  /**
   * Parses a drawing made in any SVG editor (a screen background, a library picture). Unlike {@link
   * #parse} it does not refuse what it does not know: unknown elements are left out and {@link
   * #partial} says so. Styles written as style="..." or as simple ".class" rules are turned into
   * plain attributes. Nothing is fetched or executed here either.
   */
  public static SvgSymbol parseDrawing(byte[] bytes, int maxBytes) {
    if (bytes.length > maxBytes) throw new IllegalArgumentException("SVG too large");
    Element root = document(bytes);
    double[] viewBox = new double[4];
    String[] box = root.getAttribute("viewBox").trim().split("[\\s,]+");
    try {
      if (box.length == 4) for (int i = 0; i < 4; i++) viewBox[i] = Double.parseDouble(box[i]);
      else {
        viewBox[2] = length(root.getAttribute("width"));
        viewBox[3] = length(root.getAttribute("height"));
      }
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("SVG size is not a number");
    }
    if (!(viewBox[2] > 0 && viewBox[3] > 0)) throw new IllegalArgumentException("SVG has no size");
    Map<String, Map<String, String>> classes = new HashMap<>();
    NodeList styles = root.getElementsByTagNameNS("*", "style");
    for (int i = 0; i < styles.getLength(); i++) {
      java.util.regex.Matcher m = RULE.matcher(styles.item(i).getTextContent());
      while (m.find())
        classes
            .computeIfAbsent(m.group(1), k -> new LinkedHashMap<>())
            .putAll(declarations(m.group(2)));
    }
    boolean[] partial = {false};
    Map<String, Node> ids = new HashMap<>();
    Node tree = drawingNode(root, 0, new int[] {0}, classes, partial, ids);
    return new SvgSymbol(
        viewBox,
        root.getAttribute("preserveAspectRatio").equals("none"),
        tree,
        partial[0],
        Collections.unmodifiableMap(ids));
  }

  private static double length(String v) {
    return Double.parseDouble(v.trim().replaceAll("(px|pt|mm|cm|in)$", ""));
  }

  private static Map<String, String> declarations(String css) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String part : css.split(";")) {
      int colon = part.indexOf(':');
      if (colon > 0) out.put(part.substring(0, colon).trim(), part.substring(colon + 1).trim());
    }
    return out;
  }

  private static Node drawingNode(
      Element e,
      int depth,
      int[] count,
      Map<String, Map<String, String>> classes,
      boolean[] partial,
      Map<String, Node> ids) {
    if (depth > 64 || ++count[0] > 20000) throw new IllegalArgumentException("SVG too deep or big");
    String tag = e.getLocalName();
    Map<String, String> attrs = new LinkedHashMap<>();
    NamedNodeMap list = e.getAttributes();
    for (int i = 0; i < list.getLength(); i++) {
      Attr a = (Attr) list.item(i);
      String name = a.getLocalName() == null ? a.getName() : a.getLocalName();
      if (name.equals("xmlns") || "xmlns".equals(a.getPrefix()) || name.equals("style")) continue;
      attrs.put(name, a.getValue());
    }
    // CSS wins over presentation attributes: first the class rules, then the element's own style.
    for (String name : e.getAttribute("class").trim().split("\\s+"))
      if (classes.containsKey(name)) attrs.putAll(classes.get(name));
    attrs.putAll(declarations(e.getAttribute("style")));
    List<Node> children = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (org.w3c.dom.Node c = e.getFirstChild(); c != null; c = c.getNextSibling())
      if (c instanceof Element child) {
        String name = child.getLocalName();
        if (DRAWING.contains(name) && "http://www.w3.org/2000/svg".equals(child.getNamespaceURI()))
          children.add(drawingNode(child, depth + 1, count, classes, partial, ids));
        else if (!SILENT.contains(name)) partial[0] = true;
      } else if (c.getNodeType() == org.w3c.dom.Node.TEXT_NODE
          || c.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) text.append(c.getNodeValue());
    for (String unsupported : List.of("clip-path", "mask", "filter"))
      if (!attrs.getOrDefault(unsupported, "none").equals("none")) partial[0] = true;
    Node node =
        new Node(
            tag,
            Collections.unmodifiableMap(attrs),
            List.copyOf(children),
            tag.equals("style") ? "" : text.toString().trim());
    if (attrs.containsKey("id")) ids.putIfAbsent(attrs.get("id"), node);
    return node;
  }

  private static Element document(byte[] bytes) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
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
      Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
      if (!"svg".equals(root.getLocalName())) throw new IllegalArgumentException("Root is not svg");
      return root;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Not an SVG: " + e.getMessage());
    }
  }

  /** Parses a symbol; throws IllegalArgumentException when it is outside the subset. */
  public static SvgSymbol parse(byte[] bytes) {
    if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("SVG larger than 64 KB");
    Element root;
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      f.setNamespaceAware(true);
      javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
      // Report through the exception only, not on the console.
      builder.setErrorHandler(
          new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException e)
                throws org.xml.sax.SAXException {
              throw e;
            }
          });
      root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    } catch (Exception e) {
      throw new IllegalArgumentException("Not an SVG: " + e.getMessage());
    }
    if (!root.getLocalName().equals("svg")) throw new IllegalArgumentException("Root is not svg");
    String[] box = root.getAttribute("viewBox").trim().split("[\\s,]+");
    if (box.length != 4) throw new IllegalArgumentException("svg needs a viewBox");
    double[] viewBox = new double[4];
    for (int i = 0; i < 4; i++) viewBox[i] = Double.parseDouble(box[i]);
    if (viewBox[2] <= 0 || viewBox[3] <= 0) throw new IllegalArgumentException("Empty viewBox");
    String aspect = root.getAttribute("preserveAspectRatio");
    if (!Set.of("", "none", "xMidYMid", "xMidYMid meet").contains(aspect))
      throw new IllegalArgumentException("preserveAspectRatio " + aspect);
    int[] count = {0};
    return new SvgSymbol(viewBox, aspect.equals("none"), node(root, 0, count));
  }

  private static Node node(Element e, int depth, int[] count) {
    if (depth > 16 || ++count[0] > 2000) throw new IllegalArgumentException("SVG too deep or big");
    String tag = e.getLocalName();
    Set<String> allowed = ELEMENTS.get(tag);
    if (allowed == null) throw new IllegalArgumentException("<" + tag + "> is not in the subset");
    Map<String, String> attrs = new LinkedHashMap<>();
    NamedNodeMap list = e.getAttributes();
    for (int i = 0; i < list.getLength(); i++) {
      Attr a = (Attr) list.item(i);
      String name = a.getLocalName() == null ? a.getName() : a.getLocalName();
      if (name.equals("xmlns") || "xmlns".equals(a.getPrefix())) continue;
      if (!allowed.contains(name) && !COMMON.contains(name))
        throw new IllegalArgumentException("<" + tag + " " + name + "> is not in the subset");
      String v = a.getValue();
      if ((name.equals("fill") || name.equals("stroke")) && !PAINT.matcher(v).matches())
        throw new IllegalArgumentException("Colour " + v);
      if (name.equals("transform") && !TRANSFORM.matcher(v).matches())
        throw new IllegalArgumentException("Transform " + v);
      attrs.put(name, v);
    }
    if (tag.equals("animateTransform")
        && !("transform".equals(attrs.get("attributeName")) && "rotate".equals(attrs.get("type"))))
      throw new IllegalArgumentException("Only a rotating animateTransform");
    List<Node> children = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (org.w3c.dom.Node c = e.getFirstChild(); c != null; c = c.getNextSibling())
      if (c instanceof Element child) children.add(node(child, depth + 1, count));
      else if (c.getNodeType() == org.w3c.dom.Node.TEXT_NODE
          || c.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) text.append(c.getNodeValue());
    return new Node(
        tag, Collections.unmodifiableMap(attrs), List.copyOf(children), text.toString().trim());
  }
}
