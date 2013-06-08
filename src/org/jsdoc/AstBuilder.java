package org.jsdoc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.ast.*;  // we use almost every class

/**
 * Convert a Rhino-style AST to a SpiderMonkey/Esprima-style AST. The new AST is composed of
 * native JavaScript datatypes, so it can be manipulated and traversed with the same code that is
 * used for an AST generated in JavaScript.
 *
 * We handle this conversion in Java because an equivalent JavaScript implementation was more than
 * four times slower (presumably because it required thousands of Java-to-JS-and-back type
 * conversions).
 * @author Jeff Williams <jeffrey.l.williams@gmail.com>
 */
public class AstBuilder
{
	private static final String NODE_ID = HiddenProperties.NODE_ID.getPropertyName();
	private static final String RHINO_NODE = HiddenProperties.RHINO_NODE.getPropertyName();
	private static final String ROOT = HiddenProperties.ROOT.getPropertyName();
	private static final String TYPE = Properties.TYPE.getPropertyName();

	private static Context cx;
	private static ScriptableObject scope;

	private Parser parser;
	private NativeObject ast;
	private AstRoot root;
	private List<Comment> seenComments;

	private enum NodeTypes
	{
		ArrayComprehension,
		ArrayComprehensionLoop,
		ArrayLiteral,
		Assignment,
		AstRoot,
		Block,
		BreakStatement,
		CatchClause,
		Comment,
		ConditionalExpression,
		ContinueStatement,
		DoLoop,
		ElementGet,
		EmptyExpression,
		ExpressionStatement,
		ForInLoop,
		ForLoop,
		FunctionCall,
		FunctionNode,
		IfStatement,
		InfixExpression,
		KeywordLiteral,
		Label,
		LabeledStatement,
		LetNode,
		Name,
		NewExpression,
		NumberLiteral,
		ObjectLiteral,
		ObjectProperty,
		ParenthesizedExpression,
		PropertyGet,
		RegExpLiteral,
		ReturnStatement,
		Scope,
		StringLiteral,
		SwitchCase,
		SwitchStatement,
		ThrowStatement,
		TryStatement,
		UnaryExpression,
		VariableDeclaration,
		VariableInitializer,
		WhileLoop,
		WithStatement,
		Yield;
	}


	public AstBuilder()
	{
		cx = Context.getCurrentContext();
		scope = cx.initStandardObjects();
		reset();
	}

	public void reset()
	{
		parser = null;
		ast = null;
		root = null;
		seenComments = new ArrayList<Comment>();
	}

	public NativeObject getAst()
	{
		return ast;
	}

	public NativeObject build(String sourceCode, String sourceName)
	{
		// Reset the instance's state if necessary
		if (ast != null) {
			reset();
		}

		parser = getParser();

		root = parser.parse(sourceCode, sourceName, 1);

		// ast will be null if there are no syntax nodes
		ast = processNode(root);
		if (ast == null) {
			ast = newObject();
		}

		attachRemainingComments();

		return ast;
	}

	protected static Context getCurrentContext()
	{
		return cx;
	}

	protected static ScriptableObject getCurrentScope()
	{
		return scope;
	}

	protected static NativeObject newObject() {
		return (NativeObject)cx.newObject(scope);
	}

	protected static NativeArray newArray(List<?> list) {
		return (NativeArray)cx.newArray(scope, list.toArray());
	}

	protected static NativeArray newArray(int capacity) {
		return (NativeArray)cx.newArray(scope, capacity);
	}

	private static Parser getParser()
	{
		CompilerEnvirons ce = new CompilerEnvirons();

		ce.setRecordingComments(true);
		ce.setRecordingLocalJsDocComments(true);
		ce.setLanguageVersion(180);
		ce.initFromContext(cx);

		return new Parser(ce, ce.getErrorReporter());
	}

	private NativeArray getRange(AstNode rhinoNode)
	{
		List<Integer> range = new ArrayList<Integer>();

		Integer start = rhinoNode.getAbsolutePosition();
		Integer end = start + rhinoNode.getLength();
		range.add(start);
		range.add(end);

		return newArray(range);
	}

