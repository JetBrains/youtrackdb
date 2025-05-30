package com.youtrack.db.gremlin.internal.gremlintest;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import org.apache.tinkerpop.gremlin.GraphProviderClass;
import org.apache.tinkerpop.gremlin.process.ProcessStandardSuite;
import org.junit.runner.RunWith;


@RunWith(ProcessStandardSuite.class)
@GraphProviderClass(provider = YTDBGraphProvider.class, graph = YTDBGraph.class)
public class YTDBProcessStandardTest {

}
