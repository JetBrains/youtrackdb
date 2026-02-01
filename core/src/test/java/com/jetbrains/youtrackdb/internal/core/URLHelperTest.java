package com.jetbrains.youtrackdb.internal.core;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.util.URLHelper;
import java.io.File;
import org.junit.Test;

/**
 *
 */
public class URLHelperTest {

  @Test
  public void testSimpleUrl() {
    var parsed = URLHelper.parse("disk:/path/test/to");
    assertEquals("disk", parsed.type());
    assertEquals(parsed.path(), new File("/path/test").getAbsolutePath());
    assertEquals("to", parsed.dbName());

    parsed = URLHelper.parse("memory:some");
    assertEquals("memory", parsed.type());
    // assertEquals(parsed.getPath(), "");
    assertEquals("some", parsed.dbName());

    parsed = URLHelper.parse("remote:localhost/to");
    assertEquals("remote", parsed.type());
    assertEquals("localhost", parsed.path());
    assertEquals("to", parsed.dbName());
  }

  @Test
  public void testSimpleNewUrl() {
    var parsed = URLHelper.parseNew("disk:/path/test/to");
    assertEquals("embedded", parsed.type());
    assertEquals(parsed.path(), new File("/path/test").getAbsolutePath());
    assertEquals("to", parsed.dbName());

    parsed = URLHelper.parseNew("memory:some");
    assertEquals("embedded", parsed.type());
    assertEquals("", parsed.path());
    assertEquals("some", parsed.dbName());

    parsed = URLHelper.parseNew("embedded:/path/test/to");
    assertEquals("embedded", parsed.type());
    assertEquals(parsed.path(), new File("/path/test").getAbsolutePath());
    assertEquals("to", parsed.dbName());

    parsed = URLHelper.parseNew("remote:localhost/to");
    assertEquals("remote", parsed.type());
    assertEquals("localhost", parsed.path());
    assertEquals("to", parsed.dbName());
  }

  @Test(expected = ConfigurationException.class)
  public void testWrongPrefix() {
    URLHelper.parseNew("embd:/path/test/to");
  }

  @Test(expected = ConfigurationException.class)
  public void testNoPrefix() {
    URLHelper.parseNew("/embd/path/test/to");
  }

  @Test()
  public void testRemoteNoDatabase() {
    var parsed = URLHelper.parseNew("remote:localhost");
    assertEquals("remote", parsed.type());
    assertEquals("localhost", parsed.path());
    assertEquals("", parsed.dbName());

    parsed = URLHelper.parseNew("remote:localhost:2424");
    assertEquals("remote", parsed.type());
    assertEquals("localhost:2424", parsed.path());
    assertEquals("", parsed.dbName());

    parsed = URLHelper.parseNew("remote:localhost:2424/db1");
    assertEquals("remote", parsed.type());
    assertEquals("localhost:2424", parsed.path());
    assertEquals("db1", parsed.dbName());
  }
}
