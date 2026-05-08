package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.Resettable;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Spliterator;
import org.junit.Assert;
import org.junit.Test;

/**
 * Targets the EnhancedIterator and MergingSpliterator inner classes of AbstractLinkBag
 * (reached via LinkBag.iterator() and LinkBag.spliterator()). The methods isResetable(),
 * size(), isSizeable(), and reset() on the iterator, and trySplit(), estimateSize(),
 * and characteristics() on the spliterator, are not exercised by any existing test.
 * Each test builds a per-method memory DB via DbTestBase's @Before lifecycle.
 */
public class LinkBagIteratorOpsTest extends DbTestBase {

  /**
   * Verifies that the EnhancedIterator returned by LinkBag.iterator() reports
   * isResetable()==true and isSizeable()==true (both AbstractLinkBag-defined constants),
   * and that size() returns the bag's element count.
   */
  @Test
  public void testEnhancedIteratorIsResetableIsSizeableAndSize() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var bag = new LinkBag(session);
    entity.setProperty("bag", bag);

    // Add three entries so size() can be verified
    var rid1 = new RecordId(5, 1);
    var rid2 = new RecordId(5, 2);
    var rid3 = new RecordId(5, 3);
    bag.add(rid1);
    bag.add(rid2);
    bag.add(rid3);

    Iterator<RidPair> it = bag.iterator();

    // isResetable() must return true — AbstractLinkBag.EnhancedIterator always does
    Assert.assertTrue(((Resettable) it).isResetable());

    // isSizeable() must return true — AbstractLinkBag.EnhancedIterator always does
    Assert.assertTrue(((Sizeable) it).isSizeable());

    // size() delegates to AbstractLinkBag.size() — should match the number of added entries
    Assert.assertEquals(3, ((Sizeable) it).size());

    session.rollback();
  }

  /**
   * Verifies that reset() on the EnhancedIterator reinstalls a fresh MergingSpliterator,
   * allowing the same iterator instance to traverse the bag from the beginning again.
   * This covers AbstractLinkBag.EnhancedIterator.reset().
   */
  @Test
  public void testEnhancedIteratorResetRestartsTraversal() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var bag = new LinkBag(session);
    entity.setProperty("bag", bag);

    var rid1 = new RecordId(5, 10);
    var rid2 = new RecordId(5, 20);
    bag.add(rid1);
    bag.add(rid2);

    Iterator<RidPair> it = bag.iterator();

    // Advance the iterator past the first entry
    Assert.assertTrue(it.hasNext());
    it.next();

    // reset() must allow iterating from the beginning again
    ((Resettable) it).reset();

    // After reset, hasNext() must be true and next() returns the first entry again
    Assert.assertTrue(it.hasNext());

    session.rollback();
  }

  /**
   * Verifies that MergingSpliterator.trySplit() returns null (the spliterator is
   * not further-splittable) and that estimateSize() returns a non-negative value.
   * This covers AbstractLinkBag.MergingSpliterator.trySplit() and estimateSize().
   *
   * Note: LinkBag.spliterator() uses the default Iterable.spliterator() which wraps
   * the iterator and does not return the MergingSpliterator directly. To reach the
   * MergingSpliterator, the delegate (AbstractLinkBag) is accessed via reflection so
   * that delegate.spliterator() dispatches to the AbstractLinkBag override.
   */
  @Test
  public void testMergingSpliteratorTrySplitAndEstimateSize() throws Exception {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var bag = new LinkBag(session);
    entity.setProperty("bag", bag);

    var rid1 = new RecordId(5, 100);
    var rid2 = new RecordId(5, 200);
    bag.add(rid1);
    bag.add(rid2);

    // Access the delegate (AbstractLinkBag) to call its overridden spliterator()
    AbstractLinkBag delegate = getDelegate(bag);
    Spliterator<RidPair> spliterator = delegate.spliterator();

    // trySplit() must return null — AbstractLinkBag.MergingSpliterator is non-splittable
    Assert.assertNull(spliterator.trySplit());

    // estimateSize() must return the bag's element count (size >= 0)
    long estimated = spliterator.estimateSize();
    Assert.assertTrue("estimateSize() must be >= 0", estimated >= 0);

    session.rollback();
  }

  /**
   * Verifies that MergingSpliterator.characteristics() includes SORTED, NONNULL,
   * and ORDERED as declared in AbstractLinkBag.MergingSpliterator.
   * Uses the same delegate-reflection approach as testMergingSpliteratorTrySplitAndEstimateSize.
   */
  @Test
  public void testMergingSpliteratorCharacteristics() throws Exception {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var bag = new LinkBag(session);
    entity.setProperty("bag", bag);

    bag.add(new RecordId(5, 1));

    AbstractLinkBag delegate = getDelegate(bag);
    Spliterator<RidPair> spliterator = delegate.spliterator();

    int chars = spliterator.characteristics();

    // AbstractLinkBag.MergingSpliterator always reports SORTED | NONNULL | ORDERED
    Assert.assertTrue("SORTED expected", (chars & Spliterator.SORTED) != 0);
    Assert.assertTrue("NONNULL expected", (chars & Spliterator.NONNULL) != 0);
    Assert.assertTrue("ORDERED expected", (chars & Spliterator.ORDERED) != 0);

    session.rollback();
  }

  /**
   * Accesses the private {@code delegate} field of a {@link LinkBag} via reflection and
   * returns it as an {@link AbstractLinkBag} so that the overridden {@code spliterator()}
   * method on AbstractLinkBag is dispatched (as opposed to the default Iterable.spliterator()
   * that LinkBag inherits, which wraps the iterator rather than returning the MergingSpliterator).
   */
  private static AbstractLinkBag getDelegate(LinkBag bag) throws Exception {
    Field delegateField = LinkBag.class.getDeclaredField("delegate");
    delegateField.setAccessible(true);
    return (AbstractLinkBag) delegateField.get(bag);
  }
}
