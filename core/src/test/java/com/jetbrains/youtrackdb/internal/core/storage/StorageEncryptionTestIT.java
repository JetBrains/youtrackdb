package com.jetbrains.youtrackdb.internal.core.storage;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.io.File;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class StorageEncryptionTestIT {

  @Before
  public void before() {
    FileUtils.deleteRecursively(new File(DbTestBase.getBaseDirectoryPath(getClass())));
  }

  @Test
  public void testEncryption() {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_ENCRYPTION_KEY.getKey(),
        "T1JJRU5UREJfSVNfQ09PTA==");
    try (final var youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
            config)) {
      youTrackDB.createIfNotExists(StorageEncryptionTestIT.class.getSimpleName(), DatabaseType.DISK,
          config,
          "admin", "admin", "admin");
      try (var session = (DatabaseSessionInternal) youTrackDB.open(
          StorageEncryptionTestIT.class.getSimpleName(), "admin",
          "admin")) {
        final var schema = session.getSchema();
        final var cls = schema.createClass("EncryptedData");
        cls.createProperty("id", PropertyType.INTEGER);
        cls.createProperty("value", PropertyType.STRING);

        cls.createIndex("EncryptedTree", SchemaClass.INDEX_TYPE.UNIQUE, "id");
        cls.createIndex("EncryptedHash", SchemaClass.INDEX_TYPE.UNIQUE, "id");

        var tx = session.begin();
        for (var i = 0; i < 10_000; i++) {
          final var document = tx.newEntity(cls);
          document.setProperty("id", i);
          document.setProperty(
              "value",
              "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor"
                  + " incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis"
                  + " nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat."
                  + " ");

        }
        tx.commit();

        tx = session.begin();
        final Random random = ThreadLocalRandom.current();
        for (var i = 0; i < 1_000; i++) {
          try (var resultSet =
              tx.query("select from EncryptedData where id = ?", random.nextInt(10_000_000))) {
            if (resultSet.hasNext()) {
              final var result = resultSet.next();
              result.asEntity().delete();
            }
          }
        }
        tx.commit();
      }
    }

    try (final var youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPath(getClass()))) {
      try {
        try (final var ignored = youTrackDB.open(StorageEncryptionTestIT.class.getSimpleName(),
            "admin", "admin")) {
          Assert.fail();
        }
      } catch (Exception e) {
        // ignore
      }
    }

    var wrongConfig = new BaseConfiguration();
    wrongConfig.setProperty(GlobalConfiguration.STORAGE_ENCRYPTION_KEY.getKey(),
        "T1JJRU5UREJfSVNfQ09PTA==");
    try (final var youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
            wrongConfig)) {
      try {
        try (final var ignored = youTrackDB.open(StorageEncryptionTestIT.class.getSimpleName(),
            "admin", "admin")) {
          Assert.fail();
        }
      } catch (Exception e) {
        // ignore
      }
    }

    var wrongKeyTwoConfig = new BaseConfiguration();
    wrongKeyTwoConfig.setProperty(GlobalConfiguration.STORAGE_ENCRYPTION_KEY.getKey(),
        "DD0ViGecppQOx4ijWL4XGBwun9NAfbqFaDnVpn9+lj8");
    try (final var youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
            wrongKeyTwoConfig)) {
      try {
        try (final var ignored = youTrackDB.open(StorageEncryptionTestIT.class.getSimpleName()
            , "admin", "admin")) {
          Assert.fail();
        }
      } catch (Exception e) {
        // ignore
      }
    }

    try (final var youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()),
            config)) {
      try (final var session =
          (DatabaseSessionEmbedded) youTrackDB.open(StorageEncryptionTestIT.class.getSimpleName(),
              "admin", "admin")) {
        final var indexManager = session.getSharedContext().getIndexManager();
        final var treeIndex = indexManager.getIndex("EncryptedTree");
        final var hashIndex = indexManager.getIndex("EncryptedHash");

        session.executeInTx(tx -> {
          var entityIterator = session.browseClass("EncryptedData");
          while (entityIterator.hasNext()) {
            final var entity = entityIterator.next();
            final int id = entity.getProperty("id");
            final RID treeRid;
            try (var rids = treeIndex.getRids(session, id)) {
              treeRid = rids.findFirst().orElse(null);
            }
            final RID hashRid;
            try (var rids = hashIndex.getRids(session, id)) {
              hashRid = rids.findFirst().orElse(null);
            }

            Assert.assertEquals(entity.getIdentity(), treeRid);
            Assert.assertEquals(entity.getIdentity(), hashRid);
          }

          Assert.assertEquals(session.countClass("EncryptedData"),
              treeIndex.size(session));
          Assert.assertEquals(session.countClass("EncryptedData"),
              hashIndex.size(session));
        });
      }
    }
  }

  @Test
  public void testEncryptionSingleDatabase() {
    try (final var youTrackDB =
        YourTracks.instance(
            DbTestBase.getBaseDirectoryPath(getClass()))) {
      youTrackDB.createIfNotExists(StorageEncryptionTestIT.class.getSimpleName(), DatabaseType.DISK,
          "admin", "admin", "admin");
    }
    try (final var youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(
            DbTestBase.getBaseDirectoryPath(getClass()))) {
      final var youTrackDBConfig =
          (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
              .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_ENCRYPTION_KEY,
                  "T1JJRU5UREJfSVNfQ09PTA==")
              .build();
      try (var session =
          (DatabaseSessionInternal) youTrackDB.open(StorageEncryptionTestIT.class.getSimpleName(),
              "admin", "admin",
              youTrackDBConfig)) {
        final var schema = session.getSchema();
        final var cls = schema.createClass("EncryptedData");

        var tx = session.begin();
        final var document = tx.newEntity(cls);
        document.setProperty("id", 10);
        document.setProperty(
            "value",
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor"
                + " incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis"
                + " nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat."
                + " ");
        tx.commit();
      }

      try (var session =
          youTrackDB.open(StorageEncryptionTestIT.class.getSimpleName(), "admin", "admin",
              youTrackDBConfig)) {
        var tx = session.begin();
        try (var resultSet = tx.query("select from EncryptedData where id = ?", 10)) {
          assertTrue(resultSet.hasNext());
        }
        tx.commit();
      }
    }
  }
}
