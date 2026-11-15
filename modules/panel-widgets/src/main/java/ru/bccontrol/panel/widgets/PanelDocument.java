package ru.bccontrol.panel.widgets;

import java.io.*;
import java.util.*;
import ru.bccontrol.panel.api.PanelData.Ref;
import ru.bccontrol.panel.api.PanelText;

/** Owned by panel module; host treats this versioned document as opaque UTF-8 text. */
public final class PanelDocument {
  public record Widget(String kind, String title, Ref ref) {}

  public static List<Widget> read(String text) throws IOException {
    if (text.length() > 262144)
      throw new IOException(
          PanelText.of("panelmodule.demo.documentTooLarge", "The document is larger than 256 KiB"));
    if (text.isBlank()) return List.of();
    Properties p = new Properties();
    p.load(new StringReader(text));
    if (!"1".equals(p.getProperty("version")))
      throw new IOException(PanelText.of("panelmodule.unknownVersion", "Unknown panel version"));
    int count;
    try {
      count = Integer.parseInt(p.getProperty("count"));
    } catch (RuntimeException ex) {
      throw new IOException(PanelText.of("panelmodule.widgetCount", "Widget count"), ex);
    }
    if (count < 0 || count > 32)
      throw new IOException(PanelText.of("panelmodule.limit32", "Limit of 32 widgets"));
    List<Widget> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String key = "widget." + i + ".";
      String kind = p.getProperty(key + "kind"),
          path = p.getProperty(key + "path"),
          field = p.getProperty(key + "field"),
          title = p.getProperty(key + "title", "");
      if (!Set.of("value", "indicator").contains(Objects.toString(kind, ""))
          || path == null
          || field == null
          || path.isBlank()
          || field.isBlank()
          || path.length() > 4096
          || field.length() > 256
          || title.length() > 256)
        throw new IOException(PanelText.of("panelmodule.badWidget", "Invalid widget {0}", i));
      out.add(new Widget(kind, title, new Ref(path, field)));
    }
    return List.copyOf(out);
  }

  public static String write(List<Widget> widgets) throws IOException {
    Properties p = new Properties();
    p.setProperty("version", "1");
    p.setProperty("count", Integer.toString(widgets.size()));
    for (int i = 0; i < widgets.size(); i++) {
      Widget w = widgets.get(i);
      String k = "widget." + i + ".";
      p.setProperty(k + "kind", w.kind());
      p.setProperty(k + "title", w.title());
      p.setProperty(k + "path", w.ref().componentPath());
      p.setProperty(k + "field", w.ref().field());
    }
    StringWriter writer = new StringWriter();
    p.store(writer, "Sedona Panel");
    String text = writer.toString();
    read(text);
    return text;
  }
}
