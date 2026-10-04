package restaurants.cli

import restaurants.db.IndexManager
import restaurants.india.*
import restaurants.model.{PageRequest, QueryPlan}
import restaurants.{AppContext, AppError, NotFound, ValidationError}

import java.text.NumberFormat
import java.util.Locale
import scala.annotation.tailrec

/** Menus for the India dataset (`sample_restaurants.india_restaurants`, imported from Zomato). */
final class IndiaMenus(ctx: AppContext) extends MenuSupport {

  private val india    = ctx
  private val PageSize = 10
  private val rupees   = NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")) // 1,00,000 grouping

  override def title: String              = "India · Zomato restaurants"
  override def namespace: String          = ctx.namespace
  override def collectionName: String     = ctx.config.collection
  override def indexManager: IndexManager = india.indexes

  override def explainPresets: List[(String, () => Either[AppError, (QueryPlan, QueryPlan)])] =
    IndiaPreset.values.toList.map { p =>
      p.label -> { () =>
        val page = PageRequest.of(1, 20)
        for {
          indexed <- india.service.explain(p.criteria, p.sort, page, forceCollectionScan = false)
          scanned <- india.service.explain(p.criteria, p.sort, page, forceCollectionScan = true)
        } yield (indexed, scanned)
      }
    }

  private def inr(n: Int): String = if (n <= 0) Term.muted("unknown") else s"₹${rupees.format(n.toLong)}"

  private def ratingLabel(r: IndiaRestaurant): String =
    if (!r.isRated) Term.muted("Not rated") else Term.ratingColor(r.rating, f"${r.rating}%.1f") + Term.muted(s" ${r.ratingText}")

  // ================================================================== CREATE

  override def addRestaurant(): Unit = {
    Term.heading("Add restaurant", "Fields marked * are required. Type ? at the city or cuisine prompt to list existing values.")

    @tailrec
    def loop(previous: IndiaDraft): Unit = {
      val draft = promptDraft(previous)
      india.service.create(draft) match {
        case Right(saved) =>
          Term.line()
          Term.success(s"Inserted ${Term.bold(saved.displayName)} with restaurant_id ${Term.saffron(saved.restaurantId.toString)} (_id ${saved.objectId.getOrElse("?")}).")
          printDetails(saved)
        case Left(ValidationError(errors)) =>
          Term.line()
          Term.errors(errors)
          if (Term.confirm("Fix the highlighted fields and try again?")) loop(draft)
        case Left(other) => Term.error(other.message)
      }
    }

    loop(IndiaDraft("", "", "", "", "", ""))
  }

  private def promptDraft(d: IndiaDraft): IndiaDraft = {
    val name     = Term.askWithDefault("Name *", d.name)
    val city     = askListed("City *", d.city, india.service.cities())
    val locality = Term.askWithDefault("Locality * (e.g. Connaught Place)", d.locality)
    val address  = Term.askWithDefault("Street address", d.address)
    val cuisines = askListed("Cuisines * (comma separated, e.g. North Indian, Mughlai)", d.cuisines, india.service.cuisines())
    val cost     = Term.askWithDefault("Average cost for two in ₹ *", d.costForTwo)
    val price    = Term.askWithDefault("Price range 1-4 (Enter = derive from cost)", d.priceRange)
    val online   = Term.askWithDefault("Online delivery? (y/N)", d.onlineDelivery)
    val table    = Term.askWithDefault("Table booking? (y/N)", d.tableBooking)
    val lat      = Term.askWithDefault("Latitude (optional, e.g. 28.6315)", d.latitude)
    val lon      = if (lat.isEmpty) "" else Term.askWithDefault("Longitude (e.g. 77.2167)", d.longitude)
    IndiaDraft(name, city, locality, address, cuisines, cost, price, online, table, lat, lon)
  }

  /** A prompt where `?` prints the known values (cities or cuisines) before asking again. */
  @tailrec
  private def askListed(prompt: String, default: String, values: => Either[AppError, List[String]]): String = {
    val answer = Term.askWithDefault(prompt, default)
    if (answer == "?") {
      values match {
        case Right(all) => printColumns(all)
        case Left(e)    => Term.error(e.message)
      }
      askListed(prompt, default, values)
    } else answer
  }

