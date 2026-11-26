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

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps a JDBC URL's secrets ({@link JdbcUrlSecrets}) in the keychain under
 * {@link CredentialStore#urlKey} instead of in {@code settings.json}, and in step with the
 * URL the user last saved: the editor is shown the URL with them merged back, and every
 * save splits it again, replacing or clearing what the keychain holds. So a password taken
 * out of the URL is gone, not merged back on the next connect.
 */
@Slf4j
public final class UrlSecretKeeper {

	private final ConnectionProfileStore store;
	private final CredentialStore credentials;

	public UrlSecretKeeper(ConnectionProfileStore store, CredentialStore credentials) {
		this.store = store;
		this.credentials = credentials;
	}

	/** {@code profile} with its URL secrets merged back: for connecting, and for the editor. */
	public ConnectionProfile withSecrets(ConnectionProfile profile) {
		String secrets = credentials.loadPassword(CredentialStore.urlKey(profile.getId()));
		if (secrets == null) {
			return profile;
		}
		return profile.toBuilder().jdbcUrl(JdbcUrlSecrets.merge(profile.getJdbcUrl(), secrets)).build();
	}

	/**
	 * After {@code saved} was stored with the URL as typed: move any secrets in it to the
	 * keychain and store the URL without them; with none, clear the keychain entry. When the
	 * keychain cannot take them the URL stays as typed, as before this existed, so nothing
	 * is lost.
	 */
	public void keep(ConnectionProfile saved) {
		String key = CredentialStore.urlKey(saved.getId());
		JdbcUrlSecrets.Split split = JdbcUrlSecrets.split(saved.getJdbcUrl());
		if (!split.hasSecrets()) {
			credentials.deletePassword(key);
			return;
		}
		String failure = credentials.savePassword(key, split.secrets().toCharArray());
		if (failure != null) {
			log.warn("Keeping the secrets of connection {} in its URL: the keychain did not take them", saved.getId());
			return;
		}
		saved.setJdbcUrl(split.url());
		store.update(saved);
	}

	/** A duplicate gets the original's URL secrets too. */
	public void copy(String fromId, String toId) {
		String secrets = credentials.loadPassword(CredentialStore.urlKey(fromId));
		if (secrets != null) {
			credentials.savePassword(CredentialStore.urlKey(toId), secrets.toCharArray());
		}
	}

	public void delete(String id) {
		credentials.deletePassword(CredentialStore.urlKey(id));
	}
}
