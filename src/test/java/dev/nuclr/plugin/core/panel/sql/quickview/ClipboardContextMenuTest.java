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
package dev.nuclr.plugin.core.panel.sql.quickview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.event.PopupMenuEvent;
import javax.swing.table.DefaultTableModel;

import org.junit.jupiter.api.Test;

/**
 * The quick-view's right-click menu. Copying itself reaches the system clipboard, which a
 * headless run has none of, so these cover what is offered and when it is enabled — the
 * part that decides whether the menu is any use.
 */
class ClipboardContextMenuTest {

	private static List<String> labels(JPopupMenu popup) {
		return java.util.Arrays.stream(popup.getComponents())
				.filter(JMenuItem.class::isInstance)
				.map(c -> ((JMenuItem) c).getText())
				.toList();
	}

	private static JMenuItem item(JPopupMenu popup, String label) {
		return java.util.Arrays.stream(popup.getComponents())
				.filter(JMenuItem.class::isInstance)
				.map(JMenuItem.class::cast)
				.filter(i -> label.equals(i.getText()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no item labelled " + label));
	}

	/** Ask the popup to refresh itself the way opening it would. */
	private static void aboutToOpen(JPopupMenu popup) {
		for (var listener : popup.getPopupMenuListeners()) {
			listener.popupMenuWillBecomeVisible(new PopupMenuEvent(popup));
		}
	}

	private static JTable gridWithRows() {
		var model = new DefaultTableModel(new Object[] { "id", "name" }, 0);
		model.addRow(new Object[] { 1, "Ada" });
		model.addRow(new Object[] { 2, "Grace" });
		return new JTable(model);
	}

	@Test
	void aTextViewOffersCopyAndSelectAll() {
		var text = new JTextArea("id: 1\nname: Ada\n");

		ClipboardContextMenu.install(text);

		assertNotNull(text.getComponentPopupMenu());
		assertEquals(List.of("Copy", "Select All"), labels(text.getComponentPopupMenu()));
	}

	@Test
	void copyIsOfferedOnlyWhenSomethingIsSelectedInText() {
		var text = new JTextArea("id: 1\nname: Ada\n");
		ClipboardContextMenu.install(text);
		var popup = text.getComponentPopupMenu();

		aboutToOpen(popup);
		assertFalse(item(popup, "Copy").isEnabled(), "nothing is selected yet");

		text.select(0, 5);
		aboutToOpen(popup);
		assertTrue(item(popup, "Copy").isEnabled());
	}

	@Test
	void selectAllIsOfferedOnlyWhileThereIsTextToSelect() {
		var text = new JTextArea("");
		ClipboardContextMenu.install(text);
		var popup = text.getComponentPopupMenu();

		aboutToOpen(popup);
		assertFalse(item(popup, "Select All").isEnabled());

		text.setText("id: 1\n");
		aboutToOpen(popup);
		assertTrue(item(popup, "Select All").isEnabled());
	}

	@Test
	void selectAllSelectsTheWholeDocument() {
		var text = new JTextArea("id: 1\nname: Ada\n");
		ClipboardContextMenu.install(text);
		var popup = text.getComponentPopupMenu();

		item(popup, "Select All").doClick();

		assertEquals(text.getText(), text.getSelectedText());
	}

	@Test
	void aGridOffersTheThreeCopiesAndSelectAll() {
		var table = gridWithRows();

		ClipboardContextMenu.install(table);

		assertNotNull(table.getComponentPopupMenu());
		assertEquals(List.of("Copy", "Copy with Headers", "Copy as CSV", "Select All"),
				labels(table.getComponentPopupMenu()));
	}

	@Test
	void copyAsCsvQuotesTheWayTheFileExportDoes() {
		var model = new DefaultTableModel(new Object[] { "id", "name" }, 0);
		model.addRow(new Object[] { 1, "a,b" });
		model.addRow(new Object[] { 2, null });
		var table = new JTable(model);
		table.setRowSelectionInterval(0, 1);

		// CRLF and the doubled-quote rules come from CsvExport, not from this menu.
		assertEquals("id,name\r\n1,\"a,b\"\r\n2,\r\n", ClipboardContextMenu.asCsv(table));
	}

	@Test
	void csvAndTabSeparatedCopiesAreDifferentThings() {
		var model = new DefaultTableModel(new Object[] { "id", "name" }, 0);
		model.addRow(new Object[] { 1, "a,b" });
		var table = new JTable(model);
		table.setRowSelectionInterval(0, 0);

		// The spreadsheet-facing copy leaves the comma alone; the CSV one has to quote it.
		assertTrue(ClipboardContextMenu.withHeaders(table).contains("1\ta,b"));
		assertTrue(ClipboardContextMenu.asCsv(table).contains("1,\"a,b\""));
	}

	@Test
	void allThreeCopiesFollowTheSelectionTogether() {
		var table = gridWithRows();
		ClipboardContextMenu.install(table);
		var popup = table.getComponentPopupMenu();

		aboutToOpen(popup);
		assertFalse(item(popup, "Copy as CSV").isEnabled());

		table.setRowSelectionInterval(0, 0);
		aboutToOpen(popup);
		assertTrue(item(popup, "Copy as CSV").isEnabled());
	}

	@Test
	void bothCopiesFollowTheSelectionTogether() {
		var table = gridWithRows();
		ClipboardContextMenu.install(table);
		var popup = table.getComponentPopupMenu();

		aboutToOpen(popup);
		assertFalse(item(popup, "Copy with Headers").isEnabled());

		table.setRowSelectionInterval(0, 1);
		aboutToOpen(popup);
		assertTrue(item(popup, "Copy with Headers").isEnabled());
	}

	@Test
	void copyWithHeadersLaysTheSelectionOutAsTabSeparatedRowsUnderTheColumnNames() {
		var table = gridWithRows();
		table.setRowSelectionInterval(0, 1);

		String text = ClipboardContextMenu.withHeaders(table);

		var lines = text.lines().toList();
		assertEquals(3, lines.size(), text);
		assertEquals("id	name", lines.get(0));
		assertEquals("1	Ada", lines.get(1));
		assertEquals("2	Grace", lines.get(2));
	}

	@Test
	void copyWithHeadersStillCarriesTheHeadersWhenNoRowIsSelected() {
		var table = gridWithRows();

		assertEquals("id	name", ClipboardContextMenu.withHeaders(table));
	}

	@Test
	void aNullCellCopiesAsAnEmptyFieldRatherThanTheWordNull() {
		var model = new DefaultTableModel(new Object[] { "id", "email" }, 0);
		model.addRow(new Object[] { 2, null });
		var table = new JTable(model);
		table.setRowSelectionInterval(0, 0);

		assertEquals("id	email" + System.lineSeparator() + "2	", ClipboardContextMenu.withHeaders(table));
	}

	@Test
	void copyIsOfferedOnlyWhenRowsAreSelectedInAGrid() {
		var table = gridWithRows();
		ClipboardContextMenu.install(table);
		var popup = table.getComponentPopupMenu();

		aboutToOpen(popup);
		assertFalse(item(popup, "Copy").isEnabled(), "no row is selected yet");

		table.setRowSelectionInterval(0, 0);
		aboutToOpen(popup);
		assertTrue(item(popup, "Copy").isEnabled());
	}

	@Test
	void selectAllIsOfferedOnlyWhileTheGridHasRows() {
		var table = new JTable(new DefaultTableModel(new Object[] { "id" }, 0));
		ClipboardContextMenu.install(table);
		var popup = table.getComponentPopupMenu();

		aboutToOpen(popup);
		assertFalse(item(popup, "Select All").isEnabled(), "an empty grid has nothing to select");

		((DefaultTableModel) table.getModel()).addRow(new Object[] { 1 });
		aboutToOpen(popup);
		assertTrue(item(popup, "Select All").isEnabled());
	}

	@Test
	void selectAllSelectsEveryRow() {
		var table = gridWithRows();
		ClipboardContextMenu.install(table);

		item(table.getComponentPopupMenu(), "Select All").doClick();

		assertEquals(2, table.getSelectedRowCount());
	}
}
