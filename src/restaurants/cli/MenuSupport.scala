package restaurants.cli

import restaurants.AppError
import restaurants.analytics.Aggregation
import restaurants.db.{IndexManager, IndexStatus}
import restaurants.model.QueryPlan

import java.text.NumberFormat
import java.util.Locale
import scala.annotation.tailrec

/** Reusable menu behaviour mixed into [[IndiaMenus]]: formatting helpers,
  * aggregation-report printing and the complete index menu. Concrete menus only
  * supply the abstract members (template-method pattern).
  */
trait MenuSupport {

  /** Short name shown in the main menu, e.g. "India · Zomato". */
  def title: String
  def namespace: String
  def collectionName: String
  def indexManager: IndexManager

  /** (label, run) pairs; `run` returns (planner's plan, forced collection-scan plan). */
  def explainPresets: List[(String, () => Either[AppError, (QueryPlan, QueryPlan)])]

  def addRestaurant(): Unit
  def searchMenu(): Unit
  def updateRestaurant(): Unit
  def deleteRestaurant(): Unit
  def analyticsMenu(): Unit

  // ------------------------------------------------------------- formatting

  private val numbers = NumberFormat.getIntegerInstance(Locale.US)

  protected def fmt(n: Long): String   = numbers.format(n)
  protected def fmt1(d: Double): String = f"$d%.1f"
  protected def fmt2(d: Double): String = f"$d%.2f"

  protected def nonEmpty(s: String): Option[String] = Option(s.trim).filter(_.nonEmpty)

  protected def printColumns(values: List[String]): Unit = {
    val width = values.map(_.length).maxOption.getOrElse(10).min(28) + 2
    values.grouped(3).foreach(row => Term.line("    " + row.map(v => v.take(width - 2).padTo(width, ' ')).mkString))
  }

  /** Prints an aggregation result, then optionally the exact pipeline that produced it. */
  protected def report[A](result: Either[AppError, Aggregation[A]])(render: List[A] => Unit): Unit =
    result match {
      case Left(e) => Term.error(e.message)
      case Right(agg) =>
        if (agg.rows.isEmpty) Term.warn("The aggregation returned no rows.") else render(agg.rows)
        if (Term.ask(Term.muted("p = show aggregation pipeline · Enter = continue: ")).equalsIgnoreCase("p")) {
          Term.heading("Pipeline", s"db.$collectionName.aggregate([...])")
          agg.pipeline.zipWithIndex.foreach((stage, i) => Term.line(s"   ${Term.muted(s"${i + 1}.")} ${Term.sky(stage.toJson)}"))
          Term.pause()
        }
    }

  // ---------------------------------------------------------------- indexes

  @tailrec
  final def indexMenu(): Unit = {
    Term.heading("Index information", namespace)
    Term.menu(
      List(
        "1" -> "List indexes on the collection",
        "2" -> "Create / verify the application indexes",
        "3" -> "Explain a query: index scan vs collection scan",
        "0" -> "Back"
      )
    )
    val stay = Term.ask("Choose an option: ") match {
      case "1" => listIndexes(); true
      case "2" =>
        indexManager.ensureIndexes().foreach { r =>
          val status = r.status match {
            case IndexStatus.Created             => Term.jade("created")
            case IndexStatus.AlreadyExisted      => Term.muted("already exists")
            case IndexStatus.CreatedNonUnique(w) => Term.saffron(s"created without unique ($w)")
            case IndexStatus.Failed(reason)      => Term.coral(s"failed: $reason")
          }
          Term.line(s"   ${Term.bold(r.spec.name.padTo(48, ' '))} $status")
        }
        true
      case "3"      => explainMenu(); true
      case "0" | "" => false
      case other    => Term.error(s"'$other' is not a menu option."); true
    }
    if (stay) indexMenu()
  }

  private def listIndexes(): Unit =
    scala.util.Try(indexManager.listIndexes()).toEither.left.map(AppError.fromThrowable) match {
      case Left(e) => Term.error(e.message)
      case Right(indexes) =>
        Term.heading(s"${indexes.size} indexes on $namespace")
        Term.line(
          Term.table(
            List("Name", "Keys", "Type", "Used for"),
            indexes.map { i =>
              val kind = List(
                Option.when(i.isCompound)("compound"),
                Option.when(!i.isCompound)("single"),
                Option.when(i.unique)("unique"),
                Option.when(i.multikeyField)("multikey")
              ).flatten.mkString(", ")
              List(Term.saffron(i.name), i.keys.map((k, d) => s"$k: $d").mkString(", "), kind, i.purpose)
            },
            maxWidth = 70
          )
        )
        Term.info(s"${indexes.size - 1} secondary indexes, ${indexes.count(_.isCompound)} of them compound.")
    }

  private def explainMenu(): Unit = {
    Term.heading("Explain", "Runs explain(\"executionStats\") twice: with the planner's choice and with a forced collection scan.")
    explainPresets.zipWithIndex.foreach { case ((label, _), i) => Term.line(s"   ${Term.saffron(Term.bold((i + 1).toString))}  $label") }
    Term.ask("Choose a query: ").toIntOption.filter(i => i >= 1 && i <= explainPresets.size) match {
      case None => Term.error("Please choose one of the listed queries.")
      case Some(i) =>
        explainPresets(i - 1)._2() match {
          case Left(e) => Term.error(e.message)
          case Right((indexed, scanned)) =>
            Term.line(s"   ${Term.muted("filter")}  ${Term.sky(indexed.filterJson)}")
            Term.line(
              Term.table(
                List("", "With index", "Collection scan"),
                List(
                  List("Plan", indexed.pipelineLabel, scanned.pipelineLabel),
                  List("Index used", if (indexed.indexesUsed.isEmpty) "none" else indexed.indexesUsed.mkString(", "), "none"),
                  List("Keys examined", fmt(indexed.keysExamined), fmt(scanned.keysExamined)),
                  List("Docs examined", fmt(indexed.docsExamined), fmt(scanned.docsExamined)),
                  List("Returned", fmt(indexed.returned), fmt(scanned.returned)),
                  List("Time (ms)", fmt(indexed.millis), fmt(scanned.millis))
                ),
                maxWidth = 52
              )
            )
            if (indexed.usesIndex && scanned.docsExamined > 0)
              Term.success(
                s"The index read ${fmt(indexed.keysExamined)} keys and fetched ${fmt(indexed.docsExamined)} documents; " +
                  s"a collection scan reads all ${fmt(scanned.docsExamined)}."
              )
        }
    }
  }
}
