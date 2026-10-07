package com.jetbrains.youtrackdb.internal.core.index.comparator;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Comparator;

public class DescComparator implements Comparator<RawPair<Object, RID>> {

  public static final DescComparator INSTANCE = new DescComparator();

  @Override
  public int compare(RawPair<Object, RID> entryOne, RawPair<Object, RID> entryTwo) {
    return AscComparator.INSTANCE.compare(entryTwo, entryOne);
  }
}
