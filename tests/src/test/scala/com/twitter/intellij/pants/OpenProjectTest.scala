package com.twitter.intellij.pants

import com.twitter.intellij.pants.OpenProjectTestFixture.TestData
import com.twitter.intellij.pants.protocol.PythonFacet
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.{Assert, Test}
import org.virtuslab.ideprobe.junit4.RunningIntelliJPerSuite
import org.virtuslab.ideprobe.protocol.{FileRef, ModuleRef, ProjectRef, RunFixesSpec, SourceFolder, VcsRoot}
import org.virtuslab.ideprobe.scala.ScalaPluginExtension
import org.virtuslab.ideprobe.{ConfigFormat, RunningIntelliJFixture, Shell}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

// Actual test classes:
class OpenProjectTestPants extends CommonOpenProjectTests {
  override def intelliJ: RunningIntelliJFixture = OpenProjectTestPants.intelliJ

  // TODO see how can it be supported with BSP
  @Test def hasPythonFacetsSetup(): Unit = {
    def checkFacetsForModule(module: ModuleRef, facets: Seq[PythonFacet]): Unit = {
      Assert.assertTrue(s"Unexpected python facets $facets for $module", facets.size == 1)
      val Seq(facet) = facets
      Assert.assertTrue(s"No sdk in python facet $facet for $module", facet.sdk.isDefined)
      val Some(sdk) = facet.sdk
      Assert.assertTrue(s"Sdk does not have home path: $sdk for $module", sdk.homePath.nonEmpty)
      Assert.assertEquals(s"Incorrect sdk type for $module", "Python SDK", sdk.typeId)
    }

    val pantsDriver = PantsProbeDriver(intelliJ.probe)

    // check if python modules have python sdk set up
    val pythonModules = intelliJ.config[Set[String]]("project.pythonModules")

    intelliJ.probe.projectModel().modulesByNames(pythonModules).map(_.toRef()).foreach { module =>
      val facets = pantsDriver.getPythonFacets(module)
      checkFacetsForModule(module, facets)
    }

    // check if after running python facet inspection *all* modules have python sdk setup
    intelliJ.probe.runLocalInspection(
      "com.twitter.intellij.pants.inspection.PythonFacetInspection",
      FileRef(intelliJ.workspace.resolve(intelliJ.config[Path]("project.buildFile"))),
      RunFixesSpec.All
    )

    intelliJ.probe.projectModel().moduleRefs.foreach { ref =>
      val facets = pantsDriver.getPythonFacets(ref)
      checkFacetsForModule(ref, facets)
    }
  }

}

trait OpenProjectTestBspBase extends CommonOpenProjectTests {

  // TODO add to common OpenProjectTest when it works with pants
  @Test def hasGitRepositoryRootDetected(): Unit = {
    val actualVcsRoots = intelliJ.probe.vcsRoots()
    val expectedRoot = VcsRoot("Git", intelliJ.workspace.toRealPath())
    assertEquals(Seq(expectedRoot), actualVcsRoots)
  }
}

class OpenProjectTestFastpassWithCmdLine extends OpenProjectTestBspBase {
  override def intelliJ: RunningIntelliJFixture = OpenProjectTestFastpassWithCmdLine.intelliJ
}

abstract class CommonOpenProjectTests {

  def intelliJ: RunningIntelliJFixture

  @Test def hasExpectedName(): Unit = {
    val expectedProject = intelliJ.config[String]("project.name")
    val projectModel = intelliJ.probe.projectModel()
    Assert.assertEquals(expectedProject, projectModel.name)
  }

  @Test def hasExpectedModules(): Unit = {
    def relative(absolutePath: Path): Path = intelliJ.workspace.toRealPath().relativize(absolutePath)

    val projectModel = intelliJ.probe.projectModel()

    val expectedModules = intelliJ.config[Seq[TestData.Module]]("project.modules")
    val importedModules = projectModel.modules.map { module =>
      TestData.Module(module.name, module.contentRoots.all.map(sf => TestData.SourceRoot(relative(sf.path), sf.kind, sf.packagePrefix)))
    }

    Assert.assertTrue(
      s"Expected modules: $expectedModules, actual modules: $importedModules (not a subset)",
      expectedModules.toSet.subsetOf(importedModules.toSet))
  }

  @Test def hasProjectSdkSet(): Unit = {
    val projectSdk = intelliJ.probe.projectSdk()
    Assert.assertTrue(s"Project without sdk", projectSdk.isDefined)
  }

  @Test def hasModuleSdksSet(): Unit = {
    val project = intelliJ.probe.projectModel()
    val expectedModulesWithSdk = intelliJ.config[Seq[TestData.Module]]("project.modules").map(_.name)

    val modulesWithoutSdk = project.modules
      .filter(module => module.kind.isDefined && expectedModulesWithSdk.contains(module.name))
      .map(m => ModuleRef(m.name))
      .filter(module => intelliJ.probe.moduleSdk(module).isEmpty)

    Assert.assertTrue(s"Modules without sdk: $modulesWithoutSdk", modulesWithoutSdk.isEmpty)
  }

}

object OpenProjectTestFixture extends ConfigFormat {
  object TestData {
    case class SourceRoot(path: Path, kind: SourceFolder.Kind, packagePrefix: Option[String])
    case class Module(name: String, sourceRoots: Set[SourceRoot])
    implicit val contentRootReader: ConfigReader[SourceRoot] = deriveReader[SourceRoot]
    implicit val moduleReader: ConfigReader[Module] = deriveReader[Module]
  }
}

// common fixtures configuration:

// Because RunningIntellijPerSuite uses @BeforeClass, which must be static, this trait must be
// mixed in to an companion object of actual test class
trait OpenProjectTestFixture extends PantsTestSuite with RunningIntelliJPerSuite with ConfigFormat {
  override protected def baseFixture = fixtureFromConfig()

  override def beforeAll(): Unit = {
    Shell.run(in = intelliJ.workspace, "git", "init")
    openProject()
  }

  def openProject(): ProjectRef
}

object OpenProjectTestPants extends OpenProjectTestFixture {
  override def openProject(): ProjectRef = openProjectWithPants(intelliJ)
}

object OpenProjectTestFastpassWithCmdLine extends OpenProjectTestFixture {
  override def openProject(): ProjectRef = openProjectWithBsp(intelliJ)
}

object OpenProjectTestFastpassWithWizard extends OpenProjectTestFixture with ScalaPluginExtension {
  private def targetsFromConfig(intelliJ: RunningIntelliJFixture): Seq[String] =  {
    intelliJ.config[Seq[String]]("pants.import.targets")
  }

  override def openProject(): ProjectRef = {
    val path =  intelliJ.workspace.resolve(targetsFromConfig(intelliJ).head.stripSuffix("::"))
    val project = intelliJ.probe.importBspProject(path)
    project
  }
}

class OpenProjectTestFastpassWithWizard extends OpenProjectTestBspBase {
  override def intelliJ: RunningIntelliJFixture = OpenProjectTestFastpassWithWizard.intelliJ
}
