package sqlancer.duckdb.test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.QueryAdapter;
import sqlancer.Randomly;
import sqlancer.TestOracle;
import sqlancer.ast.newast.NewAliasNode;
import sqlancer.ast.newast.NewFunctionNode;
import sqlancer.ast.newast.NewUnaryPostfixOperatorNode;
import sqlancer.ast.newast.NewUnaryPrefixOperatorNode;
import sqlancer.ast.newast.Node;
import sqlancer.duckdb.DuckDBErrors;
import sqlancer.duckdb.DuckDBProvider.DuckDBGlobalState;
import sqlancer.duckdb.DuckDBSchema.DuckDBCompositeDataType;
import sqlancer.duckdb.DuckDBSchema.DuckDBDataType;
import sqlancer.duckdb.DuckDBToStringVisitor;
import sqlancer.duckdb.ast.DuckDBExpression;
import sqlancer.duckdb.ast.DuckDBSelect;
import sqlancer.duckdb.gen.DuckDBExpressionGenerator.DuckDBAggregateFunction;
import sqlancer.duckdb.gen.DuckDBExpressionGenerator.DuckDBCastOperation;
import sqlancer.duckdb.gen.DuckDBExpressionGenerator.DuckDBUnaryPostfixOperator;
import sqlancer.duckdb.gen.DuckDBExpressionGenerator.DuckDBUnaryPrefixOperator;

public class DuckDBQueryPartitioningAggregateTester extends DuckDBQueryPartitioningBase implements TestOracle {

	private String firstResult;
	private String secondResult;
	private String originalQuery;
	private String metamorphicQuery;

	public DuckDBQueryPartitioningAggregateTester(DuckDBGlobalState state) {
		super(state);
		DuckDBErrors.addGroupByErrors(errors);
	}

	public void check() throws SQLException {
		super.check();
		DuckDBAggregateFunction aggregateFunction = Randomly.fromOptions(DuckDBAggregateFunction.MAX,
				DuckDBAggregateFunction.MIN, DuckDBAggregateFunction.SUM,
				DuckDBAggregateFunction.COUNT/*, DuckDBAggregateFunction.AVG https://github.com/cwida/duckdb/issues/543 */);
		NewFunctionNode<DuckDBExpression, DuckDBAggregateFunction> aggregate = gen.generateArgsForAggregate(aggregateFunction);
		List<Node<DuckDBExpression>> fetchColumns = new ArrayList<>();
		fetchColumns.add(aggregate);
		while (Randomly.getBooleanWithRatherLowProbability()) {
			fetchColumns.add(gen.generateAggregate());
		}
		select.setFetchColumns(Arrays.asList(aggregate));
		if (Randomly.getBooleanWithRatherLowProbability()) {
			select.setOrderByExpressions(gen.generateOrderBys());
		}
		originalQuery = DuckDBToStringVisitor.asString(select);
		firstResult = getAggregateResult(originalQuery);
		metamorphicQuery = createMetamorphicUnionQuery(select, aggregate, select.getFromList());
		secondResult = getAggregateResult(metamorphicQuery);

		state.getState().queryString = "--" + originalQuery + ";\n--" + metamorphicQuery + "\n-- " + firstResult
				+ "\n-- " + secondResult;
		if (firstResult == null && secondResult != null
				|| firstResult != null && (!firstResult.contentEquals(secondResult)
						&& !DatabaseProvider.isEqualDouble(firstResult, secondResult))) {
			if (secondResult.contains("Inf")) {
				throw new IgnoreMeException(); // FIXME: average computation
			}
			throw new AssertionError();
		}

	}

