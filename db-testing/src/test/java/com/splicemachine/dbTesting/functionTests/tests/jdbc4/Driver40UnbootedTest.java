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

package com.splicemachine.dbTesting.functionTests.tests.jdbc4;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;

import junit.framework.Test;
import junit.framework.TestSuite;

import com.splicemachine.dbTesting.junit.BaseJDBCTestCase;
import com.splicemachine.dbTesting.junit.JDBC;
import com.splicemachine.dbTesting.junit.TestConfiguration;
import com.splicemachine.dbTesting.junit.SpawnedProcess;
import com.splicemachine.dbTesting.junit.SecurityManagerSetup;


/**
 * Test that getParentLogger() returns the correct kind of exception when
 * the engine is not booted.
 */

public class Driver40UnbootedTest extends BaseJDBCTestCase
{
    ///////////////////////////////////////////////////////////////////////////////////
    //
    // CONSTANTS
    //
    ///////////////////////////////////////////////////////////////////////////////////

    private static  final   String  SUCCESS = "Success";

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // STATE
    //
    ///////////////////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // CONSTRUCTOR
    //
    ///////////////////////////////////////////////////////////////////////////////////

    public Driver40UnbootedTest(String name) { super( name ); }

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // JUnit BEHAVIOR
    //
    ///////////////////////////////////////////////////////////////////////////////////

    /**
     * Return suite with all tests of the class.
     */
    public static Test suite()
    {
        if (JDBC.vmSupportsJSR169())
        {
            return new TestSuite(
                "DriverTest tests java.sql.Driver, not supported with JSR169");
        }
        
        Test test = TestConfiguration.embeddedSuite(Driver40UnbootedTest.class);

        return SecurityManagerSetup.noSecurityManager( test );
    }
   
    ///////////////////////////////////////////////////////////////////////////////////
    //
    // ENTRY POINT
    //
    ///////////////////////////////////////////////////////////////////////////////////

    /**
     * <p>
     * This entry point is used to run a separate java process in order to verify
     * that the correct exception is being raised by getParentLogger() when the
     * engine hasn't been booted yet.
     * </p>
     */
    public  static  void    main( String[] args )  throws Exception
    {
        Driver  embeddedDriver = DriverManager.getDriver( "jdbc:splice:" );
        Wrapper41Driver embeddedWrapper = new Wrapper41Driver( embeddedDriver );

        String  statusMessage = SUCCESS;
        
        try {
            embeddedWrapper.getParentLogger();
            statusMessage = "getParentLogger() unexpectedly succeeded";
        }
        catch (Exception se)
        {
            if ( !( se instanceof SQLFeatureNotSupportedException ) )
            {
                statusMessage = "Exception was not a SQLFeatureNotSupportedException. It was a " + se.getClass().getName();
            }
        }

        System.out.print( statusMessage );
    }

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // TESTS
    //
    ///////////////////////////////////////////////////////////////////////////////////


    /**
     * <p>
     * Test that getParentLogger() raises the right exception even if the engine
     * isn't booted.
     * </p>
     */
    public void test_notBooted() throws Exception
    {
        if ( !getTestConfiguration().loadingFromJars() ) { return ; }
        
        String[] command = {
            "-Demma.verbosity.level=silent",
            getClass().getName()
        };

        Process process = execJavaCmd(command);
        
        SpawnedProcess spawned = new SpawnedProcess( process, "UnbootedTest" );
        
        // Ensure it completes without failures.
        assertEquals(0, spawned.complete());

        assertEquals( SUCCESS, spawned.getFullServerOutput() );
    }

}
