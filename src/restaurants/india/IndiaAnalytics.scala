package restaurants.india

import com.mongodb.client.MongoCollection
import org.bson.Document
import restaurants.AppError
import restaurants.analytics.Aggregation
import restaurants.analytics.PipelineDsl.doc

import scala.jdk.CollectionConverters.*
import scala.util.Try

final case class IndiaOverview(restaurants: Long, cities: Int, cuisines: Int, votes: Long, avgRating: Option[Double], avgCost: Option[Double])
final case class CityStat(city: String, restaurants: Long, avgRating: Option[Double], avgCost: Option[Double])
final case class CuisineStat(cuisine: String, restaurants: Long, avgRating: Option[Double])
final case class CityRating(city: String, avgRating: Double, rated: Long, votes: Long)
final case class CityCost(city: String, avgCost: Double, minCost: Int, maxCost: Int, restaurants: Long)
final case class BandCount(band: String, restaurants: Long, percent: Double)
final case class TopIndia(restaurantId: Long, name: String, city: String, locality: String, cuisines: List[String], rating: Double, votes: Int, costForTwo: Int)
final case class ServiceShare(city: String, restaurants: Long, onlineDelivery: Double, tableBooking: Double)

/** Aggregation pipelines over `india_restaurants`.
  * "Rated" means votes > 0 AND rating > 0 (Zomato stores "Not rated" as 0); unknown costs (0) are excluded from cost statistics.
  */
final class IndiaAnalytics(collection: MongoCollection[Document]) {

  private val isRated    = doc("$and" -> List(doc("$gt" -> List("$votes", 0)), doc("$gt" -> List("$rating", 0))))
  private val ratedOnly  = doc("$cond" -> List(isRated, "$rating", null))
  private val knownCost  = doc("$cond" -> List(doc("$gt" -> List("$cost_for_two", 0)), "$cost_for_two", null))

  def overview(): Either[AppError, Aggregation[IndiaOverview]] = run(
    List(
      doc(
        "$group" -> doc(
          "_id"          -> null,
          "restaurants"  -> doc("$sum" -> 1),
          "cities"       -> doc("$addToSet" -> "$city"),
          "cuisineLists" -> doc("$addToSet" -> "$cuisines"),
          "votes"        -> doc("$sum" -> "$votes"),
          "avgRating"    -> doc("$avg" -> ratedOnly),
          "avgCost"      -> doc("$avg" -> knownCost)
        )
      ),
      doc(
        "$project" -> doc(
          "_id"         -> 0,
          "restaurants" -> 1,
          "votes"       -> 1,
          "cities"      -> doc("$size" -> "$cities"),
          "cuisines" -> doc(
            "$size" -> doc("$reduce" -> doc("input" -> "$cuisineLists", "initialValue" -> List(), "in" -> doc("$setUnion" -> List("$$value", "$$this"))))
          ),
          "avgRating" -> doc("$round" -> List("$avgRating", 2)),
          "avgCost"   -> doc("$round" -> List("$avgCost", 0))
        )
      )
    )
  )(d => IndiaOverview(long(d, "restaurants"), long(d, "cities").toInt, long(d, "cuisines").toInt, long(d, "votes"), dbl(d, "avgRating"), dbl(d, "avgCost")))

  /** 1. Restaurants per city with mean rating and mean cost for two. */
  def byCity(limit: Int): Either[AppError, Aggregation[CityStat]] = run(
    List(
      doc("$group" -> doc("_id" -> "$city", "restaurants" -> doc("$sum" -> 1), "avgRating" -> doc("$avg" -> ratedOnly), "avgCost" -> doc("$avg" -> knownCost))),
      doc("$sort" -> doc("restaurants" -> -1, "_id" -> 1)),
      doc("$limit" -> clamp(limit, 1, 100)),
      doc(
        "$project" -> doc(
          "_id"         -> 0,
          "city"        -> "$_id",
          "restaurants" -> 1,
          "avgRating"   -> doc("$round" -> List("$avgRating", 2)),
          "avgCost"     -> doc("$round" -> List("$avgCost", 0))
        )
      )
    )
  )(d => CityStat(str(d, "city"), long(d, "restaurants"), dbl(d, "avgRating"), dbl(d, "avgCost")))

