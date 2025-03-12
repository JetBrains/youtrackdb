package com.jetbrains.youtrack.db.internal.core.sql.orderby;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.Ignore;
import org.junit.Test;

public class TestOrderBy extends DbTestBase {

  @Test
  public void testGermanOrderBy() {
    session.set(DatabaseSession.ATTRIBUTES.LOCALE_COUNTRY, Locale.GERMANY.getCountry());
    session.set(DatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE, Locale.GERMANY.getLanguage());
    session.getMetadata().getSchema().createClass("test");

    session.begin();
    var res1 = (DBRecord) ((EntityImpl) session.newEntity("test")).field("name", "Ähhhh");
    var res2 = (DBRecord) ((EntityImpl) session.newEntity("test")).field("name", "Ahhhh");
    var res3 = (DBRecord) ((EntityImpl) session.newEntity("test")).field("name", "Zebra");
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
    session.set(DatabaseSession.ATTRIBUTES.LOCALE_COUNTRY, Locale.GERMANY.getCountry());
    session.set(DatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE, Locale.GERMANY.getLanguage());
    var clazz = session.getMetadata().getSchema().createClass("test");
    clazz.createProperty("name", PropertyType.STRING)
        .createIndex(INDEX_TYPE.NOTUNIQUE);
    var res1 = (DBRecord) ((EntityImpl) session.newEntity("test")).field("name", "Ähhhh");
    var res2 = (DBRecord) ((EntityImpl) session.newEntity("test")).field("name", "Ahhhh");
    var res3 = (DBRecord) ((EntityImpl) session.newEntity("test")).field("name", "Zebra");
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
