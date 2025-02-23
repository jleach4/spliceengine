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

package com.splicemachine.db.iapi.services.io;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@code com.splicemachine.db.iapi.services.io.ArrayInputStream}.
 */
public class ArrayInputStreamTest {


    /**
     * Test that we don't get an overflow when the argument to skip() is
     * Long.MAX_VALUE (DERBY-3739).
     */
    @Test
    public void testSkipLongMaxValue() throws IOException {
        ArrayInputStream ais = new ArrayInputStream(new byte[1000]);
        assertEquals(1000, ais.skip(Long.MAX_VALUE));
        assertEquals(1000, ais.getPosition());
        ais.setPosition(1);
        assertEquals(999, ais.skip(Long.MAX_VALUE));
        assertEquals(1000, ais.getPosition());
    }

    /**
     * Test that we don't get an overflow when the argument to skipBytes() is
     * Integer.MAX_VALUE (DERBY-3739).
     */
    @Test
    public void testSkipBytesIntMaxValue() throws IOException {
        ArrayInputStream ais = new ArrayInputStream(new byte[1000]);
        assertEquals(1000, ais.skipBytes(Integer.MAX_VALUE));
        assertEquals(1000, ais.getPosition());
        ais.setPosition(1);
        assertEquals(999, ais.skipBytes(Integer.MAX_VALUE));
        assertEquals(1000, ais.getPosition());
    }

    /**
     * Test that skip() returns 0 when the argument is negative (DERBY-3739).
     */
    @Test
    public void testSkipNegative() throws IOException {
        ArrayInputStream ais = new ArrayInputStream(new byte[1000]);
        assertEquals(0, ais.skip(-1));
    }

    /**
     * Test that skipBytes() returns 0 when the argument is negative
     * (DERBY-3739).
     */
    @Test
    public void testSkipBytesNegative() throws IOException {
        ArrayInputStream ais = new ArrayInputStream(new byte[1000]);
        assertEquals(0, ais.skipBytes(-1));
    }
}
