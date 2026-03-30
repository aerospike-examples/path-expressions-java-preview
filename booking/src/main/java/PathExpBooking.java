import java.util.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.Record;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.cdt.CTX;
import com.aerospike.client.cdt.CdtOperation;
import com.aerospike.client.cdt.ListReturnType;
import com.aerospike.client.cdt.MapReturnType;
import com.aerospike.client.cdt.SelectFlags;
import com.aerospike.client.exp.CdtExp;
import com.aerospike.client.exp.Exp;
import com.aerospike.client.exp.ExpOperation;
import com.aerospike.client.exp.ExpReadFlags;
import com.aerospike.client.exp.Expression;
import com.aerospike.client.exp.ListExp;
import com.aerospike.client.exp.LoopVarPart;
import com.aerospike.client.exp.MapExp;

/**
 * Verify that all three path expression implementations return the same result.
 *
 * Data is parsed from an embedded minified JSON string (see booking.json for readable version)
 * and stored in a single map bin "doc".
 *
 * Data structure:
 *   bin "doc" (map, long keys) ->
 *     10001 -> {rates: [{Id:1, a:2, beta:3.0, ...}, {Id:2, a:2, beta:0.0, ...}], e:4, isDeleted:false, time:1795647328}
 *     10002 -> {rates: [{Id:1, ...}], e:4, isDeleted:true,  time:1795647328}
 *     10003 -> {rates: [{Id:1, ...}], e:4, isDeleted:false, time:1764140184}
 *
 * Filters:
 *   exp1: time > threshold (1780000000)
 *   exp2: isDeleted == false
 *   exp3: beta > 0.0
 *   roomIds IN [10001, 10003]
 *
 * Expected: only room 10001 passes all filters, with only the first rate (beta=3.0).
 */
public class PathExpBooking {

