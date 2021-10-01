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

package org.opensearch.alerting.action

import org.opensearch.action.ActionResponse
import org.opensearch.alerting.model.destination.email.EmailAccount
import org.opensearch.alerting.model.destination.email.EmailGroup
import org.opensearch.alerting.util._ID
import org.opensearch.alerting.util._PRIMARY_TERM
import org.opensearch.alerting.util._SEQ_NO
import org.opensearch.alerting.util._VERSION
import org.opensearch.common.io.stream.StreamInput
import org.opensearch.common.io.stream.StreamOutput
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.ToXContentObject
import org.opensearch.common.xcontent.XContentBuilder
import org.opensearch.rest.RestStatus
import java.io.IOException

class SearchEmailGroupResponse : ActionResponse, ToXContentObject {
    var status: RestStatus
    // totalEmailGroups is not the same as the size of emailGroups because there can be 30 email groups from the request, but
    // the request only asked for 5 email groups, so totalEmailGroups will be 30, but will only contain 5 email groups
    var totalEmailGroups: Int?
    var emailGroups: List<EmailGroup>

    constructor(
        status: RestStatus,
        totalEmailGroups: Int?,
        emailGroups: List<EmailGroup>
    ) : super() {
        this.status = status
        this.totalEmailGroups = totalEmailGroups
        this.emailGroups = emailGroups
    }

    @Throws(IOException::class)
    constructor(sin: StreamInput) {
        this.status = sin.readEnum(RestStatus::class.java)
        val emailGroups = mutableListOf<EmailGroup>()
        this.totalEmailGroups = sin.readOptionalInt()
        val currentSize = sin.readInt()
        for (i in 0 until currentSize) {
            emailGroups.add(EmailGroup.readFrom(sin))
        }
        this.emailGroups = emailGroups
    }

    @Throws(IOException::class)
    override fun writeTo(out: StreamOutput) {
        out.writeEnum(status)
        out.writeOptionalInt(totalEmailGroups)
        out.writeInt(emailGroups.size)
        for (emailGroup in emailGroups) {
            emailGroup.writeTo(out)
        }
    }

    @Throws(IOException::class)
    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
        return builder.startObject()
            .field("totalEmailGroups", totalEmailGroups)
            .field("emailGroups", emailGroups)
            .endObject()
    }
}
