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

package com.act.reachables

import java.io.{File, PrintWriter}

import act.server.MongoDB
import org.apache.commons.cli.{CommandLine, DefaultParser, HelpFormatter, Options, ParseException, Option => CliOption}
import org.apache.logging.log4j.LogManager

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.io.Source

object reachables {
  private val HELP_FORMATTER: HelpFormatter = new HelpFormatter
  HELP_FORMATTER.setWidth(100)
  val LOGGER = LogManager.getLogger(getClass.getName)

  private val HELP_MESSAGE =
    """Usage: run --prefix=PRE 
      |           --useNativesFile=FILE
      |           --useCofactorsFile=FILE
      |           --defaultDbName=DB_NAME
      |           --output-dir=DIR
      |           [--hasSeq=true|false]
      |           [--regressionSuiteDir=path]
      |           [--extra=semicolon-sep db.chemical fields]
      |
      |Example: run --prefix=r
      | will create reachables tree with prefix r and by default with only enzymes that have seq
      |
      |Example: run --prefix=r --extra=xref.CHEBI;xref.DEA;xref.DRUGBANK
      | will make sure all CHEBI/DEA/DRUGBANK chemicals are included. Those that are already reachable will be in the
      | normal part of the tree and those that are not will have parent_id < -1 """.stripMargin

  private val OPTION_PREFIX = "p"
  private val OPTION_HAS_SEQ = "s"
  private val OPTION_NATIVES_FILE = "n"
  private val OPTION_COFACTORS_FILE = "c"
  private val OPTION_REGRESSION_DIR = "r"
  private val OPTION_EXTRA_INFORMATION = "e"
  private val OPTION_OUTPUT_DIRECTORY = "o"
  private val OPTION_DATABASE = "d"

  private def getCommandLineOptions: Options = {
    val options = List[CliOption.Builder](

      CliOption.builder(OPTION_PREFIX).
        hasArg.
        longOpt("prefix").
        desc("Prefix applied to any file generated").
        required(true),

      CliOption.builder(OPTION_NATIVES_FILE).
        hasArg().
        longOpt("useNativesFile").
        desc("Path to file containing native chemicals.").
        required(true),

      CliOption.builder(OPTION_COFACTORS_FILE).
        hasArg().
        longOpt("useCofactorsFile").
        desc("Path to file containing cofactor chemicals.").
        required(true),

      CliOption.builder(OPTION_HAS_SEQ).
        longOpt("hasSeq").
        desc("Flag to indicate if only reactions with sequences should be used."),

      CliOption.builder(OPTION_REGRESSION_DIR).
        hasArg.
        longOpt("regressionSuiteDir").
        desc("Path to the directory that contains regression test files."),

      CliOption.builder(OPTION_EXTRA_INFORMATION).
        hasArgs.valueSeparator(';').
        longOpt("extra").
        desc("Ensure chemicals with certain information are included."),

      CliOption.builder(OPTION_OUTPUT_DIRECTORY).
        hasArg.
        longOpt("output-dir").
        desc("Ensure chemicals with certain information are included.").
        required(true),
      
      CliOption.builder(OPTION_DATABASE).
        hasArg.
        longOpt("defaultDbName").
        desc("The database that the reachables collection will use.").
        required(true),


      CliOption.builder("h").argName("help").desc("Prints this help message").longOpt("help")
    )

    val opts: Options = new Options()
    for (opt <- options) {
      opts.addOption(opt.build)
    }
    opts
  }

  private def exitWithHelp(opts: Options): Unit = {
    HELP_FORMATTER.printHelp(this.getClass.getCanonicalName, HELP_MESSAGE, opts, null, true)
    System.exit(1)
  }