  // ==================================================================== READ

  override def searchMenu(): Unit = searchLoop()

  @tailrec
  private def searchLoop(): Unit = {
    Term.heading("Search / view restaurants", namespace)
    Term.menu(
      List(
        "1" -> "Search by name (partial, case-insensitive)",
        "2" -> "Filter by city (top rated first)",
        "3" -> "Filter by cuisine",
        "4" -> "Filter by minimum rating",
        "5" -> "Filter by budget (max cost for two)",
        "6" -> "Advanced search (combine filters, delivery, booking, sort)",
        "7" -> "View one restaurant by restaurant_id",
        "8" -> "Browse all (top rated first)",
        "0" -> "Back"
      )
    )
    var back = false
    val criteria: Option[(IndiaCriteria, IndiaSort)] = Term.ask("Choose an option: ") match {
      case "1" => nonEmpty(Term.ask("Name contains: ")).map(n => IndiaCriteria(name = Some(n)) -> IndiaSort.NameAsc)
      case "2" => nonEmpty(askListed("City", "", india.service.cities())).map(c => IndiaCriteria(city = Some(c)) -> IndiaSort.TopRated)
      case "3" => nonEmpty(askListed("Cuisine", "", india.service.cuisines())).map(c => IndiaCriteria(cuisine = Some(c)) -> IndiaSort.TopRated)
      case "4" =>
        askRating("Minimum rating 0-5 (e.g. 4): ").map(r => IndiaCriteria(minRating = Some(r)) -> IndiaSort.TopRated)
      case "5" =>
        Term.askOptionalInt("Maximum cost for two in ₹: ", 0, IndiaValidation.MaxCost).map(c => IndiaCriteria(maxCost = Some(c)) -> IndiaSort.CostLow)
      case "6" => Some(advancedCriteria())
      case "7" =>
        viewById()
        None
      case "8" => Some(IndiaCriteria() -> IndiaSort.TopRated)
      case "0" | "" =>
        back = true
        None
      case other =>
        Term.error(s"'$other' is not a menu option.")
        None
    }
    criteria.foreach { case (c, sort) => showResults(c, sort, 1) }
    if (!back) searchLoop()
  }

  private def askRating(prompt: String): Option[Double] = {
    val raw = Term.ask(prompt)
    if (raw.isEmpty) None
    else
      raw.toDoubleOption.filter(r => r >= 0 && r <= 5) match {
        case some @ Some(_) => some
        case None =>
          Term.error("Please enter a number between 0 and 5.")
          askRating(prompt)
      }
  }

  private def advancedCriteria(): (IndiaCriteria, IndiaSort) = {
    Term.info("Press Enter to skip any filter.")
    val name     = nonEmpty(Term.ask("Name contains: "))
    val city     = nonEmpty(askListed("City", "", india.service.cities()))
    val cuisine  = nonEmpty(askListed("Cuisine", "", india.service.cuisines()))
    val locality = nonEmpty(Term.ask("Locality contains: "))
    val minR     = askRating("Minimum rating 0-5: ")
    val maxC     = Term.askOptionalInt("Maximum cost for two in ₹: ", 0, IndiaValidation.MaxCost)
    val online   = Term.confirm("Only restaurants with online delivery?")
    val table    = Term.confirm("Only restaurants with table booking?")
    val sorts    = IndiaSort.values.toList
    val sort = Term.ask(s"Sort: ${sorts.zipWithIndex.map((s, i) => s"${i + 1} ${s.label}").mkString(" · ")} [1]: ").toIntOption match {
      case Some(i) if i >= 1 && i <= sorts.size => sorts(i - 1)
      case _                                    => IndiaSort.TopRated
    }
    IndiaCriteria(name, city, cuisine, locality, minR, maxC, online, table) -> sort
  }

