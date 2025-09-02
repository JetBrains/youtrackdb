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
package com.jetbrains.youtrackdb.internal.core.sql.parser.operators;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeqOperator;
import java.math.BigDecimal;
import org.junit.Assert;
import org.junit.Test;

public class NeqOperatorTest extends DbTestBase {

  @Test
  public void test() {
    var op = new SQLNeqOperator(-1);
    Assert.assertTrue(op.execute(session, null, 1));
    Assert.assertTrue(op.execute(session, 1, null));
    Assert.assertTrue(op.execute(session, null, null));

    Assert.assertFalse(op.execute(session, 1, 1));
    Assert.assertTrue(op.execute(session, 1, 0));
    Assert.assertTrue(op.execute(session, 0, 1));

    Assert.assertTrue(op.execute(session, "aaa", "zzz"));
    Assert.assertTrue(op.execute(session, "zzz", "aaa"));
    Assert.assertFalse(op.execute(session, "aaa", "aaa"));

    Assert.assertTrue(op.execute(session, 1, 1.1));
    Assert.assertTrue(op.execute(session, 1.1, 1));

    Assert.assertFalse(op.execute(session, BigDecimal.ONE, 1));
    Assert.assertFalse(op.execute(session, 1, BigDecimal.ONE));

    Assert.assertTrue(op.execute(session, 1.1, BigDecimal.ONE));
    Assert.assertTrue(op.execute(session, 2, BigDecimal.ONE));

    Assert.assertTrue(op.execute(session, BigDecimal.ONE, 0.999999));
    Assert.assertTrue(op.execute(session, BigDecimal.ONE, 0));

    Assert.assertTrue(op.execute(session, BigDecimal.ONE, 2));
    Assert.assertTrue(op.execute(session, BigDecimal.ONE, 1.0001));

    Assert.assertFalse(op.execute(session, new RecordId(1, 10), new RecordId((short) 1, 10)));
    Assert.assertTrue(op.execute(session, new RecordId(1, 10), new RecordId((short) 1, 20)));

    Assert.assertTrue(op.execute(session, new Object(), new Object()));
  }
}
