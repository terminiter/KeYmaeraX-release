/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/
/**
  * @note Code Review: 2016-06-01
  */
package edu.cmu.cs.ls.keymaerax.tools

// favoring immutable Seqs
import scala.collection.immutable._
import com.wolfram.jlink._
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.tools.MathematicaConversion.{KExpr, MExpr}

import scala.math.BigDecimal

/**
 * Converts com.wolfram.jlink.Expr -> edu.cmu...keymaerax.core.Expr
 *
 * @author Nathan Fulton
 * @author Stefan Mitsch
 */
object MathematicaToKeYmaera extends BaseM2KConverter {

  def k2m: K2MConverter = KeYmaeraToMathematica

  /** Converts a Mathematica expression to a KeYmaera expression. */
  def convert(e: MExpr): KExpr = {
    //Exceptional states
    if (isAborted(e)) throw abortExn(e)
    else if (isFailed(e))  throw failExn(e)

    //Numbers
    else if (e.numberQ() && !e.rationalQ()) {
      try {
        val number = e.asBigDecimal()
        Number(BigDecimal(number))
      }
      catch {
        case exn : NumberFormatException => throw mathExnMsg(e, "Could not convert number: " + e.toString)
        case exn : ExprFormatException => throw mathExnMsg(e, "Could not represent number as a big decimal: " + e.toString)
      }
    }
    //@todo Code Review: assert arity 2 --> split into convertBinary and convertNary (see DIV, EXP, MINUS)
    //@solution: introduced explicit convertNary (used for plus/times/and/or), convertBinary forwards to convertNary after contract checking (2 args)
    else if (e.rationalQ()) {assert(hasHead(e,MathematicaSymbols.RATIONAL)); convertBinary(e, Divide.apply)}

    // Arith expressions
    else if (hasHead(e,MathematicaSymbols.PLUS))  convertNary(e, Plus.apply)
    else if (hasHead(e,MathematicaSymbols.MINUS)) convertBinary(e, Minus.apply)
    else if (hasHead(e,MathematicaSymbols.MULT))  convertNary(e, Times.apply)
    else if (hasHead(e,MathematicaSymbols.DIV))   convertBinary(e, Divide.apply)
    else if (hasHead(e,MathematicaSymbols.EXP))   convertBinary(e, Power.apply)
    else if (hasHead(e,MathematicaSymbols.MINUSSIGN)) convertUnary(e, Neg.apply)

    // Comparisons
    else if (hasHead(e, MathematicaSymbols.EQUALS))         convertComparison(e, Equal.apply)
    else if (hasHead(e, MathematicaSymbols.UNEQUAL))        convertComparison(e, NotEqual.apply)
    else if (hasHead(e, MathematicaSymbols.GREATER))        convertComparison(e, Greater.apply)
    else if (hasHead(e, MathematicaSymbols.GREATER_EQUALS)) convertComparison(e, GreaterEqual.apply)
    else if (hasHead(e, MathematicaSymbols.LESS))           convertComparison(e, Less.apply)
    else if (hasHead(e, MathematicaSymbols.LESS_EQUALS))    convertComparison(e, LessEqual.apply)
    else if (hasHead(e, MathematicaSymbols.INEQUALITY))     convertInequality(e)

    // Formulas
    else if (hasHead(e, MathematicaSymbols.TRUE))   True
    else if (hasHead(e, MathematicaSymbols.FALSE))  False
    else if (hasHead(e, MathematicaSymbols.NOT))    convertUnary(e, Not.apply)
    else if (hasHead(e, MathematicaSymbols.AND))    convertNary(e, And.apply)
    else if (hasHead(e, MathematicaSymbols.OR))     convertNary(e, Or.apply)
    else if (hasHead(e, MathematicaSymbols.IMPL))   convertBinary(e, Imply.apply)
    else if (hasHead(e, MathematicaSymbols.BIIMPL)) convertBinary(e, Equiv.apply)

    //Quantifiers
    else if (hasHead(e,MathematicaSymbols.FORALL)) convertQuantifier(e, Forall.apply)
    else if (hasHead(e,MathematicaSymbols.EXISTS)) convertQuantifier(e, Exists.apply)

    // Rules and List of rules not supported -> override if needed
    else if (hasHead(e, MathematicaSymbols.RULE)) throw new ConversionException("Unsupported conversion RULE")
    else if (e.listQ() && e.args().forall(r => r.listQ() && r.args().forall(
      hasHead(_, MathematicaSymbols.RULE)))) throw new ConversionException("Unsupported conversion List[RULE]")

    // Derivatives
    //@todo Code Review: check e.head
    else if (e.head.head.symbolQ() && e.head.head == MathematicaSymbols.DERIVATIVE) convertDerivative(e)

    // Functions
    else if (e.head().symbolQ() && !MathematicaSymbols.keywords.contains(e.head().toString)) convertAtomicTerm(e)

    // Pairs
    else if (e.listQ()) convertList(e)

    //Variables. This case intentionally comes last, so that it doesn't gobble up
    //and keywords that were not declared correctly in MathematicaSymbols (should be none)
    else if (e.symbolQ() && !MathematicaSymbols.keywords.contains(e.asString())) {
      convertAtomicTerm(e)
    }
    else {
      throw mathExn(e) //Other things to handle: integrate, rule, minussign, possibly some list.
    }
  }

