package sedona.sox;

/**
 * Forgets the stock client's cached components. An action may rebuild part of the tree on the
 * device - delete a component and create another type under the freed id - which the cache cannot
 * follow ("Component has changed type!"); after it every component is loaded afresh. Same
 * split-package technique as {@link SchemaParts}.
 */
public final class ClientCache {
  private ClientCache() {}

  public static void forget(SoxClient client) {
    synchronized (client) {
      if (client.cache == null) return;
      java.util.Arrays.fill(client.cache, null);
    }
  }
}
