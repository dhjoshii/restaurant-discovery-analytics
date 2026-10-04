package restaurants.web

import restaurants.*
import restaurants.config.AppConfig
import restaurants.db.IndexStatus
import restaurants.india.*
import restaurants.model.*

import java.nio.charset.StandardCharsets
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

/** JSON API + static frontend, served by Cask (Undertow) on `$PORT` (default 8080).
  *
  * Run locally with: `scala-cli run . --main-class restaurants.web.WebServer`
  * The same [[AppContext]] (repository, service, analytics, indexes) powers the CLI.
  */
object WebServer extends cask.MainRoutes {

  override def host: String       = "0.0.0.0"
  override def port: Int          = AppConfig.port
  override def debugMode: Boolean = false

  override def main(args: Array[String]): Unit = {
    println(s"[zaika] Web server starting on http://localhost:$port")
    val warmUp = new Thread(() => {
      Backend.get match {
        case Right(ctx) => println(s"[zaika] Connected to ${ctx.namespace}")
        case Left(e)    => println(s"[zaika] MongoDB not reachable yet: ${e.message}")
      }
    })
    warmUp.setDaemon(true)
    warmUp.start()
    super.main(args)
  }

  // ======================================================== connection holder

  /** Connects lazily and retries (at most every 10 s) if MongoDB was unreachable. */
  private object Backend {
    @volatile private var context: Option[AppContext]            = None
    @volatile private var lastFailure: Option[(Long, AppError)] = None
    private val RetryAfterMillis                                 = 10000L

    def get: Either[AppError, AppContext] = context match {
      case Some(ctx) => Right(ctx)
      case None =>
        synchronized {
          (context, lastFailure) match {
            case (Some(ctx), _) => Right(ctx)
            case (None, Some((at, error))) if System.currentTimeMillis() - at < RetryAfterMillis => Left(error)
            case _ =>
              AppContext.connect() match {
                case Right(ctx) =>
                  context = Some(ctx)
                  lastFailure = None
                  ctx.indexes.ensureIndexes().foreach { r =>
                    r.status match {
                      case IndexStatus.Failed(reason) => println(s"[zaika] Index ${r.spec.name} failed: $reason")
                      case _                          => ()
                    }
                  }
                  Right(ctx)
                case Left(error) =>
                  lastFailure = Some(System.currentTimeMillis() -> error)
                  Left(error)
              }
          }
        }
    }
  }

  // ================================================================ helpers

  private val SecurityHeaders = Seq(
    "X-Content-Type-Options" -> "nosniff",
    "Referrer-Policy"        -> "strict-origin-when-cross-origin",
    "X-Frame-Options"        -> "DENY"
  )

  private def json(value: ujson.Value, status: Int = 200): cask.Response[String] =
    cask.Response(
      ujson.write(value),
      statusCode = status,
      headers = Seq("Content-Type" -> "application/json; charset=utf-8", "Cache-Control" -> "no-store") ++ SecurityHeaders
    )

  private def failure(error: AppError): cask.Response[String] = {
    val status = error match {
      case _: ValidationError => 400
      case _: NotFound        => 404
      case _: ConfigError     => 503
      case _: DatabaseError   => 503
    }
    val details = error match {
      case ValidationError(errors) => errors
      case _                       => List(error.message)
    }
    json(ujson.Obj("error" -> error.message, "details" -> ujson.Arr.from(details.map(ujson.Str(_)))), status)
  }

  /** Runs `f` against the live context, mapping every failure to a JSON error response. */
  private def handle(status: Int = 200)(f: AppContext => Either[AppError, ujson.Value]): cask.Response[String] =
    try Backend.get.flatMap(f).fold(failure, json(_, status))
    catch { case NonFatal(t) => failure(AppError.fromThrowable(t)) }

  private def intParam(raw: String, label: String): Either[AppError, Option[Int]] =
    raw.trim match {
      case "" => Right(None)
      case s  => s.toIntOption.map(Some(_)).toRight(ValidationError(List(s"$label must be a whole number.")))
    }

  private def intOr(raw: String, default: Int): Int = raw.trim.toIntOption.getOrElse(default)

