package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class PatternTest extends ParserTestAbstract {

  @Test
  public void testSimplePattern() {
    var query = "MATCH {as:a, class:Person} return a";
    var parser = getParserFor(query);
    try {
      var stm = (SQLMatchStatement) parser.parse();
      stm.setContext(getContext());
      stm.buildPatterns();
      var pattern = stm.pattern;
      Assert.assertEquals(0, pattern.getNumOfEdges());
      Assert.assertEquals(1, pattern.getAliasToNode().size());
      Assert.assertNotNull(pattern.getAliasToNode().get("a"));
      Assert.assertEquals(1, pattern.getDisjointPatterns().size());
    } catch (ParseException e) {
      Assert.fail();
    }
  }

  @Test
  public void testCartesianProduct() {
    var query = "MATCH {as:a, class:Person}, {as:b, class:Person} return a, b";
    var parser = getParserFor(query);
    try {
      var stm = (SQLMatchStatement) parser.parse();
      stm.setContext(getContext());
      stm.buildPatterns();
      var pattern = stm.pattern;
      Assert.assertEquals(0, pattern.getNumOfEdges());
      Assert.assertEquals(2, pattern.getAliasToNode().size());
      Assert.assertNotNull(pattern.getAliasToNode().get("a"));
      var subPatterns = pattern.getDisjointPatterns();
      Assert.assertEquals(2, subPatterns.size());
      Assert.assertEquals(0, subPatterns.get(0).getNumOfEdges());
      Assert.assertEquals(1, subPatterns.get(0).getAliasToNode().size());
      Assert.assertEquals(0, subPatterns.get(1).getNumOfEdges());
      Assert.assertEquals(1, subPatterns.get(1).getAliasToNode().size());

      Set<String> aliases = new HashSet<>();
      aliases.add("a");
      aliases.add("b");
      aliases.remove(subPatterns.get(0).getAliasToNode().keySet().iterator().next());
      aliases.remove(subPatterns.get(1).getAliasToNode().keySet().iterator().next());
      Assert.assertEquals(0, aliases.size());

    } catch (ParseException e) {
      Assert.fail();
    }
  }

  @Test
  public void testComplexCartesianProduct() {
    var query =
        "MATCH {as:a, class:Person}-->{as:b}, {as:c, class:Person}-->{as:d}-->{as:e}, {as:d,"
            + " class:Foo}-->{as:f} return a, b";
    var parser = getParserFor(query);
    try {
      var stm = (SQLMatchStatement) parser.parse();
      stm.setContext(getContext());
      stm.buildPatterns();
      var pattern = stm.pattern;
      Assert.assertEquals(4, pattern.getNumOfEdges());
      Assert.assertEquals(6, pattern.getAliasToNode().size());
      Assert.assertNotNull(pattern.getAliasToNode().get("a"));
      var subPatterns = pattern.getDisjointPatterns();
      Assert.assertEquals(2, subPatterns.size());

      Set<String> aliases = new HashSet<>();
      aliases.add("a");
      aliases.add("b");
      aliases.add("c");
      aliases.add("d");
      aliases.add("e");
      aliases.add("f");
      aliases.removeAll(subPatterns.get(0).getAliasToNode().keySet());
      aliases.removeAll(subPatterns.get(1).getAliasToNode().keySet());
      Assert.assertEquals(0, aliases.size());

    } catch (ParseException e) {
      Assert.fail();
    }
  }

  private CommandContext getContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    return ctx;
  }
}
