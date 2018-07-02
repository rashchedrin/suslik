package org.tygus.synsl.synthesis

import org.tygus.synsl.SynSLException
import org.tygus.synsl.language.Statements._
import org.tygus.synsl.logic._
import org.tygus.synsl.logic.smt.SMTSolving
import org.tygus.synsl.util.{SynLogging, SynStats}
import org.tygus.synsl.logic.Specifications._

import scala.Console.{BLACK, BLUE, CYAN, GREEN, MAGENTA, RED, YELLOW}
import scala.collection.mutable.ListBuffer

/**
  * @author Nadia Polikarpova, Ilya Sergey
  */

trait Synthesis extends SepLogicUtils {

  val log: SynLogging

  import log._

  val synQualifier: String = "synthesis"

  case class SynthesisException(msg: String) extends SynSLException(synQualifier, msg)

  def synAssert(assertion: Boolean, msg: String): Unit = if (!assertion) throw SynthesisException(msg)

  def allRules: List[SynthesisRule]
  def initialRules: List[SynthesisRule] = allRules
  def nextRules(goal: Goal, depth: Int): List[SynthesisRule]

  val startingDepth: Int

  def synthesizeProc(funGoal: FunSpec, env: Environment)(implicit printTrace: Boolean = true):
  Option[(Procedure, SynStats)] = {
    val FunSpec(name, tp, formals, pre, post) = funGoal
    val goal = makeNewGoal(pre, post, formals, name, env)
    printLog(List(("Initial specification:", Console.BLACK), (s"${goal.pp}\n", Console.BLUE)))(0, printTrace)
    val stats = new SynStats()
    SMTSolving.init()
    synthesize(goal, startingDepth)(stats = stats, rules = allRules)(printTrace = printTrace) match {
      case Some(body) =>
        val proc = Procedure(name, tp, formals, body)
        Some((proc, stats))
      case None =>
        printlnErr(s"Deductive synthesis failed for the goal\n ${goal.pp},\n depth = $startingDepth.")
        None
    }

  }

  private def synthesize(goal: Goal, depth: Int = startingDepth)
                        (stats: SynStats,
                         rules: List[SynthesisRule])
                        (implicit ind: Int = 0, printTrace: Boolean = true): Option[Statement] = {

    printLog(List((s"${goal.env.pp}", Console.MAGENTA)))
    printLog(List((s"${goal.pp}", Console.BLUE)))

    if (depth < 0) {
      printLog(List(("Reached maximum depth.", RED)))
      return None
    }

    def tryRules(rules: List[SynthesisRule]): Option[Statement] = rules match {
      case Nil => None
      case r :: rs =>

        // Try alternative sub-derivations after applying `r`
        def tryAlternatives(alts: Seq[Subderivation], altIndex: Int): Option[Statement] = alts match {
          case a :: as =>
            if (altIndex > 0) printLog(List((s"${r.toString} Trying alternative sub-derivation ${altIndex + 1}:", MAGENTA)))
            solveSubgoals(a) match {
              case Some(Magic) =>
                stats.bumpUpBacktracing()
                tryAlternatives(as, altIndex + 1) // This alternative is inconsistent: try other alternatives
              case Some(res) =>
                stats.bumpUpLastingSuccess()
                Some(res) // This alternative succeeded
              case None =>
                stats.bumpUpBacktracing()
                tryAlternatives(as, altIndex + 1) // This alternative failed: try other alternatives
            }
          case Nil =>
            // All alternatives have failed
            if (r.isInstanceOf[InvertibleRule]) {
              // Do not backtrack application of this rule: the rule is invertible and cannot be the reason for failure
              printLog(List((s"${r.toString} All sub-derivations failed: invertible rule, do not backtrack.", MAGENTA)))
              None
            } else {
              // Backtrack application of this rule
              stats.bumpUpBacktracing()
              printLog(List((s"${r.toString} All sub-derivations failed: backtrack.", MAGENTA)))
              tryRules(rs)
            }
        }

        // Solve all sub-goals in a sub-derivation
        def solveSubgoals(s: Subderivation): Option[Statement] = {

          // Optimization: if one of the subgoals failed, to not try the rest!
          // <ugly-imperative-code>
          val results = new ListBuffer[Option[Statement]]
          import util.control.Breaks._
          breakable {
            for {subgoal <- s.subgoals} {
              synthesize(subgoal, depth - 1)(stats, nextRules(subgoal, depth))(ind + 1, printTrace) match {
                case s@Some(_) => results.append(s)
                case _ => break
              }
            }
          }
          // </ugly-imperative-code>

          val resultStmts = for (r <- results if r.isDefined) yield r.get
          if (resultStmts.size < s.subgoals.size) {
            // One of the sub-goals failed: this sub-derivation fails
            None
          } else {
            Some(s.kont(resultStmts))
          }
        }

        // Invoke the rule
        val allSubderivations = r(goal)
        val goalStr = s"$r: "

        // Filter out subderivations that violate rule ordering
        def goalInOrder(g: Goal): Boolean = {
          g.deriv.outOfOrder(allRules) match {
            case None => true
            case Some(app) =>
              //              printLog(List((g.deriv.preIndex.map(_.pp).mkString(", "), BLACK)), isFail = true)
              //              printLog(List((g.deriv.postIndex.map(_.pp).mkString(", "), BLACK)), isFail = true)
              printLog(List((s"$goalStr${RED}Alternative ${g.deriv.applications.head.pp} commutes with earlier ${app.pp}", BLACK)), isFail = true)
              false
          }
        }

        // Toggle this comment to enable and disable commute optimization

        // TODO: This optimisation interferes with ApplyHypothesis rule - see beyond/abduct/list-free-frame.syn
        val subderivations = allSubderivations.filter(sub => sub.subgoals.forall(goalInOrder))
        //         val subderivations = allSubderivations

        if (subderivations.isEmpty) {
          // Rule not applicable: try the rest
          printLog(List((s"$goalStr${RED}FAIL", BLACK)), isFail = true)
          tryRules(rs)
        } else {
          // Rule applicable: try all possible sub-derivations
          val subSizes = subderivations.map(s => s"${s.subgoals.size} sub-goal(s)").mkString(", ")
          val succ = s"SUCCESS at depth $ind, ${subderivations.size} alternative(s) [$subSizes]"
          printLog(List((s"$goalStr$GREEN$succ", BLACK)))
          stats.bumpUpSuccessfulRuleApp()
          if (subderivations.size > 1) {
            printLog(List((s"Trying alternative sub-derivation 1:", CYAN)))
          }
          tryAlternatives(subderivations, 0)
        }
    }

    tryRules(rules)
  }

  private def getIndent(implicit i: Int): String = if (i <= 0) "" else "|  " * i

  private def printLog(sc: List[(String, String)], isFail: Boolean = false)
                      (implicit i: Int, printDerivations: Boolean = true): Unit = {
    if (printDerivations) {
      if (!isFail || printDerivations) {
        for ((s, c) <- sc if s.trim.length > 0) {
          print(s"$BLACK$getIndent")
          println(s"$c${s.replaceAll("\n", s"\n$BLACK$getIndent$c")}")
        }
      }
      print(s"$BLACK")
    }
  }

}