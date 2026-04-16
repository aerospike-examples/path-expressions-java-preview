import java.util.*;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.Value;
import com.aerospike.client.cdt.CTX;
import com.aerospike.client.cdt.ListReturnType;
import com.aerospike.client.cdt.MapReturnType;
import com.aerospike.client.cdt.SelectFlags;
import com.aerospike.client.exp.CdtExp;
import com.aerospike.client.exp.Exp;
import com.aerospike.client.exp.ExpOperation;
import com.aerospike.client.exp.ExpReadFlags;
import com.aerospike.client.exp.Expression;
import com.aerospike.client.exp.ListExp;
import com.aerospike.client.exp.MapExp;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.QueryPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.query.Filter;
import com.aerospike.client.query.IndexCollectionType;
import com.aerospike.client.query.IndexType;
import com.aerospike.client.query.RecordSet;
import com.aerospike.client.query.Statement;
import com.aerospike.client.task.IndexTask;

/**
 * Verifies the three Java examples from nesting.mdx against a live Aerospike server.
 */
public class NestingExamplesTest {

    static final String HOST = "127.0.0.1";
    static final int PORT = 3000;
    static final boolean USE_SERVICES_ALTERNATE = true;

    static final String NS = "test";
    static final String SET = "demo";

    public static void main(String[] args) throws Exception {
        ClientPolicy cp = new ClientPolicy();
        cp.useServicesAlternate = USE_SERVICES_ALTERNATE;
        try (IAerospikeClient client = new AerospikeClient(cp, HOST, PORT)) {
            System.out.println("Connected to Aerospike " + HOST + ":" + PORT);

            Key key1 = new Key(NS, SET, "nesting_rec1");
            Key key2 = new Key(NS, SET, "nesting_rec2");

            setupData(client, key1, key2);

            try {
                testExample1(client, key1);
                testExample2(client);
                testExample3(client);
                System.out.println("\n=== ALL EXAMPLES PASSED ===");
            } finally {
                cleanup(client, key1, key2);
            }
        }
    }

    static void setupData(IAerospikeClient client, Key key1, Key key2) {
        client.delete(null, key1);
        client.delete(null, key2);

        List<Map<String, Object>> vehicles1 = List.of(
            Map.of("make", "Toyota", "model", "RAV4",    "color", "white",  "license", "8PAJ017"),
            Map.of("make", "Tesla",  "model", "Model 3", "color", "blue",   "license", "6ABC123"),
            Map.of("make", "Honda",  "model", "Civic",   "color", "silver", "license", "7XYZ789"));

        List<Map<String, Object>> vehicles2 = List.of(
            Map.of("make", "Ford", "model", "F-150", "color", "black", "license", "4DEF456"));

        WritePolicy wp = new WritePolicy();
        wp.sendKey = true;
        client.put(wp, key1, new Bin("vehicles", vehicles1));
        client.put(wp, key2, new Bin("vehicles", vehicles2));
        System.out.println("Data loaded: rec1 (3 vehicles incl 7XYZ789), rec2 (1 vehicle, no 7XYZ789)");
    }

    // ── Example 1: Read the license plate of the default vehicle ──────────
    // Verbatim from nesting.mdx lines 61-70
    static void testExample1(IAerospikeClient client, Key key) {
        System.out.println("\n=== EXAMPLE 1: Read default vehicle license ===");

        Record record = client.operate(null, key,
            ExpOperation.read("defaultLicense",
                Exp.build(
                    MapExp.getByKey(MapReturnType.VALUE, Exp.Type.STRING,
                        Exp.val("license"),
                        ListExp.getByIndex(ListReturnType.VALUE, Exp.Type.MAP,
                            Exp.val(0), Exp.listBin("vehicles")))),
                ExpReadFlags.DEFAULT));

        String plate = record.getString("defaultLicense");

        System.out.println("defaultLicense = " + plate);
        assert "8PAJ017".equals(plate) : "Expected 8PAJ017 but got " + plate;
        System.out.println("PASS");
    }