  /**
    * Whether e is thing or starts with head thing.
    * @return true if ``e" and ``thing" are .equals-related.
    */
  def hasHead(e: MExpr, thing: MExpr) =
    e.equals(thing) || e.head().equals(thing)

  private def convertUnary[T<:Expression](e : MExpr, op: T=>T): T = {
    val subformula = apply(e.args().head).asInstanceOf[T]
    op(subformula)
  }

  private def convertBinary[T<:Expression](e : MExpr, op: (T,T) => T): T = {
    require(e.args().length == 2, "binary operator expects 2 arguments")
    convertNary(e, op)
  }

  private def convertNary[T<:Expression](e : MExpr, op: (T,T) => T): T = {
    val subexpressions = e.args().map(apply)
    require(subexpressions.length >= 2, "nary operator expects at least 2 arguments")
    val asTerms = subexpressions.map(_.asInstanceOf[T])
    asTerms.reduce((l,r) => op(l,r))
  }

  private def convertComparison[S<:Expression,T<:Expression](e : MExpr, op: (S,S) => T): T = {
    val subexpressions = e.args().map(apply)
    require(subexpressions.length == 2, "binary operator expects 2 arguments")
    val asTerms = subexpressions.map(_.asInstanceOf[S])
    op(asTerms(0), asTerms(1))
  }

  private def convertQuantifier(e: MExpr, op:(Seq[Variable], Formula)=>Formula) = {
    require(e.args().length == 2, "Expected args size 2.")

    val variableBlock = e.args().headOption.getOrElse(
      throw new ConversionException("Found non-empty list after quantifier."))

    val quantifiedVars: List[Variable] = if (variableBlock.head().equals(MathematicaSymbols.LIST)) {
      //Convert the list of quantified variables
      variableBlock.args().toList.map(n => MathematicaNameConversion.toKeYmaera(n).asInstanceOf[Variable])
    } else {
      List(apply(variableBlock).asInstanceOf[Variable])
    }

    //Recurse on the body of the expression.
    val bodyOfQuantifier = apply(e.args().last).asInstanceOf[Formula]

    // convert quantifier block into chain of single quantifiers
    quantifiedVars.foldRight(bodyOfQuantifier)((v, fml) => op(v :: Nil, fml))
  }

  private def convertDerivative(e: MExpr): KExpr = {
    require(e.args().length == 1, "Expected args size 1 (single differential symbol or single differential term)")
    require(e.head.args().length == 1 && e.head.args().head == new MExpr(1), "Expected 1 prime (e.g., v', not v'')")
    apply(e.args.head) match {
      case v: Variable => DifferentialSymbol(v)
      case t: Term => Differential(t)
    }
  }

