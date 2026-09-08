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
package dev.nuclr.plugin.core.panel.sql.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.sql.PluginIds;
import dev.nuclr.plugin.core.panel.sql.support.FakeSettings;

class ConnectionProfileStoreTest {

	private FakeSettings settings;
	private ConnectionProfileStore store;

	@BeforeEach
	void setUp() {
		settings = new FakeSettings();
		store = new ConnectionProfileStore(settings);
	}

	private static ConnectionProfile.ConnectionProfileBuilder shop() {
		return ConnectionProfile.builder()
				.name("Shop")
				.driverKey("postgresql")
				.host("db.example.com")
				.port("5432")
				.database("shop")
				.jdbcUrl("jdbc:postgresql://db.example.com:5432/shop")
				.username("app")
				.useSsl(true);
	}

	@Test
	void startsEmpty() {
		assertTrue(store.loadAll().isEmpty());
	}

	@Test
	void addAssignsAnIdAndTheProfileComesBackWholeOnTheNextRead() {
		ConnectionProfile added = store.add(shop().build());

		assertNotNull(added.getId());
		ConnectionProfile reloaded = new ConnectionProfileStore(settings).findById(added.getId());
		assertEquals("Shop", reloaded.getName());
		assertEquals("postgresql", reloaded.getDriverKey());
		assertEquals("jdbc:postgresql://db.example.com:5432/shop", reloaded.getJdbcUrl());
		assertEquals("db.example.com", reloaded.getHost());
		assertEquals("5432", reloaded.getPort());
		assertEquals("shop", reloaded.getDatabase());
		assertEquals("app", reloaded.getUsername());
		assertTrue(reloaded.isUseSsl());
	}

	@Test
	void addKeepsAnIdTheCallerAlreadyChose() {
		assertEquals("fixed-id", store.add(shop().id("fixed-id").build()).getId());
	}

	@Test
	void theStoredJsonCarriesNoPasswordAndNoDerivedFields() {
		store.add(shop().build());

		String json = settings.get(PluginIds.PLUGIN_ID, "profiles.json");
		assertFalse(json.contains("password"), json);
		assertFalse(json.contains("driverLabel"), json);
		assertFalse(json.contains("displayTarget"), json);
	}

	@Test
	void updateReplacesOnlyTheMatchingProfile() {
		ConnectionProfile first = store.add(shop().build());
		ConnectionProfile second = store.add(shop().name("Other").build());

		first.setName("Renamed");
		store.update(first);

		assertEquals("Renamed", store.findById(first.getId()).getName());
		assertEquals("Other", store.findById(second.getId()).getName());
		assertEquals(2, store.loadAll().size());
	}

	@Test
	void deleteRemovesOnlyTheMatchingProfile() {
		ConnectionProfile first = store.add(shop().build());
		ConnectionProfile second = store.add(shop().name("Other").build());

		store.delete(first.getId());

		assertNull(store.findById(first.getId()));
		assertNotNull(store.findById(second.getId()));
	}

	@Test
	void findByIdReturnsNullForAnUnknownId() {
		store.add(shop().build());

		assertNull(store.findById("nope"));
	}

	@Test
	void corruptStoredJsonDegradesToAnEmptyListRatherThanFailing() {
		settings.putRaw(PluginIds.PLUGIN_ID, "profiles.json", "{not json");

		assertTrue(store.loadAll().isEmpty());
	}

	@Test
	void blankStoredJsonDegradesToAnEmptyList() {
		settings.putRaw(PluginIds.PLUGIN_ID, "profiles.json", "   ");

		assertTrue(store.loadAll().isEmpty());
	}

	@Test
	void aProfileSavedBeforeTheHostPortDatabaseFieldsExistedStillLoads() {
		settings.putRaw(PluginIds.PLUGIN_ID, "profiles.json",
				"[{\"id\":\"old\",\"name\":\"Legacy\",\"driverKey\":\"mysql\",\"jdbcUrl\":\"jdbc:mysql://host/db\"}]");

		ConnectionProfile legacy = store.findById("old");

		assertEquals("Legacy", legacy.getName());
		assertNull(legacy.getHost());
		assertNull(legacy.getDatabase());
	}

	@Test
	void loadAllHandsBackACopyThatCannotWriteThroughToTheStore() {
		store.add(shop().build());

		store.loadAll().clear();

		assertEquals(1, store.loadAll().size());
	}
}
