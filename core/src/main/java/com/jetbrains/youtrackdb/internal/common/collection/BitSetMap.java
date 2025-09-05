package com.jetbrains.youtrack.db.internal.common.collection;

import java.util.BitSet;
import java.util.function.IntPredicate;

/**
 * This bit set map should be used to map a key to a boolean value. At the moment once value is
 * calculated it is not supposed to be updated.
 */
public class BitSetMap {

  private final BitSet keyPresent = new BitSet();
  private final BitSet values = new BitSet();

  public boolean computeIfAbsent(int k, IntPredicate valueFunction) {
    if (keyPresent.get(k)) {
      return values.get(k);
    }
    keyPresent.set(k);
    if (valueFunction.test(k)) {
      values.set(k);
      return true;
    }
    return false;
  }
}
