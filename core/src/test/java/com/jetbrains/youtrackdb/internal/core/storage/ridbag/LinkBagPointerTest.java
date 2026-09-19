package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests the persisted link-collection pointer domain used by the negative identifier allocator. */
public class LinkBagPointerTest {

  @Test
  public void allocatorStylePointerIsValid() {
    assertTrue(new LinkBagPointer(0, -1).isValid());
    assertTrue(new LinkBagPointer(Long.MAX_VALUE, Long.MIN_VALUE).isValid());
  }

  @Test
  public void invalidSentinelAndNegativeFileIdsAreInvalid() {
    assertFalse(LinkBagPointer.INVALID.isValid());
    assertFalse(new LinkBagPointer(-1, -2).isValid());
  }

  @Test
  public void zeroAndPositiveBagIdsAreInvalid() {
    assertFalse(new LinkBagPointer(0, 0).isValid());
    assertFalse(new LinkBagPointer(0, 1).isValid());
  }
}
