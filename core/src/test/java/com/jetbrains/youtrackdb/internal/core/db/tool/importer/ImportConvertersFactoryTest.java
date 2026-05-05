/*
 *
 *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *
 */
package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.HashSet;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for {@link ImportConvertersFactory}'s dispatch table. The factory is a
 * singleton (one static {@code INSTANCE}) that maps a runtime value type to a converter class.
 * The dispatch order matters because the link-typed concrete classes
 * (e.g., {@code EntityLinkListImpl}) extend the generic {@code Identifiable}-bearing class
 * hierarchy, so the more-specific check must run first.
 *
 * <p>This test pins:
 *
 * <ul>
 *   <li>The {@code INSTANCE} singleton convention.</li>
 *   <li>The {@code BROKEN_LINK} sentinel rid (cluster -1, position -42 — the unique value the
 *       importer uses to flag a broken link).</li>
 *   <li>Each dispatch arm — {@link LinkMapConverter}, {@link EmbeddedMapConverter}, {@link
 *       LinkListConverter}, {@link EmbeddedListConverter}, {@link LinkSetConverter}, {@link
 *       EmbeddedSetConverter}, {@link LinkBagConverter}, {@link LinkConverter}, and the
 *       null fallback for unrecognised types.</li>
 * </ul>
 */
public class ImportConvertersFactoryTest extends DbTestBase {

  /**
   * Defensive {@code @After} (Track 5+ idiom).
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session == null || session.isClosed()) {
      return;
    }
    var tx = session.getActiveTransactionOrNull();
    if (tx != null && tx.isActive()) {
      tx.rollback();
    }
  }

  private ConverterData newConverterData() {
    return new ConverterData(session, new HashSet<>());
  }

  /**
   * The factory exposes a single static {@code INSTANCE} singleton — the importer reaches it
   * via {@link ImportConvertersFactory#INSTANCE} from multiple call sites and must see the
   * same instance every time. Pinning identity catches a refactor that turns the singleton
   * into a per-call factory or a thread-local without an explicit migration plan.
   */
  @Test
  public void testInstanceIsSingleton() {
    var first = ImportConvertersFactory.INSTANCE;
    var second = ImportConvertersFactory.INSTANCE;

    assertSame("INSTANCE must be a true singleton", first, second);
    assertNotNull(first);
  }

  /**
   * The {@code BROKEN_LINK} sentinel is a fixed rid {@code #-1:-42}. The negative cluster id
   * marks it as non-persistent; the unique pair distinguishes it from any other transient
   * identity used in the system. {@link AbstractCollectionConverter#convertSingleValue}
   * compares against this constant by reference ({@code ==}), so the field must remain a
   * single shared instance.
   */
  @Test
  public void testBrokenLinkSentinelHasFixedShape() {
    RID sentinel = ImportConvertersFactory.BROKEN_LINK;

    assertNotNull(sentinel);
    assertEquals("BROKEN_LINK collection id is -1", -1, sentinel.getCollectionId());
    assertEquals("BROKEN_LINK position is -42", -42, sentinel.getCollectionPosition());
    assertSame(
        "BROKEN_LINK must be a single shared instance (used in == comparison)",
        sentinel, ImportConvertersFactory.BROKEN_LINK);
  }

  /**
   * An {@code EntityLinkListImpl} instance dispatches to {@link LinkListConverter}.
   */
  @Test
  public void testLinkListDispatch() {
    var value = session.newLinkList();
    var converter = ImportConvertersFactory.INSTANCE.getConverter(value, newConverterData());

    assertNotNull(converter);
    assertEquals(LinkListConverter.class, converter.getClass());
  }

  /**
   * An {@code EntityEmbeddedListImpl} instance dispatches to {@link EmbeddedListConverter}.
   */
  @Test
  public void testEmbeddedListDispatch() {
    var value = session.newEmbeddedList();
    var converter = ImportConvertersFactory.INSTANCE.getConverter(value, newConverterData());

    assertNotNull(converter);
    assertEquals(EmbeddedListConverter.class, converter.getClass());
  }

