package com.datastax.spark.connector.writer

import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.cql.BatchType
import com.datastax.spark.connector.util.Logging

private[connector] class BatchStatementBuilder(
  val batchType: BatchType,
  val routingKeyGenerator: RoutingKeyGenerator,
  val consistencyLevel: ConsistencyLevel) extends Logging {

  /** Converts a sequence of statements into a batch if its size is greater than 1.
    * Sets the routing key and consistency level. */
  def maybeCreateBatch(stmts: Seq[RichBoundStatementWrapper]): RichStatement = {
    require(stmts.nonEmpty, "Statements list cannot be empty")
    val stmt = stmts.head
    // for batch statements, it is enough to set routing key for the first statement
    stmt.setRoutingKey(routingKeyGenerator.apply(stmt.stmt))

    if (stmts.size == 1) {
      stmt.setConsistencyLevel(consistencyLevel)
    } else {
      new RichBatchStatementWrapper(batchType, consistencyLevel, stmts)
    }
  }

}