  /** 2. Most popular cuisines ($unwind over the cuisines array), optionally inside one city. */
  def topCuisines(limit: Int, city: Option[String]): Either[AppError, Aggregation[CuisineStat]] = run(
    city.map(c => doc("$match" -> doc("city" -> c))).toList ++ List(
      doc("$unwind" -> "$cuisines"),
      doc("$group" -> doc("_id" -> "$cuisines", "restaurants" -> doc("$sum" -> 1), "avgRating" -> doc("$avg" -> ratedOnly))),
      doc("$sort" -> doc("restaurants" -> -1, "_id" -> 1)),
      doc("$limit" -> clamp(limit, 1, 100)),
      doc("$project" -> doc("_id" -> 0, "cuisine" -> "$_id", "restaurants" -> 1, "avgRating" -> doc("$round" -> List("$avgRating", 2))))
    )
  )(d => CuisineStat(str(d, "cuisine"), long(d, "restaurants"), dbl(d, "avgRating")))

  /** 3. Average rating by city (rated restaurants only, cities with enough of them). */
  def ratingByCity(limit: Int, minRated: Int): Either[AppError, Aggregation[CityRating]] = run(
    List(
      doc("$match" -> doc("votes" -> doc("$gt" -> 0), "rating" -> doc("$gt" -> 0))),
      doc("$group" -> doc("_id" -> "$city", "avgRating" -> doc("$avg" -> "$rating"), "rated" -> doc("$sum" -> 1), "votes" -> doc("$sum" -> "$votes"))),
      doc("$match" -> doc("rated" -> doc("$gte" -> clamp(minRated, 1, 10000)))),
      doc("$sort" -> doc("avgRating" -> -1, "rated" -> -1)),
      doc("$limit" -> clamp(limit, 1, 100)),
      doc("$project" -> doc("_id" -> 0, "city" -> "$_id", "avgRating" -> doc("$round" -> List("$avgRating", 2)), "rated" -> 1, "votes" -> 1))
    )
  )(d => CityRating(str(d, "city"), dbl(d, "avgRating").getOrElse(0.0), long(d, "rated"), long(d, "votes")))

  /** 4. Average cost for two by city (known costs only). */
  def costByCity(limit: Int, minRestaurants: Int): Either[AppError, Aggregation[CityCost]] = run(
    List(
      doc("$match" -> doc("cost_for_two" -> doc("$gt" -> 0))),
      doc(
        "$group" -> doc(
          "_id"         -> "$city",
          "avgCost"     -> doc("$avg" -> "$cost_for_two"),
          "minCost"     -> doc("$min" -> "$cost_for_two"),
          "maxCost"     -> doc("$max" -> "$cost_for_two"),
          "restaurants" -> doc("$sum" -> 1)
        )
      ),
      doc("$match" -> doc("restaurants" -> doc("$gte" -> clamp(minRestaurants, 1, 10000)))),
      doc("$sort" -> doc("avgCost" -> -1)),
      doc("$limit" -> clamp(limit, 1, 100)),
      doc("$project" -> doc("_id" -> 0, "city" -> "$_id", "avgCost" -> doc("$round" -> List("$avgCost", 0)), "minCost" -> 1, "maxCost" -> 1, "restaurants" -> 1))
    )
  )(d => CityCost(str(d, "city"), dbl(d, "avgCost").getOrElse(0.0), long(d, "minCost").toInt, long(d, "maxCost").toInt, long(d, "restaurants")))

  /** 5. Rating-band distribution (Excellent … Not rated), optionally inside one city. */
  def ratingBands(city: Option[String]): Either[AppError, Aggregation[BandCount]] =
    run(
      city.map(c => doc("$match" -> doc("city" -> c))).toList ++ List(
        doc("$group" -> doc("_id" -> "$rating_text", "restaurants" -> doc("$sum" -> 1))),
        doc("$project" -> doc("_id" -> 0, "band" -> "$_id", "restaurants" -> 1))
      )
    )(d => BandCount(str(d, "band"), long(d, "restaurants"), 0.0)).map { agg =>
      // Ordering and percentages are applied in Scala.
      val total = agg.rows.map(_.restaurants).sum.toDouble
      val rows = agg.rows
        .sortBy(r => RatingBand.ordered.indexOf(r.band) match { case -1 => 99; case i => i })
        .map(r => r.copy(percent = if (total == 0) 0 else math.round(r.restaurants / total * 1000) / 10.0))
      agg.copy(rows = rows)
    }

