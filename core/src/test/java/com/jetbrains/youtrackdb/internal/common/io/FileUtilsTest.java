package com.jetbrains.youtrackdb.internal.common.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for {@link FileUtils} — file system utilities for size parsing/formatting, path
 * manipulation, directory operations, file copy/move/delete.
 */
public class FileUtilsTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  // ---------------------------------------------------------------------------
  // getSizeAsNumber
  // ---------------------------------------------------------------------------

  /** Pure numeric string is parsed as bytes. */
  @Test
  public void getSizeAsNumberPureNumber() {
    assertThat(FileUtils.getSizeAsNumber("1024")).isEqualTo(1024L);
  }

  /** Number object is returned as long value. */
  @Test
  public void getSizeAsNumberFromNumberObject() {
    assertThat(FileUtils.getSizeAsNumber(2048)).isEqualTo(2048L);
  }

  /** KB suffix multiplies by 1024. */
  @Test
  public void getSizeAsNumberKilobytes() {
    assertThat(FileUtils.getSizeAsNumber("2KB")).isEqualTo(2 * 1024L);
  }

  /** MB suffix multiplies by 1048576. */
  @Test
  public void getSizeAsNumberMegabytes() {
    assertThat(FileUtils.getSizeAsNumber("3MB")).isEqualTo(3 * 1048576L);
  }

  /** GB suffix multiplies by 1073741824. */
  @Test
  public void getSizeAsNumberGigabytes() {
    assertThat(FileUtils.getSizeAsNumber("1GB")).isEqualTo(1073741824L);
  }

  /** TB suffix multiplies by terabyte constant. */
  @Test
  public void getSizeAsNumberTerabytes() {
    assertThat(FileUtils.getSizeAsNumber("1TB")).isEqualTo(1099511627776L);
  }

  /** B suffix (bare bytes). */
  @Test
  public void getSizeAsNumberBareBytes() {
    assertThat(FileUtils.getSizeAsNumber("512B")).isEqualTo(512L);
  }

  /** Decimal KB value. */
  @Test
  public void getSizeAsNumberDecimalKB() {
    assertThat(FileUtils.getSizeAsNumber("1.5KB")).isEqualTo((long) (1.5f * 1024));
  }

  /** Null throws IllegalArgumentException. */
  @Test
  public void getSizeAsNumberNullThrows() {
    assertThatThrownBy(() -> FileUtils.getSizeAsNumber(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Size is null");
  }

  /** Unrecognized size suffix throws IllegalArgumentException. */
  @Test
  public void getSizeAsNumberUnrecognizedSuffixThrows() {
    assertThatThrownBy(() -> FileUtils.getSizeAsNumber("100X"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unrecognizable format");
  }

  /** Percentage value is returned as negative. */
  @Test
  public void getSizeAsNumberPercentage() {
    assertThat(FileUtils.getSizeAsNumber("50%")).isEqualTo(-50L);
  }

  /** Zero string is parsed as 0 bytes. */
  @Test
  public void getSizeAsNumberZeroString() {
    assertThat(FileUtils.getSizeAsNumber("0")).isEqualTo(0L);
  }

  /**
   * Empty string causes NumberFormatException — vacuous-truth bug in the digit-check loop (same
   * pattern as IOUtils.isLong("")).
   */
  @Test
  public void getSizeAsNumberEmptyStringThrows() {
    assertThatThrownBy(() -> FileUtils.getSizeAsNumber(""))
        .isInstanceOf(NumberFormatException.class);
  }

  // ---------------------------------------------------------------------------
  // string2number
  // ---------------------------------------------------------------------------

  /** Integer string parses as Long. */
  @Test
  public void string2numberInteger() {
    Number result = FileUtils.string2number("42");
    assertThat(result).isInstanceOf(Long.class);
    assertThat(result.longValue()).isEqualTo(42L);
  }

  /** Decimal string parses as Double. */
  @Test
  public void string2numberDecimal() {
    Number result = FileUtils.string2number("3.14");
    assertThat(result).isInstanceOf(Double.class);
    assertThat(result.doubleValue()).isCloseTo(3.14, org.assertj.core.data.Offset.offset(0.001));
  }

  /** Negative integer parses correctly. */
  @Test
  public void string2numberNegative() {
    assertThat(FileUtils.string2number("-7").longValue()).isEqualTo(-7L);
  }

  // ---------------------------------------------------------------------------
  // getSizeAsString
  // ---------------------------------------------------------------------------

  /** Small values are formatted as bytes. */
  @Test
  public void getSizeAsStringBytes() {
    assertThat(FileUtils.getSizeAsString(512)).isEqualTo("512b");
  }

  /** KB range values. */
  @Test
  public void getSizeAsStringKilobytes() {
    assertThat(FileUtils.getSizeAsString(2048)).isEqualTo("2.00KB");
  }

  /** MB range values. */
  @Test
  public void getSizeAsStringMegabytes() {
    assertThat(FileUtils.getSizeAsString(5 * 1048576L)).isEqualTo("5.00MB");
  }

  /** GB range values. */
  @Test
  public void getSizeAsStringGigabytes() {
    assertThat(FileUtils.getSizeAsString(2L * 1073741824)).isEqualTo("2.00GB");
  }

  /** TB range values. */
  @Test
  public void getSizeAsStringTerabytes() {
    assertThat(FileUtils.getSizeAsString(2L * 1099511627776L)).isEqualTo("2.00TB");
  }

  /** Exactly KILOBYTE is formatted as bytes, not KB (strict > boundary). */
  @Test
  public void getSizeAsStringExactKilobyte() {
    assertThat(FileUtils.getSizeAsString(1024)).isEqualTo("1024b");
  }

  /** Zero size is formatted as bytes. */
  @Test
  public void getSizeAsStringZero() {
    assertThat(FileUtils.getSizeAsString(0)).isEqualTo("0b");
  }

  /** Negative size falls through all checks to bytes format. */
  @Test
  public void getSizeAsStringNegative() {
    assertThat(FileUtils.getSizeAsString(-100)).isEqualTo("-100b");
  }

  // ---------------------------------------------------------------------------
  // getDirectory / getPath
  // ---------------------------------------------------------------------------

  /** Extracts directory from a path with a file component. */
  @Test
  public void getDirectoryExtractsDir() {
    assertThat(FileUtils.getDirectory("/path/to/file.txt")).isEqualTo("/path/to");
  }

  /** No separator returns empty string. */
  @Test
  public void getDirectoryNoSeparator() {
    assertThat(FileUtils.getDirectory("file.txt")).isEmpty();
  }

  /** Backslashes are converted to forward slashes by getPath. */
  @Test
  public void getPathConvertsBackslashes() {
    assertThat(FileUtils.getPath("C:\\path\\to\\file")).isEqualTo("C:/path/to/file");
  }

  /** Null path returns null. */
  @Test
  public void getPathNull() {
    assertThat(FileUtils.getPath(null)).isNull();
  }

  // ---------------------------------------------------------------------------
  // checkValidName
  // ---------------------------------------------------------------------------

  /** Valid name does not throw. */
  @Test
  public void checkValidNameAcceptsValidName() throws IOException {
    FileUtils.checkValidName("my-database");
  }

  /** Name with .. throws IOException. */
  @Test
  public void checkValidNameRejectsDoubleDot() {
    assertThatThrownBy(() -> FileUtils.checkValidName("../escape"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Invalid file name");
  }

  /** Name with forward slash throws IOException. */
  @Test
  public void checkValidNameRejectsSlash() {
    assertThatThrownBy(() -> FileUtils.checkValidName("path/file"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Invalid file name");
  }

  /** Name with backslash throws IOException. */
  @Test
  public void checkValidNameRejectsBackslash() {
    assertThatThrownBy(() -> FileUtils.checkValidName("path\\file"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Invalid file name");
  }

  // ---------------------------------------------------------------------------
  // File operations (using TemporaryFolder)
  // ---------------------------------------------------------------------------

  /** deleteRecursively removes a non-empty directory tree. */
  @Test
  public void deleteRecursivelyRemovesNonEmptyDir() throws IOException {
    File dir = tempFolder.newFolder("toDelete");
    File subDir = new File(dir, "sub");
    subDir.mkdir();
    Files.writeString(new File(subDir, "file.txt").toPath(), "content");

    FileUtils.deleteRecursively(dir);
    assertThat(dir).doesNotExist();
  }

  /** deleteRecursively on non-existent file does nothing (no exception, no side effects). */
  @Test
  public void deleteRecursivelyNonExistentIsNoOp() {
    File nonExistent = new File(tempFolder.getRoot(), "nonexistent-" + System.nanoTime());
    assertThat(nonExistent).doesNotExist();
    FileUtils.deleteRecursively(nonExistent);
    assertThat(nonExistent).doesNotExist();
  }

  /** deleteFolderIfEmpty deletes an empty directory. */
  @Test
  public void deleteFolderIfEmptyDeletesEmptyDir() throws IOException {
    File dir = tempFolder.newFolder("emptyDir");
    assertThat(dir).exists();
    FileUtils.deleteFolderIfEmpty(dir);
    assertThat(dir).doesNotExist();
  }

  /** deleteFolderIfEmpty keeps a non-empty directory and its contents intact. */
  @Test
  public void deleteFolderIfEmptyKeepsNonEmptyDir() throws IOException {
    File dir = tempFolder.newFolder("nonEmpty");
    File file = new File(dir, "file.txt");
    Files.writeString(file.toPath(), "content");
    FileUtils.deleteFolderIfEmpty(dir);
    assertThat(dir).exists();
    assertThat(file).exists();
    assertThat(Files.readString(file.toPath())).isEqualTo("content");
  }

  /** copyFile copies content from source to destination. */
  @Test
  public void copyFilePreservesContent() throws IOException {
    File src = tempFolder.newFile("source.txt");
    Files.writeString(src.toPath(), "hello copy");
    File dst = new File(tempFolder.getRoot(), "dest.txt");

    FileUtils.copyFile(src, dst);
    assertThat(dst).exists();
    assertThat(Files.readString(dst.toPath())).isEqualTo("hello copy");
  }

  /** copyDirectory recursively copies all files. */
  @Test
  public void copyDirectoryRecursiveCopy() throws IOException {
    File srcDir = tempFolder.newFolder("srcDir");
    Files.writeString(new File(srcDir, "a.txt").toPath(), "aaa");
    File sub = new File(srcDir, "sub");
    sub.mkdir();
    Files.writeString(new File(sub, "b.txt").toPath(), "bbb");

    File dstDir = new File(tempFolder.getRoot(), "dstDir");
    FileUtils.copyDirectory(srcDir, dstDir);

    assertThat(Files.readString(new File(dstDir, "a.txt").toPath())).isEqualTo("aaa");
    assertThat(new File(dstDir, "sub")).isDirectory();
    assertThat(Files.readString(new File(dstDir, "sub/b.txt").toPath())).isEqualTo("bbb");
  }

  /** renameFile renames a file. */
  @Test
  public void renameFileMovesFile() throws IOException {
    File src = tempFolder.newFile("old.txt");
    Files.writeString(src.toPath(), "rename me");
    File dst = new File(tempFolder.getRoot(), "new.txt");

    boolean result = FileUtils.renameFile(src, dst);
    assertThat(result).isTrue();
    assertThat(src).doesNotExist();
    assertThat(Files.readString(dst.toPath())).isEqualTo("rename me");
  }

  /** delete removes an existing file. */
  @Test
  public void deleteRemovesExistingFile() throws IOException {
    File file = tempFolder.newFile("todelete.txt");
    boolean result = FileUtils.delete(file);
    assertThat(result).isTrue();
    assertThat(file).doesNotExist();
  }

  /** delete on non-existent file returns true (file already absent). */
  @Test
  public void deleteNonExistentReturnsTrue() throws IOException {
    File file = new File(tempFolder.getRoot(), "nonexistent.txt");
    boolean result = FileUtils.delete(file);
    assertThat(result).isTrue();
  }

  /** prepareForFileCreationOrReplacement creates parent directories but not the file itself. */
  @Test
  public void prepareForFileCreationCreatesParentDirs() throws IOException {
    Path path = tempFolder.getRoot().toPath().resolve("a/b/c/file.txt");
    FileUtils.prepareForFileCreationOrReplacement(path, this, "testing");
    assertThat(path.getParent()).exists();
    assertThat(path).doesNotExist();
  }

  /** prepareForFileCreationOrReplacement deletes existing file. */
  @Test
  public void prepareForFileCreationDeletesExisting() throws IOException {
    File existing = tempFolder.newFile("existing.txt");
    Files.writeString(existing.toPath(), "old");
    FileUtils.prepareForFileCreationOrReplacement(existing.toPath(), this, "testing");
    assertThat(existing).doesNotExist();
  }

  /** atomicMoveWithFallback moves a file. */
  @Test
  public void atomicMoveWithFallbackMovesFile() throws IOException {
    File src = tempFolder.newFile("src.txt");
    Files.writeString(src.toPath(), "move me", StandardCharsets.UTF_8);
    Path target = tempFolder.getRoot().toPath().resolve("target.txt");

    FileUtils.atomicMoveWithFallback(src.toPath(), target, this);
    assertThat(src).doesNotExist();
    assertThat(Files.readString(target)).isEqualTo("move me");
  }
}
