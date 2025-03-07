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

package com.jetbrains.youtrack.db.internal.core;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.common.profiler.Profiler;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map.Entry;
import sun.misc.Signal;

@SuppressWarnings("restriction")
public class SignalHandler implements sun.misc.SignalHandler {

  private final Hashtable<Signal, sun.misc.SignalHandler> redefinedHandlers = new Hashtable(4);
  private final List<SignalListener> listeners = new ArrayList<SignalListener>();

  public interface SignalListener {

    void onSignal(Signal signal);
  }

  public SignalHandler() {
  }

  public void registerListener(final SignalListener listener) {
    listeners.add(listener);
  }

  public void unregisterListener(final SignalListener listener) {
    listeners.remove(listener);
  }

  public void listenTo(final String name, final sun.misc.SignalHandler iListener) {
    Signal signal = new Signal(name);
    sun.misc.SignalHandler redefinedHandler = Signal.handle(signal, iListener);
    if (redefinedHandler != null) {
      redefinedHandlers.put(signal, redefinedHandler);
    }
  }

  public void handle(final Signal signal) {
    LogManager.instance().warn(this, "Received signal: %s", signal);

    final String s = signal.toString().trim();

    if (YouTrackDBEnginesManager.instance().isSelfManagedShutdown()
        && (s.equals("SIGKILL")
        || s.equals("SIGHUP")
        || s.equals("SIGINT")
        || s.equals("SIGTERM"))) {
      YouTrackDBEnginesManager.instance().shutdown();
      System.exit(1);
    } else if (s.equals("SIGTRAP")) {
      System.out.println();
      GlobalConfiguration.dumpConfiguration(System.out);
      System.out.println();
      System.out.println(Profiler.dumpEnvironment());
      System.out.println();
      System.out.println(Profiler.threadDump());
    } else {
      sun.misc.SignalHandler redefinedHandler = redefinedHandlers.get(signal);
      if (redefinedHandler != null) {
        redefinedHandler.handle(signal);
      }
    }

    for (SignalListener l : listeners) {
      l.onSignal(signal);
    }
  }

  public void installDefaultSignals() {
    installDefaultSignals(this);
  }

  public void installDefaultSignals(final sun.misc.SignalHandler iListener) {
    // listenTo("HUP", iListener); // DISABLED HUB BECAUSE ON WINDOWS IT'S USED INTERNALLY AND
    // CAUSED JVM KILL
    // listenTo("KILL",iListener);

    try {
      listenTo("INT", iListener);
    } catch (IllegalArgumentException ignore) {
      // NOT AVAILABLE
    }
    try {
      listenTo("TERM", iListener);
    } catch (IllegalArgumentException ignore) {
      // NOT AVAILABLE
    }
    try {
      listenTo("TRAP", iListener);
    } catch (IllegalArgumentException ignore) {
      // NOT AVAILABLE
    }
  }

  public void cancel() {
    for (Entry<Signal, sun.misc.SignalHandler> entry : redefinedHandlers.entrySet()) {
      try {
        // re-install the original handler we replaced
        Signal.handle(entry.getKey(), entry.getValue());
      } catch (IllegalStateException e) {
        LogManager.instance()
            .error(this, "Error during reverting signal handlers to default ones", e);
        // not expected as we were able to redefine it earlier, but just in case
      }
    }
    redefinedHandlers.clear();
  }
}
