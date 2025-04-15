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

package com.jetbrains.youtrack.db.internal.core.storage.cache;

/**
 * @since 25.04.13
 */
public class PageDataVerificationError {

  private final boolean incorrectMagicNumber;
  private final boolean incorrectCheckSum;
  private final long pageIndex;
  private final String fileName;

  public PageDataVerificationError(
      boolean incorrectMagicNumber, boolean incorrectCheckSum, long pageIndex, String fileName) {
    this.incorrectMagicNumber = incorrectMagicNumber;
    this.incorrectCheckSum = incorrectCheckSum;
    this.pageIndex = pageIndex;
    this.fileName = fileName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var that = (PageDataVerificationError) o;

    if (incorrectCheckSum != that.incorrectCheckSum) {
      return false;
    }
    if (incorrectMagicNumber != that.incorrectMagicNumber) {
      return false;
    }
    if (pageIndex != that.pageIndex) {
      return false;
    }
    return fileName.equals(that.fileName);
  }

  @Override
  public int hashCode() {
    var result = (incorrectMagicNumber ? 1 : 0);
    result = 31 * result + (incorrectCheckSum ? 1 : 0);
    result = 31 * result + (int) (pageIndex ^ (pageIndex >>> 32));
    result = 31 * result + fileName.hashCode();
    return result;
  }
}
