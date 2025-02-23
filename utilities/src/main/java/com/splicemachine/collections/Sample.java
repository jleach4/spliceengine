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

package com.splicemachine.collections;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;

/**
 * @author Scott Fines
 *         Date: 8/17/15
 */
public class Sample<T> extends AbstractCollection<T>{
    private final int maxSize;
    private final Random rng;

    private Object[] data;
    private int size;
    private int sampleSize = 0;

    public Sample(int maxSize,Random rng){
        this.maxSize=maxSize;
        this.rng=rng;
        this.data = new Object[10];
    }

    @Override
    public boolean add(T t){
        if(size<maxSize){
            place(t);
            sampleSize++;
            return true;
        }

        int n = rng.nextInt(sampleSize);
        sampleSize++;
        if(n<size){
            data[n] = t;
            return true;
        }
        return false;
    }

    @Override
    public void clear(){
        size = 0;
    }

    @Override
    public Iterator<T> iterator(){
        return new Itr();
    }

    @Override
    public int size(){
        return size;
    }

    /* **********************************************************************************************************/
    /*private helper methods and classes*/
    private class Itr implements Iterator<T>{
        private int pos =0;
        @Override
        public boolean hasNext(){
            return pos < size;
        }

        @Override
        public T next(){
            Object o =data[pos];
            pos++;
            //noinspection unchecked
            return (T)o;
        }

        @Override
        public void remove(){
            throw new UnsupportedOperationException("Removal not supported");
        }
    }

    private void place(T t){
        if(size==data.length)
            data  =Arrays.copyOf(data,Math.min(maxSize,2*size));
        data[size] = t;
        size++;
    }
}
