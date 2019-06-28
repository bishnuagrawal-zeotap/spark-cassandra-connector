package com.datastax.spark.connector.streaming

import com.datastax.spark.connector._
import com.datastax.spark.connector.cluster.DefaultCluster
import com.datastax.spark.connector.cql.CassandraConnector
import com.datastax.spark.connector.testkit._
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.{Milliseconds, StreamingContext}
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import scala.collection.mutable
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Random

case class WordCount(word: String, count: Int)
case class Key(word: String)

class RDDStreamingSpec extends SparkCassandraITFlatSpecBase with DefaultCluster
  with Eventually
  with BeforeAndAfterEach
  with BeforeAndAfterAll {

  import org.scalatest.time.SpanSugar._

  implicit val pc = PatienceConfig(60.seconds, 1.second)

  CassandraConnector(defaultConf).withSessionDo { session =>

    createKeyspace(session)

    awaitAll(
      Future {
        session.execute(s"CREATE TABLE $ks.streaming_wordcount (word TEXT PRIMARY KEY, count COUNTER)")
      },
      Future {
        session.execute(s"CREATE TABLE $ks.streaming_join (word TEXT PRIMARY KEY, count COUNTER)")
        (for (d <- dataSeq; word <- d) yield
          session.executeAsync(s"UPDATE $ks.streaming_join set count = count + 10 where word = ?", word.trim))
          .par.foreach(_.getUninterruptibly)
      },
      Future {
        session.execute(s"CREATE TABLE $ks.streaming_join_output (word TEXT PRIMARY KEY, count COUNTER)")
      },
      Future {
        session.execute(s"CREATE TABLE $ks.dstream_join_output (word TEXT PRIMARY KEY, count COUNTER)")
      },
      Future {
        session.execute(s"CREATE TABLE $ks.streaming_deletes (word TEXT PRIMARY KEY, count INT)")
        session.execute( s"INSERT INTO $ks.streaming_deletes (word, count) VALUES ('1words', 1)")
        session.execute( s"INSERT INTO $ks.streaming_deletes (word, count) VALUES ('1round', 2)")
        session.execute( s"INSERT INTO $ks.streaming_deletes (word, count) VALUES ('survival', 3)")
      }
    )
  }

  val r = new Random()
  // Build 4 400 Element RDDs to use as a DStream
  val dataRDDs = new mutable.Queue[RDD[String]]()

  override def beforeEach() {
    while (dataRDDs.nonEmpty) dataRDDs.dequeue
    for (rddNum <- 1 to 4) {
      dataRDDs.enqueue(sc.parallelize((1 to 400).map(item => data(r.nextInt(data.size)))))
    }
  }

  def withStreamingContext(test: StreamingContext => Any) = {
    val ssc = new StreamingContext(sc, Milliseconds(200))
    try {
      test(ssc)
    }
    finally {
      ssc.stop(stopSparkContext = false, stopGracefully = true)
      // this will rethrow any exceptions thrown during execution (from foreachRDD etc)
      ssc.awaitTerminationOrTimeout(60 * 1000)
    }
  }

  "RDDStream" should s"write from the stream to cassandra table: $ks.streaming_wordcount" in withStreamingContext { ssc =>
    val stream = ssc.queueStream[String](dataRDDs)

    val wc = stream
      .map(x => (x, 1))
      .reduceByKey(_ + _)
      .saveToCassandra(ks, "streaming_wordcount")

    // start the streaming context so the data can be processed and actor started
    ssc.start()
    eventually {
      dataRDDs shouldBe empty
    }

    eventually {
      val rdd = ssc.cassandraTable[WordCount](ks, "streaming_wordcount")
      val result = rdd.collect
      result.nonEmpty should be(true)
      result.length should be(data.size)
    }
  }

  it should "be able to utilize joinWithCassandra during transforms " in withStreamingContext { ssc =>
    val stream = ssc.queueStream[String](dataRDDs)

    val wc = stream
      .map(x => (x, 1))
      .reduceByKey(_ + _)
      .saveToCassandra(ks, "streaming_wordcount")

    stream
      .map(Tuple1(_))
      .transform(rdd => rdd.joinWithCassandraTable(ks, "streaming_join"))
      .map(_._2)
      .saveToCassandra(ks, "streaming_join_output")

    ssc.start()

    eventually {
      dataRDDs shouldBe empty
    }

    eventually {
      val rdd = ssc.cassandraTable[WordCount](ks, "streaming_join_output")
      val result = rdd.collect
      result.nonEmpty should be(true)
      result.length should be(data.size)
      rdd.collect.nonEmpty && rdd.collect.size == data.size
      ssc.sparkContext.cassandraTable(ks, "streaming_join_output").collect.size should be(data.size)
    }
  }

  it should "be able to utilize joinWithCassandra and repartitionByCassandraTable on a Dstream " in withStreamingContext { ssc =>
    val stream = ssc.queueStream[String](dataRDDs)

    val wc = stream
      .map(x => (x, 1))
      .reduceByKey(_ + _)
      .saveToCassandra(ks, "streaming_wordcount")

    val jcRepart = stream
      .map(Tuple1(_))
      .repartitionByCassandraReplica(ks, "streaming_join")

    val conn = CassandraConnector(defaultConf)

    jcRepart.foreachRDD(rdd => rdd
      .partitions
      .map(rdd.preferredLocations)
      .foreach { preferredLocations =>
        withClue("Failed to verify preferred locations of repartitionByCassandraReplica RDD") {
          conn.hosts.map(_.getHostAddress) should contain(preferredLocations.head)
        }
      }
    )

    jcRepart.joinWithCassandraTable(ks, "streaming_join")
      .map(_._2)
      .saveToCassandra(ks, "dstream_join_output")

    ssc.start()

    eventually {
      dataRDDs shouldBe empty
    }

    eventually {
      val rdd = ssc.cassandraTable[WordCount](ks, "dstream_join_output")
      val result = rdd.collect
      result should have size data.size
      ssc.sparkContext.cassandraTable(ks, "dstream_join_output").collect.size should be(data.size)
    }
  }

  it should "delete rows from cassandra table base on streaming keys" in withStreamingContext { ssc =>
    val stream = ssc.queueStream[String](dataRDDs)

    val wc = stream
      .map(Key(_))
      .deleteFromCassandra(ks, "streaming_deletes")

    // start the streaming context so the data can be processed and actor started
    ssc.start()
    eventually {
      dataRDDs shouldBe empty
    }

    eventually {
      val rdd = ssc.cassandraTable[WordCount](ks, "streaming_deletes")
      val result = rdd.collect
      result.length should be(1)
      result(0) should be(WordCount("survival", 3))
    }
  }
}


