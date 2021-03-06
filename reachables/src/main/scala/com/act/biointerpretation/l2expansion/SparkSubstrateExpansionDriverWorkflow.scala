/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package com.act.biointerpretation.l2expansion

import java.io.File

import com.act.workflow.tool_manager.jobs.Job
import com.act.workflow.tool_manager.tool_wrappers.{ScalaJobWrapper, SparkWrapper}
import com.act.workflow.tool_manager.workflow.Workflow
import org.apache.commons.cli.{CommandLine, Options, Option => CliOption}
import org.apache.commons.io.FileUtils
import org.apache.logging.log4j.LogManager

import scala.collection.JavaConverters._

class SparkSubstrateExpansionDriverWorkflow extends Workflow {

  val DEFAULT_SPARK_MASTER = "spark://spark-master:7077"
  val LOCAL_JAR_PATH = "target/scala-2.10/reachables-assembly-0.1.jar"
  val logger = LogManager.getLogger(getClass.getName)

  override val HELP_MESSAGE = "Workflow for doing substrate expansions with Spark.  " +
    "Handles reconverting the predictions to InChIs and starting the next level of expansion."


  val OPTION_LICENSE_FILE = "l"
  val OPTION_SUBSTRATES_LIST = "i"
  val OPTION_OUTPUT_DIRECTORY = "o"
  val OPTION_NUMBER_OF_REPEATS = "n"
  val OPTION_SPARK_MASTER = "m"
  val OPTION_ASSEMBLED_JAR_FILE = "j"
  val OPTION_CREATE_JAR_FILE_AT_RUNTIME = "r"

  override def getCommandLineOptions: Options = {
    val options = List[CliOption.Builder](
      CliOption.builder(OPTION_LICENSE_FILE).
        required.
        hasArg.
        longOpt("license-file")
        .desc("A path to the Chemaxon license file to load, mainly for checking license validity"),

      CliOption.builder(OPTION_SUBSTRATES_LIST).
        required.
        hasArg.
        longOpt("substrates-list").
        desc("A list of substrate InChIs onto which to project ROs"),

      CliOption.builder(OPTION_OUTPUT_DIRECTORY).
        required.
        hasArg.
        longOpt("output-directory").
        desc("A directory in which to write per-RO result files"),

      CliOption.builder(OPTION_NUMBER_OF_REPEATS).
        required.
        hasArg.
        longOpt("repeat-number").
        desc("The number of expansions that should be done"),

      CliOption.builder(OPTION_ASSEMBLED_JAR_FILE).
        hasArg.
        longOpt("assembled-jar-file").
        desc("The path to an assembled JAR from this project."),

      CliOption.builder(OPTION_CREATE_JAR_FILE_AT_RUNTIME).
        longOpt("assembled-jar-file").
        desc("This toggle tries to use the current project as the assembled JAR file, assembling it if need be."),

      CliOption.builder(OPTION_SPARK_MASTER).
        hasArg.
        longOpt("spark-master").
        desc(s"Spark master for the spark cluster.  Defaults to '$DEFAULT_SPARK_MASTER'."),

      CliOption.builder("h").argName("help").desc("Prints this help message").longOpt("help")
    )

    val opts: Options = new Options()
    for (opt <- options) {
      opts.addOption(opt.build)
    }
    opts
  }