  private def optional(raw: String): Option[String] = Option(raw.trim).filter(_.nonEmpty)

  private def isTrue(raw: String): Boolean = Set("1", "true", "yes", "on").contains(raw.trim.toLowerCase)

  private def readBody(request: cask.Request): Either[AppError, Map[String, String]] = {
    val text = request.text()
    if (text.length > 16 * 1024) Left(ValidationError(List("Request body is too large.")))
    else JsonCodec.parseObject(text).left.map(e => ValidationError(List(e)))
  }

  private def criteriaFrom(
      name: String,
      city: String,
      cuisine: String,
      locality: String,
      minRating: String,
      maxCost: String,
      online: String,
      table: String
  ): Either[AppError, IndiaCriteria] = {
    val rating = minRating.trim match {
      case "" => Right(None)
      case s  => s.toDoubleOption.map(Some(_)).toRight(ValidationError(List("minRating must be a number between 0 and 5.")))
    }
    for {
      r <- rating
      c <- intParam(maxCost, "maxCost")
    } yield IndiaCriteria(optional(name), optional(city), optional(cuisine), optional(locality), r, c, isTrue(online), isTrue(table))
  }

  // ================================================================= static

  private val StaticTypes = Map(
    "html" -> "text/html; charset=utf-8",
    "css"  -> "text/css; charset=utf-8",
    "js"   -> "text/javascript; charset=utf-8",
    "svg"  -> "image/svg+xml; charset=utf-8",
    "json" -> "application/json; charset=utf-8",
    "txt"  -> "text/plain; charset=utf-8"
  )
  private val staticCache = TrieMap.empty[String, Option[String]]

