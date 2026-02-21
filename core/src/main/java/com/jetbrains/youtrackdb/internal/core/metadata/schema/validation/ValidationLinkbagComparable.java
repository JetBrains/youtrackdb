package com.jetbrains.youtrackdb.internal.core.metadata.schema.validation;

import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;

@SuppressWarnings("ComparableType")
public class ValidationLinkbagComparable implements Comparable<Object> {

  private final int size;

  public ValidationLinkbagComparable(int size) {
    this.size = size;
  }

  @Override
  public int compareTo(Object o) {
    return size - ((LinkBag) o).size();
  }
}
