---
title: "PuppetDB 2.2 » API » v3 » Query Operators"
layout: default
canonical: "/puppetdb/latest/api/query/v3/operators.html"
---

[resources]: ./resources.html
[facts]: ./facts.html
[nodes]: ./nodes.html
[query]: ./query.html

PuppetDB's [query strings][query] can use several common operators.

> **Note:** The operators below apply to **version 3** of the query API. Not all of them are available to version 1 or 2 queries.


## Binary Operators

Each of these operators accepts two arguments: a **field,** and a
**value.** These operators are **non-transitive:** their syntax must always be:

    ["<OPERATOR>", "<FIELD>", "<VALUE>"]

The available fields for each endpoint are listed in that endpoint's documentation.

### `=` (equality)

**Matches if:** the field's actual value is the same as the provided value.
This operator will coerce queries on fact values to strings; in all other cases
the query type must match that of the database value.  For example:

* Fact value queries for "3.14" or 3.14 will match either "3.14" or 3.14
* Fact value queries for "true" and true will match either "true" or true
* Queries for resources with exported field "false" will throw an error, since
  `exported` is a boolean field in the database.

### `>` (greater than)

**Matches if:** the field is greater than the provided value. Coerces both the field and value to floats or integers; if
they can't be coerced, the operator will not match.

### `<` (less than)

**Matches if:** the field is less than the provided value. Coerces both the field and value to floats or integers; if
they can't be coerced, the operator will not match.

### `>=` (greater than or equal to)

**Matches if:** the field is greater than or equal to the provided value. Coerces both the field and value to floats or integers; if
they can't be coerced, the operator will not match.

### `<=` (less than or equal to)

**Matches if:** the field is less than or equal to the provided value. Coerces both the field and value to floats or integers; if
they can't be coerced, the operator will not match.

### `~` (regexp match)

**Matches if:** the field's actual value matches the provided regular expression. The provided value must be a regular expression represented as a JSON string:

* The regexp **must not** be surrounded by the slash characters (`/rexegp/`) that delimit regexps in many languages.
* Every backslash character **must** be escaped with an additional backslash. Thus, a sequence like `\d` would be represented as `\\d`, and a literal backslash (represented in a regexp as a double-backslash `\\`) would be represented as a quadruple-backslash (`\\\\`).

The following example would match if the `certname` field's actual value resembled something like `www03.example.com`:

    ["~", "certname", "www\\d+\\.example\\.com"]

