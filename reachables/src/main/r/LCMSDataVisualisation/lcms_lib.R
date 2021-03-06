##########################################################################
#                                                                        #
#  This file is part of the 20n/act project.                             #
#  20n/act enables DNA prediction for synthetic biology/bioengineering.  #
#  Copyright (C) 2017 20n Labs, Inc.                                     #
#                                                                        #
#  Please direct all queries to act@20n.com.                             #
#                                                                        #
#  This program is free software: you can redistribute it and/or modify  #
#  it under the terms of the GNU General Public License as published by  #
#  the Free Software Foundation, either version 3 of the License, or     #
#  (at your option) any later version.                                   #
#                                                                        #
#  This program is distributed in the hope that it will be useful,       #
#  but WITHOUT ANY WARRANTY; without even the implied warranty of        #
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         #
#  GNU General Public License for more details.                          #
#                                                                        #
#  You should have received a copy of the GNU General Public License     #
#  along with this program.  If not, see <http://www.gnu.org/licenses/>. #
#                                                                        #
##########################################################################

# Main library. Contains functions related to LCMS data processing likely to be re-used.

kLCMSDataLocation <- "MNT_DATA_LEVEL1/lcms-ms1/"
kLCMSDataCacheLocation <- "MNT_DATA_LEVEL1/lcms-ms1-rcache/"
kPeakDisplaySep <- " - "
kIntensityThreshold <- 10000
kSSRatio <- 20

getAndCacheScanFile <- function(scan.file.name) {
  # Get and serialize a netCDF scan file.
  #
  # Args:
  #   scan.file.name: input scan file name (relative to `kLCMSDataLocation`)
  #
  # Returns:
  #   a list of three objects: scan.file.name, hd, ms1.scans
  
  # Parameters validation
  shiny::validate(
    need(scan.file.name != "", "Please choose an input file!")
  )
  filepath <- paste0(kLCMSDataLocation, scan.file.name)
  cachename <- gsub(".nc", ".rds", scan.file.name)
  cachepath <- paste0(kLCMSDataCacheLocation, cachename)
  
  if (file.exists(cachepath)) {
    loginfo("Reading scan file (%s) from cache at %s.", scan.file.name, cachepath)
    scan.file <- readRDS(cachepath)
    shiny::validate(
      need(scan.file$filename == scan.file.name, 
           sprintf("Oops, the cached scan file (%s) was found with an incorrect filename (%s).",
                   cachepath, scan.file$filename))
    )
    loginfo("Done reading scan file (%s) from cache at %s.", scan.file.name, cachepath)
  } else {
    shiny::validate(
      need(file.exists(filepath), 
           sprintf("Input scan file was not found in the cache or in default directory (%s)", kLCMSDataLocation))
    )
    loginfo("Reading scan file (%s) from disk at %s.", scan.file.name, filepath)
    msfile <- openMSfile(filepath, backend="netCDF")
    hd <- header(msfile)
    ms1 <- which(hd$msLevel == 1)
    ms1.scans <- peaks(msfile, ms1)
    scan.file <- list(filename = scan.file.name, hd = hd, ms1.scans = ms1.scans)
    loginfo("Saving scan file (%s) in the cache at %s.", scan.file.name, cachepath)
    saveRDS(scan.file, file = cachepath)
    loginfo("Done saving scan file (%s) in the cache.", scan.file.name)
  }
  return(scan.file)
}

getScans <- function(scan.file, retention.time.range) {
  # Get scans corresponding to a time range
  #
  # Args:
  #   scan.file: a list of three objects: filename, hd, ms1.scans
  #   retention.time.range: tuple, retention time range selected
  #
  # Returns:
  #   list: filename, scans, retention.time, retention.time.range
  
  # Parameters validation
  shiny::validate(
    need(length(retention.time.range) == 2, "Rentention time range is not a tuple. Please fix!"),
    need(is.numeric(retention.time.range), "Rentention time range was not numeric. Please fix!"),
    need(length(scan.file$ms1.scans) > 0, "Found 0 scans in loaded scan file. Please check the input file or the cached data!")
  )
  min.rt <- retention.time.range[1]
  max.rt <- retention.time.range[2]
  # Extract the relevant scans from the scan file
  header <- scan.file$hd
  ms1 <- which(header$msLevel == 1)
  rtsel <- header$retentionTime[ms1] > min.rt & header$retentionTime[ms1] < max.rt # vector of boolean
  loginfo("Found %d scans with retention time in range [%.1f, %.1f] for scan file %s.", sum(rtsel), min.rt, max.rt, scan.file$filename)
  scans <- scan.file$ms1.scans[rtsel]
  
  # We need to replicate the retention time as many times as the length of each scan
  scans.lengths <- unlist(lapply(scans, nrow))
  retention.time <- rep(header$retentionTime[rtsel], scans.lengths)
  list(filename = scan.file$filename, scans = scans, retention.time = retention.time, retention.time.range = retention.time.range)
}

