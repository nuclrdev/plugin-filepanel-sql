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

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;

/**
 * Owns the driver a single connection was loaded through, and connects with it
 * directly ({@link Driver#connect}) rather than via {@link java.sql.DriverManager}.
 *
 * <p>The plugin bundle already gets its own isolated {@code URLClassLoader} from the
 * host (confirmed against {@code PluginRegistry.prepareBundle}), but there is no host
 * API to add a jar to it after load ({@code URLClassLoader.addURL} is protected and
 * never exposed). A downloaded or user-supplied driver jar therefore needs its own
 * child {@code URLClassLoader}, parented to this plugin's own classloader — and once a
 * driver class comes from a classloader other than the caller's, {@code DriverManager}
 * will not see it without extra shim registration. Instantiating the {@link Driver}
 * directly and calling {@link Driver#connect} sidesteps that entirely; this is the
 * standard pattern for a driver loaded outside the application classloader.
 */
@Slf4j
public final class DriverClassLoader implements AutoCloseable {

	private final URLClassLoader ownClassLoader;

	private final Driver driver;

	private DriverClassLoader(URLClassLoader ownClassLoader, Driver driver) {
		this.ownClassLoader = ownClassLoader;
		this.driver = driver;
	}

	/** For a downloaded or user-supplied driver jar, isolated in its own child classloader. */
	public static DriverClassLoader forJar(Path jarPath, String driverClassName)
			throws ReflectiveOperationException, IOException {
		URL[] urls = { jarPath.toUri().toURL() };
		URLClassLoader child = new URLClassLoader(urls, DriverClassLoader.class.getClassLoader());
		try {
			Driver driver = instantiate(driverClassName, child);
			return new DriverClassLoader(child, driver);
		} catch (ReflectiveOperationException | RuntimeException e) {
			child.close();
			throw e;
		}
	}

	private static Driver instantiate(String className, ClassLoader loader) throws ReflectiveOperationException {
		Class<?> driverClass = Class.forName(className, true, loader);
		return (Driver) driverClass.getDeclaredConstructor().newInstance();
	}

	public Connection connect(String jdbcUrl, Properties props) throws SQLException {
		Connection connection = driver.connect(jdbcUrl, props);
		if (connection == null) {
			throw new SQLException("Driver " + driver.getClass().getName() + " did not accept URL: " + jdbcUrl);
		}
		return connection;
	}

	@Override
	public void close() {
		try {
			ownClassLoader.close();
		} catch (IOException e) {
			log.debug("Failed to close driver classloader: {}", e.getMessage());
		}
	}
}
