package com.jetbrains.youtrack.db.internal.server.query;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import org.junit.Test;

/**
 *
 */
public class RemoteDropCollectionTest extends BaseServerMemoryDatabase {

  public void beforeTest() {
    GlobalConfiguration.CLASS_COLLECTIONS_COUNT.setValue(1);
    super.beforeTest();
  }

  @Test
  public void simpleDropCollection() {
    var cl = session.addCollection("one");
    session.dropCollection(cl);
  }

  @Test
  public void simpleDropCollectionTruncate() {
    var cl = session.addCollection("one");
    session.dropCollection(cl);
  }

  @Test
  public void simpleDropCollectionName() {
    session.addCollection("one");
    session.dropCollection("one");
  }

  @Test
  public void simpleDropCollectionNameTruncate() {
    session.addCollection("one");
    session.dropCollection("one");
  }
}
