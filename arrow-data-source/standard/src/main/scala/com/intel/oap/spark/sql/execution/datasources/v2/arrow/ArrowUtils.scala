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

package com.intel.oap.spark.sql.execution.datasources.v2.arrow

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.ZoneId

import scala.collection.JavaConverters._
import scala.collection.mutable

import com.intel.oap.vectorized.{ArrowColumnVectorUtils, ArrowWritableColumnVector}
import org.apache.arrow.dataset.file.FileSystemDatasetFactory
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch
import org.apache.arrow.vector.types.pojo.{Field, Schema}
import org.apache.hadoop.fs.FileStatus
import org.apache.spark.TaskContext

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.datasources.v2.arrow.{SparkMemoryUtils, SparkSchemaUtils}
import org.apache.spark.sql.execution.vectorized.ColumnVectorUtils
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.sql.util.CaseInsensitiveStringMap
import org.apache.spark.sql.vectorized.{ColumnarBatch, ColumnVector}

object ArrowUtils {

  def readSchema(file: FileStatus, options: CaseInsensitiveStringMap): Option[StructType] = {
    val factory: FileSystemDatasetFactory =
      makeArrowDiscovery(file.getPath.toString, -1L, -1L,
        new ArrowOptions(options.asScala.toMap))
    val schema = factory.inspect()
    try {
      Option(SparkSchemaUtils.fromArrowSchema(schema))
    } finally {
      factory.close()
    }
  }

  def readSchema(files: Seq[FileStatus], options: CaseInsensitiveStringMap): Option[StructType] = {
    if (files.isEmpty) {
      throw new IllegalArgumentException("No input file specified")
    }
    readSchema(files.toList.head, options) // todo merge schema
  }

  def isOriginalFormatSplitable(options: ArrowOptions): Boolean = {
    val format = getFormat(options)
    format match {
      case _: org.apache.arrow.dataset.file.format.ParquetFileFormat =>
        true
      case _ =>
        false
    }
  }

  def makeArrowDiscovery(encodedUri: String, startOffset: Long, length: Long,
      options: ArrowOptions): FileSystemDatasetFactory = {

    val format = getFormat(options)
    val allocator = SparkMemoryUtils.contextAllocator()
    val factory = new FileSystemDatasetFactory(allocator,
      SparkMemoryUtils.contextMemoryPool(),
      format,
      rewriteUri(encodedUri),
      startOffset,
      length)
    factory
  }

  def toArrowSchema(t: StructType): Schema = {
    // fixme this might be platform dependent
    SparkSchemaUtils.toArrowSchema(t, SparkSchemaUtils.getLocalTimezoneID())
  }

  def loadMissingColumns(
      rowCount: Int,
      missingSchema: StructType): Array[ArrowWritableColumnVector] = {

    val vectors =
      ArrowWritableColumnVector.allocateColumns(rowCount, missingSchema)
    vectors.foreach { vector =>
      vector.putNulls(0, rowCount)
      vector.setValueCount(rowCount)
    }

    SparkMemoryUtils.addLeakSafeTaskCompletionListener[Unit]((_: TaskContext) => {
      vectors.foreach(_.close())
    })

    vectors
  }

  def loadPartitionColumns(
      rowCount: Int,
      partitionSchema: StructType,
      partitionValues: InternalRow): Array[ArrowWritableColumnVector] = {
    val partitionColumns = ArrowWritableColumnVector.allocateColumns(rowCount, partitionSchema)
    (0 until partitionColumns.length).foreach(i => {
      ArrowColumnVectorUtils.populate(partitionColumns(i), partitionValues, i)
      partitionColumns(i).setValueCount(rowCount)
      partitionColumns(i).setIsConstant()
    })

    SparkMemoryUtils.addLeakSafeTaskCompletionListener[Unit]((_: TaskContext) => {
      partitionColumns.foreach(_.close())
    })

    partitionColumns
  }