  private def convertFuncOf(fn: Function, arguments: Array[Term]): FuncOf = {
    if (arguments.nonEmpty) {
      val args = if (arguments.length > 1) arguments.reduceRight[Term]((l, r) => Pair(l, r))
      else { assert(arguments.length == 1); arguments.head }
      FuncOf(fn, args)
    } else {
      FuncOf(fn, Nothing)
    }
  }

  private def convertFunctionDomain(arg: MExpr): Sort = {
    if (arg.listQ()) {
      assert(arg.args().length == 2)
      Tuple(convertFunctionDomain(arg.args()(0)), convertFunctionDomain(arg.args()(1)))
    } else {
      Real
    }
  }

  private def convertList(e: MExpr): Pair = {
    if (e.listQ) {
      assert(e.args.length == 2)
      Pair(apply(e.args().head).asInstanceOf[Term], apply(e.args()(1)).asInstanceOf[Term])
    } else throw new ConversionException("Expected a list, but got " + e)
  }

  private def convertAtomicTerm(e: MExpr): KExpr =
    if (e.head.symbolQ() && e.head == MathematicaSymbols.APPLY) {
      val fnName = MathematicaNameConversion.unmaskName(e.args().head)
      assert(e.args().tail.length == 1)
      val fnDomain = convertFunctionDomain(e.args().tail.head)
      convertFuncOf(Function(fnName._1, fnName._2, fnDomain, Real), e.args().tail.map(apply).map(_.asInstanceOf[Term]))
    } else {
      MathematicaNameConversion.toKeYmaera(e) match {
        case result: Function => convertFuncOf(result, e.args().map(apply).map(_.asInstanceOf[Term]))
        case result: Variable => result
      }
    }

  //@todo could streamline this implementation
  /** Converts inequality chains of the form a <= b < c < 0 to conjunctions of individual inequalities a <= b & b < c & c < 0 */
  private def convertInequality(e: MExpr): Formula = {
    /** Extract overlapping inequalities from a chain of inequalities, so x<y=z<=d will be x<y, y=z, z<=d */
    def extractInequalities(exprs: Array[Expr]): List[(MExpr, MExpr, MExpr)] = {
      require(exprs.length >= 3 && exprs.length % 2 == 1, "Need pairs of expressions separated by operators")
      if (exprs.length == 3) (exprs(0), exprs(1), exprs(2)) :: Nil
      else (exprs(0), exprs(1), exprs(2)) :: extractInequalities(exprs.tail.tail)
    }

    // conjunction of converted indidivual inequalities
    extractInequalities(e.args()).
      map({case (arg1, op, arg2) => new MExpr(op, Array[MExpr](arg1, arg2))}).
      map(apply(_).asInstanceOf[Formula]).reduce(And)
  }

  // error catching and reporting

  private def isAborted(e : com.wolfram.jlink.Expr) = {
    e.toString.equalsIgnoreCase("$Aborted") ||
    e.toString.equalsIgnoreCase("Abort[]")
  }
  
  private def isFailed(e : com.wolfram.jlink.Expr) = {
    e.toString.equalsIgnoreCase("$Failed")
  }

  private def failExn(e:com.wolfram.jlink.Expr) = new MathematicaComputationFailedException(e.toString)
  private def abortExn(e:com.wolfram.jlink.Expr) = new MathematicaComputationAbortedException(e.toString)

  private def mathExnMsg(e:MExpr, s:String) : Exception =
    new ConversionException("Conversion of " + e.toString + " failed because: " + s)
  
  private def mathExn(e:com.wolfram.jlink.Expr) : Exception =
    new ConversionException("conversion not defined for Mathematica expr: " + e.toString + " with infos: " + mathInfo(e))
  
  private def mathInfo(e : com.wolfram.jlink.Expr) : String = {
    "args:\t" + {if (e.args().length == 0) { "empty" } else {e.args().map(_.toString).reduce(_+","+_)}} +
    "\n" +
    "toString:\t" + e.toString
  }
}
  
