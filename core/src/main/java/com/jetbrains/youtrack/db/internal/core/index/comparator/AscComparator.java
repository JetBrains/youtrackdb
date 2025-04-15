package com.jetbrains.youtrack.db.internal.core.index.comparator;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import java.util.Comparator;

public class AscComparator implements Comparator<RawPair<Object, RID>> {

  public static final AscComparator INSTANCE = new AscComparator();

  @Override
  public int compare(RawPair<Object, RID> entryOne, RawPair<Object, RID> entryTwo) {
    return DefaultComparator.INSTANCE.compare(entryOne.first(), entryTwo.first());
  }
}
