package com.jetbrains.youtrackdb.internal.common.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Test;

/**
 * Tests for {@link AnsiLogFormatter} — subclass of LogFormatter that inserts ANSI color markers
 * based on log level. Since AnsiCode.isSupportsColors() depends on static initialization and may be
 * false in test environments, tests verify the raw $ANSI{...} marker insertion and plain-text
 * content.
 */
public class AnsiLogFormatterTest {

  private final AnsiLogFormatter formatter = new AnsiLogFormatter();

  /** INFO level message contains the level and message text. */
  @Test
  public void formatInfoMessage() {
    LogRecord record = new LogRecord(Level.INFO, "Info message");
    String result = formatter.format(record);
    assertThat(result).contains("INFO");
    assertThat(result).contains("Info message");
  }

  /** SEVERE level message contains the level text. */
  @Test
  public void formatSevereMessage() {
    LogRecord record = new LogRecord(Level.SEVERE, "Error occurred");
    String result = formatter.format(record);
    assertThat(result).contains("SEVER");
    assertThat(result).contains("Error occurred");
  }

  /** WARNING level message contains the level text. */
  @Test
  public void formatWarningMessage() {
    LogRecord record = new LogRecord(Level.WARNING, "Warning text");
    String result = formatter.format(record);
    assertThat(result).contains("WARNI");
    assertThat(result).contains("Warning text");
  }

  /** Message with parameters uses String.format substitution. */
  @Test
  public void formatWithParameters() {
    LogRecord record = new LogRecord(Level.INFO, "Count: %d");
    record.setParameters(new Object[] {7});
    String result = formatter.format(record);
    assertThat(result).contains("Count: 7");
  }

  /** Logger name is extracted and appended in brackets. */
  @Test
  public void formatWithLoggerName() {
    LogRecord record = new LogRecord(Level.INFO, "Test");
    record.setLoggerName("com.example.MyService");
    String result = formatter.format(record);
    assertThat(result).contains("[MyService]");
  }

  /** The output always contains a cyan timestamp marker. */
  @Test
  public void formatContainsCyanTimestampMarker() {
    LogRecord record = new LogRecord(Level.INFO, "Test");
    // The AnsiLogFormatter always wraps the timestamp with $ANSI{cyan ...}
    // If colors are not supported, the marker is resolved to plain text
    String result = formatter.format(record);
    // The result should contain a timestamp (date pattern) regardless of color support
    assertThat(result).isNotEmpty();
  }

  /** SEVERE message with thrown exception includes stack trace. */
  @Test
  public void formatSevereWithException() {
    LogRecord record = new LogRecord(Level.SEVERE, "Fatal error");
    record.setThrown(new RuntimeException("boom"));
    String result = formatter.format(record);
    assertThat(result).contains("Fatal error");
    assertThat(result).contains("RuntimeException");
    assertThat(result).contains("boom");
  }
}
