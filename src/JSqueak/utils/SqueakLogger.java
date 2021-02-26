package JSqueak.utils;


import JSqueak.SqueakConfig;

public class SqueakLogger {

    public static void log_D(String msg) {
        if (SqueakConfig.DEBUG_LOGGING) {
            System.out.println(msg);
        }
    }

    public static void log_E(String msg) {
        if (SqueakConfig.DEBUG_LOGGING) {
            System.err.println(msg);
        }
    }
}
