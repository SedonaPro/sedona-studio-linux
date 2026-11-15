package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import javafx.animation.*;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.*;
import javafx.scene.shape.*;
import javafx.scene.text.*;
import javafx.scene.transform.*;
import javafx.util.Duration;
import ru.bccontrol.sedonastudio.storage.SvgSymbol;

/**
 * Draws a widget symbol (the SVG subset of {@link SvgSymbol}) as JavaFX shapes fitted into a box.
 * No browser and no SVG engine: only the elements and attributes of the subset exist here.
 */
final class SvgFx {
  /** The shapes of one symbol and its running animations (stop them when the shapes go away). */
  record Drawn(Node node, List<Animation> animations) {
    void stop() {
      for (Animation a : animations) a.stop();
    }
  }

  private record Style(
      Paint fill,
      Paint stroke,
      double strokeWidth,
      double fillOpacity,
      double strokeOpacity,
      StrokeLineCap cap,
      StrokeLineJoin join,
      List<Double> dashes,
      String font,
      double fontSize,
      FontWeight weight,
      String anchor) {}

  private static final Style ROOT =
      new Style(
          Color.BLACK,
          null,
          1,
          1,
          1,
          StrokeLineCap.BUTT,
          StrokeLineJoin.MITER,
          List.of(),
          "System",
          12,
          FontWeight.NORMAL,
          "start");

  private SvgFx() {}

  /** The symbol scaled into a w x h box at the origin. */
  static Drawn draw(SvgSymbol symbol, double w, double h) {
    List<Animation> animations = new ArrayList<>();
    Group content = new Group();
    for (SvgSymbol.Node child : symbol.root.children())
      add(content, child, style(ROOT, symbol.root.attrs(), symbol), animations, symbol, 0);
    double[] vb = symbol.viewBox;
    double sx = w / vb[2], sy = h / vb[3], tx = 0, ty = 0;
    if (!symbol.stretch) {
      double s = Math.min(sx, sy);
      tx = (w - vb[2] * s) / 2;
      ty = (h - vb[3] * s) / 2;
      sx = sy = s;
    }
    content
        .getTransforms()
        .addAll(new Translate(tx, ty), new Scale(sx, sy), new Translate(-vb[0], -vb[1]));
    Group box = new Group(content);
    // Clip to the box like an <img>: a symbol never paints outside its place.
    box.setClip(new Rectangle(0, 0, w, h));
    return new Drawn(box, animations);
  }

