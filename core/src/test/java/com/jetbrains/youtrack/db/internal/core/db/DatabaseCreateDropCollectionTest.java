package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class DatabaseCreateDropCollectionTest extends DbTestBase {

  @Test
  public void createDropCollection() {
    session.addCollection("test");
    Assert.assertNotEquals(-1, session.getCollectionIdByName("test"));
    session.dropCollection("test");
    Assert.assertEquals(-1, session.getCollectionIdByName("test"));
  }
}
