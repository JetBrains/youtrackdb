package com.jetbrains.youtrackdb.internal.common.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Test;

/**
 * Tests for {@link LogFormatter} — JUL formatter that produces plain-text log records with
 * timestamp, level, message, and optional stack trace.
 */
public class LogFormatterTest {

  private final LogFormatter formatter = new LogFormatter();

  // ---------------------------------------------------------------------------
  // format — basic message
  // ---------------------------------------------------------------------------

  /** INFO level message contains the level name and message text. */
  @Test
  public void formatInfoMessage() {
    LogRecord record = new LogRecord(Level.INFO, "Test message");
    String result = formatter.format(record);
    assertThat(result).contains("INFO");
    assertThat(result).contains("Test message");
  }

  /** WARNING level message contains WARNING level name. */
  @Test
  public void formatWarningMessage() {
    LogRecord record = new LogRecord(Level.WARNING, "Warning text");
    String result = formatter.format(record);
    assertThat(result).contains("WARNI");
    assertThat(result).contains("Warning text");
  }

  // ---------------------------------------------------------------------------
  // format — with exception
  // ---------------------------------------------------------------------------

  /** Message with thrown exception includes the stack trace. */
  @Test
  public void formatWithExceptionIncludesStackTrace() {
    LogRecord record = new LogRecord(Level.SEVERE, "Error occurred");
    record.setThrown(new RuntimeException("test error"));
    String result = formatter.format(record);
    assertThat(result).contains("Error occurred");
    assertThat(result).contains("RuntimeException");
    assertThat(result).contains("test error");
  }

  // ---------------------------------------------------------------------------
  // format — with parameters
  // ---------------------------------------------------------------------------

  /** Message with parameters uses String.format substitution. */
  @Test
  public void formatWithParameters() {
    LogRecord record = new LogRecord(Level.INFO, "Value: %d");
    record.setParameters(new Object[] {42});
    String result = formatter.format(record);
    assertThat(result).contains("Value: 42");
  }

  /** Message with invalid format string falls back to raw message. */
  @Test
  public void formatWithInvalidFormatFallsBack() {
    LogRecord record = new LogRecord(Level.INFO, "Bad format: %z");
    record.setParameters(new Object[] {"value"});
    String result = formatter.format(record);
    assertThat(result).contains("Bad format: %z");
  }

  // ---------------------------------------------------------------------------
  // format — with logger name (requester)
  // ---------------------------------------------------------------------------

  /** Logger name is extracted to simple class name and appended in brackets. */
  @Test
  public void formatWithLoggerNameIncludesSimpleName() {
    LogRecord record = new LogRecord(Level.INFO, "Test");
    record.setLoggerName("com.example.MyClass");
    String result = formatter.format(record);
    assertThat(result).contains("[MyClass]");
  }

  /** Null logger name does not add brackets. */
  @Test
  public void formatWithNullLoggerName() {
    LogRecord record = new LogRecord(Level.INFO, "Test");
    record.setLoggerName(null);
    String result = formatter.format(record);
    assertThat(result).doesNotContain("[");
  }

  // ---------------------------------------------------------------------------
  // getSourceClassSimpleName
  // ---------------------------------------------------------------------------

  /** Extracts simple name from fully-qualified class name. */
  @Test
  public void getSourceClassSimpleNameExtractsLast() {
    assertThat(formatter.getSourceClassSimpleName("com.example.MyClass"))
        .isEqualTo("MyClass");
  }

  /** Simple name without dots returns the whole string. */
  @Test
  public void getSourceClassSimpleNameNoDot() {
    assertThat(formatter.getSourceClassSimpleName("MyClass")).isEqualTo("MyClass");
  }

  /** Null input returns null. */
  @Test
  public void getSourceClassSimpleNameNull() {
    assertThat(formatter.getSourceClassSimpleName(null)).isNull();
  }
}
