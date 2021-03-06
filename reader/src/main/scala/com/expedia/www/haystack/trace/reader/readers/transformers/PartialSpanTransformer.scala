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

package com.expedia.www.haystack.trace.reader.readers.transformers

import com.expedia.open.tracing.{Span, Tag}
import com.expedia.www.haystack.trace.reader.readers.utils.TagBuilders.{buildBoolTag, buildLongTag, buildStringTag}
import com.expedia.www.haystack.trace.reader.readers.utils.TagExtractors.extractTagStringValue
import com.expedia.www.haystack.trace.reader.readers.utils.{AuxiliaryTags, MutableSpanForest, SpanMarkers, SpanUtils}

import scala.collection.JavaConverters._

/**
  * Merges partial spans and generates a single Span combining a client and corresponding server span
  * gracefully fallback to collapse to a single span if there are multiple or missing client/server spans
  */
class PartialSpanTransformer extends SpanTreeTransformer {
  override def transform(spanForest: MutableSpanForest): MutableSpanForest = {
    var hasAnySpanMerged = false

    val mergedSpans: Seq[Span] = spanForest.getUnderlyingSpans.groupBy(_.getSpanId).map((pair) => pair._2 match {
      case Seq(span: Span) => span
      case list: Seq[Span] =>
        hasAnySpanMerged = true
        mergeSpans(list)
    }).toSeq

    spanForest.updateUnderlyingSpans(mergedSpans, hasAnySpanMerged)
  }

  private def mergeSpans(spans: Seq[Span]): Span = {
    val serverSpanOptional = collapseSpans(spans.filter(SpanUtils.containsServerLogTag))
    val clientSpanOptional = collapseSpans(spans.filter(SpanUtils.containsClientLogTag))

    (clientSpanOptional, serverSpanOptional) match {
      // ideally there should be one server and one client span
      // merging these partial spans to form a new single span
      case (Some(clientSpan), Some(serverSpan)) =>
        Span
          .newBuilder(serverSpan)
          .setParentSpanId(clientSpan.getParentSpanId) // use the parentSpanId of the client span to stitch in the client's trace tree
          .addAllTags((clientSpan.getTagsList.asScala ++ auxiliaryCommonTags(clientSpan, serverSpan) ++ auxiliaryClientTags(clientSpan) ++ auxiliaryServerTags(serverSpan)).asJavaCollection)
          .clearLogs().addAllLogs((clientSpan.getLogsList.asScala ++ serverSpan.getLogsList.asScala.sortBy(_.getTimestamp)).asJavaCollection)
          .build()

      // imperfect scenario, fallback to return available server span
      case (None, Some(serverSpan)) => serverSpan

      // imperfect scenario, fallback to return available client span
      case (Some(clientSpan), None) => clientSpan

      // imperfect scenario, fallback to collapse all spans
      case _ => collapseSpans(spans).get
    }
  }

  // collapse all spans of a type(eg. client or server) if needed,
  // ideally there would be just one span in the list and hence no need of collapsing
  private def collapseSpans(spans: Seq[Span]): Option[Span] = {
    spans match {
      case Nil => None
      case Seq(span) => Some(span)
      case _ =>
        // if there are multiple spans fallback to collapse all the spans in a single span
        // start the collapsed span from startTime of the first and end at ending of last such span
        // also add an error marker in the collapsed span
        val firstSpan = spans.minBy(_.getStartTime)
        val lastSpan = spans.maxBy(span => span.getStartTime + span.getDuration)
        val allTags = spans.flatMap(span => span.getTagsList.asScala)
        val allLogs = spans.flatMap(span => span.getLogsList.asScala)
        val opName = spans.map(_.getOperationName).reduce((a, b) => a + " & " + b)

        Some(
          Span
            .newBuilder(firstSpan)
            .setOperationName(opName)
            .setDuration(lastSpan.getStartTime + lastSpan.getDuration - firstSpan.getStartTime)
            .clearTags().addAllTags(allTags.asJava)
            .addTags(buildBoolTag(AuxiliaryTags.ERR_IS_MULTI_PARTIAL_SPAN, tagValue = true))
            .clearLogs().addAllLogs(allLogs.asJava)
            .build())
    }
  }

  // Network delta - difference between server and client duration
  // calculate only if serverDuration is smaller then client
  private def calculateNetworkDelta(clientSpan: Span, serverSpan: Span): Option[Long] = {
    val clientDuration = SpanUtils.getEventTimestamp(clientSpan, SpanMarkers.CLIENT_RECV_EVENT) - SpanUtils.getEventTimestamp(clientSpan, SpanMarkers.CLIENT_SEND_EVENT)
    val serverDuration = SpanUtils.getEventTimestamp(serverSpan, SpanMarkers.SERVER_SEND_EVENT) - SpanUtils.getEventTimestamp(serverSpan, SpanMarkers.SERVER_RECV_EVENT)

    // difference of duration of spans
    if (serverDuration < clientDuration) {
      Some(clientDuration - serverDuration)
    } else {
      None
    }
  }

  private def auxiliaryCommonTags(clientSpan: Span, serverSpan: Span): List[Tag]  =
    List(
      buildBoolTag(AuxiliaryTags.IS_MERGED_SPAN, tagValue = true),
      buildLongTag(AuxiliaryTags.NETWORK_DELTA, calculateNetworkDelta(clientSpan, serverSpan).getOrElse(-1))
    )

  private def auxiliaryClientTags(span: Span): List[Tag] =
    List(
      buildStringTag(AuxiliaryTags.CLIENT_SERVICE_NAME, span.getServiceName),
      buildStringTag(AuxiliaryTags.CLIENT_OPERATION_NAME, span.getOperationName),
      buildStringTag(AuxiliaryTags.CLIENT_INFRASTRUCTURE_PROVIDER, extractTagStringValue(span, AuxiliaryTags.INFRASTRUCTURE_PROVIDER)),
      buildStringTag(AuxiliaryTags.CLIENT_INFRASTRUCTURE_LOCATION, extractTagStringValue(span, AuxiliaryTags.INFRASTRUCTURE_LOCATION)),
      buildLongTag(AuxiliaryTags.CLIENT_START_TIME, span.getStartTime),
      buildLongTag(AuxiliaryTags.CLIENT_DURATION, span.getDuration)
    )

  private def auxiliaryServerTags(span: Span): List[Tag] = {
    List(
      buildStringTag(AuxiliaryTags.SERVER_SERVICE_NAME, span.getServiceName),
      buildStringTag(AuxiliaryTags.SERVER_OPERATION_NAME, span.getOperationName),
      buildStringTag(AuxiliaryTags.SERVER_INFRASTRUCTURE_PROVIDER, extractTagStringValue(span, AuxiliaryTags.INFRASTRUCTURE_PROVIDER)),
      buildStringTag(AuxiliaryTags.SERVER_INFRASTRUCTURE_LOCATION, extractTagStringValue(span, AuxiliaryTags.INFRASTRUCTURE_LOCATION)),
      buildLongTag(AuxiliaryTags.SERVER_START_TIME, span.getStartTime),
      buildLongTag(AuxiliaryTags.SERVER_DURATION, span.getDuration)
    )
  }
}
