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

package com.splicemachine.testutil;

import org.junit.experimental.theories.PotentialAssignment;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.internal.Assignments;
import org.junit.internal.AssumptionViolatedException;
import org.junit.runners.model.*;

import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

/**
 * A Parallel Runner for Theories classes. This makes it so that different permutations
 * will be run in parallel in a (configured) threadpool. This means we can do more permutations
 * without significantly impacting our overall test times.
 * @author Scott Fines
 *         Date: 6/23/15
 */
public class ParallelTheoryRunner extends Theories{

    public ParallelTheoryRunner(Class<?> klass) throws InitializationError{
        super(klass);
        setScheduler(new ParallelScheduler());
    }

    @Override
    public Statement methodBlock(FrameworkMethod method){
        return new ParallelTheoryAnchor(method,getTestClass());
    }

    private static class ParallelScheduler implements RunnerScheduler{
        private static final ForkJoinPool FORK_JOIN_POOL = setupForkJoinPool();

        private static ForkJoinPool setupForkJoinPool(){
            Runtime runtime = Runtime.getRuntime();
            int numThreads = runtime.availableProcessors();
            try{
                String configuredThreads = System.getProperty("maxParallelTheories");
                numThreads = Math.max(numThreads,Integer.parseInt(configuredThreads));
            }catch(Exception ignored){
                numThreads = 3*numThreads/4;
            }
            if(numThreads<1)
                numThreads = 1;
            ForkJoinPool.ForkJoinWorkerThreadFactory threadFactory = new ForkJoinPool.ForkJoinWorkerThreadFactory(){
                @Override
                public ForkJoinWorkerThread newThread(ForkJoinPool pool){
                    ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                    thread.setName("TestTheoryRunner-"+thread.getName());
                    return thread;
                }
            };
            return new ForkJoinPool(numThreads,threadFactory,null,false);
        }

        private final Deque<ForkJoinTask<?>> tasks = new LinkedList<>();
        private Runnable lastScheduled;

        @Override
        public void schedule(Runnable childStatement){
            if(lastScheduled!=null){
                //submit the last child
                if(ForkJoinTask.inForkJoinPool()){
                    tasks.addFirst(ForkJoinTask.adapt(lastScheduled).fork());
                }else{
                    tasks.addFirst(FORK_JOIN_POOL.submit(lastScheduled));
                }
            }
            lastScheduled = childStatement;
        }

        @Override
        public void finished(){
            List<Throwable> errors = new LinkedList<>();
            if (lastScheduled != null) {
                if (ForkJoinTask.inForkJoinPool()) {
                    // Execute the last scheduled child in the current thread ...
                    try { lastScheduled.run(); } catch (Throwable t) { errors.add(t); }
                } else {
                    // Submit the last scheduled child to the ForkJoinPool too,
                    // because all tests should run in the worker threads ...
                    tasks.addFirst(FORK_JOIN_POOL.submit(lastScheduled));
                }
                // Make sure all asynchronously executed children are done, before we return ...
                for (ForkJoinTask<?> task : tasks) {
                    // Note: Because we have added all tasks via addFirst into _asyncTasks,
                    // task.join() is able to steal tasks from other worker threads,
                    // if there are tasks, which have not been started yet ...
                    // from other worker threads ...
                    try { task.join(); } catch (Throwable t) { errors.add(t); }
                }
                if(errors.size()>0)
                    throw new RuntimeException(new MultipleFailureException(Collections.unmodifiableList(errors)));
            }
        }
    }

    private class ParallelTheoryAnchor extends TheoryAnchor{
        private final Deque<ForkJoinTask<?>> runs = new LinkedBlockingDeque<>();
        private volatile boolean wasRunWithAssignmentCalled;

        public ParallelTheoryAnchor(FrameworkMethod method,TestClass testClass){
            super(method,testClass);
        }

        @Override
        protected void runWithAssignment(Assignments assignments) throws Throwable{
            if(wasRunWithAssignmentCalled)
                super.runWithAssignment(assignments);
            else{
                wasRunWithAssignmentCalled =true;
                super.runWithAssignment(assignments);
                /*
                 * Since this is the first time we entered this block,
                 * we need to make sure that all asynchronous runs have finished before we
                 * return.
                 */
                Throwable failure = null;
                while(failure==null &&!runs.isEmpty()){
                    ForkJoinTask<?> task = runs.removeFirst();
                    try{
                        task.join();
                    }catch(Throwable t){failure = t;}
                }
                if(failure!=null){
                    //cancel everything left, since we have an error
                    while(!runs.isEmpty()){
                        ForkJoinTask<?> task = runs.removeFirst();
                        try{
                            task.cancel(true);
                        }catch(Throwable ignored){}
                    }
                    //and join to make sure everything is happy
                    while(!runs.isEmpty()){
                        ForkJoinTask<?> task = runs.removeFirst();
                        try{
                            task.join();
                        }catch(Throwable ignored){}
                    }

                    throw failure;
                }
            }
        }

        //override to add synchronization
        @Override
        protected synchronized void handleAssumptionViolation(AssumptionViolatedException e){
            super.handleAssumptionViolation(e);
        }

        @Override
        protected synchronized void handleDataPointSuccess(){
            super.handleDataPointSuccess();
        }

        @Override
        protected void runWithIncompleteAssignment(Assignments incomplete) throws Throwable{
            for(PotentialAssignment source:incomplete.potentialsForNextUnassigned()){
                final Assignments nextAssignment = incomplete.assignNext(source);
                ForkJoinTask<?> run = new RecursiveAction(){
                    @Override
                    protected void compute(){
                        try{
                            ParallelTheoryAnchor.this.runWithAssignment(nextAssignment);
                        }catch(Throwable t){
                            throw new RuntimeException(t);
                        }
                    }
                };
                runs.addFirst(run.fork());
            }
        }
    }
}
