package io.github.realmlabs.asteria.script.job

data class ScriptJobResultSummary(
    val jobId: ScriptJobId,
    val totalItems: Int,
    val completedItems: Int,
    val failedItems: Int,
    val cancelledItems: Int,
    val errorTypes: List<ScriptJobErrorSummary>,
)

data class ScriptJobErrorSummary(
    val error: String,
    val count: Int,
    val sampleTargets: List<String> = emptyList(),
) {
    init {
        require(error.isNotBlank()) { "script job error summary error must not be blank" }
        require(count > 0) { "script job error summary count must be positive" }
    }
}

data class ScriptJobResultExport(
    val fileName: String,
    val contentType: String,
    val content: String,
) {
    init {
        require(fileName.isNotBlank()) { "script job result export file name must not be blank" }
        require(contentType.isNotBlank()) { "script job result export content type must not be blank" }
    }
}

data class ScriptJobRetryFailedItemsRequest(
    val error: String? = null,
    val limit: Int = 100,
) {
    init {
        error?.let { require(it.isNotBlank()) { "script job retry error must not be blank" } }
        require(limit > 0) { "script job retry limit must be positive" }
    }
}
