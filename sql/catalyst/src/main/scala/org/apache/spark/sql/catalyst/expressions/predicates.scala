/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.trees
import org.apache.spark.sql.catalyst.errors.TreeNodeException
import org.apache.spark.sql.catalyst.analysis.UnresolvedException
import org.apache.spark.sql.catalyst.types.{BooleanType, StringType, TimestampType}

object InterpretedPredicate {
  def apply(expression: Expression): (Row => Boolean) = {
    (r: Row) => expression.apply(r).asInstanceOf[Boolean]
  }
}

trait Predicate extends Expression {
  self: Product =>

  def dataType = BooleanType

  type EvaluatedType = Any
}

trait PredicateHelper {
  def splitConjunctivePredicates(condition: Expression): Seq[Expression] = condition match {
    case And(cond1, cond2) => splitConjunctivePredicates(cond1) ++ splitConjunctivePredicates(cond2)
    case other => other :: Nil
  }
}

abstract class BinaryPredicate extends BinaryExpression with Predicate {
  self: Product =>
  def nullable = left.nullable || right.nullable
}

case class Not(child: Expression) extends Predicate with trees.UnaryNode[Expression] {
  def references = child.references
  override def foldable = child.foldable
  def nullable = child.nullable
  override def toString = s"NOT $child"

  override def apply(input: Row): Any = {
    child.apply(input) match {
      case null => null
      case b: Boolean => !b
    }
  }
}

/**
 * Evaluates to `true` if `list` contains `value`.
 */
case class In(value: Expression, list: Seq[Expression]) extends Predicate {
  def children = value +: list
  def references = children.flatMap(_.references).toSet
  def nullable = true // TODO: Figure out correct nullability semantics of IN.
  override def toString = s"$value IN ${list.mkString("(", ",", ")")}"

  override def apply(input: Row): Any = {
    val evaluatedValue = value.apply(input)
    list.exists(e => e.apply(input) == evaluatedValue)
  }
}

case class And(left: Expression, right: Expression) extends BinaryPredicate {
  def symbol = "&&"

  override def apply(input: Row): Any = {
    val l = left.apply(input)
    val r = right.apply(input)
    if (l == false || r == false) {
      false
    } else if (l == null || r == null ) {
      null
    } else {
      true
    }
  }
}

case class Or(left: Expression, right: Expression) extends BinaryPredicate {
  def symbol = "||"

  override def apply(input: Row): Any = {
    val l = left.apply(input)
    val r = right.apply(input)
    if (l == true || r == true) {
      true
    } else if (l == null || r == null) {
      null
    } else {
      false
    }
  }
}

abstract class BinaryComparison extends BinaryPredicate {
  self: Product =>
}

case class Equals(left: Expression, right: Expression) extends BinaryComparison {
  def symbol = "="
  override def apply(input: Row): Any = {
    val l = left.apply(input)
    val r = right.apply(input)
    if (l == null || r == null) null else l == r
  }
}

case class LessThan(left: Expression, right: Expression) extends BinaryComparison {
  def symbol = "<"
  override def apply(input: Row): Any = c2(input, left, right, _.lt(_, _))
}

case class LessThanOrEqual(left: Expression, right: Expression) extends BinaryComparison {
  def symbol = "<="
  override def apply(input: Row): Any = c2(input, left, right, _.lteq(_, _))
}

case class GreaterThan(left: Expression, right: Expression) extends BinaryComparison {
  def symbol = ">"
  override def apply(input: Row): Any = c2(input, left, right, _.gt(_, _))
}

case class GreaterThanOrEqual(left: Expression, right: Expression) extends BinaryComparison {
  def symbol = ">="
  override def apply(input: Row): Any = c2(input, left, right, _.gteq(_, _))
}

case class If(predicate: Expression, trueValue: Expression, falseValue: Expression)
    extends Expression {

  def children = predicate :: trueValue :: falseValue :: Nil
  def nullable = trueValue.nullable || falseValue.nullable
  def references = children.flatMap(_.references).toSet
  override lazy val resolved = childrenResolved && trueValue.dataType == falseValue.dataType
  def dataType = {
    if (!resolved) {
      throw new UnresolvedException(
        this,
        s"Can not resolve due to differing types ${trueValue.dataType}, ${falseValue.dataType}")
    }
    trueValue.dataType
  }

  type EvaluatedType = Any
  override def apply(input: Row): Any = {
    if (predicate(input).asInstanceOf[Boolean]) {
      trueValue.apply(input)
    } else {
      falseValue.apply(input)
    }
  }

  override def toString = s"if ($predicate) $trueValue else $falseValue"
}
