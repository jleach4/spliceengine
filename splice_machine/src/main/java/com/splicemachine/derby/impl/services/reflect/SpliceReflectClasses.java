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

package com.splicemachine.derby.impl.services.reflect;

import com.splicemachine.db.iapi.util.ByteArray;
import com.splicemachine.db.impl.services.reflect.DatabaseClasses;
import com.splicemachine.db.impl.services.reflect.LoadedGeneratedClass;
import com.splicemachine.db.impl.services.reflect.ReflectGeneratedClass;
import com.splicemachine.db.impl.services.reflect.ReflectLoaderJava2;
import org.spark_project.guava.cache.Cache;
import org.spark_project.guava.cache.CacheBuilder;
import java.security.PrivilegedAction;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * @author Scott Fines
 *         Created on: 10/28/13
 */
public class SpliceReflectClasses extends DatabaseClasses {
    private Cache<String,LoadedGeneratedClass> preCompiled;


    private final PrivilegedAction<ReflectLoaderJava2> reflectLoader = new PrivilegedAction<ReflectLoaderJava2>() {
        @Override
        public ReflectLoaderJava2 run() {
            return new ReflectLoaderJava2(SpliceReflectClasses.this.getClass().getClassLoader(),SpliceReflectClasses.this);
        }
    };

    private final PrivilegedAction<ClassLoader> threadLoader = new PrivilegedAction<ClassLoader>() {
        @Override
        public ClassLoader run() {
            return Thread.currentThread().getContextClassLoader();
        }
    };

    public SpliceReflectClasses() {
        super();

        preCompiled = CacheBuilder.newBuilder().maximumSize(1000).build();
    }


    @Override
    protected LoadedGeneratedClass loadGeneratedClassFromData(final String fullyQualifiedName, final ByteArray classDump){
        if (classDump == null || classDump.getArray() == null) {
            // not a generated class, just load the class directly.
            try {
                Class jvmClass = Class.forName(fullyQualifiedName);
                return new ReflectGeneratedClass(this, jvmClass, null);
            } catch (ClassNotFoundException cnfe) {
                throw new NoClassDefFoundError(cnfe.toString());
            }
        } else {
            try {
                return preCompiled.get(fullyQualifiedName,new Callable<LoadedGeneratedClass>() {
                    @Override
                    public LoadedGeneratedClass call() throws Exception {
                        return java.security.AccessController.doPrivileged(reflectLoader).loadGeneratedClass(fullyQualifiedName,classDump);
                    }
                });
            } catch (ExecutionException e) {
                //should never happen
                throw new RuntimeException(e.getCause());
            }
        }
    }

    @Override
    protected Class loadClassNotInDatabaseJar(String name) throws ClassNotFoundException {

        Class foundClass;

        // We may have two problems with calling  getContextClassLoader()
        // when trying to find our own classes for aggregates.
        // 1) If using the URLClassLoader a ClassNotFoundException may be
        //    thrown (Beetle 5002).
        // 2) If Derby is loaded with JNI, getContextClassLoader()
        //    may return null. (Beetle 5171)
        //
        // If this happens we need to user the class loader of this object
        // (the classLoader that loaded Derby).
        // So we call Class.forName to ensure that we find the class.
        try {
            ClassLoader cl= java.security.AccessController.doPrivileged(threadLoader);
            foundClass = (cl != null) ?  cl.loadClass(name)
                    :Class.forName(name);
        } catch (ClassNotFoundException cnfe) {
            foundClass = Class.forName(name);
        }
        return foundClass;
    }
}