  private def parseCommandLineOptions(args: Array[String]): CommandLine = {
    val opts = getCommandLineOptions

    // Parse command line options
    var cl: Option[CommandLine] = None
    try {
      val parser = new DefaultParser()
      cl = Option(parser.parse(opts, args))
    } catch {
      case e: ParseException =>
        LOGGER.error(s"Argument parsing failed: ${e.getMessage}\n")
        exitWithHelp(opts)
    }

    if (cl.isEmpty) {
      LOGGER.error("Detected that command line parser failed to be constructed.")
      exitWithHelp(opts)
    }

    if (cl.get.hasOption("help")) exitWithHelp(opts)

    LOGGER.info("Finished processing command line information")
    cl.get
  }

  def main(args: Array[String]) {
    val params = parseCommandLineOptions(args)

    val prefix = params.getOptionValue(OPTION_PREFIX)
    
    val currentDatabase = params.getOptionValue(OPTION_DATABASE)

    writeReachableTree(prefix, currentDatabase, params.hasOption(OPTION_HAS_SEQ),
      Option(params.getOptionValues(OPTION_EXTRA_INFORMATION)),
      Option(params.getOptionValue(OPTION_REGRESSION_DIR)),
      params.getOptionValue(OPTION_NATIVES_FILE),
      Option(params.getOptionValue(OPTION_COFACTORS_FILE)),
      Option(params.getOptionValue(OPTION_OUTPUT_DIRECTORY)))
  }

  def writeReachableTree(prefix: String, currentDb: String, needSeq: Boolean, extraInformation: Option[Array[String]],
                         regressionDir: Option[String],
                         nativesFile: String,
                         cofactorsFile: Option[String], outputFile: Option[String]) {

    /* --------------- Parse Options ------------------ */
    val regressionOutputDirectory = s"$prefix.regressions/"

    val universal_natives = {
      val data = Source.fromFile(nativesFile).getLines
      val inchis = data.filter { x => x.length > 0 && x.charAt(0) != '#' }.map(x => x.trim)
      collection.mutable.Set(inchis.toSeq: _*).toSet.asJava
    }

    val universal_cofactors = if (cofactorsFile.isDefined) {
      val data = Source.fromFile(cofactorsFile.get).getLines
      val inchis = data.filter { x => x.length > 0 && x.charAt(0) != '#' }
      collection.mutable.Set(inchis.toSeq: _*).toSet.asJava
    } else {
      null
    }

    val regression_suite_files = if (regressionDir.isDefined){
      val files = new File(regressionDir.get).listFiles
      val testfiles = files.map(n => n.getAbsolutePath).filter(_.endsWith(".test.txt"))
      testfiles.toSet
    } else {
      Set()
    }

    val chems_w_extra_fields = if (extraInformation.isDefined){
      extraInformation.get
    } else {
      null
    }

    val outputDirectory = outputFile match {
      case Some(x) => {
        val gottenDir = new File(outputFile.get)
        if (!gottenDir.exists()) {
          gottenDir.mkdirs()
        }
        gottenDir.getAbsolutePath
      }
      case None => null
    }

    /* --------------- Construct Reachable Tree ------------------ */
    // set parameter for whether we want to exclude rxns that dont have seq
    GlobalParams._actTreeOnlyIncludeRxnsWithSequences = needSeq

    val tree = LoadAct.getReachablesTree(currentDb, universal_natives, universal_cofactors, needSeq, chems_w_extra_fields)
    LOGGER.info(s"Done: L2 reachables computed. Num reachables found: ${tree.nodesAndIds.size}")

    // get inchis for all the reachables
    // Network.nodesAndIds(): Map[Node, Long] i.e., maps nodes -> ids
    // chemId2Inchis: Map[Long, String] i.e., maps ids -> inchis
    // so we take the Map[Node, Long] and map each of the key_value pairs
    // by looking up their ids (using n_ids._2) in chemId2Inchis
    // then get a nice little set from that Iterable
    def id2InChIName(id: Long) = id -> (ActData.instance.chemId2Inchis.get(id), ActData.instance.chemId2ReadableName.get(id))
    def tab(id_inchi_name: (Long, (String, String))) = id_inchi_name._1 + "\t" + id_inchi_name._2._2 + "\t" + id_inchi_name._2._1

    val reachables: Map[Long, (String, String)] = tree.nodesAndIds.map(x => id2InChIName(x._2)).toMap
    def fst(x: (String, String)) = x._1
    val r_inchis: Set[String] = reachables.values.toSet.map(fst) // reachables.values are (inchi, name)

    // create output directory for regression test reports, if not already exists
    makeRegressionDirectory(regressionOutputDirectory)
    // run regression suites if provided
    regression_suite_files.foreach(test => runRegression(r_inchis, test, regressionOutputDirectory))

    // serialize ActData, which contains a summary of the relevant act
    // data and the corresponding computed reachables state
    ActData.instance.serialize(new File(outputDirectory, s"$prefix.actdata").getAbsolutePath)
  }

