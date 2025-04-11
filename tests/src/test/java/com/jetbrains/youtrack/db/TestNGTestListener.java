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
 */

package com.jetbrains.youtrack.db;

import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import org.testng.Assert;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ISuiteResult;

/**
 * <ol>
 *   <li>Listens for TestNG test run started and prohibits logging of exceptions on storage level.
 *   <li>Listens for the TestNG test run finishing and runs the direct memory leaks detector, if no
 *       tests failed. If leak detector finds some leaks, it triggers {@link AssertionError} and the
 *       build is marked as failed. Java assertions (-ea) must be active for this to work.
 *   <li>Triggers {@link AssertionError} if {@link LogManager} is shutdown before test is finished.
 *       We may miss some errors because {@link LogManager} is shutdown
 * </ol>
 */
public class TestNGTestListener implements ISuiteListener {

  @Override
  public void onFinish(ISuite suite) {

    if (LogManager.instance().isShutdown()) {
      final var msg = "LogManager was switched off before shutdown";

      System.err.println(msg);
      Assert.fail(msg);
    }
    if (!isFailed(suite)) {
      System.out.println("Shutting down engine and checking for direct memory leaks...");
      final var youTrack = YouTrackDBEnginesManager.instance();
      if (youTrack != null) {
        // state is verified during shutdown
        youTrack.shutdown();
      } else {
        ByteBufferPool.instance(null).checkMemoryLeaks();
      }
    }
  }

  private static boolean isFailed(ISuite suite) {
    if (suite.getSuiteState().isFailed()) {
      return true;
    }

    for (var result : suite.getResults().values()) {
      if (result.getTestContext().getFailedTests().size() != 0) {
        return true;
      }
    }

    return false;
  }
}
