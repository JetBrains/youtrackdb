package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityShared;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

public class IndexStreamSecurityDecorator {

  public static CloseableIterator<RawPair<Object, RID>> decorateIterator(
      Index originalIndex, CloseableIterator<RawPair<Object, RID>> iterator,
      DatabaseSessionEmbedded session) {
    var indexClass = originalIndex.getDefinition().getClassName();
    if (indexClass == null) {
      return iterator;
    }
    var security = session.getSharedContext().getSecurity();
    if (security instanceof SecurityShared
        && !((SecurityShared) security).couldHaveActivePredicateSecurityRoles(session,
        indexClass)) {
      return iterator;
    }

    return YTDBIteratorUtils.filter(iterator,
        (pair) -> Index.securityFilterOnRead(session, originalIndex, pair.second()) != null);
  }

  public static CloseableIterator<RID> decorateRidIterator(Index originalIndex,
      CloseableIterator<RID> iterator,
      DatabaseSessionEmbedded session) {
    var indexClass = originalIndex.getDefinition().getClassName();
    if (indexClass == null) {
      return iterator;
    }
    var security = session.getSharedContext().getSecurity();
    if (security instanceof SecurityShared
        && !((SecurityShared) security).couldHaveActivePredicateSecurityRoles(session,
        indexClass)) {
      return iterator;
    }

    return YTDBIteratorUtils.filter(iterator,
        (rid) -> Index.securityFilterOnRead(session, originalIndex, rid) != null);
  }
}