	/**
	 * Provide the node's location in an Esprima-compatible format. Rhino doesn't store
	 * start.column, end.line, or end.column, but fortunately we don't really need them.
	 * @param rhinoNode
	 * @return Esprima-compatible location info.
	 */
	private NativeObject getLocation(AstNode rhinoNode)
	{
		NativeObject loc = newObject();
		NativeObject start = newObject();

		start.put("line", start, rhinoNode.getLineno());
		loc.put("start", loc, start);
		loc.put("end", loc, newObject());

		return loc;
	}

	private Integer getSyntaxStart()
	{
		AstNode node = (AstNode)root.getFirstChild();
		while (node instanceof Comment) {
			node = (AstNode)node.getNext();
		}

		if (node != null) {
			return node.getAbsolutePosition();
		} else {
			// no syntax nodes, just comments (or an empty file)
			return null;
		}
	}

	private boolean isJsDocComment(Comment comment)
	{
		return comment.getCommentType() == Token.CommentType.JSDOC;
	}

	private void attachLeadingComments(AstNode rhinoNode, Entry info)
	{
		List<NativeObject> comments = new ArrayList<NativeObject>();
		Comment comment = rhinoNode.getJsDocNode();
		if (comment != null) {
			seenComments.add(comment);
			comments.add(processNode(comment));
			info.put("leadingComments", newArray(comments));
		};
	}

	@SuppressWarnings("unchecked")
	private void attachRemainingComments()
	{
		NativeObject comment;
		List<Integer> range;
		Integer start;

		Integer syntaxStart = getSyntaxStart();
		List<NativeObject> leadingComments = new ArrayList<NativeObject>();
		List<NativeObject> trailingComments = new ArrayList<NativeObject>();

		Set<Comment> allComments = root.getComments();
		if (allComments == null) {
			return;
		}

		for (Comment commentNode : allComments) {
			if (seenComments.contains(commentNode) == false && isJsDocComment(commentNode)) {
				comment = processNode(commentNode);
				range = (List<Integer>)comment.get("range", comment);
				start = range.get(0);

				if (syntaxStart != null && start < syntaxStart) {
					leadingComments.add(comment);
				} else {
					trailingComments.add(comment);
				}
			}
		}

		if (leadingComments.size() > 0) {
			ast.put("leadingComments", ast, newArray(leadingComments));
		}

		if (trailingComments.size() > 0) {
			ast.put("trailingComments", ast, newArray(trailingComments));
		}
	}

	private NativeObject createNode(Entry info)
	{
		JsDocNode node;
		Integer start;
		Integer end;

		AstNode rhinoNode = (AstNode)info.get(RHINO_NODE);

		NativeArray range = getRange(rhinoNode);
		info.put("range", range);
		info.put("loc", getLocation(rhinoNode));

		attachLeadingComments(rhinoNode, info);

		node = new JsDocNode(info);

		if (this.ast == null && info.get(TYPE) == JsDocNode.PROGRAM) {
			node.put(ROOT, node.getNativeObject());
		} else {
			node.put(ROOT, this.ast);
		}

		return node.getNativeObject();
	}

	private String getRhinoNodeId(Node rhinoNode)
	{
		return "astnode" + rhinoNode.hashCode();
	}

	private NativeArray processNodeList(List<? extends AstNode> nodes)
	{
		List<NativeObject> newNodes = new ArrayList<NativeObject>();
		for (AstNode node : nodes) {
			newNodes.add(processNode(node));
		}

		return newArray(newNodes);
	}

	private NativeArray processNodeChildren(AstNode rhinoNode)
	{
		List<AstNode> kids = new ArrayList<AstNode>();
		Node current = rhinoNode.getFirstChild();
 
		while (current != null) {
			kids.add((AstNode)current);
			current = current.getNext();
		}
 
		return processNodeList(kids);
	}

