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

package org.apache.spark.sql.execution

import java.io.{File, DataOutputStream, ByteArrayInputStream, ByteArrayOutputStream}

import org.apache.spark.executor.ShuffleWriteMetrics
import org.apache.spark.storage.ShuffleBlockId
import org.apache.spark.util.collection.ExternalSorter
import org.apache.spark.util.Utils
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow}
import org.apache.spark.sql.catalyst.expressions.{UnsafeProjection, UnsafeRow}
import org.apache.spark.sql.types._
import org.apache.spark._


/**
 * used to test close InputStream in UnsafeRowSerializer
 */
class ClosableByteArrayInputStream(buf: Array[Byte]) extends ByteArrayInputStream(buf) {
  var closed: Boolean = false
  override def close(): Unit = {
    closed = true
    super.close()
  }
}

class UnsafeRowSerializerSuite extends SparkFunSuite {

  private def toUnsafeRow(row: Row, schema: Array[DataType]): UnsafeRow = {
    val converter = unsafeRowConverter(schema)
    converter(row)
  }

  private def unsafeRowConverter(schema: Array[DataType]): Row => UnsafeRow = {
    val converter = UnsafeProjection.create(schema)
    (row: Row) => {
      converter(CatalystTypeConverters.convertToCatalyst(row).asInstanceOf[InternalRow])
    }
  }

  test("toUnsafeRow() test helper method") {
    // This currently doesnt work because the generic getter throws an exception.
    val row = Row("Hello", 123)
    val unsafeRow = toUnsafeRow(row, Array(StringType, IntegerType))
    assert(row.getString(0) === unsafeRow.getUTF8String(0).toString)
    assert(row.getInt(1) === unsafeRow.getInt(1))
  }

  test("basic row serialization") {
    val rows = Seq(Row("Hello", 1), Row("World", 2))
    val unsafeRows = rows.map(row => toUnsafeRow(row, Array(StringType, IntegerType)))
    val serializer = new UnsafeRowSerializer(numFields = 2).newInstance()
    val baos = new ByteArrayOutputStream()
    val serializerStream = serializer.serializeStream(baos)
    for (unsafeRow <- unsafeRows) {
      serializerStream.writeKey(0)
      serializerStream.writeValue(unsafeRow)
    }
    serializerStream.close()
    val input = new ClosableByteArrayInputStream(baos.toByteArray)
    val deserializerIter = serializer.deserializeStream(input).asKeyValueIterator
    for (expectedRow <- unsafeRows) {
      val actualRow = deserializerIter.next().asInstanceOf[(Integer, UnsafeRow)]._2
      assert(expectedRow.getSizeInBytes === actualRow.getSizeInBytes)
      assert(expectedRow.getString(0) === actualRow.getString(0))
      assert(expectedRow.getInt(1) === actualRow.getInt(1))
    }
    assert(!deserializerIter.hasNext)
    assert(input.closed)
  }

  test("close empty input stream") {
    val baos = new ByteArrayOutputStream()
    val dout = new DataOutputStream(baos)
    dout.writeInt(-1)  // EOF
    dout.flush()
    val input = new ClosableByteArrayInputStream(baos.toByteArray)
    val serializer = new UnsafeRowSerializer(numFields = 2).newInstance()
    val deserializerIter = serializer.deserializeStream(input).asKeyValueIterator
    assert(!deserializerIter.hasNext)
    assert(input.closed)
  }

  test("SPARK-10466: external sorter spilling with unsafe row serializer") {
    var sc: SparkContext = null
    var outputFile: File = null
    val oldEnv = SparkEnv.get // save the old SparkEnv, as it will be overwritten
    Utils.tryWithSafeFinally {
      val conf = new SparkConf()
        .set("spark.shuffle.spill.initialMemoryThreshold", "1024")
        .set("spark.shuffle.sort.bypassMergeThreshold", "0")
        .set("spark.shuffle.memoryFraction", "0.0001")

      sc = new SparkContext("local", "test", conf)
      outputFile = File.createTempFile("test-unsafe-row-serializer-spill", "")
      // prepare data
      val converter = unsafeRowConverter(Array(IntegerType))
      val data = (1 to 1000).iterator.map { i =>
        (i, converter(Row(i)))
      }
      val sorter = new ExternalSorter[Int, UnsafeRow, UnsafeRow](
        partitioner = Some(new HashPartitioner(10)),
        serializer = Some(new UnsafeRowSerializer(numFields = 1)))

      // Ensure we spilled something and have to merge them later
      assert(sorter.numSpills === 0)
      sorter.insertAll(data)
      assert(sorter.numSpills > 0)

      // Merging spilled files should not throw assertion error
      val taskContext =
        new TaskContextImpl(0, 0, 0, 0, null, null, InternalAccumulator.create(sc))
      taskContext.taskMetrics.shuffleWriteMetrics = Some(new ShuffleWriteMetrics)
      sorter.writePartitionedFile(ShuffleBlockId(0, 0, 0), taskContext, outputFile)
    } {
      // Clean up
      if (sc != null) {
        sc.stop()
      }

      // restore the spark env
      SparkEnv.set(oldEnv)

      if (outputFile != null) {
        outputFile.delete()
      }
    }
  }
}
