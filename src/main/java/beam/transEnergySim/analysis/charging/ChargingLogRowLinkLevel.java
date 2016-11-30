/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2012 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package beam.transEnergySim.analysis.charging;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import beam.transEnergySim.vehicles.api.Vehicle;

public class ChargingLogRowLinkLevel extends ChargingLogRow {

	public ChargingLogRowLinkLevel(Id<Vehicle> agentId, Id<Link> linkId, double startChargingTime, double chargingDuration,
			double energyChargedInJoule) {
		super();
		this.agentId = agentId;
		this.linkId = linkId;
		this.startChargingTime = startChargingTime;
		this.chargingDuration = chargingDuration;
		this.energyChargedInJoule = energyChargedInJoule;
	}


}
