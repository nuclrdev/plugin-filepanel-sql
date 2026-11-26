package dev.nuclr.plugin.core.panel.sql.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.NuclrCredentialException.Reason;
import dev.nuclr.plugin.core.panel.sql.support.FakeCredentialStore;
import dev.nuclr.plugin.core.panel.sql.support.FakeSettings;

class UrlSecretKeeperTest {

	private final FakeCredentialStore keychain = new FakeCredentialStore();
	private final ConnectionProfileStore store = new ConnectionProfileStore(new FakeSettings());
	private final UrlSecretKeeper keeper = new UrlSecretKeeper(store, new CredentialStore(keychain));

	private ConnectionProfile saved(String url) {
		return store.add(ConnectionProfile.builder().name("shop").jdbcUrl(url).build());
	}

	@Test
	void savingMovesTheUrlsSecretsToTheKeychain() {
		var profile = saved("jdbc:postgresql://db/shop?password=hunter2");

		keeper.keep(profile);

		assertEquals("jdbc:postgresql://db/shop", store.findById(profile.getId()).getJdbcUrl());
		assertTrue(keychain.secrets.get(profile.getId() + ":url").contains("hunter2"));
		assertEquals("jdbc:postgresql://db/shop?password=hunter2",
				keeper.withSecrets(store.findById(profile.getId())).getJdbcUrl());
	}

	@Test
	void savingAUrlWithoutThePasswordClearsTheKeychainEntry() {
		var profile = saved("jdbc:postgresql://db/shop?password=hunter2");
		keeper.keep(profile);

		var edited = keeper.withSecrets(store.findById(profile.getId()));
		edited.setJdbcUrl("jdbc:postgresql://db/shop");
		store.update(edited);
		keeper.keep(edited);

		assertFalse(keychain.secrets.containsKey(profile.getId() + ":url"));
		assertEquals("jdbc:postgresql://db/shop", keeper.withSecrets(store.findById(profile.getId())).getJdbcUrl());
	}

	@Test
	void changingThePasswordReplacesIt() {
		var profile = saved("jdbc:postgresql://db/shop?password=old");
		keeper.keep(profile);

		var edited = keeper.withSecrets(store.findById(profile.getId()));
		edited.setJdbcUrl("jdbc:postgresql://db/shop?password=new");
		store.update(edited);
		keeper.keep(edited);

		assertEquals("jdbc:postgresql://db/shop?password=new",
				keeper.withSecrets(store.findById(profile.getId())).getJdbcUrl());
	}

	@Test
	void withoutAKeychainTheUrlStaysAsTypedSoNothingIsLost() {
		var profile = saved("jdbc:postgresql://db/shop?password=hunter2");
		keychain.failWith(Reason.UNAVAILABLE, "no keyring");

		keeper.keep(profile);

		assertEquals("jdbc:postgresql://db/shop?password=hunter2", store.findById(profile.getId()).getJdbcUrl());
	}

	@Test
	void aDuplicateGetsTheSecretsAndDeleteRemovesThem() {
		var profile = saved("jdbc:postgresql://db/shop?password=hunter2");
		keeper.keep(profile);

		keeper.copy(profile.getId(), "copy");
		assertEquals(keychain.secrets.get(profile.getId() + ":url"), keychain.secrets.get("copy:url"));
		keeper.delete(profile.getId());
		assertFalse(keychain.secrets.containsKey(profile.getId() + ":url"));
	}
}
