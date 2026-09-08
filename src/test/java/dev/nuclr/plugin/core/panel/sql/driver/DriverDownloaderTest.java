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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.sql.PluginIds;

class DriverDownloaderTest {

	@Test
	void refusesToResolveADriverWithNoMavenCoordinates() {
		var e = assertThrows(IllegalArgumentException.class, () -> DriverDownloader.resolve(DriverCatalog.CUSTOM, null));
		assertTrue(e.getMessage().contains(DriverCatalog.CUSTOM.label()));
	}

	@Test
	void cachesUnderThisPluginsOwnDataDirectory() {
		var cacheDir = DriverDownloader.cacheDir();

		assertEquals("drivers", cacheDir.getFileName().toString());
		assertEquals(PluginIds.PLUGIN_ID, cacheDir.getParent().getFileName().toString());
		assertEquals("plugins-data", cacheDir.getParent().getParent().getFileName().toString());
		assertEquals(".nuclr", cacheDir.getParent().getParent().getParent().getFileName().toString());
		assertTrue(cacheDir.startsWith(System.getProperty("user.home")));
	}
}
