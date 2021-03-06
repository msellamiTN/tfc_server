package uk.ac.cam.tfc_server.util;
    
import io.vertx.core.json.JsonObject;

// Position simply stores a lat/long/timestamp tuple
// and provides some utility methods, such as distance from another Position.
public class Position {
    public double lat;
    public double lng;
    public Long ts;

    public Position()
    {
        lat = 0.0;
        lng = 0.0;
        ts = 0L;
    }

    public Position(JsonObject p)
    {
        lat = p.getDouble("lat");
        lng = p.getDouble("lng");
        ts  = p.getLong("ts",0L);
    }
    
    public Position(double init_lat, double init_lng)
    {
        this(init_lat, init_lng, 0L);
    }

    public Position(double init_lat, double init_lng, Long init_ts)
    {
        lat = init_lat;
        lng = init_lng;
        ts = init_ts;
    }

    public String toString()
    {
        return "{ \"lat\": " + String.valueOf(lat) + "," +
            "\"lng\": " + String.valueOf(lng) + "," +
            "\"ts\": " + String.valueOf(ts) +
            "}";
    }

    public JsonObject toJsonObject()
    {
        JsonObject jo = new JsonObject();
        jo.put("lat", lat);
        jo.put("lng", lng);
        jo.put("ts", ts);
        return jo;
    }
    
    // Return distance in m between positions p1 and p2.
    // lat/longs in e.g. p1.lat etc
    double distance(Position p) {
        //double R = 6378137.0; // Earth's mean radius in meter
        double R = 6380000.0; // Earth's radius at Lat 52 deg in meter
        double dLat = Math.toRadians(p.lat - lat);
        double dLong = Math.toRadians(p.lng - lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(p.lat)) *
                Math.sin(dLong / 2) * Math.sin(dLong / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double d = R * c;
        return d; // returns the distance in meter
    };

}
