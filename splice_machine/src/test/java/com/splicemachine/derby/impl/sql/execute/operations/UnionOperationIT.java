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

package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.derby.test.framework.TestConnection;
import com.splicemachine.util.StatementUtils;
import org.junit.*;
import org.spark_project.guava.collect.Lists;
import org.spark_project.guava.collect.Ordering;
import org.spark_project.guava.collect.Sets;
import com.splicemachine.derby.test.framework.SpliceWatcher;
import com.splicemachine.derby.test.framework.SpliceSchemaWatcher;
import com.splicemachine.homeless.TestUtils;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import static com.splicemachine.derby.test.framework.SpliceUnitTest.resultSetSize;
import static org.junit.Assert.*;

public class UnionOperationIT {

    private static final String CLASS_NAME = UnionOperationIT.class.getSimpleName().toUpperCase();
    private static final SpliceWatcher spliceClassWatcher = new SpliceWatcher(CLASS_NAME);

    private static final Comparator<int[]> intArrayComparator= new Comparator<int[]>(){
        @Override
        public int compare(int[] o1,int[] o2){
            int compare;
            for(int i=0;i<Math.min(o1.length,o2.length);i++){
                compare = Integer.compare(o1[i],o2[i]);
                if(compare!=0) return compare;
            }
            return 0;
        }
    };

    @ClassRule
    public static TestRule chain = RuleChain.outerRule(spliceClassWatcher)
            .around(new SpliceSchemaWatcher(CLASS_NAME))
            .around(TestUtils.createFileDataWatcher(spliceClassWatcher, "test_data/UnionOperationIT.sql", CLASS_NAME));

    @Rule
    public SpliceWatcher methodWatcher = new SpliceWatcher(CLASS_NAME);

    private TestConnection conn;

    @Before
    public void setUp() throws Exception{
        conn = methodWatcher.getOrCreateConnection();
        conn.setAutoCommit(false);
    }

    @After
    public void tearDown() throws Exception{
        conn.rollback();
        conn.reset();
    }

    @Test
    public void testUnionAll() throws Exception {
        try(Statement s = conn.createStatement()){
            List<String> names=queryToStringList(s,"select name from ST_MARS UNION ALL select name from ST_EARTH");
            assertEquals(10,names.size());
            assertEquals(Ordering.natural().nullsLast().sortedCopy(names),
                    Lists.newArrayList(
                            "Duncan-Robert","Mulgrew-Kate","Nimoy-Leonard","Nimoy-Leonard","Patrick","Ryan-Jeri",
                            "Shatner-William","Spiner-Brent",null,null
                    ));
        }
    }


    @Test
    public void testUnionOneColumn() throws Exception {
        try(Statement s = conn.createStatement()){
            List<String> names=queryToStringList(s,"select name from ST_MARS UNION select name from ST_EARTH");
            assertEquals(8,names.size());
            assertEquals(Ordering.natural().nullsLast().sortedCopy(names),
                    Lists.newArrayList(
                            "Duncan-Robert","Mulgrew-Kate","Nimoy-Leonard","Patrick","Ryan-Jeri","Shatner-William",
                            "Spiner-Brent",null
                    ));
        }
    }

