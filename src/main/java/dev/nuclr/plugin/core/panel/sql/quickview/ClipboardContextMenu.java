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

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.TransferHandler;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;

import dev.nuclr.plugin.core.panel.sql.csv.CsvExport;

/**
 * Right-click "Copy" / "Select All" for the quick-view's data surfaces.
 *
 * <p>Both surfaces already answer Ctrl+C and Ctrl+A through Swing's own key bindings, but
 * only while they hold the keyboard focus — and the quick view is a side panel the cursor
 * usually is not in. A context menu is the way to reach the data without first having to
 * work out how to focus the thing.
 *
 * <p>Installed with {@link javax.swing.JComponent#setComponentPopupMenu}, which handles
 * the platform's popup trigger (press on Linux and macOS, release on Windows) and the
 * keyboard menu key, rather than a mouse listener that would have to get that right by
 * hand.
 */
final class ClipboardContextMenu {

	private ClipboardContextMenu() {
	}

	/**
	 * Copy the current selection, or select the whole document, in a read-only text view.
	 *
	 * @return the installed menu, so the caller can put the same one on the scroll pane
	 *         around it — see {@link #install(JTable)} for why that matters
	 */
	static JPopupMenu install(JTextComponent text) {
		var copy = new JMenuItem("Copy");
		copy.addActionListener(e -> text.copy());

		var selectAll = new JMenuItem("Select All");
		selectAll.addActionListener(e -> {
			// Without focus the selection is painted in the inactive highlight, which on
			// some themes is invisible; taking focus first makes the result obvious.
			text.requestFocusInWindow();
			text.selectAll();
		});

		var popup = menu(() -> {
			copy.setEnabled(text.getSelectedText() != null);
			selectAll.setEnabled(!text.getText().isEmpty());
		}, copy, selectAll);
		text.setComponentPopupMenu(popup);
		return popup;
	}

	/**
	 * Copy the selected rows, or select every row, in a result grid. Copying goes through
	 * the table's own {@link TransferHandler}, so the clipboard gets exactly what Ctrl+C
	 * would put there — tab separated, one line per row, which pastes into a spreadsheet
	 * as cells rather than as one run of text.
	 *
	 * @return the installed menu. A grid laid out with {@code AUTO_RESIZE_OFF} is only as
	 *         wide as its columns, so a viewport wider than that leaves a margin that is
	 *         not the table — put the same menu on the scroll pane and the whole panel
	 *         answers a right-click instead of just the cells.
	 */
	static JPopupMenu install(JTable table) {
		var copy = new JMenuItem("Copy");
		copy.addActionListener(e -> TransferHandler.getCopyAction()
				.actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "copy")));

		// Rows on their own paste into a spreadsheet as unlabelled columns; this is the
		// version you want when the paste has to stand on its own.
		var copyWithHeaders = new JMenuItem("Copy with Headers");
		copyWithHeaders.addActionListener(e -> copyToClipboard(withHeaders(table)));

		// Tab-separated text is what a spreadsheet wants; CSV is what a file or a tool that
		// reads one wants. They are not the same thing, so both are offered.
		var copyAsCsv = new JMenuItem("Copy as CSV");
		copyAsCsv.addActionListener(e -> copyToClipboard(asCsv(table)));

		var selectAll = new JMenuItem("Select All");
		selectAll.addActionListener(e -> {
			table.requestFocusInWindow();
			table.selectAll();
		});

		var popup = menu(() -> {
			boolean hasSelection = table.getSelectedRowCount() > 0;
			copy.setEnabled(hasSelection);
			copyWithHeaders.setEnabled(hasSelection);
			copyAsCsv.setEnabled(hasSelection);
			selectAll.setEnabled(table.getRowCount() > 0);
		}, copy, copyWithHeaders, copyAsCsv, selectAll);
		table.setComponentPopupMenu(popup);
		return popup;
	}

	/** The selected rows as tab-separated text, with the column headers as the first line. */
	static String withHeaders(JTable table) {
		var text = new StringBuilder();
		int columns = table.getColumnCount();
		for (int column = 0; column < columns; column++) {
			text.append(column > 0 ? "	" : "").append(table.getColumnName(column));
		}
		for (int row : table.getSelectedRows()) {
			text.append(System.lineSeparator());
			for (int column = 0; column < columns; column++) {
				Object value = table.getValueAt(row, column);
				text.append(column > 0 ? "	" : "").append(value == null ? "" : value);
			}
		}
		return text.toString();
	}

	/**
	 * The selected rows as CSV, quoted by the same {@link CsvExport} the panel's own export
	 * uses — so a grid copy and a file export of the same rows agree character for
	 * character.
	 */
	static String asCsv(JTable table) {
		var columns = new ArrayList<String>(table.getColumnCount());
		for (int column = 0; column < table.getColumnCount(); column++) {
			columns.add(table.getColumnName(column));
		}
		var rows = new ArrayList<Map<String, Object>>();
		for (int row : table.getSelectedRows()) {
			var values = new LinkedHashMap<String, Object>();
			for (int column = 0; column < columns.size(); column++) {
				values.put(columns.get(column), table.getValueAt(row, column));
			}
			rows.add(values);
		}
		return CsvExport.rowsCsv(columns, rows);
	}

	private static void copyToClipboard(String text) {
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	/**
	 * A popup whose items are brought up to date each time it opens. Enablement has to be
	 * decided then rather than once at construction, because what is selected changes
	 * between one right-click and the next.
	 */
	private static JPopupMenu menu(Runnable refresh, JMenuItem... items) {
		var popup = new JPopupMenu();
		for (JMenuItem item : items) {
			popup.add(item);
		}
		popup.addPopupMenuListener(new PopupMenuListener() {

			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
				refresh.run();
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
				// nothing to undo
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e) {
				// nothing to undo
			}
		});
		return popup;
	}
}
