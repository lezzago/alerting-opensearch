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
import org.opensearch.alerting.action.SearchEmailGroupAction
import org.opensearch.alerting.action.SearchEmailGroupRequest
import org.opensearch.alerting.action.SearchEmailGroupResponse
import org.opensearch.alerting.actionconverter.EmailGroupActionsConverter.Companion.convertGetNotificationConfigResponseToSearchEmailGroupResponse
import org.opensearch.alerting.actionconverter.EmailGroupActionsConverter.Companion.convertSearchEmailGroupRequestToGetNotificationConfigRequest
import org.opensearch.alerting.core.model.ScheduledJob
import org.opensearch.alerting.model.destination.email.EmailGroup
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
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.ConfigConstants
import org.opensearch.commons.authuser.User
import org.opensearch.index.query.QueryBuilders
import org.opensearch.rest.RestStatus
import org.opensearch.search.builder.SearchSourceBuilder
import org.opensearch.search.fetch.subphase.FetchSourceContext
import org.opensearch.search.sort.SortBuilders
import org.opensearch.search.sort.SortOrder
import org.opensearch.tasks.Task
import org.opensearch.transport.TransportService

private val log = LogManager.getLogger(TransportSearchEmailGroupAction::class.java)

class TransportSearchEmailGroupAction @Inject constructor(
    transportService: TransportService,
    val client: NodeClient,
    actionFilters: ActionFilters,
    val clusterService: ClusterService,
    settings: Settings,
    val xContentRegistry: NamedXContentRegistry
) : HandledTransportAction<SearchEmailGroupRequest, SearchEmailGroupResponse>(
    SearchEmailGroupAction.NAME, transportService, actionFilters, ::SearchEmailGroupRequest
) {

    @Volatile private var allowList = ALLOW_LIST.get(settings)

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(ALLOW_LIST) { allowList = it }
    }

    override fun doExecute(task: Task, searchEmailGroupRequest: SearchEmailGroupRequest, actionListener: ActionListener<SearchEmailGroupResponse>) {
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

        val searchEmailGroupResponse: SearchEmailGroupResponse
        try {
            val getRequest = convertSearchEmailGroupRequestToGetNotificationConfigRequest(searchEmailGroupRequest)
            val getNotificationConfigResponse = NotificationAPIUtils.getNotificationConfig(client, getRequest)
            searchEmailGroupResponse = convertGetNotificationConfigResponseToSearchEmailGroupResponse(getNotificationConfigResponse)
        } catch (e: Exception) {
            actionListener.onFailure(AlertingException.wrap(e))
            return
        }

        val tableProp = searchEmailGroupRequest.table

        val sortBuilder = SortBuilders
            .fieldSort(tableProp.sortString)
            .order(SortOrder.fromString(tableProp.sortOrder))

        val searchSourceBuilder = SearchSourceBuilder()
            .sort(sortBuilder)
            .size(tableProp.size - searchEmailGroupResponse.emailGroups.size)
            .from(tableProp.startIndex)
            .fetchSource(FetchSourceContext(true, Strings.EMPTY_ARRAY, Strings.EMPTY_ARRAY))
            .seqNoAndPrimaryTerm(true)
            .version(true)
        val queryBuilder = QueryBuilders.boolQuery()
            .must(QueryBuilders.existsQuery("email_group"))

        if (!tableProp.searchString.isNullOrBlank()) {
            queryBuilder
                .must(
                    QueryBuilders
                        .queryStringQuery(tableProp.searchString)
                        .field("email_group.name")
                )
        }

        searchSourceBuilder.query(queryBuilder)

        client.threadPool().threadContext.stashContext().use {
            search(searchSourceBuilder, searchEmailGroupResponse, actionListener)
        }
    }

    fun search(
        searchSourceBuilder: SearchSourceBuilder,
        searchEmailGroupResponse: SearchEmailGroupResponse,
        actionListener: ActionListener<SearchEmailGroupResponse>
    ) {
        val searchRequest = SearchRequest()
            .source(searchSourceBuilder)
            .indices(ScheduledJob.SCHEDULED_JOBS_INDEX)
        client.search(
            searchRequest,
            object : ActionListener<SearchResponse> {
                override fun onResponse(response: SearchResponse) {
                    var totalEmailGroupCount = response.hits.totalHits?.value?.toInt() ?: 0
                    val emailGroups = mutableListOf<EmailGroup>()
                    for (hit in response.hits) {
                        val xcp = XContentFactory.xContent(XContentType.JSON)
                            .createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, hit.sourceAsString)
                        val emailGroup = EmailGroup.parseWithType(xcp, hit.id, hit.version)
                        emailGroups.add(emailGroup)
                    }
                    totalEmailGroupCount += searchEmailGroupResponse.totalEmailGroups ?: 0
                    emailGroups.addAll(searchEmailGroupResponse.emailGroups)
                    val getResponse = SearchEmailGroupResponse(
                        RestStatus.OK,
                        totalEmailGroupCount,
                        emailGroups
                    )
                    actionListener.onResponse(getResponse)
                }

                override fun onFailure(t: Exception) {
                    log.warn("Failed to search email groups from alerting config index", t)
                    actionListener.onResponse(searchEmailGroupResponse)
                }
            }
        )
    }
}
