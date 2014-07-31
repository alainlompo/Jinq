package org.jinq.jpa.transform;

import org.jinq.jpa.MetamodelUtil;
import org.jinq.jpa.jpqlquery.BinaryExpression;
import org.jinq.jpa.jpqlquery.ColumnExpressions;
import org.jinq.jpa.jpqlquery.Expression;
import org.jinq.jpa.jpqlquery.JPQLQuery;
import org.jinq.jpa.jpqlquery.SelectFromWhere;

import ch.epfl.labos.iu.orm.queryll2.path.PathAnalysis;
import ch.epfl.labos.iu.orm.queryll2.path.PathAnalysisSimplifier;
import ch.epfl.labos.iu.orm.queryll2.symbolic.ConstantValue;
import ch.epfl.labos.iu.orm.queryll2.symbolic.TypedValue;
import ch.epfl.labos.iu.orm.queryll2.symbolic.TypedValueVisitorException;

public class WhereTransform extends JPQLQueryTransform
{
   public WhereTransform(MetamodelUtil metamodel)
   {
      super(metamodel);
   }
   
   @Override
   public <U, V> JPQLQuery<U> apply(JPQLQuery<V> query, LambdaInfo where) throws QueryTransformException
   {
      try  {
         if (query.isSelectFromWhere())
         {
            SelectFromWhere<V> sfw = (SelectFromWhere<V>)query;
            SymbExToColumns translator = new SymbExToColumns(metamodel, 
                  new SelectFromWhereLambdaArgumentHandler(sfw, where, metamodel, false));
            Expression methodExpr = null;
            for (int n = 0; n < where.symbolicAnalysis.paths.size(); n++)
            {
               PathAnalysis path = where.symbolicAnalysis.paths.get(n);

               TypedValue returnVal = PathAnalysisSimplifier
                     .simplifyBoolean(path.getReturnValue(), metamodel.comparisonMethods);
               SymbExPassDown returnPassdown = SymbExPassDown.with(null, true);
               ColumnExpressions<?> returnColumns = returnVal.visit(translator, returnPassdown);
               if (!returnColumns.isSingleColumn())
                  throw new QueryTransformException("Expecting single column");
               Expression returnExpr = returnColumns.getOnlyColumn();

               if (returnVal instanceof ConstantValue.BooleanConstant)
               {
                  if (((ConstantValue.BooleanConstant)returnVal).val)
                  {
                     // This path returns true, so it's redundant to actually
                     // put true into the final code.
                     returnExpr = null;
                  }
                  else
                  {
                     // This path returns false, so we can ignore it
                     continue;
                  }
               }
               
               // Handle where path conditions
               Expression conditionExpr = null;
               for (TypedValue cmp: path.getConditions())
               {
                  SymbExPassDown passdown = SymbExPassDown.with(null, true);
                  ColumnExpressions<?> col = cmp.visit(translator, passdown);
                  if (!col.isSingleColumn()) return null;
                  Expression expr = col.getOnlyColumn();
                  if (conditionExpr != null)
                     conditionExpr = new BinaryExpression("AND", conditionExpr, expr);
                  else
                     conditionExpr = expr;
               }
               
               // Merge path conditions and return value to create a value for the path
               Expression pathExpr = returnExpr;
               if (conditionExpr != null)
               {
                  if (pathExpr == null)
                     pathExpr = conditionExpr;
                  else
                     pathExpr = new BinaryExpression("AND", pathExpr, conditionExpr);
               }
               
               // Merge into new expression summarizing the method
               if (methodExpr != null)
                  methodExpr = new BinaryExpression("OR", methodExpr, pathExpr);
               else
                  methodExpr = pathExpr;
            }
            
            // Create the new query, merging in the analysis of the method
            SelectFromWhere<U> toReturn = (SelectFromWhere<U>)sfw.shallowCopy();
            if (sfw.where == null)
               toReturn.where = methodExpr;
            else
               toReturn.where = new BinaryExpression("AND", sfw.where, methodExpr);
            return toReturn;
         }
         throw new QueryTransformException("Existing query cannot be transformed further");
      }
      catch (TypedValueVisitorException e)
      {
         throw new QueryTransformException(e);
      }
   }

}
