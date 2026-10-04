package restaurants.config

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Immutable runtime configuration.
  *
  * Values are read from real environment variables first and then from an
  * optional `.env` file in the working directory, so the same build works on a
  * laptop (with `.env`) and on Render (with dashboard environment variables).
  *
  * @param collection the restaurants collection imported from Zomato (`india_restaurants`)
  */
final case class AppConfig(mongoUri: String, database: String, collection: String) {

  /** Just the cluster host (no user, password or options), e.g. `cluster0.abcde.mongodb.net`. */
  def host: String =
    mongoUri.replaceFirst("^mongodb(\\+srv)?://", "").split("@").last.takeWhile(c => c != '/' && c != '?')
}

object AppConfig {

  val DefaultDatabase   = "sample_restaurants"
  val DefaultCollection = "india_restaurants"
  val DefaultPort       = 8080

  private lazy val dotEnv: Map[String, String] = readDotEnv(Paths.get(".env"))

  /** Looks a key up in the environment, falling back to `.env`; blank values count as missing. */
  def lookup(key: String): Option[String] =
    sys.env.get(key).orElse(dotEnv.get(key)).map(_.trim).filter(_.nonEmpty)

  /** HTTP port for the web server (Render injects `PORT`). */
  def port: Int = lookup("PORT").flatMap(_.toIntOption).filter(p => p > 0 && p < 65536).getOrElse(DefaultPort)

  def load(): Either[String, AppConfig] =
    lookup("MONGODB_URI") match {
      case None =>
        Left("MONGODB_URI is not set. Copy .env.example to .env and paste your Atlas connection string.")
      case Some(uri) if !(uri.startsWith("mongodb://") || uri.startsWith("mongodb+srv://")) =>
        Left("MONGODB_URI must start with mongodb:// or mongodb+srv://")
      case Some(uri) if uri.contains("<db_password>") || uri.contains("<password>") =>
        Left("MONGODB_URI still contains the <db_password> placeholder. Replace it with the real password.")
      case Some(uri) =>
        Right(
          AppConfig(
            mongoUri = uri,
            database = lookup("MONGODB_DB").getOrElse(DefaultDatabase),
            collection = lookup("MONGODB_COLLECTION").getOrElse(DefaultCollection)
          )
        )
    }

  private def readDotEnv(path: Path): Map[String, String] =
    if (!Files.isRegularFile(path)) Map.empty
    else
      Try(Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList)
        .getOrElse(Nil)
        .map(_.replace("﻿", "").trim)
        .filterNot(line => line.isEmpty || line.startsWith("#"))
        .flatMap { line =>
          line.stripPrefix("export ").split("=", 2) match {
            case Array(key, value) => Some(key.trim -> unquote(value.trim))
            case _                 => None
          }
        }
        .toMap

  private def unquote(value: String): String =
    if (value.length >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))))
      value.substring(1, value.length - 1)
    else value
}
