package com.jetbrains.youtrack.db.internal.lucene.engine;

import static org.apache.lucene.index.IndexWriterConfig.OpenMode.CREATE_OR_APPEND;

import java.io.IOException;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;

/**
 *
 */
public class LuceneIndexWriterFactory {

  public IndexWriter createIndexWriter(Directory dir, Map<String, ?> metadata, Analyzer analyzer)
      throws IOException {

    var config = createIndexWriterConfig(metadata, analyzer);

    return new IndexWriter(dir, config);
  }

  public IndexWriterConfig createIndexWriterConfig(Map<String, ?> metadata, Analyzer analyzer) {
    var config = new IndexWriterConfig(analyzer);

    config.setOpenMode(CREATE_OR_APPEND);

    if (metadata.containsKey("use_compound_file")) {
      config.setUseCompoundFile((Boolean) metadata.get("use_compound_file"));
    }

    if (metadata.containsKey("ram_buffer_MB")) {
      config.setRAMBufferSizeMB(Double.parseDouble(metadata.get("ram_buffer_MB").toString()));
    }

    if (metadata.containsKey("max_buffered_docs")) {
      config.setMaxBufferedDocs(Integer.parseInt(metadata.get("max_buffered_docs").toString()));
    }

    // TODO REMOVED

    //    if (metadata.containsField("max_buffered_delete_terms"))
    //
    // config.setMaxBufferedDeleteTerms(Integer.valueOf(metadata.<String>field("max_buffered_delete_terms")));

    if (metadata.containsKey("ram_per_thread_MB")) {
      config.setRAMPerThreadHardLimitMB(
          Integer.parseInt(metadata.get("ram_per_thread_MB").toString()));
    }

    return config;
  }
}