  @tailrec
  private def showResults(criteria: IndiaCriteria, sort: IndiaSort, pageNo: Int): Unit =
    india.service.search(criteria, sort, PageRequest.of(pageNo, PageSize)) match {
      case Left(ValidationError(errors)) => Term.errors(errors)
      case Left(error)                   => Term.error(error.message)
      case Right(page) if page.total == 0 =>
        Term.warn(s"No restaurants match ${criteria.describe}.")
      case Right(page) =>
        Term.heading(s"Results · page ${page.page} of ${page.totalPages}", s"${fmt(page.total)} restaurants where ${criteria.describe} · sorted ${sort.label}")
        Term.line(
          Term.table(
            List("#", "ID", "Name", "City", "Locality", "Cuisines", "Rating", "Votes", "Cost for two"),
            page.items.zipWithIndex.map { (r, i) =>
              List(
                (i + 1).toString,
                r.restaurantId.toString,
                r.displayName,
                r.city,
                r.locality,
                r.cuisines.take(2).mkString(", "),
                if (r.isRated) Term.ratingColor(r.rating, f"${r.rating}%.1f") else Term.muted("-"),
                fmt(r.votes.toLong),
                inr(r.costForTwo)
              )
            },
            rightAligned = Set(0, 6, 7, 8),
            maxWidth = 30
          )
        )
        Term.line(Term.muted("  Ratings are Zomato's 1–5 scale: higher is better."))
        val options = List(
          Option.when(page.hasNext)("n next"),
          Option.when(page.hasPrevious)("p previous"),
          Some(s"1-${page.items.size} details"),
          Some("i page insights"),
          Some("Enter back")
        ).flatten.mkString(Term.muted(" · "))
        Term.ask(s"$options: ").toLowerCase match {
          case "n" if page.hasNext     => showResults(criteria, sort, pageNo + 1)
          case "p" if page.hasPrevious => showResults(criteria, sort, pageNo - 1)
          case "i" =>
            printInsights(page.items)
            Term.pause()
            showResults(criteria, sort, pageNo)
          case n if n.toIntOption.exists(i => i >= 1 && i <= page.items.size) =>
            printDetails(page.items(n.toInt - 1))
            Term.pause()
            showResults(criteria, sort, pageNo)
          case _ => ()
        }
    }

  private def viewById(): Unit =
    nonEmpty(Term.ask("restaurant_id: ")).foreach { id =>
      india.service.get(id) match {
        case Right(r)                 => printDetails(r)
        case Left(ValidationError(e)) => Term.errors(e)
        case Left(e)                  => Term.error(e.message)
      }
    }

  private def printDetails(r: IndiaRestaurant): Unit = {
    Term.heading(r.displayName, s"${r.locality}, ${r.city}")
    Term.keyValues(
      List(
        "restaurant_id"   -> Term.saffron(r.restaurantId.toString),
        "_id"             -> r.objectId.getOrElse("-"),
        "Address"         -> (if (r.address.isEmpty) Term.muted("not recorded") else r.address),
        "Cuisines"        -> r.cuisines.mkString(", "),
        "Rating"          -> (if (r.isRated) s"${ratingLabel(r)} ${Term.muted(s"from ${fmt(r.votes.toLong)} votes")}" else ratingLabel(r)),
        "Cost for two"    -> s"${inr(r.costForTwo)}  ${Term.muted(r.priceSymbol)}",
        "Online delivery" -> yesNo(r.hasOnlineDelivery),
        "Table booking"   -> yesNo(r.hasTableBooking),
        "Coordinates"     -> r.coord.map(c => f"${c.latitude}%.5f, ${c.longitude}%.5f").getOrElse(Term.muted("not recorded"))
      )
    )
  }

  private def yesNo(b: Boolean): String = if (b) Term.jade("Yes") else Term.muted("No")

  /** Client-side insights for one page, computed purely with Scala collections. */
  private def printInsights(items: List[IndiaRestaurant]): Unit = {
    val byCity      = items.groupBy(_.city).view.mapValues(_.size).toList.sortBy((c, n) => (-n, c))
    val topCuisines = items.flatMap(_.cuisines).groupBy(identity).map((c, xs) => c -> xs.size).toList.sortBy((c, n) => (-n, c)).take(3)
    val rated       = items.filter(_.isRated)
    val avgRating   = if (rated.isEmpty) None else Some(rated.map(_.rating).sum / rated.size)
    val withCost    = items.filter(_.costForTwo > 0)
    val cheapest    = withCost.minByOption(_.costForTwo)
    val online      = items.count(_.hasOnlineDelivery)
    Term.heading("Page insights", "Computed in Scala with groupBy / flatMap / filter / minByOption over this page.")
    Term.keyValues(
      List(
        "Restaurants"     -> items.size.toString,
        "By city"         -> byCity.map((c, n) => s"$c $n").mkString(", "),
        "Top cuisines"    -> topCuisines.map((c, n) => s"$c $n").mkString(", "),
        "Mean rating"     -> avgRating.map(r => Term.ratingColor(r, f"$r%.2f")).getOrElse(Term.muted("no rated restaurants")),
        "Cheapest"        -> cheapest.map(r => s"${r.displayName} (${inr(r.costForTwo)})").getOrElse("-"),
        "Online delivery" -> s"$online of ${items.size}"
      )
    )
  }

