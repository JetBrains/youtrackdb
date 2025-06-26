package com.jetbrains.youtrack.db.internal.lucene.builder;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * This utility class replace the {@link org.apache.lucene.document.DateTools} from Lucene code
 * base.
 *
 * <p>It uses the {@link java.util.TimeZone} defined at database level and maintains only methods
 * for string conversion to {@link Date} or long value.
 */
public class LuceneDateTools {

  // indexed by format length
  private static final Resolution[] RESOLUTIONS;

  static {
    RESOLUTIONS = new Resolution[Resolution.MILLISECOND.formatLen + 1];
    for (var resolution : Resolution.values()) {
      RESOLUTIONS[resolution.formatLen] = resolution;
    }
  }

  // cannot create, the class has static methods only
  private LuceneDateTools() {
  }

  /**
   * Converts a string produced by <code>timeToString</code> or <code>dateToString</code> back to a
   * time, represented as the number of milliseconds since January 1, 1970, 00:00:00 GMT.
   *
   * @param dateString the date string to be converted
   * @param session
   * @return the number of milliseconds since January 1, 1970, 00:00:00 GMT
   * @throws ParseException if <code>dateString</code> is not in the expected format
   */
  public static long stringToTime(String dateString, DatabaseSessionInternal session)
      throws ParseException {
    return stringToDate(dateString, session).getTime();
  }

  /**
   * Converts a string produced by <code>timeToString</code> or <code>dateToString</code> back to a
   * time, represented as a Date object.
   *
   * @param dateString the date string to be converted
   * @param session
   * @return the parsed time as a Date object
   * @throws ParseException if <code>dateString</code> is not in the expected format
   */
  public static Date stringToDate(String dateString, DatabaseSessionInternal session)
      throws ParseException {
    try {
      var format = RESOLUTIONS[dateString.length()].format(session);
      return format.parse(dateString);
    } catch (Exception e) {
      LogManager.instance()
          .error(LuceneDateTools.class, "Exception is suppressed, original exception is ", e);
      //noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException
      throw new ParseException("Input is not a valid date string: " + dateString, 0);
    }
  }

  /**
   * Specifies the time granularity.
   */
  public enum Resolution {
    /**
     * Limit a date's resolution to year granularity.
     */
    YEAR(4),
    /**
     * Limit a date's resolution to month granularity.
     */
    MONTH(6),
    /**
     * Limit a date's resolution to day granularity.
     */
    DAY(8),
    /**
     * Limit a date's resolution to hour granularity.
     */
    HOUR(10),
    /**
     * Limit a date's resolution to minute granularity.
     */
    MINUTE(12),
    /**
     * Limit a date's resolution to second granularity.
     */
    SECOND(14),
    /**
     * Limit a date's resolution to millisecond granularity.
     */
    MILLISECOND(17);

    private final int formatLen;

    Resolution(int formatLen) {
      this.formatLen = formatLen;
    }

    public SimpleDateFormat format(DatabaseSessionInternal session) {
      // formatLen 10's place:                     11111111
      // formatLen  1's place:            12345678901234567

      var format =
          new SimpleDateFormat("yyyyMMddHHmmssSSS".substring(0, formatLen), Locale.ROOT);
      format.setTimeZone(DateHelper.getDatabaseTimeZone(session));

      return format;
    }

    /**
     * this method returns the name of the resolution in lowercase (for backwards compatibility)
     */
    @Override
    public String toString() {
      return super.toString().toLowerCase(Locale.ROOT);
    }
  }
}
