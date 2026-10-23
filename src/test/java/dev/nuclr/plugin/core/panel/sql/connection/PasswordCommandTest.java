package dev.nuclr.plugin.core.panel.sql.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

class PasswordCommandTest {

	@Test
	void runsInTheLoginShellOnMacAndLinux() {
		List<String> line = PasswordCommand.wrap("gcloud auth print-access-token", "Mac OS X", "/bin/zsh", "cmd.exe");
		assertEquals(List.of("/bin/zsh", "-l", "-i", "-c"), line.subList(0, 4));
		assertTrue(line.get(4).endsWith("; gcloud auth print-access-token"));
	}

	@Test
	void runsInCmdOnWindows() {
		assertEquals(List.of("C:\\Windows\\cmd.exe", "/d", "/s", "/c", "\"type token.txt\""),
				PasswordCommand.wrap(" type token.txt ", "Windows 11", null, "C:\\Windows\\cmd.exe"));
	}

	@Test
	void dropsWhatTheShellStartupPrintedBeforeTheMarker() {
		String marker = PasswordCommand.wrap("x", "Linux", "/bin/sh", "").get(4).split("'")[3];
		assertEquals("ya29.token", PasswordCommand.afterMarker("Welcome!\nnvm loaded\n" + marker + "ya29.token\n"));
		assertEquals("plain", PasswordCommand.afterMarker("  plain\r\n"));
	}

	@Test
	void returnsWhatTheCommandPrinted() throws IOException {
		assertEquals("s3cret-token", PasswordCommand.run("echo s3cret-token"));
	}

	@Test
	void reportsAFailingCommand() {
		var e = assertThrows(IOException.class, () -> PasswordCommand.run("exit 3"));
		assertTrue(e.getMessage().contains("3"), e.getMessage());
	}

	@Test
	void profileWithCommandNeverPrompts() {
		var profile = ConnectionProfile.builder().username("sergey.nechaev")
				.passwordCommand("gcloud auth print-access-token").build();
		assertFalse(profile.needsPassword());
		profile.setPasswordCommand(" ");
		assertTrue(profile.needsPassword());
	}
}