  private def staticFile(path: String): cask.Response[String] = {
    val safe = path.nonEmpty && path.split('/').forall(seg => seg.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*"))
    val ext  = path.split('.').lastOption.getOrElse("").toLowerCase
    val content =
      if (!safe || !StaticTypes.contains(ext)) None
      else
        staticCache.getOrElseUpdate(
          path,
          Option(getClass.getResourceAsStream(s"/public/$path")).map { in =>
            try new String(in.readAllBytes(), StandardCharsets.UTF_8)
            finally in.close()
          }
        )
    content match {
      case Some(body) =>
        cask.Response(body, 200, Seq("Content-Type" -> StaticTypes(ext), "Cache-Control" -> "no-cache") ++ SecurityHeaders)
      case None =>
        cask.Response("Not found", 404, Seq("Content-Type" -> "text/plain; charset=utf-8") ++ SecurityHeaders)
    }
  }

  // Pages accept (and ignore) any query string, e.g. tracking parameters.
  @cask.get("/")
  def index(params: cask.QueryParams): cask.Response[String] = staticFile("index.html")

  @cask.get("/assets", subpath = true)
  def assets(request: cask.Request, params: cask.QueryParams): cask.Response[String] =
    staticFile(("assets" +: request.remainingPathSegments).mkString("/"))

  // ================================================================== meta

  @cask.get("/api/health")
  def health(): cask.Response[String] =
    Backend.get match {
      case Right(ctx) =>
        val up = ctx.connection.ping().isSuccess
        json(ujson.Obj("status" -> (if (up) "ok" else "degraded"), "database" -> up, "namespace" -> ctx.namespace))
      case Left(e) => json(ujson.Obj("status" -> "degraded", "database" -> false, "error" -> e.message))
    }

  @cask.get("/api/meta")
  def meta(): cask.Response[String] = handle() { ctx =>
    for {
      cities   <- ctx.service.cities()
      cuisines <- ctx.service.cuisines()
      overview <- ctx.analytics.overview()
    } yield ujson.Obj(
      "namespace" -> ctx.namespace,
      "server"    -> ctx.connection.serverVersion.map(ujson.Str(_)).getOrElse(ujson.Null),
      "cities"    -> ujson.Arr.from(cities.map(ujson.Str(_))),
      "cuisines"  -> ujson.Arr.from(cuisines.map(ujson.Str(_))),
      "bands"     -> ujson.Arr.from(RatingBand.ordered.map(ujson.Str(_))),
      "sorts"     -> ujson.Arr.from(IndiaSort.values.toList.map(s => ujson.Obj("key" -> s.key, "label" -> s.label))),
      "overview"  -> overview.rows.headOption.map(IndiaJson.overview).getOrElse(ujson.Null)
    )
  }

  // ================================================================== CRUD

  @cask.get("/api/restaurants")
  def search(
      name: String = "",
      city: String = "",
      cuisine: String = "",
      locality: String = "",
      minRating: String = "",
      maxCost: String = "",
      online: String = "",
      table: String = "",
      sort: String = "rating",
      page: String = "1",
      pageSize: String = "12",
      explain: String = ""
  ): cask.Response[String] = handle() { ctx =>
    for {
      criteria <- criteriaFrom(name, city, cuisine, locality, minRating, maxCost, online, table)
      order    <- IndiaSort.fromKey(sort).toRight(ValidationError(List(s"Unknown sort '$sort'.")))
      request = PageRequest.of(intOr(page, 1), intOr(pageSize, 12))
      result <- ctx.service.search(criteria, order, request)
      plan <-
        if (isTrue(explain)) ctx.service.explain(criteria, order, request, forceCollectionScan = false).map(Some(_))
        else Right(None)
    } yield {
      val body = IndiaJson.page(result).obj
      body("criteria") = IndiaJson.criteria(criteria)
      body("sort") = order.key
      body("plan") = plan.map(JsonCodec.plan).getOrElse(ujson.Null)
      ujson.Obj.from(body)
    }
  }

  @cask.get("/api/restaurants/:id")
  def getOne(id: String): cask.Response[String] = handle() { ctx =>
    ctx.service.get(id).map(IndiaJson.restaurant)
  }

  @cask.post("/api/restaurants")
  def create(request: cask.Request): cask.Response[String] = handle(201) { ctx =>
    readBody(request).flatMap(f => ctx.service.create(IndiaJson.draft(f))).map(IndiaJson.restaurant)
  }

  @cask.put("/api/restaurants/:id")
  def update(id: String, request: cask.Request): cask.Response[String] = handle() { ctx =>
    readBody(request).flatMap(f => ctx.service.update(id, IndiaJson.patch(f))).map(IndiaJson.restaurant)
  }

  @cask.post("/api/restaurants/:id/ratings")
  def rate(id: String, request: cask.Request): cask.Response[String] = handle(201) { ctx =>
    readBody(request).flatMap(f => ctx.service.rate(id, f.getOrElse("rating", ""))).map(IndiaJson.restaurant)
  }

  @cask.delete("/api/restaurants/:id")
  def remove(id: String): cask.Response[String] = handle() { ctx =>
    ctx.service.delete(id).map(r => ujson.Obj("deleted" -> IndiaJson.restaurant(r)))
  }

  // ============================================================= analytics

  @cask.get("/api/analytics/overview")
  def overview(): cask.Response[String] = handle() { ctx =>
    ctx.analytics.overview().map(JsonCodec.aggregation(_)(IndiaJson.overview))
  }

  @cask.get("/api/analytics/cities")
  def byCity(limit: String = "12"): cask.Response[String] = handle() { ctx =>
    ctx.analytics.byCity(intOr(limit, 12)).map(JsonCodec.aggregation(_)(IndiaJson.cityStat))
  }

  @cask.get("/api/analytics/cuisines")
  def cuisines(limit: String = "10", city: String = ""): cask.Response[String] = handle() { ctx =>
    ctx.analytics.topCuisines(intOr(limit, 10), optional(city)).map(JsonCodec.aggregation(_)(IndiaJson.cuisineStat))
  }

  @cask.get("/api/analytics/city-ratings")
  def cityRatings(limit: String = "10", minRated: String = "20"): cask.Response[String] = handle() { ctx =>
    ctx.analytics.ratingByCity(intOr(limit, 10), intOr(minRated, 20)).map(JsonCodec.aggregation(_)(IndiaJson.cityRating))
  }

  @cask.get("/api/analytics/city-costs")
  def cityCosts(limit: String = "10", minRestaurants: String = "20"): cask.Response[String] = handle() { ctx =>
    ctx.analytics.costByCity(intOr(limit, 10), intOr(minRestaurants, 20)).map(JsonCodec.aggregation(_)(IndiaJson.cityCost))
  }

  @cask.get("/api/analytics/ratings")
  def bands(city: String = ""): cask.Response[String] = handle() { ctx =>
    ctx.analytics.ratingBands(optional(city)).map(JsonCodec.aggregation(_)(IndiaJson.band))
  }

  @cask.get("/api/analytics/top-rated")
  def topRated(city: String = "", cuisine: String = "", limit: String = "10", minVotes: String = "100"): cask.Response[String] =
    handle() { ctx =>
      ctx.analytics.topRated(intOr(limit, 10), intOr(minVotes, 100), optional(city), optional(cuisine)).map(JsonCodec.aggregation(_)(IndiaJson.top))
    }

  @cask.get("/api/analytics/services")
  def services(limit: String = "10", minRestaurants: String = "20"): cask.Response[String] = handle() { ctx =>
    ctx.analytics.services(intOr(limit, 10), intOr(minRestaurants, 20)).map(JsonCodec.aggregation(_)(IndiaJson.service))
  }

  // =============================================================== indexes

  @cask.get("/api/indexes")
  def indexes(): cask.Response[String] = handle() { ctx =>
    scala.util.Try(ctx.indexes.listIndexes()).toEither.left.map(AppError.fromThrowable).map { list =>
      ujson.Obj(
        "namespace" -> ctx.namespace,
        "indexes"   -> ujson.Arr.from(list.map(JsonCodec.index)),
        "presets"   -> ujson.Arr.from(IndiaPreset.values.toList.map(p => ujson.Obj("key" -> p.key, "label" -> p.label, "sort" -> p.sort.key)))
      )
    }
  }

  @cask.post("/api/indexes/ensure")
  def ensureIndexes(): cask.Response[String] = handle() { ctx =>
    scala.util.Try(ctx.indexes.ensureIndexes()).toEither.left.map(AppError.fromThrowable).map { results =>
      ujson.Obj("results" -> ujson.Arr.from(results.map { r =>
        val (status, detail) = r.status match {
          case IndexStatus.Created             => ("created", "")
          case IndexStatus.AlreadyExisted      => ("exists", "")
          case IndexStatus.CreatedNonUnique(w) => ("created-non-unique", w)
          case IndexStatus.Failed(reason)      => ("failed", reason)
        }
        ujson.Obj("name" -> r.spec.name, "status" -> status, "detail" -> detail)
      }))
    }
  }

  /** Explains a preset (or arbitrary filters) twice: planner's choice vs forced collection scan. */
  @cask.get("/api/indexes/explain")
  def explain(
      preset: String = "",
      name: String = "",
      city: String = "",
      cuisine: String = "",
      locality: String = "",
      minRating: String = "",
      maxCost: String = "",
      online: String = "",
      table: String = "",
      sort: String = "rating"
  ): cask.Response[String] = handle() { ctx =>
    val target: Either[AppError, (String, IndiaCriteria, IndiaSort)] = optional(preset) match {
      case Some(key) => IndiaPreset.fromKey(key).map(p => (p.label, p.criteria, p.sort)).toRight(ValidationError(List(s"Unknown preset '$key'.")))
      case None =>
        for {
          c <- criteriaFrom(name, city, cuisine, locality, minRating, maxCost, online, table)
          s <- IndiaSort.fromKey(sort).toRight(ValidationError(List(s"Unknown sort '$sort'.")))
        } yield (c.describe, c, s)
    }
    val page = PageRequest.of(1, 20)
    target.flatMap { case (label, criteria, order) =>
      for {
        indexed <- ctx.service.explain(criteria, order, page, forceCollectionScan = false)
        scanned <- ctx.service.explain(criteria, order, page, forceCollectionScan = true)
      } yield ujson.Obj("label" -> label, "sort" -> order.label, "indexed" -> JsonCodec.plan(indexed), "collectionScan" -> JsonCodec.plan(scanned))
    }
  }

  initialize()
}
