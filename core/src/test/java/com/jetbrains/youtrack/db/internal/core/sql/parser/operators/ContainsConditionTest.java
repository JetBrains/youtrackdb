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
package com.jetbrains.youtrack.db.internal.core.sql.parser.operators;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsCondition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ContainsConditionTest extends DbTestBase {

  @Test
  public void test() {
    var op = new SQLContainsCondition(-1);

    Assert.assertFalse(op.execute(session, null, null));
    Assert.assertFalse(op.execute(session, null, "foo"));

    List<Object> left = new ArrayList<Object>();
    Assert.assertFalse(op.execute(session, left, "foo"));
    Assert.assertFalse(op.execute(session, left, null));

    left.add("foo");
    left.add("bar");

    Assert.assertTrue(op.execute(session, left, "foo"));
    Assert.assertTrue(op.execute(session, left, "bar"));
    Assert.assertFalse(op.execute(session, left, "fooz"));

    left.add(null);
    Assert.assertTrue(op.execute(session, left, null));
  }

  @Test
  public void testIterable() {
    var left =
        new Iterable() {
          private final List<Integer> ls = Arrays.asList(3, 1, 2);

          @Override
          public Iterator iterator() {
            return ls.iterator();
          }
        };

    var right =
        new Iterable() {
          private final List<Integer> ls = Arrays.asList(2, 3);

          @Override
          public Iterator iterator() {
            return ls.iterator();
          }
        };

    var op = new SQLContainsCondition(-1);
    Assert.assertTrue(op.execute(session, left, right));
  }
}
