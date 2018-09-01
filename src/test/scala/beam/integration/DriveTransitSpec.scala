package beam.integration

import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.tags.{ExcludeRegular, Periodic}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent, PersonDepartureEvent}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{Matchers, WordSpecLike}

/**
  * Created by colinsheppard 2018-05-14
  */
class DriveTransitSpec extends WordSpecLike with Matchers with BeamHelper {

  /*
   * This test passes, but it is slow b/c it runs sf-light-1k so ignoring for now. When we actually run "Periodic" tests
   * in a periodic fashion, this can be un-ignored. -CS
   */
  "DriveTransit trips" must {
    "run to completion" taggedAs (Periodic, ExcludeRegular) in {
      val config = testConfig("test/input/sf-light/sf-light-1k.conf")
        .withValue(
          "beam.agentsim.agents.modalBehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogit")
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept",
          ConfigValueFactory.fromAnyRef(9999)
        )
        .withValue(
          "beam.outputs.events.overrideWritingLevels",
          ConfigValueFactory.fromAnyRef(
            "org.matsim.api.core.v01.events.ActivityEndEvent:REGULAR, org.matsim.api.core.v01.events.ActivityStartEvent:REGULAR, org.matsim.api.core.v01.events.PersonEntersVehicleEvent:REGULAR, org.matsim.api.core.v01.events.PersonLeavesVehicleEvent:REGULAR, beam.agentsim.events.ModeChoiceEvent:VERBOSE, beam.agentsim.events.PathTraversalEvent:VERBOSE, org.matsim.api.core.v01.events.PersonDepartureEvent:VERBOSE, org.matsim.api.core.v01.events.PersonArrivalEvent:VERBOSE"
          )
        )
        .withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(0))
      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig = configBuilder.buildMatSamConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig = BeamConfig(config)

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
      val scenario =
        ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
      val networkCoordinator = new NetworkCoordinator(beamConfig)
      networkCoordinator.loadNetwork()
      scenario.setNetwork(networkCoordinator.network)
      var nDepartures = 0
      var nArrivals = 0
      val injector = org.matsim.core.controler.Injector.createInjector(
        scenario.getConfig,
        new AbstractModule() {
          override def install(): Unit = {
            install(module(config, scenario, networkCoordinator))
            addEventHandlerBinding().toInstance(new BasicEventHandler {
              override def handleEvent(event: Event): Unit = {
                event match {
                  case depEvent: PersonDepartureEvent
                      if depEvent.getLegMode.equalsIgnoreCase("drive_transit") =>
                    nDepartures = nDepartures + 1
                  case arrEvent: PersonArrivalEvent
                      if arrEvent.getLegMode.equalsIgnoreCase("drive_transit") =>
                    nArrivals = nArrivals + 1
                  case _ =>
                }
              }
            })
          }
        }
      )
      val controler = injector.getInstance(classOf[BeamServices]).controler
      controler.run()
      assert(nDepartures == nArrivals)
    }
  }

}
