# Path Expressions — Java Examples

Java code examples for the [Path Expressions](https://docs.aerospike.com/develop/expressions/path/) documentation.

## Structure

| Folder | Description | Docs page |
|---|---|---|
| `bookstore/` | Bookstore data model with nested queries | [Overview](https://docs.aerospike.com/develop/expressions/path/) |
| `catalog/` | E-commerce catalog: filtering, select flags, modification | [Quickstart](https://docs.aerospike.com/develop/expressions/path/quickstart/) and [Advanced usage](https://docs.aerospike.com/develop/expressions/path/advanced/) |
| `booking/` | Booking data model: IN-list filtering with 8.1.2 CTX enhancements | [Performance](https://docs.aerospike.com/develop/expressions/path/performance/) |
| `misc/` | Miscellaneous code examples for path expression use, such as chaining `mapKeysIn`/`andFilter` pairs and nesting experiments | [FAQ](https://docs.aerospike.com/develop/expressions/path/faq/) |
| `pathexp-opportunistic-map-expiry/` | Opportunistic expiry of nested map entries using `void_time` (epoch seconds) on read and write | [Example README](pathexp-opportunistic-map-expiry/README.md) |

Each folder is a self-contained Maven project.

## Prerequisites

- Java 21+
- Maven 3.9+
- Aerospike Database 8.1.2 or later. See the [Quick Start](https://aerospike.com/docs/database/quick-start/).

## Getting started

1. **Clone the repository.**

   ```bash
   git clone https://github.com/aerospike-examples/path-expressions-java-preview.git
   cd path-expressions-java-preview
   ```

2. **Build and run an example.**

   ```bash
   cd catalog
   mvn compile exec:java
   ```

   Replace `catalog` with `bookstore`, `booking`, or `pathexp-opportunistic-map-expiry` to run their example.

   The `misc/` folder contains multiple examples. By default `mvn compile exec:java`
   runs `NestingExamplesTest`. To run a different class, pass `-Dmain=ClassName`:

   ```bash
   cd misc
   mvn compile exec:java                              # runs NestingExamplesTest
   mvn compile exec:java -Dmain=ChainedMapKeysInExp   # runs ChainedMapKeysInExp
   ```
