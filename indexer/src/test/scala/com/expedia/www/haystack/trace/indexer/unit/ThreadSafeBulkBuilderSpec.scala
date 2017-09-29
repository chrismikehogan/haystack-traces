/*
 *  Copyright 2017 Expedia, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.expedia.www.haystack.trace.indexer.unit

import com.expedia.www.haystack.trace.indexer.writers.es.ThreadSafeBulkBuilder
import com.google.gson.Gson
import io.searchbox.core.Index
import org.scalatest.{FunSpec, Matchers}

class ThreadSafeBulkBuilderSpec extends FunSpec with Matchers {
  val gson = new Gson()

  describe("Thread safe bulk builder") {
    it("should return the bulk object when index operations exceeds the configured maxDocument count") {
      val builder = new ThreadSafeBulkBuilder(maxDocuments = 3, 1000)
      var bulkOp = builder.addAction(new Index.Builder("source1").build(), 10, forceBulkCreate = false)
      bulkOp shouldBe 'empty

      bulkOp = builder.addAction(new Index.Builder("source2").build(), 10, forceBulkCreate = false)
      bulkOp shouldBe 'empty

      bulkOp = builder.addAction(new Index.Builder("source3").build(), 10, forceBulkCreate = false)
      val bulkJson = bulkOp.get.getData(gson)
      bulkJson shouldEqual """{"index":{}}
                             |source1
                             |{"index":{}}
                             |source2
                             |{"index":{}}
                             |source3
                             |""".stripMargin

      val currentBuilder = builder.bulkBuilder.build().getData(gson)
      builder.docsCount shouldBe 0
      builder.totalSizeInBytes shouldBe 0
      currentBuilder shouldBe 'empty
    }

    it("should return the bulk after size of the index operations exceed the configured threshold") {
      val builder = new ThreadSafeBulkBuilder(maxDocuments = 10, 100)
      var bulkOp = builder.addAction(new Index.Builder("source1").build(), 30, forceBulkCreate = false)
      bulkOp shouldBe 'empty

      bulkOp = builder.addAction(new Index.Builder("source2").build(), 30, forceBulkCreate = false)
      bulkOp shouldBe 'empty

      bulkOp = builder.addAction(new Index.Builder("source3").build(), 80, forceBulkCreate = false)
      val bulkJson = bulkOp.get.getData(gson)
      bulkJson shouldEqual """{"index":{}}
                             |source1
                             |{"index":{}}
                             |source2
                             |{"index":{}}
                             |source3
                             |""".stripMargin

      val currentBuilder = builder.bulkBuilder.build().getData(gson)
      currentBuilder shouldBe 'empty
      builder.docsCount shouldBe 0
      builder.totalSizeInBytes shouldBe 0
    }

    it("should return the bulk if forceBulkCreate attribute is set") {
      val builder = new ThreadSafeBulkBuilder(maxDocuments = 10, 1000)
      var bulkOp = builder.addAction(new Index.Builder("source1").build(), 30, forceBulkCreate = false)
      bulkOp shouldBe 'empty

      bulkOp = builder.addAction(new Index.Builder("source2").build(), 30, forceBulkCreate = false)
      bulkOp shouldBe 'empty

      bulkOp = builder.addAction(new Index.Builder("source3").build(), 80, forceBulkCreate = false)
      bulkOp shouldBe 'empty

      bulkOp = builder.addAction(new Index.Builder("source4").build(), 80, forceBulkCreate = true)
      val bulkJson = bulkOp.get.getData(gson)
      bulkJson shouldEqual """{"index":{}}
                             |source1
                             |{"index":{}}
                             |source2
                             |{"index":{}}
                             |source3
                             |{"index":{}}
                             |source4
                             |""".stripMargin

      val currentBuilder = builder.bulkBuilder.build().getData(gson)
      currentBuilder shouldBe 'empty
      builder.docsCount shouldBe 0
      builder.totalSizeInBytes shouldBe 0
    }
  }
}