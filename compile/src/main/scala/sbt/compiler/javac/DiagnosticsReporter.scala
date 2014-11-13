package sbt.compiler.javac

import java.io.File
import javax.tools.{ Diagnostic, JavaFileObject, DiagnosticListener }

import sbt.Logger
import xsbti.{ Severity, Reporter }

/**
 * A diagnostics listener that feeds all messages into the given reporter.
 * @param reporter
 */
final class DiagnosticsReporter(reporter: Reporter) extends DiagnosticListener[JavaFileObject] {
  val END_OF_LINE_MATCHER = "(\r\n)|[\r]|[\n]"
  val EOL = System.getProperty("line.separator")
  private def fixedDiagnosticMessage(d: Diagnostic[_ <: JavaFileObject]): String = {
    def getRawMessage = d.getMessage(null)
    def fixWarnOrErrorMessage = {
      val tmp = getRawMessage
      // we fragment off the line/source/type report from the message.
      // NOTE - End of line handling may be off.
      val lines: Seq[String] =
        tmp.split(END_OF_LINE_MATCHER) match {
          case Array(head, tail @ _*) =>
            val newHead = head.split(":").last
            newHead +: tail
          case Array(head) =>
            head.split(":").last :: Nil
          case Array() => Seq.empty[String]
        }
      lines.mkString(EOL)
    }
    d.getKind match {
      case Diagnostic.Kind.ERROR | Diagnostic.Kind.WARNING | Diagnostic.Kind.MANDATORY_WARNING => fixWarnOrErrorMessage
      case _ => getRawMessage
    }
  }
  private def fixSource[T <: JavaFileObject](source: T): Option[String] = {
    try Option(source).map(_.toUri.normalize).map(new File(_)).map(_.getAbsolutePath)
    catch {
      case t: IllegalArgumentException =>
        // Oracle JDK6 has a super dumb notion of what a URI is.  In fact, it's not even a legimitate URL, but a dump
        // of the filename in a "I hope this works to toString it" kind of way.  This appears to work in practice
        // but we may need to re-evaluate.
        Option(source).map(_.toUri.toString)
    }
  }
  override def report(d: Diagnostic[_ <: JavaFileObject]) {
    val severity =
      d.getKind match {
        case Diagnostic.Kind.ERROR => Severity.Error
        case Diagnostic.Kind.WARNING | Diagnostic.Kind.MANDATORY_WARNING => Severity.Warn
        case _ => Severity.Info
      }
    val msg = fixedDiagnosticMessage(d)
    val pos: xsbti.Position =
      new xsbti.Position {
        override val line =
          Logger.o2m(if (d.getLineNumber == -1) None
          else Option(new Integer(d.getLineNumber.toInt)))
        override def lineContent = {
          // TODO - Is this pulling contents of the line correctly?
          // Would be ok to just return null if this version of the JDK doesn't support grabbing
          // source lines?
          Option(d.getSource).
            flatMap(s => Option(s.getCharContent(true))).
            map(_.subSequence(d.getStartPosition.intValue, d.getEndPosition.intValue).toString).
            getOrElse("")
        }
        override val offset = Logger.o2m(Option(Integer.valueOf(d.getPosition.toInt)))
        private val sourceUri = fixSource(d.getSource)
        override val sourcePath = Logger.o2m(sourceUri)
        override val sourceFile = Logger.o2m(sourceUri.map(new File(_)))
        override val pointer = Logger.o2m(Option.empty[Integer])
        override val pointerSpace = Logger.o2m(Option.empty[String])
        override def toString =
          if (sourceUri.isDefined) s"${sourceUri.get}:${if (line.isDefined) line.get else -1}"
          else ""
      }
    reporter.log(pos, msg, severity)
  }
}