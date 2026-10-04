package restaurants.model

/** GeoJSON-style coordinates, stored in MongoDB as `[longitude, latitude]`. */
final case class Coordinates(longitude: Double, latitude: Double)

/** 1-based page request with sane bounds. */
final case class PageRequest private (page: Int, size: Int) {
  def offset: Int = (page - 1) * size
}

object PageRequest {
  val MaxSize = 50

  def of(page: Int, size: Int): PageRequest =
    PageRequest(math.max(1, page), math.min(MaxSize, math.max(1, size)))
}

final case class Page[A](items: List[A], page: Int, size: Int, total: Long) {

  def totalPages: Int = if (total == 0) 0 else math.ceil(total.toDouble / size).toInt

  def hasNext: Boolean = page < totalPages

  def hasPrevious: Boolean = page > 1
}

/** Summary of a MongoDB `explain("executionStats")` result. */
final case class QueryPlan(
    filterJson: String,
    stages: List[String],
    indexesUsed: List[String],
    keysExamined: Long,
    docsExamined: Long,
    returned: Long,
    millis: Long
) {
  def usesIndex: Boolean = indexesUsed.nonEmpty

  def pipelineLabel: String = if (stages.isEmpty) "unknown" else stages.mkString(" → ")
}
