package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.api.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
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
