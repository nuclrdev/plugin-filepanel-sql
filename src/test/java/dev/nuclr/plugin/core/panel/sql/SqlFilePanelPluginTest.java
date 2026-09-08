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
package dev.nuclr.plugin.core.panel.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.EntrySink;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.NuclrResourceData;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.sql.resource.ResourceKind;
import dev.nuclr.plugin.core.panel.sql.resource.SqlNuclrResource;
import dev.nuclr.plugin.core.panel.sql.support.FakeContext;
import dev.nuclr.plugin.core.panel.sql.support.SqliteFixture;

/**
 * End-to-end navigation over a real database on disk: the whole
 * {@code Explorer root -> Connection -> Schema -> Table -> Row} tree, driven exactly the
 * way the commander drives it. See {@link SqliteFixture} for why the profile describes a
 * custom driver.
 */
class SqlFilePanelPluginTest {

	private FakeContext context;
	private SqlFilePanelPlugin plugin;
	private ConnectionProfile profile;

	/** Collects everything the streaming variant of {@code openResource} publishes. */
	private static final class RecordingSink implements EntrySink {

		final List<String> columns = new ArrayList<>();
		final List<NuclrResource> entries = new ArrayList<>();

		@Override
		public void columns(List<String> columnNames) {
			columns.addAll(columnNames);
		}

		@Override
		public void add(NuclrResource entry) {
			entries.add(entry);
		}
	}

	@BeforeEach
	void setUp(@TempDir Path dir) throws Exception {
		context = new FakeContext();
		plugin = new SqlFilePanelPlugin();
		plugin.preinit(context);
		plugin.init();
		profile = new ConnectionProfileStore(context.settings)
				.add(SqliteFixture.profile("Fixture", dir.resolve("fixture.db")));
	}

	@AfterEach
	void tearDown() {
		plugin.unload();
	}

	private NuclrResourceData open(NuclrResource resource) {
		NuclrResourceData data = plugin.openResource(resource, new AtomicBoolean(false));
		assertNotNull(data, "the plugin should recognise its own resources");
		return data;
	}

	private SqlNuclrResource entryNamed(NuclrResourceData data, String name) {
		return data.getEntries().stream()
				.filter(e -> name.equals(e.getName()))
				.map(SqlNuclrResource.class::cast)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no entry named " + name + " in " + data.getEntries()));
	}

	private SqlNuclrResource usersTable() {
		var connection = entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture");
		var schema = entryNamed(open(connection), "main");
		return entryNamed(open(schema), "users");
	}

	// ------------------------------------------------------------------
	// Capability probe and drive menu
	// ------------------------------------------------------------------

	@Test
	void claimsOnlyItsOwnResources() {
		assertTrue(plugin.supports(SqlNuclrResource.explorerRoot()));
		assertFalse(plugin.supports(null));
	}

	@Test
	void offersASingleDriveMenuEntryThatLandsOnTheExplorerRoot() {
		var holder = plugin.getPluginMenuItems();

		assertEquals("SQL", holder.getTitle());
		assertEquals(1, holder.getMenuItems().size());
		var item = holder.getMenuItems().get(0);
		assertEquals("SQL Explorer", item.getText());
		assertEquals(SqlNuclrResource.explorerRoot(), item.getPath());
	}

	@Test
	void startsAtTheExplorerRoot() {
		assertEquals(ResourceKind.EXPLORER_ROOT, ((SqlNuclrResource) plugin.getCurrentResource()).getKind());
		assertEquals("SQL Explorer", plugin.getCurrentLocationDisplayText());
	}

	// ------------------------------------------------------------------
	// Listing each level
	// ------------------------------------------------------------------

	@Test
	void theRootListsSavedConnectionsAndNothingElse() {
		var data = open(SqlNuclrResource.explorerRoot());

		assertEquals(List.of("Name", "Status", "Driver", "Host"), data.getColumnNames());
		assertEquals(1, data.getEntriesCount());
		var connection = (SqlNuclrResource) data.getEntryAt(0);
		assertEquals(ResourceKind.CONNECTION, connection.getKind());
		assertEquals("Fixture", connection.getName());
		assertEquals(SqliteFixture.DriverClassName, connection.getMetadata().get("Driver"));
	}

