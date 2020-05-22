package beam.physsim.jdeqsim

import java.util
import java.util.stream.Collectors
import java.util.{HashMap, List, Map}

import beam.analysis.physsim.{PhyssimCalcLinkStats, PhyssimSpeedHandler}
import beam.analysis.plot.PlotGraph
import beam.physsim.bprsim.{BPRSimConfig, BPRSimulation, ParallelBPRSimulation}
import beam.physsim.jdeqsim.cacc.CACCSettings
import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.{
  Hao2018CaccRoadCapacityAdjustmentFunction,
  RoadCapacityAdjustmentFunction
}
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation
import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.sim.config.BeamConfig
import beam.utils.{DebugLib, FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.lang3.StringUtils
import org.matsim.analysis.LegHistogram
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Population
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.core.utils.misc.Time

import scala.util.Try
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class JDEQSimRunner(
  val beamConfig: BeamConfig,
  val jdeqSimScenario: Scenario,
  val population: Population,
  val beamServices: BeamServices,
  val controlerIO: OutputDirectoryHierarchy,
  val isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  val beamConfigChangesObservable: BeamConfigChangesObservable,
  val agentSimIterationNumber: Int
) extends StrictLogging {

  def simulate(currentPhysSimIter: Int, writeEvents: Boolean): SimulationResult = {
    val jdeqsimEvents = new EventsManagerImpl
    val travelTimeCalculator =
      new TravelTimeCalculator(jdeqSimScenario.getNetwork, jdeqSimScenario.getConfig.travelTimeCalculator)
    val legHistogram = new LegHistogram(
      population,
      jdeqsimEvents,
      beamConfig.beam.outputs.stats.binSize,
      getNoOfBins(beamConfig.beam.outputs.stats.binSize)
    );

    val linkStatsGraph = new PhyssimCalcLinkStats(
      jdeqSimScenario.getNetwork,
      controlerIO,
      beamServices.beamConfig,
      jdeqSimScenario.getConfig.travelTimeCalculator,
      beamConfigChangesObservable
    );
    linkStatsGraph.notifyIterationStarts(jdeqsimEvents, jdeqSimScenario.getConfig.travelTimeCalculator)

    val eventTypeCounter = new EventTypeCounter
    jdeqsimEvents.addHandler(eventTypeCounter)
    val carTravelTimeHandler = new CarTravelTimeHandler(isCACCVehicle.asScala.map {
      case (k, v) => k -> Boolean2boolean(v)
    })
    jdeqsimEvents.addHandler(carTravelTimeHandler)

    jdeqsimEvents.addHandler(travelTimeCalculator)
    jdeqsimEvents.addHandler(new JDEQSimMemoryFootprint(beamConfig.beam.debug.debugEnabled))

    val physsimSpeedHandler = new PhyssimSpeedHandler(population, controlerIO, beamConfig)
    jdeqsimEvents.addHandler(physsimSpeedHandler)

    val maybeEventWriter = if (writeEvents) {
      val writer = PhysSimEventWriter(beamServices, jdeqsimEvents)
      jdeqsimEvents.addHandler(writer)
      Some(writer)
    } else None

    val maybeRoadCapacityAdjustmentFunction = if (beamConfig.beam.physsim.jdeqsim.cacc.enabled) {
      Some(
        new Hao2018CaccRoadCapacityAdjustmentFunction(
          beamConfig,
          agentSimIterationNumber,
          controlerIO,
          beamConfigChangesObservable
        )
      )
    } else None

    val simName = beamConfig.beam.physsim.name

    try {
      ProfilingUtils.timed(
        s"JDEQSim iteration $currentPhysSimIter for ${population.getPersons.size()} people",
        x => logger.info(x)
      ) {
        val jdeqSimulation =
          getJDEQSimulation(jdeqSimScenario, jdeqsimEvents, maybeRoadCapacityAdjustmentFunction, simName)
        logger.info(s"JDEQSim iteration $currentPhysSimIter start");
        if (beamConfig.beam.debug.debugEnabled) {
          logger.info(DebugLib.getMemoryLogMessage("Memory Use Before JDEQSim: "));
        }
        jdeqSimulation.run()
        logger.info(s"JDEQSim iteration $currentPhysSimIter finished");
      }

    } finally {
      Try(jdeqsimEvents.finishProcessing())
      maybeEventWriter.foreach { wrt =>
        Try(wrt.closeFile())
      }
      maybeRoadCapacityAdjustmentFunction.foreach(_.reset())

      legHistogram.getLegModes.forEach(mode => {
        new PlotGraph().writeGraphic(
          legHistogram,
          controlerIO,
          s"${currentPhysSimIter}.physsimTripHistogram",
          "time (binSize=<?> sec)",
          mode,
          agentSimIterationNumber,
          beamConfig.beam.outputs.stats.binSize
        )
      })
      linkStatsGraph.notifyIterationEnds(agentSimIterationNumber, travelTimeCalculator.getLinkTravelTimes)
      physsimSpeedHandler.notifyIterationEnds(agentSimIterationNumber)
    }
    SimulationResult(
      iteration = currentPhysSimIter,
      travelTime = travelTimeCalculator.getLinkTravelTimes,
      eventTypeToNumberOfMessages = eventTypeCounter.getStats,
      carTravelTimeStats = carTravelTimeHandler.compute
    )
  }

  private def getJDEQSimulation(
    jdeqSimScenario: Scenario,
    jdeqsimEvents: EventsManager,
    maybeRoadCapacityAdjustmentFunction: Option[RoadCapacityAdjustmentFunction],
    simName: String
  ): Mobsim = {
    val config = new JDEQSimConfigGroup
    val flowCapacityFactor = beamConfig.beam.physsim.flowCapacityFactor
    config.setFlowCapacityFactor(flowCapacityFactor)
    config.setStorageCapacityFactor(beamConfig.beam.physsim.storageCapacityFactor)
    config.setSimulationEndTime(beamConfig.matsim.modules.qsim.endTime)
    logger.info(s"Physsim name = $simName, qsim.endTime = ${config.getSimulationEndTimeAsString}")
    simName match {
      case "BPRSIM" =>
        val bprCfg = BPRSimConfig(
          config.getSimulationEndTime,
          1,
          0,
          getTravelTimeFunction(
            beamConfig.beam.physsim.bprsim.travelTimeFunction,
            beamConfig.beam.physsim.flowCapacityFactor
          )
        )
        new BPRSimulation(jdeqSimScenario, bprCfg, jdeqsimEvents)
      case "PARBPRSIM" =>
        val numberOfClusters = beamConfig.beam.physsim.parbprsim.numberOfClusters
        if (numberOfClusters <= 0) {
          throw new IllegalArgumentException("number of clusters must be greater then zero")
        }
        val syncInterval = beamConfig.beam.physsim.parbprsim.syncInterval
        if (syncInterval <= 0) {
          throw new IllegalArgumentException("sync interval must be greater then zero")
        }
        val bprCfg = BPRSimConfig(
          config.getSimulationEndTime,
          numberOfClusters,
          syncInterval,
          getTravelTimeFunction(
            beamConfig.beam.physsim.bprsim.travelTimeFunction,
            beamConfig.beam.physsim.flowCapacityFactor
          )
        )
        new ParallelBPRSimulation(jdeqSimScenario, bprCfg, jdeqsimEvents)
      case "JDEQSIM" =>
        maybeRoadCapacityAdjustmentFunction match {
          case Some(roadCapacityAdjustmentFunction) =>
            logger.info("CACC enabled")
            var caccCategoryRoadCount = 0
            for (link <- jdeqSimScenario.getNetwork.getLinks.values.asScala) {
              if (roadCapacityAdjustmentFunction.isCACCCategoryRoad(link)) caccCategoryRoadCount += 1
            }
            logger.info(
              "caccCategoryRoadCount: " + caccCategoryRoadCount + " out of " + jdeqSimScenario.getNetwork.getLinks.values.size
            )
            val caccSettings = new CACCSettings(isCACCVehicle, roadCapacityAdjustmentFunction)
            val speedAdjustmentFactor = beamConfig.beam.physsim.jdeqsim.cacc.speedAdjustmentFactor
            val adjustedMinimumRoadSpeedInMetersPerSecond =
              beamConfig.beam.physsim.jdeqsim.cacc.adjustedMinimumRoadSpeedInMetersPerSecond
            new JDEQSimulation(
              config,
              jdeqSimScenario,
              jdeqsimEvents,
              caccSettings,
              speedAdjustmentFactor,
              adjustedMinimumRoadSpeedInMetersPerSecond
            )

          case None =>
            logger.info("CACC disabled")
            new org.matsim.core.mobsim.jdeqsim.JDEQSimulation(config, jdeqSimScenario, jdeqsimEvents)
        }
      case unknown @ _ => throw new IllegalArgumentException(s"Unknown physsim: $unknown")
    }
  }

  private def getTravelTimeFunction(functionName: String, flowCapacityFactor: Double): (Double, Link, Int) => Double = {
    functionName match {
      case "FREE_FLOW" =>
        (time, link, _) =>
          link.getLength / link.getFreespeed(time)
      case "BPR" =>
        (time, link, volume) =>
          {
            val ftt = link.getLength / link.getFreespeed(time)
            val tmp = volume / (link.getCapacity(time) * flowCapacityFactor)
            ftt * (1 + 1 * tmp * tmp)
          }
      case unknown @ _ => throw new IllegalArgumentException(s"Unknown function name: $unknown")
    }
  }

  def getNoOfBins(binSize: Int): Int = {
    val endTimeStr = beamConfig.matsim.modules.qsim.endTime
    val endTime = Time.parseTime(endTimeStr)
    var numOfTimeBins = endTime / binSize
    numOfTimeBins = Math.floor(numOfTimeBins)
    numOfTimeBins.toInt + 1
  }
}
