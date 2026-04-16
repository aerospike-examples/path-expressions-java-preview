import java.util.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.Record;
import com.aerospike.client.Value;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.cdt.CTX;
import com.aerospike.client.cdt.CdtOperation;
import com.aerospike.client.cdt.MapReturnType;
import com.aerospike.client.cdt.SelectFlags;
import com.aerospike.client.exp.Exp;
import com.aerospike.client.exp.LoopVarPart;
import com.aerospike.client.exp.MapExp;

/**
 * Demonstrate Aerospike path expressions using the classic JSONPath bookstore dataset.
 * Data is parsed from an embedded JSON string (see also bookstore.json)
 * and stored in a single map bin "bookstore".
 *
 * Data structure:
 *   bin "bookstore" (map) ->
 *     store (map) ->
 *       book (list of maps): [{category, author, title, price, isbn?}, ...]
 *       bicycle (map): {color, price}
 *     expensive: 10
 */
public class BookstorePathExp {

    static final String HOST = "127.0.0.1";
    static final int PORT = 3000;
    static final boolean USE_SERVICES_ALTERNATE = true;
    static final String JSON_DATA = """
{
  "store": {
    "book": [
      {"category": "reference", "author": "Nigel Rees", "title": "Sayings of the Century", "price": 8.95},
      {"category": "fiction", "author": "Evelyn Waugh", "title": "Sword of Honour", "price": 12.99},
      {"category": "fiction", "author": "Herman Melville", "title": "Moby Dick", "isbn": "0-553-21311-3", "price": 8.99},
      {"category": "fiction", "author": "J. R. R. Tolkien", "title": "The Lord of the Rings", "isbn": "0-395-19395-8", "price": 22.99}
    ],
    "bicycle": {"color": "red", "price": 19.95}
  },
  "expensive": 10
}
""";

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        ClientPolicy cp = new ClientPolicy();
        cp.useServicesAlternate = USE_SERVICES_ALTERNATE;
        try (IAerospikeClient client = new AerospikeClient(cp, HOST, PORT)) {
            System.out.println("Connected to Aerospike " + HOST + ":" + PORT);

            Key key = new Key("test", "bookstore", "bookstore1");
            setupData(client, key);

            System.out.println("\n=== Q1: The authors of all books in the store ===");
            Object r1 = runQ1(client, key);
            System.out.println(MAPPER.writeValueAsString(r1));

            System.out.println("\n=== Q2: Filter all books with ISBN number ===");
            Object r2 = runQ2(client, key);
            System.out.println(MAPPER.writeValueAsString(r2));

            System.out.println("\n=== Q3: Filter all books cheaper than 10 ===");
            Object r3 = runQ3(client, key);
            System.out.println(MAPPER.writeValueAsString(r3));

            System.out.println("\n=== Q4: Filter all fiction books costing less than 10 ===");
            Object r4 = runQ4(client, key);
            System.out.println(MAPPER.writeValueAsString(r4));

            client.delete(null, key);
        }
    }

    // --- Question methods ---

    /**
     * Q1: The authors of all books in the store.
     * Path: bookstore -> store -> book -> allChildren -> mapKeysIn("author")
     * JSONPath: $.store.book[*].author
     */
    static Object runQ1(IAerospikeClient client, Key key) {
        Operation op = CdtOperation.selectByPath("bookstore", SelectFlags.VALUE,
            CTX.mapKey(Value.get("store")),
            CTX.mapKey(Value.get("book")),
            CTX.allChildren(),
            CTX.mapKeysIn("author"));

        Record record = client.operate(null, key, op);
        return record.getValue("bookstore");
    }

    /**
     * Q2: Filter all books with ISBN number.
     * Path: bookstore -> store -> book -> allChildrenWithFilter(has isbn)
     * JSONPath: $.store.book[?(@.isbn)]
     */
    static Object runQ2(IAerospikeClient client, Key key) {
        Exp hasIsbn = Exp.gt(
            MapExp.getByKey(MapReturnType.COUNT, Exp.Type.INT,
                Exp.val("isbn"), Exp.mapLoopVar(LoopVarPart.VALUE)),
            Exp.val(0));

        Operation op = CdtOperation.selectByPath("bookstore", SelectFlags.VALUE,
            CTX.mapKey(Value.get("store")),
            CTX.mapKey(Value.get("book")),
            CTX.allChildrenWithFilter(hasIsbn));

        Record record = client.operate(null, key, op);
        return record.getValue("bookstore");
    }

    /**
     * Q3: Filter all books cheaper than 10.
     * Path: bookstore -> store -> book -> allChildrenWithFilter(price < 10)
     * JSONPath: $.store.book[?(@.price < 10)]
     */
    static Object runQ3(IAerospikeClient client, Key key) {
        Exp cheapBooks = Exp.lt(
            MapExp.getByKey(MapReturnType.VALUE, Exp.Type.FLOAT,
                Exp.val("price"), Exp.mapLoopVar(LoopVarPart.VALUE)),
            Exp.val(10.0));

        Operation op = CdtOperation.selectByPath("bookstore", SelectFlags.VALUE,
            CTX.mapKey(Value.get("store")),
            CTX.mapKey(Value.get("book")),
            CTX.allChildrenWithFilter(cheapBooks));

        Record record = client.operate(null, key, op);
        return record.getValue("bookstore");
    }

    /**
     * Q4: Filter all fiction books costing less than 10.
     * Path: bookstore -> store -> book -> allChildrenWithFilter(category == "fiction" AND price < 10)
     * JSONPath: $.store.book[?(@.category=='fiction' && @.price < 10)]
     */
    static Object runQ4(IAerospikeClient client, Key key) {
        Exp isFiction = Exp.eq(
            MapExp.getByKey(MapReturnType.VALUE, Exp.Type.STRING,
                Exp.val("category"), Exp.mapLoopVar(LoopVarPart.VALUE)),
            Exp.val("fiction"));

        Exp cheapBooks = Exp.lt(
            MapExp.getByKey(MapReturnType.VALUE, Exp.Type.FLOAT,
                Exp.val("price"), Exp.mapLoopVar(LoopVarPart.VALUE)),
            Exp.val(10.0));

        Operation op = CdtOperation.selectByPath("bookstore", SelectFlags.VALUE,
            CTX.mapKey(Value.get("store")),
            CTX.mapKey(Value.get("book")),
            CTX.allChildrenWithFilter(Exp.and(isFiction, cheapBooks)));

        Record record = client.operate(null, key, op);
        return record.getValue("bookstore");
    }

    // --- Data setup ---

    static void setupData(IAerospikeClient client, Key key) throws Exception {
        client.delete(null, key);

        Map<String, Object> doc = MAPPER.readValue(JSON_DATA, new TypeReference<>() {});

        client.put(null, key, new Bin("bookstore", doc));
        System.out.println("Data loaded from embedded JSON. Full record:");

        Record check = client.get(null, key);
        System.out.println(MAPPER.writeValueAsString(check.getMap("bookstore")));
    }
}
