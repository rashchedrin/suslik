package org.tygus.synsl

import org.tygus.synsl.language.Expressions.{Expr, Var}
import org.tygus.synsl.logic.Specifications.Spec

/**
  * @author Ilya Sergey
  */

trait PrettyPrinting {
  def pp : String = toString
}


trait Substitutable[A] {
  // Variable substitution
  def subst(x: Var, by: Expr) : A = {
    this.subst(Map.empty[Var,Expr] + (x -> by))
  }

  def subst(sigma: Map[Var,Expr]) : A
}

object LanguageUtils {

  def generateFreshVar(s: Spec, tmpName: String = "tmp"): Var = {
    var counter = 1
    var candidate = Var(s"$tmpName")
    val existing = s.vars
    while (existing.contains(candidate)) {
      counter = counter + 1
      candidate = Var(s"$tmpName$counter")
    }
    candidate
  }

}