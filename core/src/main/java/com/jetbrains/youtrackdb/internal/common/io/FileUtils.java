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
package com.jetbrains.youtrackdb.internal.common.io;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

public class FileUtils {

  public static final int KILOBYTE = 1024;
  public static final int MEGABYTE = 1048576;
  public static final int GIGABYTE = 1073741824;
  public static final long TERABYTE = 1099511627776L;

  private static final boolean useOldFileAPI;
  static final String MISSING_NATIVE_HELPER_WARNING =
      "The Java Native Access native helper is unavailable. The crash-atomic guarantee is not"
          + " available on this host. Using the portable move";

  static {
    var oldAPI = false;

    try {
      Class.forName("java.nio.file.FileSystemException");
    } catch (ClassNotFoundException ignore) {
      oldAPI = true;
    }

    useOldFileAPI = oldAPI;
  }

  public static long getSizeAsNumber(final Object iSize) {
    if (iSize == null) {
      throw new IllegalArgumentException("Size is null");
    }

    if (iSize instanceof Number number) {
      return number.longValue();
    }

    var size = iSize.toString();

    var number = true;
    for (var i = size.length() - 1; i >= 0; --i) {
      final var c = size.charAt(i);
      if (!Character.isDigit(c)) {
        if (i > 0 || (c != '-' && c != '+')) {
          number = false;
        }
        break;
      }
    }

    if (number) {
      return string2number(size).longValue();
    } else {
      size = size.toUpperCase(Locale.ENGLISH);
      var pos = size.indexOf("KB");
      if (pos > -1) {
        return (long) (string2number(size.substring(0, pos)).floatValue() * KILOBYTE);
      }

      pos = size.indexOf("MB");
      if (pos > -1) {
        return (long) (string2number(size.substring(0, pos)).floatValue() * MEGABYTE);
      }

      pos = size.indexOf("GB");
      if (pos > -1) {
        return (long) (string2number(size.substring(0, pos)).floatValue() * GIGABYTE);
      }

      pos = size.indexOf("TB");
      if (pos > -1) {
        return (long) (string2number(size.substring(0, pos)).floatValue() * TERABYTE);
      }

      pos = size.indexOf('B');
      if (pos > -1) {
        return (long) string2number(size.substring(0, pos)).floatValue();
      }

      pos = size.indexOf('%');
      if (pos > -1) {
        return (long) (-1 * string2number(size.substring(0, pos)).floatValue());
      }

      // RE-THROW THE EXCEPTION
      throw new IllegalArgumentException("Size " + size + " has a unrecognizable format");
    }
  }

  public static Number string2number(final String iText) {
    if (iText.indexOf('.') > -1) {
      return Double.parseDouble(iText);
    } else {
      return Long.parseLong(iText);
    }
  }

  public static String getSizeAsString(final long iSize) {
    if (iSize > TERABYTE) {
      return String.format("%2.2fTB", (float) iSize / TERABYTE);
    }
    if (iSize > GIGABYTE) {
      return String.format("%2.2fGB", (float) iSize / GIGABYTE);
    }
    if (iSize > MEGABYTE) {
      return String.format("%2.2fMB", (float) iSize / MEGABYTE);
    }
    if (iSize > KILOBYTE) {
      return String.format("%2.2fKB", (float) iSize / KILOBYTE);
    }

    return iSize + "b";
  }

  public static String getDirectory(String iPath) {
    iPath = getPath(iPath);
    var pos = iPath.lastIndexOf('/');
    if (pos == -1) {
      return "";
    }

    return iPath.substring(0, pos);
  }

  public static void createDirectoryTree(final String iFileName) {
    final var fileDirectories = iFileName.split("/", -1);
    for (var i = 0; i < fileDirectories.length - 1; ++i) {
      new File(fileDirectories[i]).mkdir();
    }
  }

  @Nullable public static String getPath(final String iPath) {
    if (iPath == null) {
      return null;
    }
    return iPath.replace('\\', '/');
  }

  public static void checkValidName(final String iFileName) throws IOException {
    if (iFileName.contains("..") || iFileName.contains("/") || iFileName.contains("\\")) {
      throw new IOException("Invalid file name '" + iFileName + "'");
    }
  }

