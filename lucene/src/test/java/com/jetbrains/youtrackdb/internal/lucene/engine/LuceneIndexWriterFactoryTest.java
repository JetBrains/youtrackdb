package com.jetbrains.youtrackdb.internal.lucene.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.io.YTDBIOUtils;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrackdb.internal.lucene.test.BaseLuceneTest;
import java.io.File;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.store.RAMDirectory;
import org.junit.Test;

/**
 *
 */
public class LuceneIndexWriterFactoryTest extends BaseLuceneTest {

  @Test
  public void shouldCreateIndexWriterConfiguredWithMetadataValues() throws Exception {

    var fc = new LuceneIndexWriterFactory();

    // sample metadata json
    var meta = JSONSerializerJackson.INSTANCE.mapFromJson(YTDBIOUtils.readFileAsString(
        new File("./src/test/resources/index_metadata_new.json")));
    var writer = fc.createIndexWriter(new RAMDirectory(), meta,
        new StandardAnalyzer());

    var config = writer.getConfig();
    assertThat(config.getUseCompoundFile()).isFalse();

    assertThat(config.getAnalyzer()).isInstanceOf(StandardAnalyzer.class);

    assertThat(config.getMaxBufferedDocs()).isEqualTo(-1);

    assertThat(config.getRAMPerThreadHardLimitMB()).isEqualTo(1024);
  }
}
