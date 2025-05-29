package com.youtrack.db.gremlin.internal.gremlintest.structure;

import com.youtrack.db.gremlin.internal.gremlintest.suite.DebugSuite;
import org.apache.tinkerpop.gremlin.GraphProviderClass;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.youtrack.db.gremlin.internal.gremlintest.YTDBGraphProvider;
import org.junit.runner.RunWith;

/**
 * Created by Enrico Risa on 10/11/16.
 */
@RunWith(DebugSuite.class)
@GraphProviderClass(provider = YTDBGraphProvider.class, graph = YTDBGraph.class)
public class DebugGraphStructureTest {
}
