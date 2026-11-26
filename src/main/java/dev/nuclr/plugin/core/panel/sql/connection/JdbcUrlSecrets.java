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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes the secrets out of a JDBC URL and puts them back. A URL typed by hand can carry a
 * password in three shapes: {@code //user:password@host}, a parameter such as
 * {@code ?password=...} or {@code ;pwd=...}, and Oracle's {@code thin:user/password@}. The
 * saved {@link ConnectionProfile#getJdbcUrl()} keeps the URL without them; what was taken out
 * lives in the keychain under {@link CredentialStore#urlKey} and is merged back on connect.
 *
 * <p>The removed parts are kept as one string, a line per part ({@code kind TAB text}), so
 * {@link #merge} can put each back where it belongs. URLs have no line breaks or tabs to
 * clash with.
 */
public final class JdbcUrlSecrets {

	/** A URL without its secrets, and the secrets ({@code null} when there were none). */
	public static final class Split {

		private final String url;
		private final String secrets;

		Split(String url, String secrets) {
			this.url = url;
			this.secrets = secrets;
		}

		public String url() {
			return url;
		}

		public String secrets() {
			return secrets;
		}

		public boolean hasSecrets() {
			return secrets != null;
		}
	}

	private static final String SecretNames = "password|passwd|pwd|secret|token|accesstoken|access_token";

	/** {@code ?password=x}, {@code &pwd=x}, {@code ;password=x}: the delimiter, then name=value. */
	private static final Pattern Parameter = Pattern.compile("(?i)([?&;])\\s*((?:" + SecretNames + ")\\s*=[^;&]*)");

	/** The password of {@code //user:password@host}; an empty password is not a secret. */
	private static final Pattern UserInfo = Pattern.compile("(//[^/@:;?]*):([^@/]+)@");

	/** Oracle's {@code jdbc:oracle:thin:user/password@host}. */
	private static final Pattern OracleThin = Pattern.compile("(?i)(:thin:)([^/@:]+/[^@]*)@");

	private static final char Query = 'Q';
	private static final char Semicolon = 'S';
	private static final char Password = 'U';
	private static final char Oracle = 'O';

	private JdbcUrlSecrets() {
	}

	/** {@code url} without its secrets. A {@code null} URL splits into {@code null} and none. */
	public static Split split(String url) {
		if (url == null || url.isEmpty()) {
			return new Split(url, null);
		}
		var parts = new ArrayList<String>();
		String rest = url;

		Matcher oracle = OracleThin.matcher(rest);
		if (oracle.find()) {
			parts.add(Oracle + "\t" + oracle.group(2));
			rest = rest.substring(0, oracle.start()) + oracle.group(1) + "@" + rest.substring(oracle.end());
		}

		Matcher userInfo = UserInfo.matcher(rest);
		if (userInfo.find()) {
			parts.add(Password + "\t" + userInfo.group(2));
			rest = rest.substring(0, userInfo.start()) + userInfo.group(1) + "@" + rest.substring(userInfo.end());
		}

		while (true) {
			Matcher parameter = Parameter.matcher(rest);
			if (!parameter.find()) {
				break;
			}
			char delimiter = parameter.group(1).charAt(0);
			parts.add((delimiter == ';' ? Semicolon : Query) + "\t" + parameter.group(2).strip());
			String before = rest.substring(0, parameter.start());
			String after = rest.substring(parameter.end());
			// "?password=x&user=y" keeps its "?": the next parameter takes the removed one's place.
			if (delimiter == '?' && after.startsWith("&")) {
				after = "?" + after.substring(1);
			}
			rest = before + after;
		}

		return new Split(rest, parts.isEmpty() ? null : String.join("\n", parts));
	}

	/** Put {@code secrets} from {@link #split} back into {@code url}. Unknown lines are ignored. */
	public static String merge(String url, String secrets) {
		if (url == null || secrets == null || secrets.isBlank()) {
			return url;
		}
		String result = url;
		for (String line : secrets.split("\n")) {
			int tab = line.indexOf('\t');
			if (tab != 1) {
				continue;
			}
			char kind = line.charAt(0);
			String text = line.substring(2);
			switch (kind) {
				case Oracle -> {
					int thin = indexOfIgnoreCase(result, ":thin:@");
					if (thin >= 0) {
						int at = thin + ":thin:".length();
						result = result.substring(0, at) + text + result.substring(at);
					}
				}
				case Password -> {
					int slashes = result.indexOf("//");
					int at = slashes < 0 ? -1 : result.indexOf('@', slashes + 2);
					int slash = slashes < 0 ? -1 : result.indexOf('/', slashes + 2);
					if (at > 0 && (slash < 0 || at < slash)) {
						result = result.substring(0, at) + ":" + text + result.substring(at);
					}
				}
				case Semicolon -> result = result + ";" + text;
				case Query -> result = result + (result.indexOf('?') >= 0 ? "&" : "?") + text;
				default -> {
				}
			}
		}
		return result;
	}

	/** Two secret strings as one, for a URL that still carries some and a keychain entry with more. */
	public static String join(String a, String b) {
		var lines = new ArrayList<String>();
		for (String part : new String[] { a, b }) {
			if (part != null && !part.isBlank()) {
				for (String line : part.split("\n")) {
					if (!line.isBlank() && !lines.contains(line)) {
						lines.add(line);
					}
				}
			}
		}
		return lines.isEmpty() ? null : String.join("\n", lines);
	}

	/** The secret lines' kinds, for tests and logs that must not print the values. */
	static List<Character> kinds(String secrets) {
		var kinds = new ArrayList<Character>();
		if (secrets != null) {
			for (String line : secrets.split("\n")) {
				if (!line.isEmpty()) {
					kinds.add(line.charAt(0));
				}
			}
		}
		return kinds;
	}

	private static int indexOfIgnoreCase(String text, String part) {
		return text.toLowerCase(java.util.Locale.ROOT).indexOf(part.toLowerCase(java.util.Locale.ROOT));
	}
}
