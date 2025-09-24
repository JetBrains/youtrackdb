package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites.YTDBProcessSuite;
import org.apache.tinkerpop.gremlin.GraphProviderClass;
import org.apache.tinkerpop.gremlin.structure.RemoteGraph;
import org.junit.runner.RunWith;

@RunWith(YTDBProcessSuite.class)
@GraphProviderClass(provider = YTDBGraphSONRemoteGraphProvider.class, graph = RemoteGraph.class)
public class GraphSONRemoteGraphProcessExtendedTest {
}