getPeaksInScope <- function(scans.with.time, target.mz.value, mz.band.halfwidth) {
  # Get peaks in an mz window and retention time range
  #
  # Args:
  #   scans.with.time: list - filename, scans, retention.time, retention.time.range
  #   target.mz.value: Double - m/z value, center of the m/z window
  #   mz.band.halfwidth: Double - m/z bandwidth on each side
  #
  # Returns:
  #   list: filename, peaks, retention.time.range, mz.range
  
  # Parameters validation
  shiny::validate(
    need(target.mz.value >= 50 && target.mz.value <= 950, "Target mz value should be between 50 and 950"),
    need(mz.band.halfwidth >= 0.00001, "M/Z band halfwidth should be >= 0.00001"),
    need(mz.band.halfwidth <= 1, "Avoid values of M/Z band halfwidth > 1 that can make the server crash")
  )
  min.ionic.mass <- target.mz.value - mz.band.halfwidth
  max.ionic.mass <- target.mz.value + mz.band.halfwidth
  
  # extract mz/intensity values from scans to a dataframe
  peaks <- with(scans.with.time, {
    shiny::validate(
      need(length(scans) > 0, "Found 0 scans in input time range")
    )
    mz <- unlist(lapply(scans, function(x) x[, "mz"]))
    intensity <- unlist(lapply(scans, function(x) x[, "intensity"]))
    data.frame(mz = mz, retention.time = retention.time, intensity = intensity)
  })
  # now we can manipulate triples (retention.time, mz, intensity)
  peaks.in.scope <- peaks %>% 
    dplyr::filter(mz < max.ionic.mass & mz > min.ionic.mass)
  loginfo("Found %d peaks in mz window [%.4f, %.4f] for scan file %s.", 
          nrow(peaks.in.scope), min.ionic.mass, max.ionic.mass, scans.with.time$filename)
  list(filename = scans.with.time$filename, peaks = peaks.in.scope, 
       retention.time.range = scans.with.time$retention.time.range, mz.range = c(min.ionic.mass, max.ionic.mass))
}

drawScatterplot <- function(plot.data, plot.parameters, ...) {
  # Draw a 3D scatterplot of the data with given angle parameters
  #
  # Args:
  #   plot.data: list: filename, peaks, retention.time.range, mz.range
  #   plot.parameters: list of theta and phi angles (in degrees)
  #   ... (zlim, clim): intensity and color scale - used when normalizing graphs
  shiny::validate(
    need(nrow(plot.data$peaks) > 0, "There are 0 peaks to plot trace and scope.")
  )
  with(plot.data, {
    scatter3D(peaks$retention.time, peaks$mz, peaks$intensity, 
              # pch: plotting symbol, cex: label magnification factor
              pch = 16, cex = 1.5, 
              # type: adds vertical sticks to the drawn points
              type = "h", 
              # colkey: plots a color legend
              colkey = list(side = 1, length = 0.5, width = 0.5, cex.clab = 0.75), 
              # expand: vertical expansion of the graph, ticktype: ticks on all axis
              expand = 0.5, ticktype = "detailed", 
              # main: title, {x,y,z}lab: axis labels
              main = filename, zlab = "Intensity", xlab = "Retention time (sec)", ylab = "m/z (Da)",
              # theta: azimuthal (left <> right) angle, phi: colatitude (down <> up) angle
              theta = plot.parameters$angle.theta, phi = plot.parameters$angle.phi, 
              # {x,y,z}lim: limits of the graph, clim: limits for the color scale
              xlim = retention.time.range, ylim = mz.range, ...)
  })
}

detectPeaks <- function(peaks) {
  # Apply a simple peak detection method on a set of peaks (mz, rt, intensity triples)
  #
  # Args:
  #   peaks: dataframe with columns: mz, retention.time, intensity
  #
  # Returns:
  #   one or two rows of the above dataframe
  
  # select peaks above intensity threshold
  peak.set <- peaks %>%
    dplyr::filter(intensity > kIntensityThreshold)
  # if no peak meets that criterion, display error message
  shiny::validate(
    need(nrow(peak.set) > 0, sprintf("No peak found above the clustering threshold: %d", kIntensityThreshold))
  )
  # set seed for reproducibility of the results
  set.seed(2016)
  # run kmeans with k=2
  fit <- kmeans(peak.set$mz, centers = 2)
  # assess separation of clusters (kSSRatio is experimental)
  if (fit$betweenss / fit$tot.withinss > kSSRatio) {
    intervals <- classIntervals(peak.set$mz, n = 2, style = "kmeans")  
    mean.mz.break <- intervals$brks[2]
    peak1 <- peak.set %>%
      dplyr::filter(mz < mean.mz.break) %>%
      top_n(1, intensity)
    peak2 <- peak.set %>%
      dplyr::filter(mz >= mean.mz.break) %>%
      top_n(1, intensity)
    # if clusters are separated well enough, return two peaks
    rbind(peak1, peak2)
  } else {
    peak1 <- peak.set %>%
      top_n(1, intensity)
    # otherwise return one peak
    peak1
  }
}

getAndValidateConfigFile <- function(input.file) {
  # Read and validate a JSON file based on a shiny::fileInput
  #
  # Args:
  #   input.file: result from shiny's fileInput
  #
  # Returns:
  #   the parsed config file
  shiny::validate(
    need(!is.null(input.file), "Please upload a configuration file.") 
  )
  config <- fromJSON(file(input.file$datapath))
  layout <- config$layout
  scan.filenames <- config$scanfiles$filename
  shiny::validate(
    need(length(scan.filenames) > 0, 
         "No scan file names found. Scan file names should be fed in 'scanfiles'"),
    need(layout$nrow >= 1 && layout$nrow <= 3, "Number of rows in the layout should be in the range [1, 3]"),
    need(layout$ncol >= 1 && layout$nrow <= 3, "Number of cols in the layout should be in the range [1, 3]"),
    need(layout$nrow * layout$ncol >= length(scan.filenames), 
         "Too many scan files for input layout. Please double check the layout.")
  )
  config
}
