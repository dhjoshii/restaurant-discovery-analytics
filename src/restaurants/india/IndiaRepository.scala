package restaurants.india

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.*
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import restaurants.analytics.PipelineDsl.doc
import restaurants.db.Explain
import restaurants.model.{Coordinates, Page, PageRequest, QueryPlan}

import scala.jdk.CollectionConverters.*

/** Persistence contract for restaurants. The service depends only on this trait, so the MongoDB implementation could be swapped (e.g. for an in-memory fake in tests). */
trait IndiaRepository {
  def insert(restaurant: IndiaRestaurant): IndiaRestaurant
  def findById(restaurantId: Long): Option[IndiaRestaurant]
  def search(criteria: IndiaCriteria, sort: IndiaSort, page: PageRequest): Page[IndiaRestaurant]
  def update(restaurantId: Long, patch: ValidIndiaPatch): Option[IndiaRestaurant]
  def addRating(restaurantId: Long, rating: Double): Option[IndiaRestaurant]
  def delete(restaurantId: Long): Option[IndiaRestaurant]
  def distinctCities(): List[String]
  def distinctCuisines(): List[String]
  def nextRestaurantId(): Long
  def count(): Long
  def explain(criteria: IndiaCriteria, sort: IndiaSort, page: PageRequest, forceCollectionScan: Boolean): QueryPlan
}

/** BSON ⇄ [[IndiaRestaurant]]. Field names mirror the imported Zomato columns in snake_case. */
object IndiaCodec {

  def fromDocument(d: Document): IndiaRestaurant =
    IndiaRestaurant(
      objectId = Option(d.get("_id")).map {
        case oid: ObjectId => oid.toHexString
        case other         => other.toString
      },
      restaurantId = long(d, "restaurant_id"),
      name = string(d, "name"),
      city = string(d, "city"),
      locality = string(d, "locality"),
      address = string(d, "address"),
      cuisines = list(d, "cuisines").collect { case s: String => s },
      costForTwo = long(d, "cost_for_two").toInt,
      priceRange = long(d, "price_range").toInt,
      rating = Option(d.get("rating")).collect { case n: Number => n.doubleValue() }.getOrElse(0.0),
      votes = long(d, "votes").toInt,
      hasTableBooking = Option(d.get("has_table_booking")).contains(true),
      hasOnlineDelivery = Option(d.get("has_online_delivery")).contains(true),
      coord = list(d, "coord").collect { case n: Number => n.doubleValue() } match {
        case lon :: lat :: _ if !(lon == 0 && lat == 0) => Some(Coordinates(lon, lat))
        case _                                          => None
      }
    )

  def toDocument(r: IndiaRestaurant): Document =
    new Document("restaurant_id", Long.box(r.restaurantId))
      .append("name", r.name)
      .append("city", r.city)
      .append("locality", r.locality)
      .append("address", r.address)
      .append("cuisines", r.cuisines.asJava)
      .append("cost_for_two", Int.box(r.costForTwo))
      .append("currency", "INR")
      .append("price_range", Int.box(r.priceRange))
      .append("rating", Double.box(r.rating))
      .append("rating_text", r.ratingText)
      .append("votes", Int.box(r.votes))
      .append("has_table_booking", Boolean.box(r.hasTableBooking))
      .append("has_online_delivery", Boolean.box(r.hasOnlineDelivery))
      .append("coord", r.coord.map(c => List(Double.box(c.longitude), Double.box(c.latitude))).getOrElse(Nil).asJava)

  def patchToUpdate(p: ValidIndiaPatch): Bson = {
    val sets: List[Bson] = List(
      p.name.map(Updates.set("name", _)),
      p.city.map(Updates.set("city", _)),
      p.locality.map(Updates.set("locality", _)),
      p.address.map(Updates.set("address", _)),
      p.cuisines.map(c => Updates.set("cuisines", c.asJava)),
      p.costForTwo.map(c => Updates.set("cost_for_two", Int.box(c))),
      p.priceRange.map(pr => Updates.set("price_range", Int.box(pr))),
      p.onlineDelivery.map(b => Updates.set("has_online_delivery", Boolean.box(b))),
      p.tableBooking.map(b => Updates.set("has_table_booking", Boolean.box(b))),
      p.coord.map(c => Updates.set("coord", c.map(x => List(Double.box(x.longitude), Double.box(x.latitude))).getOrElse(Nil).asJava))
    ).flatten
    Updates.combine(sets.asJava)
  }

  private def string(d: Document, key: String): String = Option(d.get(key)).map(_.toString).getOrElse("")

  private def long(d: Document, key: String): Long = Option(d.get(key)).collect { case n: Number => n.longValue() }.getOrElse(0L)

  private def list(d: Document, key: String): List[Any] = Option(d.get(key)) match {
    case Some(values: java.util.List[?]) => values.asScala.toList
    case _                               => Nil
  }
}

final class MongoIndiaRepository(collection: MongoCollection[Document]) extends IndiaRepository {

  import IndiaCodec.*
  import MongoIndiaRepository.*

  override def insert(restaurant: IndiaRestaurant): IndiaRestaurant = {
    val document = toDocument(restaurant)
    collection.insertOne(document)
    fromDocument(document)
  }

  override def findById(restaurantId: Long): Option[IndiaRestaurant] =
    Option(collection.find(Filters.eq("restaurant_id", restaurantId)).first()).map(fromDocument)

  override def search(criteria: IndiaCriteria, sort: IndiaSort, page: PageRequest): Page[IndiaRestaurant] = {
    val filter = buildFilter(criteria, sort)
    val total  = collection.countDocuments(filter)
    val items = collection
      .find(filter)
      .sort(sortSpec(sort))
      .skip(page.offset)
      .limit(page.size)
      .into(new java.util.ArrayList[Document]())
      .asScala
      .toList
      .map(fromDocument)
    Page(items, page.page, page.size, total)
  }

