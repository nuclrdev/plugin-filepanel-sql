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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConnectionProfileTest {

	@Test
	void driverLabelComesFromTheCatalogForABuiltInDriver() {
		assertEquals("PostgreSQL", ConnectionProfile.builder().driverKey("postgresql").build().getDriverLabel());
		assertEquals("SQL Server", ConnectionProfile.builder().driverKey("sqlserver").build().getDriverLabel());
	}

	@Test
	void aDriverThatIsNoLongerOfferedFallsBackToTheCustomLabel() {
		// A profile saved when SQLite was still a built-in choice: its key no longer
		// resolves, so it reads as a custom driver rather than crashing the listing.
		var retired = ConnectionProfile.builder().driverKey("sqlite").build();

		assertEquals("sqlite (unavailable)", retired.getDriverLabel());
	}

	@Test
	void driverLabelForACustomDriverIsItsClassName() {
		var profile = ConnectionProfile.builder().driverKey("custom").customDriverClassName("com.acme.Driver").build();

		assertEquals("com.acme.Driver", profile.getDriverLabel());
	}

	@Test
	void driverLabelForACustomDriverWithNoClassNameStaysReadable() {
		assertEquals("Custom driver", ConnectionProfile.builder().driverKey("custom").build().getDriverLabel());
		assertEquals("Custom driver",
				ConnectionProfile.builder().driverKey("custom").customDriverClassName("  ").build().getDriverLabel());
	}

	@Test
	void displayTargetIsTheUrlAndNeverNull() {
		assertEquals("jdbc:postgresql://db.example.com:5432/shop", ConnectionProfile.builder()
				.jdbcUrl("jdbc:postgresql://db.example.com:5432/shop").build().getDisplayTarget());
		assertEquals("", ConnectionProfile.builder().build().getDisplayTarget());
	}

	@Test
	void onlyAProfileCarryingALoginIsWorthAskingAPasswordFor() {
		assertTrue(ConnectionProfile.builder().username("app").build().needsPassword());
		assertFalse(ConnectionProfile.builder().build().needsPassword());
		assertFalse(ConnectionProfile.builder().username("   ").build().needsPassword());
	}
}
