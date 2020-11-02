package beam.agentsim.infrastructure

import akka.actor.{ActorLogging, Props}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.infrastructure.HierarchicalParkingManager._
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZone.{DefaultParkingZoneId, UbiqiutousParkingAvailability}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class HierarchicalParkingManager(
  tazMap: TAZTreeMap,
  geo: GeoUtils,
  linkToTAZMapping: Map[Link, TAZ],
  parkingZones: Array[ParkingZone[Link]],
  rand: Random,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  boundingBox: Envelope,
  mnlMultiplierParameters: ParkingMNL.ParkingMNLConfig
) extends beam.utils.CriticalActor
    with ActorLogging {

  private val actualParkingZones: Array[ParkingZone[Link]] = HierarchicalParkingManager.collapse(parkingZones)

  private val tazLinks: Map[Id[TAZ], QuadTree[Link]] = createTazLinkQuadTreeMapping(linkToTAZMapping)
  private val (tazParkingZones, linkZoneToTazZoneMap) =
    convertToTazParkingZones(actualParkingZones, linkToTAZMapping.map { case (link, taz) => link.getId -> taz.tazId })
  private val tazZoneSearchTree = ParkingZoneFileUtils.createZoneSearchTree(tazParkingZones)

  private val tazSearchFunctions: ZonalParkingManagerFunctions[TAZ] = new ZonalParkingManagerFunctions[TAZ](
    tazMap.tazQuadTree,
    tazMap.idToTAZMapping,
    identity[TAZ],
    geo,
    tazParkingZones,
    tazZoneSearchTree,
    TAZ.EmergencyTAZId,
    TAZ.DefaultTAZId,
    rand,
    minSearchRadius,
    maxSearchRadius,
    boundingBox,
    mnlMultiplierParameters
  )

  val DefaultParkingZone: ParkingZone[Link] =
    ParkingZone(
      DefaultParkingZoneId,
      LinkLevelOperations.DefaultLinkId,
      ParkingType.Public,
      UbiqiutousParkingAvailability,
      None,
      None
    )

  private val linkZoneSearchMap: Map[Id[Link], Map[ParkingZoneDescription, ParkingZone[Link]]] =
    createLinkZoneSearchMap(actualParkingZones)

  override def receive: Receive = {

    case inquiry: ParkingInquiry =>
      log.debug("Received parking inquiry: {}", inquiry)

      val ParkingZoneSearch.ParkingZoneSearchResult(tazParkingStall, tazParkingZone, _, _, _) =
        tazSearchFunctions.searchForParkingStall(inquiry)

      val (parkingStall: ParkingStall, parkingZone: ParkingZone[Link]) = tazLinks.get(tazParkingZone.geoId) match {
        case Some(linkQuadTree) =>
          val foundZoneDescription = ParkingZoneDescription.describeParkingZone(tazParkingZone)
          val startingPoint = linkQuadTree.getClosest(inquiry.destinationUtm.getX, inquiry.destinationUtm.getY).getCoord
          TAZTreeMap.ringSearch(
            linkQuadTree,
            startingPoint,
            minSearchRadius / 4,
            maxSearchRadius * 5,
            radiusMultiplication = 1.5
          ) { link =>
            for {
              linkZones <- linkZoneSearchMap.get(link.getId)
              zone      <- linkZones.get(foundZoneDescription) if zone.stallsAvailable > 0
            } yield {
              (tazParkingStall.copy(zone.geoId, parkingZoneId = zone.parkingZoneId, locationUTM = link.getCoord), zone)
            }
          } match {
            case Some(foundResult) => foundResult
            case None => //Cannot find required links within the TAZ, this means the links is too far from the starting point
              log.warning(
                "Cannot find link parking stall for taz id {}, foundZoneDescription = {}",
                tazParkingZone.geoId,
                foundZoneDescription
              )
              import scala.collection.JavaConverters._
              val tazLinkZones = for {
                link      <- linkQuadTree.values().asScala.toList
                linkZones <- linkZoneSearchMap.get(link.getId)
                zone      <- linkZones.get(foundZoneDescription) if zone.stallsAvailable > 0
              } yield {
                zone
              }
              log.warning("Actually tazLink zones {}", tazLinkZones)
              lastResortStallAndZone(inquiry.destinationUtm)
          }
        case None => //no corresponding links, this means it's a special zone
          tazParkingStall.geoId match {
            case TAZ.DefaultTAZId =>
              tazParkingStall.copy(geoId = LinkLevelOperations.DefaultLinkId) -> DefaultParkingZone
            case TAZ.EmergencyTAZId =>
              tazParkingStall.copy(geoId = LinkLevelOperations.EmergencyLinkId) -> DefaultParkingZone
            case _ =>
              log.warning("Cannot find TAZ with id {}", tazParkingZone.geoId)
              lastResortStallAndZone(inquiry.destinationUtm)
          }
      }

      // reserveStall is false when agent is only seeking pricing information
      if (inquiry.reserveStall) {

        log.debug(
          s"reserving a ${if (parkingStall.chargingPointType.isDefined) "charging" else "non-charging"} stall for agent ${inquiry.requestId} in parkingZone ${parkingZone.parkingZoneId}"
        )

        ParkingZone.claimStall(parkingZone).value
        ParkingZone.claimStall(tazParkingZone).value
      }

      sender() ! ParkingInquiryResponse(parkingStall, inquiry.requestId)

    case ReleaseParkingStall(parkingZoneId, _) =>
      if (parkingZoneId == ParkingZone.DefaultParkingZoneId) {
        if (log.isDebugEnabled) {
          // this is an infinitely available resource; no update required
          log.debug("Releasing a stall in the default/emergency zone")
        }
      } else if (parkingZoneId < ParkingZone.DefaultParkingZoneId || actualParkingZones.length <= parkingZoneId) {
        if (log.isDebugEnabled) {
          log.debug("Attempting to release stall in zone {} which is an illegal parking zone id", parkingZoneId)
        }
      } else {
        val linkZone = actualParkingZones(parkingZoneId)
        val tazZoneId = linkZoneToTazZoneMap(parkingZoneId)
        val tazZone = tazParkingZones(tazZoneId)
        ParkingZone.releaseStall(linkZone).value
        ParkingZone.releaseStall(tazZone).value
      }
  }

  private def lastResortStallAndZone(location: Location) = {
    val boxAroundRequest = new Envelope(
      location.getX + 2000,
      location.getX - 2000,
      location.getY + 2000,
      location.getY - 2000
    )
    val newStall = ParkingStall.lastResortStall(
      boxAroundRequest,
      rand,
      tazId = TAZ.EmergencyTAZId,
      geoId = LinkLevelOperations.EmergencyLinkId
    )
    newStall -> DefaultParkingZone
  }

}

