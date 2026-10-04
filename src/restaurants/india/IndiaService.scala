package restaurants.india

import com.mongodb.{ErrorCategory, MongoWriteException}
import restaurants.model.{Page, PageRequest, QueryPlan}
import restaurants.{AppError, DatabaseError, NotFound, ValidationError}

import scala.util.Try

/** Business logic for the India dataset; composition over [[IndiaRepository]], errors as `Either`. */
final class IndiaService(repository: IndiaRepository) {

  private val CacheMillis = 5 * 60 * 1000L
  @volatile private var cache: Option[(Long, List[String], List[String])] = None // (loadedAt, cities, cuisines)

  def create(draft: IndiaDraft): Either[AppError, IndiaRestaurant] =
    for {
      valid <- IndiaValidation.newRestaurant(draft)
      city  <- canonicalCity(valid.city)
      cuis  <- canonicalCuisines(valid.cuisines)
      saved <- insertWithFreshId(valid.copy(city = city, cuisines = cuis), attemptsLeft = 3)
    } yield saved

  private def insertWithFreshId(r: IndiaRestaurant, attemptsLeft: Int): Either[AppError, IndiaRestaurant] =
    attempt(repository.insert(r.copy(restaurantId = repository.nextRestaurantId()))) match {
      case Left(DatabaseError(_, e: MongoWriteException)) if e.getError.getCategory == ErrorCategory.DUPLICATE_KEY && attemptsLeft > 1 =>
        insertWithFreshId(r, attemptsLeft - 1)
      case other =>
        invalidate()
        other
    }

  def get(id: String): Either[AppError, IndiaRestaurant] =
    for {
      rid   <- parseId(id)
      found <- attempt(repository.findById(rid))
      r     <- found.toRight(NotFound(id.trim))
    } yield r

  def search(criteria: IndiaCriteria, sort: IndiaSort, page: PageRequest): Either[AppError, Page[IndiaRestaurant]] =
    normalize(criteria).flatMap(c => attempt(repository.search(c, sort, page)))

  def explain(criteria: IndiaCriteria, sort: IndiaSort, page: PageRequest, forceCollectionScan: Boolean): Either[AppError, QueryPlan] =
    normalize(criteria).flatMap(c => attempt(repository.explain(c, sort, page, forceCollectionScan)))

  def update(id: String, patch: IndiaPatch): Either[AppError, IndiaRestaurant] =
    for {
      rid     <- parseId(id)
      valid   <- IndiaValidation.patch(patch)
      city    <- valid.city.map(c => canonicalCity(c).map(Some(_))).getOrElse(Right(None))
      cuis    <- valid.cuisines.map(c => canonicalCuisines(c).map(Some(_))).getOrElse(Right(None))
      updated <- attempt(repository.update(rid, valid.copy(city = city, cuisines = cuis)))
      r       <- updated.toRight(NotFound(id.trim))
    } yield {
      if (city.isDefined || cuis.isDefined) invalidate()
      r
    }

  def rate(id: String, rawRating: String): Either[AppError, IndiaRestaurant] =
    for {
      rid     <- parseId(id)
      rating  <- IndiaValidation.rating(rawRating)
      updated <- attempt(repository.addRating(rid, rating))
      r       <- updated.toRight(NotFound(id.trim))
    } yield r

  def delete(id: String): Either[AppError, IndiaRestaurant] =
    for {
      rid     <- parseId(id)
      deleted <- attempt(repository.delete(rid))
      r       <- deleted.toRight(NotFound(id.trim))
    } yield {
      invalidate()
      r
    }

  def cities(): Either[AppError, List[String]]   = lists().map(_._1)
  def cuisines(): Either[AppError, List[String]] = lists().map(_._2)

  def count(): Either[AppError, Long] = attempt(repository.count())

  // ----------------------------------------------------------------- helpers

  private def lists(): Either[AppError, (List[String], List[String])] = {
    val now = System.currentTimeMillis()
    cache match {
      case Some((at, ci, cu)) if now - at < CacheMillis => Right((ci, cu))
      case _ =>
        attempt((repository.distinctCities(), repository.distinctCuisines())).map { case (ci, cu) =>
          cache = Some((now, ci, cu))
          (ci, cu)
        }
    }
  }

  private def invalidate(): Unit = cache = None

  private def canonicalCity(raw: String): Either[AppError, String] =
    cities().map(all => all.find(_.equalsIgnoreCase(raw.trim)).getOrElse(raw.trim))

  private def canonicalCuisines(raw: List[String]): Either[AppError, List[String]] =
    cuisines().map(all => raw.map(c => all.find(_.equalsIgnoreCase(c)).getOrElse(c)))

  /** Trims, maps city / cuisine onto stored spellings (so equality filters hit their indexes) and checks ranges. */
  private def normalize(c: IndiaCriteria): Either[AppError, IndiaCriteria] = {
    def clean(v: Option[String]) = v.map(_.trim).filter(_.nonEmpty)
    val errors = List(
      c.minRating.filter(r => r < 0 || r > 5).map(_ => "Minimum rating must be between 0 and 5."),
      c.maxCost.filter(v => v < 0 || v > IndiaValidation.MaxCost).map(_ => s"Maximum cost must be between ₹0 and ₹${IndiaValidation.MaxCost}.")
    ).flatten
    if (errors.nonEmpty) Left(ValidationError(errors))
    else
      for {
        city    <- clean(c.city).map(x => canonicalCity(x).map(Some(_))).getOrElse(Right(None))
        cuisine <- clean(c.cuisine).map(x => canonicalCuisines(List(x)).map(_.headOption)).getOrElse(Right(None))
      } yield c.copy(name = clean(c.name), city = city, cuisine = cuisine, locality = clean(c.locality))
  }

  private def parseId(raw: String): Either[AppError, Long] =
    raw.trim.toLongOption.filter(_ > 0).toRight(ValidationError(List("restaurant_id must be a positive whole number.")))

  private def attempt[A](operation: => A): Either[AppError, A] = Try(operation).toEither.left.map(AppError.fromThrowable)
}
