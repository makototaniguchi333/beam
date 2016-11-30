package beam.transEnergySim.vehicles.impl;

import org.matsim.api.core.v01.Id;

import beam.transEnergySim.vehicles.api.AbstractHybridElectricVehicle;
import beam.transEnergySim.vehicles.api.Vehicle;
import beam.transEnergySim.vehicles.energyConsumption.EnergyConsumptionModel;

public class PHEV extends AbstractHybridElectricVehicle {

	public PHEV(EnergyConsumptionModel ecm, EnergyConsumptionModel engineECM, double usableBatteryCapacityInJoules, Id<Vehicle> vehicleId) {
		this.electricDriveEnergyConsumptionModel=ecm;
		//TODO replace with real energy consumption model
		this.setHybridDriveEnergyConsumptionModel(ecm);
		this.engineECM=engineECM;
		this.usableBatteryCapacityInJoules=usableBatteryCapacityInJoules;
		this.socInJoules=usableBatteryCapacityInJoules;
		this.vehicleId = vehicleId;
	}
	
	protected void logEngineEnergyConsumption(double energyConsumptionInJoule) {
		
	}

}