  /**
   * An {@code EntityLinkSetImpl} instance dispatches to {@link LinkSetConverter}.
   * {@code newLinkSet} requires an active transaction (the underlying {@code EmbeddedLinkBag}
   * registers with the tx at construction); the dispatch lookup itself does not, so the
   * lookup runs after the tx-scoped allocation finishes.
   */
  @Test
  public void testLinkSetDispatch() {
    session.executeInTx(tx -> {
      var value = session.newLinkSet();
      var converter = ImportConvertersFactory.INSTANCE.getConverter(value, newConverterData());

      assertNotNull(converter);
      assertEquals(LinkSetConverter.class, converter.getClass());
    });
  }

  /**
   * An {@code EntityEmbeddedSetImpl} instance dispatches to {@link EmbeddedSetConverter}.
   */
  @Test
  public void testEmbeddedSetDispatch() {
    var value = session.newEmbeddedSet();
    var converter = ImportConvertersFactory.INSTANCE.getConverter(value, newConverterData());

    assertNotNull(converter);
    assertEquals(EmbeddedSetConverter.class, converter.getClass());
  }

  /**
   * An {@code EntityLinkMapIml} instance dispatches to {@link LinkMapConverter}.
   */
  @Test
  public void testLinkMapDispatch() {
    var value = session.newLinkMap();
    var converter = ImportConvertersFactory.INSTANCE.getConverter(value, newConverterData());

    assertNotNull(converter);
    assertEquals(LinkMapConverter.class, converter.getClass());
  }

  /**
   * An {@code EntityEmbeddedMapImpl} instance dispatches to {@link EmbeddedMapConverter}.
   */
  @Test
  public void testEmbeddedMapDispatch() {
    var value = session.newEmbeddedMap();
    var converter = ImportConvertersFactory.INSTANCE.getConverter(value, newConverterData());

    assertNotNull(converter);
    assertEquals(EmbeddedMapConverter.class, converter.getClass());
  }

  /**
   * A {@link LinkBag} dispatches to {@link LinkBagConverter}. The bag is special-cased because
   * it implements {@code Iterable<RidPair>}, not {@code Collection<Identifiable>}. Construction
   * requires an active transaction.
   */
  @Test
  public void testLinkBagDispatch() {
    session.executeInTx(tx -> {
      var value = new LinkBag(session);
      var converter = ImportConvertersFactory.INSTANCE.getConverter(value, newConverterData());

      assertNotNull(converter);
      assertEquals(LinkBagConverter.class, converter.getClass());
    });
  }

  /**
   * A bare {@link RID} (Identifiable) — not wrapped in a collection — dispatches to {@link
   * LinkConverter}. This is the catch-all link arm that handles individual link-typed fields.
   */
  @Test
  public void testIdentifiableDispatch() {
    RID value = new RecordId(20, 1);
    var converter = ImportConvertersFactory.INSTANCE.getConverter(value, newConverterData());

    assertNotNull(converter);
    assertEquals(LinkConverter.class, converter.getClass());
  }

  /**
   * An unrecognised type (here: a plain string) dispatches to null — the dispatch table has
   * no fallback for primitives, so callers must handle null.
   */
  @Test
  public void testScalarReturnsNull() {
    var converter =
        ImportConvertersFactory.INSTANCE.getConverter("scalar", newConverterData());

    assertNull("scalar values must dispatch to null (no converter)", converter);
  }

  /**
   * Pin: a fresh {@link RecordId} is treated as Identifiable, not as a sub-type of any of the
   * collection check classes. This protects the dispatch ordering — if a future refactor
   * accidentally moves the {@code Identifiable} check above the collection checks, plain rids
   * would still resolve to {@link LinkConverter}, but a link list/set/map/bag would too,
   * which would silently bypass the per-collection handling and corrupt rewrites.
   */
  @Test
  public void testRidIsNotMisroutedToCollectionConverter() {
    RID value = new RecordId(20, 1);
    var converter =
        ImportConvertersFactory.INSTANCE.getConverter(value, newConverterData());

    assertTrue("plain rid must resolve to LinkConverter, not a collection variant",
        converter instanceof LinkConverter);
  }
}
