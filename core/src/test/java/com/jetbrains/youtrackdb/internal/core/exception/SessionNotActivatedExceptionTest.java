/*
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
package com.jetbrains.youtrackdb.internal.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Bespoke test for {@link SessionNotActivatedException} — outside the parameterized fan because
 * the only public non-copy ctor signature is {@code (String dbName)} (single-arg dbName, NO
 * separate message arg). The ctor synthesises a fixed message from the dbName, so a fan-style
 * "message round-trip" probe would treat the dbName as a message and assert against the wrong
 * value.
 */
public class SessionNotActivatedExceptionTest {

  /**
   * The {@code (String dbName)} ctor stamps a fixed canonical message and stores the dbName.
   * Pin both observables so a future refactor that changes the canonical message wording fails
   * loudly.
   */
  @Test
  public void dbNameOnlyConstructorSynthesisesCanonicalMessage() {
    var ex = new SessionNotActivatedException("dbX");

    assertThat(ex.getMessage()).contains("Session is not activated");
    assertThat(ex.getMessage()).contains("DB Name=\"dbX\"");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /** The copy ctor preserves the message and dbName via the {@link BaseException} chain. */
  @Test
  public void copyConstructorPreservesMessageAndDbName() {
    var original = new SessionNotActivatedException("dbX");
    var copy = new SessionNotActivatedException(original);

    assertThat(copy.getMessage()).contains("Session is not activated");
    assertThat(copy.getDbName()).isEqualTo("dbX");
  }
}
