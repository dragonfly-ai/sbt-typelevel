/*
 * Copyright 2022 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.typelevel.sbt.mergify

import org.typelevel.sbt.gha._
import sbt._

import java.nio.file.Path

import Keys._

object MergifyPlugin extends AutoPlugin {

  object autoImport {
    lazy val mergifyGenerate = taskKey[Unit](
      "Generates (and overwrites if extant) a .mergify.yml according to configuration")
    lazy val mergifyCheck = taskKey[Unit](
      "Checks to see if the .mergify.yml files are equivalent to what would be generated and errors if otherwise")

    lazy val mergifyPrRules = settingKey[Seq[MergifyPrRule]]("The mergify pull request rules")

    lazy val mergifyStewardConfig = settingKey[Option[MergifyStewardConfig]](
      "Config for the automerge rule for Scala Steward PRs, set to None to disable.")

    lazy val mergifyRequiredJobs =
      settingKey[Seq[String]]("Ids for jobs that must succeed for merging (default: [build])")

    lazy val mergifySuccessConditions = settingKey[Seq[MergifyCondition]](
      "Success conditions for merging (default: auto-generated from `mergifyRequiredJobs` setting)")

    lazy val mergifyLabelPaths = settingKey[Map[String, File]](
      "A map from label to file path (default: auto-populated for every subproject in your build)")

    type MergifyAction = org.typelevel.sbt.mergify.MergifyAction
    val MergifyAction = org.typelevel.sbt.mergify.MergifyAction
    type MergifyCondition = org.typelevel.sbt.mergify.MergifyCondition
    val MergifyCondition = org.typelevel.sbt.mergify.MergifyCondition
    type MergifyPrRule = org.typelevel.sbt.mergify.MergifyPrRule
    val MergifyPrRule = org.typelevel.sbt.mergify.MergifyPrRule
    type MergifyStewardConfig = org.typelevel.sbt.mergify.MergifyStewardConfig
    val MergifyStewardConfig = org.typelevel.sbt.mergify.MergifyStewardConfig
  }

  override def requires = GenerativePlugin
  override def trigger: PluginTrigger = allRequirements

  import autoImport._
  import GenerativePlugin.autoImport._

  override def buildSettings: Seq[Setting[_]] = Seq(
    mergifyStewardConfig := Some(MergifyStewardConfig()),
    mergifyRequiredJobs := Seq("build"),
    mergifyLabelPaths := Map.empty,
    mergifySuccessConditions := jobSuccessConditions.value,
    mergifyPrRules := {
      val baseDir = (LocalRootProject / baseDirectory).value.toPath
      val stewardRule =
        mergifyStewardConfig.value.map(_.toPrRule(mergifySuccessConditions.value.toList)).toList
      val labelRules =
        mergifyLabelPaths.value.toList.sorted.map {
          case (label, file) =>
            val relPath = baseDir.relativize(file.toPath.toAbsolutePath.normalize)
            val suffix = if (file.isDirectory) "/" else ""
            MergifyPrRule(
              s"Label ${label} PRs",
              List(MergifyCondition.Custom(s"files~=^${relPath}${suffix}")),
              List(MergifyAction.Label(add = List(label)))
            )
        }
      stewardRule ++ labelRules
    },
    mergifyGenerate := {
      IO.write(mergifyYaml.value, generateMergifyContents.value)
    },
    mergifyCheck := {
      val log = state.value.log

      def reportMismatch(file: File, expected: String, actual: String): Unit = {
        log.error(s"Expected:\n$expected")
        log.error(s"Actual:\n${GenerativePlugin.diff(expected, actual)}")
        sys.error(
          s"${file.getName} does not contain contents that would have been generated by sbt-typelevel-mergify; try running mergifyGenerate")
      }

      def compare(file: File, expected: String): Unit = {
        val actual = IO.read(file)
        if (expected != actual) {
          reportMismatch(file, expected, actual)
        }
      }

      compare(mergifyYaml.value, generateMergifyContents.value)
    }
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    mergifyGenerate / aggregate := false,
    mergifyCheck / aggregate := false,
    githubWorkflowGenerate := githubWorkflowGenerate
      .dependsOn((ThisBuild / mergifyGenerate))
      .value,
    githubWorkflowCheck := githubWorkflowCheck.dependsOn((ThisBuild / mergifyCheck)).value,
    ThisBuild / mergifyLabelPaths := {
      val labelPaths = (ThisBuild / mergifyLabelPaths).value
      projectLabel.value.fold(labelPaths) {
        case (label, path) =>
          val add = labelPaths.get(label) match {
            case Some(f) => label -> commonAncestor(f.toPath, path)
            case None => label -> path
          }
          labelPaths + (add._1 -> add._2.toFile)
      }
    }
  )

  private lazy val jobSuccessConditions = Def.setting {
    githubWorkflowGeneratedCI.value.flatMap {
      case job if mergifyRequiredJobs.value.contains(job.id) =>
        GenerativePlugin
          .expandMatrix(
            job.oses,
            job.scalas,
            job.javas,
            job.matrixAdds,
            job.matrixIncs,
            job.matrixExcs
          )
          .map { cell =>
            MergifyCondition.Custom(s"status-success=${job.name} (${cell.mkString(", ")})")
          }
      case _ => Nil
    }
  }

  private lazy val projectLabel = Def.setting {
    val path = (Compile / sourceDirectories)
      .?
      .value
      .getOrElse(Seq.empty)
      .map(_.toPath)
      .foldLeft(baseDirectory.value.toPath)(commonAncestor(_, _))

    val label = path.getFileName.toString

    def isRoot = path == (LocalRootProject / baseDirectory).value.toPath
    if (label.startsWith(".") || isRoot) // don't label this project
      None
    else Some(label -> path)
  }

  // x and y should be absolute/normalized
  private def commonAncestor(x: Path, y: Path): Path = {
    val n = math.min(x.getNameCount, y.getNameCount)
    (0 until n)
      .takeWhile(i => x.getName(i) == y.getName(i))
      .map(x.getName(_))
      .foldLeft(java.nio.file.Paths.get("/"))(_.resolve(_))
  }

  private lazy val mergifyYaml = Def.setting {
    (ThisBuild / baseDirectory).value / ".mergify.yml"
  }

  private lazy val generateMergifyContents = Def.task {
    import _root_.io.circe.syntax._
    import _root_.io.circe.yaml.Printer

    val contents = Map("pull_request_rules" -> mergifyPrRules.value.toList)
    val printer = Printer.spaces2.copy(dropNullKeys = true)

    s"""|# This file was automatically generated by sbt-typelevel-mergify using the
        |# mergifyGenerate task. You should add and commit this file to
        |# your git repository. It goes without saying that you shouldn't edit
        |# this file by hand! Instead, if you wish to make changes, you should
        |# change your sbt build configuration to revise the mergify configuration
        |# to meet your needs, then regenerate this file.
        |
        |${printer.pretty(contents.asJson)}""".stripMargin
  }

}