  private static void add(
      Group parent,
      SvgSymbol.Node n,
      Style inherited,
      List<Animation> anims,
      SvgSymbol symbol,
      int depth) {
    Map<String, String> a = n.attrs();
    if ("none".equals(a.get("display")) || "hidden".equals(a.get("visibility"))) return;
    Style s = style(inherited, a, symbol);
    Node out;
    switch (n.tag()) {
      case "g" -> {
        Group g = new Group();
        for (SvgSymbol.Node c : n.children()) add(g, c, s, anims, symbol, depth);
        out = g;
      }
      case "svg" -> {
        // A picture inside the picture: its own box at x, y and its own viewBox scaled into it.
        Group g = new Group();
        for (SvgSymbol.Node c : n.children()) add(g, c, s, anims, symbol, depth);
        double[] box = numbers(a.getOrDefault("viewBox", ""));
        double w = num(a, "width", box.length == 4 ? box[2] : 0),
            h = num(a, "height", box.length == 4 ? box[3] : 0);
        g.getTransforms().add(new Translate(num(a, "x"), num(a, "y")));
        if (box.length == 4 && box[2] > 0 && box[3] > 0 && w > 0 && h > 0) {
          double scale = Math.min(w / box[2], h / box[3]);
          g.getTransforms()
              .addAll(
                  new Translate((w - box[2] * scale) / 2, (h - box[3] * scale) / 2),
                  new Scale(scale, scale),
                  new Translate(-box[0], -box[1]));
          g.setClip(new Rectangle(box[0], box[1], box[2], box[3]));
        }
        out = g;
      }
      case "use" -> {
        // A copy of another element, moved by x and y; reference loops are cut by the depth.
        String ref = a.getOrDefault("href", "");
        SvgSymbol.Node target = ref.startsWith("#") ? symbol.ids.get(ref.substring(1)) : null;
        if (target == null || depth > 8) return;
        Group g = new Group();
        g.getTransforms().add(new Translate(num(a, "x"), num(a, "y")));
        if (target.tag().equals("symbol"))
          for (SvgSymbol.Node c : target.children()) add(g, c, s, anims, symbol, depth + 1);
        else add(g, target, s, anims, symbol, depth + 1);
        out = g;
      }
      case "path" -> {
        SVGPath p = new SVGPath();
        p.setContent(a.getOrDefault("d", ""));
        if ("evenodd".equals(a.get("fill-rule"))) p.setFillRule(FillRule.EVEN_ODD);
        out = paint(p, s);
      }
      case "rect" -> {
        Rectangle r = new Rectangle(num(a, "x"), num(a, "y"), num(a, "width"), num(a, "height"));
        double rx = num(a, "rx", num(a, "ry", 0)), ry = num(a, "ry", rx);
        r.setArcWidth(2 * rx);
        r.setArcHeight(2 * ry);
        out = paint(r, s);
      }
      case "circle" -> out = paint(new Circle(num(a, "cx"), num(a, "cy"), num(a, "r")), s);
      case "ellipse" -> out =
          paint(new Ellipse(num(a, "cx"), num(a, "cy"), num(a, "rx"), num(a, "ry")), s);
      case "line" -> out =
          paint(new Line(num(a, "x1"), num(a, "y1"), num(a, "x2"), num(a, "y2")), s);
      case "polyline" -> {
        Polyline p = new Polyline();
        p.getPoints().addAll(points(a.get("points")));
        out = paint(p, s);
      }
      case "polygon" -> {
        Polygon p = new Polygon();
        p.getPoints().addAll(points(a.get("points")));
        out = paint(p, s);
      }
      case "text" -> {
        StringBuilder content = new StringBuilder(n.text());
        for (SvgSymbol.Node span : n.children())
          if (span.tag().equals("tspan"))
            content.append(content.length() > 0 ? " " : "").append(span.text());
        Text t = new Text(num(a, "x"), num(a, "y"), content.toString());
        t.setFont(Font.font(s.font, s.weight, s.fontSize));
        t.setTextOrigin(VPos.BASELINE);
        paint(t, s);
        double width = t.getLayoutBounds().getWidth();
        if (s.anchor.equals("middle")) t.setX(t.getX() - width / 2);
        else if (s.anchor.equals("end")) t.setX(t.getX() - width);
        out = t;
      }
      case "animateTransform" -> {
        rotate(parent, a, anims);
        return;
      }
      default -> {
        return; // title, desc, defs, gradients, style: nothing to draw by themselves
      }
    }
    if (a.containsKey("opacity")) out.setOpacity(clamp(num(a, "opacity", 1)));
    if (a.containsKey("transform")) out.getTransforms().addAll(transforms(a.get("transform")));
    parent.getChildren().add(out);
  }

