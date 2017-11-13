/*
 *  Copyright 2017 Expedia, Inc.
 *
 *       Licensed under the Apache License, Version 2.0 (the "License");
 *       you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *       Unless required by applicable law or agreed to in writing, software
 *       distributed under the License is distributed on an "AS IS" BASIS,
 *       WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *       See the License for the specific language governing permissions and
 *       limitations under the License.
 */

package com.expedia.www.haystack.trace.reader.stores.readers.es

import com.expedia.www.haystack.trace.reader.config.entities.ElasticSearchConfiguration
import com.expedia.www.haystack.trace.reader.metrics.MetricsSupport
import com.google.gson.Gson
import io.searchbox.client.config.HttpClientConfig
import io.searchbox.client.{JestClient, JestClientFactory}
import io.searchbox.core.{Search, SearchResult}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.util.Try

class ElasticSearchReader(config: ElasticSearchConfiguration)(implicit val dispatcher: ExecutionContextExecutor) extends MetricsSupport with AutoCloseable {
  private val LOGGER = LoggerFactory.getLogger(classOf[ElasticSearchReader])
  private val readTimer = metricRegistry.timer("elasticsearch.read.time")
  private val readFailures = metricRegistry.meter("elasticsearch.read.failures")

  // initialize the elastic search client
  private val esClient: JestClient = {
    LOGGER.info("Initializing the http elastic search client with endpoint={}", config.endpoint)
    val factory = new JestClientFactory()

    factory.setHttpClientConfig(
      new HttpClientConfig.Builder(config.endpoint)
        .multiThreaded(true)
        .connTimeout(config.connectionTimeoutMillis)
        .readTimeout(config.readTimeoutMillis)
        .build())

    factory.getObject
  }

  def search(request: Search): Future[SearchResult] = {
    val promise = Promise[SearchResult]()
    val time = readTimer.time()
    try {
      esClient.executeAsync(request, new ElasticSearchReadResultListener(request, promise, time, readFailures))
      promise.future
    } catch {
      case ex: Exception =>
        readFailures.mark()
        time.stop()
        LOGGER.error(s"Failed to read from elasticsearch for request=${request.getData(new Gson())} with exception", ex)
        Future.failed(ex)
    }
  }

  override def close(): Unit = Try(esClient.shutdownClient())
}
