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
import org.opensearch.alerting.model.destination.Destination
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

class SearchEmailAccountResponse : ActionResponse, ToXContentObject {
    var status: RestStatus
    // totalEmailAccounts is not the same as the size of emailAccounts because there can be 30 email accounts from the request, but
    // the request only asked for 5 email accounts, so totalEmailAccounts will be 30, but will only contain 5 email accounts
    var totalEmailAccounts: Int?
    var emailAccounts: List<EmailAccount>

    constructor(
        status: RestStatus,
        totalEmailAccounts: Int?,
        emailAccounts: List<EmailAccount>
    ) : super() {
        this.status = status
        this.totalEmailAccounts = totalEmailAccounts
        this.emailAccounts = emailAccounts
    }

    @Throws(IOException::class)
    constructor(sin: StreamInput) {
        this.status = sin.readEnum(RestStatus::class.java)
        val emailAccounts = mutableListOf<EmailAccount>()
        this.totalEmailAccounts = sin.readOptionalInt()
        val currentSize = sin.readInt()
        for (i in 0 until currentSize) {
            emailAccounts.add(EmailAccount.readFrom(sin))
        }
        this.emailAccounts = emailAccounts
    }

    @Throws(IOException::class)
    override fun writeTo(out: StreamOutput) {
        out.writeEnum(status)
        out.writeOptionalInt(totalEmailAccounts)
        out.writeInt(emailAccounts.size)
        for (emailAccount in emailAccounts) {
            emailAccount.writeTo(out)
        }
    }

    @Throws(IOException::class)
    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
        return builder.startObject()
            .field("totalEmailAccounts", totalEmailAccounts)
            .field("emailAccounts", emailAccounts)
            .endObject()
    }
}
