package com.jetbrains.youtrack.db.internal.common.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import org.junit.Test;

public class IOUtilsTest {

  @Test
  public void shouldGetTimeAsMilis() {
    assertGetTimeAsMilis("2h", 2 * 3600 * 1000);
    assertGetTimeAsMilis("500ms", 500);
    assertGetTimeAsMilis("4d", 4 * 24 * 3600 * 1000);
    assertGetTimeAsMilis("6w", 6L * 7 * 24 * 3600 * 1000);
  }

  private void assertGetTimeAsMilis(String data, long expected) {
    assertEquals(IOUtils.getTimeAsMillisecs(data), expected);
  }

  @Test
  public void shoudGetRightTimeFromString() throws ParseException {
    var calendar = Calendar.getInstance();
    calendar.set(Calendar.HOUR_OF_DAY, 5);
    calendar.set(Calendar.MINUTE, 10);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    var d = IOUtils.getTodayWithTime("05:10:00");
    assertEquals(calendar.getTime(), d);
  }

  @Test
  public void shouldReadFileAsString() throws IOException {
    // UTF-8
    var path = Paths.get("./src/test/resources/", getClass().getSimpleName() + "_utf8.txt");

    var asString = IOUtils.readFileAsString(path.toFile());

    assertThat(asString).isEqualToIgnoringCase("utf-8 :: èàòì€");

    // ISO-8859-1
    path = Paths.get("./src/test/resources/", getClass().getSimpleName() + "_iso-8859-1.txt");

    asString = IOUtils.readFileAsString(path.toFile());

    assertThat(asString).isNotEqualToIgnoringCase("iso-8859-1 :: èàòì?");
  }

  @Test
  public void shouldReadFileAsStringWithGivenCharset() throws IOException {
    // UTF-8
    var path = Paths.get("./src/test/resources/", getClass().getSimpleName() + "_utf8.txt");

    var asString = IOUtils.readFileAsString(path.toFile(), StandardCharsets.UTF_8);

    assertThat(asString).isEqualToIgnoringCase("utf-8 :: èàòì€");

    // ISO-8859-1
    path = Paths.get("./src/test/resources/", getClass().getSimpleName() + "_iso-8859-1.txt");

    asString = IOUtils.readFileAsString(path.toFile(), StandardCharsets.ISO_8859_1);

    assertThat(asString).isEqualToIgnoringCase("iso-8859-1 :: èàòì?");
  }
}
