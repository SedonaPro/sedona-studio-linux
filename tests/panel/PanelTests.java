import static ru.bccontrol.panel.api.PanelData.*;

import java.util.*;
import ru.bccontrol.panel.api.*;
import ru.bccontrol.panel.demo.*;
import ru.bccontrol.panel.widgets.*;

public class PanelTests {
  static void check(boolean b, String text) {
    if (!b) throw new AssertionError(text);
  }

  public static void main(String[] args) throws Exception {
    var widgets =
        List.of(
            new PanelDocument.Widget("value", "Temperature", new Ref("/plant/pump", "value")),
            new PanelDocument.Widget("indicator", "Enabled", new Ref("/plant/pump", "enabled")));
    check(PanelDocument.read(PanelDocument.write(widgets)).equals(widgets), "Document roundtrip");
    check(PanelDocument.read("").isEmpty(), "Empty document");
    boolean rejected = false;
    try {
      PanelDocument.read("version=99\ncount=0");
    } catch (Exception ex) {
      rejected = true;
    }
    check(rejected, "Unknown version rejected");
    rejected = false;
    try {
      PanelDocument.read("x".repeat(262145));
    } catch (Exception ex) {
      rejected = true;
    }
    check(rejected, "Document bound");
    DemoHost host = new DemoHost();
    int[] notifications = {0};
    var sub = host.subscribe(s -> notifications[0]++);
    host.tick();
    check(notifications[0] == 2, "Mock subscription");
    sub.close();
    host.tick();
    check(notifications[0] == 2, "Unsubscribe");
    check(
        host.write(new Ref("/demo", "enabled"), "false").toCompletableFuture().get().outcome()
            == Outcome.CONFIRMED,
        "Mock write");
    check(
        host.write(new Ref("/demo", "enabled"), "wrong").toCompletableFuture().get().outcome()
            == Outcome.REJECTED,
        "Mock validation");
    check(
        host.invoke(new Ref("/demo", "reset"), "").toCompletableFuture().get().outcome()
            == Outcome.CONFIRMED,
        "Mock action");
    check(
        host.snapshot().values().get(new Ref("/demo", "value")).value().equals("0.00"),
        "Mock reset readback");
    boolean immutable = false;
    try {
      host.snapshot().components().clear();
    } catch (UnsupportedOperationException ex) {
      immutable = true;
    }
    check(immutable, "Immutable API");
    System.out.println(
        "PASS independent panel contracts: document versions/bounds/roundtrip, immutable DTOs, mock"
            + " writes/actions, subscriptions and unsubscribe");
  }
}
