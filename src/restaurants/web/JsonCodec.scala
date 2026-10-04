package restaurants.web

import org.bson.Document
import restaurants.analytics.Aggregation
import restaurants.db.IndexInfo
import restaurants.model.QueryPlan

import scala.util.Try

/** Shared JSON helpers: query plans, indexes, aggregations and request bodies. */
object JsonCodec {

  def plan(p: QueryPlan): ujson.Value = ujson.Obj(
    "filter"       -> Try(ujson.read(p.filterJson)).getOrElse(ujson.Str(p.filterJson)),
    "stages"       -> ujson.Arr.from(p.stages.map(ujson.Str(_))),
    "indexes"      -> ujson.Arr.from(p.indexesUsed.map(ujson.Str(_))),
    "keysExamined" -> p.keysExamined.toDouble,
    "docsExamined" -> p.docsExamined.toDouble,
    "returned"     -> p.returned.toDouble,
    "millis"       -> p.millis.toDouble,
    "usesIndex"    -> p.usesIndex
  )

  def index(i: IndexInfo): ujson.Value = ujson.Obj(
    "name"     -> i.name,
    "keys"     -> ujson.Arr.from(i.keys.map((k, d) => ujson.Obj("field" -> k, "direction" -> d))),
    "unique"   -> i.unique,
    "compound" -> i.isCompound,
    "multikey" -> i.multikeyField,
    "purpose"  -> i.purpose
  )

  /** Wraps aggregation rows with the pipeline that produced them, so the UI can display both. */
  def aggregation[A](agg: Aggregation[A])(row: A => ujson.Value): ujson.Value = ujson.Obj(
    "rows"     -> ujson.Arr.from(agg.rows.map(row)),
    "pipeline" -> ujson.Arr.from(agg.pipeline.map(stage))
  )

  private def stage(d: Document): ujson.Value = Try(ujson.read(d.toJson)).getOrElse(ujson.Str(d.toJson))

  /** Parses a JSON object body; numbers and booleans are turned into text for validation. */
  def parseObject(body: String): Either[String, Map[String, String]] =
    Try(ujson.read(body)).toOption match {
      case Some(ujson.Obj(fields)) =>
        Right(fields.toMap.collect {
          case (k, ujson.Str(s))  => k -> s
          case (k, ujson.Num(n))  => k -> (if (n.isWhole) n.toLong.toString else n.toString)
          case (k, ujson.Bool(b)) => k -> b.toString
          case (k, ujson.Null)    => k -> ""
        })
      case Some(_) => Left("Request body must be a JSON object.")
      case None    => Left("Request body is not valid JSON.")
    }
}
