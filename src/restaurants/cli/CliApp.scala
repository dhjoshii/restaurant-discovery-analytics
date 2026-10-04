package restaurants.cli

import restaurants.AppContext
import restaurants.db.{IndexResult, IndexStatus}

import scala.annotation.tailrec

/** Entry point of the menu-driven command line application.
  *
  * Run with: `scala-cli run . --main-class restaurants.cli.CliApp`
  * Flags: `--no-color`, `--ascii` (no box-drawing characters),
  * `--echo-input` (print each answer when piping a script into the CLI).
  */
object CliApp {

  def main(args: Array[String]): Unit = {
    Term.configure(args.toSeq)
    val out = Term.setupConsole()
    Console.withOut(out) {
      printBanner()
      Term.info("Connecting to MongoDB…")
      AppContext.connect() match {
        case Left(error) =>
          Term.error(error.message)
          Term.line()
          sys.exit(1)
        case Right(context) =>
          try new CliSession(context).run()
          finally context.close()
      }
    }
  }

  private def printBanner(): Unit = {
    Term.line()
    Term.line(s"  ${Term.saffron(Term.bold("ZAIKA"))}  ${Term.muted("·")}  ${Term.bold("Restaurant Discovery & Analytics")}")
    Term.line(Term.muted("  Scala 3 · MongoDB · restaurants across India"))
  }
}

/** One interactive session: the main menu. */
final class CliSession(ctx: AppContext) {

  private val menus = new IndiaMenus(ctx)

  def run(): Unit = {
    Term.success(s"Connected to ${Term.bold(ctx.namespace)} ${Term.muted(s"on ${ctx.config.host}")}")
    ctx.connection.serverVersion.foreach(v => Term.info(s"MongoDB server version $v"))
    val count = scala.util.Try(ctx.repository.count()).getOrElse(0L)
    if (count == 0)
      Term.warn(s"${ctx.namespace} is empty — import the data with restaurants.india.ZomatoImporter (see README).")
    ensureIndexesQuietly()
    try mainMenu()
    catch {
      case _: InputClosed =>
        Term.line()
        Term.info("Input closed. Goodbye!")
    }
  }

  private def ensureIndexesQuietly(): Unit = {
    val results = ctx.indexes.ensureIndexes()
    val failed  = results.collect { case r @ IndexResult(_, IndexStatus.Failed(_)) => r }
    val created = results.count(_.status == IndexStatus.Created)
    if (failed.isEmpty) Term.success(s"${results.size} application indexes ready ($created created now).")
    else failed.foreach(f => Term.warn(s"Index ${f.spec.name} could not be created: ${f.status}"))
  }

  @tailrec
  private def mainMenu(): Unit = {
    Term.heading("Main menu", s"Collection ${ctx.namespace}")
    Term.menu(
      List(
        "1" -> "Add Restaurant",
        "2" -> "Search / View Restaurants",
        "3" -> "Update Restaurant",
        "4" -> "Delete Restaurant",
        "5" -> "Restaurant Analytics",
        "6" -> "Index Information",
        "7" -> "Exit"
      )
    )
    val keepGoing = Term.ask("Choose an option: ") match {
      case "1" => menus.addRestaurant(); true
      case "2" => menus.searchMenu(); true
      case "3" => menus.updateRestaurant(); true
      case "4" => menus.deleteRestaurant(); true
      case "5" => menus.analyticsMenu(); true
      case "6" => menus.indexMenu(); true
      case "7" | "q" | "exit" => false
      case unknown =>
        Term.error(s"'$unknown' is not a menu option. Enter a number from 1 to 7.")
        true
    }
    if (keepGoing) mainMenu()
    else {
      Term.line()
      Term.success("Dhanyavaad! Goodbye.")
      Term.line()
    }
  }
}
