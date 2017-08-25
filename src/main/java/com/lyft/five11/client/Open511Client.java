package com.lyft.five11.client;

import com.google.transit.realtime.GtfsRealtime;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.io.IOException;

public class Open511Client {

    private static final String TRIP_UPDATE_URL = "http://api.511.org/transit/tripUpdates?api_key=%s&agency=%s";
    private static final String VEHICLE_POSITION_URL = "http://api.511.org/transit/vehiclePositions?api_key=%s&agency=%s";

    private String apiKey;
    public Open511Client(String apiKey) {
        this.apiKey = apiKey;
    }

    public GtfsRealtime.FeedMessage getTripUpdates(String agency) throws IOException {
        String uri = String.format(TRIP_UPDATE_URL, apiKey, agency);
        return getFeedMessage(uri);
    }

    public GtfsRealtime.FeedMessage getVehiclePosition(String agency) throws IOException {
        String uri = String.format(VEHICLE_POSITION_URL, apiKey, agency);
       return getFeedMessage(uri);
    }

    private GtfsRealtime.FeedMessage getFeedMessage(String uri) throws IOException {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        try {
            HttpGet httpGet = new HttpGet(uri);
            return httpClient.execute(httpGet, (httpResponse -> GtfsRealtime.FeedMessage.parseFrom(httpResponse.getEntity().getContent())));
        } finally {
            httpClient.close();
        }
    }
}
