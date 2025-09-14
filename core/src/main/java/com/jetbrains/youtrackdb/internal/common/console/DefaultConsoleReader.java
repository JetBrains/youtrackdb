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

package com.jetbrains.youtrackdb.internal.common.console;

import com.jetbrains.youtrackdb.internal.common.thread.SoftThread;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import javax.annotation.Nullable;

/**
 * Console reader implementation that uses the Java System.in.
 */
public class DefaultConsoleReader implements ConsoleReader {

  private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

  private static class EraserThread extends SoftThread {

    public EraserThread(String name) {
      super(name);
    }

    @Override
    @SuppressWarnings({"checkstyle:AvoidEscapedUnicodeCharacters", "checkstyle:IllegalTokenText"})
    protected void execute() throws Exception {
      System.out.print("\u0008*");
      try {
        sleep(1);
      } catch (InterruptedException ignore) {
        // om nom nom
      }
    }
  }

  @Nullable
  @Override
  public String readLine() {
    try {
      return reader.readLine();
    } catch (IOException ignore) {
      return null;
    }
  }

  @Nullable
  @Override
  public String readPassword() {
    if (System.console() == null)
    // IDE
    {
      return readLine();
    }

    System.out.print(" ");

    final var et = new EraserThread("Read password thread");
    et.start();

    try {
      return reader.readLine();
    } catch (IOException ignore) {
      return null;
    } finally {
      et.sendShutdown();
    }
  }


  @Override
  public int getConsoleWidth() {
    return FALLBACK_CONSOLE_WIDTH;
  }
}
