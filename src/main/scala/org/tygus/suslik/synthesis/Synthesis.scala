package org.tygus.suslik.synthesis

import org.tygus.suslik.language.Statements._
import org.tygus.suslik.logic.Specifications._
import org.tygus.suslik.logic._
import org.tygus.suslik.logic.smt.SMTSolving
import org.tygus.suslik.util.{SynLogging, SynStats}
import org.tygus.suslik.synthesis.rules.Rules._

import scala.Console._
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/**
  * @author Nadia Polikarpova, Ilya Sergey
  */

trait Synthesis extends SepLogicUtils {

  val log: SynLogging

  import log._

  def synAssert(assertion: Boolean, msg: String): Unit = if (!assertion) throw SynthesisException(msg)

  def allRules(goal: Goal): List[SynthesisRule]

  def nextRules(goal: Goal, depth: Int): List[SynthesisRule]

  def synthesizeProc(funGoal: FunSpec, env: Environment): Option[(List[Procedure], SynStats)] = {
    implicit val config: SynConfig = env.config
    val FunSpec(name, tp, formals, pre, post) = funGoal
    val goal = topLevelGoal(pre, post, formals, name, env)
    printLog(List(("Initial specification:", Console.BLACK), (s"${goal.pp}\n", Console.BLUE)))(i = 0, config)
    val stats = new SynStats()
    SMTSolving.init()
    try {
      synthesize(goal)(stats = stats) match {
        case Some((body, helpers)) =>
          val main = Procedure(name, tp, formals, body)
          Some(main :: helpers, stats)
        case None =>
          printlnErr(s"Deductive synthesis failed for the goal\n ${goal.pp}")
          None
      }
    } catch {
      case SynTimeOutException(msg) =>
        printlnErr(msg)
        None
    }
  }


  /*
  * goal is
  * Unsolvable: all child clauses has unsolvable subgoal. Permanent for goal
  * Solved: has child clause, such that all subgoals there are solved. Permanent for goal
  * Useless: for all clauses, there exists at least one unsolvable goal in same conjunction
  * */
  case class GoalInfo(isSolved:Boolean,
                      isUnsolvable:Boolean,
                      isUseless:Boolean){}

  protected def synthesize(goal: Goal)
                          (stats: SynStats)
                          (implicit ind: Int = 0): Option[Solution] = {
    // Initialize boundary with a single goal
    // goal <== goal
    val boundary = Vector(goal)
    val goalsInfo: collection.mutable.Map[Goal, GoalInfo] =
      collection.mutable.Map(goal -> GoalInfo(
        isSolved = false,
        isUnsolvable = false,
        isUseless = false)
      )
    processBoundary(goal, boundary, Nil, goalsInfo)(stats, goal.env.config, ind)
  }

  private def ConstructSolution(target: Specifications.Goal,
                                goalsInfo: collection.mutable.Map[Goal, GoalInfo],
                                implications: Seq[GoalsImplication]): Option[Solution]= {
    assert(goalsInfo(target).isSolved)

  }

