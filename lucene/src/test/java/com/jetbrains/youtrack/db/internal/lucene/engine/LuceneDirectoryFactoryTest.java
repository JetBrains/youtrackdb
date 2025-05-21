package com.jetbrains.youtrack.db.internal.lucene.engine;

import static com.jetbrains.youtrack.db.internal.lucene.engine.LuceneDirectoryFactory.DIRECTORY_MMAP;
import static com.jetbrains.youtrack.db.internal.lucene.engine.LuceneDirectoryFactory.DIRECTORY_NIO;
import static com.jetbrains.youtrack.db.internal.lucene.engine.LuceneDirectoryFactory.DIRECTORY_RAM;
import static com.jetbrains.youtrack.db.internal.lucene.engine.LuceneDirectoryFactory.DIRECTORY_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.lucene.test.BaseLuceneTest;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class LuceneDirectoryFactoryTest extends BaseLuceneTest {

  private LuceneDirectoryFactory fc;
  private Map<String, Object> meta;
  private IndexDefinition indexDef;

  @Before
  public void setUp() throws Exception {
    meta = new HashMap<>();
    indexDef = Mockito.mock(IndexDefinition.class);
    when(indexDef.getFields()).thenReturn(Collections.emptyList());
    when(indexDef.getClassName()).thenReturn("Song");
    fc = new LuceneDirectoryFactory();
  }

  @Override
  protected DatabaseType calculateDbType() {
    return DatabaseType.DISK;
  }

  @Test
  public void shouldCreateNioFsDirectory() {
    meta.put(DIRECTORY_TYPE, DIRECTORY_NIO);
    var directory = fc.createDirectory(session.getStorage(), "index.name", meta).getDirectory();
    assertThat(directory).isInstanceOf(NIOFSDirectory.class);
    assertThat(new File(
        getBaseDirectoryPath(getClass()) + File.separator + databaseName
            + "/luceneIndexes/index.name"))
        .exists();
  }

  @Test
  public void shouldCreateMMapFsDirectory() throws Exception {
    meta.put(DIRECTORY_TYPE, DIRECTORY_MMAP);
    var directory = fc.createDirectory(session.getStorage(), "index.name", meta).getDirectory();
    assertThat(directory).isInstanceOf(MMapDirectory.class);
    assertThat(new File(
        getBaseDirectoryPath(getClass()) + File.separator + databaseName
            + "/luceneIndexes/index.name"))
        .exists();

  }

  @Test
  public void shouldCreateRamDirectory() {
    meta.put(DIRECTORY_TYPE, DIRECTORY_RAM);
    var directory = fc.createDirectory(session.getStorage(), "index.name", meta).getDirectory();
    assertThat(directory).isInstanceOf(RAMDirectory.class);

  }

  @Test
  public void shouldCreateRamDirectoryOnMemoryDatabase() {
    meta.put(DIRECTORY_TYPE, DIRECTORY_RAM);
    final var directory =
        fc.createDirectory(session.getStorage(), "index.name", meta).getDirectory();
    // 'DatabaseType.MEMORY' and 'DIRECTORY_RAM' determines the RAMDirectory.
    assertThat(directory).isInstanceOf(RAMDirectory.class);
  }

  @Test
  public void shouldCreateRamDirectoryOnMemoryFromMmapDatabase() {
    meta.put(DIRECTORY_TYPE, DIRECTORY_MMAP);
    var dbName = databaseName + "memory";
    context.execute(
        "create database "
            + dbName
            + " memory users (admin identified by 'adminpwd' role admin)");
    var db =
        (DatabaseSessionInternal) context.open(dbName, "admin", "adminpwd");
    final var directory =
        fc.createDirectory(db.getStorage(), "index.name", meta).getDirectory();
    // 'DatabaseType.MEMORY' plus 'DIRECTORY_MMAP' leads to the same result as just
    // 'DIRECTORY_RAM'.
    assertThat(directory).isInstanceOf(RAMDirectory.class);
  }
}