  /** animateTransform type="rotate": spin the parent group from one angle to another. */
  private static void rotate(Group parent, Map<String, String> a, List<Animation> anims) {
    double[] from = numbers(a.getOrDefault("from", "0")), to = numbers(a.getOrDefault("to", "360"));
    Rotate r =
        new Rotate(
            from.length > 0 ? from[0] : 0,
            from.length > 2 ? from[1] : 0,
            from.length > 2 ? from[2] : 0);
    parent.getTransforms().add(r);
    double seconds = seconds(a.getOrDefault("dur", "1s"));
    if (seconds <= 0) return;
    Timeline t =
        new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(r.angleProperty(), r.getAngle())),
            new KeyFrame(
                Duration.seconds(seconds),
                new KeyValue(r.angleProperty(), to.length > 0 ? to[0] : 360, Interpolator.LINEAR)));
    t.setCycleCount("indefinite".equals(a.get("repeatCount")) ? Animation.INDEFINITE : 1);
    t.play();
    anims.add(t);
  }

  private static <T extends Shape> T paint(T shape, Style s) {
    shape.setFill(s.fill instanceof Color c ? alpha(c, s.fillOpacity) : s.fill);
    shape.setStroke(s.stroke instanceof Color c ? alpha(c, s.strokeOpacity) : s.stroke);
    shape.setStrokeWidth(s.strokeWidth);
    shape.setStrokeLineCap(s.cap);
    shape.setStrokeLineJoin(s.join);
    shape.getStrokeDashArray().setAll(s.dashes);
    return shape;
  }

  private static Style style(Style p, Map<String, String> a, SvgSymbol symbol) {
    return new Style(
        a.containsKey("fill") ? paintOf(a.get("fill"), symbol) : p.fill,
        a.containsKey("stroke") ? paintOf(a.get("stroke"), symbol) : p.stroke,
        num(a, "stroke-width", p.strokeWidth),
        clamp(num(a, "fill-opacity", p.fillOpacity)),
        clamp(num(a, "stroke-opacity", p.strokeOpacity)),
        switch (a.getOrDefault("stroke-linecap", "")) {
          case "round" -> StrokeLineCap.ROUND;
          case "square" -> StrokeLineCap.SQUARE;
          case "butt" -> StrokeLineCap.BUTT;
          default -> p.cap;
        },
        switch (a.getOrDefault("stroke-linejoin", "")) {
          case "round" -> StrokeLineJoin.ROUND;
          case "bevel" -> StrokeLineJoin.BEVEL;
          case "miter" -> StrokeLineJoin.MITER;
          default -> p.join;
        },
        a.containsKey("stroke-dasharray") ? dashes(a.get("stroke-dasharray")) : p.dashes,
        a.getOrDefault("font-family", p.font).split(",")[0].replace("'", "").trim(),
        num(a, "font-size", p.fontSize),
        a.containsKey("font-weight")
            ? (a.get("font-weight").equals("bold") || num(a, "font-weight", 400) >= 600
                ? FontWeight.BOLD
                : FontWeight.NORMAL)
            : p.weight,
        a.getOrDefault("text-anchor", p.anchor));
  }

  /** A colour, or a gradient named as url(#id). */
  private static Paint paintOf(String v, SvgSymbol symbol) {
    String t = v.trim();
    if (!t.startsWith("url(")) return color(t);
    String id =
        t.substring(4, Math.max(4, t.indexOf(')')))
            .replace("#", "")
            .replace("'", "")
            .replace("\"", "")
            .trim();
    Paint gradient = gradient(symbol.ids.get(id), symbol);
    return gradient == null ? Color.GRAY : gradient;
  }

  private static Paint gradient(SvgSymbol.Node g, SvgSymbol symbol) {
    if (g == null || !g.tag().endsWith("Gradient")) return null;
    Map<String, String> a = g.attrs();
    // Stops (and missing settings) may be taken from another gradient through href.
    String ref = a.getOrDefault("href", "");
    SvgSymbol.Node base = ref.startsWith("#") ? symbol.ids.get(ref.substring(1)) : null;
    List<SvgSymbol.Node> stopNodes = g.children();
    if (stopNodes.stream().noneMatch(c -> c.tag().equals("stop")) && base != null && base != g)
      stopNodes = base.children();
    List<Stop> stops = new ArrayList<>();
    for (SvgSymbol.Node c : stopNodes) {
      if (!c.tag().equals("stop")) continue;
      Map<String, String> sa = c.attrs();
      String offset = sa.getOrDefault("offset", "0").trim();
      double at =
          offset.endsWith("%")
              ? num(Map.of("v", offset.substring(0, offset.length() - 1)), "v") / 100
              : num(sa, "offset");
      Color colour = color(sa.getOrDefault("stop-color", "black"));
      if (colour == null) colour = Color.TRANSPARENT;
      stops.add(new Stop(clamp(at), alpha(colour, clamp(num(sa, "stop-opacity", 1)))));
    }
    if (stops.isEmpty()) return null;
    boolean user = "userSpaceOnUse".equals(a.get("gradientUnits"));
    List<Transform> moved =
        a.containsKey("gradientTransform") ? transforms(a.get("gradientTransform")) : List.of();
    if (g.tag().equals("linearGradient")) {
      javafx.geometry.Point2D
          from = new javafx.geometry.Point2D(unit(a, "x1", 0), unit(a, "y1", 0)),
          to = new javafx.geometry.Point2D(unit(a, "x2", 1), unit(a, "y2", 0));
      for (int i = moved.size() - 1; i >= 0; i--) {
        from = moved.get(i).transform(from);
        to = moved.get(i).transform(to);
      }
      return new LinearGradient(
          from.getX(), from.getY(), to.getX(), to.getY(), !user, CycleMethod.NO_CYCLE, stops);
    }
    javafx.geometry.Point2D centre =
        new javafx.geometry.Point2D(unit(a, "cx", 0.5), unit(a, "cy", 0.5));
    double radius = unit(a, "r", 0.5);
    for (int i = moved.size() - 1; i >= 0; i--) {
      javafx.geometry.Point2D edge = moved.get(i).transform(centre.add(radius, 0));
      centre = moved.get(i).transform(centre);
      radius = centre.distance(edge);
    }
    return new RadialGradient(
        0, 0, centre.getX(), centre.getY(), radius, !user, CycleMethod.NO_CYCLE, stops);
  }

  /** A gradient coordinate: a number, or a percentage of the box. */
  private static double unit(Map<String, String> a, String key, double fallback) {
    String v = a.get(key);
    if (v == null) return fallback;
    v = v.trim();
    return v.endsWith("%")
        ? num(Map.of("v", v.substring(0, v.length() - 1)), "v") / 100
        : num(a, key, fallback);
  }

  private static Color color(String v) {
    if (v.equals("none")) return null;
    try {
      return Color.web(v);
    } catch (IllegalArgumentException e) {
      return Color.BLACK;
    }
  }

  private static Color alpha(Color c, double opacity) {
    return opacity >= 1
        ? c
        : Color.color(c.getRed(), c.getGreen(), c.getBlue(), c.getOpacity() * opacity);
  }

  /** translate/rotate/scale/matrix, applied in the order written (as in SVG). */
  static List<Transform> transforms(String v) {
    List<Transform> out = new ArrayList<>();
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(translate|rotate|scale|matrix)\\(([^)]*)\\)").matcher(v);
    while (m.find()) {
      double[] n = numbers(m.group(2));
      switch (m.group(1)) {
        case "translate" -> out.add(new Translate(n[0], n.length > 1 ? n[1] : 0));
        case "rotate" -> out.add(n.length > 2 ? new Rotate(n[0], n[1], n[2]) : new Rotate(n[0]));
        case "scale" -> out.add(new Scale(n[0], n.length > 1 ? n[1] : n[0]));
        default -> {
          if (n.length == 6) out.add(new Affine(n[0], n[2], n[4], n[1], n[3], n[5]));
        }
      }
    }
    return out;
  }

  private static List<Double> points(String v) {
    List<Double> out = new ArrayList<>();
    for (double d : numbers(v == null ? "" : v)) out.add(d);
    if (out.size() % 2 == 1) out.remove(out.size() - 1);
    return out;
  }

  private static List<Double> dashes(String v) {
    List<Double> out = new ArrayList<>();
    if (!v.trim().equals("none")) for (double d : numbers(v)) out.add(d);
    return out;
  }

  private static double[] numbers(String v) {
    String t = v.trim();
    if (t.isEmpty()) return new double[0];
    String[] parts = t.split("[\\s,]+");
    double[] out = new double[parts.length];
    for (int i = 0; i < parts.length; i++)
      try {
        out[i] = Double.parseDouble(parts[i]);
      } catch (NumberFormatException e) {
        out[i] = 0;
      }
    return out;
  }

  private static double seconds(String v) {
    try {
      if (v.endsWith("ms")) return Double.parseDouble(v.substring(0, v.length() - 2)) / 1000;
      if (v.endsWith("s")) return Double.parseDouble(v.substring(0, v.length() - 1));
      return Double.parseDouble(v);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static double num(Map<String, String> a, String key) {
    return num(a, key, 0);
  }

  private static double num(Map<String, String> a, String key, double fallback) {
    String v = a.get(key);
    if (v == null) return fallback;
    try {
      return Double.parseDouble(v.trim().replace("px", ""));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double clamp(double v) {
    return Math.max(0, Math.min(1, v));
  }
}