	private String createMetamorphicUnionQuery(DuckDBSelect select, NewFunctionNode<DuckDBExpression, DuckDBAggregateFunction> aggregate,
			List<Node<DuckDBExpression>> from) {
		String metamorphicQuery;
		Node<DuckDBExpression> whereClause = gen.generateExpression();
		Node<DuckDBExpression> negatedClause = new NewUnaryPrefixOperatorNode<DuckDBExpression>(whereClause, DuckDBUnaryPrefixOperator.NOT);
		Node<DuckDBExpression> notNullClause = new NewUnaryPostfixOperatorNode<DuckDBExpression>(whereClause, DuckDBUnaryPostfixOperator.IS_NULL);
		List<Node<DuckDBExpression>> mappedAggregate = mapped(aggregate);
		DuckDBSelect leftSelect = getSelect(mappedAggregate, from, whereClause, select.getJoinList());
		DuckDBSelect middleSelect = getSelect(mappedAggregate, from, negatedClause, select.getJoinList());
		DuckDBSelect rightSelect = getSelect(mappedAggregate, from, notNullClause, select.getJoinList());
		metamorphicQuery = "SELECT " + getOuterAggregateFunction(aggregate).toString() + " FROM (";
		metamorphicQuery += DuckDBToStringVisitor.asString(leftSelect) + " UNION ALL "
				+ DuckDBToStringVisitor.asString(middleSelect) + " UNION ALL " + DuckDBToStringVisitor.asString(rightSelect);
		metamorphicQuery += ") as asdf";
		return metamorphicQuery;
	}

	private String getAggregateResult(String queryString) throws SQLException {
		String resultString;
		QueryAdapter q = new QueryAdapter(queryString, errors);
		try (ResultSet result = q.executeAndGet(state.getConnection())) {
			if (result == null) {
				throw new IgnoreMeException();
			}
			if (!result.next()) {
				resultString = null;
			} else {
				resultString = result.getString(1);
			}
			return resultString;
		} catch (SQLException e) {
			if (!e.getMessage().contains("Not implemented type")) {
				throw new AssertionError(queryString, e);
			} else {
				throw new IgnoreMeException();
			}
		}
	}

	private List<Node<DuckDBExpression>> mapped(NewFunctionNode<DuckDBExpression, DuckDBAggregateFunction> aggregate) {
		DuckDBCastOperation count;
		switch (aggregate.getFunc()) {
		case COUNT:
		case MAX:
		case MIN:
		case SUM:
			return aliasArgs(Arrays.asList(aggregate));
		case AVG:
			NewFunctionNode<DuckDBExpression, DuckDBAggregateFunction> sum = new NewFunctionNode<DuckDBExpression, DuckDBAggregateFunction>(aggregate.getArgs(), DuckDBAggregateFunction.SUM);
			count = new DuckDBCastOperation(
					new NewFunctionNode<DuckDBExpression, DuckDBAggregateFunction>(aggregate.getArgs(), DuckDBAggregateFunction.COUNT),
					new DuckDBCompositeDataType(DuckDBDataType.FLOAT, 64));
			return aliasArgs(Arrays.asList(sum, count));
		default:
			throw new AssertionError(aggregate.getFunc());
		}
	}

	private List<Node<DuckDBExpression>> aliasArgs(List<Node<DuckDBExpression>> originalAggregateArgs) {
		List<Node<DuckDBExpression>> args = new ArrayList<>();
		int i = 0;
		for (Node<DuckDBExpression> expr : originalAggregateArgs) {
			args.add(new NewAliasNode<DuckDBExpression>(expr, "agg" + i++));
		}
		return args;
	}

	private String getOuterAggregateFunction(NewFunctionNode<DuckDBExpression, DuckDBAggregateFunction> aggregate) {
		switch (aggregate.getFunc()) {
		case AVG:
			return "SUM(agg0::FLOAT)/SUM(agg1)::FLOAT";
		case COUNT:
			return DuckDBAggregateFunction.SUM.toString() + "(agg0)";
		default:
			return aggregate.getFunc().toString() + "(agg0)";
		}
	}

	private DuckDBSelect getSelect(List<Node<DuckDBExpression>> aggregates, List<Node<DuckDBExpression>> from,
			Node<DuckDBExpression> whereClause, List<Node<DuckDBExpression>> joinList) {
		DuckDBSelect leftSelect = new DuckDBSelect();
		leftSelect.setFetchColumns(aggregates);
		leftSelect.setFromList(from);
		leftSelect.setWhereClause(whereClause);
		leftSelect.setJoinList(joinList);
		if (Randomly.getBooleanWithSmallProbability()) {
			leftSelect.setGroupByExpressions(gen.generateExpressions(Randomly.smallNumber() + 1));
		}
		return leftSelect;
	}

}