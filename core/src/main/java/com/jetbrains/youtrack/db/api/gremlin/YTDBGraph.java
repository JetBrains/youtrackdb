package com.jetbrains.youtrack.db.api.gremlin;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBSingleThreadGraph;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactoryClass;

@GraphFactoryClass(YTDBGraphFactory.class)
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_INTEGRATE)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
public interface YTDBGraph extends Graph {

  DatabaseSession getUnderlyingDatabaseSession();

  @Override
  YTDBVertex addVertex(Object... keyValues);

  @Override
  YTDBVertex addVertex(String label);

  static YTDBGraph wrapSession(DatabaseSession session) {
    if (!(session instanceof DatabaseSessionEmbedded)) {
      throw new IllegalArgumentException(
          "Passed in database session is not embedded. Only sessions of embedded databases are supported.");
    }
    return new YTDBSingleThreadGraph(null, (DatabaseSessionEmbedded) session,
        YTDBGraphFactory.createGraphConfiguration(session));
  }
}
