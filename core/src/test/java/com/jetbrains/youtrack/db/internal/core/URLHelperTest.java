package com.jetbrains.youtrack.db.internal.core;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.internal.core.util.URLHelper;
import java.io.File;
import org.junit.Test;

/**
 *
 */
public class URLHelperTest {

  @Test
  public void testSimpleUrl() {
    var parsed = URLHelper.parse("disk:/path/test/to");
    assertEquals("disk", parsed.getType());
    assertEquals(parsed.getPath(), new File("/path/test").getAbsolutePath());
    assertEquals("to", parsed.getDbName());

    parsed = URLHelper.parse("memory:some");
    assertEquals("memory", parsed.getType());
    // assertEquals(parsed.getPath(), "");
    assertEquals("some", parsed.getDbName());

    parsed = URLHelper.parse("remote:localhost/to");
    assertEquals("remote", parsed.getType());
    assertEquals("localhost", parsed.getPath());
    assertEquals("to", parsed.getDbName());
  }

  @Test
  public void testSimpleNewUrl() {
    var parsed = URLHelper.parseNew("disk:/path/test/to");
    assertEquals("embedded", parsed.getType());
    assertEquals(parsed.getPath(), new File("/path/test").getAbsolutePath());
    assertEquals("to", parsed.getDbName());

    parsed = URLHelper.parseNew("memory:some");
    assertEquals("embedded", parsed.getType());
    assertEquals("", parsed.getPath());
    assertEquals("some", parsed.getDbName());

    parsed = URLHelper.parseNew("embedded:/path/test/to");
    assertEquals("embedded", parsed.getType());
    assertEquals(parsed.getPath(), new File("/path/test").getAbsolutePath());
    assertEquals("to", parsed.getDbName());

    parsed = URLHelper.parseNew("remote:localhost/to");
    assertEquals("remote", parsed.getType());
    assertEquals("localhost", parsed.getPath());
    assertEquals("to", parsed.getDbName());
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
    assertEquals("remote", parsed.getType());
    assertEquals("localhost", parsed.getPath());
    assertEquals("", parsed.getDbName());

    parsed = URLHelper.parseNew("remote:localhost:2424");
    assertEquals("remote", parsed.getType());
    assertEquals("localhost:2424", parsed.getPath());
    assertEquals("", parsed.getDbName());

    parsed = URLHelper.parseNew("remote:localhost:2424/db1");
    assertEquals("remote", parsed.getType());
    assertEquals("localhost:2424", parsed.getPath());
    assertEquals("db1", parsed.getDbName());
  }
}
