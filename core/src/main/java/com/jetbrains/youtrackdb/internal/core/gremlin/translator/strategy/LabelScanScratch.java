package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Exact bounded membership for one whole-query scan. Reentrant calls own separate scratch. */
final class LabelScanScratch implements AutoCloseable {
  static final int LIMIT = 64;
  private static final ThreadLocal<LabelScanScratch> LOCAL =
      ThreadLocal.withInitial(LabelScanScratch::new);
  private final String[] labels = new String[LIMIT];
  private int size;
  private Set<String> overflow;
  private boolean leased;

  static LabelScanScratch acquire() {
    var scratch = LOCAL.get();
    if (scratch.leased) {
      scratch = new LabelScanScratch();
    }
    scratch.leased = true;
    return scratch;
  }

  boolean add(String label) {
    if (overflow != null) {
      return overflow.add(label);
    }
    for (int i = 0; i < size; i++) {
      if (labels[i].equals(label)) {
        return false;
      }
    }
    if (size == LIMIT) {
      overflow = new HashSet<>();
      for (int i = 0; i < size; i++) {
        overflow.add(labels[i]);
      }
      return overflow.add(label);
    }
    labels[size++] = label;
    return true;
  }

  @Override
  public void close() {
    Arrays.fill(labels, 0, size, null);
    size = 0;
    overflow = null;
    leased = false;
  }
}
