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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.sql.resource.SqlNuclrResource;
import dev.nuclr.plugin.core.panel.sql.support.FakeContext;
import dev.nuclr.plugin.core.panel.sql.support.SqliteFixture;

/**
 * What the function-key bar and the right-click menu offer at each level.
 *
 * <p>The host builds the bar from {@code menuItems(focusedResource)} — the resource under
 * the cursor — and fires only what the bar shows. An empty listing focuses nothing, and
 * the explorer root before any connection is saved is the one listing that can be empty,
 * so "New Connection" has to survive a {@code null} there or it can never be reached.
 */
class SqlFilePanelPluginMenuTest {

	private FakeContext context;
	private SqlFilePanelPlugin plugin;
	private Path dbFile;

	@BeforeEach
	void setUp(@TempDir Path dir) {
		context = new FakeContext();
		plugin = new SqlFilePanelPlugin();
		plugin.preinit(context);
		plugin.init();
		dbFile = dir.resolve("fixture.db");
	}

	@AfterEach
	void tearDown() {
		// Windows will not let @TempDir delete the fixture while its connection is open.
		plugin.unload();
	}

	/** Sort entries ride the same list but are parsed out by the commander, so keep them apart. */
	private static boolean isSort(NuclrMenuResource item) {
		return item.getEventType() != null && item.getEventType().startsWith("filepanel.sort:");
	}

	private List<String> barLabels(NuclrResource focused) {
		return plugin.menuItems(focused).stream().filter(i -> !isSort(i)).map(NuclrMenuResource::getName).toList();
	}

	private List<String> barFunctionKeys(NuclrResource focused) {
		return plugin.menuItems(focused).stream().filter(i -> !isSort(i))
				.map(NuclrMenuResource::getFunctionKey).toList();
	}

	private List<String> sortLabels(NuclrResource focused) {
		return plugin.menuItems(focused).stream().filter(SqlFilePanelPluginMenuTest::isSort)
				.map(NuclrMenuResource::getName).toList();
	}

	private List<String> sortEvents(NuclrResource focused) {
		return plugin.menuItems(focused).stream().filter(SqlFilePanelPluginMenuTest::isSort)
				.map(NuclrMenuResource::getEventType).toList();
	}

	private List<String> contextLabels(NuclrResource focused) {
		return plugin.contextMenuItems(focused, List.of()).stream()
				.map(NuclrContextMenuItem::getLabel).toList();
	}

	private SqlNuclrResource addConnectionAndList() throws Exception {
		new ConnectionProfileStore(context.settings).add(SqliteFixture.profile("Fixture", dbFile));
		var data = plugin.openResource(SqlNuclrResource.explorerRoot(), new AtomicBoolean(false));
		return (SqlNuclrResource) data.getEntryAt(0);
	}

	@Test
	void anEmptyExplorerRootStillOffersF7SoTheFirstConnectionCanBeMade() {
		// A fresh install: the root lists nothing, so the host has no focused resource.
		assertTrue(plugin.openResource(SqlNuclrResource.explorerRoot(), new AtomicBoolean(false))
				.getEntries().isEmpty());

		assertEquals(List.of("New Connection"), barLabels(null));
		assertEquals(List.of("F7"), barFunctionKeys(null));
	}

	@Test
	void anEmptyRootOffersNewConnectionOnRightClickToo() {
		plugin.openResource(SqlNuclrResource.explorerRoot(), new AtomicBoolean(false));

		assertEquals(List.of("New Connection…"), contextLabels(null));
	}

	@Test
	void aFocusedConnectionAlsoBindsEditAndDelete() throws Exception {
		var connection = addConnectionAndList();

		assertEquals(List.of("New Connection", "Edit Connection", "Delete Connection"), barLabels(connection));
		assertEquals(List.of("F7", "F4", "F8"), barFunctionKeys(connection));
	}

	@Test
	void editAndDeleteStayUnboundWhileNothingNamesAConnection() throws Exception {
		addConnectionAndList();

		// Right-clicking empty space below the list, or an empty root: there is no
		// connection to edit or delete, so binding those keys would only make them dead.
		assertEquals(List.of("New Connection"), barLabels(null));
	}

	@Test
	void aFocusedConnectionOffersTheWholeRightClickMenu() throws Exception {
		var connection = addConnectionAndList();

		// The null is the separator between the connection actions and Disconnect.
		assertEquals(
				Arrays.asList("New Connection…", "Edit Connection…", "Duplicate Connection", "Delete Connection…",
						null, "Disconnect"),
				contextLabels(connection));
	}