  override def update(restaurantId: Long, patch: ValidIndiaPatch): Option[IndiaRestaurant] =
    Option(
      collection.findOneAndUpdate(
        Filters.eq("restaurant_id", restaurantId),
        patchToUpdate(patch),
        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
    ).map(fromDocument)

  /** Atomic running average using an aggregation-pipeline update (no read-modify-write race). */
  override def addRating(restaurantId: Long, rating: Double): Option[IndiaRestaurant] =
    Option(
      collection.findOneAndUpdate(
        Filters.eq("restaurant_id", restaurantId),
        ratingPipeline(rating).asJava,
        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
    ).map(fromDocument)

  override def delete(restaurantId: Long): Option[IndiaRestaurant] =
    Option(collection.findOneAndDelete(Filters.eq("restaurant_id", restaurantId))).map(fromDocument)

  override def distinctCities(): List[String] =
    collection.distinct("city", classOf[String]).into(new java.util.ArrayList[String]()).asScala.toList.filter(_.trim.nonEmpty).sorted

  override def distinctCuisines(): List[String] =
    collection.distinct("cuisines", classOf[String]).into(new java.util.ArrayList[String]()).asScala.toList.filter(_.trim.nonEmpty).sorted

  override def nextRestaurantId(): Long =
    Option(collection.find().sort(Sorts.descending("restaurant_id")).projection(Projections.include("restaurant_id")).limit(1).first())
      .flatMap(d => Option(d.get("restaurant_id")).collect { case n: Number => n.longValue() })
      .getOrElse(0L) + 1

  override def count(): Long = collection.estimatedDocumentCount()

  override def explain(criteria: IndiaCriteria, sort: IndiaSort, page: PageRequest, forceCollectionScan: Boolean): QueryPlan =
    Explain.find(collection, buildFilter(criteria, sort), sortSpec(sort), page.offset, page.size, forceCollectionScan)
}

object MongoIndiaRepository {

  /** Each condition maps onto one of [[IndiaIndexes.specs]]. */
  def buildFilter(c: IndiaCriteria, sort: IndiaSort): Bson = {
    val costMatters = c.maxCost.isDefined || sort == IndiaSort.CostLow || sort == IndiaSort.CostHigh
    val costFilter: Option[Bson] =
      if (!costMatters) None
      else // cost_for_two = 0 means "unknown", so it is excluded whenever cost matters
        Some(Filters.and((Filters.gt("cost_for_two", Int.box(0)) :: c.maxCost.map(m => Filters.lte("cost_for_two", Int.box(m))).toList).asJava))
    val parts: List[Bson] = List(
      c.name.map(n => Filters.regex("name", Explain.escapeRegex(n), "i")),
      c.city.map(Filters.eq("city", _)),
      c.cuisine.map(Filters.eq("cuisines", _)), // array membership
      c.locality.map(l => Filters.regex("locality", Explain.escapeRegex(l), "i")),
      c.minRating.map(r => Filters.gte("rating", Double.box(r))),
      costFilter,
      Option.when(c.onlineDelivery)(Filters.eq("has_online_delivery", true)),
      Option.when(c.tableBooking)(Filters.eq("has_table_booking", true))
    ).flatten
    parts match {
      case Nil           => new Document()
      case single :: Nil => single
      case many          => Filters.and(many.asJava)
    }
  }

  def sortSpec(sort: IndiaSort): Bson = sort match {
    case IndiaSort.TopRated  => Sorts.orderBy(Sorts.descending("rating", "votes"), Sorts.ascending("restaurant_id"))
    case IndiaSort.MostVoted => Sorts.orderBy(Sorts.descending("votes"), Sorts.ascending("restaurant_id"))
    case IndiaSort.CostLow   => Sorts.ascending("cost_for_two", "restaurant_id")
    case IndiaSort.CostHigh  => Sorts.descending("cost_for_two", "restaurant_id")
    case IndiaSort.NameAsc   => Sorts.ascending("name", "restaurant_id")
  }

  /** new average = (rating·votes + r) / (votes + 1), rounded half-up to one decimal; then re-derive the band.
    * A restaurant without a rating yet (rating 0 = "Not rated") simply takes `r`.
    */
  def ratingPipeline(r: Double): List[Document] = List(
    doc(
      "$set" -> doc(
        "rating" -> doc(
          "$cond" -> List(
            doc("$gt" -> List("$rating", 0)),
            // round half-up to one decimal: floor(x * 10 + 0.5) / 10 ($round would round half to even)
            doc(
              "$divide" -> List(
                doc(
                  "$floor" -> doc(
                    "$add" -> List(
                      doc(
                        "$multiply" -> List(
                          doc("$divide" -> List(doc("$add" -> List(doc("$multiply" -> List("$rating", "$votes")), r)), doc("$add" -> List("$votes", 1)))),
                          10
                        )
                      ),
                      0.5
                    )
                  )
                ),
                10
              )
            ),
            r
          )
        ),
        "votes" -> doc("$add" -> List("$votes", 1))
      )
    ),
    doc(
      "$set" -> doc(
        "rating_text" -> doc(
          "$switch" -> doc(
            "branches" -> List(
              doc("case" -> doc("$gte" -> List("$rating", 4.5)), "then" -> "Excellent"),
              doc("case" -> doc("$gte" -> List("$rating", 4.0)), "then" -> "Very Good"),
              doc("case" -> doc("$gte" -> List("$rating", 3.5)), "then" -> "Good"),
              doc("case" -> doc("$gte" -> List("$rating", 2.5)), "then" -> "Average")
            ),
            "default" -> "Poor"
          )
        )
      )
    )
  )
}
