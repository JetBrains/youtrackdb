package com.youtrack.db.gremlin.internal.gremlintest.process;

import org.apache.tinkerpop.gremlin.GraphProviderClass;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.youtrack.db.gremlin.internal.gremlintest.YTDBGraphProvider;

// @RunWith(ProcessStandardSuite.class)
@GraphProviderClass(provider = YTDBGraphProvider.class, graph = YTDBGraph.class)
public class StandardGraphProcessStandardTest {

}