	@Test
	void aSchemaBindsNothingOnTheBar() throws Exception {
		var connection = addConnectionAndList();
		var schema = (SqlNuclrResource) plugin.openResource(connection, new AtomicBoolean(false)).getEntryAt(1);

		assertEquals(List.of(), barLabels(schema));
		assertEquals(List.of(), contextLabels(schema));
	}

	@Test
	void aTableBindsCopyAndOffersFilterSortAndACount() throws Exception {
		var connection = addConnectionAndList();
		var schema = (SqlNuclrResource) plugin.openResource(connection, new AtomicBoolean(false)).getEntryAt(1);
		var table = plugin.openResource(schema, new AtomicBoolean(false)).getEntries().stream()
				.filter(e -> "users".equals(e.getName())).findFirst().orElseThrow();

		assertEquals(List.of("Copy"), barLabels(table));
		assertEquals(List.of("F5"), barFunctionKeys(table));
		assertEquals(Arrays.asList("Filter / Sort…", "Count Rows", null, "Export to CSV…", "Copy as CSV"),
				contextLabels(table));
	}

	// ------------------------------------------------------------------
	// Sorts, which the commander parses out of the same menu-item list
	// ------------------------------------------------------------------

	@Test
	void theRootListingCanBeSortedByName() {
		plugin.openResource(SqlNuclrResource.explorerRoot(), new AtomicBoolean(false));

		assertEquals(List.of("Name", "Unsort", "Sort"), sortLabels(null));
		assertEquals(List.of("filepanel.sort:name:Name", "filepanel.sort:unsorted", "filepanel.sort:dialog"),
				sortEvents(null));
	}

	@Test
	void aSchemaListingAlsoOffersItsSizeColumn() throws Exception {
		var connection = addConnectionAndList();
		var schema = (SqlNuclrResource) plugin.openResource(connection, new AtomicBoolean(false)).getEntryAt(1);
		plugin.openResource(schema, new AtomicBoolean(false));

		assertEquals(List.of("Name", "Size", "Unsort", "Sort"), sortLabels(null));
		assertTrue(sortEvents(null).contains("filepanel.sort:size:Size"));
	}

	@Test
	void aRowListingSortsByKeyWithNoColumnToPointAt() throws Exception {
		var connection = addConnectionAndList();
		var schema = (SqlNuclrResource) plugin.openResource(connection, new AtomicBoolean(false)).getEntryAt(1);
		var table = plugin.openResource(schema, new AtomicBoolean(false)).getEntries().stream()
				.filter(e -> "users".equals(e.getName())).findFirst().orElseThrow();
		plugin.openResource(table, new AtomicBoolean(false));

		// The listing's columns are the table's own, so no generic comparator maps to one.
		assertEquals(List.of("Key", "Unsort", "Sort"), sortLabels(null));
		assertEquals("filepanel.sort:name", sortEvents(null).get(0));
	}

	@Test
	void everySortEntryLandsOnTheCtrlRowSoNoneClashWithAnAction() throws Exception {
		var connection = addConnectionAndList();

		for (var item : plugin.menuItems(connection)) {
			if (isSort(item)) {
				assertTrue(item.getFunctionKey().startsWith("Ctrl+"), item.getName() + " -> " + item.getFunctionKey());
			} else {
				assertFalse(item.getFunctionKey().startsWith("Ctrl+"), item.getName());
			}
		}
	}

	@Test
	void anEmptyTableListingFallsBackToTheTableItIsShowing() throws Exception {
		var connection = addConnectionAndList();
		var schema = (SqlNuclrResource) plugin.openResource(connection, new AtomicBoolean(false)).getEntryAt(1);
		var table = plugin.openResource(schema, new AtomicBoolean(false)).getEntries().stream()
				.filter(e -> "users".equals(e.getName())).findFirst().orElseThrow();
		plugin.openResource(table, new AtomicBoolean(false));

		// Right-clicking empty space inside a table now offers the table's own action
		// instead of the blanket "New Connection…" it used to.
		assertEquals(Arrays.asList("Filter / Sort…", "Count Rows", null, "Export to CSV…", "Copy as CSV"),
				contextLabels(null));
	}

	@Test
	void aForeignResourceFallsBackToWhereThisPanelStands() {
		plugin.openResource(SqlNuclrResource.explorerRoot(), new AtomicBoolean(false));

		// The host can hand over the other pane's selection while this panel has none.
		assertEquals(List.of("New Connection"), barLabels(new ForeignResource()));
	}

	/** Stands in for a resource belonging to some other file-panel plugin. */
	private static final class ForeignResource extends NuclrResource {

		private static final long serialVersionUID = 1L;

		ForeignResource() {
			super(null);
			setUuid("other://thing");
			setName("thing");
		}
	}
}
