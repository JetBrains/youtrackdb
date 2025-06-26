/*
 *
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrack.db.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import java.util.Map;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneCreateJavaApiTest extends LuceneBaseTest {

  @Before
  public void init() {
    var song = session.createVertexClass("Song");
    song.createProperty("title", PropertyType.STRING);
    song.createProperty("author", PropertyType.STRING);
    song.createProperty("description", PropertyType.STRING);
  }

  @Test
  public void testCreateIndex() {
    Schema schema = session.getMetadata().getSchema();

    var song = schema.getClass("Song");

    var meta = Map.<String, Object>of("analyzer", StandardAnalyzer.class.getName());

    song.createIndex(
        "Song.title",
        SchemaClass.INDEX_TYPE.FULLTEXT.toString(),
        null,
        meta,
        "LUCENE", new String[]{"title"});

    var lucene = session.getIndex("Song.title");
    assertThat(lucene).isNotNull();

    assertThat(lucene.getMetadata().containsKey("analyzer")).isTrue();

    assertThat(lucene.getMetadata().get("analyzer"))
        .isEqualTo(StandardAnalyzer.class.getName());
  }

  @Test
  public void testCreateIndexCompositeWithDefaultAnalyzer() {
    Schema schema = session.getMetadata().getSchema();
    var song = schema.getClass("Song");

    song.createIndex(
        "Song.author_description",
        SchemaClass.INDEX_TYPE.FULLTEXT.toString(),
        null,
        null,
        "LUCENE", new String[]{"author", "description"});

    var lucene = session.getIndex("Song.author_description");
    assertThat(lucene).isNotNull();

    assertThat(lucene.getMetadata().containsKey("analyzer")).isTrue();

    assertThat(lucene.getMetadata().get("analyzer"))
        .isEqualTo(StandardAnalyzer.class.getName());
  }
}