  @tailrec
  private def processBoundary(target:Goal,
                              boundary: Seq[Goal],
                              knownClauses: Seq[GoalsImplication],
                              goalsInfo: collection.mutable.Map[Goal, GoalInfo] // todo: use labels
                             )
                             (implicit
                              stats: SynStats,
                              config: SynConfig,
                              ind: Int): Option[Solution] = {

    // Check for timeouts
    val currentTime = System.currentTimeMillis()
    if (currentTime - config.startTime > config.timeOut) {
      throw SynTimeOutException(s"\n\nThe derivation took too long: more than ${config.timeOut.toDouble / 1000} seconds.\n")
    }

    val sz = boundary.length
    printLog(List((s"\nboundary ($sz): ${boundary.map(_.pp).mkString(" ")}", Console.YELLOW)))
    stats.updateMaxWLSize(sz)

    if (goalsInfo(target).isSolved) { // enough information to construct the solution
      ConstructSolution(target, goalsInfo, knownClauses)
    }else if(goalsInfo(target).isUnsolvable){
      None
    }else {
      boundary match {
        case Nil =>
          // No more subgoals to try: synthesis failed
          None
        case goal +: moreGoals => {
          // Otherwise, expand the first open goal
          printLog(List((s"Goal to expand: ${goal.label.pp} (depth: ${goal.depth})", Console.BLUE)))
          stats.updateMaxDepth(goal.depth)
          if (config.printEnv) {
            printLog(List((s"${goal.env.pp}", Console.MAGENTA)))
          }
          printLog(List((s"${goal.pp}", Console.BLUE)))

          // Apply all possible rules to the current goal to get a list of alternatives,
          // each of which can have multiple open goals
          val rules = nextRules(goal, 0)
          val children =
            if (goal.isUnsolvable || goalsInfo(goal).isUseless) Nil // No use of expanding this goal, discard eagerly
            else applyRules(rules)(goal, stats, config, ind)

          if (children.isEmpty) {
            stats.bumpUpBacktracing()
            printLog(List((s"Cannot expand goal: BACKTRACK", Console.RED)))
            MaybeUpdateParentsUnsolvableRecursive()
          }

          val newClauses = for {
            subderivation <- children
          } yield GoalsImplication(subderivation.subgoals, subderivation.kont, goal) // todo: change to letters
          val newKnownClauses = knownClauses ++ newClauses

          // update uselesness
          for(subderivation <- children){
            var subderivationIsUseless = false
            for(subgoal <- subderivation.subgoals){
              if(subgoal.isUnsolvable || goalsInfo(subgoal).isUnsolvable){
                subderivationIsUseless = true
              }
            }
            if(!subderivationIsUseless){
              for(subgoal <- subderivation.subgoals){
                goalsInfo(subgoal) = goalsInfo(subgoal).copy(isUseless = false)
              }
            }
          }

          // update solved
          for(subderivation <- children){
            if(subderivation.subgoals.isEmpty){
              // our current goal is solved!
              for(parent <- parents(goal)) {
                RecursiveUpdateIsSolved(parent)
              }
            }
          }

          // update boundary
          val newBoundary_ : ArrayBuffer[Goal] = ArrayBuffer()
          for(subderivation <- children){
            for(subgoal <- subderivation.subgoals){
              if(!goalsInfo.contains(subgoal)){
                goalsInfo(subgoal) = GoalInfo(
                  isSolved = false,
                  isUnsolvable = subgoal.isUnsolvable,
                  isUseless = false)
                newBoundary_ += subgoal
              }
            }
          }
          newBoundary_ ++= moreGoals
          val newBoundary = if (config.depthFirst) newBoundary_ else newBoundary_.sortBy(_.cost)



//          val newSubderivations = children.map(child => {
//            // To turn a child into a valid subderivation,
//            // add the rest of the open goals from the current subderivation,
//            // and set up the solution producer to join results from all the open goals
//            Subderivation(child.subgoals ++ moreGoals, child.kont >> subgoal.kont)
//          })

          // Add new subderivations to the worklist, sort by cost and process
//          val newWorkList_ = newSubderivations ++ rest
//          val newWorkList = if (config.depthFirst) newWorkList_ else newWorkList_.sortBy(_.cost)
          processBoundary(target, newBoundary, newKnownClauses, goalsInfo)

        }
      }
    }
  }

  protected def applyRules(rules: List[SynthesisRule])(implicit goal: Goal,
                                                       stats: SynStats,
                                                       config: SynConfig,
                                                       ind: Int): Seq[Subderivation] = rules match
  {
    case Nil => Vector() // No more rules to apply: done expanding the goal
    case r :: rs =>
      val goalStr = s"$r: "
      // Invoke the rule
      val allChildren = r(goal)
      // Filter out children that contain out-of-order goals
      val children = if (config.commute) {
        allChildren.filterNot(_.subgoals.exists(goalOutOfOrder))
      } else allChildren

      if (children.isEmpty) {
        // Rule not applicable: try other rules
        printLog(List((s"${goalStr}FAIL", BLACK)), isFail = true)
        applyRules(rs)
      } else {
        // Rule applicable: try all possible sub-derivations
//        val subSizes = children.map(c => s"${c.subgoals.size} sub-goal(s)").mkString(", ")
        val succ = s"SUCCESS, ${children.size} alternative(s): ${children.map(_.pp).mkString(", ")}"
        printLog(List((s"$goalStr$GREEN$succ", BLACK)))
        stats.bumpUpRuleApps()
        if (config.invert && r.isInstanceOf[InvertibleRule]) {
          // The rule is invertible: do not try other rules on this goal
          children
        } else {
          // Both this and other rules apply
          children ++ applyRules(rs)
        }
      }
  }

  // Is current goal supposed to appear before g?
  def goalOutOfOrder(g: Goal)(implicit goal: Goal,
                              stats: SynStats,
                              config: SynConfig,
                              ind: Int): Boolean = {
    g.hist.outOfOrder(allRules(goal)) match {
      case None => false
      case Some(app) =>
        //              printLog(List((g.deriv.preIndex.map(_.pp).mkString(", "), BLACK)), isFail = true)
        //              printLog(List((g.deriv.postIndex.map(_.pp).mkString(", "), BLACK)), isFail = true)
        printLog(List((s"${RED}Alternative ${g.hist.applications.head.pp} commutes with earlier ${app.pp}", BLACK)))
        true
    }
  }

  private def getIndent(implicit i: Int): String = if (i <= 0) "" else "|  " * i

  protected def printLog(sc: List[(String, String)], isFail: Boolean = false)
                      (implicit i: Int, config: SynConfig): Unit = {
    if (config.printDerivations) {
      if (!isFail || config.printFailed) {
        for ((s, c) <- sc if s.trim.length > 0) {
          print(s"$BLACK$getIndent")
          println(s"$c${s.replaceAll("\n", s"\n$BLACK$getIndent$c")}")
        }
      }
      print(s"$BLACK")
    }
  }

}