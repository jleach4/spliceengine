/*
 * Apache Derby is a subproject of the Apache DB project, and is licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use these files
 * except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Splice Machine, Inc. has modified this file.
 *
 * All Splice Machine modifications are Copyright 2012 - 2016 Splice Machine, Inc.,
 * and are licensed to you under the License; you may not use this file except in
 * compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */
package com.splicemachine.db.impl.jdbc;

import java.io.IOException;
import java.io.Writer;
import java.sql.SQLException;
import com.splicemachine.db.iapi.reference.MessageId;
import com.splicemachine.db.iapi.services.i18n.MessageService;

/**
 * Writer implementation for <code>Clob</code>.
 */
final class ClobUtf8Writer extends Writer {
    private TemporaryClob control;    
    private long pos; // Position in characters.
    private boolean closed;
    
    /**
     * Constructor.
     *
     * @param control worker object for the CLOB value
     * @param pos initial <b>byte</b> position in the CLOB value
     */
    ClobUtf8Writer(TemporaryClob control, long pos) {
        this.control = control;
        this.pos = pos;
        closed = false;
    }    

    /**
     * Flushes the stream.
     * <p>
     * Flushing the stream after {@link #close} has been called will cause an
     * exception to be thrown.
     * <p>
     * <i>Implementation note:</i> In the current implementation, this is a
     * no-op. Flushing is left to the underlying stream(s). Note that when
     * programming against/with this class, always follow good practice and call
     * <code>flush</code>.
     *
     * @throws IOException if the stream has been closed
     */
    public void flush() throws IOException {
        if (closed)
            throw new IOException (
                MessageService.getTextMessage(MessageId.OBJECT_CLOSED));
        // A no-op.
        // Flushing is currently the responsibility of the underlying stream(s).
    }

    /**
     * Closes the stream.
     * <p>
     * Once the stream has been closed, further <code>write</code> or 
     * {@link #flush} invocations will cause an <code>IOException</code> to be
     * thrown. Closing a previously closed stream has no effect.
     */
    public void close() {
        closed = true;
    }

    /**
     * Writes a portion of an array of characters to the CLOB value.
     * 
     * @param cbuf array of characters
     * @param off offset into <code>cbuf</code> from which to start writing
     *      characters
     * @param len number of characters to write
     * @throws IOException if an I/O error occurs
     */
    public void write(char[] cbuf, int off, int len) throws IOException {
        if (closed)
            throw new IOException (
                MessageService.getTextMessage(MessageId.OBJECT_CLOSED));
        try {
            long ret = control.insertString (String.copyValueOf (
                                                    cbuf, off, len), 
                                              pos);
            if (ret > 0)
                pos += ret;
        }
        catch (SQLException e) {
            throw Util.newIOException(e);
        }
    }
}
