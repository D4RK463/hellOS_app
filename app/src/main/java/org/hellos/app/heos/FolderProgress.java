// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

/**
 * Progress of playing or queueing a whole folder.
 *
 * @param done  titles added so far
 * @param total titles to add, {@code 0} while the folder is still being read
 */
public record FolderProgress(int done, int total) {
}
