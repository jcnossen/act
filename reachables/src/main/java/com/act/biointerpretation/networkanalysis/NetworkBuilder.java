package com.act.biointerpretation.networkanalysis;

import com.act.biointerpretation.l2expansion.L2PredictionCorpus;
import com.act.jobs.FileChecker;
import com.act.jobs.JavaRunnable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable class to build a metabolic network from a set of prediction corpuses.
 * For maximum flexibility
 */
public class NetworkBuilder implements JavaRunnable {

  private static final Logger LOGGER = LogManager.getFormatterLogger(NetworkBuilder.class);

  private static final boolean FAIL_ON_INVALID_INPUT = false;

  private final List<File> corpusFiles;
  private final File outputFile;
  // True if the builder should read in every valid input file even if some inputs are invalid.
  // False if builder should crash on even a single invalid input file.
  private final boolean skipInvalidInputs;

  public NetworkBuilder(List<File> corpusFiles, File outputFile) {
    this(corpusFiles, outputFile, FAIL_ON_INVALID_INPUT);
  }

  public NetworkBuilder(List<File> corpusFiles, File outputFile, boolean skipInvalidInputs) {
    this.corpusFiles = corpusFiles;
    this.outputFile = outputFile;
    this.skipInvalidInputs = skipInvalidInputs;
  }

  @Override
  public void run() throws IOException {
    LOGGER.info("Starting NetworkBuilder run.");

    // Check input files for validity
    for (File file : corpusFiles) {
      FileChecker.verifyInputFile(file);
    }
    FileChecker.verifyAndCreateOutputFile(outputFile);
    LOGGER.info("Checked input files for validity.");

    // Read in input corpuses
    List<L2PredictionCorpus> corpuses = new ArrayList<>(corpusFiles.size());
    for (File file : corpusFiles) {
      try {
        corpuses.add(L2PredictionCorpus.readPredictionsFromJsonFile(file));
      } catch (IOException e) {
        LOGGER.warn("Couldn't read file of name %s as input corpus; ignoring this file.", file.getName());
        if (!skipInvalidInputs) {
          throw new IOException("Couldn't read input file " + file.getName() + ": " + e.getMessage());
        }
      }
    }
    LOGGER.info("Successfully read in %d input files. Loading edges into network.", corpuses.size());

    // Set up network object, and loading predictions from corpuses into network edges.
    MetabolismNetwork network = new MetabolismNetwork();
    corpuses.forEach(corpus -> network.loadPredictions(corpus));
    LOGGER.info("Loaded corpuses. Writing network to file.");

    // Write network out
    network.writeToJsonFile(outputFile);
    LOGGER.info("Complete! Network has been written to %s", outputFile.getAbsolutePath());
  }
}