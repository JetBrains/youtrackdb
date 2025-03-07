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
package com.jetbrains.youtrack.db.base;

import com.jetbrains.youtrack.db.internal.common.test.SpeedTestMonoThread;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import org.testng.annotations.Test;

@Test(enabled = false)
public abstract class YouTrackMonoThreadTest extends SpeedTestMonoThread {

  public YouTrackMonoThreadTest(int iCycles) {
    super(iCycles);
  }

  public YouTrackMonoThreadTest() {
    super(1);
  }

  @Override
  public void deinit() {
//    System.out.println(YouTrackDBEnginesManager.instance().getProfiler().dump());
  }
}
