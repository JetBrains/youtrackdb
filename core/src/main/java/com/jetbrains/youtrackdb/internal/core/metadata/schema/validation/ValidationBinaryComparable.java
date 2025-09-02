package com.jetbrains.youtrackdb.internal.core.metadata.schema.validation;

public class ValidationBinaryComparable implements Comparable<Object> {

  private final int size;

  public ValidationBinaryComparable(int size) {
    this.size = size;
  }

  @Override
  public int compareTo(Object o) {
    return size - ((byte[]) o).length;
  }
}
