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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.plugin.core.panel.sql.TableStats;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.sql.connection.ConnectionRegistry;
import dev.nuclr.plugin.core.panel.sql.connection.Schemas;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the actions this plugin declares in {@code actions.json}: listing saved
 * connections, tables and columns, reading rows, and running SQL.
 *
 * <p>Actions need no pane, selection or open resource, so they behave the same on a
 * headless plugin instance. The commander keeps one such instance for actions, so
 * their connections are their own: a transaction an agent leaves open never shows up
 * in a pane the user is browsing, and the reverse.
 *
 * <p>Only {@code sql.query} runs SQL the caller wrote. The read actions build every
 * statement themselves, from identifiers checked against the database's own metadata
 * and values bound as parameters, so nothing in their arguments can turn into a
 * second statement.
 *
 * <p>Error messages go back to whoever asked - often an agent - so they say what to
 * do next, not just what went wrong.
 *
 * <p>This class is the only place the plugin uses SDK API newer than its manifest's
 * {@code platformSdkVersion} ({@code onResult}); only Commanders that have that API
 * run actions.
 */
@Slf4j
public final class SqlActions {

	/** Connects a saved profile. Production shares the plugin instance's registry and password prompt. */
	@FunctionalInterface
	public interface Opener {

		/**
		 * Return the live session for the profile, connecting on first use.
		 *
		 * @param profile the saved connection
		 * @return the session, or {@code null} if the user dismissed the password prompt
		 * @throws Exception if the driver cannot be loaded or the connection fails
		 */
		ConnectionRegistry.Session open(ConnectionProfile profile) throws Exception;
	}

	public static final String CONNECTIONS_LIST = "sql.connections.list";
	public static final String TABLES_LIST = "sql.tables.list";
	public static final String TABLE_DESCRIBE = "sql.table.describe";
	public static final String ROWS_READ = "sql.rows.read";
	public static final String QUERY = "sql.query";

	/** Every action id this class handles; must match actions.json exactly. */
	public static final Set<String> IDS = Set.of(CONNECTIONS_LIST, TABLES_LIST, TABLE_DESCRIBE, ROWS_READ, QUERY);

	static final int DEFAULT_TABLE_LIMIT = 500;
	static final int MAX_TABLE_LIMIT = 5000;
	static final int DEFAULT_ROW_LIMIT = 100;
	static final int MAX_ROW_LIMIT = 1000;
	static final int MAX_OFFSET = 100_000;
	static final int DEFAULT_QUERY_ROWS = 200;
	static final int MAX_QUERY_ROWS = 5000;
	static final int DEFAULT_TIMEOUT_SECONDS = 60;
	static final int MAX_TIMEOUT_SECONDS = 3600;
	/** Result sets and update counts kept from one sql.query call. */
	static final int MAX_RESULTS = 20;
	/** Characters kept of one value; longer text is cut and says so. */
	static final int MAX_VALUE_CHARS = 8000;
	/** Binary values up to this size come back as base64, larger ones as a size note. */
	static final int MAX_INLINE_BINARY = 3000;
	/** Rough budget for all the values in one result; rows past it are dropped, with truncated set. */
	static final int MAX_RESULT_CHARS = 1_000_000;

	private static final long POLL_MILLIS = 250;

