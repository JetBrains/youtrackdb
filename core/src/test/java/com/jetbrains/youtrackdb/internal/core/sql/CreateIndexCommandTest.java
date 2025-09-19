package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import org.junit.Test;

/**
 *
 */
public class CreateIndexCommandTest extends DbTestBase {

  @Test(expected = IndexException.class)
  public void testCreateIndexOnMissingPropertyWithCollate() {
    session.getMetadata().getSlowMutableSchema().createClass("Test");
    session.execute(" create index Test.test on Test(test collate ci) UNIQUE").close();
  }
}
