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

package com.splicemachine.storage.util;

import com.splicemachine.metrics.TimeView;
import com.splicemachine.storage.DataCell;
import com.splicemachine.storage.DataResult;
import com.splicemachine.storage.DataResultScanner;
import com.splicemachine.storage.DataScanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A DataResultScanner which delegates to an underlying DataScanner. This makes it easy
 * to convert between the two interfaces.
 *
 * Subclasses are expected to provide the implementation of DataResult to return.
 *
 * @author Scott Fines
 *         Date: 12/16/15
 */
public abstract class MappedDataResultScanner implements DataResultScanner{

    private final DataScanner scanner;
    private List<DataCell> internalList;

    private DataResult resultWrapper;

    public MappedDataResultScanner(DataScanner scanner){
        this.scanner=scanner;
    }

    @Override
    public DataResult next() throws IOException{
        List<DataCell> n = scanner.next(-1);
        if(n==null||n.size()<=0) return null;
        if(internalList==null)
            internalList = new ArrayList<>(n.size());
        internalList.clear();
        internalList.addAll(n);
        if(resultWrapper==null)
            resultWrapper = newResult();

        setResultRow(internalList,resultWrapper);
        return resultWrapper;
    }

    @Override public TimeView getReadTime(){ return scanner.getReadTime(); }
    @Override public long getBytesOutput(){ return scanner.getBytesOutput(); }
    @Override public long getRowsFiltered(){ return scanner.getRowsFiltered(); }
    @Override public long getRowsVisited(){ return scanner.getRowsVisited(); }

    @Override
    public void close() throws IOException{
        scanner.close();
    }

    protected abstract DataResult newResult();

    protected abstract void setResultRow(List<DataCell> nextRow,DataResult resultWrapper);
}
