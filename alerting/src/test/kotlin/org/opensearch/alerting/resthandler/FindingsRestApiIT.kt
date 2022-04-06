package org.opensearch.alerting.resthandler

import org.junit.Assert
import org.opensearch.alerting.AlertingRestTestCase
import org.opensearch.alerting.elasticapi.string
import org.opensearch.alerting.model.Finding
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.XContentBuilder
import org.opensearch.common.xcontent.XContentType
import org.opensearch.test.junit.annotations.TestLogging
import java.time.Instant
import java.util.UUID

@TestLogging("level:DEBUG", reason = "Debug for tests.")
@Suppress("UNCHECKED_CAST")
class FindingsRestApiIT : AlertingRestTestCase() {

    fun `test find Finding where doc is not retrieved`() {

        createFindings(matchingDocIds = setOf("someId"))
        val response = searchFindings()
        assertEquals(1, response.totalFindings)
        assertEquals(0, response.findings[0].documents.size)
    }

    private fun createFindings(
        monitorId: String = "NO_ID",
        monitorName: String = "NO_NAME",
        index: String = "testIndex",
        docLevelQueryId: String = "NO_ID",
        docLevelQueryTags: List<String> = emptyList(),
        matchingDocIds: Set<String>
    ): String {
        val finding = Finding(
            id = UUID.randomUUID().toString(),
            relatedDocId = matchingDocIds.joinToString(","),
            monitorId = monitorId,
            monitorName = monitorName,
            index = index,
            queryId = docLevelQueryId,
            queryTags = docLevelQueryTags,
            severity = "sev3",
            timestamp = Instant.now(),
            triggerId = null,
            triggerName = null
        )

        val findingStr = finding.toXContent(XContentBuilder.builder(XContentType.JSON.xContent()), ToXContent.EMPTY_PARAMS).string()
        // todo: below is all hardcoded, temp code and added only to test. replace this with proper Findings index lifecycle management.
//        val indexRequest = IndexRequest(".opensearch-alerting-findings")
//            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
//            .source(findingStr, XContentType.JSON)

//        client().index(indexRequest).actionGet()
        indexDoc(".opensearch-alerting-findings", finding.id, findingStr)
        return finding.id
    }
}
