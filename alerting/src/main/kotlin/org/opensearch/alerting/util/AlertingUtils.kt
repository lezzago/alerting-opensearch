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

package org.opensearch.alerting.util

import org.opensearch.OpenSearchStatusException
import org.opensearch.action.ActionListener
import org.opensearch.alerting.model.AggregationResultBucket
import org.opensearch.alerting.model.BucketLevelTriggerRunResult
import org.opensearch.alerting.model.Monitor
import org.opensearch.alerting.model.action.Action
import org.opensearch.alerting.model.action.ActionExecutionPolicy
import org.opensearch.alerting.model.destination.Destination
import org.opensearch.alerting.settings.DestinationSettings
import org.opensearch.commons.authuser.User
import org.opensearch.rest.RestStatus

/**
 * RFC 5322 compliant pattern matching: https://www.ietf.org/rfc/rfc5322.txt
 * Regex was based off of this post: https://stackoverflow.com/a/201378
 */
fun isValidEmail(email: String): Boolean {
    val validEmailPattern = Regex(
        "(?:[a-z0-9!#\$%&'*+\\/=?^_`{|}~-]+(?:\\.[a-z0-9!#\$%&'*+\\/=?^_`{|}~-]+)*" +
            "|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")" +
            "@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?" +
            "|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}" +
            "(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:" +
            "(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])",
        RegexOption.IGNORE_CASE
    )

    return validEmailPattern.matches(email)
}

/** Allowed Destinations are ones that are specified in the [DestinationSettings.ALLOW_LIST] setting. */
fun Destination.isAllowed(allowList: List<String>): Boolean = allowList.contains(this.type.value)

/**
 1. If filterBy is enabled
 a) Don't allow to create monitor/ destination (throw error) if the logged-on user has no backend roles configured.
 2. If filterBy is enabled & monitors are created when filterBy is disabled:
 a) If backend_roles are saved with config, results will get filtered and data is shown
 b) If backend_roles are not saved with monitor config, results will get filtered and no monitors
 will be displayed.
 c) Users can edit and save the monitors to associate their backend_roles.
 3. If filterBy is enabled & monitors are created by older version:
 a) No User details are present on monitor.
 b) No monitors will be displayed.
 c) Users can edit and save the monitors to associate their backend_roles.
 */
fun <T : Any> checkFilterByUserBackendRoles(filterByEnabled: Boolean, user: User?, actionListener: ActionListener<T>): Boolean {
    if (filterByEnabled) {
        if (user == null) {
            actionListener.onFailure(
                AlertingException.wrap(
                    OpenSearchStatusException(
                        "Filter by user backend roles is not enabled with security disabled.", RestStatus.FORBIDDEN
                    )
                )
            )
            return false
        } else if (user.backendRoles.isNullOrEmpty()) {
            actionListener.onFailure(
                AlertingException.wrap(
                    OpenSearchStatusException("User doesn't have backend roles configured. Contact administrator.", RestStatus.FORBIDDEN)
                )
            )
            return false
        }
    }
    return true
}

/**
 * If FilterBy is enabled, this function verifies that the requester user has FilterBy permissions to access
 * the resource. If FilterBy is disabled, we will assume the user has permissions and return true.
 *
 * This check will later to moved to the security plugin.
 */
fun <T : Any> checkUserFilterByPermissions(
    filterByEnabled: Boolean,
    requesterUser: User?,
    resourceUser: User?,
    actionListener: ActionListener<T>,
    resourceType: String,
    resourceId: String
): Boolean {

    if (!filterByEnabled) return true

    val resourceBackendRoles = resourceUser?.backendRoles
    val requesterBackendRoles = requesterUser?.backendRoles

    if (resourceBackendRoles == null || requesterBackendRoles == null || resourceBackendRoles.intersect(requesterBackendRoles).isEmpty()) {
        actionListener.onFailure(
            AlertingException.wrap(
                OpenSearchStatusException(
                    "Do not have permissions to resource, $resourceType, with id, $resourceId",
                    RestStatus.FORBIDDEN
                )
            )
        )
        return false
    }
    return true
}

fun Monitor.isBucketLevelMonitor(): Boolean = this.monitorType == Monitor.MonitorType.BUCKET_LEVEL_MONITOR

/**
 * Since buckets can have multi-value keys, this converts the bucket key values to a string that can be used
 * as the key for a HashMap to easily retrieve [AggregationResultBucket] based on the bucket key values.
 */
fun AggregationResultBucket.getBucketKeysHash(): String = this.bucketKeys.joinToString(separator = "#")

fun Action.getActionExecutionPolicy(monitor: Monitor): ActionExecutionPolicy? {
    // When the ActionExecutionPolicy is null for an Action, the default is resolved at runtime
    // so it can be chosen based on the Monitor type at that time.
    // The Action config is not aware of the Monitor type which is why the default was not stored during
    // the parse.
    return this.actionExecutionPolicy ?: if (monitor.isBucketLevelMonitor()) {
        ActionExecutionPolicy.getDefaultConfigurationForBucketLevelMonitor()
    } else {
        null
    }
}

fun BucketLevelTriggerRunResult.getCombinedTriggerRunResult(
    prevTriggerRunResult: BucketLevelTriggerRunResult?
): BucketLevelTriggerRunResult {
    if (prevTriggerRunResult == null) return this

    // The aggregation results and action results across to two trigger run results should not have overlapping keys
    // since they represent different pages of aggregations so a simple concatenation will combine them
    val mergedAggregationResultBuckets = prevTriggerRunResult.aggregationResultBuckets + this.aggregationResultBuckets
    val mergedActionResultsMap = (prevTriggerRunResult.actionResultsMap + this.actionResultsMap).toMutableMap()

    // Update to the most recent error if it's not null, otherwise keep the old one
    val error = this.error ?: prevTriggerRunResult.error

    return this.copy(aggregationResultBuckets = mergedAggregationResultBuckets, actionResultsMap = mergedActionResultsMap, error = error)
}

fun updateKeyValueFromOtherMap(source: MutableMap<String, Any>, mods: Map<String, String>) {
    val entries = source.entries
    for (entry in entries) {
        if (mods.containsKey(entry.key)) {
            source[mods[entry.key]!!] = source[entry.key]!!
            source.remove(entry.key)
        }

        if (entry.value is String && mods.containsKey(entry.value as String))
            source[entry.key] = mods[entry.value as String]!!

        if (entry.value is Map<*, *>)
            updateKeyValueFromOtherMap(entry.value as MutableMap<String, Any>, mods)

        if (entry.value is List<*>) {
            for (element in entry.value as List<*>) {
                if (element is Map<*, *>)
                    updateKeyValueFromOtherMap(element as MutableMap<String, Any>, mods)
                if (element is String && mods.containsKey(element as String)) {
                    // Figure out how to do this cleanly
                    (entry.value as List<*>).plus(mods[element])
                    continue
                }
            }
        }
    }
}
