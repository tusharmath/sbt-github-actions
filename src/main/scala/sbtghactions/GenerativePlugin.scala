/*
 * Copyright 2020 Daniel Spiewak
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

package sbtghactions

import sbt._, Keys._

import java.io.{BufferedWriter, FileWriter}

object GenerativePlugin extends AutoPlugin {

  override def requires = GitHubActionsPlugin
  override def trigger = noTrigger

  object autoImport extends GenerativeKeys

  import autoImport._

  private def indent(output: String, level: Int): String = {
    val space = (0 until level * 2).map(_ => ' ').mkString
    space + output.replace("\n", s"\n$space")
  }

  private def isSafeString(str: String): Boolean =
    !(str.indexOf(':') >= 0 ||    // pretend colon is illegal everywhere for simplicity
      str.indexOf('#') >= 0 ||    // same for comment
      str.indexOf('!') == 0 ||
      str.indexOf('*') == 0 ||
      str.indexOf('-') == 0 ||
      str.indexOf('?') == 0 ||
      str.indexOf('{') == 0 ||
      str.indexOf('}') == 0 ||
      str.indexOf('[') == 0 ||
      str.indexOf(']') == 0 ||
      str.indexOf(',') == 0 ||
      str.indexOf('|') == 0 ||
      str.indexOf('>') == 0 ||
      str.indexOf('@') == 0 ||
      str.indexOf('`') == 0 ||
      str.indexOf('"') == 0 ||
      str.indexOf('\'') == 0 ||
      str.indexOf('&') == 0)

  private def wrap(str: String): String =
    if (str.indexOf('\n') >= 0)
      " |\n" + indent(str, 1)
    else if (isSafeString(str))
      str
    else
      s"'${str.replace("'", "''")}'"

  private def compileList(items: List[String]): String =
    items.map(wrap).map("- " + _).mkString("\n")

  private def compileEnv(env: Map[String, String]): String =
    if (env.isEmpty) {
      ""
    } else {
      val rendered = env map {
        case (key, value) =>
          if (!isSafeString(key) || key.indexOf(' ') >= 0)
            sys.error(s"'$key' is not a valid environment variable name")

          s"""$key: ${wrap(value)}"""
      }
s"""env:
${indent(rendered.mkString("\n"), 1)}"""
    }

  private def compileStep(step: WorkflowStep, sbt: String, declareShell: Boolean = false): String = {
    import WorkflowStep._

    val renderedName = step.name.map(wrap).map("name: " + _ + "\n").getOrElse("")
    val renderedCond = step.cond.map(wrap).map("if: " + _ + "\n").getOrElse("")
    val renderedShell = if (declareShell) "shell: bash\n" else ""

    val preamblePre = renderedName + renderedCond + renderedShell + compileEnv(step.env)

    val preamble = if (preamblePre.isEmpty)
      ""
    else
      preamblePre + "\n"

    val body = step match {
      case Run(commands, _, _, _) =>
        "run: " + wrap(commands.mkString("\n"))

      case Sbt(commands, _, _, _) =>
        val safeCommands = commands map { c =>
          if (c.indexOf(' ') >= 0)
            s"'$c'"
          else
            c
        }

        "run: " + wrap(s"$sbt ++$${{ matrix.scala }} ${safeCommands.mkString("\n")}")

      case Use(owner, repo, version, params, _, _, _) =>
        val renderedParamsPre = compileEnv(params)
        val renderedParams = if (renderedParamsPre.isEmpty)
          ""
        else
          "\n" + renderedParamsPre

        s"uses: $owner/$repo@v$version" + renderedParams
    }

    indent(body, 1).updated(0, '-')
  }

  private def compileJob(job: WorkflowJob, sbt: String): String = {
    val renderedNeeds = if (job.needs.isEmpty)
      ""
    else
      s"\nneeds: [${job.needs.mkString(", ")}]"

    val renderedCond = job.cond.map(wrap).map("\nif: " + _).getOrElse("")

    val declareShell = job.oses.exists(_.contains("windows"))

s"""name: ${wrap(job.name)}${renderedNeeds}${renderedCond}
strategy:
  matrix:
    os: [${job.oses.mkString(", ")}]
    scala: [${job.scalas.mkString(", ")}]
    java: [${job.javas.mkString(", ")}]
runs-on: $${{ matrix.os }}${compileEnv(job.env)}
steps:
${indent(job.steps.map(compileStep(_, sbt, declareShell = declareShell)).mkString("\n\n"), 1)}"""
  }

  def compile(name: String, branches: List[String], env: Map[String, String], jobs: List[WorkflowJob], sbt: String): String = {
    val renderedEnvPre = compileEnv(env)
    val renderedEnv = if (renderedEnvPre.isEmpty)
      ""
    else
      renderedEnvPre + "\n\n"

    s"""name: ${wrap(name)}

on:
  pull_request:
${indent(compileList(branches), 2)}
  push:
${indent(compileList(branches), 2)}

${renderedEnv}jobs:
${indent(jobs.map(compileJob(_, sbt)).mkString("\n\n"), 1)}"""
}

  val settingDefaults = Seq(
    githubWorkflowSbtCommand := "sbt",

    githubWorkflowBuildPreamble := Seq(),
    githubWorkflowBuild := WorkflowStep.Sbt(List("test"), name = Some("Build project")),

    githubWorkflowPublishPreamble := Seq(),
    githubWorkflowPublish := WorkflowStep.Sbt(List("+publish"), name = Some("Publish project")),
    githubWorkflowPublishBranchGlobs := Seq("master"),
    githubWorkflowPublishCond := None,

    githubWorkflowJavaVersions := Seq("adopt@1.8"),
    githubWorkflowScalaVersions := crossScalaVersions.value,
    githubWorkflowOSes := Seq("ubuntu-latest"),
    githubWorkflowDependencyPatterns := Seq("**/*.sbt", "project/build.properties"),
    githubWorkflowTargetBranches := Seq("*"),

    githubWorkflowEnv := Map("GITHUB_TOKEN" -> s"$${{ secrets.GITHUB_TOKEN }}"),
    githubWorkflowAddedJobs := Seq())

  private lazy val internalTargetAggregation = settingKey[Seq[File]]("Aggregates target directories from all subprojects")

  override def projectSettings = Seq(Global / internalTargetAggregation += target.value)

  override def globalSettings = settingDefaults ++ Seq(
    githubWorkflowGeneratedCI := {
      val hashes = githubWorkflowDependencyPatterns.value map { glob =>
        s"$${{ hashFiles('$glob') }}"
      }

      val hashesStr = hashes.mkString("-")

      val uploadSteps = (internalTargetAggregation.value :+ file("project/target")) map { target =>
        WorkflowStep.Use(
          "actions",
          "upload-artifact",
          1,
          name = Some(s"Upload target directory '$target'"),
          params = Map(
            "name" -> s"target-$${{ runner.os }}-$target",
            "path" -> target.toString))
      }

      val downloadSteps = (internalTargetAggregation.value :+ file("project/target")) map { target =>
        WorkflowStep.Use(
          "actions",
          "download-artifact",
          1,
          name = Some(s"Download target directory '$target'"),
          params = Map("name" -> s"target-$${{ runner.os }}-$target"))
      }

      val preamble = List(
        WorkflowStep.Checkout,
        WorkflowStep.SetupScala,

        WorkflowStep.Use(
          "actions",
          "cache",
          1,
          name = Some("Cache ivy2"),
          params = Map(
            "path" -> "~/.ivy2/cache",
            "key" -> s"$${{ runner.os }}-sbt-ivy-cache-$hashesStr")),

        WorkflowStep.Use(
          "actions",
          "cache",
          1,
          name = Some("Cache coursier"),
          params = Map(
            "path" -> "~/.cache/coursier/v1",
            "key" -> s"$${{ runner.os }}-sbt-coursier-cache-$hashesStr")),

        WorkflowStep.Use(
          "actions",
          "cache",
          1,
          name = Some("Cache sbt"),
          params = Map(
            "path" -> "~/.sbt",
            "key" -> s"$${{ runner.os }}-sbt-cache-$hashesStr")))

      val publicationCondPre =
        githubWorkflowPublishBranchGlobs.value.map(g => s"contains(github.ref, $g)").mkString(" && ")

      val publicationCond = githubWorkflowPublishCond.value match {
        case Some(cond) => publicationCondPre + " && (" + cond + ")"
        case None => publicationCondPre
      }

      val publishJobOpt = Seq(
        WorkflowJob(
          "publish",
          "Publish Artifacts",
          preamble :::
            downloadSteps.toList :::
            githubWorkflowPublishPreamble.value.toList :::
            List(githubWorkflowPublish.value),   // TODO more steps
          List("ubuntu-latest"),
          cond = Some(s"github.event_name != 'pull_request' && $publicationCond"),
          scalas = List(scalaVersion.value),
          needs = List("build"))).filter(_ => !githubWorkflowPublishBranchGlobs.value.isEmpty)

      Seq(
        WorkflowJob(
          "build",
          "Build and Test",
          preamble :::
            githubWorkflowBuildPreamble.value.toList :::
            List(githubWorkflowBuild.value) :::
            uploadSteps.toList,
          githubWorkflowOSes.value.toList,
          scalas = crossScalaVersions.value.toList)) ++ publishJobOpt ++ githubWorkflowAddedJobs.value
    },

    githubWorkflowGeneratedClean := Seq())

  override def buildSettings = Seq(
    githubWorkflowGenerate / aggregate := false,
    githubWorkflowCheck / aggregate := false,

    githubWorkflowGenerate := {
      val ciContents = compile(
        "Continuous Integration",
        githubWorkflowTargetBranches.value.toList,
        githubWorkflowEnv.value,
        githubWorkflowGeneratedCI.value.toList,
        githubWorkflowSbtCommand.value)

      val githubDir = baseDirectory.value / ".github"
      val workflowsDir = githubDir / "workflows"
      val ciYml = workflowsDir / "ci.yml"

      if (!githubDir.exists()) {
        githubDir.mkdir()
      }

      if (!workflowsDir.exists()) {
        workflowsDir.mkdir()
      }

      val writer = new BufferedWriter(new FileWriter(ciYml))
      try {
        writer.write(ciContents)
      } finally {
        writer.close()
      }
    },

    githubWorkflowCheck := {
      // TODO
    })
}
