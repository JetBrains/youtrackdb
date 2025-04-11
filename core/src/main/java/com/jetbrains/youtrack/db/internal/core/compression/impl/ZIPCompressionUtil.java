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

package com.jetbrains.youtrack.db.internal.core.compression.impl;

import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;

/**
 * Compression Utility.
 */
public class ZIPCompressionUtil {

  public static List<String> compressDirectory(
      final String sourceFolderName,
      final ZipOutputStream zos,
      final String[] iSkipFileExtensions,
      final CommandOutputListener iOutput)
      throws IOException {
    final List<String> compressedFiles = new ArrayList<>();
    addFolder(
        zos, sourceFolderName, sourceFolderName, iSkipFileExtensions, iOutput, compressedFiles);
    return compressedFiles;
  }

  /**
   * Extract zipfile to outdir with complete directory structure
   */
  public static void uncompressDirectory(
      final InputStream in, final String out, final CommandOutputListener iListener)
      throws IOException {
    final var outdir = new File(out);
    final var targetDirPath = outdir.getCanonicalPath() + File.separator;

    try (var zin = new ZipInputStream(in)) {
      ZipEntry entry;
      String name;
      String dir;
      while ((entry = zin.getNextEntry()) != null) {
        name = entry.getName();

        final var file = new File(outdir, name);
        if (!file.getCanonicalPath().startsWith(targetDirPath)) {
          throw new IOException(
              "Expanding '"
                  + entry.getName()
                  + "' would create file outside of directory '"
                  + outdir
                  + "'");
        }

        if (entry.isDirectory()) {
          mkdirs(outdir, name);
          continue;
        }

        /*
         * this part is necessary because file entry can come before directory entry where is file located i.e.: /foo/foo.txt /foo/
         */
        dir = getDirectoryPart(name);
        if (dir != null) {
          mkdirs(outdir, dir);
        }

        extractFile(zin, outdir, name, iListener);
      }
    }
  }

  private static void extractFile(
      final ZipInputStream in,
      final File outdir,
      final String name,
      final CommandOutputListener iListener)
      throws IOException {
    if (iListener != null) {
      iListener.onMessage("\n- Uncompressing file " + name + "...");
    }

    // Validate the name to prevent path traversal
    if (name.contains("..") ||
        !name.isEmpty() && name.charAt(0) == '/' ||
        !name.isEmpty() && name.charAt(0) == '\\'
    ) {
      throw new IOException("Invalid entry name: " + name);
    }

    try (var out =
        new BufferedOutputStream(new FileOutputStream(new File(outdir, name)))) {
      IOUtils.copyStream(in, out);
    }
  }

  private static void mkdirs(final File outdir, final String path) {
    final var d = new File(outdir, path);
    if (!d.exists()) {
      d.mkdirs();
    }
  }

  @Nullable
  private static String getDirectoryPart(final String name) {
    var path = Paths.get(name);
    var parent = path.getParent();
    if (parent != null) {
      return parent.toString();
    }

    return null;
  }

  private static void addFolder(
      ZipOutputStream zos,
      String path,
      String baseFolderName,
      final String[] iSkipFileExtensions,
      final CommandOutputListener iOutput,
      final List<String> iCompressedFiles)
      throws IOException {

    var f = new File(path);
    if (!f.exists()) {
      var entryName = path.substring(baseFolderName.length() + 1);
      for (var skip : iSkipFileExtensions) {
        if (entryName.endsWith(skip)) {
          return;
        }
      }
    }
    if (f.exists()) {
      if (f.isDirectory()) {
        final var files = f.listFiles();
        if (files != null) {
          for (var file : files) {
            addFolder(
                zos,
                file.getAbsolutePath(),
                baseFolderName,
                iSkipFileExtensions,
                iOutput,
                iCompressedFiles);
          }
        }
      } else {
        // add file
        // extract the relative name for entry purpose
        var entryName = path.substring(baseFolderName.length() + 1);

        if (iSkipFileExtensions != null) {
          for (var skip : iSkipFileExtensions) {
            if (entryName.endsWith(skip)) {
              return;
            }
          }
        }

        iCompressedFiles.add(path);

        addFile(zos, path, entryName, iOutput);
      }

    } else {
      throw new IllegalArgumentException("Directory " + path + " not found");
    }
  }

  /**
   * Compresses the given files stored at the given base directory into a zip archive.
   *
   * @param baseDirectory    the base directory where files are stored.
   * @param fileNames        the file names map, keys are the file names stored on disk, values are
   *                         the file names to be stored in a zip archive.
   * @param output           the output stream.
   * @param listener         the command listener.
   * @param compressionLevel the desired compression level.
   */
  public static void compressFiles(
      String baseDirectory,
      Map<String, String> fileNames,
      OutputStream output,
      CommandOutputListener listener,
      int compressionLevel)
      throws IOException {
    final var zipOutputStream = new ZipOutputStream(output);
    zipOutputStream.setComment("YouTrackDB Backup executed on " + new Date());
    try {
      zipOutputStream.setLevel(compressionLevel);
      for (var entry : fileNames.entrySet()) {
        addFile(zipOutputStream, baseDirectory + "/" + entry.getKey(), entry.getValue(), listener);
      }
    } finally {
      zipOutputStream.close();
    }
  }

  private static void addFile(
      final ZipOutputStream zos,
      final String folderName,
      final String entryName,
      final CommandOutputListener iOutput)
      throws IOException {
    final var begin = System.currentTimeMillis();

    if (iOutput != null) {
      iOutput.onMessage("\n- Compressing file " + entryName + "...");
    }

    final var ze = new ZipEntry(entryName);
    zos.putNextEntry(ze);
    try {
      final var in = new FileInputStream(folderName);
      try {
        IOUtils.copyStream(in, zos);
      } finally {
        in.close();
      }
    } catch (IOException e) {
      if (iOutput != null) {
        iOutput.onMessage("error: " + e);
      }

      LogManager.instance()
          .error(ZIPCompressionUtil.class, "Cannot compress file: %s", e, folderName);
      throw e;
    } finally {
      zos.closeEntry();
    }

    if (iOutput != null) {
      final var ratio = ze.getSize() > 0 ? 100 - (ze.getCompressedSize() * 100 / ze.getSize()) : 0;

      iOutput.onMessage(
          "ok size="
              + FileUtils.getSizeAsString(ze.getSize())
              + " compressedSize="
              + ze.getCompressedSize()
              + " ratio="
              + ratio
              + "%% elapsed="
              + IOUtils.getTimeAsString(System.currentTimeMillis() - begin));
    }
  }
}
