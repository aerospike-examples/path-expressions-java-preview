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
import com.aerospike.client.cdt.MapReturnType;
import com.aerospike.client.cdt.SelectFlags;
import com.aerospike.client.exp.Exp;
import com.aerospike.client.exp.LoopVarPart;
import com.aerospike.client.exp.MapExp;

/**
 * Experiment: can two CTX.mapKeysIn/andFilter pairs be chained at successive
 * nesting levels in a single selectByPath call?
 *
 * Data (all string keys):
 *   "100" -> { "beta": 2.0, "1001": {"a":1,"b":2,"yes":1}, "1002": {"z":26}, "1003": {"f":6} }
 *   "200" -> { "beta": 3.0, "2001": {"d":4,"e":5,"yes":1}, "2002": {"y":25} }
 *
 * Query:
 *   CTX.mapKeysIn("100"),              // level 1: select top-level key
 *   CTX.andFilter(beta > 0),           // level 1: filter by beta field
 *   CTX.mapKeysIn("1001", "1002"),     // level 2: select inner keys
 *   CTX.andFilter(yes == 1)            // level 2: filter by "yes" field
 *
 * Expected result: {"100": {"1001": {"a": 1, "b": 2, "yes": 1}}}
 */
public class ChainedMapKeysInExp {

    static final String HOST = "127.0.0.1";
    static final int PORT = 3000;
    static final boolean USE_SERVICES_ALTERNATE = true;
    // Minified chained.json (see file for readable version)
    static final String JSON_DATA = "{\"100\":{\"beta\":2.0,\"1001\":{\"a\":1,\"b\":2,\"yes\":1},\"1002\":{\"z\":26},\"1003\":{\"f\":6}},\"200\":{\"beta\":3.0,\"2001\":{\"d\":4,\"e\":5,\"yes\":1},\"2002\":{\"y\":25}}}";

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        ClientPolicy cp = new ClientPolicy();
        cp.useServicesAlternate = USE_SERVICES_ALTERNATE;
        try (IAerospikeClient client = new AerospikeClient(cp, HOST, PORT)) {
            System.out.println("Connected to Aerospike " + HOST + ":" + PORT);

            Key key = new Key("test", "pathexp", "chained1");
            setupData(client, key);

            // beta > 0.0 (applied at a level where entries have a "beta" field)
            Exp betaGtZero = Exp.gt(
                MapExp.getByKey(MapReturnType.VALUE, Exp.Type.FLOAT,
                    Exp.val("beta"),
                    Exp.mapLoopVar(LoopVarPart.VALUE)),
                Exp.val(0.0));

            // "yes" == 1 (applied at a level where entries may have a "yes" field)
            Exp saysYes = Exp.eq(
                MapExp.getByKey(MapReturnType.VALUE, Exp.Type.INT,
                    Exp.val("yes"),
                    Exp.mapLoopVar(LoopVarPart.VALUE)),
                Exp.val(1));

            System.out.println("\n=== Chained mapKeysIn/andFilter ===");
            System.out.println("Path: mapKeysIn(\"100\") -> andFilter(beta>0) -> mapKeysIn(\"1001\",\"1002\") -> andFilter(yes==1)");
            System.out.println("Expected: {\"100\": {\"1001\": {\"a\": 1, \"b\": 2, \"yes\": 1}}}");

            try {
                Operation op = CdtOperation.selectByPath("doc",
                    SelectFlags.MATCHING_TREE | SelectFlags.NO_FAIL,
                    CTX.mapKeysIn("100"),
                    CTX.andFilter(betaGtZero),
                    CTX.mapKeysIn("1001", "1002"),
                    CTX.andFilter(saysYes));

                Record record = client.operate(null, key, op);
                Object result = record.getValue("doc");
                System.out.println("Result:   " + MAPPER.writeValueAsString(result));

                Map<String, Map<String, Map<String, Long>>> expected =
                    Map.of("100", Map.of("1001", Map.of("a", 1L, "b", 2L, "yes", 1L)));
                System.out.println("Match:    " + Objects.equals(result, expected));

            } catch (AerospikeException e) {
                System.out.println("Server error: " + e.getMessage());
                System.out.println("  ResultCode: " + e.getResultCode());
            }

            client.delete(null, key);
            System.out.println("\nCleanup done.");
        }
    }

    static void setupData(IAerospikeClient client, Key key) throws Exception {
        client.delete(null, key);

        Map<String, Object> doc = MAPPER.readValue(JSON_DATA, new TypeReference<>() {});

        client.put(null, key, new Bin("doc", doc));
        System.out.println("Data loaded from embedded JSON. Full record:");

        Record check = client.get(null, key);
        System.out.println(MAPPER.writeValueAsString(check.getMap("doc")));
    }
}
