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

package com.splicemachine.pipeline.constraint;

import com.splicemachine.si.testenv.ArchitectureIndependent;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.assertArrayEquals;

@Category(ArchitectureIndependent.class)
public class ConstraintContextTest {

    @Test
    public void withInsertedMessage() {
        // given
        ConstraintContext context1 = new ConstraintContext("aa", "bb", "cc");
        // when
        ConstraintContext context2 = context1.withInsertedMessage(0, "ZZ");
        ConstraintContext context3 = context1.withInsertedMessage(1, "ZZ");
        ConstraintContext context4 = context1.withInsertedMessage(2, "ZZ");
        ConstraintContext context5 = context1.withInsertedMessage(3, "ZZ");
        // then
        assertArrayEquals(new String[]{"ZZ", "aa", "bb", "cc"}, context2.getMessages());
        assertArrayEquals(new String[]{"aa", "ZZ", "bb", "cc"}, context3.getMessages());
        assertArrayEquals(new String[]{"aa", "bb", "ZZ", "cc"}, context4.getMessages());
        assertArrayEquals(new String[]{"aa", "bb", "cc", "ZZ"}, context5.getMessages());
    }

    @Test
    public void withOutMessage() {
        // given
        ConstraintContext context1 = new ConstraintContext("aa", "bb", "cc");
        // when
        ConstraintContext context2 = context1.withoutMessage(0);
        ConstraintContext context3 = context1.withoutMessage(1);
        ConstraintContext context4 = context1.withoutMessage(2);
        // then
        assertArrayEquals(new String[]{"bb", "cc"}, context2.getMessages());
        assertArrayEquals(new String[]{"aa", "cc"}, context3.getMessages());
        assertArrayEquals(new String[]{"aa", "bb"}, context4.getMessages());
    }


    @Test
    public void withMessage() {
        // given
        ConstraintContext context1 = new ConstraintContext("aa", "bb", "cc");
        // when
        ConstraintContext context2 = context1.withMessage(0, "ZZ");
        ConstraintContext context3 = context1.withMessage(1, "ZZ");
        ConstraintContext context4 = context1.withMessage(2, "ZZ");
        // then
        assertArrayEquals(new String[]{"ZZ", "bb", "cc"}, context2.getMessages());
        assertArrayEquals(new String[]{"aa", "ZZ", "cc"}, context3.getMessages());
        assertArrayEquals(new String[]{"aa", "bb", "ZZ"}, context4.getMessages());
    }
}