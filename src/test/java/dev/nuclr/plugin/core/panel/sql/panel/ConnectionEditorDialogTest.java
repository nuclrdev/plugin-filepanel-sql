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
package dev.nuclr.plugin.core.panel.sql.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * What "Test Connection" puts in the status area. The dialog itself needs a display, so
 * these cover the message building, which is what made the old single-line label
 * unreadable in the first place.
 */
class ConnectionEditorDialogTest {

	@Test
	void aLoneFailureReadsAsItsOwnMessage() {
		assertEquals("Connection refused",
				ConnectionEditorDialog.describeFailure(new SQLException("Connection refused")));
	}

	@Test
	void aChainReadsFromTheOutsideIn() {
		var failure = new SQLException("Could not open a session",
				new IOException("connect timed out after 30s"));

		assertEquals("Could not open a session\n\nCaused by: connect timed out after 30s",
				ConnectionEditorDialog.describeFailure(failure));
	}

	@Test
	void aWrapperThatOnlyRepeatsItsCauseIsNotSaidTwice() {
		// The common JDBC shape: the outer message quotes the inner one verbatim.
		var failure = new SQLException("Connection refused: connect", new IOException("Connection refused"));

		assertEquals("Connection refused: connect", ConnectionEditorDialog.describeFailure(failure));
	}

	@Test
	void theMoreInformativeLinkWinsWhenTheCauseSubsumesTheWrapper() {
		var failure = new SQLException("refused", new IOException("connection refused by db.example.com:5432"));

		assertEquals("connection refused by db.example.com:5432",
				ConnectionEditorDialog.describeFailure(failure));
	}

	@Test
	void anIdenticalMessageRepeatedDownTheChainIsSaidOnce() {
		var failure = new SQLException("Access denied", new SQLException("Access denied",
				new SQLException("Access denied")));

		assertEquals("Access denied", ConnectionEditorDialog.describeFailure(failure));
	}

	@Test
	void aFailureWithNoMessageIsNamedByItsType() {
		assertEquals("NullPointerException", ConnectionEditorDialog.describeFailure(new NullPointerException()));
		assertEquals("IOException", ConnectionEditorDialog.describeFailure(new IOException("   ")));
	}

	@Test
	void aVeryDeepChainStopsBeingReadOutAfterAFewLinks() {
		Throwable failure = new IOException("root cause");
		for (int i = 0; i < 20; i++) {
			failure = new SQLException("layer " + i, failure);
		}

		String described = ConnectionEditorDialog.describeFailure(failure);

		assertTrue(described.startsWith("layer 19"), described);
		assertFalse(described.contains("root cause"), "a 20-deep chain should not be read out in full");
		assertEquals(4, described.split("Caused by: ", -1).length - 1, described);
	}

	@Test
	void aSelfReferentialCauseDoesNotHangTheDialog() {
		var looping = new SQLException("stuck") {

			private static final long serialVersionUID = 1L;

			@Override
			public synchronized Throwable getCause() {
				return this;
			}
		};

		assertTimeoutPreemptively(Duration.ofSeconds(2),
				() -> assertEquals("stuck", ConnectionEditorDialog.describeFailure(looping)));
	}

	@Test
	void aMessageLongEnoughToHaveBrokenTheOldLabelIsKeptWhole() {
		String sprawling = "FATAL: password authentication failed for user \"app\"; "
				+ "url=jdbc:postgresql://db.internal.example.com:5432/analytics_warehouse"
				+ "?sslmode=require&ApplicationName=Nuclr&connectTimeout=30";

		String described = ConnectionEditorDialog.describeFailure(new SQLException(sprawling));

		// Nothing is truncated: the status area wraps and scrolls, so length is no longer
		// the layout's problem.
		assertEquals(sprawling, described);
	}
}
