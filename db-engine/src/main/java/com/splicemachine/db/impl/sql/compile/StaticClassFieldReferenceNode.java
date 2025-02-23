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

package com.splicemachine.db.impl.sql.compile;

import com.splicemachine.db.iapi.services.compiler.MethodBuilder;

import com.splicemachine.db.iapi.services.sanity.SanityManager;

import com.splicemachine.db.iapi.error.StandardException;

import com.splicemachine.db.iapi.reference.SQLState;

import com.splicemachine.db.iapi.services.loader.ClassInspector;

import com.splicemachine.db.iapi.store.access.Qualifier;

import com.splicemachine.db.iapi.sql.compile.CompilerContext;

import com.splicemachine.db.iapi.util.JBitSet;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * A StaticClassFieldReferenceNode represents a Java static field reference from 
 * a Class (as opposed to an Object).  Field references can be 
 * made in DML (as expressions).
 *
 */

public final class StaticClassFieldReferenceNode extends JavaValueNode {
	/*
	** Name of the field.
	*/
	private String	fieldName;

	/* The class name */
	private String	javaClassName;
	private boolean classNameDelimitedIdentifier;

	/**
		The field we are going to access.
	*/
	private Member			field;

	/**
	 * Initializer for a StaticClassFieldReferenceNode
	 *
	 * @param	javaClassName	The class name
	 * @param	fieldName		The field name
	 */
	public void init(Object javaClassName, Object fieldName, Object classNameDelimitedIdentifier)
	{
		this.fieldName = (String) fieldName;
		this.javaClassName = (String) javaClassName;
		this.classNameDelimitedIdentifier = ((Boolean) classNameDelimitedIdentifier).booleanValue();
	}

	/**
	 * Bind this expression.  This means binding the sub-expressions,
	 * as well as figuring out what the return type is for this expression.
	 *
	 * @param fromList        The FROM list for the query this
	 *				expression is in, for binding columns.
	 * @param subqueryList        The subquery list being built as we find SubqueryNodes
	 * @param aggregateVector    The aggregate vector being built as we find AggregateNodes
	 *
	 * @return	Nothing
	 *
	 * @exception StandardException		Thrown on error
	 */

	public JavaValueNode bindExpression(FromList fromList,
                                        SubqueryList subqueryList,
                                        List<AggregateNode> aggregateVector) throws StandardException {
		ClassInspector classInspector = getClassFactory().getClassInspector();


//		if (((getCompilerContext().getReliability() & CompilerContext.INTERNAL_SQL_ILLEGAL) != 0)
//			|| !javaClassName.startsWith("java.sql.")) {
//
//			throw StandardException.newException(SQLState.LANG_SYNTAX_ERROR, javaClassName + "::" + fieldName);
//		}

		verifyClassExist(javaClassName);

		/*
		** Find the field that is public.
		*/
		field = classInspector.findPublicField(javaClassName,
										fieldName,
										true);
		/* Get the field type */
	 	setJavaTypeName( classInspector.getType(field) );

		return this;

	}

	/**
	 * Preprocess an expression tree.  We do a number of transformations
	 * here (including subqueries, IN lists, LIKE and BETWEEN) plus
	 * subquery flattening.
	 * NOTE: This is done before the outer ResultSetNode is preprocessed.
	 *
	 * @param	numTables			Number of tables in the DML Statement
	 * @param	outerFromList		FromList from outer query block
	 * @param	outerSubqueryList	SubqueryList from outer query block
	 * @param	outerPredicateList	PredicateList from outer query block
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void preprocess(int numTables,
							FromList outerFromList,
							SubqueryList outerSubqueryList,
							PredicateList outerPredicateList) 
					throws StandardException
	{
	}

	/**
	 * Categorize this predicate.  Initially, this means
	 * building a bit map of the referenced tables for each predicate.
	 * If the source of this ColumnReference (at the next underlying level) 
	 * is not a ColumnReference or a VirtualColumnNode then this predicate
	 * will not be pushed down.
	 *
	 * For example, in:
	 *		select * from (select 1 from s) a (x) where x = 1
	 * we will not push down x = 1.
	 * NOTE: It would be easy to handle the case of a constant, but if the
	 * inner SELECT returns an arbitrary expression, then we would have to copy
	 * that tree into the pushed predicate, and that tree could contain
	 * subqueries and method calls.
	 * RESOLVE - revisit this issue once we have views.
	 *
	 * @param referencedTabs	JBitSet with bit map of referenced FromTables
	 * @param simplePredsOnly	Whether or not to consider method
	 *							calls, field references and conditional nodes
	 *							when building bit map
	 *
	 * @return boolean		Whether or not source.expression is a ColumnReference
	 *						or a VirtualColumnNode.
	 */
	public boolean categorize(JBitSet referencedTabs, boolean simplePredsOnly)
	{
		return true;
	}

	/**
	 * Remap all ColumnReferences in this tree to be clones of the
	 * underlying expression.
	 *
	 * @return JavaValueNode			The remapped expression tree.
	 *
	 * @exception StandardException			Thrown on error
	 */
	public JavaValueNode remapColumnReferencesToExpressions()
		throws StandardException
	{
		return this;
	}

	/**
	 * Return the variant type for the underlying expression.
	 * The variant type can be:
	 *		VARIANT				- variant within a scan
	 *							  (method calls and non-static field access)
	 *		SCAN_INVARIANT		- invariant within a scan
	 *							  (column references from outer tables)
	 *		QUERY_INVARIANT		- invariant within the life of a query
	 *		CONSTANT			- constant
	 *
	 * @return	The variant type for the underlying expression.
	 */
	protected int getOrderableVariantType()
	{
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(field != null,
					"field is expected to be non-null");
		}
		/* Static field references are invariant for the life 
		 * of the query, non-static are variant.
		 */
		if (Modifier.isFinal(field.getModifiers()))
		{
			return Qualifier.CONSTANT;
		}
		else
		{
			return Qualifier.VARIANT;
		}
	}

	/**
	 * @see QueryTreeNode#generate
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void generateExpression(ExpressionClassBuilder acb,
											MethodBuilder mb)
	throws StandardException
	{
		/*
		** Generate the following:
		**
		** <javaClassName>.<field name>
		*/

		mb.getStaticField(field.getDeclaringClass().getName(),
								 fieldName,
								 getJavaTypeName());
	}

}
