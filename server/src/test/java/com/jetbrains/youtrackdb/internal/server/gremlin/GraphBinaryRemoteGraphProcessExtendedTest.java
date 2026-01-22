package com.jetbrains.youtrackdb.internal.server.gremlin;

import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites.YTDBProcessSuiteRemote;
import org.apache.tinkerpop.gremlin.GraphProviderClass;
import org.apache.tinkerpop.gremlin.structure.RemoteGraph;
import org.junit.runner.RunWith;

@RunWith(YTDBProcessSuiteRemote.class)
@GraphProviderClass(provider = YTDBGraphBinaryRemoteGraphProvider.class, graph = RemoteGraph.class)
public class GraphBinaryRemoteGraphProcessExtendedTest {

}
