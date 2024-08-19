import mill._
import scalalib._
import mill.scalalib.publish._
import scala.util.Try

object lsp extends MavenModule with PublishModule {

  def millSourcePath: os.Path = os.pwd

  def ivyDeps = Agg(
    ivy"org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1",
    ivy"software.amazon.smithy:smithy-build:1.50.0",
    ivy"software.amazon.smithy:smithy-cli:1.50.0",
    ivy"software.amazon.smithy:smithy-model:1.50.0",
    ivy"software.amazon.smithy:smithy-syntax:1.50.0"
  )

  def publishVersion = T { gitVersion() }

  def javacOptions = T {
    super.javacOptions() ++ Seq(
      "-source",
      "1.8",
      "-target",
      "1.8"
    )
  }

  override def javadocOptions: T[Seq[String]] = T {
    super.javacOptions() ++ Seq("-Xdoclint:none")
  }

  override def artifactName = s"smithy-language-server"

  def pomSettings = PomSettings(
    description = "LSP implementation for smithy",
    organization = "com.disneystreaming.smithy",
    url = "https://github.com/disneystreaming/smithy-language-server",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl(
      Some("https://github.com/disneystreaming/smithy-language-server")
    ),
    Seq(Developer("baccata", "Olivier Mélois", "https://github.com/baccata"))
  )

  def gitVersion: T[String] = T.input {

    val gitDirty =
      os.proc("git", "diff", "HEAD").call().out.lines.nonEmpty

    if (gitDirty) sys.error("Dirty workspace !")

    val commitHash =
      os.proc("git", "rev-parse", "--short", "HEAD").call().out.lines.head.trim

    val describeResult = os
      .proc(
        "git",
        "describe",
        "--long",
        "--tags",
        "--abbrev=8",
        "--match",
        "v[0-9]*",
        "--always",
        "--dirty=+dirty"
      )
      .call()
      .out
      .lines
      .lastOption
      .map(_.replaceAll("-([0-9]+)-g([0-9a-f]{8})", "+$1-$2"))
      .getOrElse(s"v0.0.0-$commitHash")
    parseVersion(describeResult)
  }

  def mainBranch: T[String] = "dss"

  def latestTag: T[Option[String]] = T {
    val branch = mainBranch()
    os.proc("git", "describe", branch, "--abbrev=0", "--tags")
      .call()
      .out
      .lines
      .headOption
  }

  object int {
    def unapply(s: String) = s.toIntOption
  }

  object distanceToTag {
    def unapply(s: String): Option[Int] = {
      Option(s).flatMap(_.toIntOption).orElse(Some(0))
    }
  }

  object sha {
    private val shaRegex = """([0-9a-f]{8})""".r
    def unapply(s: String): Option[String] =
      if (shaRegex.pattern.matcher(s).matches()) Some(s) else None
  }

  object nonCommittedCode {
    def unapply(s: String): Option[Boolean] = Some(Option(s).isDefined)
  }

  def parseVersion(s: String): String = s.trim match {
    case s"v$tag+${int(dist)}-${sha(s)}+dirty" =>
      version(Some(tag), dist, s, dirty = true)
    case s"v$tag+${int(dist)}-${sha(s)}" =>
      version(Some(tag), dist, s, dirty = false)
    case s"${sha(s)}+dirty" =>
      version(None, 0, s, dirty = true)
    case s"${sha(s)}" =>
      version(None, 0, s, dirty = false)
    case s"v$tag" =>
      version(Some(tag), 0, s, dirty = false)
  }

  def version(
      maybeTag: Option[String],
      distance: Int,
      sha: String,
      dirty: Boolean
  ): String = {
    val untagged = if (sha.isEmpty) "" else s"+$distance-$sha"
    val tagged = if (distance > 0) s"+$distance-$sha" else ""
    val version = maybeTag.fold(s"0.0.0$untagged")(tag => s"$tag$tagged")
    val dirtySuffix = if (dirty) "-SNAPSHOT" else ""
    s"$version$dirtySuffix"
  }

  def writeVersion: T[PathRef] = T {
    val version = publishVersion()
    val targetDir = T.ctx().dest / "resources"

    os.makeDir.all(targetDir)
    os.write(targetDir / "version.properties", s"version=$version")
    PathRef(targetDir)
  }

  override def localClasspath = super.localClasspath() :+ writeVersion()

}
