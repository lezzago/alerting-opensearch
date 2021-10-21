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
import org.opensearch.alerting.action.SearchEmailAccountAction
import org.opensearch.alerting.core.model.ScheduledJob.Companion.SCHEDULED_JOBS_INDEX
import org.opensearch.alerting.elasticapi.convertToMap
import org.opensearch.alerting.model.destination.email.EmailAccount
import org.opensearch.alerting.model.notification.NotificationConfigDoc
import org.opensearch.alerting.util.IndexUtils
import org.opensearch.alerting.util.context
import org.opensearch.alerting.util.updateKeyValueFromOtherMap
import org.opensearch.client.node.NodeClient
import org.opensearch.common.Strings
import org.opensearch.common.bytes.BytesReference
import org.opensearch.common.xcontent.LoggingDeprecationHandler
import org.opensearch.common.xcontent.NamedXContentRegistry
import org.opensearch.common.xcontent.ToXContent.EMPTY_PARAMS
import org.opensearch.common.xcontent.XContentFactory.jsonBuilder
import org.opensearch.common.xcontent.XContentParser
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.notifications.model.ConfigType
import org.opensearch.commons.notifications.model.MethodType
import org.opensearch.commons.notifications.model.SmtpAccount
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
 * Rest handlers to search for EmailAccount
 */
class RestSearchEmailAccountAction : BaseRestHandler() {

    private val log = LogManager.getLogger(RestSearchEmailAccountAction::class.java)

    override fun getName(): String {
        return "search_email_account_action"
    }

    override fun routes(): List<Route> {
        return listOf(
        )
    }

    override fun replacedRoutes(): MutableList<ReplacedRoute> {
        return mutableListOf(
            ReplacedRoute(
                RestRequest.Method.POST,
                "${AlertingPlugin.EMAIL_ACCOUNT_BASE_URI}/_search",
                RestRequest.Method.POST,
                "${AlertingPlugin.LEGACY_OPENDISTRO_EMAIL_ACCOUNT_BASE_URI}/_search"
            ),
            ReplacedRoute(
                RestRequest.Method.GET,
                "${AlertingPlugin.EMAIL_ACCOUNT_BASE_URI}/_search",
                RestRequest.Method.GET,
                "${AlertingPlugin.LEGACY_OPENDISTRO_EMAIL_ACCOUNT_BASE_URI}/_search"
            )
        )
    }

    @Throws(IOException::class)
    override fun prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer {
        val searchSourceBuilder = SearchSourceBuilder()
        searchSourceBuilder.parseXContent(request.contentOrSourceParamParser())
        searchSourceBuilder.fetchSource(context(request))

        log.info("email account query: ${searchSourceBuilder.convertToMap()}")

        searchSourceBuilder.query(
            QueryBuilders.boolQuery().must(searchSourceBuilder.query())
                .must(QueryBuilders.matchQuery("config.config_type", "smtp_account"))
//                    .filter(QueryBuilders.existsQuery("config.smtp_account"))
        )

        val mappedQuery = searchSourceBuilder.convertToMap()

        val conversionMap = mutableMapOf(
            Pair("email_account.name", "config.name"),
            Pair("email_account.name.keyword", "config.name.keyword"),
            Pair("email_account.host", "config.smtp_account.host"),
            Pair("email_account.host.keyword", "config.smtp_account.host.keyword"),
            Pair("email_account.port", "config.smtp_account.port"),
            Pair("email_account.method", "config.smtp_account.method"),
            Pair("email_account.from", "config.smtp_account.from_address"),
            Pair("email_account.from.keyword", "config.smtp_account.from_address.keyword")
        )

        updateKeyValueFromOtherMap(mappedQuery as MutableMap<String, Any>, conversionMap)

        val builder = jsonBuilder()
        builder.map(mappedQuery)
        val json = Strings.toString(builder)

        val parser: XContentParser = XContentType.JSON.xContent()
            .createParser(request.xContentRegistry, LoggingDeprecationHandler.INSTANCE, json)

        val updatedSearchSourceBuilder = SearchSourceBuilder()
        updatedSearchSourceBuilder.parseXContent(parser)

        val searchRequest = SearchRequest()
            .source(updatedSearchSourceBuilder)
            .indices(".opensearch-notifications-config")
        return RestChannelConsumer { channel ->
            client.execute(SearchEmailAccountAction.INSTANCE, searchRequest, searchEmailAccountResponse2(channel))
        }
    }

    private fun searchEmailAccountResponse(channel: RestChannel): RestResponseListener<SearchResponse> {
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
                        val emailAccount = EmailAccount.parseWithType(hitsParser, hit.id, hit.version)
                        val xcb = emailAccount.toXContent(jsonBuilder(), EMPTY_PARAMS)
                        hit.sourceRef(BytesReference.bytes(xcb))
                    }
                }
                return BytesRestResponse(RestStatus.OK, response.toXContent(channel.newBuilder(), EMPTY_PARAMS))
            }
        }
    }

    private fun searchEmailAccountResponse2(channel: RestChannel): RestResponseListener<SearchResponse> {
        return object : RestResponseListener<SearchResponse>(channel) {
            @Throws(Exception::class)
            override fun buildResponse(response: SearchResponse): RestResponse {
                if (response.isTimedOut) {
                    return BytesRestResponse(RestStatus.REQUEST_TIMEOUT, response.toString())
                }

                for (hit in response.hits) {
                    XContentType.JSON.xContent().createParser(
                        NamedXContentRegistry.EMPTY,
                        LoggingDeprecationHandler.INSTANCE, hit.sourceAsString
                    ).use { hitsParser ->
//                        log.info("This is sourceHitString")
//                        log.info(hit.sourceAsString)
                        hitsParser.nextToken()
                        val notificationConfigDoc = NotificationConfigDoc.parse(hitsParser)
                        val notificationConfig = notificationConfigDoc.config
                        if (notificationConfig.configType == ConfigType.SMTP_ACCOUNT) {
                            val smtpAccount: SmtpAccount = notificationConfig.configData as SmtpAccount
                            val methodType = convertNotificationToAlertingMethodType(smtpAccount.method)
                            val emailAccount = EmailAccount(
                                hit.id,
                                EmailAccount.NO_VERSION,
                                IndexUtils.NO_SCHEMA_VERSION,
                                notificationConfig.name,
                                smtpAccount.fromAddress,
                                smtpAccount.host,
                                smtpAccount.port,
                                methodType,
                                null,
                                null
                            )
                            val xcb = emailAccount.toXContent(jsonBuilder(), EMPTY_PARAMS)
                            hit.sourceRef(BytesReference.bytes(xcb))
                        }
                    }
                }
                return BytesRestResponse(RestStatus.OK, response.toXContent(channel.newBuilder(), EMPTY_PARAMS))
            }
        }
    }

    private fun convertNotificationToAlertingMethodType(notificationMethodType: MethodType): EmailAccount.MethodType {
        return when (notificationMethodType) {
            MethodType.NONE -> EmailAccount.MethodType.NONE
            MethodType.SSL -> EmailAccount.MethodType.SSL
            MethodType.START_TLS -> EmailAccount.MethodType.TLS
            else -> EmailAccount.MethodType.NONE
        }
    }
}
