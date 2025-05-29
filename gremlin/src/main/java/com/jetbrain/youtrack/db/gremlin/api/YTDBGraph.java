package com.jetbrain.youtrack.db.gremlin.api;

import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactoryClass;

@GraphFactoryClass(YTDBGraphFactory.class)
public interface YTDBGraph extends Graph {
}
