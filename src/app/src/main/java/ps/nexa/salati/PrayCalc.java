package ps.nexa.salati;

import java.util.HashMap;
import java.util.Map;

/** Prayer-time calculation (adapted from the PrayTimes.org algorithm). Pure Java, no Android imports. */
public final class PrayCalc {

    public static final class Method {
        public final String id, name;
        public final double fajr, isha;      // angles
        public final double ishaMin;         // >0 → isha = maghrib + minutes (angle ignored)
        public final double maghribAngle;    // >0 → maghrib by angle, else sunset
        Method(String id, String name, double fajr, double isha, double ishaMin, double maghribAngle) {
            this.id = id; this.name = name; this.fajr = fajr; this.isha = isha; this.ishaMin = ishaMin; this.maghribAngle = maghribAngle;
        }
    }

    public static final Method[] METHODS = {
            new Method("gaza", "تقويم قطاع غزة (مطابق لتقويم المساجد)", 19.5, 17.5, 0, 0),
            new Method("egypt", "الهيئة المصرية العامة للمساحة", 19.5, 17.5, 0, 0),
            new Method("mwl", "رابطة العالم الإسلامي", 18, 17, 0, 0),
            new Method("makkah", "أم القرى (مكة المكرمة)", 18.5, 0, 90, 0),
            new Method("karachi", "جامعة العلوم الإسلامية بكراتشي", 18, 18, 0, 0),
            new Method("isna", "أمريكا الشمالية (ISNA)", 15, 15, 0, 0),
            new Method("dubai", "الإمارات", 18.2, 18.2, 0, 0),
            new Method("kuwait", "الكويت", 18, 17.5, 0, 0),
            new Method("qatar", "قطر", 18, 0, 90, 0),
            new Method("jordan", "الأردن", 18, 18, 0, 0),
            new Method("morocco", "المغرب", 19, 17, 0, 0),
            new Method("algeria", "الجزائر", 18, 17, 0, 0),
            new Method("tunisia", "تونس", 18, 18, 0, 0),
            new Method("turkey", "تركيا (ديانت)", 18, 17, 0, 0),
            new Method("singapore", "جنوب شرق آسيا", 20, 18, 0, 0),
            new Method("france", "فرنسا (UOIF)", 12, 12, 0, 0),
            new Method("russia", "روسيا", 16, 15, 0, 0),
            new Method("tehran", "طهران", 17.7, 14, 0, 4.5),
    };

    private static final Map<String, String> COUNTRY = new HashMap<>();
    static {
        String[][] m = {
                {"egypt", "PS EG SY LB IQ LY SD SS"},
                {"makkah", "SA YE BH OM"},
                {"dubai", "AE"}, {"kuwait", "KW"}, {"qatar", "QA"}, {"jordan", "JO"},
                {"morocco", "MA"}, {"algeria", "DZ"}, {"tunisia", "TN"}, {"turkey", "TR AZ"},
                {"karachi", "PK IN BD AF"}, {"isna", "US CA"},
                {"singapore", "MY SG ID BN"}, {"france", "FR"}, {"russia", "RU"}, {"tehran", "IR"},
        };
        for (String[] r : m) for (String c : r[1].split(" ")) COUNTRY.put(c, r[0]);
    }

    public static String methodForCountry(String cc) {
        if (cc == null) return "mwl";
        String m = COUNTRY.get(cc.toUpperCase());
        return m == null ? "mwl" : m;
    }

    /** Hanafi Asr is the common default in these countries. */
    public static boolean hanafiForCountry(String cc) {
        if (cc == null) return false;
        switch (cc.toUpperCase()) { case "PK": case "IN": case "BD": case "AF": return true; default: return false; }
    }

    /** Inside the Gaza Strip the mosques follow one printed calendar for the whole strip. */
    public static boolean inGaza(double lat, double lng) {
        return lat >= 31.21 && lat <= 31.60 && lng >= 34.20 && lng <= 34.57 && lat - 31.21 <= (lng - 34.20) * 1.25 + 0.06;
    }
    /** reference point and seconds added (then floored) to match the Gaza calendar */
    static final double GAZA_LAT = 31.42, GAZA_LNG = 34.37;
    static final int[] GAZA_SEC = {15, 40, 30, 40, 225, 55};

    public static Method method(String id) {
        for (Method m : METHODS) if (m.id.equals(id)) return m;
        return METHODS[1];
    }