object HierarchicalParkingManager {

  /**
    * This class "describes" a parking zone (i.e. extended type of parking zone). This allows to search for similar
    * parking zones on other links or TAZes
    * @param parkingType the parking type (Residential, Workplace, Public)
    * @param chargingPointType the charging point type
    * @param pricingModel the pricing model
    */
  case class ParkingZoneDescription(
    parkingType: ParkingType,
    chargingPointType: Option[ChargingPointType],
    pricingModel: Option[PricingModel]
  )

  object ParkingZoneDescription {

    def describeParkingZone(zone: ParkingZone[_]): ParkingZoneDescription = {
      new ParkingZoneDescription(zone.parkingType, zone.chargingPointType, zone.pricingModel)
    }
  }

  def props(
    tazMap: TAZTreeMap,
    geo: GeoUtils,
    linkToTAZMapping: Map[Link, TAZ],
    parkingZones: Array[ParkingZone[Link]],
    rand: Random,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    boundingBox: Envelope,
    mnlMultiplierParameters: ParkingMNL.ParkingMNLConfig
  ): Props = {
    Props(
      new HierarchicalParkingManager(
        tazMap,
        geo,
        linkToTAZMapping,
        parkingZones,
        rand,
        minSearchRadius,
        maxSearchRadius,
        boundingBox,
        mnlMultiplierParameters,
      )
    )
  }