  /** 6. Top-rated restaurants with enough votes to be meaningful. */
  def topRated(limit: Int, minVotes: Int, city: Option[String], cuisine: Option[String]): Either[AppError, Aggregation[TopIndia]] = {
    val matchStage = doc(
      (city.map(c => "city" -> (c: Any)).toList ++
        cuisine.map(c => "cuisines" -> (c: Any)).toList ++
        List("votes" -> doc("$gte" -> clamp(minVotes, 1, 100000))))*
    )
    run(
      List(
        doc("$match" -> matchStage),
        doc("$sort" -> doc("rating" -> -1, "votes" -> -1, "restaurant_id" -> 1)),
        doc("$limit" -> clamp(limit, 1, 100)),
        doc("$project" -> doc("_id" -> 0, "restaurant_id" -> 1, "name" -> 1, "city" -> 1, "locality" -> 1, "cuisines" -> 1, "rating" -> 1, "votes" -> 1, "cost_for_two" -> 1))
      )
    ) { d =>
      TopIndia(
        long(d, "restaurant_id"),
        str(d, "name"),
        str(d, "city"),
        str(d, "locality"),
        Option(d.get("cuisines")).collect { case l: java.util.List[?] => l.asScala.toList.map(_.toString) }.getOrElse(Nil),
        dbl(d, "rating").getOrElse(0.0),
        long(d, "votes").toInt,
        long(d, "cost_for_two").toInt
      )
    }
  }

  /** 7. Share of restaurants offering online delivery / table booking, per city. */
  def services(limit: Int, minRestaurants: Int): Either[AppError, Aggregation[ServiceShare]] = run(
    List(
      doc(
        "$group" -> doc(
          "_id"         -> "$city",
          "restaurants" -> doc("$sum" -> 1),
          "online"      -> doc("$sum" -> doc("$cond" -> List("$has_online_delivery", 1, 0))),
          "table"       -> doc("$sum" -> doc("$cond" -> List("$has_table_booking", 1, 0)))
        )
      ),
      doc("$match" -> doc("restaurants" -> doc("$gte" -> clamp(minRestaurants, 1, 10000)))),
      doc("$sort" -> doc("restaurants" -> -1)),
      doc("$limit" -> clamp(limit, 1, 100)),
      doc(
        "$project" -> doc(
          "_id"            -> 0,
          "city"           -> "$_id",
          "restaurants"    -> 1,
          "onlineDelivery" -> doc("$round" -> List(doc("$multiply" -> List(doc("$divide" -> List("$online", "$restaurants")), 100)), 1)),
          "tableBooking"   -> doc("$round" -> List(doc("$multiply" -> List(doc("$divide" -> List("$table", "$restaurants")), 100)), 1))
        )
      )
    )
  )(d => ServiceShare(str(d, "city"), long(d, "restaurants"), dbl(d, "onlineDelivery").getOrElse(0.0), dbl(d, "tableBooking").getOrElse(0.0)))

  private def run[A](pipeline: List[Document])(f: Document => A): Either[AppError, Aggregation[A]] =
    Try(Aggregation(collection.aggregate(pipeline.asJava).into(new java.util.ArrayList[Document]()).asScala.toList.map(f), pipeline)).toEither.left
      .map(AppError.fromThrowable)

  private def clamp(v: Int, lo: Int, hi: Int): Int = math.max(lo, math.min(hi, v))
  private def str(d: Document, k: String): String  = Option(d.get(k)).map(_.toString).getOrElse("")
  private def long(d: Document, k: String): Long   = Option(d.get(k)).collect { case n: Number => n.longValue() }.getOrElse(0L)
  private def dbl(d: Document, k: String): Option[Double] = Option(d.get(k)).collect { case n: Number => n.doubleValue() }
}
