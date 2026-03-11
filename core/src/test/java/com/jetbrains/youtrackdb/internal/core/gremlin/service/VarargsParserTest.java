package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class VarargsParserTest {

  @Test
  public void parseVarargs_singleCommand_returnsCommandWithEmptyArgs() {
    var result = VarargsParser.parseVarargs(List.of("SELECT 1"));
    Assert.assertEquals("SELECT 1", result.command());
    Assert.assertTrue(result.arguments().isEmpty());
  }

  @Test
  public void parseVarargs_commandWithOneKeyValuePair() {
    var result = VarargsParser.parseVarargs(List.of("MATCH (n:V)", "key", "value"));
    Assert.assertEquals("MATCH (n:V)", result.command());
    Assert.assertEquals(Map.of("key", "value"), result.arguments());
  }

  @Test
  public void parseVarargs_commandWithMultipleKeyValuePairs() {
    var result = VarargsParser.parseVarargs(
        List.of("query", "k1", "v1", "k2", "v2"));
    Assert.assertEquals("query", result.command());
    Assert.assertEquals(2, result.arguments().size());
    Assert.assertEquals("v1", result.arguments().get("k1"));
    Assert.assertEquals("v2", result.arguments().get("k2"));
  }

  @Test
  public void parseVarargs_preservesInsertionOrder() {
    var result = VarargsParser.parseVarargs(
        List.of("q", "b", 2, "a", 1));
    var keys = result.arguments().keySet().stream().toList();
    Assert.assertEquals(List.of("b", "a"), keys);
  }

  @Test
  public void parseVarargs_nonStringValues_allowed() {
    var result = VarargsParser.parseVarargs(
        List.of("q", "count", 42, "flag", true));
    Assert.assertEquals(42, result.arguments().get("count"));
    Assert.assertEquals(true, result.arguments().get("flag"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void parseVarargs_emptyList_throws() {
    VarargsParser.parseVarargs(List.of());
  }

  @Test(expected = IllegalArgumentException.class)
  public void parseVarargs_firstElementNotString_throws() {
    VarargsParser.parseVarargs(List.of(123));
  }

  @Test(expected = IllegalArgumentException.class)
  public void parseVarargs_oddNumberOfArgs_throws() {
    VarargsParser.parseVarargs(List.of("q", "key"));
  }

  @Test
  public void parseVarargs_emptyString_accepted() {
    var result = VarargsParser.parseVarargs(List.of(""));
    Assert.assertEquals("", result.command());
    Assert.assertTrue(result.arguments().isEmpty());
  }

  @Test
  public void parseResult_recordAccessors_work() {
    var result = new VarargsParser.ParseResult("cmd", Map.of("k", "v"));
    Assert.assertEquals("cmd", result.command());
    Assert.assertEquals(Map.of("k", "v"), result.arguments());
  }
}