  /**
    * Makes TAZ level parking data from the link level parking data
    * @param parkingZones link level parking zones
    * @param linkToTAZMapping link to TAZ map
    * @return taz parking zones, link zone id -> taz zone id map
    */
  private[infrastructure] def convertToTazParkingZones(
    parkingZones: Array[ParkingZone[Link]],
    linkToTAZMapping: Map[Id[Link], Id[TAZ]]
  ): (Array[ParkingZone[TAZ]], Map[Int, Int]) = {
    val tazZonesMap = parkingZones.groupBy(zone => linkToTAZMapping(zone.geoId))

    // list of parking zone description including TAZ and link zones
    val tazZoneDescriptions = tazZonesMap.flatMap {
      case (tazId, currentTazParkingZones) =>
        val tazLevelZoneDescriptions = currentTazParkingZones.groupBy(ParkingZoneDescription.describeParkingZone)
        tazLevelZoneDescriptions.map {
          case (description, linkZones) =>
            (tazId, description, linkZones)
        }
    }
    //generate taz parking zones
    val tazZones = tazZoneDescriptions.zipWithIndex.map {
      case ((tazId, description, linkZones), id) =>
        val numStalls = Math.min(linkZones.map(_.maxStalls.toLong).sum, Int.MaxValue).toInt
        new ParkingZone[TAZ](
          id,
          tazId,
          description.parkingType,
          numStalls,
          numStalls,
          description.chargingPointType,
          description.pricingModel
        )
    }
    //link zone to taz zone map
    val linkZoneToTazZoneMap = tazZones
      .zip(tazZoneDescriptions.map { case (_, _, linkZones) => linkZones })
      .flatMap { case (tazZone, linkZones) => linkZones.map(_.parkingZoneId -> tazZone.parkingZoneId) }
      .toMap
    (tazZones.toArray, linkZoneToTazZoneMap)
  }

  private def createLinkZoneSearchMap(
    parkingZones: Array[ParkingZone[Link]]
  ): Map[Id[Link], Map[ParkingZoneDescription, ParkingZone[Link]]] = {
    parkingZones.foldLeft(Map.empty: Map[Id[Link], Map[ParkingZoneDescription, ParkingZone[Link]]]) {
      (accumulator, zone) =>
        val zoneDescription = ParkingZoneDescription.describeParkingZone(zone)
        val parking = accumulator.getOrElse(zone.geoId, Map())
        accumulator.updated(zone.geoId, parking.updated(zoneDescription, zone))
    }
  }

  def createTazLinkQuadTreeMapping(linkToTAZMapping: Map[Link, TAZ]): Map[Id[TAZ], QuadTree[Link]] = {
    val tazToLinks = invertMap(linkToTAZMapping)
    tazToLinks.map {
      case (taz, links) =>
        taz.tazId -> LinkLevelOperations.getLinkTreeMap(links.toSeq)
    }
  }

  private def invertMap(linkToTAZMapping: Map[Link, TAZ]): Map[TAZ, Set[Link]] = {
    linkToTAZMapping.groupBy(_._2).mapValues(_.keys.toSet)
  }

  /**
    * Collapses multiple similar parking zones in the same Link to a single zone
    * @param parkingZones the parking zones
    * @return collapsed parking zones
    */
  def collapse(parkingZones: Array[ParkingZone[Link]]): Array[ParkingZone[Link]] =
    parkingZones
      .groupBy(_.geoId)
      .flatMap {
        case (linkId, zones) =>
          zones
            .groupBy(ParkingZoneDescription.describeParkingZone)
            .map { case (descr, linkZones) => (linkId, descr, linkZones.map(_.maxStalls.toLong).sum) }
      }
      .filter { case (_, _, maxStalls) => maxStalls > 0 }
      .zipWithIndex
      .map {
        case ((linkId, description, maxStalls), id) =>
          val numStalls = Math.min(maxStalls, Int.MaxValue).toInt
          new ParkingZone[Link](
            id,
            linkId,
            description.parkingType,
            numStalls,
            numStalls,
            description.chargingPointType,
            description.pricingModel
          )
      }
      .toArray
}
