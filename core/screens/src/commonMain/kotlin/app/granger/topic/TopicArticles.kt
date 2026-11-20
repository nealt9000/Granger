package app.granger.topic

/** What a node on the connections map is (Plan LL2 Task 5), for its colour and its filter. */
enum class NodeKind { PERSON, EVENT, PLACE, TOPIC, BOOK }

/** One closest article, as its card shows it; [kind] from the time index (a life, an event) or a place's coordinates. */
data class TopicArticle(
    val packId: String,
    val path: String,
    val title: String,
    val blurb: String,
    val picture: String?,
    val kind: NodeKind = NodeKind.TOPIC,
)
