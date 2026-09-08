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
package dev.nuclr.plugin.core.panel.sql.driver;

import java.util.List;
import java.util.Map;

/**
 * Built-in JDBC driver choices offered by the connection editor. No driver ships with
 * this plugin: every entry except {@link #CUSTOM} is fetched from Maven Central on first
 * use and cached — see {@link DriverDownloader} — and {@link #CUSTOM} is loaded from a
 * jar the user points at.
 */
public final class DriverCatalog {

	/**
	 * @param key              stable identifier stored on {@code ConnectionProfile}
	 * @param label            shown in the driver picker
	 * @param driverClassName  fully qualified {@code java.sql.Driver} implementation
	 * @param groupId          Maven groupId, or {@code null} for {@link #CUSTOM}
	 * @param artifactId       Maven artifactId, or {@code null} for {@link #CUSTOM}
	 * @param defaultVersion   version resolved when the profile does not override it
	 * @param urlTemplate      {@link String#format} template taking {@code host, port,
	 *                         database}, or {@code null} for {@link #CUSTOM}
	 * @param defaultPort      conventional port, or {@code 0} when not applicable
	 * @param sslProperties    connection properties that ask this driver for an encrypted
	 *                         link. Every driver spells this differently, and handing one
	 *                         another's property is at best ignored and at worst rejected.
	 */
	public record Entry(
			String key,
			String label,
			String driverClassName,
			String groupId,
			String artifactId,
			String defaultVersion,
			String urlTemplate,
			int defaultPort,
			Map<String, String> sslProperties) {
	}

	public static final Entry POSTGRESQL = new Entry(
			"postgresql", "PostgreSQL", "org.postgresql.Driver",
			"org.postgresql", "postgresql", "42.7.4",
			"jdbc:postgresql://%s:%d/%s", 5432,
			Map.of("ssl", "true", "sslmode", "require"));

	public static final Entry MYSQL = new Entry(
			"mysql", "MySQL / MariaDB", "com.mysql.cj.jdbc.Driver",
			"com.mysql", "mysql-connector-j", "9.1.0",
			"jdbc:mysql://%s:%d/%s", 3306,
			// Connector/J 8 and later read sslMode; useSSL is the pre-8 spelling, and is
			// still accepted, so sending both covers either vintage of the driver.
			Map.of("sslMode", "REQUIRED", "useSSL", "true"));

	public static final Entry SQLSERVER = new Entry(
			"sqlserver", "SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
			"com.microsoft.sqlserver", "mssql-jdbc", "12.8.1.jre11",
			"jdbc:sqlserver://%s:%d;databaseName=%s", 1433,
			Map.of("encrypt", "true"));

	/**
	 * User supplies both the driver class name and a local jar path. Nothing is known
	 * about how it spells SSL, so nothing is sent — a custom driver takes its encryption
	 * settings from the JDBC URL the user wrote.
	 */
	public static final Entry CUSTOM = new Entry(
			"custom", "Custom JDBC driver…", null, null, null, null, null, 0, Map.of());

	private static final List<Entry> ALL = List.of(POSTGRESQL, MYSQL, SQLSERVER, CUSTOM);

	public static List<Entry> all() {
		return ALL;
	}

	public static Entry byKey(String key) {
		return ALL.stream().filter(e -> e.key().equals(key)).findFirst().orElse(CUSTOM);
	}

	private DriverCatalog() {
	}
}
