/*
 *
 *  *  Copyright YouTrackDB
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Test;

public class CommandExecutorSQLCreateDBSequenceTest extends DbTestBase {

  @Test
  public void testSimple() {
    session.execute("CREATE SEQUENCE Sequence1 TYPE ORDERED").close();

    var results =
        session.query("select sequence('Sequence1').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(1L);
    }
    results =
        session.query("select sequence('Sequence1').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(2L);
    }
    results =
        session.query("select sequence('Sequence1').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(3L);
    }
  }

  @Test
  public void testIncrement() {
    session.execute("CREATE SEQUENCE SequenceIncrement TYPE ORDERED INCREMENT 3").close();
    var results =
        session.query("select sequence('SequenceIncrement').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(3L);
    }
    results =
        session.query("select sequence('SequenceIncrement').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(6L);
    }
    results =
        session.query("select sequence('SequenceIncrement').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(9L);
    }
  }

  @Test
  public void testStart() {
    session.execute("CREATE SEQUENCE SequenceStart TYPE ORDERED START 3").close();

    var results =
        session.query("select sequence('SequenceStart').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(4L);
    }
    results =
        session.query("select sequence('SequenceStart').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(5L);
    }
    results =
        session.query("select sequence('SequenceStart').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(6L);
    }
  }

  @Test
  public void testStartIncrement() {
    session.execute("CREATE SEQUENCE SequenceStartIncrement TYPE ORDERED START 3 INCREMENT 10")
        .close();

    var results =
        session.query("select sequence('SequenceStartIncrement').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(13L);
    }
    results =
        session.query("select sequence('SequenceStartIncrement').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(23L);
    }
    results =
        session.query("select sequence('SequenceStartIncrement').next() as val").toList();
    assertEquals(1, results.size());
    for (var result : results) {
      assertThat(result.<Long>getProperty("val")).isEqualTo(33L);
    }
  }
}
