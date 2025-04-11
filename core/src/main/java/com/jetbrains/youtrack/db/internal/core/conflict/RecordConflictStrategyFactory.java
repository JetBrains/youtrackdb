/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.core.conflict;

import com.jetbrains.youtrack.db.internal.common.factory.ConfigurableStatelessFactory;

/**
 * Factory to manage the record conflict strategy implementations.
 */
public class RecordConflictStrategyFactory
    extends ConfigurableStatelessFactory<String, RecordConflictStrategy> {

  public RecordConflictStrategyFactory() {
    final var def = new VersionRecordConflictStrategy();

    registerImplementation(VersionRecordConflictStrategy.NAME, def);
    registerImplementation(
        AutoMergeRecordConflictStrategy.NAME, new AutoMergeRecordConflictStrategy());
    registerImplementation(
        ContentRecordConflictStrategy.NAME, new ContentRecordConflictStrategy());

    setDefaultImplementation(def);
  }

  public RecordConflictStrategy getStrategy(final String iStrategy) {
    return getImplementation(iStrategy);
  }

  public String getDefaultStrategy() {
    return VersionRecordConflictStrategy.NAME;
  }
}
