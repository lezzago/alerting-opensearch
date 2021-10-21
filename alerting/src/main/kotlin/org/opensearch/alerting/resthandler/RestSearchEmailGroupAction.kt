/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 *   Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package org.opensearch.alerting.resthandler

import org.apache.logging.log4j.LogManager
import org.opensearch.action.search.SearchRequest
import org.opensearch.action.search.SearchResponse
import org.opensearch.alerting.AlertingPlugin
import org.opensearch.alerting.action.SearchEmailGroupAction
import org.opensearch.alerting.core.model.ScheduledJob.Companion.SCHEDULED_JOBS_INDEX
import org.opensearch.alerting.elasticapi.convertToMap
import org.opensearch.alerting.model.destination.email.EmailAccount
import org.opensearch.alerting.model.destination.email.EmailEntry
import org.opensearch.alerting.model.destination.email.EmailGroup
import org.opensearch.alerting.model.notification.NotificationConfigDoc
import org.opensearch.alerting.util.IndexUtils
import org.opensearch.alerting.util.context
import org.opensearch.alerting.util.updateKeyValueFromOtherMap
import org.opensearch.client.node.NodeClient
import org.opensearch.common.Strings
import org.opensearch.common.bytes.BytesReference
import org.opensearch.common.xcontent.LoggingDeprecationHandler
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.XContentFactory
import org.opensearch.common.xcontent.XContentParser
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.notifications.model.ConfigType
import org.opensearch.index.query.QueryBuilders
import org.opensearch.rest.BaseRestHandler
import org.opensearch.rest.BaseRestHandler.RestChannelConsumer
import org.opensearch.rest.BytesRestResponse
import org.opensearch.rest.RestChannel
import org.opensearch.rest.RestHandler.ReplacedRoute
import org.opensearch.rest.RestHandler.Route
import org.opensearch.rest.RestRequest
import org.opensearch.rest.RestResponse
import org.opensearch.rest.RestStatus
import org.opensearch.rest.action.RestResponseListener
import org.opensearch.search.builder.SearchSourceBuilder
import java.io.IOException

/**
 * Rest handlers to search for EmailGroup
 */
class RestSearchEmailGroupAction : BaseRestHandler() {

    private val log = LogManager.getLogger(RestSearchEmailGroupAction::class.java)

    override fun getName(): String {
        return "search_email_group_action"
    }

    override fun routes(): List<Route> {
        return listOf(
        )
    }

    override fun replacedRoutes(): MutableList<ReplacedRoute> {
        return mutableListOf(
            ReplacedRoute(
                RestRequest.Method.POST,
                "${AlertingPlugin.EMAIL_GROUP_BASE_URI}/_search",
                RestRequest.Method.POST,
                "${AlertingPlugin.LEGACY_OPENDISTRO_EMAIL_GROUP_BASE_URI}/_search"
            ),
            ReplacedRoute(
                RestRequest.Method.GET,
                "${AlertingPlugin.EMAIL_GROUP_BASE_URI}/_search",
                RestRequest.Method.GET,
                "${AlertingPlugin.LEGACY_OPENDISTRO_EMAIL_GROUP_BASE_URI}/_search"
            )
        )
    }

    @Throws(IOException::class)
    override fun prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer {
        val searchSourceBuilder = SearchSourceBuilder()
        searchSourceBuilder.parseXContent(request.contentOrSourceParamParser())
        searchSourceBuilder.fetchSource(context(request))

        log.info("email group query: ${searchSourceBuilder.convertToMap()}")

        searchSourceBuilder.query(
            QueryBuilders.boolQuery().must(searchSourceBuilder.query())
                .must(QueryBuilders.matchQuery("config.config_type", "email_group"))
        )

//            log.info("This is the searchSource")
//            log.info(searchSourceBuilder.toString())

        val mappedQuery = searchSourceBuilder.convertToMap()
//            log.info("This is the original mapped query: $mappedQuery")

        // Assuming that the Query DSL language does not contains the key values listed in conversion map
        // Assuming using is not part of their query text, where this is part of their data. For example data searching for is "email_group.name" which is part of some text data in the document
        val conversionMap = mutableMapOf(
            Pair("email_group.name", "config.name"),
            Pair("email_group.name.keyword", "config.name.keyword"),
            Pair("email_group.emails", "config.email_group.recipient_list"),
            Pair("email_group.emails.email", "config.email_group.recipient_list.recipient")
        )
//            val wasModified = XContentHelper.update(mappedQuery, conversionMap as Map<String, Any>?, true)
//            log.info("the map was modified? $wasModified")
//            log.info("This is the updated mapped query: $mappedQuery")
        updateKeyValueFromOtherMap(mappedQuery as MutableMap<String, Any>, conversionMap)

        val builder = XContentFactory.jsonBuilder()
        builder.map(mappedQuery)
        val json = Strings.toString(builder)
//            log.info("This is the updated mapped query: $json")
//            val json = String(builder.bytes(), "UTF-8")

//            val queryString = searchSourceBuilder.toString()
//            val modifiedString = queryString
//                .replace("email_group.name", "config.name")
//                .replace("email_group.emails", "config.email_group.recipient_list")
        val parser: XContentParser = XContentType.JSON.xContent()
            .createParser(request.xContentRegistry, LoggingDeprecationHandler.INSTANCE, json)

        val updatedSearchSourceBuilder = SearchSourceBuilder()
        updatedSearchSourceBuilder.parseXContent(parser)

        val searchRequest = SearchRequest()
            .source(updatedSearchSourceBuilder)
            .indices(".opensearch-notifications-config")
        return RestChannelConsumer { channel ->
            client.execute(SearchEmailGroupAction.INSTANCE, searchRequest, searchEmailGroupResponse2(channel))
        }
    }

