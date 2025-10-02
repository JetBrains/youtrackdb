package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import org.junit.Assert;
import org.junit.Test;

public class TestSerializationCompatibilityDBRecord extends DbTestBase {

  @Test
  public void testDataNotMatchSchema() {
    var klass = session.getMetadata()
        .getSlowMutableSchema()
        .createClass("Test", session.getMetadata().getSlowMutableSchema().getClass("V"));
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

    klass.createProperty("map", PropertyTypeInternal.EMBEDDEDMAP,
        (PropertyTypeInternal) null);

    session.begin();
    session.setValidationEnabled(false);
    var record = session.loadEntity(id);
    // Force deserialize + serialize;
    record.setProperty("some", "aa");
    session.commit();

    session.setValidationEnabled(true);

    session.begin();
    EntityImpl record1 = session.load(id);

    Assert.assertTrue(record1.getProperty("map") instanceof EntityLinkMapIml);
    assertEquals(PropertyType.LINKMAP, record1.getPropertyType("map"));
    assertEquals("aa", record1.getString("some"));
    session.commit();
  }
}
