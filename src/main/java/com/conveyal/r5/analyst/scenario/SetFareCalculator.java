package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.profile.GreedyFareCalculator;
import com.conveyal.r5.transit.TransportNetwork;

/**
 * Set the fare calculator on a transport network.
 */
public class SetFareCalculator extends Modification {
    public GreedyFareCalculator fareCalculator;

    @Override
    public String getType() {
        return "set-fare-calculator";
    }

    @Override
    public boolean apply(TransportNetwork network) {
        // NB will break if applied more than once, but don't think that should happen
        network.fareCalculator = this.fareCalculator;
        network.fareCalculator.transitLayer = network.transitLayer;
        return false;
    }

    @Override
    public int getSortOrder() {
        return 100;
    }
}
