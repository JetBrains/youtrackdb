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

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLEqualsCompareOperator;
import java.math.BigDecimal;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class EqualsCompareOperatorTest {

  @Test
  public void test() {
    var op = new SQLEqualsCompareOperator(-1);

    Assert.assertFalse(op.execute(null, 1));
    Assert.assertFalse(op.execute(1, null));
    Assert.assertFalse(op.execute(null, null));

    Assert.assertTrue(op.execute(1, 1));
    Assert.assertFalse(op.execute(1, 0));
    Assert.assertFalse(op.execute(0, 1));

    Assert.assertFalse(op.execute("aaa", "zzz"));
    Assert.assertFalse(op.execute("zzz", "aaa"));
    Assert.assertTrue(op.execute("aaa", "aaa"));

    Assert.assertFalse(op.execute(1, 1.1));
    Assert.assertFalse(op.execute(1.1, 1));

    Assert.assertTrue(op.execute(BigDecimal.ONE, 1));
    Assert.assertTrue(op.execute(1, BigDecimal.ONE));

    Assert.assertFalse(op.execute(1.1, BigDecimal.ONE));
    Assert.assertFalse(op.execute(2, BigDecimal.ONE));

    Assert.assertFalse(op.execute(BigDecimal.ONE, 0.999999));
    Assert.assertFalse(op.execute(BigDecimal.ONE, 0));

    Assert.assertFalse(op.execute(BigDecimal.ONE, 2));
    Assert.assertFalse(op.execute(BigDecimal.ONE, 1.0001));

    Assert.assertTrue(op.execute(new RecordId(1, 10), new RecordId((short) 1, 10)));
    Assert.assertFalse(op.execute(new RecordId(1, 10), new RecordId((short) 1, 20)));

    Assert.assertFalse(op.execute(new Object(), new Object()));
  }
}
