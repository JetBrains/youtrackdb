package com.jetbrains.youtrack.db.internal.core.gremlin.gremlintest;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import org.apache.tinkerpop.gremlin.GraphProviderClass;
import org.apache.tinkerpop.gremlin.process.ProcessStandardSuite;
import org.junit.runner.RunWith;


@RunWith(ProcessStandardSuite.class)
@GraphProviderClass(provider = YTDBGraphProvider.class, graph = YTDBGraph.class)
public class YTDBProcessStandardTest {

}
