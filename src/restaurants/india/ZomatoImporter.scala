package restaurants.india

import com.mongodb.client.model.InsertManyOptions
import org.bson.Document
import restaurants.AppContext
import restaurants.db.IndexStatus
import restaurants.model.Coordinates

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** Imports the Kaggle "Zomato Restaurants Data" CSV (zomato.csv) into `india_restaurants`.
  *
  * Usage: `scala-cli run . --main-class restaurants.india.ZomatoImporter -- path/to/zomato.csv [--replace]`
  *
  * Only rows with Country Code 1 (India) are imported. The file is read as UTF-8
  * when valid, otherwise as Windows-1252 (the encoding the Kaggle file ships in).
  */
object ZomatoImporter {

  private val Required = List(
    "Restaurant ID", "Restaurant Name", "Country Code", "City", "Address", "Locality", "Longitude", "Latitude", "Cuisines",
    "Average Cost for two", "Has Table booking", "Has Online delivery", "Price range", "Aggregate rating", "Votes"
  )

  def main(args: Array[String]): Unit = {
    val replace = args.contains("--replace")
    val path = args.find(a => !a.startsWith("--")).map(Paths.get(_)).getOrElse {
      println("Usage: ZomatoImporter <path/to/zomato.csv> [--replace]")
      sys.exit(2)
    }
    if (!Files.isRegularFile(path)) { println(s"File not found: $path"); sys.exit(2) }

    val rows = parseCsv(readText(path))
    if (rows.isEmpty) { println("The file is empty."); sys.exit(1) }
    val header  = rows.head.map(_.trim.stripPrefix("﻿"))
    val missing = Required.filterNot(header.contains)
    if (missing.nonEmpty) { println(s"Not the expected Zomato file; missing columns: ${missing.mkString(", ")}"); sys.exit(1) }
    val col = header.zipWithIndex.toMap

    val (restaurants, skipped) = rows.tail.filter(_.exists(_.trim.nonEmpty)).foldLeft((ListBuffer.empty[IndiaRestaurant], 0)) {
      case ((ok, bad), row) =>
        def at(name: String): String = row.lift(col(name)).getOrElse("").trim
        if (at("Country Code") != "1") (ok, bad) // not India
        else
          toRestaurant(at) match {
            case Some(r) => (ok += r, bad)
            case None    => (ok, bad + 1)
          }
    }

    println(s"Parsed ${rows.size - 1} rows -> ${restaurants.size} Indian restaurants (${skipped} malformed rows skipped).")

    AppContext.connect() match {
      case Left(error) =>
        println(s"Could not connect: ${error.message}")
        sys.exit(1)
      case Right(ctx) =>
        try {
          val collection = ctx.collection
          val existing   = collection.estimatedDocumentCount()
          if (existing > 0 && !replace) {
            println(s"${ctx.config.database}.${ctx.config.collection} already has $existing documents. Re-run with --replace to reload.")
          } else {
            if (existing > 0) { collection.drop(); println(s"Dropped the existing $existing documents.") }
            restaurants.grouped(1000).foreach { batch =>
              collection.insertMany(batch.map(IndiaCodec.toDocument).toList.asJava, new InsertManyOptions().ordered(false))
            }
            println(s"Inserted ${collection.countDocuments()} documents into ${ctx.config.database}.${ctx.config.collection}.")
          }
          ctx.indexes.ensureIndexes().foreach { r =>
            val status = r.status match {
              case IndexStatus.Created             => "created"
              case IndexStatus.AlreadyExisted      => "already exists"
              case IndexStatus.CreatedNonUnique(w) => s"created without unique ($w)"
              case IndexStatus.Failed(reason)      => s"FAILED: $reason"
            }
            println(f"  index ${r.spec.name}%-48s $status")
          }
        } finally ctx.close()
    }
  }

  /** Maps one CSV row to a restaurant, or `None` when a numeric column is unreadable. */
  private def toRestaurant(at: String => String): Option[IndiaRestaurant] =
    for {
      id     <- at("Restaurant ID").toLongOption
      cost   <- at("Average Cost for two").toIntOption
      price  <- at("Price range").toIntOption
      rating <- at("Aggregate rating").toDoubleOption
      votes  <- at("Votes").toIntOption
    } yield {
      val lon = at("Longitude").toDoubleOption.getOrElse(0.0)
      val lat = at("Latitude").toDoubleOption.getOrElse(0.0)
      IndiaRestaurant(
        objectId = None,
        restaurantId = id,
        name = at("Restaurant Name"),
        city = at("City"),
        locality = at("Locality"),
        address = at("Address"),
        cuisines = at("Cuisines").split(",").toList.map(_.trim).filter(_.nonEmpty),
        costForTwo = cost,
        priceRange = price,
        rating = rating,
        votes = votes,
        hasTableBooking = at("Has Table booking").equalsIgnoreCase("Yes"),
        hasOnlineDelivery = at("Has Online delivery").equalsIgnoreCase("Yes"),
        coord = Option.when(lon != 0.0 || lat != 0.0)(Coordinates(lon, lat))
      )
    }

  private def readText(path: Path): String = {
    val bytes = Files.readAllBytes(path)
    val strictUtf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    try strictUtf8.decode(ByteBuffer.wrap(bytes)).toString
    catch { case _: CharacterCodingException => new String(bytes, "windows-1252") }
  }

  /** RFC 4180 CSV: quoted fields, escaped quotes ("") and newlines inside quotes. */
  def parseCsv(text: String): List[Vector[String]] = {
    val rows   = ListBuffer.empty[Vector[String]]
    val row    = ListBuffer.empty[String]
    val field  = new StringBuilder
    var quoted = false
    var i      = 0
    while (i < text.length) {
      val ch = text.charAt(i)
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < text.length && text.charAt(i + 1) == '"') { field += '"'; i += 1 }
          else quoted = false
        } else field += ch
      } else
        ch match {
          case '"'  => quoted = true
          case ','  => row += field.toString; field.clear()
          case '\r' => ()
          case '\n' => row += field.toString; field.clear(); rows += row.toVector; row.clear()
          case c    => field += c
        }
      i += 1
    }
    if (field.nonEmpty || row.nonEmpty) { row += field.toString; rows += row.toVector }
    rows.toList
  }
}
