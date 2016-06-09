package com.act.biointerpretation.l2expansion;

import com.act.biointerpretation.mechanisminspection.Ero;
import com.act.biointerpretation.mechanisminspection.ErosCorpus;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;


/**
 * Runs L2 Expansion
 */
public class L2ExpansionDriver {

  private static final Logger LOGGER = LogManager.getFormatterLogger(L2ExpansionDriver.class);

  private static final String OPTION_METABOLITES = "m";
  private static final String OPTION_ROS = "r";
  private static final String OPTION_OUTPUT_PATH = "o";
  private static final String OPTION_HELP = "h";
  private static final String OPTION_ALL_ROS = "A";

  private static final String DEFAULT_METABOLITES = "/mnt/shared-data/Gil/resources/PABA_metabolites.txt";
  private static final String DEFAULT_ROS = "/mnt/shared-data/Gil/resources/PABA_ros.txt";

  public static final String HELP_MESSAGE =
          "This class is used to apply every RO from an input list to every metabolite in another input list. " +
                  "It creates a list of predicted reactions, containing the substrates, reactor, and products for each " +
                  "(RO, metabolite) pair for which a reaction is predicted to occur. " +
                  "This list is written to a file in json format.";

  public static final List<Option.Builder> OPTION_BUILDERS = new ArrayList<Option.Builder>() {{
    add(Option.builder(OPTION_METABOLITES)
            .argName("metabolites path name")
            .desc("The path to the metabolites file.")
            .hasArg()
            .longOpt("metabolite-file")
    );
    add(Option.builder(OPTION_ROS)
            .argName("ros path name")
            .desc("The path to the ros file.")
            .hasArg()
            .longOpt("ro-file")
    );
    add(Option.builder(OPTION_ALL_ROS)
            .argName("all ros flag")
            .desc("If this option is chosen, the expansion will be run on every RO in eros.json. " +
                    "This overrides any file provided with -r.")
            .longOpt("all-ros")
    );
    add(Option.builder(OPTION_OUTPUT_PATH)
            .argName("output file path")
            .desc("A path to which to write the json file of predicted reactions.")
            .hasArg()
            .longOpt("output-path")
            .required(true)
    );
    add(Option.builder(OPTION_HELP)
            .argName("help")
            .desc("Prints this help message.")
            .longOpt("help")
    );
  }};

  public static final HelpFormatter HELP_FORMATTER = new HelpFormatter();

  static {
    HELP_FORMATTER.setWidth(100);
  }

  public static void main(String[] args) throws Exception {

    // Build command line parser
    Options opts = new Options();
    for (Option.Builder b : OPTION_BUILDERS) {
      opts.addOption(b.build());
    }

    CommandLine cl = null;
    try {
      CommandLineParser parser = new DefaultParser();
      cl = parser.parse(opts, args);
    } catch (ParseException e) {
      LOGGER.error("Argument parsing failed: %s", e.getMessage());
      HELP_FORMATTER.printHelp(L2ExpansionDriver.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    // Print help
    if (cl.hasOption(OPTION_HELP)) {
      HELP_FORMATTER.printHelp(L2ExpansionDriver.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      return;
    }

    // Set filenames
    String metabolitesFile = cl.getOptionValue(OPTION_METABOLITES, DEFAULT_METABOLITES);
    String rosFile = cl.getOptionValue(OPTION_ROS, DEFAULT_ROS);
    String outputFile = cl.getOptionValue(OPTION_OUTPUT_PATH);

    // Build metabolite list
    LOGGER.info("Getting metabolite list from %s", metabolitesFile);
    L2MetaboliteCorpus metaboliteCorpus = new L2MetaboliteCorpus(metabolitesFile);
    metaboliteCorpus.loadCorpus();
    List<String> metaboliteList = metaboliteCorpus.getMetaboliteList();
    LOGGER.info("Metabolite list contains %d metabolites", metaboliteList.size());

    // Build ro list
    List<Ero> roList;
    ErosCorpus eroCorpus = new ErosCorpus();
    eroCorpus.loadCorpus();
    if (!cl.hasOption(OPTION_ALL_ROS)) {
      LOGGER.info("Getting ro list from %s", rosFile);
      roList = eroCorpus.getRoListFromFile(rosFile);
    } else {
      LOGGER.info("Getting all ROs.");
      roList = eroCorpus.getRos();
    }
    LOGGER.info("Ro list contains %d ros", roList.size());

    // Build L2Expander
    L2Expander expander = new L2Expander(roList, metaboliteList);

    // Carry out L2 expansion
    LOGGER.info("Beginning L2 expansion.");
    L2PredictionCorpus predictionCorpus = expander.getSingleSubstratePredictionCorpus();
    LOGGER.info("Done with L2 expansion.  Produced %d predictions.", predictionCorpus.getCorpus().size());

    // Print prediction corpus as json file
    LOGGER.info("Printing corpus to file %s", outputFile);
    predictionCorpus.writePredictionsToJsonFile(outputFile);
    LOGGER.info("L2ExpansionDriver complete!");
  }
}