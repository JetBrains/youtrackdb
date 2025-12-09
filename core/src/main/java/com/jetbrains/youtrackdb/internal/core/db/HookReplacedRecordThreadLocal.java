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
package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBListenerAbstract;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;

/**
 * Uses Thread Local to store information used by hooks.
 */
public class HookReplacedRecordThreadLocal extends ThreadLocal<DBRecord> {

  public static volatile HookReplacedRecordThreadLocal INSTANCE =
      new HookReplacedRecordThreadLocal();

  static {
    YouTrackDBEnginesManager.instance()
        .registerListener(
            new YouTrackDBListenerAbstract() {
              @Override
              public void onStartup() {
                if (INSTANCE == null) {
                  INSTANCE = new HookReplacedRecordThreadLocal();
                }
              }

              @Override
              public void onShutdown() {
                INSTANCE = null;
              }
            });
  }

  public DBRecord getIfDefined() {
    return super.get();
  }

  public boolean isDefined() {
    return super.get() != null;
  }
}
