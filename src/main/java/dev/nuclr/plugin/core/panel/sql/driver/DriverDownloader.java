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
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import dev.nuclr.plugin.core.panel.sql.PluginIds;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches a JDBC driver jar from Maven Central on first use and caches it under
 * {@code ~/.nuclr/plugins-data/<pluginId>/drivers/}.
 *
 * <p>Deliberately does <em>not</em> reuse {@code commander}'s internal
 * {@code LocalDataLocation}: that class lives in the {@code commander} module, which
 * plugins do not (and should not) compile against — every plugin in this repository
 * depends only on {@code platform-sdk}. This follows the same {@code .nuclr} home-dir
 * convention under a plugin-owned subtree instead.
 */
@Slf4j
public final class DriverDownloader {

	private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

	private DriverDownloader() {
	}

	/** The plugin-owned cache directory driver jars are downloaded into. */
	public static Path cacheDir() {
		String home = System.getProperty("user.home");
		return Path.of(home, ".nuclr", "plugins-data", PluginIds.PLUGIN_ID, "drivers");
	}

	/**
	 * Return the cached jar for {@code entry}/{@code version}, downloading it first if
	 * it is not already cached. {@code version} may be {@code null} to use
	 * {@link DriverCatalog.Entry#defaultVersion()}.
	 */
	public static Path resolve(DriverCatalog.Entry entry, String version) throws IOException {
		if (entry.artifactId() == null || entry.groupId() == null) {
			throw new IllegalArgumentException("No Maven coordinates for driver " + entry.label());
		}
		String effectiveVersion = version == null || version.isBlank() ? entry.defaultVersion() : version;

		Path cacheDir = cacheDir();
		Files.createDirectories(cacheDir);
		Path jar = cacheDir.resolve(entry.artifactId() + "-" + effectiveVersion + ".jar");
		if (Files.exists(jar) && Files.size(jar) > 0) {
			return jar;
		}

		String groupPath = entry.groupId().replace('.', '/');
		String url = MAVEN_CENTRAL + groupPath + "/" + entry.artifactId() + "/" + effectiveVersion + "/"
				+ entry.artifactId() + "-" + effectiveVersion + ".jar";

		log.info("Downloading JDBC driver {} {} from {}", entry.label(), effectiveVersion, url);
		Path temp = Files.createTempFile(cacheDir, entry.artifactId() + "-", ".jar.part");
		try {
			try (InputStream in = URI.create(url).toURL().openStream()) {
				Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!looksLikeJar(temp)) {
				throw new IOException("Downloaded file for " + entry.label() + " does not look like a jar: " + url);
			}
			Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return jar;
		} catch (IOException e) {
			Files.deleteIfExists(temp);
			throw new IOException("Failed to download the " + entry.label() + " driver: " + e.getMessage(), e);
		}
	}

	private static boolean looksLikeJar(Path file) throws IOException {
		byte[] header = new byte[2];
		try (InputStream in = Files.newInputStream(file)) {
			if (in.readNBytes(header, 0, 2) < 2) {
				return false;
			}
		}
		// ZIP/jar local-file-header magic starts with 'P' 'K'.
		return header[0] == 'P' && header[1] == 'K';
	}
}
