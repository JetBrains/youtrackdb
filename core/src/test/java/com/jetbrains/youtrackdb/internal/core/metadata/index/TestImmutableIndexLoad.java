package com.jetbrains.youtrackdb.internal.core.metadata.index;

import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Test;

public class TestImmutableIndexLoad {

  @Test
  public void testLoadAndUseIndexOnOpen() {
    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDB.create(TestImmutableIndexLoad.class.getSimpleName(), DatabaseType.DISK,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    var db =
        youTrackDB.open(
            TestImmutableIndexLoad.class.getSimpleName(),
            "admin",
            DbTestBase.ADMIN_PASSWORD);
    var one = db.getSchema().createClass("One");
    var property = one.createProperty("one", PropertyType.STRING);
    property.createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
    db.close();
    youTrackDB.close();

    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CREATE_DEFAULT_USERS.getKey(), false);
    youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPathStr(getClass()),
            config);
    db =
        youTrackDB.open(
            TestImmutableIndexLoad.class.getSimpleName(),
            "admin",
            DbTestBase.ADMIN_PASSWORD);
    var tx = db.begin();
    var doc = (EntityImpl) tx.newEntity("One");
    doc.setProperty("one", "a");
    tx.commit();
    try {
      tx = db.begin();
      var doc1 = (EntityImpl) tx.newEntity("One");
      doc1.setProperty("one", "a");
      tx.commit();
      fail("It should fail the unique index");
    } catch (RecordDuplicatedException e) {
      // EXPEXTED
    }
    db.close();
    youTrackDB.drop(TestImmutableIndexLoad.class.getSimpleName());
    youTrackDB.close();
  }
}
