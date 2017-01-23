package com.soywiz.korte

import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.util.*

interface ExprNode : Dynamic.Context {
	suspend fun eval(context: Template.EvalContext): Any?

	data class VAR(val name: String) : ExprNode {
		override suspend fun eval(context: Template.EvalContext): Any? {
			return context.scope.get(name)
		}
	}

	data class LIT(val value: Any?) : ExprNode {
		override suspend fun eval(context: Template.EvalContext): Any? = value
	}

	data class ARRAY_LIT(val items: List<ExprNode>) : ExprNode {
		override suspend fun eval(context: Template.EvalContext): Any? {
			return items.map { it.eval(context) }
		}
	}

	data class OBJECT_LIT(val items: List<Pair<ExprNode, ExprNode>>) : ExprNode {
		override suspend fun eval(context: Template.EvalContext): Any? {
			return items.map { it.first.eval(context) to it.second.eval(context) }.toMap()
		}
	}

	data class FILTER(val name: String, val expr: ExprNode, val params: List<ExprNode>) : ExprNode {
		override suspend fun eval(context: Template.EvalContext): Any? {
			val filter = context.config.filters[name] ?: invalidOp("Unknown filter '$name'")
			return filter.eval(this, expr.eval(context), params.map { it.eval(context) })
		}
	}

	data class ACCESS(val expr: ExprNode, val name: ExprNode) : ExprNode {
		override suspend fun eval(context: Template.EvalContext): Any? {
			val obj = expr.eval(context)
			val key = name.eval(context)
			return try {
				obj.dynamicGet(key)
			} catch (t: Throwable) {
				try {
					obj.dynamicCallMethod(key)
				} catch (t: Throwable) {
					null
				}
			}
		}
	}

	data class CALL(val method: ExprNode, val args: List<ExprNode>) : ExprNode {
		override suspend fun eval(context: Template.EvalContext): Any? {
			val processedArgs = args.map { it.eval(context) }.toTypedArray()
			return if (method !is ExprNode.ACCESS) {
				method.eval(context).dynamicCall(*processedArgs)
			} else {
				method.expr.eval(context).dynamicCallMethod(method.name.eval(context), *processedArgs)
			}
		}
	}

	data class BINOP(val l: ExprNode, val r: ExprNode, val op: String) : ExprNode {
		override suspend fun eval(context: Template.EvalContext): Any? {
			val lr = l.eval(context)
			val rr = r.eval(context)
			return when (op) {
				"~" -> lr.toDynamicString() + rr.toDynamicString()
				else -> Dynamic.binop(lr, rr, op)
			}
		}
	}

	data class UNOP(val r: ExprNode, val op: String) : ExprNode {
		override suspend fun eval(context: Template.EvalContext): Any? {
			return when (op) {
				"", "+" -> r.eval(context)
				else -> Dynamic.unop(r.eval(context), op)
			}
		}
	}

	companion object {
		fun parse(str: String): ExprNode {
			val tokens = ExprNode.Token.Companion.tokenize(str)
			return ExprNode.parseFullExpr(tokens)
		}

		fun parseId(r: ListReader<Token>): String {
			return r.read().text
		}

		fun expect(r: ListReader<Token>, vararg tokens: String) {
			val token = r.read()
			if (token.text !in tokens) invalidOp("Expected ${tokens.joinToString(", ")} but found $token")
		}

		fun parseFullExpr(r: ListReader<Token>): ExprNode {
			val result = ExprNode.parseExpr(r)
			if (r.hasMore && r.peek() !is ExprNode.Token.TEnd) {
				invalidOp("Expected expression at " + r.peek() + " :: " + r.list.map { it.text }.joinToString(""))
			}
			return result
		}

		private val BINOPS_PRIORITIES_LIST = listOf(
			listOf("*", "/", "%"),
			listOf("+", "-", "~"),
			listOf("==", "!=", "<", ">", "<=", ">=", "<=>"),
			listOf("&&"),
			listOf("||"),
			listOf("in")
		)

		private val BINOPS = BINOPS_PRIORITIES_LIST.withIndex()
			.flatMap { (index, ops) -> ops.map { it to index } }
			.toMap()

		fun binopPr(str: String) = BINOPS[str] ?: 0

		fun parseExpr(r: ListReader<Token>): ExprNode {
			var result = ExprNode.parseFinal(r)
			while (r.hasMore) {
				//if (r.peek() !is ExprNode.Token.TOperator || r.peek().text !in ExprNode.BINOPS) break
				if (r.peek().text !in ExprNode.BINOPS) break
				val operator = r.read().text
				val right = ExprNode.parseFinal(r)
				if (result is BINOP) {
					val a = result.l
					val lop = result.op
					val b = result.r
					val rop = operator
					val c = right
					val lopPr = binopPr(lop)
					val ropPr = binopPr(rop)
					if (lopPr > ropPr) {
						result = BINOP(a, BINOP(b, c, rop), lop)
						continue
					}
				}
				result = BINOP(result, right, operator)
			}
			return result
		}

		private fun parseFinal(r: ListReader<Token>): ExprNode {
			var construct: ExprNode = when (r.peek().text) {
				"!", "~", "-", "+" -> {
					val op = r.read().text
					UNOP(parseFinal(r), op)
				}
				"(" -> {
					r.read()
					val result = ExprNode.parseExpr(r)
					if (r.read().text != ")") throw RuntimeException("Expected ')'")
					UNOP(result, "")
				}
			// Array literal
				"[" -> {
					r.read()
					val items = arrayListOf<ExprNode>()
					loop@ while (r.hasMore && r.peek().text != "]") {
						items += ExprNode.parseExpr(r)
						when (r.peek().text) {
							"," -> r.read()
							"]" -> continue@loop
							else -> invalidOp("Expected , or ]")
						}
					}
					r.expect("]")
					ARRAY_LIT(items)
				}
			// Object literal
				"{" -> {
					r.read()
					val items = arrayListOf<Pair<ExprNode, ExprNode>>()
					loop@ while (r.hasMore && r.peek().text != "}") {
						val k = ExprNode.parseFinal(r)
						r.expect(":")
						val v = ExprNode.parseExpr(r)
						items += k to v
						when (r.peek().text) {
							"," -> r.read()
							"}" -> continue@loop
							else -> invalidOp("Expected , or }")
						}
					}
					r.expect("}")
					OBJECT_LIT(items)
				}
				else -> {
					// Number
					if (r.peek() is ExprNode.Token.TNumber) {
						LIT(r.read().text.toDouble())
					}
					// String
					else if (r.peek() is ExprNode.Token.TString) {
						LIT((r.read() as Token.TString).processedValue)
					}
					// ID
					else {
						VAR(r.read().text)
					}
				}
			}

			loop@ while (r.hasMore) {
				when (r.peek().text) {
					"." -> {
						r.read()
						val id = r.read().text
						construct = ACCESS(construct, LIT(id))
						continue@loop
					}
					"[" -> {
						r.read()
						val expr = ExprNode.parseExpr(r)
						construct = ACCESS(construct, expr)
						val end = r.read()
						if (end.text != "]") throw RuntimeException("Expected ']' but found $end")
					}
					"|" -> {
						r.read()
						val name = r.read().text
						val args = arrayListOf<ExprNode>()
						if (name.isEmpty()) invalidOp("Missing filter name")
						if (r.hasMore && r.peek().text == "(") {
							r.read()
							callargsloop@ while (r.hasMore && r.peek().text != ")") {
								args += ExprNode.parseExpr(r)
								when (r.expectPeek(",", ")").text) {
									"," -> r.read()
									")" -> break@callargsloop
								}
							}
							r.expect(")")
						}
						construct = FILTER(name, construct, args)
					}
					"(" -> {
						r.read()
						val args = arrayListOf<ExprNode>()
						callargsloop@ while (r.hasMore && r.peek().text != ")") {
							args += ExprNode.parseExpr(r)
							when (r.expectPeek(",", ")").text) {
								"," -> r.read()
								")" -> break@callargsloop
							}
						}
						r.expect(")")
						construct = CALL(construct, args)
					}
					else -> break@loop
				}
			}
			return construct
		}
	}

