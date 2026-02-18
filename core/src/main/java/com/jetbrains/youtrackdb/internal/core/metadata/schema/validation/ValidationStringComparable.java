package com.jetbrains.youtrackdb.internal.core.metadata.schema.validation;

@SuppressWarnings("ComparableType")
public class ValidationStringComparable implements Comparable<Object> {

  private final int size;

  public ValidationStringComparable(int size) {
    this.size = size;
  }

  @Override
  public int compareTo(Object o) {
    return size - o.toString().length();
  }
}
