package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.index.CompositeCollate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import javax.annotation.Nullable;

/** Known equivalence between an ordered index component and the ORDER BY comparison. */
public final class IndexOrderCollations {

  private IndexOrderCollations() {
  }

  /**
   * Only built-in default and ci comparators have a known equivalence with index key order.
   * Check ordered components only. Trailing components cannot change the ordered prefix, but
   * prevent the caller from claiming RID order within an equal-prefix group.
   */
  public static boolean matches(
      Collate indexCollate, int component, @Nullable Collate comparisonCollate) {
    if (indexCollate instanceof CompositeCollate composite) {
      indexCollate = composite.getCollates().get(component);
    }
    var indexDefault = indexCollate == null || indexCollate.getClass() == DefaultCollate.class;
    var comparisonDefault =
        comparisonCollate == null || comparisonCollate.getClass() == DefaultCollate.class;
    if (indexDefault && comparisonDefault) {
      return true;
    }
    return indexCollate != null && indexCollate.getClass() == CaseInsensitiveCollate.class
        && comparisonCollate != null
        && comparisonCollate.getClass() == CaseInsensitiveCollate.class;
  }
}
