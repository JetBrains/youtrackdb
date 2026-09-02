package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

/**
 * Pins the two halves of the conformance opt-out that the suite base configuration installs.
 *
 * <p>YouTrackDB ships a deviation from portable Apache TinkerPop semantics. A global-scope
 * {@code order()} keeps a record that lacks the ordered property and sorts it as a null key.
 * Upstream scenarios assert the portable drop, so the suites run with that deviation switched off
 * through {@code YTDBGraphInitUtil.getBaseConfiguration}.
 *
 * <p>The opt-out must do exactly one thing. It must switch off the YouTrackDB default. It must not
 * switch off upstream {@code ProductiveByStrategy}, which a scenario may add on its own traversal
 * and which upstream expects to keep working. Each half is a separate case below.
 *
 * <p>The fixture is the upstream modern graph. Two of its six vertices are software, and software
 * carries no {@code age} property, so an order by {@code age} separates the drop from the keep
 * without seeding anything.
 */
@Category(SequentialTest.class)
@RunWith(GremlinProcessRunner.class)
public class YTDBProductiveOrderOptOutProcessTest extends YTDBAbstractGremlinTest {

  /**
   * Half one. Under the suite configuration the YouTrackDB default is off, so the four people are
   * ordered and the two software vertices are dropped. Six vertices go in and four come out.
   */
  @Test
  @LoadGraphWith(GraphData.MODERN)
  public void suiteConfiguration_dropsRecordMissingTheOrderKey() {
    assertThat(resolvedSetting())
        .as("the suite base configuration must carry the portable opt-out")
        .isFalse();

    var ordered = g().V().order().by("age").values("name").toList();

    assertThat(ordered)
        .as("portable order semantics drop the two software vertices, which carry no age")
        .containsExactly("vadas", "marko", "josh", "peter");
  }

  /**
   * Half two. Upstream {@code ProductiveByStrategy} still makes the modulator productive on a
   * traversal that asks for it, so the two software vertices come back and sort as null keys. The
   * opt-out therefore disables the YouTrackDB default alone, not productive-by handling in
   * general.
   */
  @Test
  @LoadGraphWith(GraphData.MODERN)
  public void upstreamProductiveByStrategy_stillKeepsRecordMissingTheOrderKey() {
    var ordered = g().withStrategies(ProductiveByStrategy.instance())
        .V().order().by("age").values("name").toList();

    assertThat(ordered)
        .as("the upstream strategy keeps every vertex, including the two without an age")
        .hasSize(6)
        .containsAll(g().V().values("name").toList());
    assertThat(ordered.subList(2, 6))
        .as("and the aged vertices still sort by age, after the two null keys")
        .containsExactly("vadas", "marko", "josh", "peter");
  }

  /**
   * The per-traversal override still reaches a suite traversal, so one traversal can ask for the
   * YouTrackDB semantics while the suite default stays portable. This is the escape hatch a
   * project-owned scenario uses to cover the shipped default inside the suites.
   */
  @Test
  @LoadGraphWith(GraphData.MODERN)
  public void perTraversalOptIn_overridesTheSuiteOptOut() {
    var ordered = g().with(YTDBQueryConfigParam.orderIncludesMissingKey, true)
        .V().order().by("age").values("name").toList();

    assertThat(ordered)
        .as("the option wins over the suite default, so no vertex is dropped")
        .hasSize(6);
    assertThat(ordered.subList(2, 6))
        .as("the aged vertices sort by age after the two null keys")
        .containsExactly("vadas", "marko", "josh", "peter");
  }

  /** The resolved value of the productive-order setting on the graph under test. */
  private boolean resolvedSetting() {
    var tx = (YTDBTransaction) graph().tx();
    tx.readWrite();
    return tx.getDatabaseSession().getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
  }
}
