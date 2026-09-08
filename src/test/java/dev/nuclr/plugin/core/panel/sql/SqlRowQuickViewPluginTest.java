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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;
import dev.nuclr.plugin.core.panel.sql.resource.SqlNuclrResource;
import dev.nuclr.plugin.core.panel.sql.support.FakeContext;

class SqlRowQuickViewPluginTest {

	private SqlRowQuickViewPlugin plugin;
	private FakeContext context;

	private static SqlNuclrResource sampleRow() {
		var values = new LinkedHashMap<String, Object>();
		values.put("id", 1);
		values.put("name", "Ada");
		return SqlNuclrResource.row("p1", "main", "users", "1", List.of("id", "name"), values);
	}

	private static void drainEventQueue() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			// Everything the plugin posted has run by the time this empty task does.
		});
	}

	@BeforeEach
	void setUp() {
		context = new FakeContext();
		plugin = new SqlRowQuickViewPlugin();
		plugin.preinit(context);
		plugin.init();
	}

	@Test
	void previewsRowsTablesAndViewsAndNothingElse() {
		assertTrue(plugin.supports(sampleRow()));
		assertTrue(plugin.supports(SqlNuclrResource.table("p1", "main", "t", false, "\"main\".\"t\"", null)));
		assertTrue(plugin.supports(SqlNuclrResource.table("p1", "main", "v", true, "\"main\".\"v\"", null)));

		assertFalse(plugin.supports(SqlNuclrResource.explorerRoot()));
		assertFalse(plugin.supports(SqlNuclrResource.schema("p1", "main")));
		assertFalse(plugin.supports(SqlNuclrResource.connection(ConnectionProfile.builder().id("p1").build())));
		assertFalse(plugin.supports(SqlNuclrResource.rowLimitMarker("p1", "main", "t", 50_000)));
	}

	@Test
	void aResourceFromAnotherPluginIsNeverClaimed() {
		assertFalse(plugin.supports(null));
	}

	@Test
	void thePanelIsBuiltOnceAndReused() {
		var panel = plugin.panel();

		assertNotNull(panel);
		assertSame(panel, plugin.panel());
	}

	@Test
	void openingARowMakesItTheCurrentResource() throws Exception {
		var row = sampleRow();

		assertTrue(plugin.openResource(row, new AtomicBoolean(false)));
		drainEventQueue();

		assertSame(row, plugin.getCurrentResource());
	}

	@Test
	void anUnsupportedResourceIsDeclinedAndChangesNothing() {
		assertFalse(plugin.openResource(SqlNuclrResource.schema("p1", "main"), new AtomicBoolean(false)));

		assertNull(plugin.getCurrentResource());
	}

	@Test
	void closingTheResourceLetsGoOfIt() throws Exception {
		plugin.openResource(sampleRow(), new AtomicBoolean(false));
		drainEventQueue();

		plugin.closeResource();
		drainEventQueue();

		assertNull(plugin.getCurrentResource());
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
	void unloadIsSafeBeforeAnythingWasEverOpened() {
		plugin.unload();
	}

	@Test
	void theContextItWasGivenIsTheOneItReports() {
		assertSame(context, plugin.getContext());
		assertNotNull(plugin.uuid());
	}
}
