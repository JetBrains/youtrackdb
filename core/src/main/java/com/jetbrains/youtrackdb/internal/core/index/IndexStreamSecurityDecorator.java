package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityShared;
import java.util.stream.Stream;

public class IndexStreamSecurityDecorator {

  public static Stream<RawPair<Object, RID>> decorateStream(
      Index originalIndex, Stream<RawPair<Object, RID>> stream, DatabaseSessionEmbedded session) {
    var indexClass = originalIndex.getDefinition().getClassName();
    if (indexClass == null) {
      return stream;
    }
    var security = session.getSharedContext().getSecurity();
    if (security instanceof SecurityShared
        && !((SecurityShared) security).couldHaveActivePredicateSecurityRoles(session,
        indexClass)) {
      return stream;
    }

    return stream.filter(
        (pair) -> Index.securityFilterOnRead(session, originalIndex, pair.second()) != null);
  }

  public static Stream<RID> decorateRidStream(Index originalIndex, Stream<RID> stream,
      DatabaseSessionEmbedded session) {
    var indexClass = originalIndex.getDefinition().getClassName();
    if (indexClass == null) {
      return stream;
    }
    var security = session.getSharedContext().getSecurity();
    if (security instanceof SecurityShared
        && !((SecurityShared) security).couldHaveActivePredicateSecurityRoles(session,
        indexClass)) {
      return stream;
    }

    return stream.filter(
        (rid) -> Index.securityFilterOnRead(session, originalIndex, rid) != null);
  }
}
