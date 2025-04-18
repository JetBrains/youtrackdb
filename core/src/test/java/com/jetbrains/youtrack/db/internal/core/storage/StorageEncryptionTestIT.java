package com.jetbrains.youtrack.db.internal.core.storage;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.common.BasicYouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.File;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Assert;
import org.junit.Test;

public class StorageEncryptionTestIT {

  @Test
  public void testEncryption() {
    final var dbDirectoryFile = cleanAndGetDirectory();

    final var youTrackDBConfig =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_ENCRYPTION_KEY,
                "T1JJRU5UREJfSVNfQ09PTA==")
            .build();
    try (final var youTrackDB =
        YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()), youTrackDBConfig)) {
      youTrackDB.execute(
          "create database encryption disk users ( admin identified by 'admin' role admin)");
      try (var session = (DatabaseSessionInternal) youTrackDB.open("encryption", "admin",
          "admin")) {
        final Schema schema = session.getMetadata().getSchema();
        final var cls = schema.createClass("EncryptedData");
        cls.createProperty("id", PropertyType.INTEGER);
        cls.createProperty("value", PropertyType.STRING);

        cls.createIndex("EncryptedTree", SchemaClass.INDEX_TYPE.UNIQUE, "id");
        cls.createIndex("EncryptedHash", SchemaClass.INDEX_TYPE.UNIQUE, "id");

        for (var i = 0; i < 10_000; i++) {
          final var document = ((EntityImpl) session.newEntity(cls));
          document.setProperty("id", i);
          document.setProperty(
              "value",
              "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor"
                  + " incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis"
                  + " nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat."
                  + " ");

        }

        final Random random = ThreadLocalRandom.current();
        for (var i = 0; i < 1_000; i++) {
          try (var resultSet =
              session.query("select from EncryptedData where id = ?", random.nextInt(10_000_000))) {
            if (resultSet.hasNext()) {
              final var result = resultSet.next();
              result.asEntity().delete();
            }
          }
        }
      }
    }

    try (final var youTrackDB =
        YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(getClass()), YouTrackDBConfig.defaultConfig())) {
      try {
        try (final var session = youTrackDB.open("encryption", "admin", "admin")) {
          Assert.fail();
        }
      } catch (Exception e) {
        // ignore
      }
    }

    final var wrongKeyOneYouTrackDBConfig =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(
                GlobalConfiguration.STORAGE_ENCRYPTION_KEY,
                "DD0ViGecppQOx4ijWL4XGBwun9NAfbqFaDnVpn9+lj8=")
            .build();
    try (final var youTrackDB =
        YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()),
            wrongKeyOneYouTrackDBConfig)) {
      try {
        try (final var session = youTrackDB.open("encryption", "admin", "admin")) {
          Assert.fail();
        }
      } catch (Exception e) {
        // ignore
      }
    }

    final var wrongKeyTwoYouTrackDBConfig =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(
                GlobalConfiguration.STORAGE_ENCRYPTION_KEY,
                "DD0ViGecppQOx4ijWL4XGBwun9NAfbqFaDnVpn9+lj8")
            .build();
    try (final var youTrackDB =
        YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()),
            wrongKeyTwoYouTrackDBConfig)) {
      try {
        try (final var session = youTrackDB.open("encryption", "admin", "admin")) {
          Assert.fail();
        }
      } catch (Exception e) {
        // ignore
      }
    }

    try (final var youTrackDB =
        YourTracks.embedded(DbTestBase.embeddedDBUrl(getClass()), youTrackDBConfig)) {
      try (final var session =
          (DatabaseSessionEmbedded) youTrackDB.open("encryption", "admin", "admin")) {
        final var indexManager = session.getSharedContext().getIndexManager();
        final var treeIndex = indexManager.getIndex(session, "EncryptedTree");
        final var hashIndex = indexManager.getIndex(session, "EncryptedHash");

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
      }
    }
  }

  private File cleanAndGetDirectory() {
    final var dbDirectory =
        "./target/databases" + File.separator + StorageEncryptionTestIT.class.getSimpleName();
    final var dbDirectoryFile = new File(dbDirectory);
    FileUtils.deleteRecursively(dbDirectoryFile);
    return dbDirectoryFile;
  }

  @Test
  public void testEncryptionSingleDatabase() {
    final var dbDirectoryFile = cleanAndGetDirectory();

    try (final var youTrackDB =
        YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(getClass()), YouTrackDBConfig.defaultConfig())) {
      final var youTrackDBConfig =
          YouTrackDBConfig.builder()
              .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_ENCRYPTION_KEY,
                  "T1JJRU5UREJfSVNfQ09PTA==")
              .build();

      youTrackDB.execute(
          "create database encryption disk users ( admin identified by 'admin' role admin)");
    }
    try (final var youTrackDB =
        YourTracks.embedded(
            DbTestBase.getBaseDirectoryPath(getClass()), YouTrackDBConfig.defaultConfig())) {
      final var youTrackDBConfig =
          (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
              .addGlobalConfigurationParameter(GlobalConfiguration.STORAGE_ENCRYPTION_KEY,
                  "T1JJRU5UREJfSVNfQ09PTA==")
              .build();
      try (var db =
          (DatabaseSessionInternal) youTrackDB.open("encryption", "admin", "admin",
              youTrackDBConfig)) {
        final Schema schema = db.getMetadata().getSchema();
        final var cls = schema.createClass("EncryptedData");

        final var document = ((EntityImpl) db.newEntity(cls));
        document.setProperty("id", 10);
        document.setProperty(
            "value",
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor"
                + " incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis"
                + " nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat."
                + " ");

        try (var resultSet = db.query("select from EncryptedData where id = ?", 10)) {
          assertTrue(resultSet.hasNext());
        }
      }
    }
  }
}
