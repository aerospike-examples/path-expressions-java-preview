# Path expressions: opportunistic map entry expiry

This example shows how to combine **path expressions** with a small amount of application data so you can **omit** or **delete** “expired” entries inside a nested map—something Aerospike does not do automatically for individual map keys.

**Path expressions and the CDT path operations used here are an [Aerospike Database 8.1.2](https://aerospike.com/docs/database/) (or newer) *server* feature.** Run against an 8.1.2+ server; older releases may not support these APIs. The feature family is documented under [Path expressions](https://docs.aerospike.com/develop/expressions/path/).

---

## How to run the example

From the repository root:

```bash
cd pathexp-opportunistic-map-expiry
mvn compile exec:java
```

That builds and runs the `OpportunisticMapExpiry` class.

**Configuration in code**

- Default cluster address is `127.0.0.1:3000`. Change `HOST` and `PORT` in [`src/main/java/OpportunisticMapExpiry.java`](src/main/java/OpportunisticMapExpiry.java) if needed.
- If your deployment requires alternate service discovery, set `USE_SERVICES_ALTERNATE` to `true` in the same file.

**What you should see**

The program prints a few labeled sections: the full bin after insert (including one expired nested entry), a **read** that only returns non-expired entries, a **write** that removes expired entries and then updates a surviving inner map, and finally deletes the demo record.

---

## What we are doing and why (a short tour)

### The limitation we are working around

Aerospike’s TTL applies to **whole records**, not to arbitrary keys inside a map. If you model many small logical objects as **one record**—for example a map from an id to a blob of fields—you still might want each object to “expire” on its own schedule without creating one Aerospike record per object.

This sample uses a **convention**: every **inner** map includes a field `void_time`, an integer **Unix time in seconds** after which we consider that entry expired. That value is normal bin data; the server does not interpret it unless we ask it to, via expressions.

### Why “opportunistic”

We only enforce that convention when we **read** or **write** that map. On a read, we **project** a result that **skips** expired entries. On a write, we **remove** expired entries from the stored structure, then perform the real update. Entries you never touch again can sit there until the **record’s** TTL removes the whole thing—so pairing this pattern with a sensible record TTL is a good idea.

### How path expressions help

Path expressions let the server walk a **path** into nested CDT data (here, a map of maps) and apply a **boolean filter** at each step. For each top-level entry in the `entries` bin, the filter sees the **inner map** as the current value and can read `void_time` from it.

- **Read:** we use `selectByPath` with a filter meaning “keep this entry only if `void_time` is still **after** our cutoff.” The cutoff is passed as `Exp.val(now)` when the client builds the request, so the server evaluates the comparison at operation time. The result uses `MATCHING_TREE` so you get a subtree of only the entries that passed.

- **Write:** we first use `modifyByPath` with `removeResult()` on entries where `void_time` is **at or before** the same cutoff, which **deletes** those branches. Then we run an ordinary map `put` on one of the remaining inner maps to show a typical mutation after cleanup.

### Caveats worth knowing

The “current time” in the expression is whatever value you put in `Exp.val(...)` when you build the operation—not a special hidden server clock. Production code should decide how fresh that value must be and how to handle bad or missing `void_time` fields (for example with `NO_FAIL` on paths if data can be imperfect).

For more patterns, see the [path expressions overview](https://docs.aerospike.com/develop/expressions/path/) and the other modules in this repository (`catalog/`, `booking/`, `misc/`, and so on).