  override def defineWorkflow(cl: CommandLine): Job = {
    require(!cl.hasOption(OPTION_ASSEMBLED_JAR_FILE) | !cl.hasOption(OPTION_CREATE_JAR_FILE_AT_RUNTIME),
      s"A JAR file must either be explicitly supplied [Option: $OPTION_ASSEMBLED_JAR_FILE <File Path>] or you must " +
        s"indicate that you'd like to try to assemble the JAR file at runtime [Option $OPTION_CREATE_JAR_FILE_AT_RUNTIME]")

    val assembledJarPath = if (cl.hasOption(OPTION_ASSEMBLED_JAR_FILE)) {
      val existingJar = new File(cl.getOptionValue(OPTION_ASSEMBLED_JAR_FILE))
      require(existingJar.exists(), s"Assembled JAR file that was supplied does not exist.  " +
        s"Supplied ${existingJar.getAbsolutePath}")
      existingJar
    } else {
      // Make sure to assemble jar first
      headerJob.thenRun(
        SparkWrapper.assembleJarAtRuntime(LOCAL_JAR_PATH).doNotWriteErrorStream())
      new File(LOCAL_JAR_PATH)
    }

    val repeatRange = Range(1, cl.getOptionValue(OPTION_NUMBER_OF_REPEATS).toInt + 1)

    val substrateListFile = new File(cl.getOptionValue(OPTION_SUBSTRATES_LIST))
    require(substrateListFile.exists(),
      s"Substrate list file supplied does not exist.  Supplied ${substrateListFile.getAbsolutePath}")

    val chemaxonLicenseFile = new File(cl.getOptionValue(OPTION_LICENSE_FILE))
    require(chemaxonLicenseFile.exists(),
      s"Chemaxon license file supplied does not exist.  Supplied ${chemaxonLicenseFile.getAbsolutePath}")


    val workingDirectory = new File(cl.getOptionValue(OPTION_OUTPUT_DIRECTORY))
    if (!workingDirectory.exists()) workingDirectory.mkdirs()

    // Drop right to remove $
    val singleSubstrateRoProjectorClassPath = SparkSingleSubstrateROProjector.getClass.getName.dropRight(1)
    val sparkMaster = cl.getOptionValue(OPTION_SPARK_MASTER, DEFAULT_SPARK_MASTER)
    // Tries to assemble JAR for spark export.  Step 1 towards Skynet is self-assembly of jar files.

    val outputInchiIdentifier = "uniqueInchisIteration"
    // Copy file so we can just use the same
    FileUtils.copyFile(substrateListFile, new File(workingDirectory, s"$outputInchiIdentifier.0.txt"))

    repeatRange.foreach(iteration => {
      val iterationOutputDirectory = new File(workingDirectory, s"$iteration.ExpansionOf.${substrateListFile.getName}")
      if (!iterationOutputDirectory.exists()) iterationOutputDirectory.mkdirs()

      val outputUniqueInchiFile = new File(workingDirectory, s"$outputInchiIdentifier.$iteration.txt")

      val substrateList = new File(workingDirectory, s"$outputInchiIdentifier.${iteration - 1}.txt")

      val roProjectionArgs = List(
        SparkSingleSubstrateROProjector.OPTION_SUBSTRATES_LIST, substrateList.getAbsolutePath,
        SparkSingleSubstrateROProjector.OPTION_OUTPUT_DIRECTORY, iterationOutputDirectory.getAbsolutePath,
        SparkSingleSubstrateROProjector.OPTION_LICENSE_FILE, chemaxonLicenseFile.getAbsolutePath,
        SparkSingleSubstrateROProjector.OPTION_FILTER_FOR_SPECTROMETERY
      )

      // Scales memory up as the size gets larger in hopes of avoiding memory issues.
      // Expansion size grows at about 50x, so memory requirements get large as size increases.
      val expansion =
        SparkWrapper.runClassPath(
          assembledJarPath.getAbsolutePath, sparkMaster)(singleSubstrateRoProjectorClassPath,
          roProjectionArgs)(memory = s"${iteration*iteration}G")

      val processing: () => Unit = () => {
        // Each RO has its own file.
        val allFilesInOutputDir: List[File] = iterationOutputDirectory.listFiles().toList
        logger.info(s"Found ${allFilesInOutputDir.length} projection files.")

        val allInchis: Set[String] = allFilesInOutputDir.flatMap(inputFile => {
          val predictionCorpus = L2PredictionCorpus.readPredictionsFromJsonFile(inputFile)

          val uniqueSubstrates = predictionCorpus.getUniqueSubstrateInchis.asScala.toSet
          val uniqueProducts = predictionCorpus.getUniqueProductInchis.asScala.toSet

          logger.info(s"Found ${uniqueSubstrates.size} unique substrates and ${uniqueProducts.size} unique products.  " +
            s"Combining and writing them to a file.")
          uniqueProducts ++ uniqueSubstrates
        }).toSet

        val inchis = new L2InchiCorpus(allInchis.asJava)
        inchis.writeToFile(outputUniqueInchiFile)
      }

      val convertPredictionToUniqueInchis = ScalaJobWrapper.wrapScalaFunction(s"Condense $iteration into unique molecules.", processing)

      // Skip if already created
      if (!outputUniqueInchiFile.exists) {
        headerJob.thenRun(expansion.doNotWriteOutputStream())
        headerJob.thenRun(convertPredictionToUniqueInchis)
      } else {
        logger.info(s"Skipping trying to create ${outputUniqueInchiFile.getAbsolutePath} as it already exists.")
      }
    })

    headerJob
  }
}
