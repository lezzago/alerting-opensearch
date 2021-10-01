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
import org.opensearch.alerting.AlertingPlugin
import org.opensearch.alerting.action.SearchEmailAccountAction
import org.opensearch.alerting.action.SearchEmailAccountRequest
import org.opensearch.alerting.model.Table
import org.opensearch.client.node.NodeClient
import org.opensearch.rest.BaseRestHandler
import org.opensearch.rest.BaseRestHandler.RestChannelConsumer
import org.opensearch.rest.RestHandler.ReplacedRoute
import org.opensearch.rest.RestHandler.Route
import org.opensearch.rest.RestRequest
import org.opensearch.rest.action.RestToXContentListener
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
        return listOf()
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
        log.debug("${request.method()} ${request.path()}")

        val sortString = request.param("sortString", "destination.name.keyword")
        val sortOrder = request.param("sortOrder", "asc")
        val size = request.paramAsInt("size", 20)
        val startIndex = request.paramAsInt("startIndex", 0)
        val searchString = request.param("searchString", "")

        val table = Table(
            sortOrder,
            sortString,
            null,
            size,
            startIndex,
            searchString
        )

        val searchEmailAccountRequest = SearchEmailAccountRequest(table)

        return RestChannelConsumer { channel ->
            client.execute(SearchEmailAccountAction.INSTANCE, searchEmailAccountRequest, RestToXContentListener(channel))
        }
    }
}
