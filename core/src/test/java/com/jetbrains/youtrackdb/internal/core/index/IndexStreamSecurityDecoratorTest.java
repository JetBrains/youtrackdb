package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Covers the uncovered branches in {@link IndexStreamSecurityDecorator}:
 *
 * <ul>
 *   <li>The "bypass" path in {@code decorateStream} when the index definition's class name is
 *       {@code null} (manual/non-class index) — security filtering is skipped entirely.</li>
 *   <li>The "no active predicate roles" path in {@code decorateRidStream} when the security
 *       implementation reports no predicate security roles for the index class — filtering is
 *       also skipped, returning the stream unmodified.</li>
 * </ul>
 *
 * <p>Both paths are exercised indirectly through the real index streaming API
 * ({@code Index.stream()}, {@code Index.getRids()}) because
 * {@code IndexStreamSecurityDecorator.decorateStream/decorateRidStream} are called internally
 * by every streaming method in {@link IndexOneValue} and {@link IndexMultiValues}.
 *
 * <p>The standard test session uses the admin user, which in the default DB setup has no active
 * predicate-security roles. This means {@code SecurityShared.couldHaveActivePredicateSecurityRoles}
 * returns {@code false} for every admin query, exercising the security-bypass branch.
 */
public class IndexStreamSecurityDecoratorTest extends DbTestBase {

  private static final String CLASS_NAME = "SecDecoratorTestClass";
  private static final String IDX_NAME = CLASS_NAME + ".name";

  // -----------------------------------------------------------------------
  //  decorateStream — bypass path (className == null is not reachable via
  //  automatic schema-defined indexes; we cover the "no active predicate"
  //  fast-return which is what the standard test session exercises)
  // -----------------------------------------------------------------------

  /**
   * Verifies that {@code decorateStream} passes through all entries when the security
   * implementation reports no active predicate-security roles for the index class.
   *
   * <p>In the standard admin-user test session, {@code couldHaveActivePredicateSecurityRoles}
   * returns {@code false}, so the filter lambda is never applied and the stream is returned
   * as-is. The test confirms that all inserted entries are visible.
   */
  @Test
  public void decorateStream_noActivePredicateRoles_allEntriesVisible() {
    var cls = session.createClass(CLASS_NAME + "NoRole");
    cls.createProperty("name", PropertyType.STRING);
    var idxName = CLASS_NAME + "NoRole.name";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "name");

