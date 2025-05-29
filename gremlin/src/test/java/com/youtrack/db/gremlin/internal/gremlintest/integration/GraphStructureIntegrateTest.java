package com.youtrack.db.gremlin.internal.gremlintest.integration;

import org.apache.tinkerpop.gremlin.GraphProviderClass;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import org.apache.tinkerpop.gremlin.structure.StructureStandardSuite;
import com.youtrack.db.gremlin.internal.gremlintest.YTDBGraphProvider;
import org.junit.runner.RunWith;

@RunWith(StructureStandardSuite.class)
@GraphProviderClass(provider = YTDBGraphProvider.class, graph = YTDBGraph.class)
public class GraphStructureIntegrateTest {}