	private NativeObject processNode(AstNode rhinoNode)
	{
		//System.out.println("new rhino node! shortName: " + rhinoNode.shortName() + ", source: " +
		//	rhinoNode.toSource());

		NativeObject node = null;
		Entry info = new Entry();
		NodeTypes type = NodeTypes.valueOf(rhinoNode.shortName());

		info.put(NODE_ID, getRhinoNodeId(rhinoNode));
		info.put(RHINO_NODE, rhinoNode);

		// surely there's a better way to do this...
		switch (type) {
			case ArrayComprehension:
				processArrayComprehension((ArrayComprehension)rhinoNode, info);
				break;
			case ArrayComprehensionLoop:
				processArrayComprehensionLoop((ArrayComprehensionLoop)rhinoNode, info);
				break;
			case ArrayLiteral:
				processArrayLiteral((ArrayLiteral)rhinoNode, info);
				break;
			case Assignment:
				processAssignment((Assignment)rhinoNode, info);
				break;
			case AstRoot:
				processAstRoot((AstRoot)rhinoNode, info);
				break;
			case Block:
				processBlock((Block)rhinoNode, info);
				break;
			case BreakStatement:
				processBreakStatement((BreakStatement)rhinoNode, info);
				break;
			case CatchClause:
				processCatchClause((CatchClause)rhinoNode, info);
				break;
			case Comment:
				processComment((Comment)rhinoNode, info);
				break;
			case ConditionalExpression:
				processConditionalExpression((ConditionalExpression)rhinoNode, info);
				break;
			case ContinueStatement:
				processContinueStatement((ContinueStatement)rhinoNode, info);
				break;
			case DoLoop:
				processDoLoop((DoLoop)rhinoNode, info);
				break;
			case ElementGet:
				processElementGet((ElementGet)rhinoNode, info);
				break;
			case EmptyExpression:
				processEmptyExpression((EmptyExpression)rhinoNode, info);
				break;
			case ExpressionStatement:
				processExpressionStatement((ExpressionStatement)rhinoNode, info);
				break;
			case ForInLoop:
				processForInLoop((ForInLoop)rhinoNode, info);
				break;
			case ForLoop:
				processForLoop((ForLoop)rhinoNode, info);
				break;
			case FunctionCall:
				processFunctionCall((FunctionCall)rhinoNode, info);
				break;
			case FunctionNode:
				processFunctionNode((FunctionNode)rhinoNode, info);
				break;
			case IfStatement:
				processIfStatement((IfStatement)rhinoNode, info);
				break;
			case InfixExpression:
				processInfixExpression((InfixExpression)rhinoNode, info);
				break;
			case KeywordLiteral:
				processKeywordLiteral((KeywordLiteral)rhinoNode, info);
				break;
			case Label:
				processLabel((Label)rhinoNode, info);
				break;
			case LabeledStatement:
				processLabeledStatement((LabeledStatement)rhinoNode, info);
				break;
			case LetNode:
				processLetNode((LetNode)rhinoNode, info);
				break;
			case Name:
				processName((Name)rhinoNode, info);
				break;
			case NewExpression:
				processNewExpression((NewExpression)rhinoNode, info);
				break;
			case NumberLiteral:
				processNumberLiteral((NumberLiteral)rhinoNode, info);
				break;
			case ObjectLiteral:
				processObjectLiteral((ObjectLiteral)rhinoNode, info);
				break;
			case ObjectProperty:
				processObjectProperty((ObjectProperty)rhinoNode, info);
				break;
			case ParenthesizedExpression:
				// we need the expression, but not the node itself
				ParenthesizedExpression expr = (ParenthesizedExpression)rhinoNode;
				node = processNode(expr.getExpression());
				break;
			case PropertyGet:
				processPropertyGet((PropertyGet)rhinoNode, info);
				break;
			case RegExpLiteral:
				processRegExpLiteral((RegExpLiteral)rhinoNode, info);
				break;
			case ReturnStatement:
				processReturnStatement((ReturnStatement)rhinoNode, info);
				break;
			case Scope:
				processScope((Scope)rhinoNode, info);
				break;
			case StringLiteral:
				processStringLiteral((StringLiteral)rhinoNode, info);
				break;
			case SwitchCase:
				processSwitchCase((SwitchCase)rhinoNode, info);
				break;
			case SwitchStatement:
				processSwitchStatement((SwitchStatement)rhinoNode, info);
				break;
			case ThrowStatement:
				processThrowStatement((ThrowStatement)rhinoNode, info);
				break;
			case TryStatement:
				processTryStatement((TryStatement)rhinoNode, info);
				break;
			case UnaryExpression:
				processUnaryExpression((UnaryExpression)rhinoNode, info);
				break;
			case VariableDeclaration:
				processVariableDeclaration((VariableDeclaration)rhinoNode, info);
				break;
			case VariableInitializer:
				processVariableInitializer((VariableInitializer)rhinoNode, info);
				break;
			case WhileLoop:
				processWhileLoop((WhileLoop)rhinoNode, info);
				break;
			case WithStatement:
				processWithStatement((WithStatement)rhinoNode, info);
				break;
			case Yield:
				processYield((Yield)rhinoNode, info);
				break;
			default:
				throw new IllegalArgumentException("Unrecognized node type " +
					rhinoNode.shortName() + " with source: " + rhinoNode.toSource());
		}

		if (node == null) {
			node = createNode(info);
		}

		return node;
	}

