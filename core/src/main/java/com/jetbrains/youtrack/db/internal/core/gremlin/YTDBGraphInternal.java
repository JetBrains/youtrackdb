package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.Element;

public interface YTDBGraphInternal extends YTDBGraph {

  @Override
  DatabaseSessionEmbedded getUnderlyingDatabaseSession();
  YTDBElementFactory elementFactory();
  Set<String> getIndexedKeys(final Class<? extends Element> elementClass, String label);
  YTDBSingleThreadGraphFactory getFactory();
}