  // ================================================================== UPDATE

  override def updateRestaurant(): Unit = {
    Term.heading("Update restaurant", namespace)
    nonEmpty(Term.ask("restaurant_id to update: ")).foreach { id =>
      india.service.get(id) match {
        case Left(e) => Term.error(e.message)
        case Right(current) =>
          printDetails(current)
          updateLoop(current)
      }
    }
  }

  @tailrec
  private def updateLoop(current: IndiaRestaurant): Unit = {
    Term.line()
    Term.menu(
      List(
        "1" -> "Change name",
        "2" -> "Change city / locality / address",
        "3" -> "Change cuisines",
        "4" -> "Change cost for two / price range",
        "5" -> "Change online delivery / table booking",
        "6" -> "Change coordinates",
        "7" -> "Add a diner rating (1-5)",
        "0" -> "Done"
      )
    )
    val id = current.restaurantId.toString
    val result: Option[Either[AppError, IndiaRestaurant]] = Term.ask("What would you like to change? ") match {
      case "1" => Some(india.service.update(id, IndiaPatch(name = Some(Term.askWithDefault("New name", current.name)))))
      case "2" =>
        Some(
          india.service.update(
            id,
            IndiaPatch(
              city = Some(askListed("City", current.city, india.service.cities())),
              locality = Some(Term.askWithDefault("Locality", current.locality)),
              address = Some(Term.askWithDefault("Address", current.address))
            )
          )
        )
      case "3" =>
        Some(india.service.update(id, IndiaPatch(cuisines = Some(askListed("Cuisines (comma separated)", current.cuisines.mkString(", "), india.service.cuisines())))))
      case "4" =>
        Some(
          india.service.update(
            id,
            IndiaPatch(
              costForTwo = Some(Term.askWithDefault("Cost for two in ₹", current.costForTwo.toString)),
              priceRange = Some(Term.askWithDefault("Price range 1-4", current.priceRange.toString))
            )
          )
        )
      case "5" =>
        Some(
          india.service.update(
            id,
            IndiaPatch(
              onlineDelivery = Some(Term.askWithDefault("Online delivery? (y/n)", if (current.hasOnlineDelivery) "y" else "n")),
              tableBooking = Some(Term.askWithDefault("Table booking? (y/n)", if (current.hasTableBooking) "y" else "n"))
            )
          )
        )
      case "6" =>
        Term.info("Leave both blank to clear the coordinates.")
        val lat = Term.ask("Latitude: ")
        val lon = Term.ask("Longitude: ")
        Some(india.service.update(id, IndiaPatch(latitude = Some(lat), longitude = Some(lon))))
      case "7" =>
        Term.info("The new average is computed atomically inside MongoDB with a pipeline update.")
        Some(india.service.rate(id, Term.ask("Your rating (1-5, e.g. 4.5): ")))
      case "0" | "" => None
      case other =>
        Term.error(s"'$other' is not an option.")
        Some(Right(current))
    }
    result match {
      case None => ()
      case Some(Right(updated)) =>
        if (updated != current) {
          Term.success(s"Updated restaurant ${updated.restaurantId}.")
          printChanges(current, updated)
        }
        updateLoop(updated)
      case Some(Left(ValidationError(errors))) =>
        Term.errors(errors)
        updateLoop(current)
      case Some(Left(e)) =>
        Term.error(e.message)
        updateLoop(current)
    }
  }

