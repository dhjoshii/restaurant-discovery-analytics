package restaurants.db

import com.mongodb.ExplainVerbosity
import com.mongodb.client.MongoCollection
import org.bson.conversions.Bson
import org.bson.{BsonDocument, Document}
import restaurants.model.QueryPlan

import scala.jdk.CollectionConverters.*

/** Runs `explain("executionStats")` for a find query and summarises the winning plan.
  * Shared by every dataset's repository.
  */
object Explain {

  /** Explains `find(filter).sort(sort).skip(skip).limit(limit)`; `forceCollectionScan` hints `$natural`. */
  def find(
      collection: MongoCollection[Document],
      filter: Bson,
      sort: Bson,
      skip: Int,
      limit: Int,
      forceCollectionScan: Boolean
  ): QueryPlan = {
    val base  = collection.find(filter).sort(sort).skip(skip).limit(limit)
    val query = if (forceCollectionScan) base.hint(new Document("$natural", 1)) else base
    parse(query.explain(ExplainVerbosity.EXECUTION_STATS), filterJson(collection, filter))
  }

  def filterJson(collection: MongoCollection[Document], filter: Bson): String =
    filter.toBsonDocument(classOf[BsonDocument], collection.getCodecRegistry).toJson

  private[db] def parse(explain: Document, filterJson: String): QueryPlan = {
    val planner = Option(explain.get("queryPlanner")).collect { case d: Document => d }
    val winning = planner.flatMap(p => Option(p.get("winningPlan"))).toList
    val nodes   = winning.flatMap(collectStages)
    val stats   = Option(explain.get("executionStats")).collect { case d: Document => d }

    def stat(key: String): Long = stats.flatMap(s => Option(s.get(key))).collect { case n: Number => n.longValue() }.getOrElse(0L)

    QueryPlan(
      filterJson = filterJson,
      stages = nodes.map(_._1).reverse, // innermost (data access) stage first
      indexesUsed = nodes.flatMap(_._2).distinct,
      keysExamined = stat("totalKeysExamined"),
      docsExamined = stat("totalDocsExamined"),
      returned = stat("nReturned"),
      millis = stat("executionTimeMillis")
    )
  }

  /** Depth-first walk of a plan tree (classic and slot-based engine layouts). */
  private def collectStages(node: Any): List[(String, Option[String])] = node match {
    case d: Document =>
      val here = Option(d.get("stage")).collect { case s: String => s }.map { s =>
        s -> Option(d.get("indexName")).collect { case i: String => i }
      }.toList
      val single = List("queryPlan", "inputStage").flatMap(k => Option(d.get(k))).flatMap(collectStages)
      val many = Option(d.get("inputStages")).toList.flatMap {
        case l: java.util.List[?] => l.asScala.toList.flatMap(collectStages)
        case _                    => Nil
      }
      here ++ single ++ many
    case _ => Nil
  }

  /** Escapes regex metacharacters so user text is matched literally. */
  def escapeRegex(raw: String): String =
    raw.trim.flatMap(ch => if ("\\^$.|?*+()[]{}/-".contains(ch)) s"\\$ch" else ch.toString)
}
