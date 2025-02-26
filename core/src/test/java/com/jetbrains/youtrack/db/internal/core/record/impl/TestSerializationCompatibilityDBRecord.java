package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import org.junit.Test;

public class TestSerializationCompatibilityDBRecord extends DbTestBase {

  @Test
  public void testDataNotMatchSchema() {
    var klass =
        (SchemaClassInternal) session.getMetadata()
            .getSchema()
            .createClass("Test", session.getMetadata().getSchema().getClass("V"));
    session.begin();
    var entity = session.newEntity("Test");
    var map = session.newLinkMap();
    map.put("some", new RecordId(10, 20));
    entity.setLinkMap("map", map);
    var id = entity.getIdentity();
    session.commit();

    klass.createProperty(session, "map", PropertyType.EMBEDDEDMAP,
        (PropertyType) null, true);

    session.begin();
    var record = session.loadEntity(id);
    // Force deserialize + serialize;
    record.setProperty("some", "aa");
    session.commit();

    session.begin();
    EntityImpl record1 = session.load(id);
    assertEquals(PropertyType.LINKMAP, record1.getPropertyType("map"));
    assertEquals("aa", record1.getString("some"));
    session.commit();
  }
}
