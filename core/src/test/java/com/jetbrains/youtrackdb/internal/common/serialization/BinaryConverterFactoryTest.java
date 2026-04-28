/*
 *
 *
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
package com.jetbrains.youtrackdb.internal.common.serialization;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Pins the {@link BinaryConverterFactory#getConverter()} dispatch on
 * {@link GlobalConfiguration#MEMORY_USE_UNSAFE} and the {@code unsafeWasDetected} class-init flag.
 *
 * <p>Marked {@code @Category(SequentialTest)} because it mutates the process-wide
 * {@code MEMORY_USE_UNSAFE} configuration; surefire forks that run sequential-category tests
 * outside the default parallel pool prevent cross-test interference. The flag is captured in
 * {@link #setUp()} and restored in {@link #tearDown()}.
 *
 * <p><strong>Caveat for callers.</strong> The factory's runtime dispatch responds to
 * {@code MEMORY_USE_UNSAFE} on every call. However, several production classes capture the
 * factory's choice into a {@code static final BinaryConverter CONVERTER} field at class-init
 * time (e.g. {@code IntegerSerializer.INSTANCE}, {@code LongSerializer.INSTANCE}, the various
 * {@code *Serializer.CONVERTER} fields). Once such a class is loaded, mutating
 * {@code MEMORY_USE_UNSAFE} mid-suite will <em>not</em> swap the captured converter — those
 * fields keep whatever instance the factory returned at load time. This test toggles the flag
 * before observing {@code getConverter()} directly so it pins only the factory's dispatch
 * contract, not the captured-field surface.
 */
@Category(SequentialTest.class)
public class BinaryConverterFactoryTest {

  private boolean savedUseUnsafe;

  @Before
  public void setUp() {
    savedUseUnsafe = GlobalConfiguration.MEMORY_USE_UNSAFE.getValueAsBoolean();
  }

  @After
  public void tearDown() {
    GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(savedUseUnsafe);
  }

  /**
   * On modern JDKs {@code sun.misc.Unsafe} is reachable, so with {@code MEMORY_USE_UNSAFE=true}
   * the factory returns the unsafe-backed converter (singleton).
   */
  @Test
  public void getConverterReturnsUnsafeWhenUseUnsafeIsTrue() {
    GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(true);

    var converter = BinaryConverterFactory.getConverter();

    Assert.assertSame(UnsafeBinaryConverter.INSTANCE, converter);
    Assert.assertTrue(converter.nativeAccelerationUsed());
    Assert.assertEquals(UnsafeBinaryConverter.class, converter.getClass());
  }

  /**
   * With {@code MEMORY_USE_UNSAFE=false} the factory returns the pure-Java converter (singleton)
   * regardless of whether {@code sun.misc.Unsafe} is available.
   */
  @Test
  public void getConverterReturnsSafeWhenUseUnsafeIsFalse() {
    GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(false);

    var converter = BinaryConverterFactory.getConverter();

    Assert.assertSame(SafeBinaryConverter.INSTANCE, converter);
    Assert.assertFalse(converter.nativeAccelerationUsed());
    Assert.assertEquals(SafeBinaryConverter.class, converter.getClass());
  }

  /** Each call returns the same singleton instance — the factory never re-allocates. */
  @Test
  public void getConverterReturnsSingletonInstanceAcrossCalls() {
    GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(true);
    var first = BinaryConverterFactory.getConverter();
    var second = BinaryConverterFactory.getConverter();
    Assert.assertSame(first, second);

    GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(false);
    var safeFirst = BinaryConverterFactory.getConverter();
    var safeSecond = BinaryConverterFactory.getConverter();
    Assert.assertSame(safeFirst, safeSecond);

    // The singleton swap happens because of MEMORY_USE_UNSAFE, not because of mutation.
    Assert.assertNotSame(first, safeFirst);
  }

  /**
   * {@code SafeBinaryConverter.INSTANCE} reports {@code false} for {@link
   * BinaryConverter#nativeAccelerationUsed()} and {@code UnsafeBinaryConverter.INSTANCE} reports
   * {@code true}; pinning both prevents a regression that flipped the labels (e.g., a refactor
   * that copy-pasted the wrong override).
   */
  @Test
  public void nativeAccelerationUsedDistinguishesSafeAndUnsafe() {
    Assert.assertFalse(SafeBinaryConverter.INSTANCE.nativeAccelerationUsed());
    Assert.assertTrue(UnsafeBinaryConverter.INSTANCE.nativeAccelerationUsed());
  }
}
