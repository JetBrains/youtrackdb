package com.jetbrains.youtrackdb.internal.core.index.multivalue;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.Test;

/**
 * Tests {@link MultiValuesTransformer}: verifies that the singleton instance casts the
 * stored value (a {@link Collection} of {@link RID}s) back to {@code Collection<RID>}
 * without copying, and that the result is the identical object.
 */
public class MultiValuesTransformerTest {

  /**
   * Verifies that transformFromValue returns the same Collection instance that was
   * passed in, with no defensive copy. The production code relies on an unchecked cast,
   * so the return must be reference-equal to the input.
   */
  @Test
  public void testTransformFromValueReturnsSameCollection() {
    List<RID> rids = Arrays.asList(
        RecordIdInternal.fromString("#1:1", false),
        RecordIdInternal.fromString("#1:2", false));

    var result = MultiValuesTransformer.INSTANCE.transformFromValue(rids);

    assertSame("transformFromValue must return the same Collection without copying", rids, result);
  }

  /**
   * Verifies that the result of transformFromValue is a Collection of RIDs, confirming
   * the expected element type after the unchecked cast.
   */
  @Test
  public void testTransformFromValueReturnsCollectionOfRIDs() {
    RID rid1 = RecordIdInternal.fromString("#2:10", false);
    RID rid2 = RecordIdInternal.fromString("#3:20", false);
    List<RID> rids = Arrays.asList(rid1, rid2);

    Collection<RID> result = MultiValuesTransformer.INSTANCE.transformFromValue(rids);

    assertTrue(result.contains(rid1));
    assertTrue(result.contains(rid2));
  }

  /**
   * Verifies that MultiValuesTransformer.INSTANCE is non-null (singleton is initialised).
   */
  @Test
  public void testSingletonIsNonNull() {
    org.junit.Assert.assertNotNull(MultiValuesTransformer.INSTANCE);
  }
}
