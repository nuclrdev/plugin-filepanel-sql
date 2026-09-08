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
package dev.nuclr.plugin.core.panel.sql.resource;

/**
 * What a {@link SqlNuclrResource} represents in the virtual tree
 * {@code Explorer root -> Connection -> Schema -> Table/View -> Row}.
 *
 * <p>Stored in {@link dev.nuclr.platform.plugin.NuclrResource#getMetadata()} under
 * {@link SqlNuclrResource#KeyKind} so {@code SqlFilePanelPlugin.openResource} and the
 * quick-view companion can dispatch on it without an {@code instanceof} chain.
 */
public enum ResourceKind {

	/** The "SQL Explorer" root: lists saved connection profiles. */
	EXPLORER_ROOT,

	/** A saved connection profile, not yet connected. Opening it connects. */
	CONNECTION,

	/** A schema (or the closest analogous grouping the driver exposes). */
	SCHEMA,

	/** A table. */
	TABLE,

	/** A view. */
	VIEW,

	/** A single row of a table or view. */
	ROW,

	/** The non-selectable marker appended when a table listing hits the row cap. */
	ROW_LIMIT_MARKER
}