  public static void deleteRecursively(final File rootFile) {
    deleteRecursively(rootFile, false);
  }

  public static void deleteRecursively(final File rootFile, boolean onlyDirs) {
    if (!rootFile.exists()) {
      return;
    }

    try {
      var rootPath = Paths.get(rootFile.getCanonicalPath());
      Files.walkFileTree(
          rootPath,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (!onlyDirs) {
                if (file != null && file.toFile() != null && file.toFile().exists()) {
                  file.toFile().delete();
                }
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
              if (dir != null && dir.toFile() != null && dir.toFile().exists()) {
                dir.toFile().delete();
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void deleteFolderIfEmpty(final File dir) {
    if (dir != null && dir.listFiles() != null && dir.listFiles().length == 0) {
      deleteRecursively(dir);
    }
  }

  @SuppressWarnings("resource")
  public static final void copyFile(final File source, final File destination) throws IOException {
    var sourceChannel = new FileInputStream(source).getChannel();
    var targetChannel = new FileOutputStream(destination).getChannel();
    sourceChannel.transferTo(0, sourceChannel.size(), targetChannel);
    sourceChannel.close();
    targetChannel.close();
  }

  public static final void copyDirectory(final File source, final File destination)
      throws IOException {
    if (!destination.exists()) {
      destination.mkdirs();
    }

    for (var f : source.listFiles()) {
      final var target = new File(destination.getAbsolutePath() + "/" + f.getName());
      if (f.isFile()) {
        copyFile(f, target);
      } else {
        copyDirectory(f, target);
      }
    }
  }

  public static boolean renameFile(File from, File to) throws IOException {
    if (useOldFileAPI) {
      return from.renameTo(to);
    }

    final var fileSystem = FileSystems.getDefault();

    final var fromPath = fileSystem.getPath(from.getAbsolutePath());
    final var toPath = fileSystem.getPath(to.getAbsolutePath());
    Files.move(fromPath, toPath);

    return true;
  }

  public static boolean delete(File file) throws IOException {
    if (!file.exists()) {
      return true;
    }

    if (useOldFileAPI) {
      return file.delete();
    }

    return FileUtilsJava7.delete(file);
  }

  /**
   * Prepares the path for a file creation or replacement. If the file pointed by the path already
   * exists, it will be deleted, a warning will be emitted to the log in this case. All absent
   * directories along the path will be created.
   *
   * @param path      the file path.
   * @param requester the requester of an operation being performed to produce user-friendly log
   *                  messages.
   * @param operation the description of an operation being performed to produce user-friendly log
   *                  messages. Use descriptions like "exporting", "backing up", etc.
   */
  public static void prepareForFileCreationOrReplacement(
      Path path, Object requester, String operation) throws IOException {
    if (Files.deleteIfExists(path)) {
      LogManager.instance().warn(requester, "'%s' deleted while %s", path, operation);
    }

    final var parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }

  /**
   * Tries to move a file from the source to the target atomically. If atomic move is not possible,
   * falls back to regular move.
   *
   * @param source    the source to move the file from.
   * @param target    the target to move the file to.
   * @param requester the requester of the move being performed to produce user-friendly log
   *                  messages.
   * @see Files#move(Path, Path, CopyOption...)
   * @see StandardCopyOption#ATOMIC_MOVE
   */
  public static void atomicMoveWithFallback(Path source, Path target, Object requester)
      throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ignore) {
      LogManager.instance()
          .warn(
              requester,
              "atomic file move is not possible, falling back to regular move (moving '%s' to"
                  + " '%s')",
              source,
              target);
      Files.move(source, target);
    }
  }

  /**
   * Durably promotes {@code source} to {@code target}. The source content is forced before an
   * atomic replacement. Unix systems then force the target directory. Windows uses a write-through
   * native replacement because the standard Java move does not provide that durability barrier.
   * If its native helper cannot load, Windows records one warning and uses the portable atomic move
   * with the pre-existing parent-directory barrier.
   *
   * <p>The operation fails closed when an atomic replacement or required durability barrier fails.
   * The Windows native-load fallback cannot promise crash atomicity. Current callers move within
   * one directory. A cross-directory caller must also force the source directory.
   */
  public static void durableAtomicMove(Path source, Path target, Object requester)
      throws IOException {
    durableAtomicMove(source, target, requester, IOUtils.isOsWindows(), FileUtils::forceDirectory);
  }

  static void durableAtomicMove(
      final Path source,
      final Path target,
      final boolean windows,
      final DirectoryForce directoryForce)
      throws IOException {
    durableAtomicMove(source, target, FileUtils.class, windows, directoryForce);
  }

  private static void durableAtomicMove(
      final Path source,
      final Path target,
      final Object requester,
      final boolean windows,
      final DirectoryForce directoryForce)
      throws IOException {
    if (windows) {
      durableAtomicMove(
          source,
          target,
          requester,
          true,
          directoryForce,
          WindowsDurableMove.BINDING,
          FileUtils::portableWindowsMove,
          FileUtils::warnAboutMissingNativeHelper,
          WindowsDurableMove.FALLBACK_WARNING_RECORDED);
    } else {
      durableAtomicMove(
          source, target, requester, false, directoryForce, null, null, null, null);
    }
  }

  static void durableAtomicMove(
      final Path source,
      final Path target,
      final Object requester,
      final boolean windows,
      final DirectoryForce directoryForce,
      final WindowsMoveBinding windowsBinding,
      final PortableMove portableMove,
      final NativeHelperWarning warning,
      final AtomicBoolean warningRecorded)
      throws IOException {
    final var sourceAttributes =
        Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!sourceAttributes.isRegularFile()) {
      throw new IOException("Durable atomic move source is not a regular file: " + source);
    }
    try (var channel =
        FileChannel.open(source, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      channel.force(true);
    }

    if (windows) {
      final var nativeMove = windowsBinding.nativeMove();
      if (nativeMove != null) {
        nativeMove.move(source, target);
        return;
      }

      final var loadFailure = windowsBinding.loadFailure();
      if (warningRecorded.compareAndSet(false, true)) {
        warning.warn(requester, MISSING_NATIVE_HELPER_WARNING, loadFailure);
      }
      try {
        portableMove.move(source, target, requester);
      } catch (IOException portableFailure) {
        final var failure =
            new IOException(
                "Portable move failed after the Windows native helper failed to load: native"
                    + " helper cause: "
                    + loadFailure
                    + "; portable move cause: "
                    + portableFailure,
                portableFailure);
        failure.addSuppressed(loadFailure);
        throw failure;
      }
      return;
    }

    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    final var parent = target.toAbsolutePath().getParent();
    if (parent != null) {
      directoryForce.force(parent);
    }
  }

  private static void forceDirectory(final Path directory) throws IOException {
    try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  static void portableWindowsMove(
      final Path source, final Path target, final Object requester) throws IOException {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    final var parent = target.toAbsolutePath().getParent();
    if (parent == null) {
      return;
    }

    FileChannel directoryChannel = null;
    try {
      directoryChannel = FileChannel.open(parent, StandardOpenOption.READ);
    } catch (IOException e) {
      LogManager.instance()
          .warn(
              requester,
              "Cannot open the parent directory of '%s' for fsync after the atomic move;"
                  + " continuing (the platform does not support directory channels)",
              e,
              target);
    }
    if (directoryChannel != null) {
      try (var openedDirectoryChannel = directoryChannel) {
        openedDirectoryChannel.force(true);
      }
    }
  }

  private static void warnAboutMissingNativeHelper(
      final Object requester, final String message, final Throwable loadFailure) {
    LogManager.instance().warn(requester, message, loadFailure);
  }

  @FunctionalInterface
  interface DirectoryForce {

    void force(Path directory) throws IOException;
  }

  @FunctionalInterface
  interface NativeMove {

    void move(Path source, Path target) throws IOException;
  }

  @FunctionalInterface
  interface WindowsBindingLoader {

    WindowsMoveBinding load();
  }

  @FunctionalInterface
  interface PortableMove {

    void move(Path source, Path target, Object requester) throws IOException;
  }

  @FunctionalInterface
  interface NativeHelperWarning {

    void warn(Object requester, String message, Throwable loadFailure);
  }

  record WindowsMoveBinding(NativeMove nativeMove, Throwable loadFailure) {

    static WindowsMoveBinding unavailable(final Throwable loadFailure) {
      return new WindowsMoveBinding(null, loadFailure);
    }
  }

  private static final class WindowsDurableMove {

    private static final int MOVE_FILE_REPLACE_EXISTING = 0x1;
    private static final int MOVE_FILE_WRITE_THROUGH = 0x8;
    private static final AtomicBoolean FALLBACK_WARNING_RECORDED = new AtomicBoolean();
    private static final WindowsMoveBinding BINDING = loadBinding();

    private WindowsDurableMove() {
    }

    private static WindowsMoveBinding loadBinding() {
      return loadWindowsMoveBinding(() -> {
        final var kernel32 =
            (Kernel32) Native.loadLibrary(
                "kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);
        return new WindowsMoveBinding(
            (source, target) -> move(kernel32, source, target), null);
      });
    }

    private static void move(
        final Kernel32 kernel32, final Path source, final Path target) throws IOException {
      final var moved =
          kernel32.MoveFileEx(
              new WString(toWindowsNativePath(source.toAbsolutePath().toString())),
              new WString(toWindowsNativePath(target.toAbsolutePath().toString())),
              MOVE_FILE_REPLACE_EXISTING | MOVE_FILE_WRITE_THROUGH);
      if (!moved) {
        throw new IOException(
            "Windows durable atomic move failed with error " + Native.getLastError());
      }
    }
  }

  static WindowsMoveBinding loadWindowsMoveBinding(final WindowsBindingLoader loader) {
    try {
      return loader.load();
    } catch (Throwable loadFailure) {
      // D192 requires every native-helper load failure to become a memorized fallback result.
      return WindowsMoveBinding.unavailable(loadFailure);
    }
  }

  /**
   * Adds the extended Windows namespace prefix to absolute drive and UNC paths. Ordinary absolute
   * paths are normalized first because the extended namespace does not interpret dot segments.
   * Relative paths and paths that already select a Windows device namespace remain unchanged.
   */
  static String toWindowsNativePath(final String path) {
    if (path.startsWith("\\\\?\\") || path.startsWith("\\\\.\\")) {
      return path;
    }
    if (path.startsWith("\\\\")) {
      final var serverEnd = path.indexOf('\\', 2);
      final var shareEnd = serverEnd < 0 ? -1 : path.indexOf('\\', serverEnd + 1);
      final var normalized =
          shareEnd < 0 ? path : normalizeWindowsAbsolutePath(path, shareEnd + 1);
      return "\\\\?\\UNC\\" + normalized.substring(2);
    }
    if (path.length() >= 3
        && Character.isLetter(path.charAt(0))
        && path.charAt(1) == ':'
        && path.charAt(2) == '\\') {
      return "\\\\?\\" + normalizeWindowsAbsolutePath(path, 3);
    }
    return path;
  }

  /**
   * Removes dot segments without accessing the file system. Windows applies the same lexical rule
   * before an ordinary path enters the extended namespace, including when a segment is a link.
   */
  private static String normalizeWindowsAbsolutePath(
      final String path, final int componentStart) {
    final var components = new ArrayDeque<String>();
    var start = componentStart;
    while (start <= path.length()) {
      var end = path.indexOf('\\', start);
      if (end < 0) {
        end = path.length();
      }
      final var component = path.substring(start, end);
      if (component.equals("..")) {
        if (!components.isEmpty()) {
          components.removeLast();
        }
      } else if (!component.isEmpty() && !component.equals(".")) {
        components.addLast(component);
      }
      start = end + 1;
    }

    final var normalized = new StringBuilder(path.substring(0, componentStart));
    for (var component : components) {
      if (normalized.charAt(normalized.length() - 1) != '\\') {
        normalized.append('\\');
      }
      normalized.append(component);
    }
    return normalized.toString();
  }

  private interface Kernel32 extends StdCallLibrary {

    boolean MoveFileEx(WString source, WString target, int flags);
  }
}
