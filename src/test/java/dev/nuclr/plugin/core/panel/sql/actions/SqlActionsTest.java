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
package dev.nuclr.plugin.core.panel.sql.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.core.panel.sql.SqlFilePanelPlugin;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.sql.support.FakeContext;
import dev.nuclr.plugin.core.panel.sql.support.RecordingCallback;
import dev.nuclr.plugin.core.panel.sql.support.SqliteFixture;

/**
 * The actions over a real SQLite database, run the way the commander runs them: on a
 * plugin instance that was only preinit-ed and init-ed, through {@code act} with no
 * other panel, no selection and no focused resource.
 */
class SqlActionsTest {

	private FakeContext context;
	private SqlFilePanelPlugin plugin;
	private ConnectionProfile profile;

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

	private RecordingCallback run(String id, Map<String, Object> args) {
		var callback = new RecordingCallback();
		plugin.act(null, id, List.of(), null, new HashMap<>(args), callback);
		return callback;
	}

	private Map<String, Object> ok(String id, Map<String, Object> args) {
		RecordingCallback callback = run(id, args);
		assertNull(callback.error, () -> id + " failed: " + callback.error);
		assertTrue(callback.completed, id + " should complete");
		assertNotNull(callback.result, id + " should report a result");
		return callback.result;
	}

	private String error(String id, Map<String, Object> args) {
		RecordingCallback callback = run(id, args);
		assertNotNull(callback.error, () -> id + " should fail, but returned " + callback.result);
		assertFalse(callback.completed);
		return callback.error;
	}

	@SuppressWarnings("unchecked")
	private static <T> T get(Map<String, Object> map, String key) {
		return (T) map.get(key);
	}

	// ------------------------------------------------------------------
	// actions.json
	// ------------------------------------------------------------------

	@Test
	void actionsJsonDeclaresExactlyTheHandledActions() throws Exception {
		String json = plugin.getActionsJson();
		assertNotNull(json, "actions.json should be found next to the plugin class");
		JsonNode root = new ObjectMapper().readTree(json);
		assertEquals(1, root.get("schemaVersion").asInt());

		var ids = new HashSet<String>();
		for (JsonNode action : root.get("actions")) {
			String id = action.get("id").asString();
			ids.add(id);
			assertTrue(id.matches("[a-z0-9._-]{1,48}"), id);
			String doc = action.get("doc").asString();
			assertNotNull(SqlFilePanelPlugin.class.getResource(doc), id + ": missing doc " + doc);
			for (JsonNode property : action.get("input").get("properties")) {
				assertTrue(property.has("description") || property.has("type"), id);
			}
		}
		assertEquals(SqlActions.IDS, ids);
	}

	@Test
	void onlyQueryIsUnrestricted() throws Exception {
		JsonNode root = new ObjectMapper().readTree(plugin.getActionsJson());
		for (JsonNode action : root.get("actions")) {
			String expected = SqlActions.QUERY.equals(action.get("id").asString()) ? "unrestricted" : "read";
			assertEquals(expected, action.get("class").asString(), action.get("id").asString());
		}
	}

	// ------------------------------------------------------------------
	// sql.connections.list
	// ------------------------------------------------------------------

	@Test
	void listsConnectionsWithoutSecrets() {
		Map<String, Object> result = ok(SqlActions.CONNECTIONS_LIST, Map.of());
		List<Map<String, Object>> connections = get(result, "connections");
		assertEquals(1, connections.size());
		assertEquals(profile.getId(), connections.get(0).get("id"));
		assertEquals("Fixture", connections.get(0).get("name"));
		assertNull(connections.get(0).get("username"));
	}

	@Test
	void redactsPasswordsInUrls() {
		assertEquals("jdbc:postgresql://db/shop?user=app&password=***&ssl=true",
				SqlActions.redactUrl("jdbc:postgresql://db/shop?user=app&password=hunter2&ssl=true"));
		assertEquals("jdbc:sqlserver://db;user=sa;Password=***;encrypt=true",
				SqlActions.redactUrl("jdbc:sqlserver://db;user=sa;Password=hunter2;encrypt=true"));
		assertEquals("jdbc:mysql://app:***@db:3306/shop", SqlActions.redactUrl("jdbc:mysql://app:hunter2@db:3306/shop"));
		assertEquals("jdbc:oracle:thin:scott/***@db:1521/orcl",
				SqlActions.redactUrl("jdbc:oracle:thin:scott/tiger@db:1521/orcl"));
		assertEquals("jdbc:postgresql://db:5432/shop", SqlActions.redactUrl("jdbc:postgresql://db:5432/shop"));
	}

