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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post;

import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerCSVAbstract;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLUpdateItem;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandDocumentAbstract;
import java.io.BufferedReader;
import java.io.StringReader;
import java.text.NumberFormat;
import java.util.InputMismatchException;
import java.util.Locale;

public class ServerCommandPostImportRecords extends ServerCommandDocumentAbstract {

  private static final char CSV_SEPARATOR = ',';
  private static final char CSV_STR_DELIMITER = '"';

  private static final String[] NAMES = {"POST|importRecords/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    final var urlParts =
        checkSyntax(
            iRequest.getUrl(),
            4,
            "Syntax error:"
                + " importRecords/<database>/<format>/<class>[/<separator>][/<string-delimiter>][/<locale>]");

    final var start = System.currentTimeMillis();

    iRequest.getData().commandInfo = "Import records";

    try (var session = getProfiledDatabaseSessionInstance(iRequest)) {
      final var cls = session.getMetadata().getSchema().getClass(urlParts[3]);
      if (cls == null) {
        throw new IllegalArgumentException("Class '" + urlParts[3] + " is not defined");
      }

      if (iRequest.getContent() == null) {
        throw new IllegalArgumentException("Empty content");
      }

      if (urlParts[2].equalsIgnoreCase("csv")) {
        final var separator = urlParts.length > 4 ? urlParts[4].charAt(0) : CSV_SEPARATOR;
        final var stringDelimiter =
            urlParts.length > 5 ? urlParts[5].charAt(0) : CSV_STR_DELIMITER;
        final var locale = urlParts.length > 6 ? new Locale(urlParts[6]) : Locale.getDefault();

        final var reader = new BufferedReader(new StringReader(iRequest.getContent()));
        var header = reader.readLine();
        if (header == null || (header = header.trim()).isEmpty()) {
          throw new InputMismatchException("Missing CSV file header");
        }

        final var columns = StringSerializerHelper.smartSplit(header, separator);
        columns.replaceAll(IOUtils::getStringContent);

        var imported = 0;
        var errors = 0;

        final var output = new StringBuilder(1024);

        var line = 0;
        var col = 0;
        var column = "?";
        var parsedCell = "?";
        final var numberFormat = NumberFormat.getNumberInstance(locale);

        for (line = 2; reader.ready(); line++) {
          try {
            final var parsedRow = reader.readLine();
            if (parsedRow == null) {
              break;
            }

            final var entity = (EntityImpl) session.newEntity(cls);
            final var row = parsedRow.trim();
            final var cells = StringSerializerHelper.smartSplit(row, CSV_SEPARATOR);

            for (col = 0; col < columns.size(); ++col) {
              parsedCell = cells.get(col);
              column = columns.get(col);

              var cellValue = parsedCell.trim();

              if (cellValue.isEmpty() || cellValue.equalsIgnoreCase("null")) {
                continue;
              }

              Object value;
              if (cellValue.length() >= 2
                  && cellValue.charAt(0) == stringDelimiter
                  && cellValue.charAt(cellValue.length() - 1) == stringDelimiter) {
                value = IOUtils.getStringContent(cellValue);
              } else {
                try {
                  value = numberFormat.parse(cellValue);
                } catch (Exception e) {
                  value = RecordSerializerCSVAbstract.getTypeValue(session, cellValue);
                }
              }

              var immutableClass = entity.getImmutableSchemaClass(session);
              var property = immutableClass != null ?
                  immutableClass.getProperty(session, columns.get(col)) : null;
              entity.setProperty(columns.get(col), SQLUpdateItem.cleanPropertyValue(value,
                  session, property));
            }

            imported++;

          } catch (Exception e) {
            errors++;
            output.append(
                String.format(
                    "#%d: line %d column %s (%d) value '%s': '%s'\n",
                    errors, line, column, col, parsedCell, e));
          }
        }

        final var elapsed = (float) (System.currentTimeMillis() - start) / 1000;

        var message =
            String.format(
                """
                    Import of records of class '%s' completed in %5.3f seconds. Line parsed: %d,\
                     imported: %d, error: %d
                    Detailed messages:
                    %s""",
                cls.getName(session), elapsed, line, imported, errors, output);

        iResponse.send(
            HttpUtils.STATUS_CREATED_CODE,
            HttpUtils.STATUS_CREATED_DESCRIPTION,
            HttpUtils.CONTENT_TEXT_PLAIN,
            message,
            null);
        return false;

      } else {
        throw new UnsupportedOperationException(
            "Unsupported format on importing record. Available formats are: csv");
      }

    }
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
