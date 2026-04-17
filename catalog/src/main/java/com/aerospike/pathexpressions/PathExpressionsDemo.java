package com.aerospike.pathexpressions;

import java.util.HashMap;
import java.util.Map;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
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
import com.aerospike.client.exp.CdtExp;
import com.aerospike.client.exp.Exp;
import com.aerospike.client.exp.ExpOperation;
import com.aerospike.client.exp.ExpWriteFlags;
import com.aerospike.client.exp.Expression;
import com.aerospike.client.exp.LoopVarPart;
import com.aerospike.client.exp.MapExp;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.query.RegexFlag;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class PathExpressionsDemo {

    static final String HOST = "127.0.0.1";
    static final int PORT = 3000;
    static final boolean USE_SERVICES_ALTERNATE = true;
    static final String JSON_DATA = """
{
  "inventory": {
    "10000001": {
      "category": "clothing",
      "featured": true,
      "name": "Classic T-Shirt",
      "description": "A lightweight cotton T-shirt perfect for everyday wear.",
      "variants": {
        "2001": {"size": "S", "price": 25, "quantity": 100},
        "2002": {"size": "M", "price": 25, "quantity": 0},
        "2003": {"size": "L", "price": 27, "quantity": 50}
      }
    },
    "10000002": {
      "category": "clothing",
      "featured": false,
      "name": "Casual Polo Shirt",
      "description": "A soft polo shirt suitable for work or leisure.",
      "variants": {
        "2004": {"size": "M", "price": 30, "quantity": 20},
        "2005": {"size": "XL", "price": 32, "quantity": 10}
      }
    },
    "50000006": {
      "category": "electronics",
      "featured": true,
      "name": "Laptop Pro 14",
      "description": "High-performance laptop designed for professionals.",
      "variants": {
        "3001": {"spec": "8GB RAM", "price": 599, "quantity": 0}
      }
    },
    "50000009": {
      "category": "electronics",
      "featured": true,
      "name": "Smart TV",
      "description": "Ultra HD smart television with built-in streaming apps.",
      "variants": [
        {"sku": 3007, "spec": "1080p", "price": 199, "quantity": 60},
        {"sku": 3008, "spec": "4K", "price": 399, "quantity": 30}
      ]
    }
  }
}
""";

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // Reusable filters
    static final Exp filterOnFeatured = Exp.eq(
        MapExp.getByKey(MapReturnType.VALUE, Exp.Type.BOOL,
            Exp.val("featured"), Exp.mapLoopVar(LoopVarPart.VALUE)),
        Exp.val(true));

    static final Exp filterOnVariantInventory = Exp.gt(
        MapExp.getByKey(MapReturnType.VALUE, Exp.Type.INT,
            Exp.val("quantity"), Exp.mapLoopVar(LoopVarPart.VALUE)),
        Exp.val(0));

    public static void main(String[] args) throws Exception {
        ClientPolicy cp = new ClientPolicy();
        cp.useServicesAlternate = USE_SERVICES_ALTERNATE;

        try (IAerospikeClient client = new AerospikeClient(cp, HOST, PORT)) {
            System.out.println("Connected to Aerospike " + HOST + ":" + PORT);

            Key key = new Key("test", "products", "catalog");
            Map<String, Object> inventory = setupData(client, key);

            runMain(client, key);
            runAdvanced1(client, key);
            runAdvanced2(client, key);
            runAdvanced3(client, key);
            runAdvanced4(client, key);
            runAdvanced5(client, key);
            runAdvanced6(client, key, inventory);
            runAdvanced7(client, key, inventory);

            client.delete(null, key);
        }
    }

    // --- Data setup ---

    static Map<String, Object> setupData(IAerospikeClient client, Key key) throws Exception {
        client.truncate(null, "test", "products", null);

        Map<String, Object> inventory = MAPPER.readValue(JSON_DATA, new TypeReference<>() {});
        client.put(null, key, new Bin("catalog", inventory));
        System.out.println("Inventory inserted");

        Record record = client.get(null, key);
        if (record == null) {
            throw new RuntimeException("No record found after insert");
        }

        printHeader("STEP 1: Retrieved inventory data");
        System.out.println(MAPPER.writeValueAsString(record.getValue("catalog")));

        return inventory;
    }

    // --- Example methods ---

    /**
     * Main example: featured products with in-stock variants.
     * JSONPath equivalent: $.inventory[?(@.featured == true)].variants[?(@.quantity > 0)]
     */
    static void runMain(IAerospikeClient client, Key key) throws Exception {
        Record record = client.operate(null, key,
            CdtOperation.selectByPath("catalog", SelectFlags.MATCHING_TREE,
                CTX.allChildren(),
                CTX.allChildrenWithFilter(filterOnFeatured),
                CTX.mapKey(Value.get("variants")),
                CTX.allChildrenWithFilter(filterOnVariantInventory)));

        printHeader("MAIN EXAMPLE (Steps 2-4): Featured products with variants having inventory > 0");
        System.out.println(MAPPER.writeValueAsString(record.getMap("catalog")));
    }

    /**
     * Advanced 1: Regex filter on map keys using LoopVar.
     * Select products whose key starts with "10000".
     */
    static void runAdvanced1(IAerospikeClient client, Key key) throws Exception {
        Exp filterOnKey = Exp.regexCompare("10000.*", RegexFlag.NONE,
            Exp.stringLoopVar(LoopVarPart.MAP_KEY));

        Record record = client.operate(null, key,
            CdtOperation.selectByPath("catalog", SelectFlags.MATCHING_TREE,
                CTX.allChildren(),
                CTX.allChildrenWithFilter(filterOnKey)));

        printHeader("ADVANCED EXAMPLE 1: Using LoopVar with regex filter on map keys");
        System.out.println("Result (MATCHING_TREE): " + MAPPER.writeValueAsString(record.getMap("catalog")));
    }

    /**
     * Advanced 2: Alternate return modes with SelectFlags.MAP_KEY.
     * Return only the SKU keys of in-stock variants for featured products.
     */
    static void runAdvanced2(IAerospikeClient client, Key key) throws Exception {
        Record record = client.operate(null, key,
            CdtOperation.selectByPath("catalog", SelectFlags.MAP_KEY | SelectFlags.NO_FAIL,
                CTX.allChildren(),
                CTX.allChildrenWithFilter(filterOnFeatured),
                CTX.mapKey(Value.get("variants")),
                CTX.allChildrenWithFilter(filterOnVariantInventory)));

        printHeader("ADVANCED EXAMPLE 2: Alternate return modes with SelectFlags");
        System.out.println("Result (MAP_KEYS only): " + MAPPER.writeValueAsString(record.getList("catalog")));
    }

    /**
     * Advanced 3: Compound filter — price < 50 AND quantity > 0.
     * Select cheap in-stock variants of featured products.
     */
    static void runAdvanced3(IAerospikeClient client, Key key) throws Exception {
        Exp filterOnCheapInStock = Exp.and(
            Exp.gt(
                MapExp.getByKey(MapReturnType.VALUE, Exp.Type.INT,
                    Exp.val("quantity"), Exp.mapLoopVar(LoopVarPart.VALUE)),
                Exp.val(0)),
            Exp.lt(
                MapExp.getByKey(MapReturnType.VALUE, Exp.Type.INT,
                    Exp.val("price"), Exp.mapLoopVar(LoopVarPart.VALUE)),
                Exp.val(50)));

        Record record = client.operate(null, key,
            CdtOperation.selectByPath("catalog", SelectFlags.MATCHING_TREE,
                CTX.allChildren(),
                CTX.allChildrenWithFilter(filterOnFeatured),
                CTX.mapKey(Value.get("variants")),
                CTX.allChildrenWithFilter(filterOnCheapInStock)));

        printHeader("ADVANCED EXAMPLE 3: Combining multiple filters (price < 50 AND quantity > 0)");
        System.out.println("Cheap in-stock items (across all products): " + MAPPER.writeValueAsString(record.getMap("catalog")));
    }

    /**
     * Advanced 4: Server-side modification via CdtOperation.modifyByPath.
     * Increment quantity by 10 for in-stock variants of featured products.
     */
    static void runAdvanced4(IAerospikeClient client, Key key) throws Exception {
        Expression incrementQuantity = Exp.build(
            MapExp.put(MapPolicy.Default,
                Exp.val("quantity"),
                Exp.add(
                    MapExp.getByKey(MapReturnType.VALUE, Exp.Type.INT,
                        Exp.val("quantity"), Exp.mapLoopVar(LoopVarPart.VALUE)),
                    Exp.val(10)),
                Exp.mapLoopVar(LoopVarPart.VALUE)));

        client.operate(null, key,
            CdtOperation.modifyByPath("catalog", ModifyFlags.DEFAULT, incrementQuantity,
                CTX.allChildren(),
                CTX.allChildrenWithFilter(filterOnFeatured),
                CTX.mapKey(Value.get("variants")),
                CTX.allChildrenWithFilter(filterOnVariantInventory)));

        Record record = client.get(null, key);
        if (record == null) {
            throw new RuntimeException("No record found after modify");
        }

        printHeader("ADVANCED EXAMPLE 4: Server-side modification (incrementing quantities)");
        System.out.println("Updated records (quantities incremented by 10):");
        System.out.println(MAPPER.writeValueAsString(record.getValue("catalog")));
    }

    /**
     * Advanced 5: Server-side modification via CdtExp.modifyByPath + ExpOperation.write.
     * Same increment, but writes the result to a different bin.
     */
    static void runAdvanced5(IAerospikeClient client, Key key) throws Exception {
        String updatedBin = "updatedBinName";

        Exp incrementExp = MapExp.put(MapPolicy.Default,
            Exp.val("quantity"),
            Exp.add(
                MapExp.getByKey(MapReturnType.VALUE, Exp.Type.INT,
                    Exp.val("quantity"), Exp.mapLoopVar(LoopVarPart.VALUE)),
                Exp.val(10)),
            Exp.mapLoopVar(LoopVarPart.VALUE));

        Expression modifyExpression = Exp.build(
            CdtExp.modifyByPath(Exp.Type.MAP, ModifyFlags.DEFAULT, incrementExp,
                Exp.mapBin("catalog"),
                CTX.allChildren(),
                CTX.allChildrenWithFilter(filterOnFeatured),
                CTX.mapKey(Value.get("variants")),
                CTX.allChildrenWithFilter(filterOnVariantInventory)));

        client.operate(null, key,
            ExpOperation.write(updatedBin, modifyExpression, ExpWriteFlags.DEFAULT));

        Record record = client.get(null, key);

        printHeader("ADVANCED EXAMPLE 5: Server-side modification alternative approach (incrementing quantities)");
        System.out.println("Updated records (quantities incremented by 10): " + MAPPER.writeValueAsString(record.getMap(updatedBin)));
    }

    /**
     * Advanced 6: NO_FAIL flag to tolerate malformed data.
     * Inserts a product with variants as a non-map/list value, then shows
     * the operation failing without NO_FAIL and succeeding with it.
     */
    static void runAdvanced6(IAerospikeClient client, Key key, Map<String, Object> inventory) throws Exception {
        Map<String, Object> badRecordDetails = new HashMap<>();
        badRecordDetails.put("category", "clothing");
        badRecordDetails.put("featured", true);
        badRecordDetails.put("name", "Hooded Sweatshirt");
        badRecordDetails.put("description", "Warm fleece hoodie with front pocket and adjustable hood.");
        badRecordDetails.put("variants", "no variant");

        WritePolicy writePolicy = new WritePolicy();
        writePolicy.recordExistsAction = RecordExistsAction.UPDATE;
        client.operate(writePolicy, key,
            MapOperation.put(MapPolicy.Default, "catalog",
                Value.get("10000003"), Value.get(badRecordDetails),
                CTX.mapKey(Value.get("inventory"))));

        printHeader("ADVANCED EXAMPLE 6: NO_FAIL flag to tolerate malformed data");

        try {
            client.operate(null, key,
                CdtOperation.selectByPath("catalog", SelectFlags.MATCHING_TREE,
                    CTX.allChildren(),
                    CTX.allChildrenWithFilter(filterOnFeatured),
                    CTX.mapKey(Value.get("variants")),
                    CTX.allChildrenWithFilter(filterOnVariantInventory)));
        } catch (AerospikeException e) {
            System.out.println("Operation failed as expected (malformed data without NO_FAIL flag)");
        }

        Record record = client.operate(null, key,
            CdtOperation.selectByPath("catalog", SelectFlags.MATCHING_TREE | SelectFlags.NO_FAIL,
                CTX.allChildren(),
                CTX.allChildrenWithFilter(filterOnFeatured),
                CTX.mapKey(Value.get("variants")),
                CTX.allChildrenWithFilter(filterOnVariantInventory)));

        System.out.println("Operation succeeded with NO_FAIL flag:");
        System.out.println(MAPPER.writeValueAsString(record.getValue("catalog")));

        WritePolicy replacePolicy = new WritePolicy();
        replacePolicy.recordExistsAction = RecordExistsAction.REPLACE;
        client.put(replacePolicy, key, new Bin("catalog", inventory));
    }

    /**
     * Advanced 7: Remove all size "M" variants using modifyByPath + Exp.removeResult().
     * The path selects matching variants across all products; removeResult() deletes them.
     * Uses NO_FAIL to skip products that lack a "size" key or use list-backed variants.
     */
    static void runAdvanced7(IAerospikeClient client, Key key, Map<String, Object> inventory) throws Exception {
        Exp filterOnSizeM = Exp.eq(
            MapExp.getByKey(MapReturnType.VALUE, Exp.Type.STRING,
                Exp.val("size"), Exp.mapLoopVar(LoopVarPart.VALUE)),
            Exp.val("M"));

        Expression removeExp = Exp.build(Exp.removeResult());

        client.operate(null, key,
            CdtOperation.modifyByPath("catalog", ModifyFlags.NO_FAIL, removeExp,
                CTX.allChildren(),
                CTX.allChildren(),
                CTX.mapKey(Value.get("variants")),
                CTX.allChildrenWithFilter(filterOnSizeM)
            )
        );

        Record record = client.get(null, key);
        if (record == null) {
            throw new RuntimeException("No record found after modify");
        }

        printHeader("ADVANCED EXAMPLE 7: Remove all size M variants");
        System.out.println(MAPPER.writeValueAsString(record.getValue("catalog")));

        WritePolicy replacePolicy = new WritePolicy();
        replacePolicy.recordExistsAction = RecordExistsAction.REPLACE;
        client.put(replacePolicy, key, new Bin("catalog", inventory));
    }

    // --- Utility ---

    static void printHeader(String title) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println(title);
        System.out.println("=".repeat(80));
    }
}