	@Test
	void unknownConnectionNamesTheSavedOnes() {
		String error = error(SqlActions.TABLES_LIST, Map.of("connection", "nope"));
		assertTrue(error.contains("Fixture"), error);
	}

	@Test
	void connectionMatchesByNameIgnoringCase() {
		ok(SqlActions.TABLES_LIST, Map.of("connection", "fixture"));
	}

	// ------------------------------------------------------------------
	// sql.tables.list / sql.table.describe
	// ------------------------------------------------------------------

	@Test
	void listsTablesAndViews() {
		Map<String, Object> result = ok(SqlActions.TABLES_LIST, Map.of("connection", profile.getId()));
		List<Map<String, Object>> tables = get(result, "tables");
		var types = new HashMap<String, Object>();
		tables.forEach(table -> types.put((String) table.get("name"), table.get("type")));
		assertEquals(Map.of("users", "table", "no_pk", "table", "active_users", "view"), types);
		assertEquals(3, result.get("total"));
		assertEquals(false, result.get("truncated"));
	}

	@Test
	void tableListHonoursPatternAndLimit() {
		Map<String, Object> filtered = ok(SqlActions.TABLES_LIST, Map.of("connection", "Fixture", "nameLike", "us%"));
		assertEquals(1, filtered.get("total"));

		Map<String, Object> limited = ok(SqlActions.TABLES_LIST, Map.of("connection", "Fixture", "limit", 2));
		assertEquals(2, ((List<?>) limited.get("tables")).size());
		assertEquals(true, limited.get("truncated"));
	}

	@Test
	void describesColumnsAndKeys() {
		Map<String, Object> result = ok(SqlActions.TABLE_DESCRIBE,
				Map.of("connection", "Fixture", "table", "USERS", "countRows", true));
		assertEquals("users", result.get("table"), "the database's own spelling comes back");
		assertEquals("table", result.get("type"));
		assertEquals(List.of("id"), result.get("primaryKey"));
		assertEquals(3L, result.get("rowCount"));

		List<Map<String, Object>> columns = get(result, "columns");
		assertEquals(List.of("id", "name", "email"), columns.stream().map(c -> c.get("name")).toList());
		assertEquals(1, columns.get(0).get("primaryKeyPosition"));
		assertNull(columns.get(1).get("primaryKeyPosition"));
	}

	@Test
	void unknownTableSuggestsListing() {
		String error = error(SqlActions.TABLE_DESCRIBE, Map.of("connection", "Fixture", "table", "orders"));
		assertTrue(error.contains("sql.tables.list"), error);
	}

	// ------------------------------------------------------------------
	// sql.rows.read
	// ------------------------------------------------------------------

	@Test
	void readsChosenColumnsFilteredAndSorted() {
		Map<String, Object> result = ok(SqlActions.ROWS_READ, Map.of("connection", "Fixture", "table", "users",
				"columns", List.of("name", "id"), "orderBy", List.of("-id")));
		assertEquals(List.of("name", "id"), result.get("columns"));
		List<List<Object>> rows = get(result, "rows");
		assertEquals(List.of(SqliteFixture.AwkwardName, 3), rows.get(0));
		assertEquals(List.of("Ada", 1), rows.get(2));
	}

	@Test
	void whereMatchesValuesAndNull() {
		Map<String, Object> byName = ok(SqlActions.ROWS_READ,
				Map.of("connection", "Fixture", "table", "users", "where", Map.of("name", "Ada")));
		assertEquals(1, ((List<?>) byName.get("rows")).size());

		var nullEmail = new HashMap<String, Object>();
		nullEmail.put("email", null);
		Map<String, Object> byNull = ok(SqlActions.ROWS_READ,
				Map.of("connection", "Fixture", "table", "users", "columns", List.of("name"), "where", nullEmail));
		assertEquals(List.of(List.of("Grace")), byNull.get("rows"));
	}

