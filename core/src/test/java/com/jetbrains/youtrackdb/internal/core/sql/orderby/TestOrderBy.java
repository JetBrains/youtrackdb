package com.jetbrains.youtrackdb.internal.core.sql.orderby;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.Ignore;
import org.junit.Test;

public class TestOrderBy extends DbTestBase {

  @Test
  public void testGermanOrderBy() {
    session.set(BasicDatabaseSession.ATTRIBUTES.LOCALE_COUNTRY, Locale.GERMANY.getCountry());
    session.set(BasicDatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE, Locale.GERMANY.getLanguage());
    session.getMetadata().getSchema().createClass("test");

    session.begin();
    var res1 = session.newEntity("test");
    res1.setProperty("name", "Ähhhh");
    var res2 = session.newEntity("test");
    res2.setProperty("name", "Ahhhh");
    var res3 = session.newEntity("test");
    res3.setProperty("name", "Zebra");
    session.commit();

    session.begin();
    var queryRes =
        session.query("select from test order by name").stream().collect(Collectors.toList());
    assertEquals(queryRes.get(0).getIdentity(), res2.getIdentity());
    assertEquals(queryRes.get(1).getIdentity(), res1.getIdentity());
    assertEquals(queryRes.get(2).getIdentity(), res3.getIdentity());

    queryRes =
        session.query("select from test order by name desc ").stream().collect(Collectors.toList());
    assertEquals(queryRes.get(0).getIdentity(), res3.getIdentity());
    assertEquals(queryRes.get(1).getIdentity(), res1.getIdentity());
    assertEquals(queryRes.get(2).getIdentity(), res2.getIdentity());
    session.commit();
  }


  @Test
  @Ignore
  public void testGermanOrderByIndex() {
    session.set(BasicDatabaseSession.ATTRIBUTES.LOCALE_COUNTRY, Locale.GERMANY.getCountry());
    session.set(BasicDatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE, Locale.GERMANY.getLanguage());

    var clazz = session.getMetadata().getSchema().createClass("test");
    clazz.createProperty("name", PropertyType.STRING)
        .createIndex(INDEX_TYPE.NOTUNIQUE);
    var res1 = session.newEntity("test");
    res1.setProperty("name", "Ähhhh");
    var res2 = session.newEntity("test");
    res2.setProperty("name", "Ahhhh");
    var res3 = session.newEntity("test");
    res3.setProperty("name", "Zebra");
    var queryRes =
        session.query("select from test order by name").stream().collect(Collectors.toList());
    assertEquals(queryRes.get(0).getIdentity(), res2.getIdentity());
    assertEquals(queryRes.get(1).getIdentity(), res1.getIdentity());
    assertEquals(queryRes.get(2).getIdentity(), res3.getIdentity());

    queryRes =
        session.query("select from test order by name desc ").stream().collect(Collectors.toList());
    assertEquals(queryRes.get(0), res3);
    assertEquals(queryRes.get(1), res1);
    assertEquals(queryRes.get(2), res2);
  }
}
