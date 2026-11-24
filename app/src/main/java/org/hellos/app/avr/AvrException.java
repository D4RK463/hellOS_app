// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.avr;

import java.io.IOException;

/** The AVR answered, but not as expected (SOAP fault, unknown response). */
public final class AvrException extends IOException {

    public AvrException(String message) {
        super(message);
    }
}
