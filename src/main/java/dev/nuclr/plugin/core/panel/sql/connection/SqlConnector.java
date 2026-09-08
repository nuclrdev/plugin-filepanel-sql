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

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Properties;

import dev.nuclr.plugin.core.panel.sql.driver.DriverCatalog;
import dev.nuclr.plugin.core.panel.sql.driver.DriverClassLoader;
import dev.nuclr.plugin.core.panel.sql.driver.DriverDownloader;

/**
 * Resolves a {@link ConnectionProfile}'s driver and opens one connection with it.
 * Shared by {@link ConnectionRegistry} (the panel's live, registered connections) and
 * {@code ConnectionEditorDialog}'s "Test Connection" (a throwaway connect-and-close,
 * never registered).
 */
public final class SqlConnector {

	public record Opened(Connection connection, DriverClassLoader driverLoader) {

		public void close() {
			try {
				connection.close();
			} catch (Exception ignored) {
				// best-effort
			}
			driverLoader.close();
		}
	}

	private SqlConnector() {
	}

	public static Opened connect(ConnectionProfile profile, String password) throws Exception {
		DriverClassLoader driverLoader = loadDriver(profile);

		Properties props = new Properties();
		if (profile.getUsername() != null && !profile.getUsername().isBlank()) {
			props.setProperty("user", profile.getUsername());
		}
		if (password != null) {
			props.setProperty("password", password);
		}
		if (profile.isUseSsl()) {
			// Each driver has its own spelling. Sending Postgres's sslmode to SQL Server,
			// as this once did for every driver alike, is ignored at best and rejected as
			// an unknown property at worst.
			DriverCatalog.byKey(profile.getDriverKey()).sslProperties().forEach(props::setProperty);
		}

		try {
			Connection connection = driverLoader.connect(profile.getJdbcUrl(), props);
			return new Opened(connection, driverLoader);
		} catch (Exception e) {
			driverLoader.close();
			throw e;
		}
	}

	/**
	 * Every driver is loaded from a jar in its own child classloader: a built-in choice
	 * from the cached Maven Central download, a custom one from the path the user gave.
	 * This plugin bundles no driver of its own, so there is no shortcut past this.
	 */
	private static DriverClassLoader loadDriver(ConnectionProfile profile) throws Exception {
		String driverKey = profile.getDriverKey();
		if ("custom".equals(driverKey)) {
			String jarPath = profile.getCustomDriverJarPath();
			if (jarPath == null || jarPath.isBlank()) {
				throw new IllegalArgumentException(
						"This connection uses a custom driver but no driver jar was chosen for it.");
			}
			return DriverClassLoader.forJar(Path.of(jarPath), profile.getCustomDriverClassName());
		}
		DriverCatalog.Entry entry = DriverCatalog.byKey(driverKey);
		if (entry == DriverCatalog.CUSTOM) {
			// byKey falls back to CUSTOM for a key it does not know, which for a profile
			// saved against a since-retired driver would otherwise surface as the
			// downloader complaining about missing Maven coordinates.
			throw new IllegalArgumentException("This connection uses a driver this version no longer offers ("
					+ driverKey + "). Edit the connection and pick a driver, or supply the driver jar yourself.");
		}
		Path jar = DriverDownloader.resolve(entry, null);
		return DriverClassLoader.forJar(jar, entry.driverClassName());
	}
}
