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

import java.awt.Color;
import java.util.function.Supplier;

import dev.nuclr.platform.NuclrThemeScheme;

/**
 * Selection colours for the quick view, resolved from the active
 * {@link NuclrThemeScheme} under the same keys the file panel marks entries with. A row
 * picked in the panel and a row picked in the preview beside it then read as the same
 * kind of thing, and re-theming the commander re-themes both together.
 *
 * <p>Resolved per paint rather than captured once, so switching theme takes effect on the
 * next repaint with no event plumbing to keep in sync.
 */
final class QuickViewTheme {

	/** Theme keys, matching {@code Model.FILE_MARK_KEY} / {@code FILE_MARK_BACKGROUND_KEY}. */
	private static final String MarkKey = "file-mark";
	private static final String MarkBackgroundKey = "file-mark-background";

	private final Supplier<NuclrThemeScheme> scheme;

	QuickViewTheme(Supplier<NuclrThemeScheme> scheme) {
		this.scheme = scheme == null ? () -> null : scheme;
	}

	/**
	 * Accent for a selected row. The fallback follows the panel's own: bright amber on a
	 * dark backdrop, a deeper amber on a light one, both reading as the classic Far
	 * Manager "picked this one" yellow.
	 */
	Color markColor(Color background) {
		Color fallback = isDark(background) ? new Color(255, 213, 79) : new Color(176, 124, 0);
		return color(MarkKey, fallback);
	}

	/**
	 * Band behind a selected row: a faint wash of {@link #markColor} over the backdrop, so
	 * a run of selected rows reads as one block rather than as separate stripes.
	 */
	Color markBackground(Color background) {
		Color base = background == null ? Color.BLACK : background;
		return color(MarkBackgroundKey, blend(markColor(background), base, 0.18f));
	}

	private Color color(String key, Color fallback) {
		NuclrThemeScheme active = scheme.get();
		if (active == null) {
			return fallback;
		}
		try {
			return active.color(key, fallback);
		} catch (RuntimeException e) {
			// A palette entry that is not a colour must not take the preview down with it.
			return fallback;
		}
	}

	static boolean isDark(Color background) {
		return background == null || (background.getRed() + background.getGreen() + background.getBlue()) / 3 < 128;
	}

	static Color blend(Color overlay, Color base, float weight) {
		return new Color(
				Math.round(overlay.getRed() * weight + base.getRed() * (1 - weight)),
				Math.round(overlay.getGreen() * weight + base.getGreen() * (1 - weight)),
				Math.round(overlay.getBlue() * weight + base.getBlue() * (1 - weight)));
	}
}
