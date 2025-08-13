package com.jetbrains.youtrackdb.internal.core.gremlin.jsr223;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphImplSession;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBPropertyImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBStatefulEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexPropertyImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YouTrackDBFeatures;
import org.apache.tinkerpop.gremlin.jsr223.AbstractGremlinPlugin;
import org.apache.tinkerpop.gremlin.jsr223.DefaultImportCustomizer;
import org.apache.tinkerpop.gremlin.jsr223.ImportCustomizer;

public class YTDBGremlinPlugin extends AbstractGremlinPlugin {

  private static final String NAME = "tinkerpop.youtrackdb";

  private static final ImportCustomizer imports;

  static {
    try {
      imports =
          DefaultImportCustomizer.build()
              .addClassImports(
                  YTDBStatefulEdgeImpl.class,
                  YTDBElementImpl.class,
                  YTDBGraphImplSession.class,
                  YouTrackDBFeatures.YTDBVariableFeatures.class,
                  YTDBPropertyImpl.class,
                  YTDBVertexImpl.class,
                  YTDBVertexPropertyImpl.class)
              .create();
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
