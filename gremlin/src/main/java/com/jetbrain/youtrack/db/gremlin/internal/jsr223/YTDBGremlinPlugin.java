package com.jetbrain.youtrack.db.gremlin.internal.jsr223;

import com.jetbrain.youtrack.db.gremlin.internal.YTDBEdgeImpl;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBElement;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBGraphImpl;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBProperty;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBVertexImpl;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBVertexProperty;
import com.jetbrain.youtrack.db.gremlin.internal.YouTrackDBFeatures;
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
                  YTDBEdgeImpl.class,
                  YTDBElement.class,
                  YTDBGraphImpl.class,
                  YouTrackDBFeatures.YTDBVariableFeatures.class,
                  YTDBProperty.class,
                  YTDBVertexImpl.class,
                  YTDBVertexProperty.class)
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
