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

    // 1. Observer Coordinates: Bajaj Ji Dreams, Vigyan Nagar, Indore
    private static final double OBSERVER_LAT = 22.6766;
    private static final double OBSERVER_LON = 75.8323;
    private static final double EARTH_RADIUS_KM = 6371.0;

    // 2. Balcony orientation: SOUTH toward the lane (~190° azimuth)
    private static final double BALCONY_FACING_AZIMUTH = 190.0;

    // 3. ntfy Phone Push Topic
    private static final String NTFY_TOPIC = "indore_balcony_iss_alert";

    // Track stages to prevent duplicate alerts
    private static boolean wakeUpAlertSent = false;
    private static boolean balconyPrepAlertSent = false;
    private static boolean livePassAlertSent = false;

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("hh:mm:ss a");

    public static void main(String[] args) {
        System.out.println("==========================================================================");
        System.out.println("   ISS DAWN BALCONY DISPATCHER & ALARM ENGINE");
        System.out.println("   Location: Bajaj Ji Dreams, Indore (" + OBSERVER_LAT + "° N, " + OBSERVER_LON + "° E)");
        System.out.println("   Balcony View: SOUTH across the lane (" + BALCONY_FACING_AZIMUTH + "°)");
        System.out.println("   Alert Channel: https://ntfy.sh/" + NTFY_TOPIC);
        System.out.println("   [Press Ctrl+C to stop]");
        System.out.println("==========================================================================");

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

                    String relativeLook = getBalconyLookInstruction(azimuthDeg);
                    boolean isVisibleSector = !relativeLook.contains("BLOCKED");
                    boolean isSunlit = visibility.equalsIgnoreCase("daylight");

                    // STAGE 1: WAKE-UP ALARM (~15-20 min window equivalent: approaching regional corridor)
                    if (surfaceDistKm <= 2200 && surfaceDistKm > 1200 && isDawnOrDusk && isSunlit && isVisibleSector && !wakeUpAlertSent) {
                        String title = "⏰ WAKE UP! ISS Approaching Indore!";
                        String body = "Dawn flyby visible from your south balcony in ~15 minutes. Time to get out of bed!";
                        sendPhoneAlarm(title, body, 5, "alarm_clock,satellite");
                        wakeUpAlertSent = true;
                        System.out.println("[DISPATCH] Sent Stage 1 Wake-up Alarm.");
                    }

                    // STAGE 2: 2-MINUTE WARNING (Approaching local horizon, getting into position)
                    if (surfaceDistKm <= 900 && surfaceDistKm > 450 && isDawnOrDusk && isSunlit && isVisibleSector && !balconyPrepAlertSent) {
                        String title = "👀 GO TO BALCONY NOW (2 Min Warning)";
                        String body = "Step out to the lane-facing balcony. It will enter your sky view from " + relativeLook + ".";
                        sendPhoneAlarm(title, body, 4, "bell,walking");
                        balconyPrepAlertSent = true;
                        System.out.println("[DISPATCH] Sent Stage 2 Balcony Warning.");
                    }

                    // STAGE 3: LIVE PASS INSTRUCTIONS (Above rooftops, actively visible)
                    if (elevationDeg >= 15.0 && isDawnOrDusk && isSunlit && isVisibleSector && !livePassAlertSent) {
                        String title = "🌟 LOOK UP NOW! ISS Visible!";
                        String body = "Look " + relativeLook + " and " + Math.round(elevationDeg) + "° UP! Look for a silent, steady white beacon.";
                        sendPhoneAlarm(title, body, 5, "star,eyes");
                        livePassAlertSent = true;
                        System.out.println("[DISPATCH] Sent Stage 3 Live Sighting Guide.");
                    }

                    // Reset state flags after satellite passes beyond 2800 km
                    if (surfaceDistKm > 2800) {
                        wakeUpAlertSent = false;
                        balconyPrepAlertSent = false;
                        livePassAlertSent = false;
                    }

                    // Console status
                    System.out.printf("[%s] Dist: %.0f km | Elev: %.1f° | Heading: %.0f° -> %s | Light: %s%n",
                            nowIst.format(TIME_FORMAT), slantRangeKm, elevationDeg, azimuthDeg, relativeLook,
                            isSunlit ? "SUNLIT" : "SHADOW");
                }
            } catch (Exception e) {
                System.out.println("[Poll Note] " + e.getMessage());
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                System.out.println("\n[Terminated]");
                break;
            }
        }
    }

    private static String getBalconyLookInstruction(double targetAzimuth) {
        double diff = (targetAzimuth - BALCONY_FACING_AZIMUTH + 360.0) % 360.0;

        if (diff >= 345 || diff <= 15) {
            return "STRAIGHT AHEAD (South)";
        } else if (diff > 15 && diff < 85) {
            return (int) diff + "° to your RIGHT (towards West)";
        } else if (diff >= 85 && diff <= 105) {
            return "90° HARD RIGHT (West wall)";
        } else if (diff > 275 && diff < 345) {
            return (int) (360 - diff) + "° to your LEFT (towards East)";
        } else if (diff >= 255 && diff <= 275) {
            return "90° HARD LEFT (East wall)";
        } else {
            return "BLOCKED BY BUILDING (North side)";
        }
    }

    private static void sendPhoneAlarm(String title, String message, int priority, String tags) {
        new Thread(() -> {
            try {
                URL url = new URI("https://ntfy.sh/" + NTFY_TOPIC).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Title", title);
                conn.setRequestProperty("Priority", String.valueOf(priority));
                conn.setRequestProperty("Tags", tags);

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