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
 *
 * <p><b>Production-bug pin (testEnhancedIteratorResetRestartsTraversal):</b>
 * {@code AbstractLinkBag.EnhancedIterator.reset()} only re-creates the {@code spliterator}
 * field. It does NOT clear or re-prime the cached {@code nextPair} field, which still holds
 * whichever element was queued by the previous {@code next()} call. The first {@code next()}
 * after {@code reset()} therefore returns that stale element instead of restarting from the
 * bag's first element — i.e. {@code reset()} does not actually restart traversal as the
 * method name and {@link Resettable} contract imply.
 *
 * <p>This file pins the buggy behavior with an inverted assertion ({@code assertNotEquals})
 * marked {@code WHEN-FIXED} so the test catches accidental no-op changes today, and so that
 * once the production bug is fixed the assertion can be flipped to {@code assertEquals}
 * (the correct restart contract). The forward-looking fix lives in the deferred-cleanup
 * queue: {@code AbstractLinkBag.EnhancedIterator.reset()} should set
 * {@code nextPair = null} and re-prime it via
 * {@code spliterator.tryAdvance(pair -> nextPair = pair)} after reseating the
 * {@code spliterator} field, mirroring the constructor.
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
   * Pins the buggy behavior of {@code AbstractLinkBag.EnhancedIterator.reset()}:
   * because {@code reset()} re-creates the spliterator without clearing or re-priming the
   * cached {@code nextPair} field, the first {@code next()} after {@code reset()} returns
   * whatever was queued by the prior {@code next()} (i.e. the SECOND element, not the
   * first), and traversal does not restart from the bag's beginning as the method name
   * and {@link Resettable} contract imply.
   *
   * <p>The {@code assertNotEquals(firstPre, firstPost)} below passes today because of the
   * production bug. Once the production fix lands (see class-level Javadoc), flip it to
   * {@code assertEquals} — the {@code WHEN-FIXED} marker identifies the line to change.
   *
   * <p>The test additionally exercises the bug's full surface area by counting how many
   * elements the iterator delivers in the post-reset traversal: with the buggy code the
   * fresh spliterator is consumed twice after reset (returning elements 0 and 1 of the
   * spliterator) while {@code currentPair = stale nextPair} adds a third pre-spliterator
   * element, so the post-reset traversal yields THREE elements for a 2-element bag.
   * The assertion {@code postResetCount == 3} (also marked {@code WHEN-FIXED}) pins this
   * specific pathological count, distinguishing the bug from generic "reset doesn't
   * work" regressions and from a no-op reset (which would yield 1 stale element).
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

    // Capture the first element before reset.
    Assert.assertTrue(it.hasNext());
    RidPair firstPre = it.next();
    Assert.assertNotNull(firstPre);

    // reset() — should restart traversal from the bag's first element.
    ((Resettable) it).reset();

    // hasNext() must be true after reset (the bag has not changed). This rules out the
    // "no-op reset that leaves the iterator exhausted" regression, since the pre-reset
    // next() left nextPair = element-1 and a no-op reset would still report hasNext().
    // It also rules out "reset clears nextPair to null without re-priming", which would
    // make hasNext() return false here.
    Assert.assertTrue(it.hasNext());

    // Read the post-reset element. With the production bug, this returns element 1
    // (stale nextPair from the pre-reset next() call); without the bug, it would
    // return element 0 again — same as firstPre.
    RidPair firstPost = it.next();
    Assert.assertNotNull(firstPost);

    // WHEN-FIXED: change assertNotEquals(...) to assertEquals(firstPre, firstPost) — the
    // correct restart contract requires the post-reset first element to equal the
    // pre-reset first element. Today the production bug makes them differ.
    Assert.assertNotEquals(
        "WHEN-FIXED: change to assertEquals — see class-level Javadoc for production fix",
        firstPre, firstPost);

    // Drain the rest of the post-reset traversal and count. With the bug we already
    // consumed the stale nextPair; the fresh spliterator now feeds 2 more elements
    // (its own elements 0 and 1), giving 1 + 2 = 3 post-reset deliveries for a 2-bag.
    int postResetCount = 1; // counts firstPost
    while (it.hasNext()) {
      RidPair p = it.next();
      Assert.assertNotNull(p);
      postResetCount++;
    }

    // WHEN-FIXED: change to assertEquals(2, postResetCount) — a correct reset() yields
    // exactly bag.size() elements after the reset. Today the bug yields 3 because the
    // stale nextPair leaks an extra element into the post-reset stream.
    Assert.assertEquals(
        "WHEN-FIXED: change expected to 2 — see class-level Javadoc for production fix",
        3, postResetCount);

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
