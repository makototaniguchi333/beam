package beam.utils.data.ctpp.readers

import beam.utils.data.ctpp.models.{Age, ResidenceGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.Table
import beam.utils.data.ctpp.CTPPParser
import com.typesafe.scalalogging.LazyLogging

import scala.util.{Failure, Success}

class AgeTableReader(pathToData: String, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(pathToData, Table.Age, Some(residenceGeography.level))
    with LazyLogging {
  logger.info(s"Path to table $table is '$pathToCsvTable'")

  def read(): Map[String, Map[Age, Double]] = {
    val ageMap = CTPPParser
      .readTable(pathToCsvTable, geographyLevelFilter)
      .groupBy(x => x.geoId)
      .map {
        case (geoId, xs) =>
          // One geoId contains multiple age ranges
          val allAges = xs.flatMap { entry =>
            val maybeAge = Age(entry.lineNumber) match {
              case Failure(ex) =>
                logger.warn(s"Could not represent $entry as age: ${ex.getMessage}", ex)
                None
              case Success(value) =>
                Some(value -> entry.estimate)
            }
            maybeAge
          }
          geoId -> allAges.toMap
      }
    ageMap
  }

}