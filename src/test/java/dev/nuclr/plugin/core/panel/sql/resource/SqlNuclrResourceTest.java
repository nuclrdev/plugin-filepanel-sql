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
package dev.nuclr.plugin.core.panel.sql.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;

class SqlNuclrResourceTest {

	private static final List<String> Columns = List.of("id", "name");

	private static SqlNuclrResource sampleRow(String key) {
		var values = new LinkedHashMap<String, Object>();
		values.put("id", 1);
		values.put("name", "Ada");
		return SqlNuclrResource.row("p1", "main", "users", key, Columns, values);
	}

	@Test
	void explorerRootIsAFolderNamedForThePlugin() {
		var root = SqlNuclrResource.explorerRoot();

		assertEquals(ResourceKind.EXPLORER_ROOT, root.getKind());
		assertEquals("SQL Explorer", root.getName());
		assertEquals("sql://", root.getUuid());
		assertTrue(root.isFolder());
	}

	@Test
	void aConnectionCarriesTheColumnsTheRootListingShows() {
		var profile = ConnectionProfile.builder()
				.id("p1").name("Shop").driverKey("postgresql")
				.jdbcUrl("jdbc:postgresql://db.example.com:5432/shop").build();

		var connection = SqlNuclrResource.connection(profile);

		assertEquals(ResourceKind.CONNECTION, connection.getKind());
		assertEquals("p1", connection.getProfileId());
		assertEquals("Shop", connection.getMetadata().get("Name"));
		assertEquals("PostgreSQL", connection.getMetadata().get("Driver"));
		assertEquals("jdbc:postgresql://db.example.com:5432/shop", connection.getMetadata().get("Host"));
		assertTrue(connection.isFolder());
	}

	@Test
	void everyLevelOfTheTreeGetsItsOwnIdentity() {
		var connection = SqlNuclrResource.connection(
				ConnectionProfile.builder().id("p1").name("Shop").driverKey("postgresql").build());
		var schema = SqlNuclrResource.schema("p1", "main");
		var table = SqlNuclrResource.table("p1", "main", "users", false, "\"main\".\"users\"", null);
		var row = sampleRow("1");

		assertEquals("sql://p1", connection.getUuid());
		assertEquals("sql://p1/main", schema.getUuid());
		assertEquals("sql://p1/main/users", table.getUuid());
		assertEquals("sql://p1/main/users/1", row.getUuid());
	}

	@Test
	void aViewIsMarkedAsOneAndATableIsNot() {
		assertEquals(ResourceKind.VIEW,
				SqlNuclrResource.table("p1", "main", "v", true, "\"main\".\"v\"", null).getKind());
		assertEquals(ResourceKind.TABLE,
				SqlNuclrResource.table("p1", "main", "t", false, "\"main\".\"t\"", null).getKind());
	}

	@Test
	void aRowIsAFileWhoseMetadataIsItsColumns() {
		var row = sampleRow("1");

		assertEquals(ResourceKind.ROW, row.getKind());
		assertFalse(row.isFolder());
		assertEquals("1", row.getName());
		assertEquals("main.users[1]", row.getFullPath());
		assertEquals(Columns, row.getColumnOrder());
		assertEquals(1, row.getMetadata().get("id"));
		assertEquals("Ada", row.getMetadata().get("name"));
	}

	@Test
	void aRowWithNoKeyStillHasAReadableName() {
		assertEquals("(row)", sampleRow("").getName());
		assertEquals("(row)", sampleRow(null).getName());
	}

	@Test
	void aCompositeKeyContainingASlashDoesNotSplitTheResourceIdentity() {
		var row = sampleRow("a/b");

		assertEquals("sql://p1/main/users/a_b", row.getUuid());
		assertEquals("main.users[a/b]", row.getFullPath());
	}

	@Test
	void twoRowsOfTheSameTableAreDistinctResources() {
		assertNotEquals(sampleRow("1"), sampleRow("2"));
		assertEquals(sampleRow("1"), sampleRow("1"));
	}

	@Test
	void aRowStreamsItselfAsATwoLineCsv() throws Exception {
		try (InputStream in = sampleRow("1").openInputStream()) {
			assertEquals("id,name\r\n1,Ada\r\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	void aTableWithNoLiveConnectionCannotBeStreamed() {
		var table = SqlNuclrResource.table("p1", "main", "users", false, null, null);

		assertThrows(UnsupportedOperationException.class, table::openInputStream);
		assertThrows(IllegalStateException.class, table::openConnection);
	}

	@Test
	void neitherASchemaNorTheRootPretendsToBeAFile() {
		assertThrows(UnsupportedOperationException.class, () -> SqlNuclrResource.schema("p1", "main").openInputStream());
		assertThrows(UnsupportedOperationException.class, () -> SqlNuclrResource.explorerRoot().openInputStream());
	}

	@Test
	void theRowLimitMarkerIsUnselectableAndSaysWhy() {
		var marker = SqlNuclrResource.rowLimitMarker("p1", "main", "users", 50_000);

		assertEquals(ResourceKind.ROW_LIMIT_MARKER, marker.getKind());
		assertFalse(marker.isReadable());
		assertFalse(marker.isFolder());
		assertTrue(marker.getName().contains("50000"), marker.getName());
	}

	@Test
	void theParentEntryReopensExactlyTheResourceItStandsFor() {
		var schema = SqlNuclrResource.schema("p1", "main");

		var up = SqlNuclrResource.parentEntry(schema);

		assertEquals("..", up.getName());
		assertTrue(up.isFolder());
		assertEquals(schema.getUuid(), up.getUuid());
		assertEquals(schema.getFullPath(), up.getFullPath());
		assertEquals(schema.getKind(), up.getKind());
		assertEquals(schema, up);
	}

	@Test
	void theParentEntryOfATableKeepsWhatMakesTheTableUsable() {
		var table = SqlNuclrResource.table("p1", "main", "users", false, "\"main\".\"users\"", null);

		var up = SqlNuclrResource.parentEntry(table);

		assertEquals("\"main\".\"users\"", up.getQuotedTableRef());
		assertEquals("users", up.getTableName());
		assertEquals("main", up.getSchemaName());
		assertEquals("p1", up.getProfileId());
	}
}