	/**
	 * Schemas left out when a table listing names none: the database's own catalog
	 * views, which bury the user's tables under hundreds of system ones. Naming one
	 * explicitly still lists it.
	 */
	private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "pg_catalog", "pg_toast",
			"performance_schema", "mysql", "sys");

	private final ConnectionProfileStore store;
	private final Opener opener;
	private final Consumer<String> forget;

	/**
	 * @param store  where saved connection profiles are read from
	 * @param opener connects a profile
	 * @param forget drops a profile's session after its connection broke, so the next
	 *               call connects afresh
	 */
	public SqlActions(ConnectionProfileStore store, Opener opener, Consumer<String> forget) {
		this.store = store;
		this.opener = opener;
		this.forget = forget;
	}

	/**
	 * Return whether {@code actionType} is one of this plugin's declared actions.
	 *
	 * @param actionType the action id passed to {@code act}
	 * @return {@code true} if {@link #run} handles it
	 */
	public static boolean handles(String actionType) {
		return actionType != null && IDS.contains(actionType);
	}

	/**
	 * Run an action and report through the callback: {@code onResult} then
	 * {@code onComplete} on success, {@code onError} otherwise.
	 *
	 * @param id       the action id
	 * @param args     the arguments, already validated against the action's schema
	 * @param callback progress, result and cancellation
	 */
	public void run(String id, Map<String, Object> args, NuclrPluginCallback callback) {
		Map<String, Object> arguments = args == null ? Map.of() : args;
		try {
			Map<String, Object> result = switch (id) {
				case CONNECTIONS_LIST -> connectionsList();
				case TABLES_LIST -> tablesList(arguments, callback);
				case TABLE_DESCRIBE -> tableDescribe(arguments, callback);
				case ROWS_READ -> rowsRead(arguments, callback);
				case QUERY -> query(arguments, callback);
				default -> throw new ActionException("Unknown action '" + id + "'.");
			};
			callback.onResult(result);
			callback.onComplete();
		} catch (ActionException e) {
			callback.onError(e.getMessage(), e);
		} catch (Exception e) {
			log.warn("Action {} failed: {}", id, e.toString());
			callback.onError(id + " failed: " + describe(e), e);
		}
	}

	// =========================================================================
	// sql.connections.list
	// =========================================================================

	private Map<String, Object> connectionsList() {
		var connections = new ArrayList<Map<String, Object>>();
		for (ConnectionProfile profile : store.loadAll()) {
			var connection = new LinkedHashMap<String, Object>();
			connection.put("id", profile.getId());
			connection.put("name", profile.getName());
			connection.put("driver", profile.getDriverLabel());
			connection.put("url", redactUrl(profile.getJdbcUrl()));
			connection.put("username", blankToNull(profile.getUsername()));
			connections.add(connection);
		}
		connections.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
		return Map.of("connections", connections);
	}

	/**
	 * A JDBC URL with anything that looks like a secret replaced by {@code ***}:
	 * password-like parameters ({@code password=}, {@code pwd=}, ...), the password
	 * part of {@code //user:password@host}, and Oracle's {@code thin:user/password@}.
	 */
	static String redactUrl(String url) {
		if (url == null) {
			return null;
		}
		return url
				.replaceAll("(?i)((?:password|passwd|pwd|secret|token|accesstoken|access_token)\\s*=)[^;&]*", "$1***")
				.replaceAll("(//[^/@:;?]*:)[^@/]*@", "$1***@")
				.replaceAll("(?i)(:thin:[^/@:]+/)[^@]*@", "$1***@");
	}

	// =========================================================================
	// sql.tables.list
	// =========================================================================

	private Map<String, Object> tablesList(Map<String, Object> args, NuclrPluginCallback callback) throws Exception {
		ConnectionProfile profile = profile(text(args, "connection", true));
		String schema = text(args, "schema", false);
		String nameLike = text(args, "nameLike", false);
		int limit = integer(args, "limit", DEFAULT_TABLE_LIMIT, 1, MAX_TABLE_LIMIT);

		return withConnection(profile, session -> {
			Connection connection = session.connection();
			List<String> allSchemas = Schemas.names(connection, callback::isCancelled);
			List<String> schemas;
			if (schema != null) {
				schemas = List.of(schemaNamed(allSchemas, schema, profile));
			} else {
				schemas = allSchemas.stream()
						.filter(name -> name == null || !SYSTEM_SCHEMAS.contains(name.toLowerCase(Locale.ROOT)))
						.toList();
			}

			DatabaseMetaData meta = connection.getMetaData();
			String catalog = Schemas.catalog(connection);
			var tables = new ArrayList<Map<String, Object>>();
			for (String schemaName : schemas) {
				checkCancelled(callback);
				Map<String, Long> estimates = TableStats.estimate(connection, profile.getJdbcUrl(), schemaName);
				try (ResultSet rs = meta.getTables(catalog, likeLiteral(meta, schemaName),
						nameLike == null ? "%" : nameLike, new String[] { "TABLE", "VIEW" })) {
					while (rs.next()) {
						checkCancelled(callback);
						// Schema names are patterns to getTables, so "a_b" also matches "axb".
						String rowSchema = rs.getString("TABLE_SCHEM");
						if (rowSchema != null && !rowSchema.equals(schemaName)) {
							continue;
						}
						String name = rs.getString("TABLE_NAME");
						var table = new LinkedHashMap<String, Object>();
						table.put("schema", schemaName);
						table.put("name", name);
						table.put("type", "VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE")) ? "view" : "table");
						table.put("estimatedRows", estimates.get(name));
						tables.add(table);
					}
				}
			}

			int total = tables.size();
			boolean truncated = total > limit;
			var result = new LinkedHashMap<String, Object>();
			result.put("schemas", allSchemas);
			result.put("tables", truncated ? new ArrayList<>(tables.subList(0, limit)) : tables);
			result.put("total", total);
			result.put("truncated", truncated);
			return result;
		});
	}

	// =========================================================================
	// sql.table.describe
	// =========================================================================

	private Map<String, Object> tableDescribe(Map<String, Object> args, NuclrPluginCallback callback) throws Exception {
		ConnectionProfile profile = profile(text(args, "connection", true));
		String schemaArg = text(args, "schema", false);
		String tableName = text(args, "table", true);
		boolean countRows = flag(args, "countRows", false);

		return withConnection(profile, session -> {
			Connection connection = session.connection();
			String schema = schemaFor(connection, schemaArg, profile, callback);
			TableInfo table = table(connection, schema, tableName, profile);

			DatabaseMetaData meta = connection.getMetaData();
			String catalog = Schemas.catalog(connection);
			var keyPositions = new TreeMap<Short, String>();
			try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table.name())) {
				while (rs.next()) {
					keyPositions.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
				}
			} catch (SQLException e) {
				log.debug("No primary key information for {}.{}: {}", schema, table.name(), e.getMessage());
			}
			List<String> primaryKey = new ArrayList<>(keyPositions.values());

			var columns = new ArrayList<Map<String, Object>>();
			try (ResultSet rs = meta.getColumns(catalog, likeLiteral(meta, schema), likeLiteral(meta, table.name()),
					"%")) {
				while (rs.next()) {
					if (!sameTable(rs, schema, table.name())) {
						continue;
					}
					String name = rs.getString("COLUMN_NAME");
					var column = new LinkedHashMap<String, Object>();
					column.put("name", name);
					column.put("type", rs.getString("TYPE_NAME"));
					column.put("size", nullableInt(rs, "COLUMN_SIZE"));
					column.put("nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
					column.put("default", rs.getString("COLUMN_DEF"));
					column.put("autoIncrement", "YES".equalsIgnoreCase(optionalString(rs, "IS_AUTOINCREMENT")));
					int keyIndex = primaryKey.indexOf(name);
					column.put("primaryKeyPosition", keyIndex < 0 ? null : keyIndex + 1);
					column.put("comment", blankToNull(rs.getString("REMARKS")));
					columns.add(column);
				}
			}

			var foreignKeys = new ArrayList<Map<String, Object>>();
			try (ResultSet rs = meta.getImportedKeys(catalog, schema, table.name())) {
				while (rs.next()) {
					var key = new LinkedHashMap<String, Object>();
					key.put("column", rs.getString("FKCOLUMN_NAME"));
					key.put("referencesSchema", rs.getString("PKTABLE_SCHEM"));
					key.put("referencesTable", rs.getString("PKTABLE_NAME"));
					key.put("referencesColumn", rs.getString("PKCOLUMN_NAME"));
					foreignKeys.add(key);
				}
			} catch (SQLException e) {
				log.debug("No foreign key information for {}.{}: {}", schema, table.name(), e.getMessage());
			}

			var result = new LinkedHashMap<String, Object>();
			result.put("schema", schema);
			result.put("table", table.name());
			result.put("type", table.view() ? "view" : "table");
			result.put("columns", columns);
			result.put("primaryKey", primaryKey);
			result.put("foreignKeys", foreignKeys);
			result.put("estimatedRows",
					TableStats.estimate(connection, profile.getJdbcUrl(), schema).get(table.name()));
			if (countRows) {
				try (Statement statement = connection.createStatement()) {
					String sql = "SELECT COUNT(*) FROM " + qualified(session, schema, table.name());
					result.put("rowCount", cancellable(statement, callback, "Cancelled while counting rows.", () -> {
						try (ResultSet rs = statement.executeQuery(sql)) {
							return rs.next() ? rs.getLong(1) : 0L;
						}
					}));
				}
			}
			return result;
		});
	}

	// =========================================================================
	// sql.rows.read
	// =========================================================================

	private Map<String, Object> rowsRead(Map<String, Object> args, NuclrPluginCallback callback) throws Exception {
		ConnectionProfile profile = profile(text(args, "connection", true));
		String schemaArg = text(args, "schema", false);
		String tableName = text(args, "table", true);
		List<String> wantedColumns = strings(args, "columns");
		Map<String, Object> where = object(args, "where");
		List<String> orderBy = strings(args, "orderBy");
		int limit = integer(args, "limit", DEFAULT_ROW_LIMIT, 1, MAX_ROW_LIMIT);
		int offset = integer(args, "offset", 0, 0, MAX_OFFSET);

		return withConnection(profile, session -> {
			Connection connection = session.connection();
			String schema = schemaFor(connection, schemaArg, profile, callback);
			TableInfo table = table(connection, schema, tableName, profile);
			List<String> known = columnNames(connection, schema, table.name());

			var sql = new StringBuilder("SELECT ");
			if (wantedColumns.isEmpty()) {
				sql.append('*');
			} else {
				var selected = new ArrayList<String>();
				for (String name : wantedColumns) {
					selected.add(quote(session, column(known, name, "columns", table.name())));
				}
				sql.append(String.join(", ", selected));
			}
			sql.append(" FROM ").append(qualified(session, schema, table.name()));

			var parameters = new ArrayList<Object>();
			if (!where.isEmpty()) {
				var conditions = new ArrayList<String>();
				for (var condition : where.entrySet()) {
					String column = quote(session, column(known, condition.getKey(), "where", table.name()));
					Object value = condition.getValue();
					if (value == null) {
						conditions.add(column + " IS NULL");
					} else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
						conditions.add(column + " = ?");
						parameters.add(value);
					} else {
						throw new ActionException("Argument 'where': the value for '" + condition.getKey()
								+ "' must be a string, number, boolean or null. Use sql.query for other conditions.");
					}
				}
				sql.append(" WHERE ").append(String.join(" AND ", conditions));
			}
			if (!orderBy.isEmpty()) {
				var terms = new ArrayList<String>();
				for (String term : orderBy) {
					boolean descending = term.startsWith("-");
					String name = descending ? term.substring(1) : term;
					terms.add(quote(session, column(known, name, "orderBy", table.name())) + (descending ? " DESC" : ""));
				}
				sql.append(" ORDER BY ").append(String.join(", ", terms));
			}

			try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
				for (int i = 0; i < parameters.size(); i++) {
					statement.setObject(i + 1, parameters.get(i));
				}
				// No OFFSET clause: its spelling differs between databases, and some have
				// none. Skipping rows here works everywhere, and offsets are capped.
				statement.setMaxRows(offset + limit + 1);
				Rows rows = cancellable(statement, callback, "Cancelled while reading rows.", () -> {
					try (ResultSet rs = statement.executeQuery()) {
						for (int skipped = 0; skipped < offset && rs.next(); skipped++) {
							checkCancelled(callback);
						}
						return readRows(rs, limit, callback);
					}
				});
				var result = new LinkedHashMap<String, Object>();
				result.put("schema", schema);
				result.put("table", table.name());
				result.put("columns", rows.columns());
				result.put("rows", rows.values());
				result.put("offset", offset);
				result.put("truncated", rows.truncated());
				return result;
			}
		});
	}

	// =========================================================================
	// sql.query
	// =========================================================================

	private Map<String, Object> query(Map<String, Object> args, NuclrPluginCallback callback) throws Exception {
		ConnectionProfile profile = profile(text(args, "connection", true));
		String sql = text(args, "sql", true);
		int maxRows = integer(args, "maxRows", DEFAULT_QUERY_ROWS, 1, MAX_QUERY_ROWS);
		int timeoutSeconds = integer(args, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);

		return withConnection(profile, session -> {
			Connection connection = session.connection();
			// Connecting can take a while, password prompt included; a Cancel pressed
			// meanwhile must mean the statement never runs.
			checkCancelled(callback, "Cancelled before the statement ran; nothing was executed.");
			try (Statement statement = connection.createStatement()) {
				statement.setQueryTimeout(timeoutSeconds);
				statement.setMaxRows(maxRows + 1);
				List<Map<String, Object>> results = cancellable(statement, callback,
						"Cancelled. The statement was stopped; with auto-commit on, statements that had already "
								+ "finished stay committed.",
						() -> {
							var collected = new ArrayList<Map<String, Object>>();
							boolean isResultSet = statement.execute(sql);
							while (collected.size() < MAX_RESULTS) {
								if (isResultSet) {
									try (ResultSet rs = statement.getResultSet()) {
										Rows rows = readRows(rs, maxRows, callback);
										var entry = new LinkedHashMap<String, Object>();
										entry.put("columns", rows.columns());
										entry.put("rows", rows.values());
										entry.put("truncated", rows.truncated());
										collected.add(entry);
									}
								} else {
									int count = statement.getUpdateCount();
									if (count == -1) {
										break;
									}
									collected.add(Map.of("updateCount", count));
								}
								isResultSet = statement.getMoreResults();
							}
							return collected;
						});
				return Map.of("results", results);
			}
		});
	}

	// =========================================================================
	// Reading result sets
	// =========================================================================

	/** Columns, rows as value lists in column order, and whether rows were left out. */
	record Rows(List<String> columns, List<List<Object>> values, boolean truncated) {
	}

	private static Rows readRows(ResultSet rs, int limit, NuclrPluginCallback callback)
			throws SQLException, ActionException {
		ResultSetMetaData meta = rs.getMetaData();
		int columnCount = meta.getColumnCount();
		var columns = new ArrayList<String>(columnCount);
		for (int i = 1; i <= columnCount; i++) {
			columns.add(meta.getColumnLabel(i));
		}
		var values = new ArrayList<List<Object>>();
		long budget = MAX_RESULT_CHARS;
		boolean truncated = false;
		while (rs.next()) {
			checkCancelled(callback);
			if (values.size() >= limit || budget <= 0) {
				truncated = true;
				break;
			}
			var row = new ArrayList<Object>(columnCount);
			for (int i = 1; i <= columnCount; i++) {
				Object value = jsonValue(rs.getObject(i));
				budget -= value instanceof String text ? text.length() : 8;
				row.add(value);
			}
			values.add(row);
		}
		return new Rows(columns, values, truncated);
	}

	/**
	 * A column value as a JSON-compatible one: numbers and booleans as they are, text
	 * cut at {@link #MAX_VALUE_CHARS}, dates and times in ISO form, small binary values
	 * as {@code base64:...}, and anything else as its text.
	 */
	static Object jsonValue(Object value) throws SQLException {
		if (value == null || value instanceof Boolean) {
			return value;
		}
		if (value instanceof Double d && (d.isNaN() || d.isInfinite())
				|| value instanceof Float f && (f.isNaN() || f.isInfinite())) {
			return value.toString();
		}
		if (value instanceof Number number) {
			// Arbitrary-precision numbers keep their digits as text: a JSON reader would
			// round them through a double.
			return number instanceof BigDecimal || number instanceof BigInteger ? number.toString() : number;
		}
		if (value instanceof byte[] bytes) {
			return binary(bytes.length, bytes);
		}
		if (value instanceof Blob blob) {
			long length = blob.length();
			return binary(length, length <= MAX_INLINE_BINARY ? blob.getBytes(1, (int) length) : null);
		}
		if (value instanceof Clob clob) {
			long length = clob.length();
			return cut(clob.getSubString(1, (int) Math.min(length, MAX_VALUE_CHARS + 1L)), length);
		}
		if (value instanceof java.sql.Timestamp timestamp) {
			return timestamp.toLocalDateTime().toString();
		}
		if (value instanceof java.sql.Date date) {
			return date.toLocalDate().toString();
		}
		if (value instanceof java.sql.Time time) {
			return time.toLocalTime().toString();
		}
		if (value instanceof Date date) {
			return date.toInstant().toString();
		}
		if (value instanceof TemporalAccessor) {
			return value.toString();
		}
		String text = value.toString();
		return cut(text, text.length());
	}

	private static String binary(long length, byte[] bytes) {
		if (bytes != null && length <= MAX_INLINE_BINARY) {
			return "base64:" + Base64.getEncoder().encodeToString(bytes);
		}
		return "(binary, " + length + " bytes)";
	}

	private static String cut(String text, long fullLength) {
		if (fullLength <= MAX_VALUE_CHARS) {
			return text;
		}
		return text.substring(0, MAX_VALUE_CHARS) + "... [cut; " + fullLength + " characters in all]";
	}

	// =========================================================================
	// Connections, schemas, tables and columns
	// =========================================================================

	@FunctionalInterface
	private interface SessionWork<T> {
		T run(ConnectionRegistry.Session session) throws Exception;
	}

	@FunctionalInterface
	private interface SqlWork<T> {
		T run() throws SQLException, ActionException;
	}

	/**
	 * Run {@code work} on the profile's session, one action at a time per connection:
	 * a JDBC connection is not meant for concurrent statements. A connection found
	 * broken afterwards is dropped, so the next call connects again.
	 */
	private <T> T withConnection(ConnectionProfile profile, SessionWork<T> work) throws Exception {
		ConnectionRegistry.Session session = connect(profile);
		synchronized (session) {
			try {
				return work.run(session);
			} catch (SQLException e) {
				if (!isValid(session.connection())) {
					log.info("Connection to {} is no longer usable; it will reconnect on the next call", profile.getName());
					forget.accept(profile.getId());
				}
				throw new ActionException(profile.getName() + ": " + describe(e), e);
			}
		}
	}

	private ConnectionRegistry.Session connect(ConnectionProfile profile) throws ActionException {
		ConnectionRegistry.Session session;
		try {
			session = opener.open(profile);
		} catch (Exception e) {
			throw new ActionException("Cannot connect to " + profile.getName() + ": " + describe(e), e);
		}
		if (session == null) {
			throw new ActionException("Not connected to " + profile.getName() + ": it needs a password, none is stored "
					+ "and the password prompt was dismissed. Ask the user to enter it, or to connect once in the SQL "
					+ "panel with 'Remember password' ticked.");
		}
		return session;
	}

	private static boolean isValid(Connection connection) {
		try {
			return connection.isValid(2);
		} catch (SQLException e) {
			return false;
		}
	}

	/** Find a saved connection by id, or by name ignoring case. */
	ConnectionProfile profile(String reference) throws ActionException {
		List<ConnectionProfile> profiles = store.loadAll();
		for (ConnectionProfile profile : profiles) {
			if (reference.equals(profile.getId())) {
				return profile;
			}
		}
		List<ConnectionProfile> byName = profiles.stream()
				.filter(profile -> reference.equalsIgnoreCase(profile.getName()))
				.toList();
		if (byName.size() == 1) {
			return byName.get(0);
		}
		String names = profiles.stream().map(ConnectionProfile::getName).collect(Collectors.joining(", "));
		if (byName.isEmpty()) {
			throw new ActionException("No saved connection '" + reference + "'. Saved connections: "
					+ (names.isEmpty() ? "none - add one in the SQL panel first." : names + "."));
		}
		throw new ActionException("'" + reference + "' matches " + byName.size()
				+ " saved connections; use the id from sql.connections.list instead.");
	}

	/** The schema named, or the only one there is when none is named. */
	private static String schemaFor(Connection connection, String schema, ConnectionProfile profile,
			NuclrPluginCallback callback) throws ActionException {
		List<String> schemas = Schemas.names(connection, callback::isCancelled);
		if (schema != null) {
			return schemaNamed(schemas, schema, profile);
		}
		List<String> user = schemas.stream()
				.filter(name -> name == null || !SYSTEM_SCHEMAS.contains(name.toLowerCase(Locale.ROOT)))
				.toList();
		if (user.size() == 1) {
			return user.get(0);
		}
		throw new ActionException(profile.getName() + " has " + user.size() + " schemas (" + String.join(", ", user)
				+ "); pass 'schema' to say which one.");
	}

	private static String schemaNamed(List<String> schemas, String wanted, ConnectionProfile profile)
			throws ActionException {
		String match = exactOrOnlyIgnoringCase(schemas, wanted);
		if (match == null) {
			throw new ActionException("No schema '" + wanted + "' in " + profile.getName() + ". Schemas: "
					+ String.join(", ", schemas) + ".");
		}
		return match;
	}

	/** A table or view, as the database spells its name. */
	record TableInfo(String name, boolean view) {
	}

	private static TableInfo table(Connection connection, String schema, String wanted, ConnectionProfile profile)
			throws SQLException, ActionException {
		DatabaseMetaData meta = connection.getMetaData();
		var names = new ArrayList<String>();
		var views = new ArrayList<String>();
		try (ResultSet rs = meta.getTables(Schemas.catalog(connection), likeLiteral(meta, schema), "%",
				new String[] { "TABLE", "VIEW" })) {
			while (rs.next()) {
				String rowSchema = rs.getString("TABLE_SCHEM");
				if (rowSchema != null && !rowSchema.equals(schema)) {
					continue;
				}
				String name = rs.getString("TABLE_NAME");
				names.add(name);
				if ("VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE"))) {
					views.add(name);
				}
			}
		}
		String match = exactOrOnlyIgnoringCase(names, wanted);
		if (match == null) {
			throw new ActionException("No table or view '" + wanted + "' in schema " + schema + " of "
					+ profile.getName() + ". Use sql.tables.list to see what there is.");
		}
		return new TableInfo(match, views.contains(match));
	}

	private static List<String> columnNames(Connection connection, String schema, String table) throws SQLException {
		DatabaseMetaData meta = connection.getMetaData();
		var names = new ArrayList<String>();
		try (ResultSet rs = meta.getColumns(Schemas.catalog(connection), likeLiteral(meta, schema),
				likeLiteral(meta, table), "%")) {
			while (rs.next()) {
				if (sameTable(rs, schema, table)) {
					names.add(rs.getString("COLUMN_NAME"));
				}
			}
		}
		return names;
	}

	/** A column the table has, as the database spells it. */
	private static String column(List<String> known, String wanted, String argument, String table)
			throws ActionException {
		String match = exactOrOnlyIgnoringCase(known, wanted);
		if (match == null) {
			throw new ActionException("Argument '" + argument + "': " + table + " has no column '" + wanted
					+ "'. Its columns: " + String.join(", ", known) + ".");
		}
		return match;
	}

	/** Metadata rows are matched by pattern, so check they are really this table's. */
	private static boolean sameTable(ResultSet rs, String schema, String table) throws SQLException {
		String rowSchema = rs.getString("TABLE_SCHEM");
		return (rowSchema == null || rowSchema.equals(schema)) && table.equals(rs.getString("TABLE_NAME"));
	}

	private static String exactOrOnlyIgnoringCase(List<String> names, String wanted) {
		if (names.contains(wanted)) {
			return wanted;
		}
		List<String> matches = names.stream().filter(name -> name != null && name.equalsIgnoreCase(wanted)).toList();
		return matches.size() == 1 ? matches.get(0) : null;
	}

	/** A name as a metadata pattern that matches only itself: {@code _} and {@code %} escaped. */
	private static String likeLiteral(DatabaseMetaData meta, String name) throws SQLException {
		String escape = meta.getSearchStringEscape();
		if (name == null || escape == null || escape.isEmpty()) {
			return name;
		}
		return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
	}

	private static String quote(ConnectionRegistry.Session session, String identifier) {
		String q = session.quoteString();
		return q + identifier.replace(q, q + q) + q;
	}

	private static String qualified(ConnectionRegistry.Session session, String schema, String table) {
		return quote(session, schema) + "." + quote(session, table);
	}

	// =========================================================================
	// Shared helpers
	// =========================================================================

	/**
	 * Run statement work that can be stopped: while it runs, a Cancel from the caller
	 * cancels the statement on the database.
	 */
	private static <T> T cancellable(Statement statement, NuclrPluginCallback callback, String cancelledMessage,
			SqlWork<T> work) throws SQLException, ActionException {
		var done = new AtomicBoolean();
		var cancelled = new AtomicBoolean();
		Thread watcher = Thread.ofVirtual().name("sql-action-cancel").start(() -> {
			while (!done.get()) {
				if (callback.isCancelled()) {
					cancelled.set(true);
					try {
						statement.cancel();
					} catch (SQLException e) {
						log.debug("Could not cancel the statement: {}", e.getMessage());
					}
					return;
				}
				try {
					Thread.sleep(POLL_MILLIS);
				} catch (InterruptedException e) {
					return;
				}
			}
		});
		try {
			return work.run();
		} catch (SQLException | ActionException e) {
			if (cancelled.get() || callback.isCancelled()) {
				throw new ActionException(cancelledMessage, e);
			}
			throw e;
		} finally {
			done.set(true);
			watcher.interrupt();
		}
	}

	private static void checkCancelled(NuclrPluginCallback callback) throws ActionException {
		checkCancelled(callback, "Cancelled.");
	}

	private static void checkCancelled(NuclrPluginCallback callback, String message) throws ActionException {
		if (callback.isCancelled()) {
			throw new ActionException(message);
		}
	}

	private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	/** A metadata column some drivers leave out. */
	private static String optionalString(ResultSet rs, String column) {
		try {
			return rs.getString(column);
		} catch (SQLException e) {
			return null;
		}
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text;
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}

	static String text(Map<String, Object> args, String name, boolean required) throws ActionException {
		Object value = args.get(name);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		if (value != null && !(value instanceof String)) {
			throw new ActionException("Argument '" + name + "' must be a string.");
		}
		if (required) {
			throw new ActionException("Missing argument '" + name + "'.");
		}
		return null;
	}

	static int integer(Map<String, Object> args, String name, int defaultValue, int min, int max)
			throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			return defaultValue;
		}
		if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
			throw new ActionException("Argument '" + name + "' must be a whole number.");
		}
		long whole = number.longValue();
		if (whole < min || whole > max) {
			throw new ActionException("Argument '" + name + "' must be between " + min + " and " + max + ".");
		}
		return (int) whole;
	}

	static boolean flag(Map<String, Object> args, String name, boolean defaultValue) throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			return defaultValue;
		}
		if (!(value instanceof Boolean bool)) {
			throw new ActionException("Argument '" + name + "' must be true or false.");
		}
		return bool;
	}

	static List<String> strings(Map<String, Object> args, String name) throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String s) || s.isBlank())) {
			throw new ActionException("Argument '" + name + "' must be a list of names.");
		}
		return list.stream().map(String.class::cast).toList();
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> object(Map<String, Object> args, String name) throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			return Map.of();
		}
		if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
			throw new ActionException("Argument '" + name + "' must be an object.");
		}
		return (Map<String, Object>) map;
	}

	/** A failure whose message is meant for the caller as it stands. */
	static final class ActionException extends Exception {

		private static final long serialVersionUID = 1L;

		ActionException(String message) {
			super(message);
		}

		ActionException(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