    /* This needs to use a provider interface for both of its traversals and not use isScan - JL */
    @Test
    public void testValuesUnion() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs=s.executeQuery("SELECT TTABBREV, TABLE_TYPE from (VALUES ('T','TABLE'), ('S','SYSTEM TABLE'), ('V', 'VIEW'), ('A', 'SYNONYM')) T (TTABBREV,TABLE_TYPE)")){
                assertTrue(resultSetSize(rs)>0);
            }
        }
    }

    @Test
    public void testUnion() throws Exception {
        try(Statement s = conn.createStatement()){
            List<Integer> idList=queryToIntList(s,"select empId from ST_MARS UNION select empId from ST_EARTH");
            Set<Integer> idSet=Sets.newHashSet(idList);
            assertEquals(5,idSet.size());
            assertEquals("Expected no duplicates in query result",idList.size(),idSet.size());
        }
    }

    @Test
    public void testUnionNoSort() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs=s.executeQuery("select * from ST_MARS UNION select * from ST_EARTH")){
                assertEquals(8,resultSetSize(rs));
            }
        }
    }

    @Test
    public void testUnionWithSort() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs=s.executeQuery("select * from ST_MARS UNION select * from ST_EARTH order by 1 desc")){
                assertEquals(8,resultSetSize(rs));
            }
        }
    }

    /* Regression test for Bug 373 */
    @Test
    public void testUnionWithWhereClause() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs=s.executeQuery("select * from ST_MARS where empId = 6 UNION select * from ST_EARTH where empId=3")){
                assertEquals(2,resultSetSize(rs));
            }
        }
    }

    /* Regression for Bug 292 */
    @Test
    public void testUnionValuesInSubSelect() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs=s.executeQuery("select empId from ST_MARS where empId in (select empId from ST_EARTH union all values 1)")){
                assertEquals(5,resultSetSize(rs));
            }
        }
    }

    @Test
    public void testValuesFirstInUnionAll() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs=s.executeQuery("values (9,10) union all select a.i, b.i from T2 a, DUPS b union all select b.i, a.i from T2 a, DUPS b")){
                assertEquals(33,resultSetSize(rs));
            }
        }
    }

    @Test
    public void testValuesLastInUnionAll() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs=s.executeQuery(
                    "select a.i, b.i from T2 a, DUPS b union all select b.i, a.i from T2 a, DUPS b union all values (9,10)")){
                assertEquals(33,resultSetSize(rs));
            }
        }
    }

    // 792
    @Test
    public void unionOverScalarAggregate_max() throws Exception {
        try(Statement s= conn.createStatement()){
            List<Integer> maxList=queryToIntList(s,"select max(a.i) from T1 a union select max(b.i) from T1 b");
            assertFalse(maxList.contains(null));
            assertEquals("union should return 1 rows",1,maxList.size());
        }
    }

    // Bug 791
    @Test
    public void unionAllOverScalarAggregate_max() throws Exception {
        try(Statement s= conn.createStatement()){
            try(ResultSet rs=s.executeQuery("select max(a.i) from T1 a UNION ALL select max(b.i) from T1 b")){
                assertEquals("union all should return 2 rows",2,resultSetSize(rs));
            }
        }
    }

    /* bug DB-1304 */
    @Test
    public void unionOverScalarAggregate_count() throws Exception {
        try(Statement s = conn.createStatement()){
            long count2=StatementUtils.onlyLong(s,"select count(*) from empty_table_1 UNION select count(*) from empty_table_2");
            long count3=StatementUtils.onlyLong(s,"select count(*) from empty_table_1 UNION select count(*) from empty_table_2 UNION select count(*) from empty_table_3");
            long count4=StatementUtils.onlyLong(s,"select count(*) from empty_table_1 UNION select count(*) from empty_table_2 UNION select count(*) from empty_table_3 UNION select count(*) from empty_table_4");
            assertEquals("count2 incorrect",0,count2);
            assertEquals("count3 incorrect!",0,count3);
            assertEquals("count4 incorrect!",0,count4);
        }
    }

    /* bug DB-1304 */
    @Test
    public void unionAllOverScalarAggregate_count() throws Exception {
        try(Statement s = conn.createStatement()){
            List<Long> counts=queryToLongList(s,"select count(*) from empty_table_1 UNION ALL select count(*) from empty_table_2");
            assertEquals(Arrays.asList(0L,0L),counts);

            counts=queryToLongList(s,"select count(*) from empty_table_1 UNION ALL select count(*) from empty_table_2 UNION ALL select count(*) from empty_table_3");
            assertEquals(Arrays.asList(0L,0L,0L),counts);

            counts=queryToLongList(s,"select count(*) from empty_table_1 UNION ALL select count(*) from empty_table_2 UNION ALL select count(*) from empty_table_3 UNION ALL select count(*) from empty_table_4");
            assertEquals(Arrays.asList(0L,0L,0L,0L),counts);
        }
    }

    /* bug DB-1304 */
    @Test
    public void unionAllOverScalarAggregate_countNonZero() throws Exception {
        try(Statement s= conn.createStatement()){
            long COUNT1=1+new Random().nextInt(9);
            long COUNT2=1+COUNT1+new Random().nextInt(9);
            insert(s,COUNT1,"insert into empty_table_1 values(100, 200, '')");
            insert(s,COUNT2,"insert into empty_table_4 values(100, 200, '')");

            List<Long> counts=queryToLongList(s,""+
                    "          select count(*) from empty_table_1 "+
                    "UNION ALL select count(*) from empty_table_2 "+
                    "UNION ALL select count(*) from empty_table_3 "+
                    "UNION ALL select count(*) from empty_table_4");
            Collections.sort(counts);

            assertEquals(Arrays.asList(0L,0L,COUNT1,COUNT2),counts);
        }
    }


    // Bug 852
    @Test
    public void testMultipleUnionsInASubSelect() throws Exception {
        try(Statement s = conn.createStatement()){
            List<Integer> actual=queryToIntList(s,
                    "select i from T1 where exists (select i from T2 where T1.i < i union \n"+
                            "select i from T2 where 1 = 0 union select i from T2 where T1.i < i union select\n"+
                            "i from T2 where 1 = 0)"
            );
            Collections.sort(actual);
            assertEquals("Incorrect result contents!",Arrays.asList(1,2),actual);
        }
    }


    /* Regression test #1 for DB-1038 */
    @Test
    public void testUnionDistinctValues() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs=s.executeQuery("values (1,2,3,4) union distinct values (5,6,7,8) union distinct values (9,10,11,12)")){
                int[][] correct=new int[][]{
                        new int[]{9,10,11,12},
                        new int[]{5,6,7,8},
                        new int[]{1,2,3,4}
                };
                Arrays.sort(correct,intArrayComparator);
                int[][] actual=new int[correct.length][];
                int count=0;
                while(rs.next()){
                    int first=rs.getInt(1);
                    int second=rs.getInt(2);
                    int third=rs.getInt(3);
                    int fourth=rs.getInt(4);
                    actual[count]=new int[]{first,second,third,fourth};
                    count++;
                }
                Arrays.sort(actual,intArrayComparator);
                for(int i=0;i<correct.length;i++){
                    assertArrayEquals("Incorrect value!",correct[i],actual[i]);
                }
            }
        }
    }

    /* Regression test #2 for DB-1038 */
    @Test
    public void testUnionValues() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs=s.executeQuery("values (1,2,3,4) union values (5,6,7,8) union values (9,10,11,12)")){
                int[][] correct=new int[][]{
                        new int[]{1,2,3,4},
                        new int[]{5,6,7,8},
                        new int[]{9,10,11,12}
                };
                Arrays.sort(correct,intArrayComparator);
                int[][] actual=new int[correct.length][];
                int count=0;
                while(rs.next()){
                    int first=rs.getInt(1);
                    int second=rs.getInt(2);
                    int third=rs.getInt(3);
                    int fourth=rs.getInt(4);
                    actual[count]=new int[]{first,second,third,fourth};
                    count++;
                }
                Arrays.sort(actual,intArrayComparator);
                for(int i=0;i<correct.length;i++){
                    assertArrayEquals("Incorrect value!",correct[i],actual[i]);
                }
            }
        }
    }


    /* Regression test for DB-1026 */
    @Test
    public void testMultipleUnionValues() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs=s.executeQuery("select distinct * from (values 2.0,2.1,2.2,2.2) v1 order by 1")){
                float[] correct=new float[]{2.0f,2.1f,2.2f};
                float[] actual=new float[correct.length];
                int count=0;
                while(rs.next()){
                    assertTrue("Too many rows returned!",count<correct.length);
                    float n=rs.getFloat(1);
                    actual[count]=n;
                    count++;
                }

                assertArrayEquals("Incorrect values, there should be no rounding error present!",correct,actual,1e-5f);
            }
        }
    }

    // Regression test for DB-2437
    @Test
    public void testValuesUnionQuery() throws Exception {
        try(Statement s = conn.createStatement()){
            try(ResultSet rs = s.executeQuery("values 2 union select a.col1 from empty_table_1 a where 1=0")){
                assertEquals(1,resultSetSize(rs));
            }
        }
    }

    /* ****************************************************************************************************************/
    /*private helper methods*/
    private void insert(Statement s, long times, String sql) throws Exception {
        for (long i = 0; i < times; i++) {
            s.executeUpdate(sql);
        }
    }

    private List<Integer> queryToIntList(Statement s,String query) throws SQLException{
        try(ResultSet rs = s.executeQuery(query)){
            List<Integer> strs = new LinkedList<>();
            while(rs.next()){
                int val=rs.getInt(1);
                if(rs.wasNull())
                    strs.add(null);
                else
                    strs.add(val);
            }
            return strs;
        }
    }

    private List<Long> queryToLongList(Statement s,String query) throws SQLException{
        try(ResultSet rs = s.executeQuery(query)){
            List<Long> strs = new LinkedList<>();
            while(rs.next()){
                long val=rs.getLong(1);
                if(rs.wasNull())
                    strs.add(null);
                else
                    strs.add(val);
            }
            return strs;
        }
    }

    private List<String> queryToStringList(Statement s,String query) throws SQLException{
        try(ResultSet rs = s.executeQuery(query)){
            List<String> strs = new LinkedList<>();
            while(rs.next()){
                String string=rs.getString(1);
                if(rs.wasNull())
                    strs.add(null);
                else
                    strs.add(string);
            }
            return strs;
        }
    }

}