> **Note:** Regular expression matching is performed by the database backend, and the available regexp features are backend-dependent. For best results, use the simplest and most common features that can accomplish your task. See the links below for details:
>
> * [PostgreSQL regexp features](http://www.postgresql.org/docs/9.1/static/functions-matching.html#POSIX-SYNTAX-DETAILS)
> * [HSQLDB (embedded database) regexp features](http://docs.oracle.com/javase/6/docs/api/java/util/regex/Pattern.html)



## Boolean Operators

Every argument of these operators should be a **complete query string** in its own right. These operators are **transitive:** the order of their arguments does not matter.

### `and`

**Matches if:** **all** of its arguments would match. Accepts any number of query strings as its arguments.

### `or`

**Matches if:** **at least one** of its arguments would match. Accepts any number of query strings as its arguments.

### `not`

**Matches if:** its argument **would not** match. Accepts a **single** query string as its argument.


## Subquery Operators

Subqueries allow you to correlate data from multiple sources or multiple
rows. (For instance, a query such as "fetch the IP addresses of all nodes with
`Class[Apache]`" would have to use both facts and resources to return a list of facts.)

Subqueries are unlike the other operators listed above. They always appear together in the following form:

    ["in", "<FIELD>", ["extract", "<FIELD>", <SUBQUERY STATEMENT>] ]

That is:

* The `in` operator results in a complete query string. The `extract` operator and the subqueries do not.
* An `in` statement **must** contain a field and an `extract` statement.
* An `extract` statement **must** contain a field and a subquery statement.

These statements work together as follows (working "outward" and starting with the subquery):

* The subquery collects a group of PuppetDB objects (specifically, a group of [resources][] or a group of [facts][]). Each of these objects has many **fields.**
* The `extract` statement collects the value of a **single field** across every object returned by the subquery.
* The `in` statement **matches** if the value of its field is present in the list returned by the `extract` statement.

Subquery | Extract | In
---------|---------|---
Every resource whose type is "Class" and title is "Apache." (Note that all resource objects have a `certname` field, among other fields.) | Every `certname` field from the results of the subquery. | Match if the `certname` field is present in the list from the `extract` statement.

The complete `in` statement described in the table above would match any object that shares a `certname` with a node that has `Class[Apache]`. This could be combined with a boolean operator to get a specific fact from every node that matches the `in` statement.


### `in`

An `in` statement constitutes a full query string, which can be used alone or as an argument for a [boolean operator](#boolean-operators).

"In" statements are **non-transitive** and take two arguments:

* The first argument **must** be a valid **field** for the endpoint **being queried.**
* The second argument **must** be an **`extract` statement,** which acts as a list of possible values for the field.

**Matches if:** the field's actual value is included in the list of values created by the `extract` statement.

### `extract`

An `extract` statement **does not** constitute a full query string. It may only be used as the second argument of an `in` statement.

"Extract" statements are **non-transitive** and take two arguments:

* The first argument **must** be a valid **field** for the endpoint **being subqueried** (see second argument).
* The second argument **must** be a **subquery statement.**

As the second argument of an `in` statement, an `extract` statement acts as a list of possible values. This list is compiled by extracting the value of the requested field from every result of the subquery.

### Subquery Statements

A subquery statement **does not** constitute a full query string. It may only be used as the second argument of an `extract` statement.

Subquery statements are **non-transitive** and take two arguments:

* The first argument **must** be the **name** of one of the available subqueries (listed below).
* The second argument **must** be a **full query string** that makes sense for the endpoint being subqueried.

As the second argument of an `extract` statement, a subquery statement acts as a collection of PuppetDB objects. Each of the objects returned by the subquery has many fields; the `extract` statement takes the value of one field from each of those objects, and passes that list of values to the `in` statement that contains it.

### Available Subqueries

Each subquery acts as a normal query to one of the PuppetDB endpoints. For info on constructing useful queries, see the docs page for that endpoint.

The available subqueries are:

* [`select-resources`](#select-resources)
* [`select-facts`](#select-facts)

#### `select-resources`

A `select-resources` subquery may **only** be used as the second argument of an `extract` statement.

It takes a single argument, which must be a **complete query string** which would be valid for [the `/v3/resources` endpoint][resources]. (Note that `/v3/resources/<TYPE>` and `/v3/resources/<TYPE>/<TITLE>` cannot be directly subqueried.) Since the argument is a normal query string, it can itself include any number of `in` statements and subqueries.

#### `select-facts`

A `select-facts` subquery may **only** be used as the second argument of an `extract` statement.

It takes a single argument, which must be a **complete query string** which would be valid for [the `/v3/facts` endpoint][facts]. (Note that `/v3/facts/<NAME>` and `/v3/facts/<NAME>/<VALUE>` cannot be directly subqueried.) Since the argument is a normal query string, it can itself include any number of `in` statements and subqueries.

### Subquery Examples

This query string queries the `/facts` endpoint for the IP address of
all nodes with `Class[Apache]`:

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["extract", "certname",
          ["select-resources",
            ["and",
              ["=", "type", "Class"],
              ["=", "title", "Apache"]]]]]]

This query string queries the `/nodes` endpoint for all nodes with `Class[Apache]`:

    ["in", "name",
      ["extract", "certname",
        ["select-resources",
          ["and",
            ["=", "type", "Class"],
            ["=", "title", "Apache"]]]]]]

This query string queries the `/facts` endpoint for the IP address of
all Debian nodes.

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["extract", "certname",
          ["select-facts",
            ["and",
              ["=", "name", "operatingsystem"],
              ["=", "value", "Debian"]]]]]]
