package com.jetbrains.youtrack.db.internal.core.metadata.security;

import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class TestReaderDropClass extends DbTestBase {

  @Test
  public void testReaderDropClass() {
    session.getMetadata().getSchema().createClass("ReaderDropClass");
    session.close();
    session = openDatabase(readerUser, readerPassword);
    try {
      session.getMetadata().getSchema().dropClass("ReaderDropClass");
      Assert.fail("reader should not be able to drop a class");
    } catch (SecurityAccessException ex) {
    }
    session.close();
    session = openDatabase();
    Assert.assertTrue(session.getMetadata().getSchema().existsClass("ReaderDropClass"));
  }
}
