import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BalconySatelliteTracker {

    // 1. Exact Coordinates from your Google Earth pin (Bajaj Ji Dreams, Vigyan Nagar)
    private static final double OBSERVER_LAT = 22.6766;
    private static final double OBSERVER_LON = 75.8323;
    private static final double EARTH_RADIUS_KM = 6371.0;

    // 2. Balcony Facing: Directly SOUTH towards the lane (190° azimuth)
    private static final double BALCONY_FACING_AZIMUTH = 190.0;

    // 3. ntfy Phone Push Topic (Subscribe to this exact name in the free ntfy mobile app)
    private static final String NTFY_TOPIC = "indore_balcony_iss_alert";

    // State tracking to prevent duplicate spam notifications
    private static boolean wakeUpAlertSent = false;
    private static boolean balconyPrepAlertSent = false;

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    public static void main(String[] args) {
        System.out.println("==========================================================================");
        System.out.println("   ISS EARLY-MORNING BALCONY SPOTTER (BAJAJ JI DREAMS, INDORE)");
        System.out.println("   Station: " + OBSERVER_LAT + "° N, " + OBSERVER_LON + "° E | Balcony Facing: SOUTH (Lane)");
        System.out.println("   Phone Alarm: https://ntfy.sh/" + NTFY_TOPIC);
        System.out.println("   [Press Ctrl+C in terminal to stop at any time]");
        System.out.println("==========================================================================");

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("hh:mm:ss a");

        while (true) {
            try {
                String json = fetchIssTelemetry();

                if (json != null && !json.isBlank()) {
                    double satLat = parseDouble(json, "\"latitude\":\\s*([-\\d.]+)");
                    double satLon = parseDouble(json, "\"longitude\":\\s*([-\\d.]+)");
                    double satAlt = parseDouble(json, "\"altitude\":\\s*([-\\d.]+)");
                    double satVel = parseDouble(json, "\"velocity\":\\s*([-\\d.]+)");
                    String visibility = parseString(json, "\"visibility\":\\s*\"([^\"]+)\"");

                    double surfaceDistKm = calculateHaversineKm(OBSERVER_LAT, OBSERVER_LON, satLat, satLon);
                    double slantRangeKm = calculateSlantRangeKm(surfaceDistKm, satAlt);
                    double azimuthDeg = calculateAzimuth(OBSERVER_LAT, OBSERVER_LON, satLat, satLon);
                    double elevationDeg = calculateElevation(surfaceDistKm, satAlt, slantRangeKm);

                    ZonedDateTime nowIst = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("Asia/Kolkata"));
                    int hour = nowIst.getHour();
                    boolean isDawnOrDusk = (hour >= 5 && hour <= 6) || (hour >= 18 && hour <= 19);

                    // Compute balcony-relative instruction tailored to your lane view
                    String relativeBearing = getBalconyLookInstruction(azimuthDeg);
                    boolean isVisibleFromBalcony = !relativeBearing.contains("BLOCKED");

                    // STAGE 1: EARLY WAKE-UP ALARM (~4 to 5 minutes before peak)
                    if (surfaceDistKm <= 1800 && surfaceDistKm > 900 && isDawnOrDusk && visibility.equalsIgnoreCase("daylight") && isVisibleFromBalcony && !wakeUpAlertSent) {
                        String title = "⏰ WAKE UP! ISS Approaching Indore!";
                        String body = "Pass visible from your south balcony in ~4 minutes. Head outside!";
                        sendPhoneAlarm(title, body, 5); // Priority 5 rings like an alarm
                        wakeUpAlertSent = true;
                    }

                    // STAGE 2: BALCONY LIVE GUIDANCE (Above roofline > 15°)
                    if (elevationDeg >= 15.0 && isDawnOrDusk && visibility.equalsIgnoreCase("daylight") && isVisibleFromBalcony && !balconyPrepAlertSent) {
                        String title = "👀 LOOK UP NOW!";
                        String body = "Look " + relativeBearing + ", " + Math.round(elevationDeg) + "° UP above the horizon!";
                        sendPhoneAlarm(title, body, 4);
                        balconyPrepAlertSent = true;
                    }

                    // Reset flags after flyby
                    if (surfaceDistKm > 2500) {
                        wakeUpAlertSent = false;
                        balconyPrepAlertSent = false;
                    }

                    // Live console output
                    System.out.printf("[%s] Dist: %.0f km | Elev: %.1f° | Azimuth: %.0f° -> %s | Status: %s%n",
                            nowIst.format(dtf), slantRangeKm, elevationDeg, azimuthDeg, relativeBearing,
                            (elevationDeg > 15.0 && isVisibleFromBalcony && visibility.equalsIgnoreCase("daylight")) ? "★ VISIBLE!" : "Tracking");
                }
            } catch (Exception e) {
                System.out.println("[Notice] Connection poll: " + e.getMessage());
            }

            try {
                Thread.sleep(4000);
            } catch (InterruptedException ie) {
                System.out.println("\n[Terminated]");
                break;
            }
        }
    }

    private static String getBalconyLookInstruction(double targetAzimuth) {
        // Difference relative to your south-facing balcony (190°)
        double diff = (targetAzimuth - BALCONY_FACING_AZIMUTH + 360.0) % 360.0;

        if (diff >= 340 || diff <= 20) {
            return "STRAIGHT AHEAD across the lane";
        } else if (diff > 20 && diff < 80) {
            return (int) diff + "° to your RIGHT (towards West)";
        } else if (diff >= 80 && diff <= 105) {
            return "90° HARD RIGHT along the road";
        } else if (diff > 280 && diff < 340) {
            return (int) (360 - diff) + "° to your LEFT (towards East)";
        } else if (diff >= 255 && diff <= 280) {
            return "90° HARD LEFT along the road";
        } else {
            return "BLOCKED BY BUILDING (Behind balcony / North side)";
        }
    }

    private static void sendPhoneAlarm(String title, String message, int priority) {
        new Thread(() -> {
            try {
                URL url = new URI("https://ntfy.sh/" + NTFY_TOPIC).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Title", title);
                conn.setRequestProperty("Priority", String.valueOf(priority));
                conn.setRequestProperty("Tags", "satellite,alarm_clock");

                byte[] out = message.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(out.length);
                conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
                conn.connect();

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {
            }
        }).start();
    }

    private static String fetchIssTelemetry() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.wheretheiss.at/v1/satellites/25544"))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return response.body();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static double calculateHaversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double calculateSlantRangeKm(double surfaceDistKm, double satAltKm) {
        double theta = surfaceDistKm / EARTH_RADIUS_KM;
        return Math.sqrt(EARTH_RADIUS_KM * EARTH_RADIUS_KM + Math.pow(EARTH_RADIUS_KM + satAltKm, 2)
                - 2 * EARTH_RADIUS_KM * (EARTH_RADIUS_KM + satAltKm) * Math.cos(theta));
    }

    private static double calculateAzimuth(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaLambda = Math.toRadians(lon2 - lon1);
        double y = Math.sin(deltaLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    private static double calculateElevation(double surfaceDistKm, double satAltKm, double slantRangeKm) {
        double theta = surfaceDistKm / EARTH_RADIUS_KM;
        double r2 = EARTH_RADIUS_KM + satAltKm;
        double cosElev = (r2 * Math.sin(theta)) / slantRangeKm;
        return Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, cosElev))) - (Math.PI / 2.0));
    }

    private static double parseDouble(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String parseString(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? m.group(1) : "";
    }
}