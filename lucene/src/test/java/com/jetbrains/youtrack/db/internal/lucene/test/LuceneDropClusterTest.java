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

package com.jetbrains.youtrack.db.internal.lucene.test;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import java.util.logging.Level;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.assertj.core.api.Assertions;
import org.junit.Test;

/**
 *
 */
public class LuceneDropClusterTest extends BaseLuceneTest {

  @Test
  public void shouldRemoveCluster() throws Exception {
    LogManager.instance().setConsoleLevel(Level.FINE.getName());
    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");

    session.execute("sql", getScriptFromStream(stream)).close();

    session.command(
            "create index Song.title on Song (title) FULLTEXT ENGINE LUCENE METADATA"
                + " {\"default\":\""
                + StandardAnalyzer.class.getName()
                + "\"}")
        .close();
    session.command(
            "create index Song.author on Song (author) FULLTEXT ENGINE LUCENE METADATA"
                + " {\"default\":\""
                + StandardAnalyzer.class.getName()
                + "\"}")
        .close();

    var metadata = session.getMetadata();

    session.begin();
    var initialIndexSize =
        metadata.getIndexManagerInternal().getIndex(session, "Song.title").getInternal().size(
            session);

    var clusterIds = metadata.getSchema().getClass("Song").getClusterIds();
    session.commit();

    session.dropCluster(clusterIds[1]);

    var afterDropIndexSize =
        metadata.getIndexManagerInternal().getIndex(session, "Song.title").getInternal().size(
            session);

    Assertions.assertThat(afterDropIndexSize).isLessThan(initialIndexSize);
  }
}
