package restaurants.india

import restaurants.db.IndexSpec

/** Indexes for `india_restaurants`; each one backs a concrete filter or sort in [[MongoIndiaRepository]]. */
object IndiaIndexes {

  val specs: List[IndexSpec] = List(
    IndexSpec(List("restaurant_id" -> 1), unique = true, "Look up / update / rate / delete by restaurant_id; next id"),
    IndexSpec(List("name" -> 1, "restaurant_id" -> 1), unique = false, "Name search (case-insensitive regex) and A→Z paging"),
    IndexSpec(
      List("city" -> 1, "rating" -> -1, "votes" -> -1, "restaurant_id" -> 1),
      unique = false,
      "City filter already sorted by top rating; city-scoped analytics"
    ),
    IndexSpec(
      List("cuisines" -> 1, "rating" -> -1, "votes" -> -1, "restaurant_id" -> 1),
      unique = false,
      "Cuisine filter (multikey over the cuisines array) sorted by top rating",
      multikey = true
    ),
    IndexSpec(
      List("rating" -> -1, "votes" -> -1, "restaurant_id" -> 1),
      unique = false,
      "Minimum-rating filter and the default 'Top rated' order"
    ),
    IndexSpec(List("cost_for_two" -> 1, "restaurant_id" -> 1), unique = false, "Budget filter (max cost for two) and cost sorting")
  )
}
