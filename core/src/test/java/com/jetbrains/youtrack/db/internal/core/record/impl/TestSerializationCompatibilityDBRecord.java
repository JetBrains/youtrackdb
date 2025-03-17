package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import org.junit.Assert;
import org.junit.Test;

public class TestSerializationCompatibilityDBRecord extends DbTestBase {

  @Test
  public void testDataNotMatchSchema() {
    var klass =
        (SchemaClassInternal) session.getMetadata()
            .getSchema()
            .createClass("Test", session.getMetadata().getSchema().getClass("V"));
    session.begin();
    var stubEntity = session.newEntity();
    session.commit();

    session.begin();
    var entity = session.newVertex("Test");
    var map = session.newLinkMap();
    map.put("some", stubEntity.getIdentity());
    entity.setLinkMap("map", map);
    var id = entity.getIdentity();
    session.commit();

    klass.createProperty("map", PropertyType.EMBEDDEDMAP,
        (PropertyType) null, true);

    session.begin();
    var record = session.loadEntity(id);
    // Force deserialize + serialize;
    record.setProperty("some", "aa");
    session.commit();

    session.begin();
    EntityImpl record1 = session.load(id);

    Assert.assertTrue(record1.getProperty("map") instanceof LinkMap);
    assertEquals(PropertyType.LINKMAP, record1.getPropertyType("map"));
    assertEquals("aa", record1.getString("some"));
    session.commit();
  }
}
