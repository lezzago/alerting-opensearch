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
 *   Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package org.opensearch.alerting.transport

import org.apache.logging.log4j.LogManager
import org.opensearch.OpenSearchStatusException
import org.opensearch.action.ActionListener
import org.opensearch.action.search.SearchRequest
import org.opensearch.action.search.SearchResponse
import org.opensearch.action.support.ActionFilters
import org.opensearch.action.support.HandledTransportAction
import org.opensearch.alerting.action.GetDestinationsResponse
import org.opensearch.alerting.action.SearchEmailAccountAction
import org.opensearch.alerting.action.SearchEmailAccountRequest
import org.opensearch.alerting.action.SearchEmailAccountResponse
import org.opensearch.alerting.actionconverter.EmailAccountActionsConverter.Companion.convertGetNotificationConfigResponseToSearchEmailAccountResponse
import org.opensearch.alerting.actionconverter.EmailAccountActionsConverter.Companion.convertSearchEmailAccountRequestToGetNotificationConfigRequest
import org.opensearch.alerting.core.model.ScheduledJob
import org.opensearch.alerting.model.destination.Destination
import org.opensearch.alerting.model.destination.email.EmailAccount
import org.opensearch.alerting.settings.DestinationSettings.Companion.ALLOW_LIST
import org.opensearch.alerting.util.AlertingException
import org.opensearch.alerting.util.DestinationType
import org.opensearch.alerting.util.NotificationAPIUtils
import org.opensearch.client.node.NodeClient
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.Strings
import org.opensearch.common.inject.Inject
import org.opensearch.common.settings.Settings
import org.opensearch.common.xcontent.LoggingDeprecationHandler
import org.opensearch.common.xcontent.NamedXContentRegistry
import org.opensearch.common.xcontent.XContentFactory
import org.opensearch.common.xcontent.XContentParser
import org.opensearch.common.xcontent.XContentParserUtils
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.ConfigConstants
import org.opensearch.commons.authuser.User
import org.opensearch.index.query.Operator
import org.opensearch.index.query.QueryBuilders
import org.opensearch.rest.RestStatus
import org.opensearch.search.builder.SearchSourceBuilder
import org.opensearch.search.fetch.subphase.FetchSourceContext
import org.opensearch.search.sort.SortBuilders
import org.opensearch.search.sort.SortOrder
import org.opensearch.tasks.Task
import org.opensearch.transport.TransportService

private val log = LogManager.getLogger(TransportSearchEmailAccountAction::class.java)

class TransportSearchEmailAccountAction @Inject constructor(
    transportService: TransportService,
    val client: NodeClient,
    actionFilters: ActionFilters,
    val clusterService: ClusterService,
    settings: Settings,
    val xContentRegistry: NamedXContentRegistry
) : HandledTransportAction<SearchEmailAccountRequest, SearchEmailAccountResponse>(
    SearchEmailAccountAction.NAME, transportService, actionFilters, ::SearchEmailAccountRequest
) {

    @Volatile private var allowList = ALLOW_LIST.get(settings)

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(ALLOW_LIST) { allowList = it }
    }

    override fun doExecute(
        task: Task,
        searchEmailAccountRequest: SearchEmailAccountRequest,
        actionListener: ActionListener<SearchEmailAccountResponse>
    ) {

        if (!allowList.contains(DestinationType.EMAIL.value)) {
            actionListener.onFailure(
                AlertingException.wrap(
                    OpenSearchStatusException(
                        "This API is blocked since Destination type [${DestinationType.EMAIL}] is not allowed",
                        RestStatus.FORBIDDEN
                    )
                )
            )
            return
        }

        val searchEmailAccountResponse: SearchEmailAccountResponse
        try {
            val getRequest = convertSearchEmailAccountRequestToGetNotificationConfigRequest(searchEmailAccountRequest)
            val getNotificationConfigResponse = NotificationAPIUtils.getNotificationConfig(client, getRequest)
            searchEmailAccountResponse = convertGetNotificationConfigResponseToSearchEmailAccountResponse(getNotificationConfigResponse)
        } catch (e: Exception) {
            actionListener.onFailure(AlertingException.wrap(e))
            return
        }

        val tableProp = searchEmailAccountRequest.table

        val sortBuilder = SortBuilders
            .fieldSort(tableProp.sortString)
            .order(SortOrder.fromString(tableProp.sortOrder))

        val searchSourceBuilder = SearchSourceBuilder()
            .sort(sortBuilder)
            .size(tableProp.size - searchEmailAccountResponse.emailAccounts.size)
            .from(tableProp.startIndex)
            .fetchSource(FetchSourceContext(true, Strings.EMPTY_ARRAY, Strings.EMPTY_ARRAY))
            .seqNoAndPrimaryTerm(true)
            .version(true)
        val queryBuilder = QueryBuilders.boolQuery()
            .must(QueryBuilders.existsQuery("email_account"))

        if (!tableProp.searchString.isNullOrBlank()) {
            queryBuilder
                .must(
                    QueryBuilders
                        .queryStringQuery(tableProp.searchString)
                        .defaultOperator(Operator.AND)
                        .field("email_account.name")
                        .field("email_account.host")
                        .field("email_account.from")
                )
        }

        searchSourceBuilder.query(queryBuilder)

        client.threadPool().threadContext.stashContext().use {
            search(searchSourceBuilder, searchEmailAccountResponse, actionListener)
        }
    }

    fun search(
        searchSourceBuilder: SearchSourceBuilder,
        searchEmailAccountResponse: SearchEmailAccountResponse,
        actionListener: ActionListener<SearchEmailAccountResponse>
    ) {
        val searchRequest = SearchRequest()
            .source(searchSourceBuilder)
            .indices(ScheduledJob.SCHEDULED_JOBS_INDEX)
        client.search(
            searchRequest,
            object : ActionListener<SearchResponse> {
                override fun onResponse(response: SearchResponse) {
                    var totalEmailAccountCount = response.hits.totalHits?.value?.toInt() ?: 0
                    val emailAccounts = mutableListOf<EmailAccount>()
                    for (hit in response.hits) {
                        val xcp = XContentFactory.xContent(XContentType.JSON)
                            .createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, hit.sourceAsString)
                        val emailAccount = EmailAccount.parseWithType(xcp, hit.id, hit.version)
                        emailAccounts.add(emailAccount)
                    }
                    totalEmailAccountCount += searchEmailAccountResponse.totalEmailAccounts ?: 0
                    emailAccounts.addAll(searchEmailAccountResponse.emailAccounts)
                    val getResponse = SearchEmailAccountResponse(
                        RestStatus.OK,
                        totalEmailAccountCount,
                        emailAccounts
                    )
                    actionListener.onResponse(getResponse)
                }

                override fun onFailure(t: Exception) {
                    log.warn("Failed to search email accounts from alerting config index", t)
                    actionListener.onResponse(searchEmailAccountResponse)
                }
            }
        )
    }
}