    static final String HOST = "127.0.0.1";
    static final int PORT = 3000;
    static final boolean USE_SERVICES_ALTERNATE = true;
    // Minified booking.json (see file for readable version)
    static final String JSON_DATA = "{\"10001\":{\"rates\":[{\"Id\":1,\"a\":2,\"beta\":3.0,\"c\":true,\"d\":\"data\"},{\"Id\":2,\"a\":2,\"beta\":0.0,\"c\":true,\"d\":\"data blob\"}],\"e\":4,\"isDeleted\":false,\"time\":1795647328},\"10002\":{\"rates\":[{\"Id\":1,\"a\":2,\"beta\":3.0,\"c\":true,\"d\":\"data blob\"}],\"e\":4,\"isDeleted\":true,\"time\":1795647328},\"10003\":{\"rates\":[{\"Id\":1,\"a\":2,\"beta\":3.0,\"c\":true,\"d\":\"data blob\"}],\"e\":4,\"isDeleted\":false,\"time\":1764140184}}";

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        ClientPolicy cp = new ClientPolicy();
        cp.useServicesAlternate = USE_SERVICES_ALTERNATE;
        try (IAerospikeClient client = new AerospikeClient(cp, HOST, PORT)) {
            System.out.println("Connected to Aerospike " + HOST + ":" + PORT);

            Key key = new Key("test", "pathexp", "ctrip1");
            setupData(client, key);

            List<Long> roomIds = Arrays.asList(10001L, 10003L);
            long timeThreshold = 1780000000L;

            // exp1: time > threshold
            Exp exp1 = Exp.gt(
                MapExp.getByKey(MapReturnType.VALUE, Exp.Type.INT,
                    Exp.val("time"),
                    Exp.mapLoopVar(LoopVarPart.VALUE)),
                Exp.val(timeThreshold));

            // exp2: isDeleted == false
            Exp exp2 = Exp.eq(
                MapExp.getByKey(MapReturnType.VALUE, Exp.Type.BOOL,
                    Exp.val("isDeleted"),
                    Exp.mapLoopVar(LoopVarPart.VALUE)),
                Exp.val(false));

            // exp3: beta > 0.0 (rate-level filter)
            Exp exp3 = Exp.gt(
                MapExp.getByKey(MapReturnType.VALUE, Exp.Type.FLOAT,
                    Exp.val("beta"),
                    Exp.mapLoopVar(LoopVarPart.VALUE)),
                Exp.val(0.0));

            System.out.println("\n=== Implementation 1: Original (CdtOperation.selectByPath) ===");
            Object result1 = runImpl1(client, key, roomIds, exp1, exp2, exp3);
            System.out.println(MAPPER.writeValueAsString(result1));

            System.out.println("\n=== Implementation 2: Preview Optimized (CdtExp + MapExp.getByKeyList) ===");
            Object result2 = runImpl2(client, key, roomIds, exp1, exp2, exp3);
            System.out.println(MAPPER.writeValueAsString(result2));

            System.out.println("\n=== Implementation 3: 8.1.2 Optimized (CTX.mapKeysIn + andFilter) ===");
            Object result3 = runImpl3(client, key, roomIds, exp1, exp2, exp3);
            System.out.println(MAPPER.writeValueAsString(result3));

            System.out.println("\n=== Comparison ===");
            System.out.println("Impl1 == Impl2:  " + Objects.equals(result1, result2));
            if (result3 != null) {
                System.out.println("Impl1 == Impl3:  " + Objects.equals(result1, result3));
                System.out.println("Impl2 == Impl3:  " + Objects.equals(result2, result3));
            }

            client.delete(null, key);
        }
    }

    static void setupData(IAerospikeClient client, Key key) throws Exception {
        client.delete(null, key);

        Map<String, Object> parsed = MAPPER.readValue(JSON_DATA, new TypeReference<>() {});

        // Convert top-level keys from String to Long (room IDs are integers in Aerospike)
        Map<Long, Object> doc = new HashMap<>();
        for (var entry : parsed.entrySet()) {
            doc.put(Long.parseLong(entry.getKey()), entry.getValue());
        }

        client.put(null, key, new Bin("doc", doc));
        System.out.println("Data loaded from embedded JSON. Full record:");

        Record check = client.get(null, key);
        System.out.println(MAPPER.writeValueAsString(check.getMap("doc")));
    }

    /**
     * Implementation 1: Original preview.
     * Uses CdtOperation.selectByPath with ListExp.getByValue for IN-list check.
     */
    static Object runImpl1(IAerospikeClient client, Key key,
                           List<Long> roomIds, Exp exp1, Exp exp2, Exp exp3) {
        // roomId IN roomIds: count of loopVar MAP_KEY in roomIds list > 0
        Exp roomExp = Exp.gt(
            ListExp.getByValue(
                ListReturnType.COUNT,
                Exp.intLoopVar(LoopVarPart.MAP_KEY),
                Exp.val(roomIds)),
            Exp.val(0));

        Operation op = CdtOperation.selectByPath("doc",
            SelectFlags.MATCHING_TREE | SelectFlags.NO_FAIL,
            CTX.allChildrenWithFilter(Exp.and(exp1, exp2, roomExp)),
            CTX.allChildren(),
            CTX.allChildrenWithFilter(exp3));

        Record record = client.operate(null, key, op);
        return record.getValue("doc");
    }

    /**
     * Implementation 2: Preview optimized.
     * Pre-filters with MapExp.getByKeyList, then applies path expression via CdtExp.
     */
    static Object runImpl2(IAerospikeClient client, Key key,
                           List<Long> roomIds, Exp exp1, Exp exp2, Exp exp3) {
        Expression readExp = Exp.build(
            CdtExp.selectByPath(
                Exp.Type.MAP,
                SelectFlags.MATCHING_TREE | SelectFlags.NO_FAIL,
                MapExp.getByKeyList(
                    MapReturnType.ORDERED_MAP,
                    Exp.val(roomIds),
                    Exp.mapBin("doc")),
                CTX.allChildrenWithFilter(Exp.and(exp1, exp2)),
                CTX.allChildren(),
                CTX.allChildrenWithFilter(exp3)));

        Operation op = ExpOperation.read("doc", readExp, ExpReadFlags.DEFAULT);
        Record record = client.operate(null, key, op);
        return record.getValue("doc");
    }

    /**
     * Implementation 3: 8.1.2 optimized.
     * Uses CTX.mapKeysIn + CTX.andFilter via CdtOperation (requires server 8.1.2+).
     * Note: CdtExp.selectByPath does NOT work with mapKeysIn/andFilter contexts;
     * must use CdtOperation.selectByPath instead.
     */
    static Object runImpl3(IAerospikeClient client, Key key,
                           List<Long> roomIds, Exp exp1, Exp exp2, Exp exp3) {
        long[] roomIdArray = roomIds.stream().mapToLong(Long::longValue).toArray();

        try {
            Operation op = CdtOperation.selectByPath("doc",
                SelectFlags.MATCHING_TREE | SelectFlags.NO_FAIL,
                CTX.mapKeysIn(roomIdArray),
                CTX.andFilter(Exp.and(exp1, exp2)),
                CTX.allChildren(),
                CTX.allChildrenWithFilter(exp3));

            Record record = client.operate(null, key, op);
            return record.getValue("doc");
        } catch (AerospikeException e) {
            System.out.println("Server error: " + e.getMessage());
            System.out.println("  ResultCode: " + e.getResultCode());
            return null;
        }
    }
}