	@Test
	void pagesWithOffsetAndLimit() {
		Map<String, Object> first = ok(SqlActions.ROWS_READ, Map.of("connection", "Fixture", "table", "users",
				"columns", List.of("id"), "orderBy", List.of("id"), "limit", 2));
		assertEquals(List.of(List.of(1), List.of(2)), first.get("rows"));
		assertEquals(true, first.get("truncated"));

		Map<String, Object> second = ok(SqlActions.ROWS_READ, Map.of("connection", "Fixture", "table", "users",
				"columns", List.of("id"), "orderBy", List.of("id"), "limit", 2, "offset", 2));
		assertEquals(List.of(List.of(3)), second.get("rows"));
		assertEquals(false, second.get("truncated"));
	}

	@Test
	void columnNamesAreCheckedNotPastedIntoSql() {
		String error = error(SqlActions.ROWS_READ, Map.of("connection", "Fixture", "table", "users",
				"orderBy", List.of("id; DROP TABLE users")));
		assertTrue(error.contains("no column"), error);
		error = error(SqlActions.ROWS_READ, Map.of("connection", "Fixture", "table", "users",
				"where", Map.of("1=1 OR name", "x")));
		assertTrue(error.contains("no column"), error);

		Map<String, Object> still = ok(SqlActions.TABLE_DESCRIBE,
				Map.of("connection", "Fixture", "table", "users", "countRows", true));
		assertEquals(3L, still.get("rowCount"));
	}

	// ------------------------------------------------------------------
	// sql.query
	// ------------------------------------------------------------------

	@Test
	void queryReturnsRowsAndUpdateCounts() {
		Map<String, Object> update = ok(SqlActions.QUERY,
				Map.of("connection", "Fixture", "sql", "UPDATE users SET email = 'g@example.com' WHERE id = 2"));
		List<Map<String, Object>> updateResults = get(update, "results");
		assertEquals(List.of(Map.of("updateCount", 1)), updateResults);

		Map<String, Object> select = ok(SqlActions.QUERY,
				Map.of("connection", "Fixture", "sql", "SELECT email FROM users WHERE id = 2"));
		List<Map<String, Object>> results = get(select, "results");
		assertEquals(List.of(List.of("g@example.com")), results.get(0).get("rows"));
	}

	@Test
	void queryCapsRows() {
		Map<String, Object> result = ok(SqlActions.QUERY,
				Map.of("connection", "Fixture", "sql", "SELECT id FROM users ORDER BY id", "maxRows", 1));
		List<Map<String, Object>> results = get(result, "results");
		assertEquals(List.of(List.of(1)), results.get(0).get("rows"));
		assertEquals(true, results.get(0).get("truncated"));
	}

	@Test
	void cancelledQueryNeverRuns() {
		var callback = new RecordingCallback();
		callback.cancelled.set(true);
		plugin.act(null, SqlActions.QUERY, List.of(), null,
				new HashMap<>(Map.of("connection", "Fixture", "sql", "DELETE FROM users")), callback);
		assertNotNull(callback.error);

		Map<String, Object> count = ok(SqlActions.TABLE_DESCRIBE,
				Map.of("connection", "Fixture", "table", "users", "countRows", true));
		assertEquals(3L, count.get("rowCount"));
	}

	@Test
	void badSqlIsReportedWithTheConnectionName() {
		String error = error(SqlActions.QUERY, Map.of("connection", "Fixture", "sql", "SELEC nonsense"));
		assertTrue(error.startsWith("Fixture: "), error);
	}

	// ------------------------------------------------------------------
	// Values
	// ------------------------------------------------------------------

	@Test
	void valuesBecomeJsonFriendly() throws Exception {
		assertEquals("12345678901234567890.0001", SqlActions.jsonValue(new BigDecimal("12345678901234567890.0001")));
		assertEquals("base64:AQI=", SqlActions.jsonValue(new byte[] { 1, 2 }));
		assertEquals("(binary, 4000 bytes)", SqlActions.jsonValue(new byte[4000]));
		assertEquals("NaN", SqlActions.jsonValue(Double.NaN));
		assertEquals(7, SqlActions.jsonValue(7));
		assertEquals("2026-10-03", SqlActions.jsonValue(java.sql.Date.valueOf("2026-10-03")));
		String cut = (String) SqlActions.jsonValue("x".repeat(SqlActions.MAX_VALUE_CHARS + 5));
		assertTrue(cut.endsWith("[cut; " + (SqlActions.MAX_VALUE_CHARS + 5) + " characters in all]"), cut);
	}
}
