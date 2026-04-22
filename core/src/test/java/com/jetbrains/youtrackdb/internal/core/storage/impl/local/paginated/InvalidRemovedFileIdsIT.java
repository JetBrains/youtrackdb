package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SequentialTest.class)
public class InvalidRemovedFileIdsIT {

  @Test
  public void testRemovedFileIds() throws Exception {
    final var buildDirectory = System.getProperty("buildDirectory", ".");
    final var dbName = InvalidRemovedFileIdsIT.class.getSimpleName();
    final var dbPath = buildDirectory + File.separator + dbName;

    deleteDirectory(new File(dbPath));

    final var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);
    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory, config);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    var db = youTrackDB.open(dbName, "admin", "admin");

    var storage = db.getStorage();
    var writeCache = ((AbstractStorage) storage).getWriteCache();
    var files = writeCache.files();

    Map<String, Integer> filesWithIntIds = new HashMap<>();

    for (var file : files.entrySet()) {
      filesWithIntIds.put(file.getKey(), writeCache.internalFileId(file.getValue().fileId()));
    }

    db.close();
    youTrackDB.close();

    // create file map of v1 binary format because but with incorrect negative file ids is present
    // only there
    try (var fileMap = new RandomAccessFile(new File(dbPath, "name_id_map.cm"), "rw")) {
      // write all existing files so map will be regenerated on open
      for (var entry : filesWithIntIds.entrySet()) {
        writeNameIdEntry(fileMap, entry.getKey(), entry.getValue());
      }

      writeNameIdEntry(fileMap, "c1.cpm", -100);
      writeNameIdEntry(fileMap, "c1.pcl", -100);

      writeNameIdEntry(fileMap, "c2.cpm", -200);
      writeNameIdEntry(fileMap, "c2.pcl", -200);
      writeNameIdEntry(fileMap, "c2.pcl", -400);

      writeNameIdEntry(fileMap, "c3.cpm", -500);
      writeNameIdEntry(fileMap, "c3.pcl", -500);
      writeNameIdEntry(fileMap, "c4.cpm", -500);
      writeNameIdEntry(fileMap, "c4.pcl", -600);
      writeNameIdEntry(fileMap, "c4.cpm", -600);
    }

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory, config);
    db = youTrackDB.open(dbName, "admin", "admin");

    final Schema schema = db.getMetadata().getSchema();
    schema.createClass("c1");
    schema.createClass("c2");
    schema.createClass("c3");
    schema.createClass("c4");

    storage = db.getStorage();
    writeCache = ((AbstractStorage) storage).getWriteCache();

    // Collection names now include a numeric suffix (e.g., "c1_0") after YTDB-615,
    // so we locate each class's files by prefix match rather than hardcoding exact names.
    files = writeCache.files();
    final Set<Long> ids = new HashSet<>();

    final var c1_cpm = findFileHandler(files, "c1", ".cpm");
    Assert.assertNotNull("c1 .cpm file must exist", c1_cpm);
    Assert.assertTrue(c1_cpm.fileId() > 0);
    Assert.assertTrue(ids.add(c1_cpm.fileId()));

    final var c1_pcl = findFileHandler(files, "c1", ".pcl");
    Assert.assertNotNull("c1 .pcl file must exist", c1_pcl);
    Assert.assertTrue(c1_pcl.fileId() > 0);
    Assert.assertTrue(ids.add(c1_pcl.fileId()));

    final var c2_cpm = findFileHandler(files, "c2", ".cpm");
    Assert.assertNotNull("c2 .cpm file must exist", c2_cpm);
    Assert.assertTrue(ids.add(c2_cpm.fileId()));

    final var c2_pcl = findFileHandler(files, "c2", ".pcl");
    Assert.assertNotNull("c2 .pcl file must exist", c2_pcl);
    Assert.assertTrue(ids.add(c2_pcl.fileId()));

    final var c3_cpm = findFileHandler(files, "c3", ".cpm");
    Assert.assertNotNull("c3 .cpm file must exist", c3_cpm);
    Assert.assertTrue(c3_cpm.fileId() > 0);
    Assert.assertTrue(ids.add(c3_cpm.fileId()));

    final var c3_pcl = findFileHandler(files, "c3", ".pcl");
    Assert.assertNotNull("c3 .pcl file must exist", c3_pcl);
    Assert.assertTrue(c3_pcl.fileId() > 0);
    Assert.assertTrue(ids.add(c3_pcl.fileId()));

    final var c4_cpm = findFileHandler(files, "c4", ".cpm");
    Assert.assertNotNull("c4 .cpm file must exist", c4_cpm);
    Assert.assertTrue(c4_cpm.fileId() > 0);
    Assert.assertTrue(ids.add(c4_cpm.fileId()));

    final var c4_pcl = findFileHandler(files, "c4", ".pcl");
    Assert.assertNotNull("c4 .pcl file must exist", c4_pcl);
    Assert.assertTrue(c4_pcl.fileId() > 0);
    Assert.assertTrue(ids.add(c4_pcl.fileId()));

    db.close();
    youTrackDB.close();
  }

  /**
   * Finds the FileHandler for a class's data or position-map file by prefix match.
   * Collection names now include a numeric suffix (e.g., "c1_0.pcl"),
   * so exact lookup by bare class name no longer works.
   */
  private static FileHandler findFileHandler(
      Map<String, FileHandler> files, String classPrefix, String extension) {
    // Match "classPrefix_" to avoid false positives (e.g., "c1_" won't match "c10_")
    return files.entrySet().stream()
        .filter(
            e -> e.getKey().startsWith(classPrefix + "_") && e.getKey().endsWith(extension))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private static void writeNameIdEntry(RandomAccessFile file, String name, int fileId)
      throws IOException {
    final var nameSize = StringSerializer.staticGetObjectSize(name);

    var serializedRecord =
        new byte[IntegerSerializer.INT_SIZE + nameSize + LongSerializer.LONG_SIZE];
    IntegerSerializer.serializeLiteral(nameSize, serializedRecord, 0);
    StringSerializer.staticSerialize(name, serializedRecord, IntegerSerializer.INT_SIZE);
    LongSerializer.serializeLiteral(
        fileId, serializedRecord, IntegerSerializer.INT_SIZE + nameSize);

    file.write(serializedRecord);
  }

  private static void deleteDirectory(final File directory) {
    if (directory.exists()) {
      final var files = directory.listFiles();

      if (files != null) {
        for (var file : files) {
          if (file.isDirectory()) {
            deleteDirectory(file);
          } else {
            Assert.assertTrue(file.delete());
          }
        }

        Assert.assertTrue(directory.delete());
      }
    }
  }
}