  def runRegression(reachable_inchis: Set[String], test_file: String, output_report_dir: String) {
    val testlines: List[String] = Source.fromFile(test_file).getLines.toList
    val testcols: List[List[String]] = testlines.map(line => line.split("\t").toList)

    val hdrs = Set("inchi", "name", "plausibility", "comment", "reference")
    if (testcols.isEmpty || !testcols.head.toSet.equals(hdrs)) {
      LOGGER.warn("Invalid test file: " + test_file)
      LOGGER.warn("\tExpected: " + hdrs.toString)
      LOGGER.warn("\tFound: " + testcols.head.toString)
    } else {
      // delete the header from the data set, leaving behind only the test rows
      val hdr = testcols.head
      val rows = testcols.drop(1)

      def add_hdrs(row: List[String]) = hdr.zip(row)

      // partition test rows based on whether this reachables set passes or fails them
      val (passed, failed) = rows.partition(testrow => runRegression(add_hdrs(testrow), reachable_inchis))

      val report = generateReport(test_file, passed, failed)
      writeTo(new File(output_report_dir, new File(test_file).getName).getAbsolutePath, report)
      LOGGER.info(s"Regression file: $test_file")
      LOGGER.info(s"Total test: ${rows.length} (passed, failed): (${passed.length}, ${failed.length})")
    }
  }

  def makeRegressionDirectory(dir: String) {
    val dirl = new File(dir)
    if (dirl exists) {
      if (dirl.isFile) {
        LOGGER.error(s"$dir already exists as a file. Need it as dir for regression output. Abort.")
        System.exit(-1)
      }
    } else {
      dirl.mkdirs()
    }
  }

  def generateReport(f: String, passed: List[List[String]], failed: List[List[String]]) = {
    val total = passed.length + failed.length
    val write_successes = false
    val write_failures = true

    val lines =
    // add summary to head of report file
      List(
        s"** Regression test result for $f",
        s"\tTOTAL: $total PASSED: ${passed.length}",
        s"\tTOTAL: $total FAILED: ${failed.length}"
      ) ++ (
        // add details of cases that succeeded
        if (write_successes)
          passed.map("\t\tPASSED: " + _)
        else
          List()
        ) ++ (
        // add details of cases that failed
        if (write_failures)
          failed.map("\t\tFAILED: " + _)
        else
          List()
        )

    // make the report as a string of lines
    val report = lines reduce (_ + "\n" + _)

    // return the report
    report
  }

  def runRegression(row: List[(String, String)], reachable_inchis: Set[String]): Boolean = {
    val data = row.toMap
    val inchi = data.getOrElse("inchi", "") // inchi column
    val should_exist = data.getOrElse("plausibility", "TRUE").toBoolean // plausibility column

    val exists = reachable_inchis.contains(inchi)
    exists
  }

  def writeTo(fname: String, json: String) {
    val file = new PrintWriter(new File(fname))
    file write json
    file.close()
  }
}