    // ── Example 2: Check a license plate against all vehicles ─────────────
    // Verbatim from nesting.mdx lines 204-215
    static void testExample2(IAerospikeClient client) {
        System.out.println("\n=== EXAMPLE 2: Filter by license (path expression) ===");

        String targetPlate = "7XYZ789";

        QueryPolicy policy = client.copyQueryPolicyDefault();
        policy.filterExp = Exp.build(
            ListExp.getByValue(ListReturnType.EXISTS,
                Exp.val(targetPlate),
                CdtExp.selectByPath(Exp.Type.LIST, SelectFlags.VALUE,
                    Exp.listBin("vehicles"),
                    CTX.allChildren(),
                    CTX.mapKey(Value.get("license"))))
        );

        Statement stmt = new Statement();
        stmt.setNamespace(NS);
        stmt.setSetName(SET);

        List<String> matchedKeys = new ArrayList<>();
        try (RecordSet rs = client.query(policy, stmt)) {
            while (rs.next()) {
                matchedKeys.add(rs.getKey().userKey.toString());
            }
        }

        System.out.println("Matched keys: " + matchedKeys);
        assert matchedKeys.contains("nesting_rec1") : "rec1 should match";
        assert !matchedKeys.contains("nesting_rec2") : "rec2 should NOT match";
        System.out.println("PASS");
    }

    // ── Example 3: Expression index + query ───────────────────────────────
    // Step 1 verbatim from nesting.mdx lines 362-371
    // Step 2 verbatim from nesting.mdx lines 498-504
    static void testExample3(IAerospikeClient client) throws Exception {
        System.out.println("\n=== EXAMPLE 3: Expression index + query ===");

        // Step 1: Create the index
        Expression licensesExp = Exp.build(
            CdtExp.selectByPath(Exp.Type.LIST, SelectFlags.VALUE,
                Exp.listBin("vehicles"),
                CTX.allChildren(),
                CTX.mapKey(Value.get("license"))));

        IndexTask task = client.createIndex(null, NS, SET,
            "idx_vehicle_license", IndexType.STRING,
            IndexCollectionType.LIST, licensesExp);
        task.waitTillComplete();
        System.out.println("Index idx_vehicle_license created");

        // Step 2: Query the index
        Statement stmt = new Statement();
        stmt.setNamespace(NS);
        stmt.setSetName(SET);
        stmt.setFilter(Filter.contains(licensesExp,
            IndexCollectionType.LIST, "7XYZ789"));

        List<String> matchedKeys = new ArrayList<>();
        try (RecordSet rs = client.query(null, stmt)) {
            while (rs.next()) {
                matchedKeys.add(rs.getKey().userKey.toString());
            }
        }

        System.out.println("Query by expression matched keys: " + matchedKeys);
        assert matchedKeys.contains("nesting_rec1") : "rec1 should match";
        assert !matchedKeys.contains("nesting_rec2") : "rec2 should NOT match";
        System.out.println("PASS");

        // Step 3: Query the index by name
        Statement stmt2 = new Statement();
        stmt2.setNamespace(NS);
        stmt2.setSetName(SET);
        stmt2.setFilter(Filter.containsByIndex("idx_vehicle_license",
            IndexCollectionType.LIST, "7XYZ789"));

        List<String> matchedKeys2 = new ArrayList<>();
        try (RecordSet rs2 = client.query(null, stmt2)) {
            while (rs2.next()) {
                matchedKeys2.add(rs2.getKey().userKey.toString());
            }
        }

        System.out.println("Query by index name matched keys: " + matchedKeys2);
        assert matchedKeys2.contains("nesting_rec1") : "rec1 should match (by index name)";
        assert !matchedKeys2.contains("nesting_rec2") : "rec2 should NOT match (by index name)";
        System.out.println("PASS");
    }

    static void cleanup(IAerospikeClient client, Key key1, Key key2) {
        try {
            client.dropIndex(null, NS, SET, "idx_vehicle_license");
            System.out.println("Dropped index idx_vehicle_license");
        } catch (Exception e) {
            System.out.println("Index drop: " + e.getMessage());
        }
        client.delete(null, key1);
        client.delete(null, key2);
        System.out.println("Deleted test records");
    }
}
