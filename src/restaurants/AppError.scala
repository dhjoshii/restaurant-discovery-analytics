package restaurants

/** Every failure the application knows how to explain to a user.
  *
  * A sealed hierarchy lets the CLI and the web layer pattern-match on the exact
  * kind of error (e.g. 400 vs 404 vs 503) without ever leaking stack traces.
  */
sealed abstract class AppError(val message: String, cause: Throwable = null)
    extends Exception(message, cause)

/** One or more user inputs were invalid. */
final case class ValidationError(errors: List[String])
    extends AppError(errors.mkString("; "))

/** A restaurant with the given `restaurant_id` does not exist. */
final case class NotFound(restaurantId: String)
    extends AppError(s"No restaurant found with restaurant_id '$restaurantId'.")

/** Configuration (e.g. MONGODB_URI) is missing or malformed. */
final case class ConfigError(detail: String) extends AppError(detail)

/** MongoDB could not be reached or rejected an operation. */
final case class DatabaseError(detail: String, underlying: Throwable = null)
    extends AppError(detail, underlying)

object AppError {

  /** Turns any unexpected throwable into a safe, user-facing [[AppError]]. */
  def fromThrowable(t: Throwable): AppError = t match {
    case known: AppError => known
    case _: com.mongodb.MongoTimeoutException =>
      DatabaseError(
        "Timed out while contacting MongoDB. Check your internet connection, the connection string, " +
          "and that your IP address is allowed in Atlas > Security > Network Access.",
        t
      )
    case _: com.mongodb.MongoSecurityException =>
      DatabaseError("MongoDB rejected the credentials. Check the username and password in MONGODB_URI.", t)
    case e: com.mongodb.MongoException =>
      DatabaseError(s"MongoDB error (code ${e.getCode}): ${firstLine(e.getMessage)}", t)
    case other =>
      DatabaseError(s"Unexpected error: ${firstLine(Option(other.getMessage).getOrElse(other.getClass.getSimpleName))}", t)
  }

  private def firstLine(s: String): String =
    Option(s).map(_.linesIterator.nextOption().getOrElse("")).getOrElse("").take(300)
}
