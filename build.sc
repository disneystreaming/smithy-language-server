import $ivy.`io.chris-kipp::mill-ci-release::0.1.10`
import mill._
import scalalib._
import mill.scalalib.publish._
import scala.util.Try
import io.kipp.mill.ci.release.CiReleaseModule
import io.kipp.mill.ci.release.SonatypeHost

object lsp extends MavenModule with CiReleaseModule {

  override def sonatypeHost = Some(SonatypeHost.s01)

  def millSourcePath: os.Path = os.pwd

  def ivyDeps = Agg(
    ivy"org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1",
    ivy"software.amazon.smithy:smithy-build:1.50.0",
    ivy"software.amazon.smithy:smithy-cli:1.50.0",
    ivy"software.amazon.smithy:smithy-model:1.50.0",
    ivy"software.amazon.smithy:smithy-syntax:1.50.0"
  )

  def javacOptions = T {
    super.javacOptions() ++ Seq(
      "-source",
      "21",
      "-target",
      "21"
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

  def writeVersion: T[PathRef] = T {
    val version = publishVersion()
    val targetDir = T.ctx().dest / "resources"

    os.makeDir.all(targetDir)
    os.write(targetDir / "version.properties", s"version=$version")
    PathRef(targetDir)
  }

  override def localClasspath = super.localClasspath() :+ writeVersion()

}
