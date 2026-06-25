package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;

/** Shared graph/tx helpers for {@link GqlMatchStatement} integration tests. */
@SuppressWarnings("resource")
abstract class GqlGraphTestBase extends GraphBaseTest {

  protected YTDBGraphInternal ytdbGraph() {
    return (YTDBGraphInternal) graph;
  }

  protected void commitGraphTx() {
    ytdbGraph().tx().commit();
  }

  protected DatabaseSessionEmbedded readWriteSession() {
    var tx = ytdbGraph().tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }
}
