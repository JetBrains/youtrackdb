/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;


public class DBRecordBytesTest extends DbTestBase {
  private static final int SMALL_ARRAY = 3;
  private static final int BIG_ARRAY = 7;
  private static final int FULL_ARRAY = 5;
  private InputStream inputStream;
  private InputStream emptyStream;

  private static void assertArrayEquals(byte[] actual, byte[] expected) {
    assert actual.length == expected.length;
    for (var i = 0; i < expected.length; i++) {
      assert actual[i] == expected[i];
    }
  }

  private static Object getFieldValue(Object source, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    final var clazz = source.getClass();
    final var field = getField(clazz, fieldName);
    field.setAccessible(true);
    return field.get(source);
  }

  private static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
    if (clazz == null) {
      throw new NoSuchFieldException(fieldName);
    }
    for (var item : clazz.getDeclaredFields()) {
      if (item.getName().equals(fieldName)) {
        return item;
      }
    }
    return getField(clazz.getSuperclass(), fieldName);
  }

  @Before
  public void setUp() throws Exception {
    inputStream = new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5});
    emptyStream = new ByteArrayInputStream(new byte[]{});

  }

  @Test
  public void testFromInputStream_ReadEmpty() throws Exception {
    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(emptyStream, SMALL_ARRAY);
    Assert.assertEquals(0, result);
    final var source = (byte[]) getFieldValue(blob, "source");
    Assert.assertEquals(0, source.length);
    session.rollback();
  }

  @Test
  public void testFromInputStream_ReadSmall() throws Exception {
    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(inputStream, SMALL_ARRAY);
    Assert.assertEquals(SMALL_ARRAY, result);
    final var source = (byte[]) getFieldValue(blob, "source");
    Assert.assertEquals(SMALL_ARRAY, source.length);
    for (var i = 1; i < SMALL_ARRAY + 1; i++) {
      Assert.assertEquals(source[i - 1], i);
    }
    session.rollback();
  }

  @Test
  public void testFromInputStream_ReadBig() throws Exception {
    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(inputStream, BIG_ARRAY);
    Assert.assertEquals(FULL_ARRAY, result);
    final var source = (byte[]) getFieldValue(blob, "source");
    Assert.assertEquals(FULL_ARRAY, source.length);
    for (var i = 1; i < FULL_ARRAY + 1; i++) {
      Assert.assertEquals(source[i - 1], i);
    }
    session.rollback();
  }

  @Test
  public void testFromInputStream_ReadFull() throws Exception {
    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(inputStream, FULL_ARRAY);
    Assert.assertEquals(FULL_ARRAY, result);
    final var source = (byte[]) getFieldValue(blob, "source");
    Assert.assertEquals(FULL_ARRAY, source.length);
    for (var i = 1; i < FULL_ARRAY + 1; i++) {
      Assert.assertEquals(source[i - 1], i);
    }
    session.rollback();
  }

  @Test
  public void testReadFromInputStreamWithWait() throws Exception {
    final var data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    final InputStream is = new NotFullyAvailableAtTheTimeInputStream(data, 5);

    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(is);
    Assert.assertEquals(result, data.length);
    Assert.assertEquals(getFieldValue(blob, "size"), data.length);

    final var source = (byte[]) getFieldValue(blob, "source");
    assertArrayEquals(source, data);
    session.rollback();
  }

  @Test
  public void testReadFromInputStreamWithWaitSizeLimit() throws Exception {
    final var data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    final InputStream is = new NotFullyAvailableAtTheTimeInputStream(data, 5);

    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(is, 10);
    Assert.assertEquals(result, data.length);
    Assert.assertEquals(getFieldValue(blob, "size"), data.length);

    final var source = (byte[]) getFieldValue(blob, "source");
    assertArrayEquals(source, data);
    session.rollback();
  }

  @Test
  public void testReadFromInputStreamWithWaitSizeTooBigLimit() throws Exception {
    final var data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    final InputStream is = new NotFullyAvailableAtTheTimeInputStream(data, 5);

    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(is, 15);
    Assert.assertEquals(result, data.length);
    Assert.assertEquals(getFieldValue(blob, "size"), data.length);

    final var source = (byte[]) getFieldValue(blob, "source");
    assertArrayEquals(source, data);
    session.rollback();
  }

  @Test
  public void testReadFromInputStreamWithWaitSizeTooSmallLimit() throws Exception {
    final var data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    final var expected = Arrays.copyOf(data, 8);
    final InputStream is = new NotFullyAvailableAtTheTimeInputStream(data, 5);

    session.begin();
    var testedInstance = session.newBlob();
    final var result = testedInstance.fromInputStream(is, 8);
    Assert.assertEquals(result, expected.length);
    Assert.assertEquals(getFieldValue(testedInstance, "size"), expected.length);

    final var source = (byte[]) getFieldValue(testedInstance, "source");
    assertArrayEquals(source, expected);
    session.rollback();
  }

  private static final class NotFullyAvailableAtTheTimeInputStream extends InputStream {

    private final byte[] data;
    private int pos = -1;
    private final int interrupt;

    private NotFullyAvailableAtTheTimeInputStream(byte[] data, int interrupt) {
      this.data = data;
      this.interrupt = interrupt;
      assert interrupt < data.length;
    }

    @Override
    public int read() {
      pos++;
      if (pos < interrupt) {
        return data[pos] & 0xFF;
      } else if (pos == interrupt) {
        return -1;
      } else if (pos <= data.length) {
        return data[pos - 1] & 0xFF;
      } else {
        return -1;
      }
    }
  }
}
