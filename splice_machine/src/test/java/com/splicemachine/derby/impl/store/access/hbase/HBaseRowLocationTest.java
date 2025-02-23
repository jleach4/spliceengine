/*
 * Copyright 2012 - 2016 Splice Machine, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.splicemachine.derby.impl.store.access.hbase;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.si.testenv.ArchitectureIndependent;
import com.splicemachine.utils.ByteSlice;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.*;

@Category(ArchitectureIndependent.class)
public class HBaseRowLocationTest {

    /**
     * Our clone constructor for this class currently has the characteristic that the returned clone shares the mutable
     * ByteSlice AND mutable byte array with the source HBaseRowLocation.
     *
     * This is dangerous (shared mutable state, etc) but efficient.  Existing users of this constructor may depend on
     * this behavoir (for performance if nothing else) so not changing for now, just added test to demo current
     * behavoir.
     */
    @Test
    public void constructor_clone() throws StandardException {
        // given - single byte array
        byte[] sourceBytes = new byte[]{1, 1, 1, 1, 1, 1, 1};

        // when - clone
        HBaseRowLocation orig = new HBaseRowLocation(sourceBytes);
        HBaseRowLocation clone = new HBaseRowLocation(orig);
        // when - modify byte array
        sourceBytes[0] = 127;

        // then - both row locations modified
        assertEquals(127, orig.getBytes()[0]);
        assertEquals(127, clone.getBytes()[0]);
        // then - and byte slice is the same
        assertSame(orig.getSlice(), clone.getSlice());
    }

    @Test
    public void deepClone() throws StandardException {
        // given - single byte array
        byte[] sourceBytes = new byte[]{1, 1, 1, 1, 1, 1, 1};

        // when - clone
        HBaseRowLocation orig = new HBaseRowLocation(sourceBytes);
        HBaseRowLocation clone = HBaseRowLocation.deepClone(orig);
        // when - modify byte array
        sourceBytes[0] = 127;

        // then - clone not modified
        assertEquals(127, orig.getBytes()[0]);
        assertEquals(1, clone.getBytes()[0]);
        // then - clone has distinct ByteSlice instance
        assertNotSame(orig.getSlice(), clone.getSlice());
    }


    @Test
    public void testEquals_distinctByteSlice() {

        byte[] rowLoc = new byte[]{1, 2, 3, 4};
        HBaseRowLocation rowLocation1 = new HBaseRowLocation(rowLoc);
        HBaseRowLocation rowLocation2 = new HBaseRowLocation(rowLoc);

        // equal
        assertEquals(rowLocation1, rowLocation2);
        assertEquals(rowLocation2, rowLocation1);

        // not equal
        HBaseRowLocation rowLocation3 = new HBaseRowLocation(new byte[]{1, 2, 3, -1});
        assertNotEquals(rowLocation3, rowLocation1);
    }

    @Test
    public void testEquals_nullByteSlice() {
        assertEquals(new HBaseRowLocation(), new HBaseRowLocation());
        assertEquals(new HBaseRowLocation((ByteSlice) null), new HBaseRowLocation((ByteSlice) null));
        assertEquals(new HBaseRowLocation((byte[]) null), new HBaseRowLocation((byte[]) null));
    }

    @Test
    public void testHashCode() {
        HBaseRowLocation rowLocation1 = new HBaseRowLocation(new byte[]{1, 2, 3, -1});
        HBaseRowLocation rowLocation2 = new HBaseRowLocation(new byte[]{1, 2, 3, -1});
        assertEquals(rowLocation1.hashCode(), rowLocation2.hashCode());

        // verify no NPE
        new HBaseRowLocation().hashCode();
        new HBaseRowLocation((ByteSlice) null).hashCode();
        new HBaseRowLocation((byte[]) null).hashCode();
    }


}