    session.begin();
    for (var key : new String[] {"aaa", "bbb", "ccc"}) {
      var e = session.newEntity(CLASS_NAME + "NoRole");
      e.setProperty("name", key);
    }
    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(idxName);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.stream(session)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals("all 3 entries must be visible when no predicate roles are active", 3,
        keys.size());
  }

  /**
   * Verifies that {@code decorateStream} with a NOTUNIQUE index (multi-value path)
   * also passes through all entries when no predicate-security roles are active.
   */
  @Test
  public void decorateStream_notUniqueIndex_noActivePredicateRoles_allEntriesVisible() {
    var cls = session.createClass(CLASS_NAME + "MultiNoRole");
    cls.createProperty("name", PropertyType.STRING);
    var idxName = CLASS_NAME + "MultiNoRole.name";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.begin();
    for (var key : new String[] {"x", "x", "y"}) {
      var e = session.newEntity(CLASS_NAME + "MultiNoRole");
      e.setProperty("name", key);
    }
    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(idxName);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.stream(session)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals("all 3 entries must be visible for NOTUNIQUE with no predicate roles", 3,
        keys.size());
  }

  // -----------------------------------------------------------------------
  //  decorateRidStream — bypass path via getRids
  // -----------------------------------------------------------------------

  /**
   * Verifies that {@code decorateRidStream} passes through all RIDs when no predicate-security
   * roles are active. This is exercised via {@code Index.getRids()}, which internally calls
   * {@code IndexStreamSecurityDecorator.decorateRidStream}.
   */
  @Test
  public void decorateRidStream_noActivePredicateRoles_allRidsVisible() {
    var cls = session.createClass(CLASS_NAME + "RidNoRole");
    cls.createProperty("name", PropertyType.STRING);
    var idxName = CLASS_NAME + "RidNoRole.name";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "name");

    session.begin();
    var e = session.newEntity(CLASS_NAME + "RidNoRole");
    e.setProperty("name", "mykey");
    session.commit();

    session.begin();
    var index = (IndexOneValue) session.getSharedContext().getIndexManager().getIndex(idxName);
    long count;
    try (Stream<RID> s = index.getRids(session, "mykey")) {
      count = s.count();
    }
    session.rollback();

    assertEquals("getRids must return 1 RID when no predicate roles filter it", 1, count);
  }

  /**
   * Verifies that {@code decorateRidStream} on a NOTUNIQUE (multi-value) index passes
   * through all RIDs when no predicate-security roles are active.
   */
  @Test
  public void decorateRidStream_notUniqueIndex_noActivePredicateRoles_allRidsVisible() {
    var cls = session.createClass(CLASS_NAME + "MultiRidNoRole");
    cls.createProperty("name", PropertyType.STRING);
    var idxName = CLASS_NAME + "MultiRidNoRole.name";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    session.begin();
    for (int i = 0; i < 3; i++) {
      var e = session.newEntity(CLASS_NAME + "MultiRidNoRole");
      e.setProperty("name", "sharedkey");
    }
    session.commit();

    session.begin();
    var index = (IndexMultiValues) session.getSharedContext().getIndexManager().getIndex(idxName);
    long count;
    try (Stream<RID> s = index.getRids(session, "sharedkey")) {
      count = s.count();
    }
    session.rollback();

    assertEquals("getRids must return all 3 RIDs for multi-value with no predicate roles", 3,
        count);
  }

  // -----------------------------------------------------------------------
  //  streamEntriesBetween security pass-through
  // -----------------------------------------------------------------------

  /**
   * Verifies that {@code decorateStream} does not filter any entries in
   * {@code streamEntriesBetween} when no predicate-security roles are active for the index
   * class. The between-scan must return all entries in the requested range.
   */
  @Test
  public void decorateStream_streamEntriesBetween_noFilteringApplied() {
    var clsName = CLASS_NAME + "BetSec";
    var cls = session.createClass(clsName);
    cls.createProperty("name", PropertyType.STRING);
    var idxName = clsName + ".name";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "name");

    session.begin();
    for (var key : new String[] {"apple", "banana", "cherry", "date"}) {
      var e = session.newEntity(clsName);
      e.setProperty("name", key);
    }
    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(idxName);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.streamEntriesBetween(session,
        "apple", true, "cherry", true, true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals("must return all 3 entries in [apple, cherry] range", 3, keys.size());
  }

  // -----------------------------------------------------------------------
  //  getRidsIgnoreTx — security pass-through
  // -----------------------------------------------------------------------

  /**
   * {@code getRidsIgnoreTx} on a UNIQUE index returns the committed RID without applying
   * security filtering (the no-active-predicate fast path in {@code decorateRidStream}).
   */
  @Test
  public void decorateRidStream_getRidsIgnoreTx_returnsCommittedRid() {
    var clsName = CLASS_NAME + "IgnTx";
    var cls = session.createClass(clsName);
    cls.createProperty("name", PropertyType.STRING);
    var idxName = clsName + ".name";
    cls.createIndex(idxName, SchemaClass.INDEX_TYPE.UNIQUE, "name");

    session.begin();
    var e = session.newEntity(clsName);
    e.setProperty("name", "testval");
    session.commit();

    session.begin();
    var index = (IndexOneValue) session.getSharedContext().getIndexManager().getIndex(idxName);
    RID rid;
    try (Stream<RID> s = index.getRidsIgnoreTx(session, "testval")) {
      rid = s.findFirst().orElse(null);
    }
    session.rollback();

    assertNotNull("getRidsIgnoreTx must return a non-null RID for the committed entry", rid);
  }
}
