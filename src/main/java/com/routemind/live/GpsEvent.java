package com.routemind.live;

import java.time.Instant;

/**
 * One GPS ping from the vehicle stream. Kafka message key = tripId, so all pings
 * for a trip land on the same partition and are processed in order.
 *
 * {"tripId":1516906,"lat":12.9352,"lon":77.6245,"speedKph":24.5,"bearing":90,
 *  "observedAt":"2026-07-15T07:48:05Z"}
 */
public record GpsEvent(long tripId,
                       double lat,
                       double lon,
                       double speedKph,
                       Double bearing,
                       Instant observedAt) {

    public boolean valid() {
        return tripId > 0
                && lat >= -90 && lat <= 90
                && lon >= -180 && lon <= 180
                && observedAt != null;
    }
}
