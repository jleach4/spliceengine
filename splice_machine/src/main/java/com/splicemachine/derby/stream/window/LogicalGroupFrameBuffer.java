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

package com.splicemachine.derby.stream.window;

import org.spark_project.guava.collect.PeekingIterator;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.derby.impl.sql.execute.operations.window.FrameDefinition;
import com.splicemachine.derby.impl.sql.execute.operations.window.WindowAggregator;
import java.io.IOException;

/**
 * @author jyuan on 9/15/14.
 */
public class LogicalGroupFrameBuffer extends BaseFrameBuffer {

    public LogicalGroupFrameBuffer (
                                    WindowAggregator[] aggregators,
                                    PeekingIterator<ExecRow> source,
                                    FrameDefinition frameDefinition,
                                    int[] sortColumns,
                                    ExecRow templateRow) throws StandardException {
        super(aggregators, source, frameDefinition, sortColumns, templateRow);
    }

    @Override
    protected void loadFrame() throws IOException, StandardException {
        // peek the first row
        ExecRow row = source.peek();
        // TODO: throw an error when there are more than one sort columns
        DataValueDescriptor currentValue = null;
        if (frameEnd < Long.MAX_VALUE) {
            currentValue = row.getColumn(sortColumns[0] + 1).cloneValue(false);
        }

        boolean endOfFrame = false;
        while (!endOfFrame) {
            ExecRow clonedRow = row.getClone();

            if (frameEnd < Long.MAX_VALUE) {
                // if frame end is not unbounded following, compare values
                DataValueDescriptor v = clonedRow.getColumn(sortColumns[0]+1);
                if (v.compare(currentValue)==0) {
                    // if the value falls into the window frame, aggregate it
                    add(clonedRow);
                    rows.add(clonedRow);
                }
                else {
                    endOfFrame = true;
                    continue;
                }
            }
            else {
                // Otherwise, always aggregate it
                rows.add(clonedRow);
                add(clonedRow);
            }
            // advance iterator
            source.next();
            if (source.hasNext()) {
                row = source.peek();
            } else {
                break;
            }
        }
        current = 0;
        end = rows.size() -1;
    }

    @Override
    public void move() throws StandardException, IOException{
        // Increment the current index first
        current++;
        // if the next candidate row is not in the buffer yet, read it from scanner
        if (current >= rows.size()) {

            if (source.hasNext()) {
                ExecRow row = source.next();
                ExecRow clonedRow = row.getClone();
                rows.add(clonedRow);
                // One more row is added into the frame buffer, include one more row into the window frame
                end++;
                add(rows.get(end));
            } else {
                return;
            }
        }

        // Remove rows from the front of the window frame
        DataValueDescriptor newKey = null;
        if (frameEnd < Long.MAX_VALUE || frameStart > Long.MIN_VALUE) {
            newKey = rows.get(current).getColumn(sortColumns[0] + 1);
        }
        if (frameStart > Long.MIN_VALUE) {
            boolean inRange = false;
            while (!inRange) {
                ExecRow row = rows.get(start);
                DataValueDescriptor v = row.getColumn(sortColumns[0]+1);
                if (v.compare(newKey) < 0) {
                    removeInternal();
                    start++;
                }
                else {
                    inRange = true;
                }
            }
        }

        // Remove rows from buffer if they are no longer needed
        int minIndex = current < start ? current : start;
        for (int i = 0; i < minIndex; ++i) {
            rows.remove(0);
            start--;
            current--;
            end--;

        }

        // Add rows to the end of window frame
        if (frameEnd < Long.MAX_VALUE) {
            while(source.hasNext()) {
                ExecRow row = source.peek();
                ExecRow clonedRow = row.getClone();
                DataValueDescriptor v = row.getColumn(sortColumns[0]+1);
                if (newKey != null && newKey.compare(v) == 0) {
                    //advance iterator
                    source.next();
                    rows.add(clonedRow);
                    add(clonedRow);
                    end++;
                } else {
                    // not in range, bail out
                    break;
                }
            }
        }
    }
}