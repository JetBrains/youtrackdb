package com.jetbrains.youtrackdb.internal.core.gremlin.jsr223;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBDemoGraphFactory;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBElement;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBStatefulEdge;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBOutToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.YTDBPToken;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import org.apache.tinkerpop.gremlin.jsr223.AbstractGremlinPlugin;
import org.apache.tinkerpop.gremlin.jsr223.DefaultImportCustomizer;
import org.apache.tinkerpop.gremlin.jsr223.ImportCustomizer;

public class YTDBGremlinPlugin extends AbstractGremlinPlugin {

  private static final String NAME = "jetbrains.youtrackdb";

  private static final ImportCustomizer imports;

  static {
    try {
      imports =
          DefaultImportCustomizer.build()
              .addClassImports(
                  YTDBStatefulEdge.class,
                  YTDBEdge.class,
                  YTDBElement.class,
                  YTDBGraph.class,
                  YTDBVertexProperty.class,
                  YTDBVertexPropertyId.class,
                  YTDBVertex.class,
                  YTDBGraphFactory.class,
                  YTDBDemoGraphFactory.class,
                  YourTracks.class,
                  YTDBGraphTraversal.class,
                  YTDBSchemaClass.class,
                  YTDBSchemaProperty.class,
                  YTDBDomainVertex.class,
                  YTDBPToken.class,
                  YTDBOutToken.class,
                  YTDBIoRegistry.class,
                  DatabaseType.class,
                  RID.class
              ).create();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  public YTDBGremlinPlugin() {
    super(NAME, imports);
  }

  private static final YTDBGremlinPlugin instance = new YTDBGremlinPlugin();

  public static YTDBGremlinPlugin instance() {
    return instance;
  }

  @Override
  public String getName() {
    return NAME;
  }
}
