/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.sql.csv;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * CSV rendering for {@code SqlNuclrResource.openInputStream()} — the mechanism
 * {@code filepanel-fs}'s extended {@code CopyService} pulls bytes from for a
 * path-less (virtual) source resource during an F5 copy.
 */
@Slf4j
public final class CsvExport {

	private CsvExport() {
	}

	/** A 2-line CSV (header + the one row) for a single row resource. */
	public static String rowCsv(List<String> columns, Map<String, Object> values) {
		return rowsCsv(columns, List.of(values));
	}

	/**
	 * A header plus one line per row, in {@code columns} order. For a selection the user
	 * picked out of a listing, where the values are already in hand and there is nothing
	 * to re-query.
	 */
	public static String rowsCsv(List<String> columns, List<Map<String, Object>> rows) {
		StringBuilder csv = new StringBuilder();
		csv.append(headerLine(columns)).append("\r\n");
		for (Map<String, Object> values : rows) {
			csv.append(dataLine(columns, columns.stream().map(values::get).toList())).append("\r\n");
		}
		return csv.toString();
	}

	private static String headerLine(List<String> columns) {
		return columns.stream().map(CsvExport::escape).collect(Collectors.joining(","));
	}

	private static String dataLine(List<String> columns, List<Object> values) {
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				line.append(',');
			}
			line.append(escape(values.get(i)));
		}
		return line.toString();
	}

	/** Passed as the cap when every row should be written. */
	public static final int NoRowCap = -1;

	/**
	 * Write {@code rs} as CSV, stopping after {@code rowCap} rows unless that is
	 * {@link #NoRowCap}. One implementation behind all three destinations — a pipe, a
	 * file, and a string for the clipboard — so they cannot drift apart in quoting.
	 *
	 * @return how many rows were written, and whether the cap cut the result short
	 */
	static Written writeCsv(ResultSet rs, Writer writer, int rowCap) throws SQLException, IOException {
		ResultSetMetaData meta = rs.getMetaData();
		int columnCount = meta.getColumnCount();
		List<String> columns = new ArrayList<>(columnCount);
		for (int i = 1; i <= columnCount; i++) {
			columns.add(meta.getColumnLabel(i));
		}
		writer.write(headerLine(columns));
		writer.write("\r\n");

		long rows = 0;
		List<Object> rowValues = new ArrayList<>(columnCount);
		while (rs.next()) {
			if (rowCap != NoRowCap && rows >= rowCap) {
				return new Written(rows, true);
			}
			rowValues.clear();
			for (int i = 1; i <= columnCount; i++) {
				rowValues.add(rs.getObject(i));
			}
			writer.write(dataLine(columns, rowValues));
			writer.write("\r\n");
			rows++;
		}
		return new Written(rows, false);
	}

	/** @param truncated whether a row cap stopped the write before the result ran out. */
	public record Written(long rows, boolean truncated) {
	}

	/**
	 * Write every row of {@code quotedTableRef} to {@code target}, replacing whatever is
	 * there. Straight to the file rather than through {@link #streamTable}'s pipe, so
	 * the row count can be reported back and there is no second thread to coordinate.
	 */
	public static Written writeTable(Connection connection, String quotedTableRef, Path target)
			throws SQLException, IOException {
		try (Statement statement = connection.createStatement();
				Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
			applyFetchHint(statement);
			try (ResultSet rs = statement.executeQuery("SELECT * FROM " + quotedTableRef)) {
				return writeCsv(rs, writer, NoRowCap);
			}
		}
	}

	/**
	 * The table as a CSV string, stopping at {@code rowCap}. The clipboard holds the
	 * whole thing in memory at once, so unlike a file export this one is bounded and
	 * says when it hit the bound.
	 */
	public static String tableCsv(Connection connection, String quotedTableRef, int rowCap, Written[] outcome)
			throws SQLException, IOException {
		var text = new StringWriter();
		try (Statement statement = connection.createStatement()) {
			applyFetchHint(statement);
			try (ResultSet rs = statement.executeQuery("SELECT * FROM " + quotedTableRef)) {
				outcome[0] = writeCsv(rs, text, rowCap);
			}
		}
		return text.toString();
	}

	/**
	 * Best-effort streaming hint; several drivers (notably Postgres, which also needs
	 * autocommit off) need more than this to avoid buffering the whole result set
	 * client-side. Not every driver supports it, and export works either way.
	 */
	private static void applyFetchHint(Statement statement) {
		try {
			statement.setFetchSize(500);
		} catch (SQLException ignored) {
			// the driver has no opinion on fetch size
		}
	}

	static String escape(Object value) {
		String s = value == null ? "" : String.valueOf(value);
		boolean needsQuoting = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
		if (!needsQuoting) {
			return s;
		}
		return "\"" + s.replace("\"", "\"\"") + "\"";
	}

	/**
	 * Stream {@code SELECT * FROM quotedTableRef} as CSV, not capped by the browse-page
	 * row limit — export reads everything the query returns. Runs the query and writes
	 * CSV on a fresh virtual thread feeding a pipe; the {@link Statement}/{@link ResultSet}
	 * close when that thread's loop ends, including when the caller closes the returned
	 * stream early (a cancelled copy), which breaks the pipe and stops the writer on its
	 * next write.
	 *
	 * @param connection    a live connection over which to run the export query; owned by
	 *                      the caller (typically the panel's {@code ConnectionRegistry}
	 *                      entry) and never closed here
	 * @param quotedTableRef the fully qualified, already-quoted table/view reference
	 *                      (e.g. {@code "public"."users"})
	 */
	public static InputStream streamTable(Connection connection, String quotedTableRef) throws SQLException {
		PipedOutputStream out = new PipedOutputStream();
		PipedInputStream in;
		try {
			in = new PipedInputStream(out, 64 * 1024);
		} catch (IOException e) {
			throw new SQLException("Failed to set up the CSV export pipe", e);
		}

		Thread.ofVirtual().name("sql-csv-export").start(() -> {
			try (Statement statement = connection.createStatement();
					Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
				applyFetchHint(statement);
				try (ResultSet rs = statement.executeQuery("SELECT * FROM " + quotedTableRef)) {
					writeCsv(rs, writer, NoRowCap);
					writer.flush();
				}
			} catch (Exception e) {
				// Includes the pipe closing early (a cancelled copy) — nothing more to do,
				// the reader simply sees a truncated or empty stream.
				log.debug("SQL CSV export ended: {}", e.getMessage());
			} finally {
				try {
					out.close();
				} catch (IOException ignored) {
				}
			}
		});

		return in;
	}
}
