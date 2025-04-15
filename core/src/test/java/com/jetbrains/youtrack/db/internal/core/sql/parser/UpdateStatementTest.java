package com.jetbrains.youtrack.db.internal.core.sql.parser;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.Test;

public class UpdateStatementTest {

  protected SimpleNode checkRightSyntax(String query) {
    var result = checkSyntax(query, true);
    return checkSyntax(result.toString(), true);
  }

  protected SimpleNode checkWrongSyntax(String query) {
    return checkSyntax(query, false);
  }

  protected SimpleNode checkSyntax(String query, boolean isCorrect) {
    var osql = getParserFor(query);
    try {
      SimpleNode result = osql.parse();
      if (!isCorrect) {
        //        System.out.println(query);
        //        if (result != null) {
        //          System.out.println("->");
        //          System.out.println(result.toString());
        //          System.out.println("............");
        //        }
        fail();
      }
      //      System.out.println(query);
      //      System.out.println("->");
      //      System.out.println(result.toString());
      //      System.out.println("............");

      return result;
    } catch (Exception e) {
      if (isCorrect) {
        System.out.println(query);
        e.printStackTrace();
        fail();
      }
    }
    return null;
  }

  @Test
  public void testSimpleInsert() {
    checkRightSyntax("update  Foo set a = b");
    checkRightSyntax("update  Foo set a = 'b'");
    checkRightSyntax("update  Foo set a = 1");
    checkRightSyntax("update  Foo set a = 1+1");
    checkRightSyntax("update  Foo set a = a.b.toLowerCase()");

    checkRightSyntax("update  Foo set a = b, b=c");
    checkRightSyntax("update  Foo set a = 'b', b=1");
    checkRightSyntax("update  Foo set a = 1, c=k");
    checkRightSyntax("update  Foo set a = 1+1, c=foo, d='bar'");
    checkRightSyntax("update  Foo set a = a.b.toLowerCase(), b=out('pippo')[0]");
    printTree("update  Foo set a = a.b.toLowerCase(), b=out('pippo')[0]");
  }

  @Test
  public void testCollections() {
    checkRightSyntax("update Foo add a = b");
    checkWrongSyntax("update Foo add 'a' = b");
    checkRightSyntax("update Foo add a = 'a'");
    checkWrongSyntax("update Foo put a = b");
    checkRightSyntax("update Foo put a = b, c");
    checkRightSyntax("update Foo put a = 'b', 1.34");
    checkRightSyntax("update Foo put a = 'b', 'c'");
  }

  @Test
  public void testJson() {
    checkRightSyntax("update Foo merge {'a':'b', 'c':{'d':'e'}} where name = 'foo'");
    checkRightSyntax(
        "update Foo content {'a':'b', 'c':{'d':'e', 'f': ['a', 'b', 4]}} where name = 'foo'");
  }

  @Test
  public void testIncrementOld() {
    checkRightSyntax("update  Foo increment a = 2");
  }

  @Test
  public void testIncrement() {
    checkRightSyntax("update  Foo set a += 2");
    printTree("update  Foo set a += 2");
  }

  @Test
  public void testDecrement() {
    checkRightSyntax("update  Foo set a -= 2");
  }

  @Test
  public void testQuotedJson() {
    checkRightSyntax(
        "UPDATE V SET key = \"test\", value = {\"f12\":\"test\\\\\"} UPSERT WHERE key = \"test\"");
  }

  @Test
  public void testTargetMultipleRids() {
    checkRightSyntax("update [#9:0, #9:1] set foo = 'bar'");
  }

  @Test
  public void testDottedTarget() {
    // issue #5397
    checkRightSyntax("update $publishedVersionEdge.row set isPublished = false");
  }

  @Test
  public void testReturnCount() {
    checkRightSyntax("update foo set bar = 1 RETURN COUNT");
    checkRightSyntax("update foo set bar = 1 return count");
  }

  private void printTree(String s) {
    var osql = getParserFor(s);
    try {
      SimpleNode result = osql.parse();

    } catch (ParseException e) {
      e.printStackTrace();
    }
  }

  protected YouTrackDBSql getParserFor(String string) {
    InputStream is = new ByteArrayInputStream(string.getBytes());
    var osql = new YouTrackDBSql(is);
    return osql;
  }
}
