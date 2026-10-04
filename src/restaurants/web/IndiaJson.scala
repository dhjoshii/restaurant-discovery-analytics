package restaurants.web

import restaurants.india.*
import restaurants.model.Page

/** JSON mapping for the India dataset. */
object IndiaJson {

  private def opt[A](o: Option[A])(f: A => ujson.Value): ujson.Value = o.map(f).getOrElse(ujson.Null)

  def restaurant(r: IndiaRestaurant): ujson.Value = ujson.Obj(
    "id"                -> opt(r.objectId)(ujson.Str(_)),
    "restaurantId"      -> r.restaurantId.toString,
    "name"              -> r.name,
    "displayName"       -> r.displayName,
    "city"              -> r.city,
    "locality"          -> r.locality,
    "address"           -> r.address,
    "fullAddress"       -> r.fullAddress,
    "cuisines"          -> ujson.Arr.from(r.cuisines.map(ujson.Str(_))),
    "costForTwo"        -> r.costForTwo,
    "priceRange"        -> r.priceRange,
    "rating"            -> r.rating,
    "ratingText"        -> r.ratingText,
    "rated"             -> r.isRated,
    "votes"             -> r.votes,
    "hasTableBooking"   -> r.hasTableBooking,
    "hasOnlineDelivery" -> r.hasOnlineDelivery,
    "coord"             -> opt(r.coord)(c => ujson.Obj("lat" -> c.latitude, "lon" -> c.longitude))
  )

  def page(p: Page[IndiaRestaurant]): ujson.Value = ujson.Obj(
    "items"      -> ujson.Arr.from(p.items.map(restaurant)),
    "page"       -> p.page,
    "pageSize"   -> p.size,
    "total"      -> p.total.toDouble,
    "totalPages" -> p.totalPages
  )

  def criteria(c: IndiaCriteria): ujson.Value = ujson.Obj(
    "name"           -> opt(c.name)(ujson.Str(_)),
    "city"           -> opt(c.city)(ujson.Str(_)),
    "cuisine"        -> opt(c.cuisine)(ujson.Str(_)),
    "locality"       -> opt(c.locality)(ujson.Str(_)),
    "minRating"      -> opt(c.minRating)(ujson.Num(_)),
    "maxCost"        -> opt(c.maxCost)(ujson.Num(_)),
    "onlineDelivery" -> c.onlineDelivery,
    "tableBooking"   -> c.tableBooking,
    "summary"        -> c.describe
  )

  def overview(o: IndiaOverview): ujson.Value = ujson.Obj(
    "restaurants" -> o.restaurants.toDouble,
    "cities"      -> o.cities,
    "cuisines"    -> o.cuisines,
    "votes"       -> o.votes.toDouble,
    "avgRating"   -> opt(o.avgRating)(ujson.Num(_)),
    "avgCost"     -> opt(o.avgCost)(ujson.Num(_))
  )

  def cityStat(c: CityStat): ujson.Value =
    ujson.Obj("city" -> c.city, "restaurants" -> c.restaurants.toDouble, "avgRating" -> opt(c.avgRating)(ujson.Num(_)), "avgCost" -> opt(c.avgCost)(ujson.Num(_)))

  def cuisineStat(c: CuisineStat): ujson.Value =
    ujson.Obj("cuisine" -> c.cuisine, "restaurants" -> c.restaurants.toDouble, "avgRating" -> opt(c.avgRating)(ujson.Num(_)))

  def cityRating(c: CityRating): ujson.Value =
    ujson.Obj("city" -> c.city, "avgRating" -> c.avgRating, "rated" -> c.rated.toDouble, "votes" -> c.votes.toDouble)

  def cityCost(c: CityCost): ujson.Value =
    ujson.Obj("city" -> c.city, "avgCost" -> c.avgCost, "minCost" -> c.minCost, "maxCost" -> c.maxCost, "restaurants" -> c.restaurants.toDouble)

  def band(b: BandCount): ujson.Value = ujson.Obj("band" -> b.band, "restaurants" -> b.restaurants.toDouble, "percent" -> b.percent)

  def top(t: TopIndia): ujson.Value = ujson.Obj(
    "restaurantId" -> t.restaurantId.toString,
    "name"         -> t.name,
    "city"         -> t.city,
    "locality"     -> t.locality,
    "cuisines"     -> ujson.Arr.from(t.cuisines.map(ujson.Str(_))),
    "rating"       -> t.rating,
    "votes"        -> t.votes,
    "costForTwo"   -> t.costForTwo
  )

  def service(s: ServiceShare): ujson.Value =
    ujson.Obj("city" -> s.city, "restaurants" -> s.restaurants.toDouble, "onlineDelivery" -> s.onlineDelivery, "tableBooking" -> s.tableBooking)

  // ------------------------------------------------------------------ bodies

  def draft(f: Map[String, String]): IndiaDraft = IndiaDraft(
    name = f.getOrElse("name", ""),
    city = f.getOrElse("city", ""),
    locality = f.getOrElse("locality", ""),
    address = f.getOrElse("address", ""),
    cuisines = f.getOrElse("cuisines", ""),
    costForTwo = f.getOrElse("costForTwo", ""),
    priceRange = f.getOrElse("priceRange", ""),
    onlineDelivery = f.getOrElse("onlineDelivery", ""),
    tableBooking = f.getOrElse("tableBooking", ""),
    latitude = f.getOrElse("latitude", ""),
    longitude = f.getOrElse("longitude", "")
  )

  def patch(f: Map[String, String]): IndiaPatch = IndiaPatch(
    name = f.get("name"),
    city = f.get("city"),
    locality = f.get("locality"),
    address = f.get("address"),
    cuisines = f.get("cuisines"),
    costForTwo = f.get("costForTwo"),
    priceRange = f.get("priceRange"),
    onlineDelivery = f.get("onlineDelivery"),
    tableBooking = f.get("tableBooking"),
    latitude = f.get("latitude"),
    longitude = f.get("longitude")
  )
}
