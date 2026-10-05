# ADR 0003 — Typed JSON-RPC envelope and DTO conventions

- **Status:** Accepted
- **Date:** 2026-10-05

## Context

ADR 0001 requires typed DTOs on both sides, a typed envelope, strict deserialization and golden fixtures. It leaves open how the envelope is typed when the params and result types depend on the method, how optional and required fields are told apart, and what exactly the "every DTO has a fixture" check covers.

Two facts about the libraries shape the answer:

- Jackson 3 **disables** `FAIL_ON_UNKNOWN_PROPERTIES` by default (Jackson 2 enabled it).
- Jackson's `FAIL_ON_MISSING_CREATOR_PROPERTIES` makes *every* record component required, `Optional` ones included. serde, by contrast, treats a missing `Option<T>` field as `None` and every other missing field as an error.

## Decision

1. **Method descriptors.** Each method is described once per side by its name, params type and result type: `RpcMethod<P, R>` records in `Methods` (Java), and a `RpcMethod` trait with associated types implemented by an uninhabited enum per method (Rust; `METHOD_NAMES` lists them). The server dispatches through `Methods`, and the client builds requests from the trait.
2. **Generic envelope.** `Request<P>` holds `jsonrpc`, `id`, `method` and `params`. `Response<R>` holds `jsonrpc`, `id`, and an optional `result` and `error`, with exactly one of them present; both sides reject anything else on deserialization. Java resolves the concrete type through `ProtocolJson.requestType(method)` / `responseType(method)`. The server can read the `method` first and then parse the request with that type, so no untyped JSON tree is needed.
3. **Envelope rules.**
   - `jsonrpc` is a one-value enum (`"2.0"`), so other versions are rejected.
   - `params` is always an object. A method without parameters has an empty params DTO (`{}`).
   - Request ids are non-negative integers chosen by the TUI. String ids and notifications from the client are not supported.
   - A response's `id` is always written. It is `null` only when the server could not read the request's id (parse error).
   - `error` is `{code, message}`. `code` is an `ErrorCode` enum serialized as its number, and unknown codes are rejected.
4. **Optional means `Optional`.** A field is optional exactly when it is `Optional*` in Java and `Option<T>` in Rust. All other fields are required and must not be `null`.
   - Java enforces this with `ProtocolAnnotationIntrospector`, which marks every non-`Optional` creator parameter as required, plus `FAIL_ON_NULL_CREATOR_PROPERTIES`.
   - `ProtocolJson` also explicitly enables `FAIL_ON_UNKNOWN_PROPERTIES`, `FAIL_ON_NULL_FOR_PRIMITIVES` and `FAIL_ON_TRAILING_TOKENS`, and writes properties in declaration order.
   - Scalars are not coerced, matching serde: `ALLOW_COERCION_OF_SCALARS` and `ACCEPT_FLOAT_AS_INT` are disabled, and integer, float and boolean input fails for textual types. Without this, Jackson accepts `"2"` and `2.5` as a `long` and `7` as a `String`.
   - In Rust, serde gives the same semantics with `deny_unknown_fields` on every struct.

   Without the required check, a field added only to the Java DTO would deserialize as `null` from an unchanged fixture, the round trip would still pass, and the drift would show up only at runtime.
5. **Fixture naming.** `<method>.request.json`, `<method>.response.json` and `<method>.error-<case>.json`. Errors that belong to no method (parse error, invalid request, unknown method) are named `rpc.error-<case>.json`. JSON-RPC reserves the `rpc.` prefix for method names, so it cannot clash with a real method.
6. **What the tests check, on both sides:**
   - every fixture round-trips unchanged (JSON equality, key order ignored)
   - request fixtures carry their method's name, `.response` fixtures hold a result and `.error-*` fixtures hold an error
   - every registered method has a request and a response fixture
   - every fixture belongs to a registered method or to `rpc`
   - the `ErrorCode` enum matches `protocol/error-codes.json`, the shared catalogue (name → code)
7. **Rust crate layout.** The `leia` package has a library target (`src/lib.rs`: `cli`, `protocol`) and the binary (`src/main.rs`). Public library items don't count as dead code, so the protocol types can land before the client uses them.

## Alternatives considered

- **Polymorphic params via Jackson's `EXTERNAL_PROPERTY` type id on `method`.** Parses requests in one pass, but serde has no matching construct that works with `deny_unknown_fields` (`flatten` is incompatible with it), and responses carry no method name anyway. Rejected.
- **`requireNonNull` in every record's compact constructor.** Works without a custom introspector, but it is easy to forget for one field, and nothing would catch that. Rejected.
- **`@JsonProperty(required = true)` on every required component.** Noisy, and just as easy to forget. Rejected.
- **A JSON Schema per DTO for the coverage check.** Stronger, but it is the codegen route that ADR 0001 rejected. Rejected.

## Consequences

- A new method means: a params and a result DTO on both sides, a `Methods` entry, a `RpcMethod` impl plus a `METHOD_NAMES` entry and a dispatch line in `fixture_tests.rs`, and at least a request and a response fixture. Leaving out any of these fails a test.
- Error codes are added in three places (the Java enum, the Rust enum and `error-codes.json`), and a test checks that they agree.
- Coverage is checked per method, not per DTO type. A nested DTO is covered through the fixtures of the methods that use it. An optional field that no fixture sets is not exercised, so fixtures should set every optional field at least once.
