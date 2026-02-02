package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import java.util.List;

record RemoveSearchResult(long leafPageIndex, int leafEntryPageIndex, List<RemovalPathItem> path) {

}