	interface Token {
		val text: String

		data class TId(override val text: String) : ExprNode.Token
		data class TNumber(override val text: String) : ExprNode.Token
		data class TString(override val text: String, val processedValue: String) : ExprNode.Token
		data class TOperator(override val text: String) : ExprNode.Token
		data class TEnd(override val text: String = "") : ExprNode.Token

		companion object {
			private val OPERATORS = setOf(
				"(", ")",
				"[", "]",
				"{", "}",
				"&&", "||",
				"&", "|", "^",
				"==", "!=", "<", ">", "<=", ">=", "<=>",
				"+", "-", "*", "/", "%", "**",
				"!", "~",
				".", ",", ";", ":",
				"="
			)

			fun tokenize(str: String): ListReader<Token> {
				val r = StrReader(str)
				val out = arrayListOf<ExprNode.Token>()
				fun emit(str: ExprNode.Token) {
					out += str
				}
				while (r.hasMore) {
					val start = r.pos
					r.skipSpaces()
					val id = r.readWhile(Char::isLetterDigitOrUnderscore)
					if (id.isNotEmpty()) {
						if (id[0].isDigit()) emit(ExprNode.Token.TNumber(id)) else emit(ExprNode.Token.TId(id))
					}
					r.skipSpaces()
					if (r.peek(3) in ExprNode.Token.Companion.OPERATORS) emit(ExprNode.Token.TOperator(r.read(3)))
					if (r.peek(2) in ExprNode.Token.Companion.OPERATORS) emit(ExprNode.Token.TOperator(r.read(2)))
					if (r.peek(1) in ExprNode.Token.Companion.OPERATORS) emit(ExprNode.Token.TOperator(r.read(1)))
					if (r.peek() == '\'' || r.peek() == '"') {
						val strStart = r.read()
						val strBody = r.readUntil(strStart) ?: ""
						val strEnd = r.read()
						emit(ExprNode.Token.TString(strStart + strBody + strEnd, strBody.unescape()))
					}
					val end = r.pos
					if (end == start) invalidOp("Don't know how to handle '${r.peek()}'")
				}
				emit(ExprNode.Token.TEnd())
				return ListReader(out)
			}
		}
	}
}

fun ListReader<ExprNode.Token>.tryRead(vararg types: String): ExprNode.Token? {
	val token = this.peek()
	if (token.text in types) {
		this.read()
		return token
	} else {
		return null
	}
}

fun ListReader<ExprNode.Token>.expectPeek(vararg types: String): ExprNode.Token {
	val token = this.peek()
	if (token.text !in types) throw java.lang.RuntimeException("Expected ${types.joinToString(", ")} but found '${token.text}'")
	return token
}

fun ListReader<ExprNode.Token>.expect(vararg types: String): ExprNode.Token {
	val token = this.read()
	if (token.text !in types) throw java.lang.RuntimeException("Expected ${types.joinToString(", ")}")
	return token
}
