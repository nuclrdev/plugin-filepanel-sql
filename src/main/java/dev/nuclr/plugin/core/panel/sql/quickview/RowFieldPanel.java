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

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.function.Supplier;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.plugin.core.panel.sql.resource.SqlNuclrResource;

/** QuickView (Ctrl+Q) preview for a single SQL row: a field list, with a raw-JSON toggle. */
public final class RowFieldPanel extends JPanel {

	private final JTextArea textArea = new JTextArea();
	private final JToggleButton jsonToggle = new JToggleButton("JSON");

	private final QuickViewTheme theme;

	private SqlNuclrResource current;

	public RowFieldPanel(Supplier<NuclrThemeScheme> theme) {
		super(new BorderLayout());
		this.theme = new QuickViewTheme(theme);
		textArea.setEditable(false);
		textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		var textScrollPane = new JScrollPane(textArea);
		textScrollPane.setComponentPopupMenu(ClipboardContextMenu.install(textArea));

		var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
		toolbar.add(jsonToggle);

		add(toolbar, BorderLayout.NORTH);
		add(textScrollPane, BorderLayout.CENTER);

		jsonToggle.addActionListener(e -> render());
		jsonToggle.setToolTipText("Show the row as raw JSON instead of a field list.");
		applyTheme();
	}

	public void show(SqlNuclrResource row) {
		this.current = row;
		// Re-resolved on every row so a theme change lands without any event plumbing.
		applyTheme();
		render();
	}

	/** Selected text picks up the same accent a marked entry does in the panel. */
	private void applyTheme() {
		textArea.setSelectionColor(theme.markBackground(textArea.getBackground()));
		textArea.setSelectedTextColor(theme.markColor(textArea.getBackground()));
	}

	public void clear() {
		this.current = null;
		textArea.setText("");
	}

	private void render() {
		if (current == null) {
			textArea.setText("");
			return;
		}
		textArea.setText(jsonToggle.isSelected() ? renderJson(current) : renderFields(current));
		textArea.setCaretPosition(0);
	}

	static String renderFields(SqlNuclrResource row) {
		var sb = new StringBuilder();
		for (String column : row.getColumnOrder()) {
			sb.append(column).append(": ").append(format(row.getMetadata().get(column))).append('\n');
		}
		return sb.toString();
	}

	static String renderJson(SqlNuclrResource row) {
		var columns = row.getColumnOrder();
		var sb = new StringBuilder("{\n");
		for (int i = 0; i < columns.size(); i++) {
			String column = columns.get(i);
			sb.append("  \"").append(escape(column)).append("\": ").append(jsonValue(row.getMetadata().get(column)));
			if (i < columns.size() - 1) {
				sb.append(',');
			}
			sb.append('\n');
		}
		sb.append("}\n");
		return sb.toString();
	}

	private static String format(Object value) {
		return value == null ? "NULL" : String.valueOf(value);
	}

	private static String jsonValue(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Number || value instanceof Boolean) {
			return String.valueOf(value);
		}
		return "\"" + escape(String.valueOf(value)) + "\"";
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
	}
}
