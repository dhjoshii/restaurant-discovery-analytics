package restaurants.analytics

import org.bson.Document

import scala.jdk.CollectionConverters.*

/** The rows of an aggregation together with the exact pipeline that produced them. */
final case class Aggregation[A](rows: List[A], pipeline: List[Document])

/** Tiny builder for pipeline stages: `doc("a" -> 1, "b" -> doc(...))`.
  * Values are inserted as BSON values (never by string interpolation), so user
  * input can't inject operators; Scala lists become Java lists.
  */
object PipelineDsl {

  def doc(pairs: (String, Any)*): Document =
    pairs.foldLeft(new Document()) { case (d, (k, v)) => d.append(k, toBson(v)) }

  private def toBson(value: Any): AnyRef = value match {
    case null        => null
    case l: List[?]  => l.map(toBson).asJava
    case i: Int      => Int.box(i)
    case l: Long     => Long.box(l)
    case d: Double   => Double.box(d)
    case b: Boolean  => Boolean.box(b)
    case ref: AnyRef => ref
    case other       => other.toString
  }
}
