package com.jetbrains.youtrackdb.internal.core.sql.functions.sql;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.query.LegacyResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class FunctionSqlTest extends DbTestBase {

  @Test
  public void functionSqlWithParameters() {
    session.getMetadata().getSchema().createClass("Test");

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("Test"));
    doc1.setProperty("name", "Enrico");
    doc1 = ((EntityImpl) session.newEntity("Test"));
    doc1.setProperty("name", "Luca");
    session.commit();

    session.begin();
    var function = new Function(session);
    function.setName("test");
    function.setCode("select from Test where name = :name");
    function.setParameters(
        new ArrayList<>(List.of("name")));
    function.save(session);
    session.commit();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    session.begin();
    var result = function.executeInContext(context, "Enrico");
    Assert.assertEquals(1, ((LegacyResultSet<?>) result).size());
    session.commit();
  }

  @Test
  public void functionSqlWithInnerFunctionJs() {

    session.getMetadata().getSchema().createClass("Test");
    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("Test"));
    doc1.setProperty("name", "Enrico");
    doc1 = ((EntityImpl) session.newEntity("Test"));
    doc1.setProperty("name", "Luca");
    session.commit();

    session.begin();
    var function = new Function(session);
    function.setName("test");
    function.setCode(
        "select name from Test where name = :name and hello(:name) = 'Hello Enrico'");
    function.setParameters(
        new ArrayList<>(List.of("name")));
    function.save(session);
    session.commit();

    session.begin();
    var function1 = new Function(session);
    function1.setName("hello");
    function1.setLanguage("javascript");
    function1.setCode("return 'Hello ' + name");
    function1.setParameters(
        new ArrayList<>(List.of("name")));
    function1.save(session);
    session.commit();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    session.begin();
    var result = function.executeInContext(context, "Enrico");
    Assert.assertEquals(1, ((LegacyResultSet) result).size());
    session.commit();
  }
}
