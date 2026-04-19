import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.Value;
import com.aerospike.client.cdt.CTX;
import com.aerospike.client.cdt.CdtOperation;
import com.aerospike.client.cdt.MapOperation;
import com.aerospike.client.cdt.MapPolicy;
import com.aerospike.client.cdt.MapReturnType;
import com.aerospike.client.cdt.ModifyFlags;
import com.aerospike.client.cdt.SelectFlags;
import com.aerospike.client.exp.Exp;
import com.aerospike.client.exp.Expression;
import com.aerospike.client.exp.LoopVarPart;
import com.aerospike.client.exp.MapExp;
import com.aerospike.client.policy.ClientPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Opportunistic expiry for entries in a map-of-maps: Aerospike does not expire individual map keys.
 * Each inner map stores {@code void_time} (Unix epoch seconds) after which the entry is treated as expired.
 * <p>
 * This demo uses path expressions to (1) return only non-expired entries on read, and (2) remove expired
 * entries when updating the map. The comparison uses {@code Exp.val(now)} built on the client at request time.
 */
public class OpportunisticMapExpiry {

    static final String HOST = "127.0.0.1";
    static final int PORT = 3000;
    static final boolean USE_SERVICES_ALTERNATE = true;

    static final String NS = "test";
    static final String SET = "pathexp";
    static final String USER_KEY = "map-ttl-demo";
    static final String BIN = "entries";

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        ClientPolicy cp = new ClientPolicy();
        cp.useServicesAlternate = USE_SERVICES_ALTERNATE;

        try (IAerospikeClient client = new AerospikeClient(cp, HOST, PORT)) {
            System.out.println("Connected to Aerospike " + HOST + ":" + PORT);

            Key key = new Key(NS, SET, USER_KEY);
            client.truncate(null, NS, SET, null);

            long now = Instant.now().getEpochSecond();

            Map<String, Map<String, Object>> outer = new LinkedHashMap<>();

            Map<String, Object> expired = new HashMap<>();
            expired.put("void_time", (int) (now - 3_600));
            expired.put("label", "expired-entry");
            outer.put("a", expired);

            Map<String, Object> valid = new HashMap<>();
            valid.put("void_time", (int) (now + 86_400));
            valid.put("label", "valid-entry");
            outer.put("b", valid);

            Map<String, Object> longLived = new HashMap<>();
            longLived.put("void_time", (int) (now + 10_000_000));
            longLived.put("label", "also-valid");
            outer.put("c", longLived);

            client.put(null, key, new Bin(BIN, outer));

            printSection("Full record after insert (includes expired key \"a\")");
            Record raw = client.get(null, key);
            System.out.println(MAPPER.writeValueAsString(raw.getValue(BIN)));

            printSection("Read: path expression keeps only entries with void_time > now");
            Record readFiltered = client.operate(null, key,
                CdtOperation.selectByPath(BIN, SelectFlags.MATCHING_TREE,
                    CTX.allChildrenWithFilter(filterValidAt(now))));

            System.out.println(MAPPER.writeValueAsString(readFiltered.getValue(BIN)));

            printSection("Write: remove expired entries, then update a surviving inner map");
            Expression removeExpired = Exp.build(Exp.removeResult());
            client.operate(null, key,
                CdtOperation.modifyByPath(BIN, ModifyFlags.DEFAULT, removeExpired,
                    CTX.allChildrenWithFilter(filterExpiredAt(now))),
                MapOperation.put(MapPolicy.Default, BIN,
                    Value.get("touch"), Value.get(1),
                    CTX.mapKey(Value.get("b"))));

            Record afterWrite = client.get(null, key);
            System.out.println(MAPPER.writeValueAsString(afterWrite.getValue(BIN)));

            client.delete(null, key);
            System.out.println("\nCleanup: record deleted.");
        }
    }

    /** Inner map value at the current path step (each outer map entry's value). */
    static Exp voidTimeOfInnerMap() {
        return MapExp.getByKey(MapReturnType.VALUE, Exp.Type.INT,
            Exp.val("void_time"),
            Exp.mapLoopVar(LoopVarPart.VALUE));
    }

    /** Not expired: void_time is strictly after the evaluation instant (epoch seconds). */
    static Exp filterValidAt(long nowEpochSeconds) {
        return Exp.gt(voidTimeOfInnerMap(), Exp.val(nowEpochSeconds));
    }

    /** Expired: void_time is at or before the evaluation instant. */
    static Exp filterExpiredAt(long nowEpochSeconds) {
        return Exp.le(voidTimeOfInnerMap(), Exp.val(nowEpochSeconds));
    }

    static void printSection(String title) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println(title);
        System.out.println("=".repeat(72));
    }
}
