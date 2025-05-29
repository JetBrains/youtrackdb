package com.youtrack.db.gremlin.internal.gremlintest.structure;

import org.apache.tinkerpop.gremlin.GraphProviderClass;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.youtrack.db.gremlin.internal.gremlintest.YTDBGraphProvider;
import org.apache.tinkerpop.gremlin.structure.StructureStandardSuite;
import org.junit.runner.RunWith;

/**
 * Executes the Standard Gremlin Structure Test Suite using OrientGraph.
 *
 * <p>Extracted from TinkerGraph tests
 */
@RunWith(StructureStandardSuite.class)
@GraphProviderClass(provider = YTDBGraphProvider.class, graph = YTDBGraph.class)
public class GraphStructureStandardIT {
}
