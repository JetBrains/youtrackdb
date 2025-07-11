package com.jetbrains.youtrack.db.internal.server.plugin.gremlin.process;

import com.jetbrains.youtrack.db.internal.server.plugin.gremlin.YTDBGraphSONRemoteGraphProvider;
import org.apache.tinkerpop.gremlin.GraphProviderClass;
import org.apache.tinkerpop.gremlin.process.ProcessStandardSuite;
import org.apache.tinkerpop.gremlin.structure.RemoteGraph;
import org.junit.runner.RunWith;

@RunWith(ProcessStandardSuite.class)
@GraphProviderClass(provider = YTDBGraphSONRemoteGraphProvider.class, graph = RemoteGraph.class)
public class GraphSONRemoteGraphProcessStandardTest {
}
