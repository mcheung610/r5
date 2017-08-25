package com.lyft.five11.client;

import com.google.transit.realtime.GtfsRealtime;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Open511ClientTest {

    private static final Logger logger = LoggerFactory.getLogger(Open511ClientTest.class);

    private String apiKey = "3114fc3d-2bf7-42e7-bd3e-de30d3ae0e43";
    private Open511Client client;

    @Before
    public void before() {
        client = new Open511Client(apiKey);
    }

    @Test
    public void testGetTripUpdates() throws Exception {
        GtfsRealtime.FeedMessage tripUpdate = client.getTripUpdates("caltrain");
        logger.debug("tripUpdate={}", tripUpdate);
    }

    @Test
    public void testGetVehiclePosition() throws Exception {
        GtfsRealtime.FeedMessage vehiclePosition = client.getVehiclePosition("caltrain");
        logger.debug("vehiclePosition={}", vehiclePosition);
    }
}
