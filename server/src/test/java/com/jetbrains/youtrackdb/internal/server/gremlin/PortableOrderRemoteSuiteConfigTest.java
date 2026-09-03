package com.jetbrains.youtrackdb.internal.server.gremlin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the create-time portable-order opt-out on server suite databases.
 *
 * <p>Embedded suites install the same flag through {@code YTDBGraphInitUtil}. Remote Process and
 * Feature suites open graphs on the server, so the flag must land on the database at
 * {@code create}. Without it, upstream {@code OrderTest} / Cucumber order scenarios keep vertices
 * that lack the ordered key and fail with larger counts.
 */
public class PortableOrderRemoteSuiteConfigTest {

  private YTDBGraphBinaryRemoteGraphProvider provider;

  @Before
  public void startServer() throws Exception {
    provider = new YTDBGraphBinaryRemoteGraphProvider();
    provider.startServer();
  }

  @After
  public void stopServer() {
    if (provider != null) {
      provider.stopServer();
      provider = null;
    }
  }

  /**
   * A freshly created suite database carries {@code orderIncludesMissingKey=false}, matching the
   * embedded InitUtil opt-out.
   */
  @Test
  public void suiteDatabaseCreate_installsPortableOrderOptOut() {
    try (var session = provider.ytdbServer.getYouTrackDB()
        .open("modern",
            YTDBGraphBinaryRemoteGraphProvider.ADMIN_USER_NAME,
            YTDBGraphBinaryRemoteGraphProvider.ADMIN_USER_PASSWORD)) {
      assertThat(session.getConfiguration().getValueAsBoolean(
          GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY))
          .as("remote suite DB must opt out of productive missing-key order")
          .isFalse();
    }
  }

  /**
   * Behavioural check on the same DB: software vertices in the modern graph have no {@code age},
   * so portable order drops them.
   */
  @Test
  public void suiteModernGraph_orderByAgeDropsVerticesMissingTheKey() {
    var pool = provider.graphGetterSessionPools.get("modern");
    assertThat(pool).as("startServer must load the modern graph").isNotNull();

    var ordered = pool.asGraph().traversal()
        .V().order().by("age").values("name").toList();

    assertThat(ordered)
        .as("portable order keeps the four people and drops the two software vertices")
        .containsExactly("vadas", "marko", "josh", "peter");
  }
}
