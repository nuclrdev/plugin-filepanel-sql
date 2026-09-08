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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.panel.sql.support.SqliteFixture;

class CsvExportTest {

	@Test
	void leavesPlainValuesAlone() {
		assertEquals("Ada", CsvExport.escape("Ada"));
		assertEquals("42", CsvExport.escape(42));
		assertEquals("", CsvExport.escape(null));
	}

	@Test
	void quotesValuesCarryingSeparatorsQuotesOrNewlines() {
		assertEquals("\"a,b\"", CsvExport.escape("a,b"));
		assertEquals("\"say \"\"hi\"\"\"", CsvExport.escape("say \"hi\""));
		assertEquals("\"one\ntwo\"", CsvExport.escape("one\ntwo"));
		assertEquals("\"one\rtwo\"", CsvExport.escape("one\rtwo"));
	}

	@Test
	void rowCsvIsAHeaderAndExactlyOneDataLine() {
		var values = new LinkedHashMap<String, Object>();
		values.put("id", 7);
		values.put("name", "a,b");
		values.put("email", null);

		String csv = CsvExport.rowCsv(List.of("id", "name", "email"), values);

		assertEquals("id,name,email\r\n7,\"a,b\",\r\n", csv);
	}

	@Test
	void rowCsvFollowsTheGivenColumnOrderNotTheMapOrder() {
		var values = new LinkedHashMap<String, Object>();
		values.put("b", 2);
		values.put("a", 1);

		assertEquals("a,b\r\n1,2\r\n", CsvExport.rowCsv(List.of("a", "b"), values));
	}

	@Test
	void streamTableExportsEveryRowWithItsHeader(@TempDir Path dir) throws Exception {
		String url = SqliteFixture.create(dir.resolve("export.db"));
		try (Connection connection = DriverManager.getConnection(url)) {
			String csv;
			try (InputStream in = CsvExport.streamTable(connection, "\"main\".\"users\"")) {
				csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}

			List<String> lines = Arrays.asList(csv.split("\r\n", -1));
			assertEquals("id,name,email", lines.get(0));
			assertEquals("1,Ada,ada@example.com", lines.get(1));
			// A NULL column exports as an empty field, not the string "null".
			assertEquals("2,Grace,", lines.get(2));
			// Comma, quote and newline all survive one round of CSV quoting.
			assertTrue(lines.get(3).startsWith("3,\"O'Hara, \"\"Bob\"\"\n"), lines.get(3));
			assertEquals(4, lines.size() - 1, "three rows plus the header, with a trailing terminator");
		}
	}

	@Test
	void streamTableReportsNoRowsForAnEmptyTable(@TempDir Path dir) throws Exception {
		String url = SqliteFixture.create(dir.resolve("empty.db"));
		try (Connection connection = DriverManager.getConnection(url)) {
			connection.createStatement().executeUpdate("CREATE TABLE blank (x TEXT)");
			try (InputStream in = CsvExport.streamTable(connection, "\"main\".\"blank\"")) {
				assertEquals("x\r\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
	}

	@Test
	void rowsCsvWritesOneLinePerRowUnderOneHeader() {
		var ada = new LinkedHashMap<String, Object>();
		ada.put("id", 1);
		ada.put("name", "Ada");
		var bob = new LinkedHashMap<String, Object>();
		bob.put("id", 2);
		bob.put("name", "a,b");

		String csv = CsvExport.rowsCsv(List.of("id", "name"), List.of(ada, bob));

		assertEquals("id,name\r\n1,Ada\r\n2,\"a,b\"\r\n", csv);
	}

	@Test
	void rowsCsvWithNoRowsIsStillAHeader() {
		assertEquals("id,name\r\n", CsvExport.rowsCsv(List.of("id", "name"), List.of()));
	}

	@Test
	void writeTablePutsEveryRowInTheFile(@TempDir Path dir) throws Exception {
		String url = SqliteFixture.create(dir.resolve("out.db"));
		Path target = dir.resolve("users.csv");
		try (Connection connection = DriverManager.getConnection(url)) {
			var written = CsvExport.writeTable(connection, "\"main\".\"users\"", target);

			assertEquals(3, written.rows());
			assertFalse(written.truncated());
		}
		List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
		assertEquals("id,name,email", lines.get(0));
		assertEquals("1,Ada,ada@example.com", lines.get(1));
	}

	@Test
	void writeTableReplacesWhateverWasThere(@TempDir Path dir) throws Exception {
		String url = SqliteFixture.create(dir.resolve("out.db"));
		Path target = dir.resolve("users.csv");
		Files.writeString(target, "stale content that must not survive");
		try (Connection connection = DriverManager.getConnection(url)) {
			CsvExport.writeTable(connection, "\"main\".\"users\"", target);
		}

		assertFalse(Files.readString(target).contains("stale"));
	}

	@Test
	void tableCsvStopsAtItsRowCapAndSaysSo(@TempDir Path dir) throws Exception {
		String url = SqliteFixture.create(dir.resolve("cap.db"));
		try (Connection connection = DriverManager.getConnection(url)) {
			var outcome = new CsvExport.Written[1];

			String csv = CsvExport.tableCsv(connection, "\"main\".\"users\"", 2, outcome);

			assertEquals(2, outcome[0].rows());
			assertTrue(outcome[0].truncated());
			assertEquals(3, csv.split("\r\n", -1).length - 1, "two rows plus the header");
		}
	}

	@Test
	void tableCsvUncappedTakesTheWholeTable(@TempDir Path dir) throws Exception {
		String url = SqliteFixture.create(dir.resolve("all.db"));
		try (Connection connection = DriverManager.getConnection(url)) {
			var outcome = new CsvExport.Written[1];

			CsvExport.tableCsv(connection, "\"main\".\"users\"", CsvExport.NoRowCap, outcome);

			assertEquals(3, outcome[0].rows());
			assertFalse(outcome[0].truncated());
		}
	}

	@Test
	void aFileExportAndAStreamExportAgreeCharacterForCharacter(@TempDir Path dir) throws Exception {
		String url = SqliteFixture.create(dir.resolve("same.db"));
		Path target = dir.resolve("users.csv");
		try (Connection connection = DriverManager.getConnection(url)) {
			CsvExport.writeTable(connection, "\"main\".\"users\"", target);
			String streamed;
			try (InputStream in = CsvExport.streamTable(connection, "\"main\".\"users\"")) {
				streamed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}

			assertEquals(streamed, Files.readString(target, StandardCharsets.UTF_8));
		}
	}
}
