package com.jetbrains.youtrackdb.internal.gremlin.jsr223;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBElement;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import org.apache.tinkerpop.gremlin.jsr223.AbstractGremlinPlugin;
import org.apache.tinkerpop.gremlin.jsr223.DefaultImportCustomizer;
import org.apache.tinkerpop.gremlin.jsr223.ImportCustomizer;

public final class YTDBGremlinPlugin extends AbstractGremlinPlugin {

  public static final String NAME = "jetbrains.ytdb";

  private static final ImportCustomizer imports = DefaultImportCustomizer.build()
      .addClassImports(
          YTDBEdge.class,
          YTDBElement.class,
          YTDBGraph.class,
          YTDBIoRegistry.class,
          YTDBVertex.class,
          YTDBVertexPropertyId.class,
          YTDBVertexProperty.class,
          YourTracks.class,
          YTDBDemoGraphFactory.class
      ).create();

  private static final YTDBGremlinPlugin INSTANCE = new YTDBGremlinPlugin();

  public static YTDBGremlinPlugin instance() {
    return INSTANCE;
  }

  public YTDBGremlinPlugin() {
    super(NAME, imports);
  }
}
