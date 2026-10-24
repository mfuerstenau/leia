# ADR 0088 — SonarQube rules leia accepts

- **Status:** Proposed
- **Date:** 2026-10-18

## Context

The Sonar fix of 2026-10-18 (`88ea82a`, `8b696a4`) left 642 issues in 34 rules on the main code. Some rules were skipped on purpose, some findings are false positives, and some were never decided. Nothing is recorded in SonarQube, and the `sonar-check` skill runs it in a fresh container, so every scan counts them all again and the quality gate can't pass. On new code the gate needs reliability A, so no issue typed BUG at all, and a low debt ratio.

The first item of "SonarQube quality gate" in `docs/tasklist.md` decides, for every open rule, whether to fix it or to accept it. This ADR records the rules that are accepted, with the scope and the reason for each. How they are whitelisted, so that the decision lives in the repository and applies to a new container, is the second item; nothing is suppressed in code or in SonarQube yet.

## Decision

### Fixed

These were fixed in the code, without a change in behavior:

- **S3655** (Optional `get()` without a check Sonar can follow, BUG): each `x.a().isPresent()` … `x.a().get()` reads the `Optional` into a local first, or goes through `map`, `flatMap`, `filter` or `ifPresent`.
- **S2259** (null dereference, BUG, false positive): the `equals` of the More-Like-This queries binds `other` with `instanceof` instead of casting it; `IndexParts.open` keeps the failures in a list.
- **S3039** (string bounds, BUG, false positive): `TextFormat.cut` drops the last code point by its `charCount`.
- **S2388**, **S3457**, **S5411**, **S1141**, **S127**, **S3398**, **S6326**: the inner-class `maxDoc()` calls name `super`, log messages are lambdas and run-time format patterns are named locals, boxed `Boolean`s are unboxed explicitly, nested `try`s are helper methods, the code-point loops are `while` loops, private helpers sit in the inner class that calls them, and regular expressions use `{3}`.

### Accepted

| Rule | Scope | Reason |
|---|---|---|
| S6878 record patterns | all | House style: a binding with accessor calls reads as well as a record pattern, and converting 269 sites is churn without a gain in behavior. A pattern could name every component, or skip one with `var _` or a typed `long _`, which Palantir 2.102.0 formats |
| S7475 unused record components in patterns | `…tui.check.Check` | The fix is a bare `_` as a component (`R(long id, _)`), which Palantir Java Format 2.102.0 can't format (`expected token: '_'`); `var _` and `case R _` it can |
| S1452 wildcard return types | leia-server `HandlerModule` | Dagger's `@IntoSet MethodHandler<?, ?>` and the `RpcMethod<?, ?>` registry need them |
| S108 empty block | leia-tui | `default -> {}` is the idiom of a key switch that ignores the other keys |
| S2094 empty class | leia-protocol, the empty `*Params` records and variants | Hard rule 3: every method has an explicit params record, also one without fields |
| S106 standard output | `Main`, `LeiaLog` | The CLI's own output (`--version`, `--download-runtimes`, the internal error), and the fallback for when the log can't be opened |
| S1181 catching `Error` | `LeiaBackend` | A `LinkageError` from a jar must still complete the call's future |
| S3077 `volatile` reference (BUG) | `ExtensionJars`, `JarChain`, `RuntimeFolders`, `Cancellation`, `Session` | Immutable values, written under a lock and published through `volatile`, which is correct as it is |
| S2143 `java.util.Date` | the date-range code of core and the runtimes | Lucene's `DateTools` takes a `Date` |
| S6218 array in a record (BUG) | the 13 records with an array component in runtime, core, server and tui | Data carriers that are never compared, hashed or used as keys (checked with a grep for `equals`, `Set` and `Map` keys and `distinct`). One that ever is gets its own `equals` and `hashCode` |
| S2975, S1182 `clone` | `CheckDirectory`, `Lucene3/5/7/9Check` | Lucene's `IndexInput.clone` contract, which a wrapping input has to keep |
| S2095 resource not closed (BUG, false positive) | the 12 sites | Ownership passes to the caller, or to a part or open index that is closed elsewhere |
| S2583 unreachable code (BUG, false positive) | `Lucene3/5/7/9Index` | `openIfChanged` returns null when nothing changed |
| S2386 public static mutable field | `RuntimeFolders.KNOWN` | A `List.copyOf`, which is immutable |
| S2326 unused type parameter | `DocValuesMatch`, `runtime.DocValuesSearch.Match`, `Commits.Fetched`, `widgets.Form.Event` | Each variant of the sealed interface uses it; the parameter ties them to one type, as `Event<Settings>` does |
| S6916 `when` guard | leia-tui | The hits are switches over enum or char constants, and `when` is allowed only on pattern labels |
| S135 several `break`/`continue` | the 6 loops | Rewriting them makes them harder to read |
| S107 too many parameters | the 10 methods | Separate options of a search, highlight or recall call; a record built only to carry them adds no meaning |
| S6539 monster class | `Documents`, `Search` | Screen classes that wire their own forms and popups |
| S3400 method returns a constant | `FieldsForm`, `ScoringForm` | Matches the `keys()` of the other forms |
| S110 inheritance depth | `Searcher` | It extends Lucene's class hierarchy |
| S1479 many `case`s | `Session` | The dispatch over the sealed `Action` |
| S6474 dependency verification | the build | Accepted for now; a backlog item asks for an ADR on Gradle dependency verification |

After the fixes, the main code has 524 open issues in 24 rules, all of them in this table.

### Mechanism

Left to the second item of the task list: how the accepted rules are recorded so that they persist in the repository and apply to a new container.

## Alternatives considered

- **Fixing everything.** S7475 can't be fixed while Palantir can't format a bare `_`, and S6878 would rewrite 269 sites for style alone; S1452, S2094 and S2975 are required by Dagger, hard rule 3 and Lucene; the BUG false positives would need code that is worse to read. Rejected.
- **Accepting S3655 as a false positive.** Most of its sites are correct, but 73 BUG findings keep the gate's reliability at E, and the rewrites are small. Rejected.
