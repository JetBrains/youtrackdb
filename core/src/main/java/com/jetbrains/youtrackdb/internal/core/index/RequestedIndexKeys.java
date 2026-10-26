package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/** Builds distinct lookup requests in the collated key space. */
final class RequestedIndexKeys {

  private RequestedIndexKeys() {
  }

  static List<Object> distinctSorted(Collection<?> keys, UnaryOperator<Object> collate,
      boolean ascending) {
    // CompositeKey.compareTo treats a prefix as equal to a longer key.
    // Refine that order for deduplication so separate prefix requests survive.
    var distinct = new TreeSet<Object>(RequestedIndexKeys::compareRequests);
    var result = new ArrayList<Object>();
    for (var key : keys) {
      var collated = collate.apply(key);
      if (distinct.add(collated)) {
        result.add(collated);
      }
    }
    result.sort(ascending ? DefaultComparator.INSTANCE
        : Collections.reverseOrder(DefaultComparator.INSTANCE));
    return result;
  }

  private static int compareRequests(Object left, Object right) {
    if (left == null || right == null) {
      return left == right ? 0 : left == null ? -1 : 1;
    }
    if (left instanceof CompositeKey leftKey) {
      if (!(right instanceof CompositeKey rightKey)) {
        return 1;
      }
      var leftParts = leftKey.getKeys();
      var rightParts = rightKey.getKeys();
      for (int i = 0; i < Math.min(leftParts.size(), rightParts.size()); i++) {
        int comparison = DefaultComparator.INSTANCE.compare(leftParts.get(i), rightParts.get(i));
        if (comparison != 0) {
          return comparison;
        }
      }
      return Integer.compare(leftParts.size(), rightParts.size());
    }
    if (right instanceof CompositeKey) {
      return -1;
    }
    return DefaultComparator.INSTANCE.compare(left, right);
  }
}