    /* ---------------- math ---------------- */
    private static double dsin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double dcos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double dtan(double d) { return Math.tan(Math.toRadians(d)); }
    private static double darcsin(double x) { return Math.toDegrees(Math.asin(x)); }
    private static double darccos(double x) { return Math.toDegrees(Math.acos(x)); }
    private static double darctan2(double y, double x) { return Math.toDegrees(Math.atan2(y, x)); }
    private static double darccot(double x) { return Math.toDegrees(Math.atan(1 / x)); }
    private static double fix(double a, double b) { a = a - b * Math.floor(a / b); return a < 0 ? a + b : a; }
    private static double fixAngle(double a) { return fix(a, 360); }
    private static double fixHour(double a) { return fix(a, 24); }
    private static double timeDiff(double t1, double t2) { return fixHour(t2 - t1); }

    private static double julian(int y, int m, int d) {
        if (m <= 2) { y -= 1; m += 12; }
        double A = Math.floor(y / 100.0);
        double B = 2 - A + Math.floor(A / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + B - 1524.5;
    }

    private double lat, lng, jDate;

    private double[] sunPosition(double jd) {
        double D = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * D);
        double q = fixAngle(280.459 + 0.98564736 * D);
        double L = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * D;
        double RA = darctan2(dcos(e) * dsin(L), dcos(L)) / 15;
        double eqt = q / 15 - fixHour(RA);
        double decl = darcsin(dsin(e) * dsin(L));
        return new double[]{decl, eqt};
    }

    private double midDay(double t) { double eqt = sunPosition(jDate + t)[1]; return fixHour(12 - eqt); }

    private double sunAngleTime(double angle, double t, boolean ccw) {
        double decl = sunPosition(jDate + t)[0];
        double noon = midDay(t);
        double T = 1.0 / 15 * darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat)));
        return noon + (ccw ? -T : T);
    }

    private double asrTime(double factor, double t) {
        double decl = sunPosition(jDate + t)[0];
        double angle = -darccot(factor + dtan(Math.abs(lat - decl)));
        return sunAngleTime(angle, t, false);
    }

    /**
     * @return hours (local) for fajr, sunrise, dhuhr, asr, maghrib, isha
     */
    public static double[] compute(int y, int mo, int d, double lat, double lng, double tzHours,
                                   Method m, boolean hanafi, double ishaMinOverride) {
        PrayCalc c = new PrayCalc();
        c.lat = lat; c.lng = lng;
        c.jDate = julian(y, mo, d) - lng / (15 * 24.0);
        double[] t = {5, 6, 12, 13, 18, 18, 18}; // fajr sunrise dhuhr asr sunset maghrib isha
        for (int i = 0; i < 1; i++) {
            double[] p = {t[0] / 24, t[1] / 24, t[2] / 24, t[3] / 24, t[4] / 24, t[5] / 24, t[6] / 24};
            t[0] = c.sunAngleTime(m.fajr, p[0], true);
            t[1] = c.sunAngleTime(0.833, p[1], true);
            t[2] = c.midDay(p[2]);
            t[3] = c.asrTime(hanafi ? 2 : 1, p[3]);
            t[4] = c.sunAngleTime(0.833, p[4], false);
            t[5] = m.maghribAngle > 0 ? c.sunAngleTime(m.maghribAngle, p[5], false) : t[4];
            t[6] = m.ishaMin > 0 ? 0 : c.sunAngleTime(m.isha, p[6], false);
        }
        for (int i = 0; i < 7; i++) t[i] += tzHours - lng / 15;
        double ishaMin = ishaMinOverride > 0 ? ishaMinOverride : m.ishaMin;
        if (ishaMin > 0) t[6] = t[5] + ishaMin / 60.0;
        // high latitudes: angle-based
        double night = timeDiff(t[4], t[1]);
        t[0] = adjustHL(t[0], t[1], m.fajr, night, true);
        if (ishaMin <= 0) t[6] = adjustHL(t[6], t[4], m.isha, night, false);
        if (m.maghribAngle > 0) t[5] = adjustHL(t[5], t[4], m.maghribAngle, night, false);
        return new double[]{t[0], t[1], t[2], t[3], t[5], t[6]};
    }

    private static double adjustHL(double time, double base, double angle, double night, boolean ccw) {
        double portion = angle / 60.0 * night;
        double diff = ccw ? timeDiff(time, base) : timeDiff(base, time);
        if (Double.isNaN(time) || diff > portion) time = base + (ccw ? -portion : portion);
        return time;
    }

    /** quick self-check: java PrayCalc lat lng tz y m d method */
    public static void main(String[] a) {
        double[] r = compute(Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5]),
                Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), method(a[6]), a.length > 7, 0);
        StringBuilder sb = new StringBuilder();
        for (double h : r) {
            int mins = (int) Math.floor(h * 60 + 0.5);
            sb.append(String.format("%02d:%02d ", mins / 60, mins % 60));
        }
        System.out.println(sb.toString().trim());
    }
}
