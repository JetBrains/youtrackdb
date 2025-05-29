package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.Element;

public interface YTDBGraphInternal extends YTDBGraph {
  DatabaseSessionEmbedded getUnderlyingSession();
  YTDBElementFactory elementFactory();
  Set<String> getIndexedKeys(final Class<? extends Element> elementClass, String label);
  YTDBSingleThreadGraphFactory getFactory();
}