  private def printChanges(before: IndiaRestaurant, after: IndiaRestaurant): Unit = {
    val fields = List[(String, IndiaRestaurant => String)](
      "name"     -> (_.name),
      "city"     -> (_.city),
      "locality" -> (_.locality),
      "address"  -> (_.address),
      "cuisines" -> (_.cuisines.mkString(", ")),
      "cost"     -> (r => s"₹${r.costForTwo}"),
      "price"    -> (_.priceRange.toString),
      "delivery" -> (r => if (r.hasOnlineDelivery) "yes" else "no"),
      "booking"  -> (r => if (r.hasTableBooking) "yes" else "no"),
      "coord"    -> (_.coord.map(c => s"${c.latitude}, ${c.longitude}").getOrElse("none")),
      "rating"   -> (r => f"${r.rating}%.1f (${r.votes} votes, ${r.ratingText})")
    )
    fields.foreach { case (label, get) =>
      if (get(before) != get(after))
        Term.line(s"     ${Term.muted(label.padTo(9, ' '))} ${Term.coral(get(before))} ${Term.muted("→")} ${Term.jade(get(after))}")
    }
  }

  // ================================================================== DELETE

  override def deleteRestaurant(): Unit = {
    Term.heading("Delete restaurant", namespace)
    nonEmpty(Term.ask("restaurant_id to delete: ")).foreach { id =>
      india.service.get(id) match {
        case Left(e) => Term.error(e.message)
        case Right(r) =>
          printDetails(r)
          Term.warn("This permanently removes the document from MongoDB.")
          val rid = r.restaurantId.toString
          if (Term.ask(s"Type the restaurant_id ${Term.saffron(rid)} to confirm: ") == rid)
            india.service.delete(rid) match {
              case Right(deleted)    => Term.success(s"Deleted ${Term.bold(deleted.displayName)} ($rid).")
              case Left(NotFound(_)) => Term.warn("It was already deleted by someone else.")
              case Left(e)           => Term.error(e.message)
            }
          else Term.info("Cancelled. Nothing was deleted.")
      }
    }
  }

  // =============================================================== ANALYTICS

  override def analyticsMenu(): Unit = analyticsLoop()

