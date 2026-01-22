package com.jetbrains.youtrackdb.internal.docker;

import java.io.PrintStream;
import org.testcontainers.containers.output.BaseConsumer;
import org.testcontainers.containers.output.OutputFrame;

public class StdOutConsumer<SELF extends BaseConsumer<SELF>> extends BaseConsumer<SELF> {

  private final String prefix;
  private final PrintStream storedErr;

  public StdOutConsumer(String prefix) {
    this.prefix = prefix;
    this.storedErr = null;
  }

  public StdOutConsumer(String prefix, PrintStream storedErr) {
    this.prefix = prefix;
    this.storedErr = storedErr;
  }

  @Override
  public void accept(OutputFrame outputFrame) {
    var utf8String = outputFrame.getUtf8StringWithoutLineEnding();
    switch (outputFrame.getType()) {
      case STDOUT -> System.out.printf("%s | %s%n", prefix, utf8String);
      case STDERR -> {
        System.err.printf("%s | %s%n", prefix, utf8String);
        if (storedErr != null) {
          storedErr.println(utf8String);
        }
      }
      case END -> {
      }
    }
  }
}
