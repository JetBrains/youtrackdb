package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites.YTDBProcessSuiteEmbedded;
import org.apache.tinkerpop.gremlin.GraphProviderClass;
import org.junit.runner.RunWith;


@RunWith(YTDBProcessSuiteEmbedded.class)
@GraphProviderClass(provider = YTDBGraphProvider.class, graph = YTDBGraph.class)
public class YTDBProcessTest {

}