	private void processArrayComprehension(ArrayComprehension rhinoNode, Entry info)
	{
		AstNode filter = rhinoNode.getFilter();

		info.put(TYPE, JsDocNode.COMPREHENSION_EXPRESSION);

		info.put("body", processNode(rhinoNode.getResult()));
		info.put("blocks", processNodeList(rhinoNode.getLoops()));
		info.put("filter", filter == null ? filter : processNode(filter));
	}

	private void processArrayComprehensionLoop(ArrayComprehensionLoop rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.COMPREHENSION_BLOCK);

		info.put("left", processNode(rhinoNode.getIterator()));
		info.put("right", processNode(rhinoNode.getIteratedObject()));
		info.put("each", rhinoNode.isForEach());
	}

	private void processArrayLiteral(ArrayLiteral rhinoNode, Entry info)
	{
		if (rhinoNode.isDestructuring()) {
			info.put(TYPE, JsDocNode.ARRAY_PATTERN);
		} else {
			info.put(TYPE, JsDocNode.ARRAY_EXPRESSION);
		}

		info.put("elements", processNodeList(rhinoNode.getElements()));		
	}

	private void processAssignment(Assignment rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.ASSIGNMENT_EXPRESSION);

		info.put("operator", AstNode.operatorToString(rhinoNode.getOperator()));
		info.put("left", processNode(rhinoNode.getLeft()));
		info.put("right", processNode(rhinoNode.getRight()));
	}

	private void processAstRoot(AstRoot rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.PROGRAM);

		info.put("body", processNodeChildren((AstNode)rhinoNode));
	}

	private void processBlock(Block rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.BLOCK_STATEMENT);

		info.put("body", processNodeChildren((AstNode)rhinoNode));
	}

	private void processBreakStatement(BreakStatement rhinoNode, Entry info)
	{
		AstNode label = rhinoNode.getBreakLabel();

		info.put(TYPE, JsDocNode.BREAK_STATEMENT);

		info.put("label", label == null ? label : processNode(label));
	}

	private void processCatchClause(CatchClause rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.CATCH_CLAUSE);

		info.put("param", processNode(rhinoNode.getVarName()));
		info.put("body", processNode(rhinoNode.getBody()));
	}

	private void processComment(Comment rhinoNode, Entry info)
	{
		String comment = rhinoNode.getValue();
		info.put(TYPE, JsDocNode.BLOCK);

		// Esprima provides the comment value without delimiters, so we do too
		info.put("value", comment.substring(2, comment.length() - 2));
		// Esprima doesn't provide this, but it's useful
		info.put("raw", rhinoNode.getValue());
	}

	private void processConditionalExpression(ConditionalExpression rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.CONDITIONAL_EXPRESSION);

		info.put("test", processNode(rhinoNode.getTestExpression()));
		info.put("consequent", processNode(rhinoNode.getTrueExpression()));
		info.put("alternate", processNode(rhinoNode.getFalseExpression()));
	}

	private void processContinueStatement(ContinueStatement rhinoNode, Entry info)
	{
		AstNode label = rhinoNode.getLabel();

		info.put(TYPE, JsDocNode.CONTINUE_STATEMENT);

		info.put("label", label == null ? label : processNode(label));
	}

	private void processDoLoop(DoLoop rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.DO_WHILE_STATEMENT);

		info.put("body", processNode(rhinoNode.getBody()));
		info.put("test", processNode(rhinoNode.getCondition()));
	}

	private void processElementGet(ElementGet rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.MEMBER_EXPRESSION);

		info.put("computed", true);
		info.put("object", processNode(rhinoNode.getTarget()));
		info.put("property", processNode(rhinoNode.getElement()));
	}

	private void processEmptyExpression(EmptyExpression rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.EMPTY_STATEMENT);
	}

	private void processExpressionStatement(ExpressionStatement rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.EXPRESSION_STATEMENT);

		info.put("expression", processNode(rhinoNode.getExpression()));
	}

	private void processForInLoop(ForInLoop rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.FOR_IN_STATEMENT);

		info.put("left", processNode(rhinoNode.getIterator()));
		info.put("right", processNode(rhinoNode.getIteratedObject()));
		info.put("body", processNode(rhinoNode.getBody()));
		info.put("each", rhinoNode.isForEach());
	}

	private void processForLoop(ForLoop rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.FOR_STATEMENT);

		info.put("init", processNode(rhinoNode.getInitializer()));
		info.put("test", processNode(rhinoNode.getCondition()));
		info.put("update", processNode(rhinoNode.getIncrement()));
		info.put("body", processNode(rhinoNode.getBody()));
	}

	private void processFunctionCall(FunctionCall rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.CALL_EXPRESSION);

		info.put("callee", processNode(rhinoNode.getTarget()));
		info.put("arguments", processNodeList(rhinoNode.getArguments()));
	}

	private void processFunctionNode(FunctionNode rhinoNode, Entry info)
	{
		AstNode id = rhinoNode.getFunctionName();

		info.put(TYPE, (rhinoNode.getFunctionType() == FunctionNode.FUNCTION_EXPRESSION) ?
			JsDocNode.FUNCTION_EXPRESSION : JsDocNode.FUNCTION_DECLARATION);

		info.put("id", id == null ? id : processNode(id));
		info.put("params", processNodeList(rhinoNode.getParams()));
		info.put("defaults", newArray(0));
		info.put("body", processNode(rhinoNode.getBody()));
		info.put("rest", null);
		info.put("generator", rhinoNode.isGenerator());
		info.put("expression", rhinoNode.isExpressionClosure());
	}

	private void processIfStatement(IfStatement rhinoNode, Entry info)
	{
		AstNode alternate = rhinoNode.getElsePart();

		info.put(TYPE, JsDocNode.IF_STATEMENT);

		info.put("test", processNode(rhinoNode.getCondition()));
		info.put("consequent", processNode(rhinoNode.getThenPart()));
		info.put("alternate", alternate == null ? alternate : processNode(alternate));
	}

	private void processInfixExpression(InfixExpression rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.BINARY_EXPRESSION);

		info.put("operator", AstNode.operatorToString(rhinoNode.getOperator()));
		info.put("left", processNode(rhinoNode.getLeft()));
		info.put("right", processNode(rhinoNode.getRight()));
	}

	private void processKeywordLiteral(KeywordLiteral rhinoNode, Entry info)
	{
		int tokenType = rhinoNode.getType();
		String type = null;

		switch (tokenType) {
			case Token.TRUE:
				info.put("value", true);
				info.put("raw", "true");
				break;
			case Token.FALSE:
				info.put("value", false);
				info.put("raw", "false");
				break;
			case Token.NULL:
				info.put("value", null);
				info.put("raw", "null");
				break;
			case Token.DEBUGGER:
				type = JsDocNode.DEBUGGER_STATEMENT;
				break;
			case Token.THIS:
				type = JsDocNode.THIS_EXPRESSION;
				break;
			default:
				throw new IllegalArgumentException("Unrecognized KeywordLiteral: " +
					rhinoNode.toSource() + " (token type: Token." + Token.typeToName(tokenType) +
					")");
		}

		if (type == null) {
			type = JsDocNode.LITERAL;
		}

		info.put(TYPE, type);
	}

	private void processLabel(Label rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.IDENTIFIER);

		info.put("name", rhinoNode.getName());
	}

	private void processLabeledStatement(LabeledStatement rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.LABELED_STATEMENT);

		// does Rhino ever think that a node has multiple labels? if so, this may not work correctly
		List<Label> labels = rhinoNode.getLabels();
		info.put("label", processNode(labels.get(labels.size() - 1)));
		info.put("body", processNode(rhinoNode.getStatement()));
	}

	private void processLetNode(LetNode rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.LET_STATEMENT);

		info.put("head", processNode(rhinoNode.getVariables()));
		info.put("body", processNode(rhinoNode.getBody()));
	}

	private void processName(Name rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.IDENTIFIER);

		info.put("name", rhinoNode.getIdentifier());
	}

	private void processNewExpression(NewExpression rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.NEW_EXPRESSION);

		info.put("callee", processNode(rhinoNode.getTarget()));
		info.put("arguments", processNodeList(rhinoNode.getArguments()));
	}

	private void processNumberLiteral(NumberLiteral rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.LITERAL);

		info.put("value", rhinoNode.getNumber());
		info.put("raw", rhinoNode.getValue());
	}

	private void processObjectLiteral(ObjectLiteral rhinoNode, Entry info)
	{
		if (rhinoNode.isDestructuring()) {
			info.put(TYPE, JsDocNode.OBJECT_PATTERN);
		} else {
			info.put(TYPE, JsDocNode.OBJECT_EXPRESSION);
		}

		info.put("properties", processNodeList(rhinoNode.getElements()));
	}

	private void processObjectProperty(ObjectProperty rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.PROPERTY);

		info.put("key", processNode(rhinoNode.getLeft()));
		info.put("value", processNode(rhinoNode.getRight()));
		info.put("kind",
			rhinoNode.isGetter() ? "get" :
			rhinoNode.isSetter() ? "set" :
			"init");
	}

	private void processPropertyGet(PropertyGet rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.MEMBER_EXPRESSION);

		info.put("computed", false);
		info.put("object", processNode(rhinoNode.getTarget()));
		info.put("property", processNode(rhinoNode.getProperty()));
	}

	private void processRegExpLiteral(RegExpLiteral rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.LITERAL);

		String value = rhinoNode.toSource(0);
		info.put("value", value);
		info.put("raw", value);
	}

	private void processReturnStatement(ReturnStatement rhinoNode, Entry info)
	{
		AstNode argument = rhinoNode.getReturnValue();

		info.put(TYPE, JsDocNode.RETURN_STATEMENT);

		info.put("argument", argument == null ? argument : processNode(argument));
	}

	private void processScope(Scope rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.BLOCK_STATEMENT);

		info.put("body", processNodeChildren(rhinoNode));
	}

	private void processStringLiteral(StringLiteral rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.LITERAL);

		info.put("value", rhinoNode.getValue(false));
		info.put("raw", rhinoNode.getValue(true));
	}

	private void processSwitchCase(SwitchCase rhinoNode, Entry info)
	{
		AstNode test = rhinoNode.getExpression();

		NativeArray consequent;
		List<AstNode> statements = rhinoNode.getStatements();
		if (statements == null) {
			consequent = newArray(0);
		} else {
			consequent = processNodeList(statements);
		}

		info.put(TYPE, JsDocNode.SWITCH_CASE);

		info.put("test", test == null ? test : processNode(test));
		info.put("consequent", consequent);
	}

	private void processSwitchStatement(SwitchStatement rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.SWITCH_STATEMENT);

		info.put("discriminant", processNode(rhinoNode.getExpression()));
		info.put("cases", processNodeList(rhinoNode.getCases()));
		// omitting the "lexical" property for now, as Rhino doesn't seem to provide it
	}

	private void processThrowStatement(ThrowStatement rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.THROW_STATEMENT);

		info.put("argument", processNode(rhinoNode.getExpression()));
	}

	private void processTryStatement(TryStatement rhinoNode, Entry info)
	{
		CatchClause current;
		AstNode finalizer = rhinoNode.getFinallyBlock();

		List<AstNode> handlers = new ArrayList<AstNode>();
		List<CatchClause> catchClauses = rhinoNode.getCatchClauses();
		List<AstNode> guardedHandlers = new ArrayList<AstNode>();
		Iterator<CatchClause> iterator = catchClauses.iterator();

		while (iterator.hasNext()) {
			current = iterator.next();
			if (current.getIfPosition() == -1) {
				handlers.add(current);
				iterator.remove();
			} else {
				guardedHandlers.add(current);
			}
		}

		info.put(TYPE, JsDocNode.TRY_STATEMENT);

		info.put("block", processNode(rhinoNode.getTryBlock()));
		// Esprima uses an array, so we do too
		info.put("handlers", processNodeList(handlers));
		info.put("guardedHandlers", processNodeList(guardedHandlers));
		info.put("finalizer", finalizer == null ? finalizer : processNode(finalizer));
	}

	private void processUnaryExpression(UnaryExpression rhinoNode, Entry info)
	{
		int op = rhinoNode.getOperator();
		String opString = null;

		if (op == Token.INC || op == Token.DEC) {
			info.put(TYPE, JsDocNode.UPDATE_EXPRESSION);
			info.put("prefix", rhinoNode.isPrefix());
		} else {
			info.put(TYPE, JsDocNode.UNARY_EXPRESSION);
		}

		// work around a bug in AstNode.operatorToString()
		if (op == Token.VOID) {
			opString = "void";
		} else {
			opString = AstNode.operatorToString(op);
		}

		info.put("operator", opString);
		info.put("argument", processNode(rhinoNode.getOperand()));
	}

	private void processVariableDeclaration(VariableDeclaration rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.VARIABLE_DECLARATION);

		info.put("declarations", processNodeList(rhinoNode.getVariables()));
		info.put("kind", Token.typeToName(rhinoNode.getType()).toLowerCase());
	}

	private void processVariableInitializer(VariableInitializer rhinoNode, Entry info)
	{
		AstNode initializer = rhinoNode.getInitializer();

		info.put(TYPE, JsDocNode.VARIABLE_DECLARATOR);

		info.put("id", processNode(rhinoNode.getTarget()));
		info.put("init", initializer == null ? initializer : processNode(initializer));
	}

	private void processWhileLoop(WhileLoop rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.WHILE_STATEMENT);

		info.put("test", processNode(rhinoNode.getCondition()));
		info.put("body", processNode(rhinoNode.getBody()));
	}

	private void processWithStatement(WithStatement rhinoNode, Entry info)
	{
		info.put(TYPE, JsDocNode.WITH_STATEMENT);

		info.put("object", processNode(rhinoNode.getExpression()));
		info.put("body", processNode(rhinoNode.getStatement()));
	}

	private void processYield(Yield rhinoNode, Entry info)
	{
		AstNode argument = rhinoNode.getValue();

		info.put(TYPE, JsDocNode.YIELD_EXPRESSION);

		info.put("argument", argument == null ? argument : processNode(argument));
	}


	enum Properties
	{
		TYPE ("type");

		private String propertyName;

		private Properties(String propertyName)
		{
			this.propertyName = propertyName;
		}

		public String getPropertyName()
		{
			return propertyName;
		}
	}

	enum HiddenProperties
	{
		RHINO_NODE ("rhinoNode"),
		NODE_ID ("nodeId"),
		ROOT ("root");

		private String propertyName;

		private HiddenProperties(String propertyName)
		{
			this.propertyName = propertyName;
		}

		public String getPropertyName()
		{
			return propertyName;
		}
	}

	class JsDocNode
	{
		// node types
		public static final String
			ARRAY_EXPRESSION = "ArrayExpression",
			ARRAY_PATTERN = "ArrayPattern",
			ASSIGNMENT_EXPRESSION = "AssignmentExpression",
			BINARY_EXPRESSION = "BinaryExpression",
			BLOCK = "Block", // for block comments
			BLOCK_STATEMENT = "BlockStatement",
			BREAK_STATEMENT = "BreakStatement",
			CALL_EXPRESSION = "CallExpression",
			CATCH_CLAUSE = "CatchClause",
			COMPREHENSION_BLOCK = "ComprehensionBlock",
			COMPREHENSION_EXPRESSION = "ComprehensionExpression",
			CONDITIONAL_EXPRESSION = "ConditionalExpression",
			CONTINUE_STATEMENT = "ContinueStatement",
			DEBUGGER_STATEMENT = "DebuggerStatement",
			DO_WHILE_STATEMENT = "DoWhileStatement",
			EMPTY_STATEMENT = "EmptyStatement",
			EXPRESSION_STATEMENT = "ExpressionStatement",
			FOR_IN_STATEMENT = "ForInStatement",
			FOR_OF_STATEMENT = "ForOfStatement",
			FOR_STATEMENT = "ForStatement",
			FUNCTION_DECLARATION = "FunctionDeclaration",
			FUNCTION_EXPRESSION = "FunctionExpression",
			IDENTIFIER = "Identifier",
			IF_STATEMENT = "IfStatement",
			LABELED_STATEMENT = "LabeledStatement",
			LET_STATEMENT = "LetStatement",
			LITERAL = "Literal",
			LOGICAL_EXPRESSION = "LogicalExpression",
			MEMBER_EXPRESSION = "MemberExpression",
			NEW_EXPRESSION = "NewExpression",
			OBJECT_EXPRESSION = "ObjectExpression",
			OBJECT_PATTERN = "ObjectPattern",
			PROGRAM = "Program",
			PROPERTY = "Property",
			RETURN_STATEMENT = "ReturnStatement",
			SEQUENCE_EXPRESSION = "SequenceExpression",
			SWITCH_CASE = "SwitchCase",
			SWITCH_STATEMENT = "SwitchStatement",
			THIS_EXPRESSION = "ThisExpression",
			THROW_STATEMENT = "ThrowStatement",
			TRY_STATEMENT = "TryStatement",
			UNARY_EXPRESSION = "UnaryExpression",
			UPDATE_EXPRESSION = "UpdateExpression",
			VARIABLE_DECLARATION = "VariableDeclaration",
			VARIABLE_DECLARATOR = "VariableDeclarator",
			WHILE_STATEMENT = "WhileStatement",
			WITH_STATEMENT = "WithStatement",
			YIELD_EXPRESSION = "YieldExpression";

		private static final int DONTENUM = ScriptableObject.DONTENUM;
		private static final int EMPTY = ScriptableObject.EMPTY;

		private final Object UNDEFINED = Undefined.instance;

		private NativeObject node;

		public JsDocNode()
		{
			node = AstBuilder.newObject();

			for (Properties prop : Properties.values()) {
				node.defineProperty(prop.getPropertyName(), UNDEFINED, EMPTY);
			}

			for (HiddenProperties hiddenProp : HiddenProperties.values()) {
				node.defineProperty(hiddenProp.getPropertyName(), UNDEFINED, DONTENUM);
			}
		}

		public JsDocNode(Entry info)
		{
			this();
			
			Set<Map.Entry<Object, Object>> entrySet = info.entrySet();
			for (Map.Entry<Object, Object> item : entrySet) {
				node.put((String)item.getKey(), node, item.getValue());
			}
		}

		public final Object get(String key)
		{
			return node.get(key, node);
		}

		public final void put(String key, Object value)
		{
			node.put(key, node, value);
		}

		public final JsDocNode getRoot()
		{
			return (JsDocNode)get(HiddenProperties.ROOT.getPropertyName());
		}

		public final NativeObject getNativeObject()
		{
			return node;
		}
	}

	class Entry extends HashMap<Object, Object>
	{
		// just for convenience
	}
}