	@Test
	void theRootSaysWhichConnectionsAreOpen() {
		// Disconnect is offered on the right-click menu, so the listing has to say what it
		// would apply to.
		assertEquals("", entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture").getMetadata().get("Status"));

		usersTable();

		assertEquals("Connected",
				entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture").getMetadata().get("Status"));
	}

	@Test
	void disconnectingIsReflectedBackInTheRootListing() {
		usersTable();

		plugin.act(null, "sql.disconnect", List.of(),
				entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture"), new java.util.HashMap<>(), null);

		assertEquals("", entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture").getMetadata().get("Status"));
	}

	@Test
	void duplicatingAConnectionCopiesItsSettingsAndItsStoredPassword() {
		context.credentials.secrets.put(profile.getId(), "s3cret");
		var payload = new java.util.HashMap<String, Object>();

		plugin.act(null, "sql.duplicateConnection", List.of(),
				entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture"), payload, null);

		var store = new ConnectionProfileStore(context.settings);
		var copy = store.loadAll().stream().filter(p -> !p.getId().equals(profile.getId())).findFirst().orElseThrow();
		assertEquals("Fixture (copy)", copy.getName());
		assertEquals(profile.getJdbcUrl(), copy.getJdbcUrl());
		assertEquals(profile.getDriverKey(), copy.getDriverKey());
		assertEquals("s3cret", context.credentials.secrets.get(copy.getId()));
		assertEquals(Boolean.TRUE, payload.get("result.refresh"));
	}

	@Test
	void duplicatingTwiceNeverProducesTwoIdenticalNames() {
		var focused = entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture");

		plugin.act(null, "sql.duplicateConnection", List.of(), focused, new java.util.HashMap<>(), null);
		plugin.act(null, "sql.duplicateConnection", List.of(), focused, new java.util.HashMap<>(), null);

		var names = new ConnectionProfileStore(context.settings).loadAll().stream()
				.map(ConnectionProfile::getName).collect(java.util.stream.Collectors.toSet());
		assertEquals(java.util.Set.of("Fixture", "Fixture (copy)", "Fixture (copy 2)"), names);
	}

	@Test
	void aConnectionListsItsSchemasBehindAWayBackUp() {
		var connection = entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture");

		var data = open(connection);

		assertEquals("..", data.getEntryAt(0).getName());
		assertEquals(ResourceKind.EXPLORER_ROOT, ((SqlNuclrResource) data.getEntryAt(0)).getKind());
		// This driver has no schema concept, so the connection's own catalog stands in.
		assertEquals(ResourceKind.SCHEMA, entryNamed(data, "main").getKind());
		assertEquals("Fixture", plugin.getCurrentLocationDisplayText());
	}

	@Test
	void aSchemaListsItsTablesAndViewsWithTheirKind() {
		var connection = entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture");
		var schema = entryNamed(open(connection), "main");

		var data = open(schema);

		assertEquals(List.of("Name", "Type", "Size", "Date"), data.getColumnNames());
		assertEquals("..", data.getEntryAt(0).getName());
		assertEquals(ResourceKind.TABLE, entryNamed(data, "users").getKind());
		assertEquals("Table", entryNamed(data, "users").getMetadata().get("Type"));
		assertEquals(ResourceKind.VIEW, entryNamed(data, "active_users").getKind());
		assertEquals("View", entryNamed(data, "active_users").getMetadata().get("Type"));
		assertEquals("Fixture / main", plugin.getCurrentLocationDisplayText());
	}

	@Test
	void aDialectWithNoCheapRowCountLeavesTheSizeColumnBlank() {
		var connection = entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture");
		var schema = entryNamed(open(connection), "main");

		assertNull(entryNamed(open(schema), "users").getMetadata().get("Size"));
	}

	@Test
	void aTableListsItsRowsKeyedByPrimaryKey() {
		var data = open(usersTable());

		assertEquals(List.of("id", "name", "email"), data.getColumnNames());
		assertEquals("..", data.getEntryAt(0).getName());
		assertEquals(4, data.getEntriesCount(), "three rows plus the way back up");
		var ada = (SqlNuclrResource) data.getEntryAt(1);
		assertEquals(ResourceKind.ROW, ada.getKind());
		assertEquals("1", ada.getName());
		assertEquals("Ada", ada.getMetadata().get("name"));
		assertFalse(ada.isFolder());
		assertEquals("Fixture / main / users", plugin.getCurrentLocationDisplayText());
	}

	@Test
	void aTableWithNoPrimaryKeyFallsBackToTheRowOffsetAsAKey() {
		var connection = entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture");
		var schema = entryNamed(open(connection), "main");

		var data = open(entryNamed(open(schema), "no_pk"));

		assertEquals("00000000", data.getEntryAt(1).getName());
		assertEquals("00000001", data.getEntryAt(2).getName());
	}

	@Test
	void aViewBrowsesItsRowsJustAsATableDoes() {
		var connection = entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture");
		var schema = entryNamed(open(connection), "main");

		var data = open(entryNamed(open(schema), "active_users"));

		assertEquals(List.of("id", "name"), data.getColumnNames());
		assertEquals(3, data.getEntriesCount(), "the two rows with an email, plus the way back up");
	}

	@Test
	void aRowIsALeafWithNothingBelowIt() {
		var row = (SqlNuclrResource) open(usersTable()).getEntryAt(1);

		assertNull(plugin.openResource(row, new AtomicBoolean(false)));
	}

	@Test
	void aResourceFromAnotherPluginIsDeclined() {
		assertNull(plugin.openResource(null, new AtomicBoolean(false)));
	}

	// ------------------------------------------------------------------
	// Streaming, cancellation, and the failure paths
	// ------------------------------------------------------------------

	@Test
	void theStreamingVariantPublishesTheSameListingItReturns() {
		var sink = new RecordingSink();

		var data = plugin.openResource(usersTable(), new AtomicBoolean(false), sink);

		assertEquals(List.of("id", "name", "email"), sink.columns);
		assertEquals(data.getEntries(), sink.entries);
	}

	@Test
	void anAlreadyCancelledListingComesBackWithoutReadingTheTable() {
		var table = usersTable();

		var data = plugin.openResource(table, new AtomicBoolean(true), null);

		assertEquals(1, data.getEntriesCount(), "only the way back up is built before the first check");
	}

	@Test
	void aConnectionThatCannotBeOpenedLeavesAnEmptyListingRatherThanThrowing() {
		var broken = new ConnectionProfileStore(context.settings)
				.add(SqliteFixture.customProfile("Broken").jdbcUrl("jdbc:sqlite:/no/such/dir/nope.db").build());

		var data = open(SqlNuclrResource.connection(broken));

		assertTrue(data.getEntries().isEmpty());
	}

	@Test
	void aProfileWithALoginNeedsAPasswordAndComesBackEmptyWhenItCannotGetOne() {
		var needsLogin = new ConnectionProfileStore(context.settings).add(
				SqliteFixture.customProfile("Needs login").username("app").jdbcUrl(profile.getJdbcUrl()).build());

		// The suite runs headless, so the prompt is declined rather than shown — the same
		// path the user takes by pressing Cancel. Either way the listing comes back empty
		// instead of half-built.
		assertTrue(open(SqlNuclrResource.connection(needsLogin)).getEntries().isEmpty());
	}

	@Test
	void aStoredPasswordMeansTheUserIsNeverPrompted() {
		var needsLogin = new ConnectionProfileStore(context.settings).add(
				SqliteFixture.customProfile("Stored login").username("app").jdbcUrl(profile.getJdbcUrl()).build());
		context.credentials.secrets.put(needsLogin.getId(), "ignored-by-this-driver");

		var data = open(SqlNuclrResource.connection(needsLogin));

		assertEquals(ResourceKind.SCHEMA, entryNamed(data, "main").getKind());
	}

	@Test
	void aProfileSavedAgainstARetiredDriverSaysSoInsteadOfListingNothing() {
		var retired = new ConnectionProfileStore(context.settings).add(ConnectionProfile.builder()
				.name("Old SQLite").driverKey("sqlite").jdbcUrl("jdbc:sqlite:/tmp/a.db").build());

		// The driver key no longer resolves, so connecting fails — but the row still reads
		// as what it wants rather than as an unfinished catalogue entry.
		assertEquals("sqlite (unavailable)", SqlNuclrResource.connection(retired).getMetadata().get("Driver"));
		assertTrue(open(SqlNuclrResource.connection(retired)).getEntries().isEmpty());
	}

	@Test
	void aProfileThatWasDeletedUnderTheCursorListsNothing() {
		new ConnectionProfileStore(context.settings).delete(profile.getId());

		assertTrue(open(SqlNuclrResource.connection(profile)).getEntries().isEmpty());
	}

	// ------------------------------------------------------------------
	// Copying rows and tables out of the panel
	// ------------------------------------------------------------------

	@Test
	void aTableCopiesOutAsCsvOverItsLiveConnection() throws Exception {
		String csv;
		try (InputStream in = usersTable().openInputStream()) {
			csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertTrue(csv.startsWith("id,name,email\r\n1,Ada,ada@example.com\r\n"), csv);
		assertTrue(csv.contains("2,Grace,"), csv);
	}

	@Test
	void aRowCopiesOutAsItsOwnTwoLineCsv() throws Exception {
		var row = (SqlNuclrResource) open(usersTable()).getEntryAt(1);

		try (InputStream in = row.openInputStream()) {
			assertEquals("id,name,email\r\n1,Ada,ada@example.com\r\n",
					new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	// ------------------------------------------------------------------
	// Filter / sort
	// ------------------------------------------------------------------

	@Test
	void aWhereClauseNarrowsTheListingAndSaysSoInTheLocationBar() {
		var table = usersTable();

		plugin.applyFilterSort(table, "id > 1", null);
		var data = open(table);

		assertEquals(3, data.getEntriesCount(), "two matching rows plus the way back up");
		assertEquals("Fixture / main / users [WHERE id > 1]", plugin.getCurrentLocationDisplayText());
	}

	@Test
	void anOrderByReordersTheListing() {
		var table = usersTable();

		plugin.applyFilterSort(table, null, "id DESC");
		var data = open(table);

		assertEquals("3", data.getEntryAt(1).getName());
		assertEquals("1", data.getEntryAt(3).getName());
		assertEquals("Fixture / main / users [ORDER BY id DESC]", plugin.getCurrentLocationDisplayText());
	}

	@Test
	void clearingBothFieldsPutsTheWholeTableBack() {
		var table = usersTable();
		plugin.applyFilterSort(table, "id > 1", "id DESC");

		plugin.applyFilterSort(table, "   ", "");
		var data = open(table);

		assertEquals(4, data.getEntriesCount());
		assertEquals("Fixture / main / users", plugin.getCurrentLocationDisplayText());
	}

	@Test
	void aFilterOnOneTableDoesNotFollowTheCursorToAnother() {
		var connection = entryNamed(open(SqlNuclrResource.explorerRoot()), "Fixture");
		var schema = entryNamed(open(connection), "main");
		plugin.applyFilterSort(entryNamed(open(schema), "users"), "id > 1", null);

		var other = open(entryNamed(open(schema), "no_pk"));

		assertEquals(3, other.getEntriesCount(), "both rows plus the way back up");
	}

	// ------------------------------------------------------------------
	// Status bar text and lifecycle
	// ------------------------------------------------------------------

	@Test
	void theSelectionSummaryCountsRowsAsRows() {
		var rows = open(usersTable()).getEntries().subList(1, 3);

		assertEquals("2 rows selected", plugin.getSelectionSummaryText(rows));
		assertEquals("1 row selected", plugin.getSelectionSummaryText(rows.subList(0, 1)));
	}

	@Test
	void theSelectionSummaryCountsAnythingElseAsItems() {
		var connections = open(SqlNuclrResource.explorerRoot()).getEntries();

		assertEquals("1 item selected", plugin.getSelectionSummaryText(connections));
		assertEquals("", plugin.getSelectionSummaryText(List.of()));
		assertEquals("", plugin.getSelectionSummaryText(null));
	}

	@Test
	void focusIsReportedBackToTheHost() {
		assertFalse(plugin.isFocused());

		assertTrue(plugin.onFocusGained());
		assertTrue(plugin.isFocused());

		plugin.onFocusLost();
		assertFalse(plugin.isFocused());
	}

	@Test
	void everyInstanceGetsItsOwnIdentityBecauseThePanelIsNotASingleton() {
		var second = new SqlFilePanelPlugin();
		second.preinit(context);

		assertNotNull(plugin.uuid());
		assertFalse(plugin.uuid().equals(second.uuid()));
		assertSame(context, plugin.getContext());
	}

	@Test
	void unloadClosesEveryConnectionItOpened() throws Exception {
		Connection connection = usersTable().openConnection();
		assertFalse(connection.isClosed());

		plugin.unload();

		assertTrue(connection.isClosed());
	}

	@Test
	void browsingAgainAfterUnloadReconnects() {
		usersTable();
		plugin.unload();

		assertEquals(4, open(usersTable()).getEntriesCount());
	}

	// ------------------------------------------------------------------
	// CSV out
	// ------------------------------------------------------------------

	@Test
	void exportingSelectedRowsWritesJustThoseRows(@TempDir Path dir) throws Exception {
		var rows = open(usersTable()).getEntries().subList(1, 3);
		Path target = dir.resolve("picked.csv");

		plugin.exportCsvTo(rows, rows.get(0), target);

		var lines = Files.readAllLines(target, StandardCharsets.UTF_8);
		assertEquals(3, lines.size(), "two rows under one header");
		assertEquals("id,name,email", lines.get(0));
		assertEquals("1,Ada,ada@example.com", lines.get(1));
		assertEquals("2,Grace,", lines.get(2));
	}

	@Test
	void exportingAFocusedTableWritesTheWholeThing(@TempDir Path dir) throws Exception {
		var table = usersTable();
		Path target = dir.resolve("users.csv");

		plugin.exportCsvTo(List.of(), table, target);

		String csv = Files.readString(target, StandardCharsets.UTF_8);
		assertTrue(csv.startsWith("id,name,email"), csv);
		assertTrue(csv.contains("1,Ada,ada@example.com"), csv);
		assertTrue(csv.contains("2,Grace,"), csv);
		// The third row's name carries a newline, so its field is quoted and spans two
		// physical lines — which is what CSV says should happen, and why counting lines
		// is not how you count rows.
		assertTrue(csv.contains("\"\"Bob\"\""), csv);
	}

	@Test
	void exportingReportsTheRowCountItActuallyWrote(@TempDir Path dir) throws Exception {
		assertEquals(3, plugin.exportCsvTo(List.of(), usersTable(), dir.resolve("users.csv")));
	}

	@Test
	void aFilterOnTheTableDoesNotNarrowAWholeTableExport(@TempDir Path dir) throws Exception {
		var table = usersTable();
		plugin.applyFilterSort(table, "id > 2", null);
		Path target = dir.resolve("users.csv");

		long rows = plugin.exportCsvTo(List.of(), table, target);

		// Export means the table, not the view the panel happens to be showing; narrowing
		// it is what selecting rows is for.
		assertEquals(3, rows);
		assertTrue(Files.readString(target, StandardCharsets.UTF_8).contains("1,Ada"));
	}

	@Test
	void selectedRowsCopyAsCsvWithTheirHeader() {
		var rows = open(usersTable()).getEntries().subList(1, 2);

		assertEquals("id,name,email\r\n1,Ada,ada@example.com\r\n", plugin.csvFor(rows, rows.get(0)));
	}

	@Test
	void aValueCarryingASeparatorIsQuotedOnItsWayToTheClipboard() {
		var rows = open(usersTable()).getEntries();
		var awkward = rows.stream().filter(r -> "3".equals(r.getName())).toList();

		String csv = plugin.csvFor(awkward, awkward.get(0));

		assertTrue(csv.contains("\"O'Hara, \"\"Bob\"\""), csv);
	}

	@Test
	void aFocusedTableWithNoRowsSelectedCopiesTheWholeTable() {
		var table = usersTable();

		String csv = plugin.csvFor(List.of(), table);

		assertTrue(csv.startsWith("id,name,email\r\n1,Ada,ada@example.com\r\n"), csv);
		assertEquals(4, csv.split("\r\n", -1).length - 1, "three rows plus the header");
	}

	@Test
	void theSuggestedFileNameIsTheTableName() {
		assertEquals("users.csv", plugin.suggestedCsvName(List.of(), usersTable()));
	}

	@Test
	void nothingWorthExportingYieldsNothingRatherThanAnEmptyFile() {
		assertNull(plugin.csvFor(List.of(), SqlNuclrResource.explorerRoot()));
		assertNull(plugin.suggestedCsvName(List.of(), SqlNuclrResource.schema(profile.getId(), "main")));
	}
}
