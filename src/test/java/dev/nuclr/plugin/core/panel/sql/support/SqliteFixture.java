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
package dev.nuclr.plugin.core.panel.sql.support;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import dev.nuclr.plugin.core.panel.sql.connection.ConnectionProfile;
import dev.nuclr.plugin.core.panel.sql.driver.DriverClassLoader;

/**
 * A real SQLite database on disk, so the navigation and export tests exercise actual JDBC
 * metadata rather than a mock of it.
 *
 * <p>SQLite is a <em>test-only</em> dependency and no longer a driver this plugin offers:
 * nothing SQLite ships in the bundle. The profiles built here therefore describe it the
 * way a user describes any driver of their own — {@code driverKey = "custom"} plus a jar
 * path — which means these tests run the same {@link DriverClassLoader#forJar} path a
 * real custom driver takes, child classloader and all.
 *
 * <p>One table with a primary key, one without (the offset-keyed row path), and one
 * view. The {@code users} rows deliberately include a NULL and a value carrying a comma,
 * a quote and a newline, which is what the CSV escaping has to survive.
 */
public final class SqliteFixture {

	public static final String AwkwardName = "O'Hara, \"Bob\"\nJr";

	private SqliteFixture() {
	}

	/** Create the fixture database at {@code dbFile} and return its JDBC URL. */
	public static String create(Path dbFile) throws SQLException {
		String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
		try (Connection connection = DriverManager.getConnection(url);
				Statement st = connection.createStatement()) {
			st.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, email TEXT)");
			st.executeUpdate("INSERT INTO users (id, name, email) VALUES (1, 'Ada', 'ada@example.com')");
			st.executeUpdate("INSERT INTO users (id, name, email) VALUES (2, 'Grace', NULL)");
			try (var ps = connection.prepareStatement("INSERT INTO users (id, name, email) VALUES (3, ?, ?)")) {
				ps.setString(1, AwkwardName);
				ps.setString(2, "bob@example.com");
				ps.executeUpdate();
			}
			st.executeUpdate("CREATE TABLE no_pk (a TEXT, b INTEGER)");
			st.executeUpdate("INSERT INTO no_pk (a, b) VALUES ('x', 1)");
			st.executeUpdate("INSERT INTO no_pk (a, b) VALUES ('y', 2)");
			st.executeUpdate("CREATE VIEW active_users AS SELECT id, name FROM users WHERE email IS NOT NULL");
		}
		return url;
	}

	/** The driver class name a custom profile built here points at. */
	public static final String DriverClassName = "org.sqlite.JDBC";

	/**
	 * The sqlite-jdbc jar on the test classpath — what a custom-driver profile loads from,
	 * exactly as a user-chosen jar would be.
	 */
	public static Path driverJar() {
		try {
			var location = Class.forName(DriverClassName).getProtectionDomain().getCodeSource().getLocation();
			return Path.of(location.toURI());
		} catch (Exception e) {
			throw new IllegalStateException("sqlite-jdbc is not on the test classpath as a jar", e);
		}
	}

	/** A saved profile pointing at {@code dbFile}, with no username, so nothing prompts. */
	public static ConnectionProfile profile(String name, Path dbFile) throws SQLException {
		return customProfile(name).jdbcUrl(create(dbFile)).build();
	}

	/** A half-built custom-driver profile, for tests that supply their own URL. */
	public static ConnectionProfile.ConnectionProfileBuilder customProfile(String name) {
		return ConnectionProfile.builder()
				.name(name)
				.driverKey("custom")
				.customDriverClassName(DriverClassName)
				.customDriverJarPath(driverJar().toString());
	}
}
