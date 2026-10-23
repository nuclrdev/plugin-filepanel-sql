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

import static dev.nuclr.plugin.core.panel.sql.Messages.tr;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * A password produced by a command rather than typed, such as
 * {@code gcloud auth print-access-token} for a Cloud SQL IAM login. Short-lived tokens
 * are the point, so the command runs on every connect and its output is never stored.
 *
 * <p>The command goes to the user's login shell, started interactive so it reads
 * {@code .zshrc} or {@code .bashrc}: a desktop app on macOS does not inherit the
 * terminal's {@code PATH}, and that is where tools like {@code gcloud} are put on it.
 * Whatever those files print would otherwise land in the password, so a marker is
 * printed just before the command and only what follows it is taken. On Windows it
 * is {@code cmd.exe}.
 */
@Slf4j
public final class PasswordCommand {

	private static final Set<String> POSIX_SHELLS = Set.of("sh", "bash", "zsh", "ksh", "dash");
	private static final String MARKER = "\u001eNUCLR-PASSWORD\u001e";
	private static final long TIMEOUT_SECONDS = 60;

	private PasswordCommand() {
	}

	/**
	 * Run {@code command} and return what it printed, with surrounding whitespace removed.
	 *
	 * @throws IOException when it cannot start, fails, times out or prints nothing
	 */
	public static String run(String command) throws IOException {
		var process = new ProcessBuilder(wrap(command, System.getProperty("os.name", ""), System.getenv("SHELL"),
				System.getenv().getOrDefault("COMSPEC", "cmd.exe")))
				// Nothing can be typed into it: a command that wants input (a re-login,
				// a passphrase) fails at once instead of hanging the connect.
				.redirectInput(ProcessBuilder.Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")))
				.start();
		var stdout = new Drain(process.getInputStream(), "sql-password-command-out");
		var stderr = new Drain(process.getErrorStream(), "sql-password-command-err");
		try {
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException(tr("password.command.timeout", String.valueOf(TIMEOUT_SECONDS)));
			}
			stdout.join();
			stderr.join();
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException(tr("password.command.timeout", String.valueOf(TIMEOUT_SECONDS)), e);
		}
		String out = afterMarker(stdout.text());
		String err = stderr.text().strip();
		if (process.exitValue() != 0) {
			throw new IOException(tr("password.command.failed", String.valueOf(process.exitValue()),
					err.isEmpty() ? out : lastLines(err)));
		}
		if (out.isEmpty()) {
			throw new IOException(tr("password.command.empty"));
		}
		return out;
	}

	static List<String> wrap(String command, String osName, String shell, String comspec) {
		var line = command.strip();
		var wrapped = new ArrayList<String>();
		if (osName.toLowerCase(Locale.ROOT).contains("win")) {
			wrapped.add(comspec);
			wrapped.add("/d");
			wrapped.add("/s");
			wrapped.add("/c");
			wrapped.add('"' + line + '"');
			return wrapped;
		}
		wrapped.add(posixShell(shell));
		wrapped.add("-l");
		wrapped.add("-i");
		wrapped.add("-c");
		wrapped.add("printf '%s' '" + MARKER + "'; " + line);
		return wrapped;
	}

	/** What the command printed after the marker, or all of it when there is none (Windows). */
	static String afterMarker(String output) {
		int at = output.lastIndexOf(MARKER);
		return (at < 0 ? output : output.substring(at + MARKER.length())).strip();
	}

	/** A shell's complaint can run long; the end of it is what says what went wrong. */
	private static String lastLines(String text) {
		String[] lines = text.split("\\R");
		int from = Math.max(0, lines.length - 5);
		return String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static String posixShell(String shell) {
		if (shell != null && !shell.isBlank()) {
			var name = Path.of(shell).getFileName();
			if (name != null && POSIX_SHELLS.contains(name.toString())) {
				return shell;
			}
		}
		// fish and friends parse the marker line differently; use a shell that reads it.
		for (var candidate : List.of("/bin/zsh", "/bin/bash")) {
			if (Files.isExecutable(Path.of(candidate))) {
				return candidate;
			}
		}
		return "/bin/sh";
	}

	/** Reads a stream to the end on its own thread, so a full pipe never stalls the command. */
	private static final class Drain {

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final Thread thread;

		Drain(InputStream in, String name) {
			thread = Thread.ofVirtual().name(name).start(() -> {
				try (in) {
					in.transferTo(bytes);
				} catch (IOException e) {
					log.debug("Password command stream closed: {}", e.getMessage());
				}
			});
		}

		void join() throws InterruptedException {
			thread.join(TimeUnit.SECONDS.toMillis(5));
		}

		String text() {
			return bytes.toString(StandardCharsets.UTF_8);
		}
	}
}