  @tailrec
  private def analyticsLoop(): Unit = {
    Term.heading("Restaurant analytics", "Every report is a MongoDB aggregation pipeline.")
    Term.menu(
      List(
        "1" -> "Restaurants by city",
        "2" -> "Most popular cuisines",
        "3" -> "Average rating by city",
        "4" -> "Average cost for two by city",
        "5" -> "Rating distribution (Excellent … Not rated)",
        "6" -> "Top-rated restaurants",
        "7" -> "Online delivery & table booking by city",
        "8" -> "Dataset overview",
        "0" -> "Back"
      )
    )
    val stay = Term.ask("Choose a report: ") match {
      case "1" =>
        report(india.analytics.byCity(15)) { rows =>
          Term.heading("Restaurants by city", "Top 15 cities")
          val max = rows.map(_.restaurants).maxOption.getOrElse(1L).toDouble
          Term.line(
            Term.table(
              List("City", "Restaurants", "Avg rating", "Avg cost for two", ""),
              rows.map(r =>
                List(r.city, fmt(r.restaurants), r.avgRating.map(fmt2).getOrElse("-"), r.avgCost.map(c => inr(c.toInt)).getOrElse("-"), Term.bar(r.restaurants.toDouble, max))
              ),
              Set(1, 2, 3)
            )
          )
        }
        true
      case "2" =>
        val city = optionalCity()
        report(india.analytics.topCuisines(15, city)) { rows =>
          Term.heading("Most popular cuisines", city.map(c => s"Top 15 in $c").getOrElse("Top 15 across India"))
          val max = rows.map(_.restaurants).maxOption.getOrElse(1L).toDouble
          Term.line(
            Term.table(
              List("Cuisine", "Restaurants", "Avg rating", ""),
              rows.map(r => List(r.cuisine, fmt(r.restaurants), r.avgRating.map(fmt2).getOrElse("-"), Term.bar(r.restaurants.toDouble, max))),
              Set(1, 2)
            )
          )
        }
        true
      case "3" =>
        report(india.analytics.ratingByCity(15, 20)) { rows =>
          Term.heading("Average rating by city", "Rated restaurants only; cities with at least 20 of them. Higher is better.")
          Term.line(
            Term.table(
              List("City", "Avg rating", "Rated restaurants", "Votes", ""),
              rows.map(r => List(r.city, Term.ratingColor(r.avgRating, fmt2(r.avgRating)), fmt(r.rated), fmt(r.votes), Term.bar(r.avgRating, 5.0))),
              Set(1, 2, 3)
            )
          )
        }
        true
      case "4" =>
        report(india.analytics.costByCity(15, 20)) { rows =>
          Term.heading("Average cost for two by city", "Known costs only; cities with at least 20 restaurants.")
          val max = rows.map(_.avgCost).maxOption.getOrElse(1.0)
          Term.line(
            Term.table(
              List("City", "Avg cost", "Cheapest", "Priciest", "Restaurants", ""),
              rows.map(r => List(r.city, inr(r.avgCost.toInt), inr(r.minCost), inr(r.maxCost), fmt(r.restaurants), Term.bar(r.avgCost, max))),
              Set(1, 2, 3, 4)
            )
          )
        }
        true
      case "5" =>
        val city = optionalCity()
        report(india.analytics.ratingBands(city)) { rows =>
          Term.heading("Rating distribution", city.getOrElse("All of India"))
          val max = rows.map(_.restaurants).maxOption.getOrElse(1L).toDouble
          Term.line(
            Term.table(
              List("Band", "Restaurants", "Share", ""),
              rows.map(r => List(r.band, fmt(r.restaurants), s"${fmt1(r.percent)}%", Term.bar(r.restaurants.toDouble, max, 24))),
              Set(1, 2)
            )
          )
        }
        true
      case "6" =>
        val city    = optionalCity()
        val cuisine = nonEmpty(askListed("Cuisine (Enter for all)", "", india.service.cuisines()))
        report(india.analytics.topRated(15, 100, city, cuisine)) { rows =>
          Term.heading("Top-rated restaurants", "Highest rating with at least 100 votes.")
          Term.line(
            Term.table(
              List("#", "ID", "Name", "City", "Locality", "Rating", "Votes", "Cost for two"),
              rows.zipWithIndex.map((r, i) =>
                List((i + 1).toString, r.restaurantId.toString, r.name, r.city, r.locality, Term.ratingColor(r.rating, f"${r.rating}%.1f"), fmt(r.votes.toLong), inr(r.costForTwo))
              ),
              Set(0, 5, 6, 7),
              maxWidth = 30
            )
          )
        }
        true
      case "7" =>
        report(india.analytics.services(15, 20)) { rows =>
          Term.heading("Online delivery & table booking by city", "Share of restaurants offering each service.")
          Term.line(
            Term.table(
              List("City", "Restaurants", "Online delivery", "", "Table booking"),
              rows.map(r => List(r.city, fmt(r.restaurants), s"${fmt1(r.onlineDelivery)}%", Term.bar(r.onlineDelivery, 100, 16), s"${fmt1(r.tableBooking)}%")),
              Set(1, 2, 4)
            )
          )
        }
        true
      case "8" =>
        report(india.analytics.overview()) { rows =>
          rows.headOption.foreach { o =>
            Term.heading("Dataset overview")
            Term.keyValues(
              List(
                "Restaurants"       -> fmt(o.restaurants),
                "Cities"            -> o.cities.toString,
                "Cuisines"          -> o.cuisines.toString,
                "Votes"             -> fmt(o.votes),
                "Avg rating"        -> o.avgRating.map(fmt2).getOrElse("-"),
                "Avg cost for two"  -> o.avgCost.map(c => inr(c.toInt)).getOrElse("-")
              )
            )
          }
        }
        true
      case "0" | "" => false
      case other =>
        Term.error(s"'$other' is not a menu option.")
        true
    }
    if (stay) analyticsLoop()
  }

  private def optionalCity(): Option[String] = {
    Term.info("Optional: restrict to one city (Enter for all of India, ? to list cities).")
    nonEmpty(askListed("City", "", india.service.cities())).map(c =>
      india.service.cities().toOption.flatMap(_.find(_.equalsIgnoreCase(c))).getOrElse(c)
    )
  }
}
