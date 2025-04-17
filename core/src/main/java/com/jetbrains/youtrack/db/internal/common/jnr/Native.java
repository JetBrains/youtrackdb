/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrack.db.internal.common.jnr;

import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Memory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import jnr.constants.platform.Sysconf;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Native {

  private static final Logger logger = LoggerFactory.getLogger(Native.class);
  private static final String DEFAULT_MEMORY_CGROUP_PATH = "/sys/fs/cgroup/memory";

  private static volatile Native instance = null;
  private static final Lock initLock = new ReentrantLock();

  private static volatile POSIX posix;

  public static Native instance() {
    if (instance != null) {
      return instance;
    }

    initLock.lock();
    try {
      if (instance != null) {
        return instance;
      }

      if (IOUtils.isOsLinux()) {
        posix = POSIXFactory.getPOSIX();
      }

      instance = new Native();
    } finally {
      initLock.unlock();
    }

    return instance;
  }

  /**
   * Address space limit.
   */
  public static final int RLIMIT_AS = 9;

  public static final int RLIMIT_NOFILE = 7;

  /**
   * Prevent initialization outside singleton
   */
  private Native() {
  }

  /**
   * Detects limit of limit of open files.
   *
   * @param recommended recommended value of limit of open files.
   * @param defLimit    default value for limit of open files.
   * @return limit of open files, available for the system.
   */
  public int getOpenFilesLimit(boolean verbose, int recommended, int defLimit) {
    if (IOUtils.isOsLinux()) {
      try {
        final var rLimit = posix.getrlimit(Native.RLIMIT_NOFILE);
        if (rLimit.rlimCur() > 0) {
          if (verbose) {
            final var additionalArgs =
                new Object[]{rLimit.rlimCur(), rLimit.rlimCur() / 2 - 512};
            LogManager.instance()
                .info(
                    this,
                    "Detected limit of amount of simultaneously open files is %d, "
                        + " limit of open files for disk cache will be set to %d",
                    additionalArgs);
          }
          if (rLimit.rlimCur() < recommended) {
            LogManager.instance()
                .warn(
                    this,
                    "Value of limit of simultaneously open files is too small, recommended value is"
                        + " %d",
                    recommended);
          }
          return (int) rLimit.rlimCur() / 2 - 512;
        } else {
          if (verbose) {
            LogManager.instance().info(this, "Can not detect value of limit of open files.");
          }
        }
      } catch (final Exception e) {
        if (verbose) {
          LogManager.instance()
              .info(this, "Can not detect value of limit of open files.", new Object[]{e});
        }
      }
    } else if (IOUtils.isOsWindows()) {
      if (verbose) {
        LogManager.instance()
            .info(
                this,
                "Windows OS is detected, %d limit of open files will be set for the disk cache.",
                recommended);
      }
      return recommended;
    }

    if (verbose) {
      LogManager.instance().info(this, "Default limit of open files (%d) will be used.", defLimit);
    }
    return defLimit;
  }

  /**
   * @param printSteps Print all steps of discovering of memory limit in the log with {@code INFO}
   *                   level.
   * @return Amount of memory which are allowed to be consumed by application, and detects whether
   * YouTrackDB instance is running inside container. If <code>null</code> is returned then it was
   * impossible to detect amount of memory on machine.
   */
  @Nullable
  public MemoryLimitResult getMemoryLimit(final boolean printSteps) {
    // Perform several steps here:
    // 1. Fetch physical size available on machine
    // 2. Fetch soft limit
    // 3. Fetch cgroup soft limit
    // 4. Fetch cgroup hard limit
    // 5. Return the minimal value from the list of results

    var memoryLimit = getPhysicalMemorySize();
    var insideContainer = false;

    if (printSteps) {
      LogManager.instance()
          .info(
              this,
              "%d B/%d MB/%d GB of physical memory were detected on machine",
              memoryLimit,
              convertToMB(memoryLimit),
              convertToGB(memoryLimit));
    }

    if (IOUtils.isOsLinux()) {
      try {
        final var rLimit = posix.getrlimit(Native.RLIMIT_AS);
        if (printSteps) {
          final var additionalArgs =
              new Object[]{
                  rLimit.rlimCur(), convertToMB(rLimit.rlimCur()), convertToGB(rLimit.rlimCur())
              };
          LogManager.instance()
              .info(
                  this,
                  "Soft memory limit for this process is set to %d B/%d MB/%d GB",
                  additionalArgs);
        }
        memoryLimit = updateMemoryLimit(memoryLimit, rLimit.rlimCur());

        if (printSteps) {
          final var additionalArgs =
              new Object[]{
                  rLimit.rlimMax(), convertToMB(rLimit.rlimMax()), convertToGB(rLimit.rlimMax())
              };
          LogManager.instance()
              .info(
                  this,
                  "Hard memory limit for this process is set to %d B/%d MB/%d GB",
                  additionalArgs);
        }
        memoryLimit = updateMemoryLimit(memoryLimit, rLimit.rlimMax());
      } catch (final Exception e) {
        if (printSteps) {
          LogManager.instance().info(this, "Can not detect memory limit value.", new Object[]{e});
        }
      }

      final var memoryCGroupPath = findMemoryGCGroupPath();
      if (memoryCGroupPath != null) {
        if (printSteps) {
          LogManager.instance().info(this, "Path to 'memory' cgroup is '%s'", memoryCGroupPath);
        }
        final var memoryCGroupRoot = findMemoryGCRoot();

        if (printSteps) {
          LogManager.instance()
              .info(this, "Mounting path for memory cgroup controller is '%s'", memoryCGroupRoot);
        }

        var memoryCGroup = new File(memoryCGroupRoot, memoryCGroupPath);
        if (!memoryCGroup.exists()) {
          if (printSteps) {
            LogManager.instance()
                .info(
                    this,
                    "Can not find '%s' path for memory cgroup, it is supposed that process is"
                        + " running in container, will try to read root '%s' memory cgroup data",
                    memoryCGroup,
                    memoryCGroupRoot);
          }
          memoryCGroup = new File(memoryCGroupRoot);
          insideContainer = true;
        }

        final var softMemoryLimit = fetchCGroupSoftMemoryLimit(memoryCGroup, printSteps);
        memoryLimit = updateMemoryLimit(memoryLimit, softMemoryLimit);

        final var hardMemoryLimit = fetchCGroupHardMemoryLimit(memoryCGroup, printSteps);
        memoryLimit = updateMemoryLimit(memoryLimit, hardMemoryLimit);
      }
    }

    if (printSteps) {
      if (memoryLimit > 0) {
        LogManager.instance()
            .info(
                this,
                "Detected memory limit for current process is %d B/%d MB/%d GB",
                memoryLimit,
                convertToMB(memoryLimit),
                convertToGB(memoryLimit));
      } else {
        LogManager.instance().info(this, "Memory limit for current process is not set");
      }
    }
    if (memoryLimit <= 0) {
      return null;
    }
    return new MemoryLimitResult(memoryLimit, insideContainer);
  }

  public int getpagesize() throws LastErrorException {
    return (int) posix.sysconf(Sysconf._SC_PAGE_SIZE);
  }

  private long updateMemoryLimit(long memoryLimit, final long newMemoryLimit) {
    if (newMemoryLimit <= 0) {
      return memoryLimit;
    }

    if (memoryLimit <= 0) {
      memoryLimit = newMemoryLimit;
    }

    if (memoryLimit > newMemoryLimit) {
      memoryLimit = newMemoryLimit;
    }

    return memoryLimit;
  }

  private long fetchCGroupSoftMemoryLimit(final File memoryCGroup, final boolean printSteps) {
    final var softMemoryCGroupLimit = new File(memoryCGroup, "memory.soft_limit_in_bytes");
    if (softMemoryCGroupLimit.exists()) {
      try {
        final var memoryLimitReader = new FileReader(softMemoryCGroupLimit);
        try (final var bufferedMemoryLimitReader =
            new BufferedReader(memoryLimitReader)) {
          try {
            final var cgroupMemoryLimitValueStr = bufferedMemoryLimitReader.readLine();
            try {
              final var cgroupMemoryLimitValue = Long.parseLong(cgroupMemoryLimitValueStr);

              if (printSteps) {
                LogManager.instance()
                    .info(
                        this,
                        "cgroup soft memory limit is %d B/%d MB/%d GB",
                        cgroupMemoryLimitValue,
                        convertToMB(cgroupMemoryLimitValue),
                        convertToGB(cgroupMemoryLimitValue));
              }

              return cgroupMemoryLimitValue;
            } catch (final NumberFormatException nfe) {
              if (cgroupMemoryLimitValueStr.matches("\\d+")) {
                if (printSteps) {
                  LogManager.instance().info(this, "cgroup soft memory limit is not set");
                }
              } else {
                LogManager.instance()
                    .error(
                        this, "Can not read memory soft limit for cgroup '%s'", nfe, memoryCGroup);
              }
            }
          } catch (final IOException ioe) {
            LogManager.instance()
                .error(this, "Can not read memory soft limit for cgroup '%s'", ioe, memoryCGroup);
          }
        } catch (final IOException e) {
          LogManager.instance().error(this, "Error on closing the reader of soft memory limit", e);
        }
      } catch (final FileNotFoundException fnfe) {
        LogManager.instance()
            .error(this, "Can not read memory soft limit for cgroup '%s'", fnfe, memoryCGroup);
      }
    } else {
      if (printSteps) {
        LogManager.instance()
            .info(this, "Can not read memory soft limit for cgroup '%s'", memoryCGroup);
      }
    }

    return -1;
  }

  private long fetchCGroupHardMemoryLimit(final File memoryCGroup, final boolean printSteps) {
    final var hardMemoryCGroupLimit = new File(memoryCGroup, "memory.limit_in_bytes");
    if (hardMemoryCGroupLimit.exists()) {
      try {
        final var memoryLimitReader = new FileReader(hardMemoryCGroupLimit);

        try (final var bufferedMemoryLimitReader =
            new BufferedReader(memoryLimitReader)) {
          try {
            final var cgroupMemoryLimitValueStr = bufferedMemoryLimitReader.readLine();
            try {
              final var cgroupMemoryLimitValue = Long.parseLong(cgroupMemoryLimitValueStr);

              if (printSteps) {
                LogManager.instance()
                    .info(
                        this,
                        "cgroup hard memory limit is %d B/%d MB/%d GB",
                        cgroupMemoryLimitValue,
                        convertToMB(cgroupMemoryLimitValue),
                        convertToGB(cgroupMemoryLimitValue));
              }

              return cgroupMemoryLimitValue;
            } catch (final NumberFormatException nfe) {
              if (cgroupMemoryLimitValueStr.matches("\\d+")) {
                if (printSteps) {
                  LogManager.instance().info(this, "cgroup hard memory limit is not set");
                }
              } else {
                LogManager.instance()
                    .error(
                        this, "Can not read memory hard limit for cgroup '%s'", nfe, memoryCGroup);
              }
            }
          } catch (final IOException ioe) {
            LogManager.instance()
                .error(this, "Can not read memory hard limit for cgroup '%s'", ioe, memoryCGroup);
          }
        } catch (final IOException e) {
          LogManager.instance().error(this, "Error on closing the reader of hard memory limit", e);
        }
      } catch (final FileNotFoundException fnfe) {
        LogManager.instance()
            .error(this, "Can not read memory hard limit for cgroup '%s'", fnfe, memoryCGroup);
      }
    } else {
      if (printSteps) {
        LogManager.instance()
            .info(this, "Can not read memory hard limit for cgroup '%s'", memoryCGroup);
      }
    }

    return -1;
  }

  private String findMemoryGCGroupPath() {
    String memoryCGroupPath = null;

    // fetch list of cgroups to which given process belongs to
    final var cgroupList = new File("/proc/self/cgroup");
    if (cgroupList.exists()) {
      try {
        final var cgroupListReader = new FileReader(cgroupList);

        try (final var bufferedCGroupReader = new BufferedReader(cgroupListReader)) {
          String cgroupData;
          try {
            while ((cgroupData = bufferedCGroupReader.readLine()) != null) {
              final var cgroupParts = cgroupData.split(":");
              // we need only memory controller
              if (cgroupParts[1].equals("memory")) {
                memoryCGroupPath = cgroupParts[2];
              }
            }
          } catch (final IOException ioe) {
            LogManager.instance()
                .error(
                    this,
                    "Error during reading of details of list of cgroups for the current process, "
                        + "no restrictions applied by cgroups will be taken into account",
                    ioe);
            memoryCGroupPath = null;
          }

        } catch (final IOException e) {
          LogManager.instance()
              .error(
                  this,
                  "Error during closing of reader which reads details of list of cgroups for the"
                      + " current process",
                  e);
        }
      } catch (final FileNotFoundException fnfe) {
        LogManager.instance()
            .warn(
                this,
                "Can not retrieve list of cgroups to which process belongs, "
                    + "no restrictions applied by cgroups will be taken into account");
      }
    }

    return memoryCGroupPath;
  }

  private String findMemoryGCRoot() {
    String memoryCGroupRoot = null;

    // fetch all mount points and find one to which cgroup memory controller is mounted
    final var procMounts = new File("/proc/mounts");
    if (procMounts.exists()) {
      final FileReader mountsReader;
      try {
        mountsReader = new FileReader(procMounts);
        try (var bufferedMountsReader = new BufferedReader(mountsReader)) {
          String fileSystem;
          while ((fileSystem = bufferedMountsReader.readLine()) != null) {
            // file system type \s+ mount point \s+ etc.
            final var fsParts = fileSystem.split("\\s+");
            if (fsParts.length == 0) {
              continue;
            }

            final var fsType = fsParts[2];
            // all cgroup controllers have "cgroup" as file system type
            if (fsType.equals("cgroup")) {
              // get mounting path of cgroup
              final var fsMountingPath = fsParts[1];
              final var fsPathParts = fsMountingPath.split(File.separator);
              if (fsPathParts[fsPathParts.length - 1].equals("memory")) {
                memoryCGroupRoot = fsMountingPath;
              }
            }
          }
        } catch (final IOException e) {
          LogManager.instance()
              .error(this, "Error during reading a list of mounted file systems", e);
          memoryCGroupRoot = DEFAULT_MEMORY_CGROUP_PATH;
        }

      } catch (final FileNotFoundException fnfe) {
        memoryCGroupRoot = DEFAULT_MEMORY_CGROUP_PATH;
      }
    }

    if (memoryCGroupRoot == null) {
      memoryCGroupRoot = DEFAULT_MEMORY_CGROUP_PATH;
    }

    return memoryCGroupRoot;
  }

  /**
   * Obtains the total size in bytes of the installed physical memory on this machine. Note that on
   * some VMs it's impossible to obtain the physical memory size, in this case the return value will
   * {@code -1}.
   *
   * @return the total physical memory size in bytes or {@code <= 0} if the size can't be obtained.
   */
  private long getPhysicalMemorySize() {
    long osMemory = -1;

    try {
      final var mBeanServer = ManagementFactory.getPlatformMBeanServer();
      final var attribute =
          mBeanServer.getAttribute(
              new ObjectName("java.lang", "type", "OperatingSystem"), "TotalPhysicalMemorySize");

      if (attribute != null) {
        if (attribute instanceof Long) {
          osMemory = (Long) attribute;
        } else {
          try {
            osMemory = Long.parseLong(attribute.toString());
          } catch (final NumberFormatException e) {
            if (!logger.isDebugEnabled()) {
              LogManager.instance()
                  .warn(Memory.class, "Unable to determine the amount of installed RAM.");
            } else {
              LogManager.instance()
                  .debug(Memory.class, "Unable to determine the amount of installed RAM.", logger,
                      e);
            }
          }
        }
      } else {
        if (!logger.isDebugEnabled()) {
          LogManager.instance()
              .warn(Memory.class, "Unable to determine the amount of installed RAM.");
        }
      }
    } catch (MalformedObjectNameException
             | AttributeNotFoundException
             | InstanceNotFoundException
             | MBeanException
             | ReflectionException e) {
      if (!logger.isDebugEnabled()) {
        LogManager.instance()
            .warn(Memory.class, "Unable to determine the amount of installed RAM.");
      } else {
        LogManager.instance()
            .debug(Memory.class, "Unable to determine the amount of installed RAM.", logger, e);
      }
    } catch (final RuntimeException e) {
      LogManager.instance()
          .warn(
              Memory.class, "Unable to determine the amount of installed RAM.", new Object[]{e});
    }

    return osMemory;
  }

  private static long convertToMB(final long bytes) {
    if (bytes < 0) {
      return bytes;
    }

    return bytes / (1024 * 1024);
  }

  private static long convertToGB(final long bytes) {
    if (bytes < 0) {
      return bytes;
    }

    return bytes / (1024 * 1024 * 1024);
  }

  public static final class MemoryLimitResult {

    public final long memoryLimit;
    public final boolean insideContainer;

    MemoryLimitResult(final long memoryLimit, final boolean insideContainer) {
      this.memoryLimit = memoryLimit;
      this.insideContainer = insideContainer;
    }
  }

  public long blockSize(String path) {
    // TODO:When upgrading to java 11 use FileStore.getBlockSize()
    try (var file = new RandomAccessFile(path, "r")) {
      return posix.fstat(file.getFD()).blockSize();
    } catch (Exception e) {
      LogManager.instance().warn(this, "Error detecting block size ignoring", e);
      return 4096;
    }
  }

  public boolean isOsRoot() {
    return IOUtils.isOsLinux() && posix.getegid() == 0;
  }
}
