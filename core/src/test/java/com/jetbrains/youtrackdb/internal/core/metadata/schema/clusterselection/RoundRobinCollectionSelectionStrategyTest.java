package com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Test;

/**
 * Live coverage pin for {@link RoundRobinCollectionSelectionStrategy}, the only collection-
 * selection strategy that {@link com.jetbrains.youtrackdb.internal.core.metadata.schema
 * .SchemaClassImpl} hard-codes in its field initializer (the {@code Balanced} and
 * {@code Default} strategies are dead-code-pinned in the
 * {@link BalancedCollectionSelectionStrategyDeadCodeTest},
 * {@link DefaultCollectionSelectionStrategyDeadCodeTest}, and
 * {@link CollectionSelectionFactoryDeadCodeTest}).
 *
 * <p>The strategy's contract:
 * <ul>
 *   <li>{@link RoundRobinCollectionSelectionStrategy#getName()} returns the registered SPI name
 *       {@code "round-robin"};</li>
 *   <li>{@link RoundRobinCollectionSelectionStrategy#getCollection(
 *       com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 *       com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass,
 *       int[],
 *       EntityImpl)} returns the single collection id when the array is length-1 (short-circuit
 *       arm) and a member of the array otherwise;</li>
 *   <li>{@link RoundRobinCollectionSelectionStrategy#getCollection(
 *       com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 *       com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass,
 *       EntityImpl)} delegates to the array-form using the class's {@code getCollectionIds()}
 *       array;</li>
 *   <li>{@link com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassImpl} (via the
 *       proxy) returns {@code RoundRobin} for {@code getCollectionSelection()}.</li>
 * </ul>
 *
 * <p>Despite the multi-cluster arm using {@link java.util.concurrent.ThreadLocalRandom} (and
 * therefore not being deterministic), the contract <i>is</i> deterministic in two ways: every
 * pick must be a member of the collection-id array, and a sufficiently large sample must hit
 * every collection id at least once with overwhelming probability.
 */
public class RoundRobinCollectionSelectionStrategyTest extends DbTestBase {

  @Test
  public void nameIsTheCanonicalSpiKey() {
    // The strategy's NAME must match the third entry in the META-INF/services SPI registration
    // (verified via CollectionSelectionFactoryDeadCodeTest). This pin guards against renames.
    assertEquals("round-robin", new RoundRobinCollectionSelectionStrategy().getName());
    assertEquals("round-robin", RoundRobinCollectionSelectionStrategy.NAME);
  }

  @Test
  public void singleCollectionShortCircuitsToTheSoleCollection() {
    // The length-1 arm of getCollection(int[]) returns collections[0] without consulting
    // ThreadLocalRandom — pin the deterministic fast path.
    var strategy = new RoundRobinCollectionSelectionStrategy();
    var cls = (SchemaClassInternal) session.getMetadata().getSchema().createClass("RR_Single");
    int[] collections = new int[] {cls.getCollectionIds()[0]};
    var entity = new EntityImpl(new RecordId(-1, -1), session);

    int picked = strategy.getCollection(session, cls, collections, entity);
    assertEquals("length-1 array must short-circuit to collections[0]",
        collections[0], picked);
  }

  @Test
  public void multiCollectionPicksFromTheArray() {
    // The length-N arm uses ThreadLocalRandom.nextInt(0, N) to pick from the array. Pin: every
    // pick is a member of the array. Loop a large number of trials to also assert that every
    // collection id appears at least once (sanity pin against the strategy returning a fixed
    // index).
    var strategy = new RoundRobinCollectionSelectionStrategy();
    var cls = (SchemaClassInternal) session.getMetadata().getSchema()
        .createClass("RR_Multi", 4);
    int[] collections = cls.getCollectionIds();
    assertTrue("test class must have multiple collection ids: "
        + collections.length, collections.length >= 2);

    var entity = new EntityImpl(new RecordId(-1, -1), session);
    var hitCounts = new int[collections.length];
    for (int i = 0; i < 1_000; i++) {
      int picked = strategy.getCollection(session, cls, collections, entity);
      int idx = -1;
      for (int j = 0; j < collections.length; j++) {
        if (collections[j] == picked) {
          idx = j;
          break;
        }
      }
      assertTrue("picked id " + picked + " must be a member of the array",
          idx >= 0);
      hitCounts[idx]++;
    }
    // Every position must have been hit at least once after 1000 trials — failure indicates
    // ThreadLocalRandom is locked to one index, which would be a regression.
    for (int i = 0; i < hitCounts.length; i++) {
      assertTrue("position " + i + " was never picked across 1000 trials (count="
          + hitCounts[i] + ")", hitCounts[i] > 0);
    }
  }

  @Test
  public void threeArgFormDelegatesToClassCollectionIds() {
    // The two-arg getCollection(session, class, entity) form delegates to the array-form using
    // class.getCollectionIds(). Pin: the result is a member of the class's collection ids.
    var strategy = new RoundRobinCollectionSelectionStrategy();
    var cls = (SchemaClassInternal) session.getMetadata().getSchema()
        .createClass("RR_Delegate", 3);
    int[] collections = cls.getCollectionIds();
    var entity = new EntityImpl(new RecordId(-1, -1), session);

    int picked = strategy.getCollection(session, cls, entity);
    boolean isMember = false;
    for (int id : collections) {
      if (id == picked) {
        isMember = true;
        break;
      }
    }
    assertTrue("picked id " + picked + " must be a member of cls.getCollectionIds()",
        isMember);
  }

  @Test
  public void schemaClassImplReturnsRoundRobinByDefault() {
    // SchemaClassImpl's field initializer hard-codes 'new RoundRobinCollectionSelectionStrategy()'
    // — confirm via the live class that getCollectionSelection() returns this concrete class.
    var cls = (SchemaClassInternal) session.getMetadata().getSchema().createClass("RR_Default");
    var strategy = cls.getCollectionSelection();
    assertNotNull(strategy);
    assertEquals("round-robin", strategy.getName());
    assertSame("the strategy class must be RoundRobinCollectionSelectionStrategy",
        RoundRobinCollectionSelectionStrategy.class, strategy.getClass());
  }
}
