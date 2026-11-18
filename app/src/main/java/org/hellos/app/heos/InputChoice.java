// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.heos;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** An input that can be played on the selected player. */
public record InputChoice(String label, Supplier<CompletableFuture<Void>> play) {
}
