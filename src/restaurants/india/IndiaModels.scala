package restaurants.india

import restaurants.model.Coordinates

/** A restaurant in India (Zomato data), stored in `india_restaurants`.
  *
  * Ratings are Zomato's 0–5 scale where HIGHER is better; `rating = 0` with
  * `votes = 0` means "Not rated". Costs are in Indian Rupees for two people.
  */
final case class IndiaRestaurant(
    objectId: Option[String],
    restaurantId: Long,
    name: String,
    city: String,
    locality: String,
    address: String,
    cuisines: List[String],
    costForTwo: Int,
    priceRange: Int,
    rating: Double,
    votes: Int,
    hasTableBooking: Boolean,
    hasOnlineDelivery: Boolean,
    coord: Option[Coordinates]
) {
  def displayName: String = if (name.trim.isEmpty) "(unnamed restaurant)" else name.trim

  def isRated: Boolean = votes > 0 && rating > 0

  def ratingText: String = RatingBand.textFor(rating, votes)

  def priceSymbol: String = "₹" * math.max(1, math.min(4, priceRange))

  /** Zomato addresses usually already contain the locality and city; only append what is missing. */
  def fullAddress: String = {
    val base    = address.trim
    val missing = List(locality, city).map(_.trim).filter(p => p.nonEmpty && !base.toLowerCase.contains(p.toLowerCase))
    (base :: missing).filter(_.nonEmpty).mkString(", ")
  }
}

/** Zomato's rating bands. */
object RatingBand {

  val ordered: List[String] = List("Excellent", "Very Good", "Good", "Average", "Poor", "Not rated")

  def textFor(rating: Double, votes: Int): String =
    if (votes <= 0 || rating <= 0) "Not rated"
    else if (rating >= 4.5) "Excellent"
    else if (rating >= 4.0) "Very Good"
    else if (rating >= 3.5) "Good"
    else if (rating >= 2.5) "Average"
    else "Poor"
}

/** Price range used by Zomato (1 = ₹, 4 = ₹₹₹₹). New records derive it from the cost for two. */
object PriceRange {
  def fromCost(costForTwo: Int): Int =
    if (costForTwo < 500) 1 else if (costForTwo < 1000) 2 else if (costForTwo < 2000) 3 else 4
}

/** Search filters for the India dataset (combined with AND). */
final case class IndiaCriteria(
    name: Option[String] = None,
    city: Option[String] = None,
    cuisine: Option[String] = None,
    locality: Option[String] = None,
    minRating: Option[Double] = None,
    maxCost: Option[Int] = None,
    onlineDelivery: Boolean = false,
    tableBooking: Boolean = false
) {
  def describe: String = {
    val parts = List(
      name.map(n => s"name contains \"$n\""),
      city.map(c => s"city = $c"),
      cuisine.map(c => s"cuisine = $c"),
      locality.map(l => s"locality contains \"$l\""),
      minRating.map(r => s"rating ≥ $r"),
      maxCost.map(c => s"cost for two ≤ ₹$c"),
      Option.when(onlineDelivery)("online delivery"),
      Option.when(tableBooking)("table booking")
    ).flatten
    if (parts.isEmpty) "all restaurants" else parts.mkString(", ")
  }
}

enum IndiaSort(val key: String, val label: String) {
  case TopRated  extends IndiaSort("rating", "Top rated")
  case MostVoted extends IndiaSort("votes", "Most voted")
  case CostLow   extends IndiaSort("cost", "Cost: low → high")
  case CostHigh  extends IndiaSort("-cost", "Cost: high → low")
  case NameAsc   extends IndiaSort("name", "Name A → Z")
}

object IndiaSort {
  def fromKey(key: String): Option[IndiaSort] = values.find(_.key.equalsIgnoreCase(key.trim))
}

/** Raw input for a new India restaurant (strings, as typed). */
final case class IndiaDraft(
    name: String,
    city: String,
    locality: String,
    address: String,
    cuisines: String, // comma separated
    costForTwo: String,
    priceRange: String = "",
    onlineDelivery: String = "",
    tableBooking: String = "",
    latitude: String = "",
    longitude: String = ""
)

/** Raw partial update: `None` = unchanged. */
final case class IndiaPatch(
    name: Option[String] = None,
    city: Option[String] = None,
    locality: Option[String] = None,
    address: Option[String] = None,
    cuisines: Option[String] = None,
    costForTwo: Option[String] = None,
    priceRange: Option[String] = None,
    onlineDelivery: Option[String] = None,
    tableBooking: Option[String] = None,
    latitude: Option[String] = None,
    longitude: Option[String] = None
) {
  def isEmpty: Boolean = this == IndiaPatch()
}

/** A validated patch, ready to become a `$set`. */
final case class ValidIndiaPatch(
    name: Option[String],
    city: Option[String],
    locality: Option[String],
    address: Option[String],
    cuisines: Option[List[String]],
    costForTwo: Option[Int],
    priceRange: Option[Int],
    onlineDelivery: Option[Boolean],
    tableBooking: Option[Boolean],
    coord: Option[Option[Coordinates]]
)

/** Ready-made queries for the explain lab. */
enum IndiaPreset(val key: String, val label: String, val criteria: IndiaCriteria, val sort: IndiaSort) {
  case NameSearch extends IndiaPreset("name", "Name contains \"biryani\"", IndiaCriteria(name = Some("biryani")), IndiaSort.NameAsc)
  case CityTop
      extends IndiaPreset("city", "City = New Delhi, top rated", IndiaCriteria(city = Some("New Delhi")), IndiaSort.TopRated)
  case CuisineTop
      extends IndiaPreset(
        "cuisine",
        "Cuisine = North Indian, rating ≥ 4",
        IndiaCriteria(cuisine = Some("North Indian"), minRating = Some(4.0)),
        IndiaSort.TopRated
      )
  case RatingTop extends IndiaPreset("rating", "Rating ≥ 4.5, top rated", IndiaCriteria(minRating = Some(4.5)), IndiaSort.TopRated)
  case Budget    extends IndiaPreset("cost", "Cost for two ≤ ₹300, cheapest first", IndiaCriteria(maxCost = Some(300)), IndiaSort.CostLow)
}

object IndiaPreset {
  def fromKey(key: String): Option[IndiaPreset] = values.find(_.key == key.trim)
}
