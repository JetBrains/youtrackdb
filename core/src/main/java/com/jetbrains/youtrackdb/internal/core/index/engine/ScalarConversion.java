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

package com.jetbrains.youtrackdb.internal.core.index.engine;

import java.util.Date;

/**
 * Converts key values to scalar doubles for range interpolation within
 * histogram buckets.
 *
 * <p>String conversion uses UTF-16 code units (matching
 * {@code DefaultComparator} &rarr; {@code String.compareTo()} ordering)
 * with base-65536 encoding. This preserves monotonicity with the B-tree's
 * key ordering for non-ASCII characters.
 *
 * <p>Inspired by PostgreSQL's {@code convert_string_to_scalar()}, adapted
 * to operate on {@code String.charAt()} values instead of raw bytes so
 * that the encoding order matches Java's {@code String.compareTo()}.
 */
public final class ScalarConversion {

  private ScalarConversion() {
  }

  // At most 4 chars contribute meaningfully to a base-65536 fractional double
  // (65536^4 ~ 1.8e19 > 2^53, exhausting double mantissa precision).
  private static final int MAX_ENCODED_CHARS = 4;

  private static final double BASE = 65536.0;

  /**
   * Converts a key value to a double for interpolation within a bucket
   * bounded by {@code lo} and {@code hi}.
   *
   * <p>Numeric types are converted directly via {@code doubleValue()}.
   * Dates are converted to epoch milliseconds. Strings use common-prefix
   * stripping followed by base-65536 encoding. Unknown types fall back
   * to 0.5 (midpoint assumption).
   *
   * @param value the key value to convert
   * @param lo    the lower bucket boundary (used for string prefix stripping)
   * @param hi    the upper bucket boundary (used for string prefix stripping)
   * @return a scalar double suitable for interpolation
   */
  public static double scalarize(Object value, Object lo, Object hi) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value instanceof Date d) {
      return (double) d.getTime();
    }
    if (value instanceof String s
        && lo instanceof String sLo
        && hi instanceof String sHi) {
      return stringToScalar(s, sLo, sHi);
    }
    // byte[], Decimal, etc. — fallback: assume midpoint (uniform within bucket)
    return 0.5;
  }

  /**
   * String-to-scalar conversion using UTF-16 code units.
   *
   * <ol>
   *   <li>Strip common char-prefix of all three strings</li>
   *   <li>Encode remaining chars as a base-65536 fractional double
   *       (each char position contributes
   *       {@code charAt(i) / 65536^(i - prefix + 1)})</li>
   * </ol>
   *
   * <p>Only the first few chars after the prefix contribute meaningfully
   * (double has ~15-17 significant decimal digits, so ~4 chars of base-65536
   * exhaust the mantissa precision).
   *
   * @param value the string to convert
   * @param lo    the lower bound string
   * @param hi    the upper bound string
   * @return a scalar double
   */
  static double stringToScalar(String value, String lo, String hi) {
    int prefix = commonCharPrefixLength(value, lo, hi);
    return charEncode(value, prefix);
  }

  /**
   * Encodes chars starting at {@code startOffset} as a base-65536
   * fractional double. At most 4 chars contribute meaningfully
   * ({@code 65536^4 ~ 1.8e19 > 2^53}).
   *
   * @param s           the string to encode
   * @param startOffset index of the first char to encode
   * @return a fractional double in [0, 1)
   */
  static double charEncode(String s, int startOffset) {
    double result = 0.0;
    double base = 1.0;
    int maxChars = Math.min(s.length() - startOffset, MAX_ENCODED_CHARS);
    for (int i = 0; i < maxChars; i++) {
      base *= BASE;
      result += s.charAt(startOffset + i) / base;
    }
    return result;
  }

  /**
   * Computes the length of the common char prefix shared by all three strings.
   *
   * @param a first string
   * @param b second string
   * @param c third string
   * @return the number of leading characters common to all three
   */
  static int commonCharPrefixLength(String a, String b, String c) {
    int minLen = Math.min(a.length(), Math.min(b.length(), c.length()));
    int prefix = 0;
    while (prefix < minLen
        && a.charAt(prefix) == b.charAt(prefix)
        && a.charAt(prefix) == c.charAt(prefix)) {
      prefix++;
    }
    return prefix;
  }
}
