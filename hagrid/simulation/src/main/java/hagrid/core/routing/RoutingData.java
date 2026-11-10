package hagrid.core.routing;

import hagrid.core.util.Hub;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import java.io.*;
import java.lang.reflect.Array;
import java.security.*;
import java.time.*;
import java.util.*;

/** Copies supported routing attributes and hashes structured values without a giant serialized network buffer. */
final class RoutingData {
    private RoutingData() {}
    static Coord coord(Coord value) {
        return value.hasZ() ? new Coord(value.getX(), value.getY(), value.getZ()) : new Coord(value.getX(), value.getY());
    }
    static Object copy(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal || value instanceof Enum<?> || value instanceof Id<?>
                || value instanceof LocalDate || value instanceof LocalDateTime || value instanceof Instant || value instanceof Duration
                || value instanceof UUID) return value;
        if (value instanceof Coord c) return coord(c);
        if (value instanceof Hub hub && value.getClass() == Hub.class) {
            var result = new Hub(hub);
            hub.getAttributes().getAsMap().forEach((key,item) -> result.getAttributes().putAttribute(key,copy(item)));
            return result;
        }
        if (value instanceof DisallowedNextLinks restrictions) return restrictions.copy();
        if (value instanceof Map<?,?> map) {
            var result = new LinkedHashMap<Object,Object>();
            map.forEach((key, item) -> result.put(copy(key),copy(item)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(RoutingData::copy).toList());
        if (value instanceof Set<?> set) {
            var result = new LinkedHashSet<Object>();
            set.forEach(item -> result.add(copy(item)));
            return Collections.unmodifiableSet(result);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object result = Array.newInstance(value.getClass().getComponentType(),length);
            for (int i=0;i<length;i++) Array.set(result,i,copy(Array.get(value,i)));
            return result;
        }
        throw new IllegalArgumentException("Unsupported mutable routing attribute: " + value.getClass().getName());
    }
    @FunctionalInterface interface Writer { void write(DataOutputStream out) throws IOException; }
    static String hash(Writer writer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try(var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))) { writer.write(out); }
            return HexFormat.of().formatHex(digest.digest());
        } catch(IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot fingerprint routing data",e); }
    }
    static String hashValues(Object... values) { return hash(out -> { for(Object value:values) write(out,value); }); }
    static void write(DataOutputStream out, Object value) throws IOException {
        if(value==null) { out.writeByte(0); return; }
        out.writeByte(1);
        if(value instanceof Map<?,?> map) {
            text(out,"map"); out.writeInt(map.size());
            for(var entry:map.entrySet().stream().sorted(Comparator.comparing(e -> stableKey(e.getKey()))).toList()) {
                write(out,entry.getKey()); write(out,entry.getValue());
            }
        } else if(value instanceof Set<?> set) {
            text(out,"set"); out.writeInt(set.size());
            for(Object item:set.stream().sorted(Comparator.comparing(RoutingData::stableKey)).toList()) write(out,item);
        } else if(value instanceof Collection<?> collection) {
            text(out,"list"); out.writeInt(collection.size()); for(Object item:collection) write(out,item);
        } else if(value.getClass().isArray()) {
            text(out,value.getClass().getName()); out.writeInt(Array.getLength(value));
            for(int i=0;i<Array.getLength(value);i++) write(out,Array.get(value,i));
        } else if(value instanceof Coord c) {
            text(out,"coord"); out.writeDouble(c.getX()); out.writeDouble(c.getY()); out.writeBoolean(c.hasZ());
            if(c.hasZ()) out.writeDouble(c.getZ());
        } else if(value instanceof Hub hub && value.getClass() == Hub.class) {
            text(out,"hagrid-hub-v1");
            write(out,Arrays.asList(hub.getId(),hub.getProvider(),hub.getAddress(),hub.getType(),hub.getCoord(),
                    hub.getLink(),hub.getAssignedSupplyDemand(),hub.getCapacityLimit(),hub.isCapacityEnabled(),
                    hub.getWLSupplyDemandByProvider(),hub.getAttributes().getAsMap()));
        } else if(value instanceof DisallowedNextLinks restrictions) {
            text(out,"turn-restrictions"); write(out,restrictions.getAsMap());
        } else if(value instanceof Optional<?> optional) {
            text(out,"optional"); write(out,optional.orElse(null));
        } else if(value instanceof OptionalInt optional) {
            text(out,"optional-int"); out.writeBoolean(optional.isPresent()); if(optional.isPresent()) out.writeInt(optional.getAsInt());
        } else if(value.getClass().isRecord()) {
            text(out,value.getClass().getName());
            for(var field:value.getClass().getRecordComponents()) {
                text(out,field.getName());
                try { write(out,field.getAccessor().invoke(value)); }
                catch(ReflectiveOperationException e) { throw new IllegalArgumentException("Cannot fingerprint record " + value.getClass().getName(),e); }
            }
        } else {
            copy(value); // Reject opaque mutable values instead of hashing an identity-based toString().
            text(out,value instanceof Id<?> ? "id" : value.getClass().getName()); text(out,value.toString());
        }
    }
    private static String stableKey(Object value) { return value==null ? "null" : value.getClass().getName()+":"+value; }
    private static void text(DataOutputStream out,String value) throws IOException {
        byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
}