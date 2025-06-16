package com.jetbrains.youtrack.db.internal.core.gremlin.jsr223;

import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBAbstractElement;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBElementWrapper;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphImplSession;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphImplSessionPool;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBPropertyImpl;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBStatefulEdgeImpl;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBStatefulEdgeWrapper;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBVertexPropertyImpl;
import com.jetbrains.youtrack.db.internal.core.gremlin.YouTrackDBFeatures;
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
                  YTDBStatefulEdgeWrapper.class,
                  YTDBAbstractElement.class,
                  YTDBElementImpl.class,
                  YTDBElementWrapper.class,
                  YTDBGraphImplSession.class,
                  YouTrackDBFeatures.YTDBVariableFeatures.class,
                  YTDBPropertyImpl.class,
                  YTDBVertexImpl.class,
                  YTDBElementWrapper.class,
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
