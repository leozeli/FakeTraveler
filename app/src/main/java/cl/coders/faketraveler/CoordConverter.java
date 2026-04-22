package cl.coders.faketraveler;

/**
 * Converts between WGS-84 (GPS) and GCJ-02 (AMap/Gaode/Google China) coordinate systems.
 *
 * AMap returns GCJ-02 coordinates on map tap. Android mock GPS expects WGS-84.
 * Outside China's bounding box the transform is a no-op.
 */
final class CoordConverter {

    private static final double PI = Math.PI;
    private static final double AXIS = 6378245.0;
    private static final double ECC = 0.00669342162296594323;

    private CoordConverter() {}

    /** Returns true if the point is within the approximate bounding box of China. */
    static boolean isInChina(double lat, double lng) {
        return lat >= 3.86 && lat <= 53.55 && lng >= 73.66 && lng <= 135.05;
    }

    /** WGS-84 → GCJ-02. Identity outside China. */
    static double[] wgs84ToGcj02(double lat, double lng) {
        if (!isInChina(lat, lng)) return new double[]{lat, lng};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - ECC * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = dLat * 180.0 / ((AXIS * (1 - ECC)) / (magic * sqrtMagic) * PI);
        dLng = dLng * 180.0 / (AXIS / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    /**
     * GCJ-02 → WGS-84 via iterative fixed-point inversion.
     * ~0.01m residual after 10 iterations. Identity outside China.
     */
    static double[] gcj02ToWgs84(double gcjLat, double gcjLng) {
        if (!isInChina(gcjLat, gcjLng)) return new double[]{gcjLat, gcjLng};
        double wgsLat = gcjLat;
        double wgsLng = gcjLng;
        for (int i = 0; i < 10; i++) {
            double[] gcj = wgs84ToGcj02(wgsLat, wgsLng);
            wgsLat -= gcj[0] - gcjLat;
            wgsLng -= gcj[1] - gcjLng;
        }
        return new double[]{wgsLat, wgsLng};
    }

    private static double transformLat(double lng, double lat) {
        double ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat
                + 0.1 * lng * lat + 0.2 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lat * PI) + 40.0 * Math.sin(lat / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(lat / 12.0 * PI) + 320.0 * Math.sin(lat * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double lng, double lat) {
        double ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng
                + 0.1 * lng * lat + 0.1 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lng * PI) + 40.0 * Math.sin(lng / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(lng / 12.0 * PI) + 300.0 * Math.sin(lng / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}
