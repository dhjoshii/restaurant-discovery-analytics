package restaurants.cli

import java.io.{FileDescriptor, FileOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import scala.util.Try

/** Signals that standard input was closed (Ctrl+D / Ctrl+Z / end of a piped script). */
final class InputClosed extends RuntimeException("Input closed")

/** Terminal helpers: colours, tables, bars and validated prompts.
  *
  * Colour can be disabled with `--no-color` or the `NO_COLOR` environment
  * variable; box-drawing characters can be disabled with `--ascii`.
  */
object Term {

  private var colorEnabled   = true
  private var unicodeEnabled = true
  private var echoInput      = false

  def configure(args: Seq[String]): Unit = {
    colorEnabled = !args.contains("--no-color") && sys.env.get("NO_COLOR").isEmpty
    unicodeEnabled = !args.contains("--ascii")
    echoInput = args.contains("--echo-input") // prints piped answers so scripted runs read like a real session
  }

  /** Makes UTF-8 box drawing render correctly, including in the classic Windows console.
    * Returns a UTF-8 stream to be installed with `Console.withOut`.
    */
  def setupConsole(): PrintStream = {
    val windows = System.getProperty("os.name", "").toLowerCase.contains("win")
    // Only for a real interactive console: with piped input the child process would consume stdin.
    if (windows && unicodeEnabled && System.console() != null)
      Try(
        new ProcessBuilder("cmd", "/c", "chcp", "65001")
          .redirectInput(ProcessBuilder.Redirect.INHERIT) // keep the console attached to the child
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
          .waitFor()
      )
    val utf8 = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8)
    System.setOut(utf8)
    utf8
  }

  // ------------------------------------------------------------------ colour

  private def rgb(r: Int, g: Int, b: Int)(s: String): String =
    if (colorEnabled) s"\u001b[38;2;$r;$g;${b}m$s\u001b[0m" else s

  // Spice-market palette: cardamom green, turmeric, chilli, rose, ash
  def jade(s: String): String    = rgb(146, 196, 92)(s)
  def saffron(s: String): String = rgb(244, 176, 46)(s)
  def coral(s: String): String   = rgb(238, 98, 66)(s)
  def sky(s: String): String     = rgb(240, 168, 150)(s)
  def muted(s: String): String   = rgb(150, 138, 124)(s)

  /** Colours a 0–5 rating (higher is better) the way Zomato bands it. */
  def ratingColor(rating: Double, text: String): String =
    if (rating >= 4.0) jade(bold(text)) else if (rating >= 3.5) jade(text) else if (rating >= 2.5) saffron(text) else coral(text)
  def bold(s: String): String    = if (colorEnabled) s"\u001b[1m$s\u001b[22m" else s

  def gradeColor(grade: String): String = grade match {
    case "A" => jade(bold(grade))
    case "B" => saffron(bold(grade))
    case "C" => coral(bold(grade))
    case ""  => muted("-")
    case g   => muted(g)
  }

  private val AnsiPattern = "\u001b\\[[0-9;]*m".r

  def visibleLength(s: String): Int = AnsiPattern.replaceAllIn(s, "").length

  // ---------------------------------------------------------------- printing

  def line(s: String = ""): Unit = println(s)

  def success(s: String): Unit = println(s"  ${jade(if (unicodeEnabled) "✔" else "OK")} $s")
  def warn(s: String): Unit    = println(s"  ${saffron(if (unicodeEnabled) "!" else "!")} $s")
  def error(s: String): Unit   = println(s"  ${coral(if (unicodeEnabled) "✖" else "X")} ${coral(s)}")
  def info(s: String): Unit    = println(s"  ${sky(if (unicodeEnabled) "›" else ">")} $s")

  def errors(list: List[String]): Unit = list.foreach(error)

  def heading(title: String, subtitle: String = ""): Unit = {
    val rule = (if (unicodeEnabled) "─" else "-") * math.max(8, 58 - title.length)
    println()
    println(s"  ${saffron(bold(title.toUpperCase))} ${muted(rule)}")
    if (subtitle.nonEmpty) println(s"  ${muted(subtitle)}")
  }

  def menu(options: List[(String, String)]): Unit =
    options.foreach { case (key, label) => println(s"   ${saffron(bold(key.padTo(2, ' ')))} $label") }

  /** A horizontal bar for a value relative to `max`, with eighth-block precision. */
  def bar(value: Double, max: Double, width: Int = 28): String =
    if (max <= 0) ""
    else {
      val cells = math.max(0.0, math.min(1.0, value / max)) * width
      val full  = cells.toInt
      if (!unicodeEnabled) jade("#" * full)
      else {
        val partials = Vector("", "▏", "▎", "▍", "▌", "▋", "▊", "▉")
        val partial  = partials(((cells - full) * 8).toInt.min(7))
        jade("█" * full + partial)
      }
    }

  /** Renders a boxed table. `rightAligned` holds the indexes of numeric columns. */
  def table(headers: List[String], rows: List[List[String]], rightAligned: Set[Int] = Set.empty, maxWidth: Int = 34): String = {
    def clip(s: String): String =
      if (visibleLength(s) <= maxWidth) s
      else if (visibleLength(s) == s.length) s.take(maxWidth - 1) + (if (unicodeEnabled) "…" else "~")
      else s // coloured cells are always short
    val clipped = rows.map(_.map(clip))
    val widths = headers.indices.map { i =>
      (headers(i).length :: clipped.map(r => visibleLength(r.lift(i).getOrElse("")))).max
    }
    def pad(s: String, i: Int): String = {
      val gap = " " * (widths(i) - visibleLength(s))
      if (rightAligned(i)) gap + s else s + gap
    }
    val (h, v, tl, tm, tr, ml, mm, mr, bl, bm, br) =
      if (unicodeEnabled) ("─", "│", "╭", "┬", "╮", "├", "┼", "┤", "╰", "┴", "╯")
      else ("-", "|", "+", "+", "+", "+", "+", "+", "+", "+", "+")
    def rule(l: String, m: String, r: String) = muted("  " + l + widths.map(w => h * (w + 2)).mkString(m) + r)
    val head = "  " + muted(v) + headers.indices.map(i => " " + bold(pad(headers(i), i)) + " ").mkString(muted(v)) + muted(v)
    val body = clipped.map(r => "  " + muted(v) + headers.indices.map(i => " " + pad(r.lift(i).getOrElse(""), i) + " ").mkString(muted(v)) + muted(v))
    (List(rule(tl, tm, tr), head, rule(ml, mm, mr)) ++ body ++ List(rule(bl, bm, br))).mkString("\n")
  }

  def keyValues(pairs: List[(String, String)]): Unit = {
    val width = pairs.map(_._1.length).maxOption.getOrElse(0)
    pairs.foreach { case (k, v) => println(s"   ${muted(k.padTo(width, ' '))}  $v") }
  }

  // ------------------------------------------------------------------- input

  def ask(prompt: String): String = {
    print(s"  ${sky(if (unicodeEnabled) "›" else ">")} $prompt")
    Console.flush()
    val input = scala.io.StdIn.readLine()
    if (input == null) throw new InputClosed
    if (echoInput) println(bold(input))
    input.trim
  }

  /** Prompts with a default shown in brackets; pressing Enter keeps the default. */
  def askWithDefault(prompt: String, default: String): String = {
    val hint = if (default.nonEmpty) muted(s" [$default]") else ""
    val answer = ask(s"$prompt$hint: ")
    if (answer.isEmpty) default else answer
  }

  /** Blank → `None`; anything else must be an integer within the bounds. */
  def askOptionalInt(prompt: String, min: Int, max: Int): Option[Int] = {
    val raw = ask(prompt)
    if (raw.isEmpty) None
    else
      raw.toIntOption match {
        case Some(n) if n >= min && n <= max => Some(n)
        case _ =>
          error(s"Please enter a whole number between $min and $max (or press Enter to skip).")
          askOptionalInt(prompt, min, max)
      }
  }

  def confirm(prompt: String): Boolean =
    ask(s"$prompt ${muted("(y/N)")}: ").toLowerCase match {
      case "y" | "yes" => true
      case _           => false
    }

  def pause(): Unit = { ask(muted("Press Enter to continue…")); () }
}
