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
package dev.nuclr.plugin.core.panel.sql;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.panel.sql.quickview.QuickViewCardPanel;
import dev.nuclr.plugin.core.panel.sql.resource.ResourceKind;
import dev.nuclr.plugin.core.panel.sql.resource.SqlNuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Ctrl+Q companion to {@link SqlFilePanelPlugin}: a field-list/JSON preview for a row,
 * a bounded grid preview for a table or view.
 */
@Slf4j
public final class SqlRowQuickViewPlugin implements QuickViewNuclrPlugin {

	private final String uuid = UUID.randomUUID().toString();

	private NuclrPluginContext context;
	private volatile boolean focused;
	private volatile NuclrResource currentResource;
	private QuickViewCardPanel view;

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
	}

	@Override
	public void init() {
	}

	@Override
	public void unload() {
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public void closeResource() {
		currentResource = null;
		if (view != null) {
			SwingUtilities.invokeLater(view::clear);
		}
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		if (!(resource instanceof SqlNuclrResource sql)) {
			return false;
		}
		return sql.getKind() == ResourceKind.ROW || sql.getKind() == ResourceKind.TABLE || sql.getKind() == ResourceKind.VIEW;
	}

	@Override
	public JComponent panel() {
		if (view == null) {
			// The context, not a captured palette: asking per paint means a theme switch
			// reaches the preview without this plugin having to listen for one.
			view = new QuickViewCardPanel(() -> context == null ? null : context.getTheme());
		}
		return view;
	}

	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (!supports(resource)) {
			return false;
		}
		var sql = (SqlNuclrResource) resource;
		currentResource = sql;
		JComponent ignored = panel(); // ensure the card panel exists before use
		if (sql.getKind() == ResourceKind.ROW) {
			SwingUtilities.invokeLater(() -> view.showRow(sql));
		} else {
			SwingUtilities.invokeLater(() -> view.showTable(sql, cancelled));
		}
		return true;
	}
}
