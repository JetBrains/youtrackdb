package com.jetbrains.youtrack.db.internal.core.metadata.schema.validation;

import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;

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
