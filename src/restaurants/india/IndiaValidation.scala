package restaurants.india

import restaurants.ValidationError
import restaurants.model.Coordinates

/** Pure validation for the India dataset; collects every problem instead of stopping at the first. */
object IndiaValidation {

  val MaxCost     = 100000
  val MaxCuisines = 8

  def newRestaurant(d: IndiaDraft): Either[ValidationError, IndiaRestaurant] = {
    val name     = text("Name", d.name, 120)
    val city     = text("City", d.city, 60)
    val locality = text("Locality", d.locality, 120)
    val address  = optionalText("Address", d.address, 250)
    val cuisines = cuisineList(d.cuisines)
    val cost     = costOf(d.costForTwo, required = true)
    val price    = if (d.priceRange.trim.isEmpty) Right(None) else priceRangeOf(d.priceRange).map(Some(_))
    val online   = yesNo("Online delivery", d.onlineDelivery)
    val table    = yesNo("Table booking", d.tableBooking)
    val coord    = coordinates(d.latitude, d.longitude)

    val errors = List(name, city, locality, address, cuisines, cost, price, online, table, coord).collect { case Left(e) => e }
    if (errors.nonEmpty) Left(ValidationError(errors))
    else
      (for {
        n  <- name
        c  <- city
        l  <- locality
        a  <- address
        cu <- cuisines
        co <- cost
        p  <- price
        on <- online
        tb <- table
        xy <- coord
      } yield IndiaRestaurant(
        objectId = None,
        restaurantId = 0L,
        name = n,
        city = c,
        locality = l,
        address = a,
        cuisines = cu,
        costForTwo = co,
        priceRange = p.getOrElse(PriceRange.fromCost(co)),
        rating = 0.0,
        votes = 0,
        hasTableBooking = tb,
        hasOnlineDelivery = on,
        coord = xy
      )).left.map(e => ValidationError(List(e)))
  }

  def patch(p: IndiaPatch): Either[ValidationError, ValidIndiaPatch] = {
    if (p.isEmpty) return Left(ValidationError(List("Nothing to update: provide at least one field.")))

    def opt[A](value: Option[String])(check: String => Either[String, A]): Either[String, Option[A]] =
      value.map(check).map(_.map(Some(_))).getOrElse(Right(None))

    val name     = opt(p.name)(text("Name", _, 120))
    val city     = opt(p.city)(text("City", _, 60))
    val locality = opt(p.locality)(text("Locality", _, 120))
    val address  = opt(p.address)(optionalText("Address", _, 250))
    val cuisines = opt(p.cuisines)(cuisineList)
    val cost     = opt(p.costForTwo)(costOf(_, required = true))
    val price    = opt(p.priceRange)(priceRangeOf)
    val online   = opt(p.onlineDelivery)(yesNo("Online delivery", _))
    val table    = opt(p.tableBooking)(yesNo("Table booking", _))
    val coord: Either[String, Option[Option[Coordinates]]] = (p.latitude, p.longitude) match {
      case (None, None) => Right(None)
      case (lat, lon)   => coordinates(lat.getOrElse(""), lon.getOrElse("")).map(Some(_))
    }

    val errors = List(name, city, locality, address, cuisines, cost, price, online, table, coord).collect { case Left(e) => e }
    if (errors.nonEmpty) Left(ValidationError(errors))
    else
      (for {
        n  <- name
        c  <- city
        l  <- locality
        a  <- address
        cu <- cuisines
        co <- cost
        pr <- price
        on <- online
        tb <- table
        xy <- coord
      } yield ValidIndiaPatch(n, c, l, a, cu, co, pr, on, tb, xy)).left.map(e => ValidationError(List(e)))
  }

  /** A single diner rating on Zomato's 1–5 scale (one decimal). */
  def rating(raw: String): Either[ValidationError, Double] =
    raw.trim.toDoubleOption match {
      case Some(r) if r >= 1.0 && r <= 5.0 => Right(math.round(r * 10) / 10.0)
      case Some(_)                         => Left(ValidationError(List("Rating must be between 1 and 5.")))
      case None                            => Left(ValidationError(List("Rating must be a number such as 4 or 4.5.")))
    }

  // ---------------------------------------------------------------- helpers

  private def text(field: String, raw: String, max: Int): Either[String, String] = {
    val v = raw.trim.replaceAll("\\s+", " ")
    if (v.isEmpty) Left(s"$field is required.")
    else if (v.length > max) Left(s"$field must be at most $max characters.")
    else Right(v)
  }

  private def optionalText(field: String, raw: String, max: Int): Either[String, String] = {
    val v = raw.trim.replaceAll("\\s+", " ")
    if (v.length > max) Left(s"$field must be at most $max characters.") else Right(v)
  }

  def cuisineList(raw: String): Either[String, List[String]] = {
    val items = raw.split(",").toList.map(_.trim.replaceAll("\\s+", " ")).filter(_.nonEmpty)
    val unique = items.foldLeft(List.empty[String]) { (acc, c) => if (acc.exists(_.equalsIgnoreCase(c))) acc else acc :+ c }
    if (unique.isEmpty) Left("Add at least one cuisine (comma separated, e.g. North Indian, Mughlai).")
    else if (unique.size > MaxCuisines) Left(s"At most $MaxCuisines cuisines are allowed.")
    else if (unique.exists(_.length > 40)) Left("Each cuisine must be at most 40 characters.")
    else Right(unique)
  }

  private def costOf(raw: String, required: Boolean): Either[String, Int] =
    raw.trim.replace(",", "").replace("₹", "") match {
      case "" if required => Left("Cost for two is required (in ₹).")
      case "" => Right(0)
      case s =>
        s.toIntOption match {
          case Some(c) if c >= 0 && c <= MaxCost => Right(c)
          case Some(_)                           => Left(s"Cost for two must be between ₹0 and ₹$MaxCost.")
          case None                              => Left("Cost for two must be a whole number of rupees.")
        }
    }

  private def priceRangeOf(raw: String): Either[String, Int] =
    raw.trim.toIntOption.filter(p => p >= 1 && p <= 4).toRight("Price range must be 1, 2, 3 or 4.")

  private def yesNo(field: String, raw: String): Either[String, Boolean] =
    raw.trim.toLowerCase match {
      case "" | "n" | "no" | "false" | "0"  => Right(false)
      case "y" | "yes" | "true" | "1" | "on" => Right(true)
      case _                                 => Left(s"$field must be yes or no.")
    }

  private def coordinates(latRaw: String, lonRaw: String): Either[String, Option[Coordinates]] =
    (latRaw.trim, lonRaw.trim) match {
      case ("", "")          => Right(None)
      case ("", _) | (_, "") => Left("Provide both latitude and longitude, or neither.")
      case (lat, lon) =>
        (lat.toDoubleOption, lon.toDoubleOption) match {
          case (Some(la), Some(lo)) if la >= -90 && la <= 90 && lo >= -180 && lo <= 180 =>
            Right(Some(Coordinates(longitude = lo, latitude = la)))
          case (Some(_), Some(_)) => Left("Latitude must be within ±90 and longitude within ±180.")
          case _                  => Left("Latitude and longitude must be decimal numbers.")
        }
    }
}
