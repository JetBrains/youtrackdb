package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared JMH state for LDBC SNB benchmarks.
 * Creates and loads a YouTrackDB database from LDBC SF 0.1 dataset on first run,
 * reuses the existing database on subsequent runs.
 *
 * <p>The dataset uses LDBC datagen v1.0.0 CsvCompositeMergeForeign format, where
 * 1-to-N relationships are embedded as foreign key columns in entity CSV files.
 * The dataset is automatically downloaded from the LDBC SURF repository if not
 * present locally.
 *
 * <p>Configure via system properties:
 * <ul>
 *   <li>{@code -Dldbc.dataset.path=/path/to/sf0.1} - path to LDBC dataset root
 *       (must contain static/ and dynamic/ subdirectories).
 *       If not set, defaults to {@code ./target/ldbc-dataset/sf0.1} and
 *       auto-downloads the dataset.</li>
 *   <li>{@code -Dldbc.db.path=./target/ldbc-bench-db} - path to store the database</li>
 *   <li>{@code -Dldbc.batch.size=1000} - batch size for data loading</li>
 *   <li>{@code -Dldbc.scale.factor=0.1} - scale factor for auto-download</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class LdbcBenchmarkState {

  private static final Logger log = LoggerFactory.getLogger(LdbcBenchmarkState.class);

  private static final String DB_NAME = "ldbc_benchmark";
  private static final int MAX_PARAMS = 200;
  private static final int DEFAULT_BATCH_SIZE = 1000;

  private static final String LDBC_DATASET_BASE_URL =
      "https://repository.surfsara.nl/datasets/cwi/snb/files";
  private static final String LDBC_SERIALIZER =
      "social_network-csv_composite-longdateformatter";

  YouTrackDB db;
  YTDBGraphTraversalSource traversal;

  // Query parameters - populated after data load
  long[] personIds;
  long[] messageIds;
  String[] firstNames;
  String[] tagNames;
  String[] countryNames;
  String[] tagClassNames;
  Date[] messageDates;

  private final AtomicLong counter = new AtomicLong();

  /**
   * Advances the shared counter and returns the new index.
   * Call this once per benchmark invocation, then use the returned index
   * for all parameter lookups to keep them consistent.
   */
  public long nextIndex() {
    return counter.getAndIncrement();
  }

  public long personId(long idx) {
    return personIds[(int) (idx % personIds.length)];
  }

  public long personId2(long idx) {
    return personIds[(int) ((idx + personIds.length / 2) % personIds.length)];
  }

  public long messageId(long idx) {
    return messageIds[(int) (idx % messageIds.length)];
  }

  public String firstName(long idx) {
    return firstNames[(int) (idx % firstNames.length)];
  }

  public String tagName(long idx) {
    return tagNames[(int) (idx % tagNames.length)];
  }

  public String countryName(long idx) {
    return countryNames[(int) (idx % countryNames.length)];
  }

  public String countryName2(long idx) {
    return countryNames[(int) ((idx + 1) % countryNames.length)];
  }

  public String tagClassName(long idx) {
    return tagClassNames[(int) (idx % tagClassNames.length)];
  }

  public Date maxDate(long idx) {
    return messageDates[(int) (idx % messageDates.length)];
  }

  /**
   * Executes a SQL query within a transaction and returns all result rows.
   */
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> executeSql(String sql, Object... keyValues) {
    return traversal.computeInTx(g -> {
      var ytg = (YTDBGraphTraversalSource) g;
      return ytg.yql(sql, keyValues).toList().stream()
          .map(obj -> (Map<String, Object>) obj)
          .toList();
    });
  }

  @Setup(Level.Trial)
  public void setup() throws Exception {
    String scaleFactor = System.getProperty("ldbc.scale.factor", "0.1");
    String datasetPath = System.getProperty("ldbc.dataset.path",
        "./target/ldbc-dataset/sf" + scaleFactor);
    String dbPath = System.getProperty("ldbc.db.path", "./target/ldbc-bench-db");
    int batchSize = Integer.getInteger("ldbc.batch.size", DEFAULT_BATCH_SIZE);

    Path datasetDir = Path.of(datasetPath);

    // Auto-download dataset if not present
    if (!Files.exists(datasetDir.resolve("static"))
        || !Files.exists(datasetDir.resolve("dynamic"))) {
      downloadDataset(datasetDir, scaleFactor);
    }

    db = YourTracks.instance(dbPath);

    if (!db.exists(DB_NAME)) {
      log.info("Creating and loading LDBC database from: {}", datasetDir);
      db.create(DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");
      traversal = db.openTraversal(DB_NAME, "admin", "admin");
      createSchema();
      loadData(datasetDir, batchSize);
      log.info("Database loaded successfully");
    } else {
      log.info("Using existing LDBC database at: {}", dbPath);
      traversal = db.openTraversal(DB_NAME, "admin", "admin");
    }

    sampleQueryParameters();
    log.info(
        "Benchmark state ready: {} persons, {} messages, {} tags, {} countries",
        personIds.length, messageIds.length, tagNames.length, countryNames.length);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    try {
      if (traversal != null) {
        traversal.close();
      }
    } catch (Exception e) {
      log.warn("Error closing traversal", e);
    }
    try {
      if (db != null) {
        db.close();
      }
    } catch (Exception e) {
      log.warn("Error closing database", e);
    }
  }

  // ==================== DATASET DOWNLOAD ====================

  private void downloadDataset(Path targetDir, String scaleFactor)
      throws Exception {
    String archiveName = LDBC_SERIALIZER + "-sf" + scaleFactor + ".tar.zst";
    String url =
        LDBC_DATASET_BASE_URL + "/" + LDBC_SERIALIZER + "/" + archiveName;

    Path tempDir = targetDir.getParent().resolve("tmp");
    Files.createDirectories(tempDir);
    Path archivePath = tempDir.resolve(archiveName);

    if (!Files.exists(archivePath)) {
      log.info("Downloading LDBC SF {} dataset from: {}", scaleFactor, url);
      log.info("This may take a few minutes for the first run...");
      downloadFile(url, archivePath);
      log.info("Download complete: {}", archivePath);
    } else {
      log.info("Using cached archive: {}", archivePath);
    }

    log.info("Extracting dataset...");
    Files.createDirectories(targetDir);
    extractTarZst(archivePath, targetDir);

    // Handle nested directory structure - move files up if needed
    Path nestedDir = findDataDir(targetDir);
    if (nestedDir != null && !nestedDir.equals(targetDir)) {
      log.info("Moving extracted data from {} to {}", nestedDir, targetDir);
      try (var files = Files.list(nestedDir)) {
        for (Path file : files.toList()) {
          Path dest = targetDir.resolve(file.getFileName());
          if (!Files.exists(dest)) {
            Files.move(file, dest);
          }
        }
      }
    }

    log.info("Dataset extracted to: {}", targetDir);
  }

  private Path findDataDir(Path root) throws IOException {
    if (Files.exists(root.resolve("static"))
        && Files.exists(root.resolve("dynamic"))) {
      return root;
    }
    // Search recursively for static/dynamic directories (handles nested
    // datagen output like social_network/graphs/csv/raw/composite-merged-fk/)
    try (var walker = Files.walk(root, 10)) {
      return walker
          .filter(Files::isDirectory)
          .filter(d -> Files.exists(d.resolve("static"))
              && Files.exists(d.resolve("dynamic")))
          .findFirst()
          .orElse(null);
    }
  }

  /**
   * Downloads a file from a URL using curl (with --insecure for envs with
   * incomplete CA bundles), falling back to wget, then Java.
   *
   * <p>The SURF Data Repository may return HTTP 409 when files are on tape
   * storage. In that case we trigger staging via their REST API and poll until
   * the file is online.
   */
  private void downloadFile(String url, Path target) throws Exception {
    String targetAbs = target.toAbsolutePath().toString();

    // SURF tape-storage handling: stage the file if needed
    stageIfSurfOffline(url);

    // Strategy 1: curl
    if (tryExec("curl", "-fSL", "--insecure", "-o", targetAbs, url) == 0
        && Files.exists(target) && Files.size(target) > 0) {
      return;
    }
    Files.deleteIfExists(target);
    log.info("curl not available or failed, trying wget...");

    // Strategy 2: wget
    if (tryExec(
        "wget", "-q", "--no-check-certificate", "-O", targetAbs, url) == 0
        && Files.exists(target) && Files.size(target) > 0) {
      return;
    }
    Files.deleteIfExists(target);
    log.info("wget not available or failed, trying Java URL.openStream()...");

    // Strategy 3: Java (may fail with SSL cert issues in some environments)
    try (InputStream in = URI.create(url).toURL().openStream()) {
      Files.copy(in, target);
      return;
    } catch (Exception e) {
      Files.deleteIfExists(target);
      throw new IOException(
          "Failed to download " + url + ". "
              + "Install curl or wget, or download manually to: " + target,
          e);
    }
  }

  /**
   * SURF Data Repository stores large files on tape. An initial request returns
   * HTTP 409 with {"error":"File is offline"}. We must POST to the stage
   * endpoint and poll until the status becomes "ONL" (online).
   */
  private void stageIfSurfOffline(String url) throws Exception {
    if (!url.contains("repository.surfsara.nl")) {
      return;
    }
    ProcessBuilder pb = new ProcessBuilder(
        "curl", "-skI", "-o", "/dev/null", "-w", "%{http_code}", url);
    pb.redirectErrorStream(true);
    Process proc = pb.start();
    String httpCode =
        new String(proc.getInputStream().readAllBytes()).trim();
    proc.waitFor();

    if (!"409".equals(httpCode)) {
      return;
    }

    pb = new ProcessBuilder("curl", "-sk", url);
    pb.redirectErrorStream(true);
    proc = pb.start();
    String body =
        new String(proc.getInputStream().readAllBytes()).trim();
    proc.waitFor();

    String stageUrl = extractJsonValue(body, "stage");
    String statusUrl = extractJsonValue(body, "status");

    if (stageUrl == null || statusUrl == null) {
      log.warn(
          "Could not parse SURF staging URLs from response: {}", body);
      return;
    }

    log.info("SURF file is offline (tape storage). Requesting staging...");
    tryExec("curl", "-sk", "-X", "POST", stageUrl);

    for (int i = 0; i < 40; i++) {
      Thread.sleep(15_000);
      pb = new ProcessBuilder("curl", "-sk", statusUrl);
      pb.redirectErrorStream(true);
      proc = pb.start();
      String status =
          new String(proc.getInputStream().readAllBytes()).trim();
      proc.waitFor();

      if (status.contains("\"ONL\"")) {
        log.info("SURF file is now online, proceeding with download.");
        return;
      }
      String stateCode = extractJsonValue(status, "status");
      log.info("Waiting for SURF staging... status={} ({}/40)",
          stateCode, i + 1);
    }
    log.warn(
        "SURF staging timed out after 10 minutes. Attempting download anyway.");
  }

  private static String extractJsonValue(String json, String key) {
    String pattern = "\"" + key + "\"";
    int idx = json.indexOf(pattern);
    if (idx < 0) {
      return null;
    }
    int valueStart = json.indexOf('"', idx + pattern.length() + 1);
    if (valueStart < 0) {
      return null;
    }
    int valueEnd = json.indexOf('"', valueStart + 1);
    if (valueEnd < 0) {
      return null;
    }
    return json.substring(valueStart + 1, valueEnd).replace("\\/", "/");
  }

  /**
   * Extracts a .tar.zst archive. Tries three strategies in order:
   * 1. tar --use-compress-program=zstd (requires zstd CLI)
   * 2. python3 with zstandard + tarfile modules
   * 3. Fails with an actionable error message
   */
  private void extractTarZst(Path archive, Path targetDir) throws Exception {
    String archiveAbs = archive.toAbsolutePath().toString();
    String targetAbs = targetDir.toAbsolutePath().toString();

    if (tryExec("tar", "--use-compress-program=zstd", "-xf", archiveAbs,
        "-C", targetAbs) == 0) {
      return;
    }
    log.info("zstd CLI not found, falling back to python3...");

    String pyScript = String.join("\n",
        "import zstandard, tarfile, os, sys",
        "archive = sys.argv[1]",
        "target = os.path.realpath(sys.argv[2])",
        "def safe_filter(member, path):",
        "    resolved = os.path.realpath(os.path.join(path, member.name))",
        "    if not resolved.startswith(path + os.sep)"
            + " and resolved != path:",
        "        raise Exception('Path traversal detected: ' + member.name)",
        "    return member",
        "with open(archive, 'rb') as fh:",
        "    dctx = zstandard.ZstdDecompressor()",
        "    with dctx.stream_reader(fh) as reader:",
        "        with tarfile.open(fileobj=reader, mode='r|') as tf:",
        "            tf.extractall(path=target, filter=safe_filter)");
    if (tryExec("python3", "-c", pyScript, archiveAbs, targetAbs) == 0) {
      return;
    }

    throw new IOException(
        "Failed to extract " + archive.getFileName() + ". "
            + "Install one of: zstd CLI (apt/brew/dnf install zstd) "
            + "or python3 + zstandard (pip install zstandard)");
  }

  private int tryExec(String... command) throws Exception {
    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.inheritIO();
      return pb.start().waitFor();
    } catch (IOException e) {
      return -1;
    }
  }

  // ==================== SCHEMA ====================

  private static final String SCHEMA_RESOURCE = "/ldbc-schema.sql";

  /**
   * Reads SQL statements from a classpath resource file.
   * Blank lines and lines starting with {@code --} are skipped.
   * Trailing semicolons are stripped from each statement.
   */
  static List<String> loadSqlStatements(String resource) {
    try (InputStream is =
        LdbcBenchmarkState.class.getResourceAsStream(resource)) {
      if (is == null) {
        throw new IllegalStateException("Resource not found: " + resource);
      }
      try (var reader = new BufferedReader(
          new InputStreamReader(is, StandardCharsets.UTF_8))) {
        return reader.lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("--"))
            .map(line -> line.endsWith(";")
                ? line.substring(0, line.length() - 1) : line)
            .toList();
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read " + resource, e);
    }
  }

  private void createSchema() {
    List<String> statements = loadSqlStatements(SCHEMA_RESOURCE);
    log.info("Creating LDBC schema ({} statements)...", statements.size());
    traversal.executeInTx(g -> {
      var ytg = (YTDBGraphTraversalSource) g;
      for (String sql : statements) {
        ytg.yql(sql).iterate();
      }
    });
  }

  // ==================== DATA LOADING ====================

  /**
   * Loads all LDBC data from CsvCompositeMergeForeign format. In this format,
   * 1-to-N relationships are embedded as foreign key columns in entity files,
   * so edges are created inline during entity loading. Remaining N-to-M
   * relationships are loaded from separate edge CSV files.
   */
  private void loadData(Path datasetRoot, int batchSize) throws Exception {
    Path staticDir = datasetRoot.resolve("static");
    Path dynamicDir = datasetRoot.resolve("dynamic");

    if (!Files.exists(staticDir) || !Files.exists(dynamicDir)) {
      throw new IllegalArgumentException(
          "Dataset directory must contain static/ and dynamic/"
              + " subdirectories: " + datasetRoot);
    }

    long start = System.currentTimeMillis();

    // ---- Static entities with embedded FK edges ----
    loadPlaces(staticDir, batchSize);
    loadTagClasses(staticDir, batchSize);
    loadTags(staticDir, batchSize);
    loadOrganisations(staticDir, batchSize);

    // ---- Dynamic entities with embedded FK edges ----
    loadPersons(dynamicDir, batchSize);
    loadForums(dynamicDir, batchSize);
    loadPosts(dynamicDir, batchSize);
    loadComments(dynamicDir, batchSize);

    // ---- Dynamic N-to-M edges from separate files ----
    loadKnowsEdges(dynamicDir, batchSize);
    loadNamedEdge(dynamicDir, "Person_hasInterest_Tag",
        "HAS_INTEREST", "Person", "Tag",
        "PersonId", "TagId", null, null, batchSize);
    loadNamedEdge(dynamicDir, "Person_studyAt_University",
        "STUDY_AT", "Person", "Organisation",
        "PersonId", "UniversityId", "classYear", "int", batchSize);
    loadNamedEdge(dynamicDir, "Person_workAt_Company",
        "WORK_AT", "Person", "Organisation",
        "PersonId", "CompanyId", "workFrom", "int", batchSize);
    loadNamedEdge(dynamicDir, "Person_likes_Post",
        "LIKES", "Person", "Post",
        "PersonId", "PostId", "creationDate", "long", batchSize);
    loadNamedEdge(dynamicDir, "Person_likes_Comment",
        "LIKES", "Person", "Comment",
        "PersonId", "CommentId", "creationDate", "long", batchSize);
    loadNamedEdge(dynamicDir, "Forum_hasMember_Person",
        "HAS_MEMBER", "Forum", "Person",
        "ForumId", "PersonId", "creationDate", "joinDate", batchSize);
    loadNamedEdge(dynamicDir, "Forum_hasTag_Tag",
        "HAS_TAG", "Forum", "Tag",
        "ForumId", "TagId", null, null, batchSize);
    loadNamedEdge(dynamicDir, "Post_hasTag_Tag",
        "HAS_TAG", "Post", "Tag",
        "PostId", "TagId", null, null, batchSize);
    loadNamedEdge(dynamicDir, "Comment_hasTag_Tag",
        "HAS_TAG", "Comment", "Tag",
        "CommentId", "TagId", null, null, batchSize);

    long duration = System.currentTimeMillis() - start;
    log.info("Data loading completed in {}ms ({} seconds)",
        duration, duration / 1000.0);
  }

  // ---- Static entity loaders ----

  private void loadPlaces(Path staticDir, int batchSize) throws IOException {
    // First pass: insert all Place vertices
    long count = processEntityCsv(staticDir, "Place", batchSize, (hdr, batch) -> {
      int iId = hdr.get("id");
      int iName = hdr.get("name");
      int iUrl = hdr.get("url");
      int iType = hdr.get("type");
      String sql = "INSERT INTO Place SET"
          + " id = :id, name = :name, url = :url, type = :type";
      traversal.executeInTx(g -> {
        var ytg = (YTDBGraphTraversalSource) g;
        for (String[] f : batch) {
          ytg.yql(sql,
              "id", Long.parseLong(f[iId]),
              "name", f[iName], "url", f[iUrl],
              "type", f[iType]).iterate();
        }
      });
    });
    log.info("Loaded {} Place vertices", count);

    // Second pass: create IS_PART_OF edges from PartOfPlaceId FK
    long edgeCount = processEntityCsv(staticDir, "Place", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iFk = hdr.get("PartOfPlaceId");
          String sql = "CREATE EDGE IS_PART_OF"
              + " FROM (SELECT FROM Place WHERE id = :fromId)"
              + " TO (SELECT FROM Place WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              if (!f[iFk].isEmpty()) {
                ytg.yql(sql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iFk])).iterate();
              }
            }
          });
        });
    log.info("Loaded IS_PART_OF edges for {} Place rows", edgeCount);
  }

  private void loadTagClasses(Path staticDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(staticDir, "TagClass", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iName = hdr.get("name");
          int iUrl = hdr.get("url");
          int iFk = hdr.get("SubclassOfTagClassId");
          String vertexSql = "INSERT INTO TagClass SET"
              + " id = :id, name = :name, url = :url";
          String edgeSql = "CREATE EDGE IS_SUBCLASS_OF"
              + " FROM (SELECT FROM TagClass WHERE id = :fromId)"
              + " TO (SELECT FROM TagClass WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              ytg.yql(vertexSql,
                  "id", Long.parseLong(f[iId]),
                  "name", f[iName], "url", f[iUrl]).iterate();
            }
          });
          // Edges in a separate transaction so all TagClass vertices exist
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              if (!f[iFk].isEmpty()) {
                ytg.yql(edgeSql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iFk])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} TagClass vertices + IS_SUBCLASS_OF edges", count);
  }

  private void loadTags(Path staticDir, int batchSize) throws IOException {
    long count = processEntityCsv(staticDir, "Tag", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iName = hdr.get("name");
          int iUrl = hdr.get("url");
          int iFk = hdr.get("TypeTagClassId");
          String vertexSql =
              "INSERT INTO Tag SET id = :id, name = :name, url = :url";
          String edgeSql = "CREATE EDGE HAS_TYPE"
              + " FROM (SELECT FROM Tag WHERE id = :fromId)"
              + " TO (SELECT FROM TagClass WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              ytg.yql(vertexSql,
                  "id", Long.parseLong(f[iId]),
                  "name", f[iName], "url", f[iUrl]).iterate();
              if (!f[iFk].isEmpty()) {
                ytg.yql(edgeSql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iFk])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Tag vertices + HAS_TYPE edges", count);
  }

  private void loadOrganisations(Path staticDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(staticDir, "Organisation", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iType = hdr.get("type");
          int iName = hdr.get("name");
          int iUrl = hdr.get("url");
          int iFk = hdr.get("LocationPlaceId");
          String vertexSql = "INSERT INTO Organisation SET"
              + " id = :id, type = :type, name = :name, url = :url";
          String edgeSql = "CREATE EDGE IS_LOCATED_IN"
              + " FROM (SELECT FROM Organisation WHERE id = :fromId)"
              + " TO (SELECT FROM Place WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              ytg.yql(vertexSql,
                  "id", Long.parseLong(f[iId]),
                  "type", f[iType], "name", f[iName],
                  "url", f[iUrl]).iterate();
              if (!f[iFk].isEmpty()) {
                ytg.yql(edgeSql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iFk])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Organisation vertices + IS_LOCATED_IN edges", count);
  }

  // ---- Dynamic entity loaders ----

  private void loadPersons(Path dynamicDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(dynamicDir, "Person", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iFirstName = hdr.get("firstName");
          int iLastName = hdr.get("lastName");
          int iGender = hdr.get("gender");
          int iBirthday = hdr.get("birthday");
          int iCreationDate = hdr.get("creationDate");
          int iLocationIP = hdr.get("locationIP");
          int iBrowserUsed = hdr.get("browserUsed");
          int iLanguage = hdr.get("language");
          int iEmail = hdr.get("email");
          int iLocationCityId = hdr.get("LocationCityId");
          String vertexSql = "INSERT INTO Person SET id = :id,"
              + " firstName = :firstName, lastName = :lastName,"
              + " gender = :gender, birthday = :birthday,"
              + " creationDate = :creationDate,"
              + " locationIP = :locationIP,"
              + " browserUsed = :browserUsed,"
              + " languages = :languages, emails = :emails";
          String edgeSql = "CREATE EDGE IS_LOCATED_IN"
              + " FROM (SELECT FROM Person WHERE id = :fromId)"
              + " TO (SELECT FROM Place WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              ytg.yql(vertexSql,
                  "id", Long.parseLong(f[iId]),
                  "firstName", f[iFirstName],
                  "lastName", f[iLastName],
                  "gender", f[iGender],
                  "birthday", Long.parseLong(f[iBirthday]),
                  "creationDate", Long.parseLong(f[iCreationDate]),
                  "locationIP", f[iLocationIP],
                  "browserUsed", f[iBrowserUsed],
                  "languages", parseList(f[iLanguage]),
                  "emails", parseList(f[iEmail])).iterate();
              if (!f[iLocationCityId].isEmpty()) {
                ytg.yql(edgeSql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iLocationCityId])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Person vertices + IS_LOCATED_IN edges", count);
  }

  private void loadForums(Path dynamicDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(dynamicDir, "Forum", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iTitle = hdr.get("title");
          int iCreationDate = hdr.get("creationDate");
          int iModeratorId = hdr.get("ModeratorPersonId");
          String vertexSql = "INSERT INTO Forum SET id = :id,"
              + " title = :title, creationDate = :creationDate";
          String edgeSql = "CREATE EDGE HAS_MODERATOR"
              + " FROM (SELECT FROM Forum WHERE id = :fromId)"
              + " TO (SELECT FROM Person WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              ytg.yql(vertexSql,
                  "id", Long.parseLong(f[iId]),
                  "title", f[iTitle],
                  "creationDate",
                  Long.parseLong(f[iCreationDate])).iterate();
              if (!f[iModeratorId].isEmpty()) {
                ytg.yql(edgeSql,
                    "fromId", Long.parseLong(f[iId]),
                    "toId", Long.parseLong(f[iModeratorId])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Forum vertices + HAS_MODERATOR edges", count);
  }

  private void loadPosts(Path dynamicDir, int batchSize) throws IOException {
    long count = processEntityCsv(dynamicDir, "Post", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iCreationDate = hdr.get("creationDate");
          int iLocationIP = hdr.get("locationIP");
          int iBrowserUsed = hdr.get("browserUsed");
          int iLanguage = hdr.get("language");
          int iContent = hdr.get("content");
          int iLength = hdr.get("length");
          int iImageFile = hdr.get("imageFile");
          int iCreatorId = hdr.get("CreatorPersonId");
          int iForumId = hdr.get("ContainerForumId");
          int iLocationId = hdr.get("LocationCountryId");
          String vertexSql = "INSERT INTO Post SET id = :id,"
              + " creationDate = :creationDate,"
              + " locationIP = :locationIP,"
              + " browserUsed = :browserUsed,"
              + " language = :language, length = :length,"
              + " imageFile = :imageFile, content = :content";
          String creatorSql = "CREATE EDGE HAS_CREATOR"
              + " FROM (SELECT FROM Post WHERE id = :fromId)"
              + " TO (SELECT FROM Person WHERE id = :toId)";
          // CONTAINER_OF goes FROM Forum TO Post
          String containerSql = "CREATE EDGE CONTAINER_OF"
              + " FROM (SELECT FROM Forum WHERE id = :fromId)"
              + " TO (SELECT FROM Post WHERE id = :toId)";
          String locationSql = "CREATE EDGE IS_LOCATED_IN"
              + " FROM (SELECT FROM Post WHERE id = :fromId)"
              + " TO (SELECT FROM Place WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              long postId = Long.parseLong(f[iId]);
              ytg.yql(vertexSql,
                  "id", postId,
                  "creationDate", Long.parseLong(f[iCreationDate]),
                  "locationIP", f[iLocationIP],
                  "browserUsed", f[iBrowserUsed],
                  "language", f[iLanguage],
                  "length", Integer.parseInt(f[iLength]),
                  "imageFile",
                  f[iImageFile].isEmpty() ? null : f[iImageFile],
                  "content",
                  f[iContent].isEmpty() ? null : f[iContent]).iterate();
              if (!f[iCreatorId].isEmpty()) {
                ytg.yql(creatorSql,
                    "fromId", postId,
                    "toId", Long.parseLong(f[iCreatorId])).iterate();
              }
              if (!f[iForumId].isEmpty()) {
                ytg.yql(containerSql,
                    "fromId", Long.parseLong(f[iForumId]),
                    "toId", postId).iterate();
              }
              if (!f[iLocationId].isEmpty()) {
                ytg.yql(locationSql,
                    "fromId", postId,
                    "toId", Long.parseLong(f[iLocationId])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Post vertices + HAS_CREATOR/CONTAINER_OF/"
        + "IS_LOCATED_IN edges", count);
  }

  private void loadComments(Path dynamicDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(dynamicDir, "Comment", batchSize,
        (hdr, batch) -> {
          int iId = hdr.get("id");
          int iCreationDate = hdr.get("creationDate");
          int iLocationIP = hdr.get("locationIP");
          int iBrowserUsed = hdr.get("browserUsed");
          int iContent = hdr.get("content");
          int iLength = hdr.get("length");
          int iCreatorId = hdr.get("CreatorPersonId");
          int iLocationId = hdr.get("LocationCountryId");
          int iParentPostId = hdr.get("ParentPostId");
          int iParentCommentId = hdr.get("ParentCommentId");
          String vertexSql = "INSERT INTO Comment SET id = :id,"
              + " creationDate = :creationDate,"
              + " locationIP = :locationIP,"
              + " browserUsed = :browserUsed,"
              + " content = :content, length = :length";
          String creatorSql = "CREATE EDGE HAS_CREATOR"
              + " FROM (SELECT FROM Comment WHERE id = :fromId)"
              + " TO (SELECT FROM Person WHERE id = :toId)";
          String locationSql = "CREATE EDGE IS_LOCATED_IN"
              + " FROM (SELECT FROM Comment WHERE id = :fromId)"
              + " TO (SELECT FROM Place WHERE id = :toId)";
          String replyPostSql = "CREATE EDGE REPLY_OF"
              + " FROM (SELECT FROM Comment WHERE id = :fromId)"
              + " TO (SELECT FROM Post WHERE id = :toId)";
          String replyCommentSql = "CREATE EDGE REPLY_OF"
              + " FROM (SELECT FROM Comment WHERE id = :fromId)"
              + " TO (SELECT FROM Comment WHERE id = :toId)";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              long commentId = Long.parseLong(f[iId]);
              ytg.yql(vertexSql,
                  "id", commentId,
                  "creationDate", Long.parseLong(f[iCreationDate]),
                  "locationIP", f[iLocationIP],
                  "browserUsed", f[iBrowserUsed],
                  "content", f[iContent],
                  "length", Integer.parseInt(f[iLength])).iterate();
              if (!f[iCreatorId].isEmpty()) {
                ytg.yql(creatorSql,
                    "fromId", commentId,
                    "toId", Long.parseLong(f[iCreatorId])).iterate();
              }
              if (!f[iLocationId].isEmpty()) {
                ytg.yql(locationSql,
                    "fromId", commentId,
                    "toId", Long.parseLong(f[iLocationId])).iterate();
              }
              if (!f[iParentPostId].isEmpty()) {
                ytg.yql(replyPostSql,
                    "fromId", commentId,
                    "toId", Long.parseLong(f[iParentPostId])).iterate();
              }
              if (!f[iParentCommentId].isEmpty()) {
                ytg.yql(replyCommentSql,
                    "fromId", commentId,
                    "toId",
                    Long.parseLong(f[iParentCommentId])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} Comment vertices + HAS_CREATOR/IS_LOCATED_IN/"
        + "REPLY_OF edges", count);
  }

  // ---- Dynamic edge loaders ----

  private void loadKnowsEdges(Path dynamicDir, int batchSize)
      throws IOException {
    long count = processEntityCsv(dynamicDir, "Person_knows_Person",
        batchSize, (hdr, batch) -> {
          int iP1 = hdr.get("Person1Id");
          int iP2 = hdr.get("Person2Id");
          int iCd = hdr.get("creationDate");
          String sql = "CREATE EDGE KNOWS"
              + " FROM (SELECT FROM Person WHERE id = :fromId)"
              + " TO (SELECT FROM Person WHERE id = :toId)"
              + " SET creationDate = :creationDate";
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              long p1 = Long.parseLong(f[iP1]);
              long p2 = Long.parseLong(f[iP2]);
              long cd = Long.parseLong(f[iCd]);
              // Bidirectional
              ytg.yql(sql,
                  "fromId", p1, "toId", p2,
                  "creationDate", cd).iterate();
              ytg.yql(sql,
                  "fromId", p2, "toId", p1,
                  "creationDate", cd).iterate();
            }
          });
        });
    log.info("Loaded {} KNOWS edges (bidirectional)", count);
  }

  /**
   * Generic loader for N-to-M edge CSV files with named columns.
   *
   * @param propCol CSV column name for the edge property (null if none)
   * @param propType "int", "long", or "joinDate" (maps creationDate to
   *     joinDate); null if no property
   */
  private void loadNamedEdge(Path dir, String entityName,
      String edgeLabel, String fromLabel, String toLabel,
      String fromCol, String toCol,
      String propCol, String propType,
      int batchSize) throws IOException {
    boolean hasProp = propCol != null && propType != null;
    // For HAS_MEMBER, creationDate in CSV maps to joinDate edge property
    boolean isJoinDate = "joinDate".equals(propType);
    String sql;
    if (!hasProp) {
      sql = "CREATE EDGE " + edgeLabel
          + " FROM (SELECT FROM " + fromLabel + " WHERE id = :fromId)"
          + " TO (SELECT FROM " + toLabel + " WHERE id = :toId)";
    } else {
      String edgePropName = isJoinDate ? "joinDate" : propCol;
      sql = "CREATE EDGE " + edgeLabel
          + " FROM (SELECT FROM " + fromLabel + " WHERE id = :fromId)"
          + " TO (SELECT FROM " + toLabel + " WHERE id = :toId)"
          + " SET " + edgePropName + " = :propValue";
    }
    String finalSql = sql;

    long count = processEntityCsv(dir, entityName, batchSize,
        (hdr, batch) -> {
          int iFrom = hdr.get(fromCol);
          int iTo = hdr.get(toCol);
          int iProp = hasProp ? hdr.get(propCol) : -1;
          traversal.executeInTx(g -> {
            var ytg = (YTDBGraphTraversalSource) g;
            for (String[] f : batch) {
              if (!hasProp) {
                ytg.yql(finalSql,
                    "fromId", Long.parseLong(f[iFrom]),
                    "toId", Long.parseLong(f[iTo])).iterate();
              } else if ("int".equals(propType)) {
                ytg.yql(finalSql,
                    "fromId", Long.parseLong(f[iFrom]),
                    "toId", Long.parseLong(f[iTo]),
                    "propValue", Integer.parseInt(f[iProp])).iterate();
              } else {
                // "long" or "joinDate" — both store a long timestamp
                ytg.yql(finalSql,
                    "fromId", Long.parseLong(f[iFrom]),
                    "toId", Long.parseLong(f[iTo]),
                    "propValue", Long.parseLong(f[iProp])).iterate();
              }
            }
          });
        });
    log.info("Loaded {} {} edges ({} -> {})",
        count, edgeLabel, fromLabel, toLabel);
  }

  // ==================== CSV UTILITIES ====================

  /**
   * Callback for processing batches of CSV rows with header-based column
   * access.
   */
  @FunctionalInterface
  interface HeaderBatchConsumer {
    void accept(Map<String, Integer> header, List<String[]> batch);
  }

  /**
   * Resolves a CSV entity to one or more part files. Supports two layouts:
   * <ul>
   *   <li>Datagen v1.0.0: directory with Spark part-*.csv files</li>
   *   <li>Datagen v0.3.5: flat file named entity_0_0.csv</li>
   * </ul>
   */
  private List<Path> resolveCsvFiles(Path dir, String entityName)
      throws IOException {
    // Datagen v1.0.0: directory with part files
    Path entityDir = dir.resolve(entityName);
    if (Files.isDirectory(entityDir)) {
      try (Stream<Path> files = Files.list(entityDir)) {
        List<Path> parts = files
            .filter(p -> p.getFileName().toString().startsWith("part-"))
            .filter(p -> p.getFileName().toString().endsWith(".csv"))
            .sorted()
            .toList();
        if (!parts.isEmpty()) {
          return parts;
        }
      }
    }
    // Datagen v0.3.5 fallback: flat file
    String flatName = entityName.toLowerCase() + "_0_0.csv";
    Path flatFile = dir.resolve(flatName);
    if (Files.exists(flatFile)) {
      return List.of(flatFile);
    }
    return List.of();
  }

  /**
   * Processes CSV files for an entity, using the header row for column lookup.
   * Handles both single-file and multi-part-file layouts.
   */
  private long processEntityCsv(Path dir, String entityName,
      int batchSize, HeaderBatchConsumer consumer) throws IOException {
    List<Path> csvFiles = resolveCsvFiles(dir, entityName);
    if (csvFiles.isEmpty()) {
      log.warn("No CSV files found for entity: {}", entityName);
      return 0;
    }

    Map<String, Integer> header = null;
    long totalCount = 0;

    for (Path csvFile : csvFiles) {
      try (Stream<String> lines = Files.lines(csvFile)) {
        var iterator = lines.iterator();
        if (!iterator.hasNext()) {
          continue;
        }

        // Parse header from every part file (they're identical)
        String headerLine = iterator.next();
        if (header == null) {
          header = parseHeader(headerLine);
        }

        var batch = new ArrayList<String[]>(batchSize);
        while (iterator.hasNext()) {
          String line = iterator.next();
          if (line.isEmpty()) {
            continue;
          }
          batch.add(line.split("\\|", -1));
          totalCount++;
          if (batch.size() >= batchSize) {
            consumer.accept(header, List.copyOf(batch));
            batch.clear();
          }
        }
        if (!batch.isEmpty()) {
          consumer.accept(header, List.copyOf(batch));
        }
      }
    }
    return totalCount;
  }

  private static Map<String, Integer> parseHeader(String headerLine) {
    String[] cols = headerLine.split("\\|", -1);
    var map = new HashMap<String, Integer>(cols.length * 2);
    for (int i = 0; i < cols.length; i++) {
      map.put(cols[i], i);
    }
    return map;
  }

  private static List<String> parseList(String value) {
    if (value == null || value.isEmpty()) {
      return List.of();
    }
    return List.of(value.split(";"));
  }

  // ==================== PARAMETER SAMPLING ====================

  @SuppressWarnings("unchecked")
  private void sampleQueryParameters() {
    List<Map<String, Object>> persons = executeSql(
        "SELECT id FROM Person LIMIT " + MAX_PARAMS);
    List<Long> pIds = new ArrayList<>(
        persons.stream()
            .map(r -> ((Number) r.get("id")).longValue()).toList());
    Collections.shuffle(pIds);
    personIds = pIds.stream().mapToLong(Long::longValue).toArray();

    List<Map<String, Object>> messages = executeSql(
        "SELECT id FROM Message LIMIT " + (MAX_PARAMS * 2));
    List<Long> mIds = new ArrayList<>(
        messages.stream()
            .map(r -> ((Number) r.get("id")).longValue()).toList());
    Collections.shuffle(mIds);
    messageIds =
        mIds.stream().limit(MAX_PARAMS).mapToLong(Long::longValue).toArray();

    List<Map<String, Object>> fNames = executeSql(
        "SELECT DISTINCT(firstName) as firstName FROM Person LIMIT "
            + MAX_PARAMS);
    List<String> fnList = new ArrayList<>(
        fNames.stream()
            .map(r -> r.get("firstName").toString()).toList());
    Collections.shuffle(fnList);
    firstNames = fnList.isEmpty()
        ? new String[] {"John"} : fnList.toArray(new String[0]);

    List<Map<String, Object>> tNames = executeSql(
        "SELECT DISTINCT(name) as name FROM Tag LIMIT " + MAX_PARAMS);
    List<String> tnList = new ArrayList<>(
        tNames.stream().map(r -> r.get("name").toString()).toList());
    Collections.shuffle(tnList);
    tagNames = tnList.isEmpty()
        ? new String[] {"Tag1"} : tnList.toArray(new String[0]);

    List<Map<String, Object>> cNames = executeSql(
        "SELECT DISTINCT(name) as name FROM Place"
            + " WHERE type = 'Country' LIMIT " + MAX_PARAMS);
    List<String> cnList = new ArrayList<>(
        cNames.stream().map(r -> r.get("name").toString()).toList());
    Collections.shuffle(cnList);
    countryNames = cnList.isEmpty()
        ? new String[] {"China"} : cnList.toArray(new String[0]);

    List<Map<String, Object>> tcNames = executeSql(
        "SELECT DISTINCT(name) as name FROM TagClass LIMIT " + MAX_PARAMS);
    List<String> tcList = new ArrayList<>(
        tcNames.stream().map(r -> r.get("name").toString()).toList());
    Collections.shuffle(tcList);
    tagClassNames = tcList.isEmpty()
        ? new String[] {"MusicalArtist"} : tcList.toArray(new String[0]);

    List<Map<String, Object>> dates = executeSql(
        "SELECT creationDate FROM Message LIMIT " + MAX_PARAMS);
    List<Date> dateList = new ArrayList<>(dates.stream().map(r -> {
      Object d = r.get("creationDate");
      return d instanceof Date ? (Date) d : new Date(((Number) d).longValue());
    }).toList());
    Collections.shuffle(dateList);
    messageDates = dateList.isEmpty()
        ? new Date[] {new Date()} : dateList.toArray(new Date[0]);
  }
}
