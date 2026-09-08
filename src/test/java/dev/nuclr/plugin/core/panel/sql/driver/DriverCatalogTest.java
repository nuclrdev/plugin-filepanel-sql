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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DriverCatalogTest {

	static Stream<DriverCatalog.Entry> builtInDrivers() {
		return DriverCatalog.all().stream().filter(e -> e != DriverCatalog.CUSTOM);
	}

	@Test
	void resolvesEveryBuiltInKey() {
		assertSame(DriverCatalog.POSTGRESQL, DriverCatalog.byKey("postgresql"));
		assertSame(DriverCatalog.MYSQL, DriverCatalog.byKey("mysql"));
		assertSame(DriverCatalog.SQLSERVER, DriverCatalog.byKey("sqlserver"));
		assertSame(DriverCatalog.CUSTOM, DriverCatalog.byKey("custom"));
	}

	@Test
	void offersTheServerDriversAndACustomEscapeHatchAndNothingElse() {
		assertEquals(List.of("postgresql", "mysql", "sqlserver", "custom"),
				DriverCatalog.all().stream().map(DriverCatalog.Entry::key).toList());
	}

	@Test
	void fallsBackToCustomForAnUnknownOrMissingKey() {
		assertSame(DriverCatalog.CUSTOM, DriverCatalog.byKey("oracle"));
		assertSame(DriverCatalog.CUSTOM, DriverCatalog.byKey(null));
		// A profile saved while SQLite was still offered resolves here rather than blowing up.
		assertSame(DriverCatalog.CUSTOM, DriverCatalog.byKey("sqlite"));
	}

	@ParameterizedTest
	@MethodSource("builtInDrivers")
	void noDriverIsBundledSoEveryBuiltInOneCarriesCoordinatesToFetch(DriverCatalog.Entry entry) {
		assertNotNull(entry.groupId(), entry.key());
		assertNotNull(entry.artifactId(), entry.key());
		assertNotNull(entry.defaultVersion(), entry.key());
		assertNotNull(entry.driverClassName(), entry.key());
	}

	@ParameterizedTest
	@MethodSource("builtInDrivers")
	void everyBuiltInDriverIsAServerConnectionDescribedByHostPortDatabase(DriverCatalog.Entry entry) {
		assertTrue(entry.defaultPort() > 0, entry.key() + " should have a conventional port");

		String url = String.format(entry.urlTemplate(), "db.example.com", entry.defaultPort(), "shop");

		assertTrue(url.startsWith("jdbc:"), entry.key() + " -> " + url);
		assertTrue(url.contains("db.example.com") && url.contains("shop"), entry.key() + " -> " + url);
	}

	@Test
	void postgresUrlTemplateTakesHostPortDatabase() {
		assertEquals("jdbc:postgresql://db.example.com:5432/shop",
				String.format(DriverCatalog.POSTGRESQL.urlTemplate(), "db.example.com", 5432, "shop"));
	}

	@Test
	void mysqlUrlTemplateTakesHostPortDatabase() {
		assertEquals("jdbc:mysql://db.example.com:3306/shop",
				String.format(DriverCatalog.MYSQL.urlTemplate(), "db.example.com", 3306, "shop"));
	}

	@Test
	void sqlServerUrlTemplateUsesItsOwnSeparatorSyntax() {
		assertEquals("jdbc:sqlserver://db.example.com:1433;databaseName=shop",
				String.format(DriverCatalog.SQLSERVER.urlTemplate(), "db.example.com", 1433, "shop"));
	}

	@Test
	void customEntryHasNoCoordinatesAndNoUrlToBuild() {
		assertNull(DriverCatalog.CUSTOM.groupId());
		assertNull(DriverCatalog.CUSTOM.artifactId());
		assertNull(DriverCatalog.CUSTOM.driverClassName());
		assertNull(DriverCatalog.CUSTOM.urlTemplate());
		assertEquals(0, DriverCatalog.CUSTOM.defaultPort());
	}

	@Test
	void eachDriverAsksForEncryptionInItsOwnSpelling() {
		// Handing one driver another's property is ignored at best and rejected at worst,
		// which is what sending Postgres's sslmode to every driver alike used to do.
		assertEquals("require", DriverCatalog.POSTGRESQL.sslProperties().get("sslmode"));
		assertEquals("REQUIRED", DriverCatalog.MYSQL.sslProperties().get("sslMode"));
		assertEquals("true", DriverCatalog.SQLSERVER.sslProperties().get("encrypt"));

		assertNull(DriverCatalog.SQLSERVER.sslProperties().get("sslmode"));
		assertNull(DriverCatalog.MYSQL.sslProperties().get("sslmode"));
	}

	@Test
	void nothingIsGuessedForACustomDriver() {
		// Its encryption settings belong in the JDBC URL the user wrote.
		assertTrue(DriverCatalog.CUSTOM.sslProperties().isEmpty());
	}

	@ParameterizedTest
	@MethodSource("builtInDrivers")
	void everyBuiltInDriverKnowsHowToAskForEncryption(DriverCatalog.Entry entry) {
		assertFalse(entry.sslProperties().isEmpty(), entry.key());
	}

	@Test
	void catalogIsImmutable() {
		assertThrows(UnsupportedOperationException.class, () -> DriverCatalog.all().add(DriverCatalog.CUSTOM));
	}
}
