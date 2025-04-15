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

import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLInOperator;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class InOperatorTest {

  @Test
  public void test() {
    var op = new SQLInOperator(-1);

    Assert.assertFalse(op.execute(null, null));
    Assert.assertFalse(op.execute(null, "foo"));
    Assert.assertFalse(op.execute("foo", null));
    Assert.assertFalse(op.execute("foo", "foo"));

    List<Object> list1 = new ArrayList<Object>();
    Assert.assertFalse(op.execute("foo", list1));
    Assert.assertFalse(op.execute(null, list1));
    Assert.assertTrue(op.execute(list1, list1));

    list1.add("a");
    list1.add(1);

    Assert.assertFalse(op.execute("foo", list1));
    Assert.assertTrue(op.execute("a", list1));
    Assert.assertTrue(op.execute(1, list1));

    // TODO
  }
}
