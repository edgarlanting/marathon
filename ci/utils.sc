// A collection of pipeline utilities such as stage names and colors.

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.Try
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import $file.provision

implicit val SemVerRead: scopt.Read[SemVer] =
  scopt.Read.reads(SemVer(_))

val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private def findMarathonRoot(path: Path): Path = {
  val utilsPath = path / "ci" / "utils.sc"
  if (utilsPath == root)
    throw new RuntimeException("Cannot find Marathon root path; pwd should be the Marathon checkout root, or a sub-folder")
  if (utilsPath.toIO.exists)
    path
  else
    findMarathonRoot(path / up)
}

val marathonRoot = findMarathonRoot(pwd)

def ciLogFile(name: String): File = {
  val log = new File(name)
  if (!log.exists())
    log.createNewFile()
  log
}

// Color definitions
object Colors {
  val BrightRed = "\u001b[31;1m"
  val BrightGreen = "\u001b[32;1m"
  val BrightBlue = "\u001b[34;1m"
  val Reset = "\u001b[0m"
}

def printWithColor(text: String, color: String): Unit = {
  print(color)
  print(text)
  print(Colors.Reset)
}

def printlnWithColor(text: String, color: String): Unit = printWithColor(s"$text\n", color)

def printHr(color: String, character: String = "*", length: Int = 80): Unit = {
  printWithColor(s"${character * length}\n", color)
}

def printCurrentTime() = {
  val date = LocalDateTime.now()
  printWithColor(s"Started at: ${date.format(timeFormatter)}\n", Colors.BrightBlue)
}

def printStageTitle(name: String): Unit = {
  val indent = (80 - name.length) / 2
  print("\n")
  print(" " * indent)
  printWithColor(s"$name\n", Colors.BrightBlue)
  printHr(Colors.BrightBlue)
  printCurrentTime()
}

case class BuildException(val cmd: String, val exitValue: Int, private val cause: Throwable = None.orNull)
    extends Exception(s"'$cmd' exited with $exitValue", cause)
case class StageException(private val message: String = "", private val cause: Throwable = None.orNull) extends Exception(message, cause)
def stage[T](name: String)(block: => T): T = {
  printStageTitle(name)

  try {
    block
  } catch {
    case NonFatal(e) =>
      throw new StageException(s"Stage $name failed.", e)
  }
}

/**
  * Run a process with given commands and time out it runs too long.
  *
 * @param timeout The maximum time to wait.
  * @param logFileName Name of file which collects all logs.
  * @param commands The commands that are executed in a process. E.g. "sbt",
  *  "compile".
  */
def runWithTimeout(timeout: FiniteDuration, logFileName: String, workDirectory: Path = pwd)(commands: Seq[String]): Unit = {

  val builder = new java.lang.ProcessBuilder()
  val buildProcess = builder
    .directory(new java.io.File(workDirectory.toString))
    .command(commands.asJava)
    .inheritIO()
    .redirectOutput(ProcessBuilder.Redirect.appendTo(ciLogFile(logFileName)))
    .start()

  val exited = buildProcess.waitFor(timeout.length, timeout.unit)

  if (exited) {
    val exitValue = buildProcess.exitValue
    if (buildProcess.exitValue != 0) {
      val cmd = commands.mkString(" ")
      throw new utils.BuildException(cmd, exitValue)
    }
  } else {
    // The process timed out. Try to kill it.
    buildProcess.destroyForcibly().waitFor()
    val cmd = commands.mkString(" ")
    throw new java.util.concurrent.TimeoutException(s"'$cmd' timed out after $timeout.")
  }
}

/**
  * Returns jenkins job name from environment variable
  */
def getJobName(): Option[String] = sys.env.get("JOB_NAME")

// The name of the build loop.
lazy val loopName: String = {
  val loopNamePattern = """marathon-sandbox/(.*)""".r
  sys.env
    .get("JOB_NAME")
    .collect { case loopNamePattern(name) => name }
    .getOrElse("loop")
}

/**
  * @return Name for build loops.
  */
def loopBuildName(): String = {
  val buildNumber = sys.env.get("BUILD_NUMBER").getOrElse("0")
  s"$loopName-$buildNumber"
}

def priorPatchVersion(tag: String): Option[String] = {
  val Array(major, minor, patch) = tag.replace("v", "").split('.').take(3).map(_.toInt)
  if (patch == 0)
    None
  else
    Some(s"v${major}.${minor}.${patch - 1}")
}

def escapeCmdArg(cmd: String): String = {
  val subbed = cmd.replace("'", "\\'").replace("\n", "\\n")
  s"""$$'${subbed}'"""
}

case class SemVer(major: Int, minor: Int, build: Int, commit: String) {
  override def toString(): String = s"$major.$minor.$build-$commit"
  def toTagString(): String = s"v$major.$minor.$build"

  /**
    * Release bucket keys are not prefixed.
    */
  def toReleaseString(): String = s"$major.$minor.$build"
}

object BranchType {

  sealed trait Branch
  object Master extends Branch
  case class PR(id: String) extends Branch
  case class Release(version: String) extends Branch

  val pr = """marathon-pipelines/PR-(\d+)""".r
  val release = """marathon-pipelines/releases%2F([\d\.]+)""".r

  def apply(jobName: Option[String]): Option[Branch] = {
    jobName match {
      case Some(jn) if jn.contains("marathon-pipelines/master") => Some(Master)
      case Some(pr(pullId)) => Some(PR(pullId))
      case Some(release(version)) => Some(Release(version))
      case _ => None
    }
  }
}

object SemVer {
  val empty = SemVer(0, 0, 0, "")

  // Matches e.g. 1.7.42
  val versionPattern = """^(\d+)\.(\d+)\.(\d+)$""".r

  /**
    * Create SemVer from string which has the form if 1.7.42 and the commit.
    */
  def apply(version: String, commit: String): SemVer =
    version match {
      case versionPattern(major, minor, build) =>
        SemVer(major.toInt, minor.toInt, build.toInt, commit)
      case _ =>
        throw new IllegalArgumentException(s"Could not parse version $version.")
    }

  // Matches e.g. 1.7.42-deadbeef
  val versionCommitPattern = """^(\d+)\.(\d+)\.(\d+)-(\w+)$""".r

  // Matches e.g. v1.7.42
  val versionTagPattern = """^v?(\d+)\.(\d+)\.(\d+)$""".r

  /**
    * Create SemVer from string which has the form if 1.7.42-deadbeef.
    */
  def apply(version: String): SemVer =
    version match {
      case versionCommitPattern(major, minor, build, commit) =>
        SemVer(major.toInt, minor.toInt, build.toInt, commit)
      case versionTagPattern(major, minor, build) =>
        SemVer(major.toInt, minor.toInt, build.toInt, "")
      case _ =>
        throw new IllegalArgumentException(s"Could not parse version $version.")
    }

}
