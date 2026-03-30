# Path Expressions — Java Examples

Java code examples for the [Path Expressions](https://docs.aerospike.com/develop/expressions/path/) documentation.

## Structure

| Folder | Description | Docs page |
|---|---|---|
| `bookstore/` | Bookstore data model with nested queries | [Overview](https://docs.aerospike.com/develop/expressions/path/) |
| `catalog/` | E-commerce catalog: filtering, select flags, modification | [Quickstart](https://docs.aerospike.com/develop/expressions/path/quickstart/) and [Advanced usage](https://docs.aerospike.com/develop/expressions/path/advanced/) |
| `booking/` | Booking data model: IN-list filtering with 8.1.2 CTX enhancements | [Performance](https://docs.aerospike.com/develop/expressions/path/performance/) |

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

   Replace `catalog` with `bookstore` (or any other folder) to run a different example.
