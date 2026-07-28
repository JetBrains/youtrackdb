package com.jetbrains.youtrackdb.internal.common.io;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins {@link FileUtils#durableAtomicMove} (the CS40 promote recipe): the source replaces a
 * pre-existing target atomically and disappears; a fresh target is created likewise. The fsync
 * legs are not black-box observable — the recipe's ordering is the reviewed contract.
 */
public class FileUtilsDurableAtomicMoveTest {

  private Path directory;

  @Before
  public void setUp() throws IOException {
    directory = Files.createTempDirectory("durable-move-test");
  }

  @After
  public void tearDown() throws IOException {
    try (var files = Files.list(directory)) {
      for (var file : files.toList()) {
        Files.deleteIfExists(file);
      }
    }
    Files.deleteIfExists(directory);
  }

  @Test
  public void moveReplacesExistingTargetAndRemovesSource() throws IOException {
    var source = directory.resolve("source.tmp");
    var target = directory.resolve("target.gz");
    var payload = "NEW-DUMP".getBytes(StandardCharsets.UTF_8);
    Files.write(source, payload);
    Files.write(target, "OLD-DUMP".getBytes(StandardCharsets.UTF_8));

    FileUtils.durableAtomicMove(source, target, this);

    assertArrayEquals("the target must carry the source's content", payload,
        Files.readAllBytes(target));
    assertTrue("the source must be gone after the move", Files.notExists(source));
  }

  @Test
  public void moveCreatesAbsentTarget() throws IOException {
    var source = directory.resolve("source.tmp");
    var target = directory.resolve("fresh.gz");
    var payload = "FRESH-DUMP".getBytes(StandardCharsets.UTF_8);
    Files.write(source, payload);

    FileUtils.durableAtomicMove(source, target, this);

    assertArrayEquals(payload, Files.readAllBytes(target));
    assertTrue(Files.notExists(source));
  }
}