  def loadBatch(
      input: ArrowRecordBatch,
      dataSchema: StructType,
      requiredSchema: StructType,
      partitionVectors: Array[ArrowWritableColumnVector] = Array.empty,
      nullVectors: Array[ArrowWritableColumnVector] = Array.empty): ColumnarBatch = {
    val rowCount: Int = input.getLength

    val vectors = try {
      ArrowWritableColumnVector.loadColumns(rowCount, toArrowSchema(dataSchema), input)
    } finally {
      input.close()
    }

    val totalVectors = if (nullVectors.nonEmpty) {
      val finalVectors =
        mutable.ArrayBuffer[ArrowWritableColumnVector]()
      val requiredIterator = requiredSchema.iterator
      val caseSensitive = SQLConf.get.caseSensitiveAnalysis
      while (requiredIterator.hasNext) {
        val field = requiredIterator.next()
        finalVectors.append(
          if (caseSensitive) {
            vectors.find(_.getValueVector.getName.equals(field.name))
              .getOrElse {
                // The missing column need to be find in nullVectors
                val nullVector = nullVectors.find(_.getValueVector.getName.equals(field.name)).get
                nullVector.setValueCount(rowCount)
                nullVector.retain()
                nullVector
              }
          } else {
            vectors.find(_.getValueVector.getName.equalsIgnoreCase(field.name))
              .getOrElse {
                // The missing column need to be find in nullVectors
                val nullVector =
                  nullVectors.find(_.getValueVector.getName.equalsIgnoreCase(field.name)).get
                nullVector.setValueCount(rowCount)
                nullVector.retain()
                nullVector
              }
          })
      }
      finalVectors.toArray
    } else {
      vectors
    }

    val batch = new ColumnarBatch(
      totalVectors.map(_.asInstanceOf[ColumnVector]) ++
        partitionVectors
          .map { vector =>
            vector.setValueCount(rowCount)
            // The vector should call retain() whenever reuse it.
            vector.retain()
            vector.asInstanceOf[ColumnVector]
          },
      rowCount)
    batch
  }

  def toArrowField(t: StructField): Field = {
    SparkSchemaUtils.toArrowField(
      t.name, t.dataType, t.nullable, SparkSchemaUtils.getLocalTimezoneID())
  }

  def loadBatch(input: ArrowRecordBatch, partitionValues: InternalRow,
      partitionSchema: StructType, dataSchema: StructType): ColumnarBatch = {
    val rowCount: Int = input.getLength

    val vectors = try {
      ArrowWritableColumnVector.loadColumns(rowCount, toArrowSchema(dataSchema), input)
    } finally {
      input.close()
    }
    val partitionColumns = ArrowWritableColumnVector.allocateColumns(rowCount, partitionSchema)
    (0 until partitionColumns.length).foreach(i => {
      ArrowColumnVectorUtils.populate(partitionColumns(i), partitionValues, i)
      partitionColumns(i).setValueCount(rowCount)
      partitionColumns(i).setIsConstant()
    })

    val batch = new ColumnarBatch(
      vectors.map(_.asInstanceOf[ColumnVector]) ++
        partitionColumns.map(_.asInstanceOf[ColumnVector]),
      rowCount)
    batch
  }

  def getFormat(
      options: ArrowOptions): org.apache.arrow.dataset.file.format.FileFormat = {
    val paramMap = options.parameters.toMap.asJava
    options.originalFormat match {
      case "parquet" => org.apache.arrow.dataset.file.format.ParquetFileFormat.create(paramMap)
      case "orc" => org.apache.arrow.dataset.file.format.OrcFileFormat.create(paramMap)
      case "csv" => org.apache.arrow.dataset.file.format.CsvFileFormat.create(paramMap)
      case _ => throw new IllegalArgumentException("Unrecognizable format")
    }
  }

  private def rewriteUri(encodeUri: String): String = {
    val decodedUri = encodeUri
    val uri = URI.create(decodedUri)
    if (uri.getScheme == "s3" || uri.getScheme == "s3a") {
      val s3Rewritten = new URI("s3", uri.getAuthority,
        uri.getPath, uri.getQuery, uri.getFragment).toString
      return s3Rewritten
    }
    val sch = uri.getScheme match {
      case "hdfs" => "hdfs"
      case "file" => "file"
    }
    val ssp = uri.getScheme match {
      case "hdfs" => uri.getSchemeSpecificPart
      case "file" => "//" + uri.getSchemeSpecificPart
    }
    val rewritten = new URI(sch, ssp, uri.getFragment)
    rewritten.toString
  }

  def compareStringFunc(caseSensitive: Boolean): (String, String) => Boolean =
    if (caseSensitive) {
      (str1: String, str2: String) => str1.equals(str2)
    } else {
      (str1: String, str2: String) => str1.equalsIgnoreCase(str2)
    }

  def getRequestedField(
      requiredSchema: StructType,
      parquetFileFields: mutable.Buffer[Field],
      caseSensitive: Boolean): Schema = {
    val compareFunc = compareStringFunc(caseSensitive)
    if (!caseSensitive) {
      requiredSchema.foreach { readField =>
        // TODO: check schema inside of complex type
        val matchedFields =
          parquetFileFields.filter(field => compareFunc(field.getName, readField.name))
        if (matchedFields.size > 1) {
          // Need to fail if there is ambiguity, i.e. more than one field is matched
          val fieldsString = matchedFields.map(_.getName).mkString("[", ", ", "]")
          throw new RuntimeException(
            s"""
               |Found duplicate field(s) "${readField.name}": $fieldsString

               |in case-insensitive mode""".stripMargin.replaceAll("\n"
              , " "))
        }
      }
    }
    val requestColNames = requiredSchema.map(_.name)
    new Schema(parquetFileFields.filter { field =>
      requestColNames.exists(col => compareFunc(col, field.getName))
    }.asJava)
  }
}
