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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.nuclr.platform.NuclrCredentialException.Reason;
import dev.nuclr.plugin.core.panel.sql.support.FakeCredentialStore;

class CredentialStoreTest {

	private FakeCredentialStore backing;
	private CredentialStore store;

	@BeforeEach
	void setUp() {
		backing = new FakeCredentialStore();
		store = new CredentialStore(backing);
	}

	@Test
	void savesAndReadsBackAPassword() {
		assertNull(store.savePassword("profile-1", "s3cret".toCharArray()));

		assertEquals("s3cret", store.loadPassword("profile-1"));
		assertTrue(store.hasPassword("profile-1"));
	}

	@Test
	void anAbsentPasswordReadsAsNullRatherThanFailing() {
		assertNull(store.loadPassword("profile-1"));
		assertFalse(store.hasPassword("profile-1"));
	}

	@Test
	void passwordsAreScopedPerProfile() {
		store.savePassword("profile-1", "one".toCharArray());
		store.savePassword("profile-2", "two".toCharArray());

		assertEquals("one", store.loadPassword("profile-1"));
		assertEquals("two", store.loadPassword("profile-2"));
	}

	@Test
	void deleteRemovesTheEntryAndIsSafeToRepeat() {
		store.savePassword("profile-1", "s3cret".toCharArray());

		store.deletePassword("profile-1");
		store.deletePassword("profile-1");

		assertNull(store.loadPassword("profile-1"));
	}

	@Test
	void aBlankPasswordClearsTheEntryInsteadOfBeingRejectedByTheHost() {
		store.savePassword("profile-1", "s3cret".toCharArray());

		assertNull(store.savePassword("profile-1", "".toCharArray()));
		assertNull(store.savePassword("profile-1", null));

		assertNull(store.loadPassword("profile-1"));
		assertFalse(backing.secrets.containsKey("profile-1"));
	}

	@Test
	void unavailableStorageReadsAsNoStoredPassword() {
		backing.failWith(Reason.UNAVAILABLE, "OS credential storage is unavailable.");

		assertNull(store.loadPassword("profile-1"));
		assertFalse(store.hasPassword("profile-1"));
	}

	@Test
	void unavailableStorageReportsWhyAPasswordCouldNotBeSaved() {
		backing.failWith(Reason.UNAVAILABLE, "OS credential storage is unavailable.");

		String reason = store.savePassword("profile-1", "s3cret".toCharArray());

		assertNotNull(reason);
		assertTrue(reason.contains("unavailable"), reason);
	}

	@Test
	void aFailingStoreDoesNotLetADeleteEscape() {
		backing.failWith(Reason.ACCESS_FAILED, "Unable to access OS credential storage.");

		store.deletePassword("profile-1");
	}

	@Test
	void aKeyTheHostWouldRejectNeverReachesIt() {
		assertNull(store.loadPassword(null));
		assertNull(store.loadPassword("  "));
		assertNull(store.loadPassword("has|pipe"));
		assertNotNull(store.savePassword("has|pipe", "s3cret".toCharArray()));
		assertTrue(backing.secrets.isEmpty());

		store.deletePassword("has|pipe");
	}
}
