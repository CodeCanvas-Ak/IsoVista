package util;

import history.serializer.ElleHistorySerializer;
import history.serializer.HistorySerializer;
import history.serializer.TextHistorySerializer;

import java.util.Locale;

public class HistorySerializerFactory {
    public static HistorySerializer getHistorySerializer(String type) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "text":
                return new TextHistorySerializer();
            case "elle":
                return new ElleHistorySerializer();
            default:
                throw new IllegalArgumentException("Unsupported history type: " + type);
        }
    }
}
