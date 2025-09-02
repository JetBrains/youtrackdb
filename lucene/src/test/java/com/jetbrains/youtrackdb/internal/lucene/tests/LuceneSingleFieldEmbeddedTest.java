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

package com.jetbrains.youtrackdb.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneSingleFieldEmbeddedTest extends LuceneBaseTest {

  @Before
  public void init() {
    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");

    session.computeScript("sql", getScriptFromStream(stream));

    session.execute("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE");
    session.execute("create index Song.author on Song (author) FULLTEXT ENGINE LUCENE");
  }

  @Test
  public void loadAndTest() {

    session.begin();
    var docs =
        session.query(
            "select * from Song where search_fields(['title'],\"(title:mountain)\")=true");

    assertThat(docs).hasSize(4);
    docs.close();

    docs = session.query(
        "select * from Song where search_fields(['author'],\"(author:Fabbio)\")=true");

    assertThat(docs).hasSize(87);
    docs.close();
    docs =
        session.query(
            "select * from Song where search_fields(['title'],\"(title:mountain)\")=true  and"
                + " search_fields(['author'],\"(author:Fabbio)\")=true");

    assertThat(docs).hasSize(1);
    docs.close();
    session.commit();
  }
}