    private fun searchEmailGroupResponse(channel: RestChannel): RestResponseListener<SearchResponse> {
        return object : RestResponseListener<SearchResponse>(channel) {
            @Throws(Exception::class)
            override fun buildResponse(response: SearchResponse): RestResponse {
                if (response.isTimedOut) {
                    return BytesRestResponse(RestStatus.REQUEST_TIMEOUT, response.toString())
                }

                for (hit in response.hits) {
                    XContentType.JSON.xContent().createParser(
                        channel.request().xContentRegistry,
                        LoggingDeprecationHandler.INSTANCE, hit.sourceAsString
                    ).use { hitsParser ->
                        val emailGroup = EmailGroup.parseWithType(hitsParser, hit.id, hit.version)
                        val xcb = emailGroup.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS)
                        hit.sourceRef(BytesReference.bytes(xcb))
                    }
                }
                return BytesRestResponse(RestStatus.OK, response.toXContent(channel.newBuilder(), ToXContent.EMPTY_PARAMS))
            }
        }
    }

    private fun searchEmailGroupResponse2(channel: RestChannel): RestResponseListener<SearchResponse> {
        return object : RestResponseListener<SearchResponse>(channel) {
            @Throws(Exception::class)
            override fun buildResponse(response: SearchResponse): RestResponse {
                if (response.isTimedOut) {
                    return BytesRestResponse(RestStatus.REQUEST_TIMEOUT, response.toString())
                }

                for (hit in response.hits) {
                    XContentType.JSON.xContent().createParser(
                        channel.request().xContentRegistry,
                        LoggingDeprecationHandler.INSTANCE, hit.sourceAsString
                    ).use { hitsParser ->
                        log.info("This data is missing for ")
                        log.info(hit.sourceAsString)
                        hitsParser.nextToken()
                        val notificationConfigDoc = NotificationConfigDoc.parse(hitsParser)
                        val notificationConfig = notificationConfigDoc.config
                        if (notificationConfig.configType == ConfigType.EMAIL_GROUP) {
                            val emailGroup: org.opensearch.commons.notifications.model.EmailGroup =
                                notificationConfig.configData as org.opensearch.commons.notifications.model.EmailGroup
                            val recipients = mutableListOf<EmailEntry>()
                            emailGroup.recipients.forEach {
                                recipients.add(EmailEntry(it.recipient))
                            }
                            val alertEmailGroup = EmailGroup(
                                hit.id,
                                EmailAccount.NO_VERSION,
                                IndexUtils.NO_SCHEMA_VERSION,
                                notificationConfig.name,
                                recipients
                            )
                            val xcb = alertEmailGroup.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS)
                            hit.sourceRef(BytesReference.bytes(xcb))
                        }
                    }
                }
                return BytesRestResponse(RestStatus.OK, response.toXContent(channel.newBuilder(), ToXContent.EMPTY_PARAMS))
            }
        }
    }

//    private fun updateKeyValueFromOtherMap(source: MutableMap<String, Any>, mods: Map<String, String>) {
//        val entries = source.entries
//        for (entry in entries) {
//            log.info("key: ${entry.key}, value: ${entry.value}")
//            if (mods.containsKey(entry.key)) {
//                source[mods[entry.key]!!] = source[entry.key]!!
//                source.remove(entry.key)
//            }
//
//            if (entry.value is String && mods.containsKey(entry.value as String))
//                source[entry.key] = mods[entry.value as String]!!
//
//            if (entry.value is Map<*, *>)
//                updateKeyValueFromOtherMap(entry.value as MutableMap<String, Any>, mods)
//
//            if (entry.value is List<*>) {
//                for (element in entry.value as List<*>) {
//                    if (element is Map<*, *>)
//                        updateKeyValueFromOtherMap(element as MutableMap<String, Any>, mods)
//                    if (element is String && mods.containsKey(element as String)) {
//                        // Figure out how to do this cleanly
//                        (entry.value as List<*>).plus(mods[element])
//                        continue
//                    }
//                }
//            }
//        }
//    }
}
